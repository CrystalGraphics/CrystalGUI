package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The gutter's right edge — one line, full height, never interrupted.
 *
 * <p>What the indent-guide column at level 0 was pretending to be. In IntelliJ this line runs the whole
 * document unbroken, including past lines at indent 0 that have no indentation to guide; an indent guide
 * cannot do that, because it is drawn per row from that row's own indent. Making it the gutter's edge
 * makes it structural: it is one element, it spans the viewport, and no line of code can break it.</p>
 *
 * <p>Scroll-exempt for the same reason the gutter is — it marks a horizontal boundary, which does not move
 * when the document scrolls vertically. It is also the reason this part attaches to the editor rather than
 * to the text viewport: chrome beside the text would be clipped away by the viewport's own box.</p>
 */
final class GutterEdgePart extends EditorViewPart {

    private final DecorationPool pool;

    GutterEdgePart(TextEditor editor) {
        super(editor);
        this.pool = new DecorationPool(() -> editor, TextEditor.GUTTER_EDGE_CLASS, false);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (!editor.isGutterVisible()) {
            pool.hideAll();
            return;
        }
        pool.beginPass();
        UIElement edge = pool.next();
        edge.setScrollExempt(true);
        // ON the gutter's right edge, which is what a border is. Floating it in the middle of the code
        // margin read as a third thing -- a stray rule with a gap either side -- rather than as the gutter
        // ending. The whole margin then sits between it and the first glyph, which is the gap it is for.
        final float left = editor.textOriginX() - editor.codeLeftPad();
        final float height = editor.viewportHeight();
        StyleGroup.defaultPipeline(edge.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(1f).height(height));
        pool.endPass();
    }
}
