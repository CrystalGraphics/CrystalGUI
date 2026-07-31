package com.crystalgui.text.syntax;

/**
 * One highlighted span: a half-open UTF-16 range and the name it was captured under.
 *
 * <p>The name is a <b>capture name</b>, matching tree-sitter's convention — {@code "keyword"},
 * {@code "string"}, {@code "function.builtin"} — and it is deliberately a string rather than an enum.
 * The set of names is a property of a grammar and its {@code highlights.scm}, not of this engine, and an
 * enum here would mean every new grammar needed a change to {@code core/} before it could say anything
 * this engine had not anticipated.</p>
 *
 * <p>Those names land in CSS as {@code ::highlight(keyword)} through the Custom Highlight API from
 * 6.1.1, so a theme styles syntax with the same mechanism it styles a search hit.</p>
 */
public record SyntaxToken(int start, int end, String name) {

    public SyntaxToken {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("bad token range " + start + ".." + end);
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a token needs a capture name");
        }
    }

    public int length() {
        return end - start;
    }

    /**
     * The general form of a dotted capture name, or {@code null} when there is none.
     *
     * <p>{@code "function.builtin"} falls back to {@code "function"} so a theme that has not named every
     * specialisation still colours it as a function. Without the fallback an unstyled capture renders as
     * plain text, which looks like the highlighter failing rather than the theme being incomplete.</p>
     */
    public String generalName() {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? null : name.substring(0, dot);
    }
}
