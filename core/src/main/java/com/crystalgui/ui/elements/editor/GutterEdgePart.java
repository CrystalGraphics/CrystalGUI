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
        // ON THE EDGE THAT FACES THE CODE, which is what a border is. Floating it in the middle of the
        // code margin read as a third thing -- a stray rule with a gap either side -- rather than as the
        // gutter ending. The whole margin then sits between it and the first glyph, which is the gap it
        // is for.
        //
        // Mirrored, the edge facing the code is the gutter's LEFT one. Leaving this as
        // `textOriginX - codeLeftPad` put it at the editor's far left instead -- a rule down the outside
        // of the pane with nothing on either side of it, while the gutter it belongs to had no border at
        // all and read as floating text.
        // ONE PIXEL CLEAR OF THE GUTTER, not on its boundary. Unmirrored the edge lands just PAST the
        // gutter's box, so nothing covers it; mirrored, `gutterLeft()` is the first pixel the gutter
        // itself paints -- and the gutter has an opaque background at z-index 6, so the rule was drawn
        // every frame and painted over every frame. Invisible for a reason that has nothing to do with
        // where it was told to go.
        final float left = editor.isGutterOnRight()
                ? Math.max(0f, editor.gutterLeft() - 1f)
                : editor.textOriginX() - editor.codeLeftPad();
        final float height = editor.viewportHeight();
        StyleGroup.defaultPipeline(edge.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(1f).height(height));
        pool.endPass();
    }
}
