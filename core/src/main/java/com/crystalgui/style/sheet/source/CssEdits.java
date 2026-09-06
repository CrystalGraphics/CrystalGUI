package com.crystalgui.style.sheet.source;

import javax.annotation.Nullable;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextRange;

/**
 * The five ways a stylesheet's text is changed, each as a {@link ChangeSet}.
 *
 * <pre>{@code
 * CssSourceModel model = CssSourceModel.parse(text);
 * ChangeSet edit = CssEdits.replaceValue(model, declaration, "18%");
 * buffer.apply(edit);          // the TextEditor showing this sheet sees it land
 * }</pre>
 *
 * <p><b>Every builder operation is one of these five</b> — insert a rule, delete a rule, insert a
 * declaration, replace a declaration's value, delete a declaration — and each is a text edit at a
 * recorded position rather than a re-serialisation of a parsed sheet. That is the whole difference: a
 * sheet round-tripped through the parser comes back reformatted with its comments gone, so an inspector
 * that "just wrote the file" would silently reformat a file somebody else maintains. These touch the
 * bytes they name and nothing else.</p>
 *
 * <p>Each returns a change set against the model's own source, so applying it to a different text is a
 * caller error — {@link CssSourceModel#source()} is what it was measured from.</p>
 */
public final class CssEdits {

    private CssEdits() {
    }

    /**
     * Replaces a declaration's value, leaving the property, the whitespace and any {@code !important}
     * placement exactly as written.
     */
    public static ChangeSet replaceValue(CssSourceModel model, CssSourceModel.Declaration declaration,
                                         String value) {
        TextRange range = declaration.valueRange();
        return ChangeSet.of(model.source().length(), new Change(range.start(), range.end(), value));
    }

    /**
     * Adds a declaration at the end of a rule's body.
     *
     * <p>Indented from the rule's own opening line and terminated, so the result reads like the file it
     * joined rather than like something a tool appended.</p>
     */
    public static ChangeSet insertDeclaration(CssSourceModel model, CssSourceModel.Rule rule,
                                              String property, String value) {
        String source = model.source();
        int at = insertionPointIn(model, rule);
        String indent = indentOf(source, rule);
        String text = needsSeparator(source, at) ? "\n" + indent + property + ": " + value + ";"
                : indent + property + ": " + value + ";\n";
        return ChangeSet.of(source.length(), Change.insert(at, text));
    }

    /**
     * Removes a declaration, and the line it sat on when it had one to itself.
     *
     * <p>Taking the text alone would leave the indent and the newline behind — a blank line inside the
     * rule where a declaration used to be, which accumulates one per edit.</p>
     */
    public static ChangeSet deleteDeclaration(CssSourceModel model,
                                              CssSourceModel.Declaration declaration) {
        String source = model.source();
        int from = declaration.range().start();
        int to = declaration.range().end();
        while (from > 0 && isBlank(source.charAt(from - 1)) && source.charAt(from - 1) != '\n') from--;
        if (to < source.length() && source.charAt(to) == ';') to++;
        if (from > 0 && source.charAt(from - 1) == '\n' && to < source.length()
                && source.charAt(to) == '\n') {
            to++;
        }
        return ChangeSet.of(source.length(), Change.delete(from, to));
    }

    /**
     * Adds a rule at the end of the sheet.
     *
     * <p>Appended rather than placed near anything it resembles: cascade order is source order here, so
     * where a rule goes decides whether it wins, and the end is the only position that means "after
     * everything I can see".</p>
     */
    public static ChangeSet insertRule(CssSourceModel model, String selector, String body) {
        String source = model.source();
        String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
        String text = separator + "\n" + selector + " {\n    " + body + "\n}\n";
        return ChangeSet.of(source.length(), Change.insert(source.length(), text));
    }

    /**
     * Removes a whole rule.
     *
     * <p><b>The comment above it is left alone.</b> A rule's range begins at its first selector, so a
     * comment explaining it is not inside the range and is not taken — which is right: the comment may
     * describe the section rather than the rule, and deleting somebody's note because it sat above the
     * thing you deleted is not recoverable from the diff.</p>
     */
    public static ChangeSet deleteRule(CssSourceModel model, CssSourceModel.Rule rule) {
        String source = model.source();
        int from = rule.range().start();
        int to = rule.range().end();
        while (from > 0 && isBlank(source.charAt(from - 1)) && source.charAt(from - 1) != '\n') from--;
        if (to < source.length() && source.charAt(to) == '\n') to++;
        return ChangeSet.of(source.length(), Change.delete(from, to));
    }

    /** Just inside the rule's closing brace. */
    private static int insertionPointIn(CssSourceModel model, CssSourceModel.Rule rule) {
        String source = model.source();
        int at = rule.range().end() - 1;               // the closing brace
        while (at > rule.range().start() && isBlank(source.charAt(at - 1))) at--;
        return at;
    }

    /** Whether the text before {@code at} already ends a declaration. */
    private static boolean needsSeparator(String source, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '\n') return false;
            if (!isBlank(c)) return true;
        }
        return false;
    }

    /** The indent the rule's declarations use, or four spaces when it has none to copy. */
    private static String indentOf(String source, CssSourceModel.Rule rule) {
        CssSourceModel.Declaration first = firstDeclaration(rule);
        if (first == null) return "    ";
        int at = first.range().start();
        int lineStart = at;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
        String indent = source.substring(lineStart, at);
        return indent.isBlank() ? indent : "    ";
    }

    @Nullable
    private static CssSourceModel.Declaration firstDeclaration(CssSourceModel.Rule rule) {
        return rule.declarations().isEmpty() ? null : rule.declarations().get(0);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
