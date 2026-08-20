package com.crystalgui.text.markup;

import java.util.Collections;
import java.util.List;

/**
 * One laid-out region — a paragraph, a code sample, a list, a heading, a quote.
 *
 * <p>A block carries <em>either</em> spans or child blocks, never both. A paragraph is text; a list is
 * items. Allowing both would make "what does a LIST's own span list mean" a question every consumer has
 * to answer, and they would answer it differently.</p>
 */
public record MarkupBlock(Kind kind, List<MarkupSpan> spans, List<MarkupBlock> children, int level) {

    public enum Kind {
        /** Running text. */
        PARAGRAPH,
        /**
         * A code sample — {@code <pre>}, and the one block whose whitespace is content.
         *
         * <p>Its spans hold the source verbatim, newlines included. Everywhere else whitespace is
         * collapsible, which is why the collapsing cannot be a pass over the finished document.</p>
         */
        CODE,
        /** {@code <ul>}/{@code <ol>} — children are {@link #ITEM}s, {@link #level} is 1 for ordered. */
        LIST,
        /** One {@code <li>} — children are its own blocks, so an item may hold a paragraph and a list. */
        ITEM,
        /** {@code <h1>}–{@code <h6>}, with the number in {@link #level}. */
        HEADING,
        /** {@code <blockquote>}. */
        QUOTE,
        /**
         * {@code <dl>} — a two-column block whose children alternate {@link #TERM} and {@link #DETAIL}.
         *
         * <p>Modelled as HTML has it rather than as a bespoke "sections" kind, because that is what a
         * documentation section list <em>is</em>: {@code Since:} beside {@code 1.0} is a term and its
         * definition. It also means nothing here knows what a javadoc block tag is — a JSDoc emitter
         * or a shader node's description can produce the same shape and get the same layout.</p>
         */
        DEFINITIONS,
        /** {@code <dt>} — the label of a {@link #DEFINITIONS} row. */
        TERM,
        /** {@code <dd>} — the value of a {@link #DEFINITIONS} row; may hold blocks of its own. */
        DETAIL
    }

    public static MarkupBlock paragraph(List<MarkupSpan> spans) {
        return new MarkupBlock(Kind.PARAGRAPH, List.copyOf(spans), List.of(), 0);
    }

    public static MarkupBlock code(String text) {
        return new MarkupBlock(Kind.CODE, List.of(MarkupSpan.of(text, MarkupSpan.CODE)), List.of(), 0);
    }

    public static MarkupBlock heading(List<MarkupSpan> spans, int level) {
        return new MarkupBlock(Kind.HEADING, List.copyOf(spans), List.of(), level);
    }

    public static MarkupBlock of(Kind kind, List<MarkupBlock> children, int level) {
        return new MarkupBlock(kind, List.of(), List.copyOf(children), level);
    }

    public MarkupBlock {
        spans = spans == null ? List.of() : Collections.unmodifiableList(spans);
        children = children == null ? List.of() : Collections.unmodifiableList(children);
    }

    /** Every span's text, joined — what a consumer with no styling to offer draws. */
    public String text() {
        if (!children.isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (MarkupBlock child : children) {
                if (out.length() > 0) out.append('\n');
                out.append(child.text());
            }
            return out.toString();
        }
        StringBuilder out = new StringBuilder();
        for (MarkupSpan span : spans) out.append(span.text());
        return out.toString();
    }
}
