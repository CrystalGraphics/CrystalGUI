package com.crystalgui.text.syntax;

import java.util.List;

import javax.annotation.Nullable;

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
 * <h3>Brackets are stated once</h3>
 * <p>This used to carry the same facts three times — a map of auto-close pairs, a list of indent openers
 * and a list of indent closers — so an opening brace appeared in two of them and a closing brace in two
 * others, with nothing keeping them in step. Two of those lists were read by <em>nobody</em>: speculative
 * data for an auto-indent that has not been written, carried by every language and free to drift out of
 * agreement with the pairs beside it.</p>
 *
 * <p>A {@link BracketPair} states the whole thing once — what closes what, and whether the pair opens a
 * block. Quotes are pairs that do not indent; braces are pairs that do. Everything else is derived, so
 * there is no second copy to get wrong.</p>
 */
public record Language(
        String name,
        @Nullable String lineComment,
        @Nullable String blockCommentStart,
        @Nullable String blockCommentEnd,
        List<BracketPair> brackets) {

    /**
     * One pair of characters the editor treats as belonging together.
     *
     * @param indents whether the pair opens a block — true for braces and parentheses, false for quotes.
     *                An auto-indent consults this, and stating it on the pair is what stops it becoming a
     *                second list that disagrees with this one.
     */
    public record BracketPair(char open, char close, boolean indents) {

        /** A pair whose two characters are the same, as quotes are. */
        public boolean selfClosing() {
            return open == close;
        }
    }

    public Language {
        brackets = List.copyOf(brackets);
    }

    /** No comments, no pairs — plain text, where every editing aid should stay out of the way. */
    public static final Language PLAIN = new Language("plain", null, null, null, List.of());

    /**
     * Whether typing {@code c} should open a completion list — §18.1's trigger characters.
     *
     * <p>Derived rather than declared, because this is a <b>record</b> and a sixth component would have to
     * be supplied at every construction site including a caller's own custom language — which is how a
     * field ends up defaulted to the empty set everywhere except the two places somebody remembered.</p>
     *
     * <p>Only {@code .} today, and only for a language that has punctuation at all. It is the one trigger
     * §18.1 names and the one every reference implementation agrees on. {@code ::}, {@code ->} and
     * {@code @} are real triggers in real editors and are deliberately absent: each needs a provider that
     * answers them <em>differently</em> from a plain member access, and adding the trigger before the answer
     * exists produces a popup listing the wrong things rather than no popup at all.</p>
     */
    public boolean isCompletionTrigger(char c) {
        return c == '.' && !brackets.isEmpty();
    }

    /** C-family: Java, GLSL, C, and anything close enough to share the punctuation. */
    public static Language cFamily(String name) {
        return new Language(name, "//", "/*", "*/", List.of(
                new BracketPair('(', ')', true),
                new BracketPair('[', ']', true),
                new BracketPair('{', '}', true),
                new BracketPair('"', '"', false),
                new BracketPair('\'', '\'', false)));
    }

    /**
     * Java.
     *
     * <p>A constant rather than a factory, so two references to the language are the same object —
     * {@link #PLAIN} already was one while these two were not, which made {@code assertSame} answer
     * differently depending on which language a test picked.</p>
     */
    public static final Language JAVA = cFamily("java");

    /** GLSL — the shader graph's language, and the reason any of this exists. */
    public static final Language GLSL = cFamily("glsl");

    /** @see #JAVA */
    public static Language java() {
        return JAVA;
    }

    /** @see #GLSL */
    public static Language glsl() {
        return GLSL;
    }

    public boolean hasLineComment() {
        return lineComment != null && !lineComment.isEmpty();
    }

    public boolean hasBlockComment() {
        return blockCommentStart != null && !blockCommentStart.isEmpty()
                && blockCommentEnd != null && !blockCommentEnd.isEmpty();
    }

    /** The closing character for {@code opener}, or {@code null} if it does not open anything. */
    @Nullable
    public Character closerFor(char opener) {
        for (BracketPair pair : brackets) {
            if (pair.open() == opener) return pair.close();
        }
        return null;
    }

    /** Whether {@code c} closes a pair — including the quotes, which close themselves. */
    public boolean isCloser(char c) {
        for (BracketPair pair : brackets) {
            if (pair.close() == c) return true;
        }
        return false;
    }

    /**
     * Whether a pair's two characters are the same, as they are for quotes.
     *
     * <p>Worth its own question because self-closing pairs need different handling everywhere: there is no
     * way to tell an opening quote from a closing one by looking at it, so auto-closing has to consider
     * what is already on the line rather than just the character typed.</p>
     */
    public boolean isSelfClosing(char c) {
        for (BracketPair pair : brackets) {
            if (pair.open() == c) return pair.selfClosing();
        }
        return false;
    }

    /** Whether typing {@code c} opens a block the next line should be indented inside. */
    public boolean opensIndent(char c) {
        for (BracketPair pair : brackets) {
            if (pair.open() == c && pair.indents()) return true;
        }
        return false;
    }

    /** Whether {@code c} closes such a block, so the line carrying it should be outdented. */
    public boolean closesIndent(char c) {
        for (BracketPair pair : brackets) {
            if (pair.close() == c && pair.indents()) return true;
        }
        return false;
    }
}
