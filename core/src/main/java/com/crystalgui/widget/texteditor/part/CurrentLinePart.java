package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The band behind the primary caret's row, in two halves.
 *
 * <p>Ported in shape from {@code browser/viewParts/currentLineHighlight/} (MIT), with one divergence
 * worth naming: Monaco keeps drawing the band when the editor loses focus (its
 * {@code renderLineHighlightOnlyWhenFocus} defaults to {@code false}, and the theme supplies a dimmer
 * unfocused colour); this hides it outright. Ours is the stricter reading of "which line am I editing" —
 * an unfocused editor is not being edited — and it avoids needing a second themed colour for a state the
 * sheet does not currently describe.</p>
 *
 * <p>Hidden while there is a selection, which is what every editor does: two overlapping highlights on the
 * same row read as a rendering fault rather than as two pieces of information.</p>
 *
 * <h3>Two elements rather than one wide one</h3>
 * <p>The gutter and the code area are separately stacked: the gutter paints an opaque background above the
 * text so a long line scrolled sideways passes behind the numbers. A single band drawn behind everything
 * is therefore simply covered in the gutter region, and one drawn in front of everything hides the
 * numbers. A band <em>inside</em> the gutter sits in the gutter's own stacking context — beneath its
 * numbers, above its background — which is the only place it can be both visible and behind the
 * digits.</p>
 */
public final class CurrentLinePart extends EditorViewPart {

    private final UIElement band;
    private final UIElement gutterBand;

    public CurrentLinePart(TextEditor editor, UIElement band, UIElement gutterBand) {
        super(editor);
        this.band = band;
        this.gutterBand = gutterBand;
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
        if (editor.selections().hasSelection() || !editor.isFocused()) {
            DecorationPool.hide(band);
            DecorationPool.hide(gutterBand);
            return;
        }
        // BACK INTO LAYOUT -- see DecorationPool.hide. Both halves, together, for the same reason
        // they are hidden together: two bands that disagree about whether they are shown is worse
        // than either state.
        DecorationPool.show(band);
        DecorationPool.show(gutterBand);
        float height = editor.lineHeight();
        int row = editor.buffer().offsetToPoint(editor.getCaret()).row();
        // THE WHOLE WRAPPED ROW, not the one view line the caret is on. The band answers "which line am I
        // editing", and a line that wraps is still one line -- highlighting a third of it would make the
        // band look like it had come unstuck from the caret whenever the caret moved along a long row.
        // SCREEN space. Neither band is inside a scroll layer: they span the VIEWPORT horizontally
        // (see below) while following the row vertically, which is the one combination no layer
        // carries. @see TextEditor#screenTopOfViewLine
        final float top = editor.screenTopOfViewLine(editor.projections().firstViewLineOfRow(row));
        final float bandHeight = height * editor.projections().projectionOf(row).viewLineCount();
        // Spans the viewport and does NOT move with horizontal scroll: it marks which row is being edited,
        // which is true of the whole visible width however far sideways the text has gone.
        final float width = Math.max(1f, editor.textViewportWidth());
        StyleGroup.defaultPipeline(band.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(top).width(width).height(bandHeight));

        // The gutter half. Its parent is scroll-exempt, so it subtracts the offset by hand exactly as the
        // numbers beside it do -- and it spans the gutter's whole box, including the fold column, so the
        // band reads as one strip across the editor rather than two with a seam.
        final float gutterBandWidth = editor.paddingLeft() + editor.gutterWidth();
        StyleGroup.defaultPipeline(gutterBand.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(top).width(gutterBandWidth).height(bandHeight));
    }
}
