package com.crystalgui.style.sheet.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgui.text.TextRange;

/**
 * A stylesheet as TEXT — where every rule, selector and declaration actually is.
 *
 * <pre>{@code
 * CssSourceModel model = CssSourceModel.parse(sheetText);
 * for (CssSourceModel.Rule rule : model.rules()) {
 *     System.out.println(rule.sourceOrder() + " " + model.textOf(rule.range()));
 * }
 * }</pre>
 *
 * <p>{@link com.crystalgui.style.sheet.StyleSheet#parse} answers what a sheet MEANS and throws the text
 * away: it strips comments first, which shifts every offset after the first one, and it keeps no
 * positions at all. So nothing could say which line a rule came from — the question an inspector asks
 * first and a write-back cannot work without.</p>
 *
 * <h3>Comments are masked, not stripped</h3>
 *
 * <p>They are replaced by spaces of the same length before the rule scan runs. Removing them is what the
 * cascade does and is why it loses positions; blanking them keeps every offset exact while making the
 * patterns step over comment content — a {@code /* } inside a comment cannot open a rule, and a rule's
 * range still slices the original text back byte for byte.</p>
 *
 * <h3>The same grammar the cascade uses</h3>
 *
 * <p>Deliberately the same two patterns {@code DeclarationParser} matches with, so this model describes
 * the sheet the engine actually parsed rather than a second opinion about it. It inherits their limits:
 * no nesting, and a brace inside a string would end a block for both. If those ever change they change
 * in one place, and this follows.</p>
 *
 * <p>READ ONLY. The five editing operations are L3.10; this half exists so an inspector can show where a
 * rule lives before anything can move it.</p>
 */
public final class CssSourceModel {

    /** The cascade's own rule grammar: a selector list, then a brace-delimited body. */
    private static final Pattern RULE = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");

    /** The cascade's own declaration grammar. */
    private static final Pattern DECLARATION = Pattern.compile("(?m)\\s*([\\w-]+)\\s*:\\s*([^;]+)\\s*;?");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    private static final Pattern IMPORTANT = Pattern.compile("(?i)!\\s*important\\s*$");

    private final String source;

    private final List<Rule> rules;

    private final List<Comment> comments;

    private CssSourceModel(String source, List<Rule> rules, List<Comment> comments) {
        this.source = source;
        this.rules = List.copyOf(rules);
        this.comments = List.copyOf(comments);
    }

    /** One comment, kept: a write-back has to put the text back with them still in it. */
    public record Comment(String text, TextRange range) {
    }

    /** One selector out of a rule's list — {@code a, b} is two, each with its own range. */
    public record Selector(String text, TextRange range) {
    }

    /**
     * One declaration, with the property and the value separately placed.
     *
     * <p>Three ranges rather than one because an edit touches one of them: changing a value must not
     * rewrite the property, and neither may disturb the whitespace between them.</p>
     */
    public record Declaration(String property, String value, boolean important,
                              TextRange range, TextRange propertyRange, TextRange valueRange) {
    }

    /**
     * One rule as written.
     *
     * @param sourceOrder what {@code StyleSheet.parse} numbered this rule, or <b>-1</b> when the cascade
     *                    skipped it — an empty selector list, or a body it read no declaration out of.
     *                    Every {@code StyleRule} carries this number, so it is how a matched rule is
     *                    traced back to its text.
     */
    public record Rule(int sourceOrder, TextRange range, TextRange selectorsRange, TextRange bodyRange,
                       List<Selector> selectors, List<Declaration> declarations) {
    }

    /**
     * Reads {@code source} positionally. Never throws: text that matches nothing yields no rules, which
     * is what an inspector should show for a sheet it cannot make sense of.
     */
    public static CssSourceModel parse(String source) {
        if (source == null || source.isEmpty()) return new CssSourceModel("", List.of(), List.of());

        List<Comment> comments = new ArrayList<>();
        String masked = mask(source, comments);

        List<Rule> rules = new ArrayList<>();
        int sourceOrder = 0;
        Matcher ruleMatcher = RULE.matcher(masked);
        while (ruleMatcher.find()) {
            TextRange selectors = range(ruleMatcher.start(1), ruleMatcher.end(1));
            List<Selector> parsedSelectors = selectors == null
                    ? List.of() : selectorsIn(source, masked, selectors);
            List<Declaration> parsedDeclarations = ruleMatcher.end(2) > ruleMatcher.start(2)
                    ? declarationsIn(source, masked, ruleMatcher.start(2), ruleMatcher.end(2))
                    : List.of();

            // THE CASCADE'S OWN SKIPS, mirrored so the numbering lines up. StyleSheet.parse drops a rule
            // with no selector and a rule it read no declaration from, and does not spend a sourceOrder
            // on either -- so a model that numbered them anyway would map every later rule to the wrong
            // text, which is worse than not mapping at all.
            boolean kept = !parsedSelectors.isEmpty() && !parsedDeclarations.isEmpty();
            // FROM THE FIRST SELECTOR, not from where the pattern started matching. `[^{}]+` swallows
            // everything since the previous brace -- the blank line, the indent, and any comment masked
            // above it -- so a rule's own range would have begun somewhere in the rule before it, and a
            // write-back that replaced it would have eaten the gap.
            int start = parsedSelectors.isEmpty()
                    ? ruleMatcher.start() : parsedSelectors.get(0).range().start();
            TextRange whole = TextRange.of(start, ruleMatcher.end());
            TextRange selectorList = parsedSelectors.isEmpty() ? whole
                    : TextRange.of(start, parsedSelectors.get(parsedSelectors.size() - 1).range().end());
            rules.add(new Rule(kept ? sourceOrder : -1, whole, selectorList,
                    ruleMatcher.end(2) > ruleMatcher.start(2)
                            ? TextRange.of(ruleMatcher.start(2), ruleMatcher.end(2)) : whole,
                    parsedSelectors, parsedDeclarations));
            if (kept) sourceOrder++;
        }
        return new CssSourceModel(source, rules, comments);
    }

    /** Comments replaced by spaces of the SAME LENGTH, so every later offset is still exact. */
    private static String mask(String source, List<Comment> into) {
        StringBuilder masked = new StringBuilder(source);
        Matcher comment = BLOCK_COMMENT.matcher(source);
        while (comment.find()) {
            into.add(new Comment(comment.group(), TextRange.of(comment.start(), comment.end())));
            for (int i = comment.start(); i < comment.end(); i++) {
                char at = masked.charAt(i);
                // Newlines survive, so a line number counted off this text is still the real one.
                if (at != '\n' && at != '\r') masked.setCharAt(i, ' ');
            }
        }
        return masked.toString();
    }

    private static List<Selector> selectorsIn(String source, String masked, TextRange list) {
        List<Selector> found = new ArrayList<>();
        int at = list.start();
        while (at < list.end()) {
            int comma = masked.indexOf(',', at);
            int end = comma < 0 || comma > list.end() ? list.end() : comma;
            TextRange trimmed = trim(masked, at, end);
            if (trimmed != null) found.add(new Selector(slice(source, trimmed), trimmed));
            at = end + 1;
        }
        return found;
    }

    private static List<Declaration> declarationsIn(String source, String masked, int from, int to) {
        List<Declaration> found = new ArrayList<>();
        Matcher declaration = DECLARATION.matcher(masked).region(from, to);
        while (declaration.find()) {
            TextRange property = range(declaration.start(1), declaration.end(1));
            TextRange value = trim(masked, declaration.start(2), declaration.end(2));
            if (property == null || value == null) continue;
            String rawValue = slice(source, value);
            boolean important = IMPORTANT.matcher(rawValue).find();
            found.add(new Declaration(slice(source, property), rawValue, important,
                    TextRange.of(declaration.start(), declaration.end()), property, value));
        }
        return found;
    }

    /** The range with surrounding whitespace removed, or null when there is nothing but whitespace. */
    @Nullable
    private static TextRange trim(String text, int start, int end) {
        int from = start;
        int to = end;
        while (from < to && Character.isWhitespace(text.charAt(from))) from++;
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
        return to > from ? TextRange.of(from, to) : null;
    }

    @Nullable
    private static TextRange range(int start, int end) {
        return end > start ? TextRange.of(start, end) : null;
    }

    private static String slice(String source, TextRange range) {
        return source.substring(range.start(), range.end());
    }

    /** The text a range covers, out of the source this was parsed from. */
    public String textOf(TextRange range) {
        return source.substring(range.start(), range.end());
    }

    /** The rule the cascade numbered {@code sourceOrder}, or null — how a matched {@code StyleRule} is
     * traced back to its text. */
    @Nullable
    public Rule ruleAt(int sourceOrder) {
        for (Rule rule : rules) {
            if (rule.sourceOrder() == sourceOrder) return rule;
        }
        return null;
    }

    public String source() {
        return source;
    }

    /** Every rule as written, in source order — including the ones the cascade skipped. */
    public List<Rule> rules() {
        return rules;
    }

    /** Every comment, in source order. */
    public List<Comment> comments() {
        return comments;
    }
}
