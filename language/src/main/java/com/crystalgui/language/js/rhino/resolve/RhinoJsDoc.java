package com.crystalgui.language.js.rhino.resolve;

import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.Comment;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.Token;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place JavaScript writes a type down — JSDoc, parsed to the four tags that carry one.
 *
 * <h3>A tag grammar, not a documentation renderer</h3>
 *
 * <p>{@code @param {T} name}, {@code @returns {T}}, {@code @type {T}}, {@code @deprecated}. Those are the
 * ones a resolver, a completion list and a hover popup can act on; the rest of a doc comment is prose and
 * is kept whole as the description. Markdown, {@code @link}, {@code @example} and the other thirty tags
 * are deliberately untouched — a popup that renders them is M13's, and inventing half a renderer here
 * would be a second one to keep in step.</p>
 *
 * <h3>Found by position, not by asking the node</h3>
 *
 * <p>Rhino's {@code setRecordingLocalJsDocComments} attaches a comment to <em>some</em> declarations, and
 * which ones differs by band — the accessor is there on both, and what it answers for a
 * {@code var}/{@code let}/{@code const} is exactly the sort of thing the {@code ObjectProperty} episode
 * says not to assume. So the node is asked first and, when it has nothing, the comment list is searched
 * for the last JSDoc block that <b>ends before the declaration with only whitespace between them</b> —
 * which is what "the comment above it" means, is band-independent, and is the same rule every JSDoc tool
 * uses.</p>
 */
final class RhinoJsDoc {

    /** No comment — every field empty rather than a null to check at each use. */
    static final RhinoJsDoc NONE =
            new RhinoJsDoc("", Map.of(), null, null, false, "", List.of());

    /** One block tag, in the order the author wrote it, with its own lines intact. */
    record Tag(String name, String text) {
    }

    private final String description;
    private final Map<String, String> paramTypes;
    @Nullable private final String returnType;
    @Nullable private final String declaredType;
    private final boolean deprecated;
    /**
     * The description as the author laid it out — the source a renderer reads.
     *
     * <p>Separate from {@link #description} because the two want opposite things. A TYPE grammar wants
     * one flat run: whitespace collapsed, lines joined, ready to be a container suffix or a completion
     * row. Markdown wants the layout kept, because in it the layout IS the syntax — a blank line ends a
     * paragraph, two spaces of indent nest a list, and three backticks on their own line open a code
     * block. Collapsing them turns a documented function's example into one long line of prose.</p>
     */
    private final String markdown;
    /** Every block tag, in source order. @see #markdown for why the text is not collapsed. */
    private final List<Tag> tags;

    private RhinoJsDoc(String description, Map<String, String> paramTypes,
                       @Nullable String returnType, @Nullable String declaredType, boolean deprecated,
                       String markdown, List<Tag> tags) {
        this.description = description;
        this.paramTypes = paramTypes;
        this.returnType = returnType;
        this.declaredType = declaredType;
        this.deprecated = deprecated;
        this.markdown = markdown;
        this.tags = tags;
    }

    /** The description as written, for a renderer. @see #markdown */
    String markdown() {
        return markdown;
    }

    /** Every block tag in source order. @see #tags */
    List<Tag> tags() {
        return tags;
    }

    String description() {
        return description;
    }

    /** The declared type of a parameter, or null. */
    @Nullable
    String paramType(String name) {
        return paramTypes.get(name);
    }

    /** {@code @type} for a variable, else {@code @returns} for a function — whichever the author wrote. */
    @Nullable
    String declaredType() {
        return declaredType != null ? declaredType : returnType;
    }

    @Nullable
    String returnType() {
        return returnType;
    }

    boolean isDeprecated() {
        return deprecated;
    }

    boolean isEmpty() {
        // AND ANY TAG AT ALL, because this stopped being only a type grammar. The four tags below are
        // the ones a RESOLVER can act on, and a comment carrying none of them used to be discarded as
        // saying nothing -- which was true when nothing else read it. A renderer reads every tag now, so
        // `/** @since 2.0 */` is a comment with something to say and was coming back as NONE.
        return this == NONE || (description.isEmpty() && paramTypes.isEmpty()
                && returnType == null && declaredType == null && !deprecated
                && markdown.isEmpty() && tags.isEmpty());
    }

    // ── Finding the comment ─────────────────────────────────────────────────────────────────────

    /**
     * The doc comment for a declaration at {@code offset}, from the node or from the text above it.
     *
     * <p>{@code root} may be null for a parse that produced no tree at all, which is a legitimate state
     * and answers {@link #NONE} rather than throwing.</p>
     */
    static RhinoJsDoc forDeclaration(@Nullable AstRoot root, @Nullable AstNode node, int offset,
                                     String source) {
        String text = attachedTo(node);
        if (text == null) text = precedingComment(root, statementStart(node, offset), source);
        return text == null ? NONE : parse(text);
    }

    /**
     * Where the declaration <em>statement</em> begins, which is what a comment sits above.
     *
     * <p>Not the name's own offset: {@code var label} puts the keyword between the comment and the
     * identifier, so measuring the gap to the name finds {@code var} in it and concludes the comment
     * belongs to something else. Every declaration in the language has this shape, so measuring to the
     * name would have found nothing anywhere — while looking correct on a fixture whose declaration
     * happened to be a bare function.</p>
     */
    private static int statementStart(@Nullable AstNode node, int fallback) {
        int start = fallback;
        for (AstNode at = node; at != null; at = at.getParent()) {
            // BY CLASS, never by a Token constant -- those are inlined and the bands renumbered them.
            // @see RhinoTokens
            if (at instanceof VariableDeclaration || at instanceof FunctionNode) {
                return at.getAbsolutePosition();
            }
            // AND STOP AT THE SCRIPT, or a declaration with no enclosing statement walks to the root and
            // reports offset 0 -- which would make the file's first comment everybody's documentation.
            if (at instanceof AstRoot) break;
            start = Math.min(start, at.getAbsolutePosition());
        }
        return start;
    }

    @Nullable
    private static String attachedTo(@Nullable AstNode node) {
        for (AstNode at = node; at != null; at = at.getParent()) {
            String doc = at.getJsDoc();
            if (doc != null && !doc.isEmpty()) return doc;
            // ONE DECLARATION UP AT MOST, and only through the shapes that wrap one: an initializer
            // inside a `var` statement, a name inside an initializer. Walking further would find the
            // comment on the enclosing FUNCTION and report it as every local's documentation.
            if (at instanceof VariableDeclaration || at instanceof FunctionNode) return null;
        }
        return null;
    }

    /**
     * The last JSDoc block above {@code offset} with nothing but whitespace between.
     *
     * <p>The "nothing but whitespace" test is what stops a file's header comment being read as the
     * documentation of whatever declaration happens to come first — which in this fixture would attach a
     * twenty-line milestone log to {@code MAX_RETRIES}.</p>
     */
    @Nullable
    private static String precedingComment(@Nullable AstRoot root, int offset, String source) {
        if (root == null || root.getComments() == null) return null;
        Comment best = null;
        for (Comment comment : root.getComments()) {
            int end = comment.getAbsolutePosition() + comment.getLength();
            if (end > offset) continue;
            if (comment.getCommentType() != Token.CommentType.JSDOC) continue;
            if (best == null || end > best.getAbsolutePosition() + best.getLength()) best = comment;
        }
        if (best == null) return null;
        int gapFrom = best.getAbsolutePosition() + best.getLength();
        for (int at = gapFrom; at < offset && at < source.length(); at++) {
            if (!Character.isWhitespace(source.charAt(at))) return null;
        }
        return best.getValue();
    }

    // ── The tag grammar ─────────────────────────────────────────────────────────────────────────

    /** Parses a raw {@code /** … *}{@code /} block. Visible for the test that pins the grammar. */
    static RhinoJsDoc parse(String raw) {
        if (raw == null || raw.isEmpty()) return NONE;
        StringBuilder description = new StringBuilder();
        StringBuilder source = new StringBuilder();
        List<Tag> collected = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        String returns = null;
        String declared = null;
        boolean deprecated = false;

        // SPLIT ON TAG BOUNDARIES, not on lines. `/** Text. @type {string} */` is the ordinary way to
        // write a one-line doc comment, and a line-based reader folds the tag into the description and
        // reports no type at all -- while every multi-line fixture passes, because there the tag does
        // start its line. The description is what precedes the first tag; a tag runs to the next one,
        // so its own text may wrap across lines.
        // TWICE OVER THE SAME SPLIT, on two strippings of one comment. The grammar reads the collapsed
        // form and a renderer reads the laid-out one; running the split twice is cheaper than teaching
        // every branch below which of the two it is looking at, and it keeps `markdown` exactly what the
        // author typed rather than something reassembled from collapsed pieces.
        List<String> laidOut = splitOnTags(strippedKeepingLayout(raw));
        for (String segment : laidOut) {
            String trimmed = segment.strip();
            if (!trimmed.startsWith("@")) {
                if (!trimmed.isEmpty()) {
                    if (source.length() > 0) source.append("\n\n");
                    source.append(trimEnd(segment));
                }
                continue;
            }
            String name = tagOf(trimmed);
            collected.add(new Tag(name, trimEnd(trimmed.substring(name.length() + 1)).stripLeading()));
        }

        List<String> segments = splitOnTags(stripped(raw));
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.startsWith("@")) {
                if (!trimmed.isEmpty()) {
                    if (description.length() > 0) description.append(' ');
                    description.append(collapse(trimmed));
                }
                continue;
            }
            String tag = tagOf(trimmed);
            String rest = collapse(trimmed.substring(tag.length() + 1).trim());
            switch (tag) {
                case "param":
                case "arg":
                case "argument": {
                    String type = braced(rest);
                    String name = nameAfterBrace(rest);
                    if (!name.isEmpty() && type != null) params.put(name, type);
                    break;
                }
                case "returns":
                case "return":
                    if (returns == null) returns = braced(rest);
                    break;
                case "type":
                    if (declared == null) declared = braced(rest);
                    break;
                case "deprecated":
                    deprecated = true;
                    break;
                default:
                    // EVERY OTHER TAG IS LEFT ALONE rather than folded into the description: `@see`,
                    // `@example` and `@license` are not prose about this symbol, and putting them in the
                    // one-line description is how a hover ends up quoting a licence header.
                    break;
            }
        }
        RhinoJsDoc parsed = new RhinoJsDoc(description.toString().trim(), params, returns, declared,
                deprecated, source.toString().strip(), List.copyOf(collected));
        return parsed.isEmpty() ? NONE : parsed;
    }

    /**
     * The description, then one segment per tag.
     *
     * <p>A tag begins at an {@code @} that starts the text or follows whitespace, and is followed by a
     * letter — which is what keeps an email address or a decorator inside a sentence from starting one.</p>
     */
    private static List<String> splitOnTags(String text) {
        List<String> segments = new ArrayList<>();
        int from = 0;
        for (int at = 0; at < text.length(); at++) {
            if (text.charAt(at) != '@') continue;
            if (at + 1 >= text.length() || !Character.isLetter(text.charAt(at + 1))) continue;
            if (at > 0 && !Character.isWhitespace(text.charAt(at - 1))) continue;
            if (at > from) segments.add(text.substring(from, at));
            from = at;
        }
        if (from < text.length()) segments.add(text.substring(from));
        return segments;
    }

    /** One line's worth of text out of however many the author used. */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Drops the delimiters and the leading star of each line — what every JSDoc tool does first. */
    private static String stripped(String raw) {
        String body = raw.trim();
        if (body.startsWith("/**")) body = body.substring(3);
        else if (body.startsWith("/*")) body = body.substring(2);
        if (body.endsWith("*/")) body = body.substring(0, body.length() - 2);
        StringBuilder out = new StringBuilder(body.length());
        for (String line : body.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*")) trimmed = trimmed.substring(1).trim();
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    /**
     * The comment's body with its LAYOUT intact — the ` * ` gone and nothing else.
     *
     * <p>{@link #stripped} trims each line, which is right for a grammar and destroys markdown: the
     * indent that nests a list and the one that opens a code block are both leading whitespace, and a
     * trim removes exactly those. Only the decoration is taken here — any leading whitespace, one
     * asterisk, and at most one space after it, which is the amount every doc-comment convention adds
     * and the amount every reader of one removes.</p>
     */
    private static String strippedKeepingLayout(String raw) {
        String body = raw.trim();
        if (body.startsWith("/**")) body = body.substring(3);
        else if (body.startsWith("/*")) body = body.substring(2);
        if (body.endsWith("*/")) body = body.substring(0, body.length() - 2);
        StringBuilder out = new StringBuilder(body.length());
        for (String line : body.split("\\R", -1)) {
            String kept = line;
            int at = 0;
            while (at < kept.length() && (kept.charAt(at) == ' ' || kept.charAt(at) == '\t')) at++;
            if (at < kept.length() && kept.charAt(at) == '*') {
                at++;
                if (at < kept.length() && kept.charAt(at) == ' ') at++;
                kept = kept.substring(at);
            }
            if (out.length() > 0) out.append('\n');
            out.append(kept);
        }
        return out.toString();
    }

    /** Trailing whitespace only — leading whitespace is markdown's own syntax. */
    private static String trimEnd(String text) {
        return text.stripTrailing();
    }

    private static String tagOf(String line) {
        int end = 1;
        while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
        return line.substring(1, end);
    }

    /** The {@code {T}} at the front of a tag's text, or null when the author wrote no type. */
    @Nullable
    private static String braced(String text) {
        if (!text.startsWith("{")) return null;
        int depth = 0;
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                String type = text.substring(1, at).trim();
                return type.isEmpty() ? null : type;
            }
        }
        return null;
    }

    /**
     * The parameter name after the braced type.
     *
     * <p>Square brackets are stripped: {@code @param {string} [name]} is JSDoc for an optional parameter,
     * and the name it is about is {@code name}. A default — {@code [name='x']} — is dropped with them.</p>
     */
    private static String nameAfterBrace(String text) {
        int at = 0;
        if (text.startsWith("{")) {
            int depth = 0;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
        }
        String rest = text.substring(Math.min(at, text.length())).trim();
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) end++;
        String name = rest.substring(0, end);
        if (name.startsWith("[") && name.length() > 1) {
            name = name.substring(1);
            int close = name.indexOf(']');
            int equals = name.indexOf('=');
            int cut = close < 0 ? name.length() : close;
            if (equals >= 0 && equals < cut) cut = equals;
            name = name.substring(0, cut);
        }
        return name;
    }
}
