package com.crystalgui.text.lang;

import com.crystalgui.text.syntax.SyntaxToken;

import java.util.List;

/**
 * A symbol's declaration as the engine would write it — {@code public void println(@Nullable String x)}
 * — with the token structure that colours it.
 *
 * <h3>Why this is structured, when both reference IDEs ship a string</h3>
 *
 * <p>LSP puts the signature in a markdown fenced code block and lets the client re-highlight it;
 * IntelliJ returns HTML and offers {@code HtmlSyntaxInfoUtil} to lex code samples into coloured spans.
 * Neither is a considered preference for text over structure — both are <b>constraints we do not
 * share</b>:</p>
 *
 * <ul>
 *   <li>LSP has a <b>process boundary and a JSON wire</b> shared by a hundred servers in a dozen
 *       languages, so the lowest common denominator wins. Nothing here crosses a process.</li>
 *   <li>IntelliJ's render surface <b>is</b> an HTML component, so HTML is its natural intermediate.
 *       Ours is {@link com.crystalgui.ui.elements.UIText}, which takes ranges natively — a string would
 *       have to be turned back into ranges before anything could be drawn.</li>
 * </ul>
 *
 * <p>And IntelliJ's own definition line is not lexed: the {@code ↗} arrows beside {@code @Nullable} and
 * {@code @Contract} are <b>navigable links to those types</b>, which no lexer can produce and only a
 * binding can. {@code HtmlSyntaxInfoUtil} is for code samples inside a doc body, not for the signature.</p>
 *
 * <p>So the alternative here would be: the engine <em>has</em> the structure, flattens it to text, and the
 * widget re-derives it with a lexer strictly worse than the parser colouring the same code two pixels
 * away — a lossy round trip, a visible quality gap against the editor, and no path to links ever.</p>
 *
 * <h3>{@link SyntaxToken} rather than a type of its own</h3>
 *
 * <p>The tokens speak the <b>same capture vocabulary</b> as the grammar and the semantic provider
 * (§10.1), so a consumer's rendering path is the one it already has for a line of code — the popup does
 * exactly what {@code ensureRowSyntax} does. A parallel type would be a second vocabulary to keep in step
 * with the schemes, and the first divergence would show up as one word in the wrong colour.</p>
 *
 * <p>It is language-neutral by construction: text, a capture name and a range say nothing about Java.
 * GLSL's {@code layout(location = 0) in vec3 x} and a shader-graph node's port declaration are the same
 * shape, which is the whole reason this is not a pile of fields named after Java concepts.</p>
 *
 * <h3>Populated by {@link Resolver#resolveAt}, and deliberately not by {@link Resolver#membersOf}</h3>
 *
 * <p>{@code resolveAt} answers about <em>one</em> symbol and is what hover and go-to-definition call;
 * {@code membersOf} answers with hundreds for a completion list, which renders a label and a detail
 * column and would never read this. Same record, populated by the query that needs it — so completion
 * pays nothing for a field it does not draw.</p>
 *
 * @param text   the declaration as it should read, on one line unless the engine broke it itself
 * @param tokens capture-named ranges into {@code text}; may be empty, which draws as plain text
 */
public record Signature(String text, List<SyntaxToken> tokens) {

    public Signature {
        if (text == null) text = "";
        tokens = tokens == null || tokens.isEmpty() ? List.of() : List.copyOf(tokens);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /**
     * A builder, because every engine builds this the same way: append a word, name what it was.
     *
     * <p>Worth having rather than leaving each engine to track offsets by hand — the offsets are into a
     * string being built, so an off-by-one is a colour landing on the neighbouring word, which is
     * exactly the class of error nobody notices in review.</p>
     */
    public static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final java.util.ArrayList<SyntaxToken> tokens = new java.util.ArrayList<>();

        /** Appends {@code word} captured as {@code captureName}, then a single space. */
        public Builder word(String word, String captureName) {
            append(word, captureName);
            text.append(' ');
            return this;
        }

        /** Appends {@code word} with no trailing space — for punctuation and tight joins. */
        public Builder append(String word, String captureName) {
            if (word == null || word.isEmpty()) return this;
            int start = text.length();
            text.append(word);
            if (captureName != null && !captureName.isEmpty()) {
                tokens.add(new SyntaxToken(start, text.length(), captureName));
            }
            return this;
        }

        /** Unnamed text — punctuation, spaces, anything the scheme has no opinion about. */
        public Builder raw(String literal) {
            return append(literal, null);
        }

        /**
         * Ends the current line.
         *
         * <p>Breaks are the <b>engine's</b> to place, because they belong at semantic points — after an
         * annotation, between parameters, before a long initializer — and only the engine knows where
         * those are. It cannot know how wide the box is, which is the other half of the decision, so it
         * breaks on the declaration's own length instead: a threshold in characters, applied to something
         * whose shape it understands. Both reference IDEs break a long signature exactly here rather than
         * letting it reflow at whatever word happens to reach the edge.</p>
         *
         * <p>The trailing space {@link #word} leaves is dropped, so a break never leaves one dangling at
         * the end of a line where it would widen the box by a space.</p>
         */
        public Builder newline() {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') {
                text.setLength(end - 1);
                end--;
            }
            text.append('\n');
            return this;
        }

        /** One level of continuation indent, matching what both references use. */
        public Builder indent() {
            return raw("    ");
        }

        public boolean isEmpty() {
            return text.length() == 0;
        }

        /** How much has been written — what a renderer checks its budget against. */
        public int length() {
            return text.length();
        }

        /**
         * Captures a range that has <b>already been written</b>, rather than appending named text.
         *
         * <p>For the case where the text comes from one place and the colours from another: an
         * initializer is quoted verbatim from the source and its captures are derived from the AST, so
         * the ranges are known only after the whole slice is in. Appending word by word cannot express
         * that without re-deriving the spacing, which is the thing quoting the source exists to avoid.</p>
         *
         * <p>Out-of-range or empty ranges are dropped rather than throwing: they mean a node the slice
         * was truncated past, which is ordinary once a budget is involved.</p>
         */
        public Builder tokenAt(int start, int end, String captureName) {
            if (captureName == null || captureName.isEmpty()) return this;
            int from = Math.max(0, start);
            int to = Math.min(text.length(), end);
            if (to > from) tokens.add(new SyntaxToken(from, to, captureName));
            return this;
        }

        public Signature build() {
            // Trailing space is an artefact of `word`, and it would widen the box by a space for every
            // declaration ending in one. Trimmed here rather than at each call site.
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') end--;
            String finished = text.substring(0, end);
            java.util.List<SyntaxToken> kept = new java.util.ArrayList<>(tokens.size());
            for (SyntaxToken token : tokens) {
                if (token.start() < finished.length()) {
                    kept.add(token.end() <= finished.length() ? token
                            : new SyntaxToken(token.start(), finished.length(), token.name()));
                }
            }
            return new Signature(finished, kept);
        }
    }
}
