package com.crystalgui.text.markup;

/**
 * Markdown's inline syntax — the part inside a paragraph, a heading or a table cell.
 *
 * <h3>Code spans are read first, and that ordering is the whole of it</h3>
 *
 * <p>Everything between backticks is content: {@code `a * b`} is a multiplication and not emphasis,
 * {@code `__init__`} is a name and not bold. A pass that handled emphasis first would have eaten both
 * before the code span was ever seen, and the damage is invisible — the text still renders, in the wrong
 * face, with the markers gone.</p>
 *
 * <h3>Underscores do not emphasise inside a word</h3>
 *
 * <p>GFM's rule, and it matters more here than in prose generally: doc comments are full of
 * {@code READ_WRITE} and {@code snake_case}, and treating the inner underscores as emphasis turns a
 * constant into {@code READWRITE} with a slanted middle. Asterisks keep markdown's ordinary behaviour,
 * since {@code a*b*c} is not a shape identifiers take.</p>
 */
final class Inline {

    private Inline() {
    }

    static String render(String text) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);

            if (c == '`') {
                int close = text.indexOf('`', at + 1);
                if (close > at) {
                    out.append("<code>")
                       .append(Markdown.escape(text.substring(at + 1, close)))
                       .append("</code>");
                    at = close + 1;
                    continue;
                }
            }

            // AN INLINE TAG IS A REFERENCE, not markdown at all -- JSDoc borrows javadoc's spelling for
            // it, and it has to be recognised here or the braces reach the reader as text. `@linkcode`
            // and `@linkplain` differ only in face, which the popup's own link styling already decides.
            if (c == '{' && text.startsWith("{@", at)) {
                int close = text.indexOf('}', at);
                int space = text.indexOf(' ', at);
                if (close > at && space > at && space < close) {
                    String name = text.substring(at + 2, space);
                    if (name.equals("link") || name.equals("linkplain") || name.equals("linkcode")
                            || name.equals("tutorial")) {
                        out.append(link(text.substring(space + 1, close).trim()));
                        at = close + 1;
                        continue;
                    }
                }
            }

            if (c == '[') {
                int label = matching(text, at, '[', ']');
                if (label > at && label + 1 < text.length() && text.charAt(label + 1) == '(') {
                    int target = matching(text, label + 1, '(', ')');
                    if (target > label) {
                        out.append("<a href=\"")
                           .append(Markdown.escape(text.substring(label + 2, target).trim()))
                           .append("\">")
                           .append(render(text.substring(at + 1, label)))
                           .append("</a>");
                        at = target + 1;
                        continue;
                    }
                }
            }

            String wrapped = span(text, at, "~~", "<i>", "</i>");
            if (wrapped == null) wrapped = span(text, at, "**", "<b>", "</b>");
            if (wrapped == null) wrapped = span(text, at, "__", "<b>", "</b>");
            if (wrapped == null) wrapped = span(text, at, "*", "<i>", "</i>");
            if (wrapped == null && flanking(text, at)) wrapped = span(text, at, "_", "<i>", "</i>");
            if (wrapped != null) {
                out.append(wrapped);
                at += consumed;
                continue;
            }

            out.append(c);
            at++;
        }
        return out.toString();
    }

    /** How much of the input the last successful {@link #span} took. */
    private static int consumed;

    /**
     * One delimited run, rendered — or null when this position does not open one.
     *
     * <p>{@code ~~} is mapped to italic rather than dropped: {@code HighlightStyle} permits
     * {@code text-decoration-line}, but a strike-through has no markup tag in the set the parser reads,
     * and losing the marker entirely would say the opposite of what the author wrote. Italic at least
     * keeps "this is set apart".</p>
     */
    private static String span(String text, int at, String marker, String open, String close) {
        if (!text.startsWith(marker, at)) return null;
        int from = at + marker.length();
        if (from >= text.length() || text.charAt(from) == ' ') return null;
        int end = text.indexOf(marker, from);
        if (end < 0) return null;
        consumed = end + marker.length() - at;
        return open + render(text.substring(from, end)) + close;
    }

    /**
     * Whether an underscore here is emphasis rather than part of a word.
     *
     * <p>The character before it must not be alphanumeric — which is what stops {@code READ_WRITE}
     * becoming a bold {@code WRITE} and leaves {@code _emphasis_} at the start of a word alone.</p>
     */
    private static boolean flanking(String text, int at) {
        if (at == 0) return true;
        char before = text.charAt(at - 1);
        return !Character.isLetterOrDigit(before) && before != '_';
    }

    /** The index of the bracket closing the one at {@code open}, honouring nesting. */
    private static int matching(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return at;
        }
        return -1;
    }

    /**
     * An inline reference, as a link the popup can follow.
     *
     * <p>{@code {@link Foo|a label}} and {@code {@link Foo a label}} are both legal JSDoc and mean the
     * same thing. The target keeps the {@code js:} scheme for the same reason javadoc's keeps
     * {@code java:} — {@code EditorLanguageFeatures} strips the scheme and hands the rest to whichever
     * engine owns the document, so nothing between here and there has to know what a reference looks
     * like in either language.</p>
     */
    private static String link(String body) {
        String target = body;
        String label = "";
        int pipe = body.indexOf('|');
        int space = body.indexOf(' ');
        if (pipe >= 0) {
            target = body.substring(0, pipe).trim();
            label = body.substring(pipe + 1).trim();
        } else if (space >= 0) {
            target = body.substring(0, space).trim();
            label = body.substring(space + 1).trim();
        }
        String shown = label.isEmpty() ? target : label;
        // AN EXTERNAL LINK IS ALREADY A URL, so it keeps its own scheme rather than being told it is a
        // JavaScript name. Following one is the host's business, not the engine's.
        boolean external = target.startsWith("http://") || target.startsWith("https://");
        String href = external ? target : "js:" + target;
        return "<a href=\"" + Markdown.escape(href) + "\">" + Markdown.escape(shown) + "</a>";
    }
}
