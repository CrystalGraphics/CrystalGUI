package com.crystalgui.text.markup;

import java.util.Collections;
import java.util.List;

/**
 * Marked-up text, as a list of blocks — what a documentation popup lays out.
 *
 * <h3>Why this exists rather than an HTML string</h3>
 *
 * <p>Both references end at a renderer that already speaks their intermediate form: IntelliJ emits HTML
 * into a Swing HTML view, and VS Code's Java hover arrives as Markdown and goes through its Markdown
 * renderer. This engine has neither — a popup lays out real elements, and a code sample has to become an
 * element with its own background and coloured spans. So the thing that crosses is the model their
 * renderers build internally, rather than the text those renderers parse.</p>
 *
 * <h3>Not an HTML DOM, deliberately</h3>
 *
 * <p>A general tree invites a general renderer, and nothing here needs one: there is no CSS, no float, no
 * table layout. Blocks and styled runs are what a documentation popup can draw, and constraining the
 * model to that is what keeps the renderer a hundred lines rather than a browser.</p>
 *
 * <p>It is also <b>not javadoc's</b>. JSDoc is Markdown, a shader node's description is plain prose, and
 * a future linter's explanation is neither — they all produce this. The javadoc-specific half, which tag
 * means what, stays with the engine that knows what a javadoc is.</p>
 */
public record MarkupDocument(List<MarkupBlock> blocks) {

    public static final MarkupDocument EMPTY = new MarkupDocument(List.of());

    public MarkupDocument {
        blocks = blocks == null ? List.of() : Collections.unmodifiableList(blocks);
    }

    /** One paragraph of unstyled text — what a producer with nothing to mark up returns. */
    public static MarkupDocument ofText(String text) {
        if (text == null || text.isEmpty()) return EMPTY;
        return new MarkupDocument(List.of(MarkupBlock.paragraph(List.of(MarkupSpan.of(text)))));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Every block's text, one per line — the honest degradation for a consumer that cannot lay out
     * blocks, and what makes this safe to put behind an API that used to return a {@code String}.
     */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (MarkupBlock block : blocks) {
            if (out.length() > 0) out.append('\n');
            out.append(block.text());
        }
        return out.toString();
    }
}
