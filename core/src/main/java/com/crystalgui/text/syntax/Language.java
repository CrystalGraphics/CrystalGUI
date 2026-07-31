package com.crystalgui.text.syntax;

import java.util.List;
import java.util.Map;

/**
 * What the editor needs to know about a language in order to <b>edit</b> it — which is a different
 * question from how to colour it.
 *
 * <h3>Why this is not part of {@link SyntaxTokenizer}</h3>
 * <p>A tokenizer answers "what is this span?". Editing asks "what starts a comment here", "what closes
 * this bracket", "does this line open a block". A tree-sitter grammar does not describe any of those: its
 * {@code highlights.scm} names captures, and there is no query that says {@code //} begins a comment in
 * Java and {@code #} does in Python. Folding those into the tokenizer would make the backend responsible
 * for knowledge a grammar does not carry, and would leave the built-in lexer — the fallback — unable to
 * support comment toggling at all.</p>
 *
 * <p>So it is a small value type, and a language is a tokenizer <em>plus</em> one of these.</p>
 */
public record Language(
        String name,
        String lineComment,
        String blockCommentStart,
        String blockCommentEnd,
        Map<Character, Character> autoClosePairs,
        List<Character> indentOpeners,
        List<Character> indentClosers) {

    /** No comments, no pairs — plain text, where every editing aid should stay out of the way. */
    public static final Language PLAIN = new Language("plain", null, null, null,
            Map.of(), List.of(), List.of());

    /** C-family: Java, GLSL, C, and anything close enough to share the punctuation. */
    public static Language cFamily(String name) {
        return new Language(name, "//", "/*", "*/",
                Map.of('(', ')', '[', ']', '{', '}', '"', '"', '\'', '\''),
                List.of('{', '(', '['),
                List.of('}', ')', ']'));
    }

    public static Language java() {
        return cFamily("java");
    }

    public static Language glsl() {
        return cFamily("glsl");
    }

    public boolean hasLineComment() {
        return lineComment != null && !lineComment.isEmpty();
    }

    public boolean hasBlockComment() {
        return blockCommentStart != null && !blockCommentStart.isEmpty()
                && blockCommentEnd != null && !blockCommentEnd.isEmpty();
    }

    /** The closing character for {@code opener}, or {@code null} if it does not open anything. */
    public Character closerFor(char opener) {
        return autoClosePairs.get(opener);
    }

    /** Whether {@code c} closes a pair — including the quotes, which close themselves. */
    public boolean isCloser(char c) {
        return autoClosePairs.containsValue(c);
    }

    /**
     * Whether a pair's two characters are the same, as they are for quotes.
     *
     * <p>Worth its own question because self-closing pairs need different handling everywhere: there is no
     * way to tell an opening quote from a closing one by looking at it, so auto-closing has to consider
     * what is already on the line rather than just the character typed.</p>
     */
    public boolean isSelfClosing(char c) {
        Character closer = autoClosePairs.get(c);
        return closer != null && closer == c;
    }
}
