package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.Selection;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;


/**
 * The highlight bands behind every selection — one per visible <b>view line</b> it covers.
 *
 * <p>Ported in shape from {@code browser/viewParts/selections/} (MIT). A wrapped row needs a band per
 * visual row, not one per document row: a single band spanning a wrap would be a rectangle covering text
 * that is not selected. Working in view space makes the wrapped and unwrapped cases the same code; the
 * only thing that changes is how many bands a row produces.</p>
 */
final class SelectionsPart extends EditorViewPart {

    /**
     * The bands, pooled.
     *
     * <p>IN THE VIEWPORT, like everything else in document coordinates. Left on the editor they were
     * scrolled by the pose translate <em>and</em> had the offset subtracted by hand, so a band sat a
     * screenful away from the text it marked — selecting a word painted one several lines above it.</p>
     *
     * <p>Appended rather than inserted first: the sheet already orders these by z-index
     * ({@code __selection__} at -1, {@code __caret__} at 1), so the caret cannot end up under its own
     * band.</p>
     */
    private final DecorationPool bands;

    SelectionsPart(TextEditor editor) {
        super(editor);
        this.bands = new DecorationPool(editor::textViewport, TextEditor.SELECTION_CLASS, false);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        // HIDE WHAT WE HAVE rather than returning -- see EditorViewPart.render. A pass with no window
        // still has to retire the bands from the last one, or they mark rows that no longer exist.
        bands.beginPass();
        if (!hasWindow(firstViewLine, lastViewLine)) {
            bands.endPass();
            return;
        }
        for (Selection selection : editor.selections().all()) {
            place(selection, firstViewLine, lastViewLine);
        }
        bands.endPass();
    }

    private void place(Selection selection, int firstViewLine, int lastViewLine) {
        if (selection.isEmpty()) return;
        float height = editor.lineHeight();
        final float pad = editor.codeLeftPad();
        // The selection's own ends resolve with the affinity that keeps a zero-width band off the line
        // below: a selection ending exactly at a wrap belongs to the line it visually ends on.
        int startView = editor.viewLineOf(selection.start(), LineProjection.Affinity.RIGHT);
        int endView = editor.viewLineOf(selection.end(), LineProjection.Affinity.LEFT);

        for (int viewLine = Math.max(firstViewLine, startView);
             viewLine <= Math.min(lastViewLine, endView); viewLine++) {
            if (viewLine < 0 || viewLine >= editor.viewLineCount()) continue;
            int lineStart = editor.viewLineStartOffset(viewLine);
            int lineEnd = editor.viewLineEndOffset(viewLine);
            int from = Math.max(lineStart, selection.start());
            int to = Math.min(lineEnd, selection.end());
            if (to < from) continue;

            int rowStart = editor.buffer().document().lineStartOffset(editor.modelAt(viewLine).row());
            LineProjection.ViewPosition fromView = editor.projectionAt(viewLine)
                    .toViewPosition(from - rowStart, LineProjection.Affinity.RIGHT);
            LineProjection.ViewPosition toView = editor.projectionAt(viewLine)
                    .toViewPosition(to - rowStart, LineProjection.Affinity.LEFT);

            // A BAND THAT STARTS AT THE ROW'S START REACHES THE LEFT EDGE OF THE TEXT AREA.
            //
            // Every x here is measured from the first glyph, so a selection beginning at column 0 began
            // after codeLeftPad -- leaving an unselected strip between the gutter and the text on every
            // triple-clicked line. IntelliJ has no such strip: its line highlight runs from the gutter's
            // border, and the text avoids touching that border because the gap lives inside the gutter
            // rather than in front of the code.
            //
            // Extending the band is the cheaper half of that. The margin stays where it is -- the level-0
            // indent guide has nowhere else to live, see codeLeftPad -- and nothing about where text is
            // drawn, measured or hit-tested moves. Only the highlight gets wider, on its left, and only
            // when the row's own start is inside the selection. A wrapped continuation fails that test
            // (its `from` is past the row start), so it still begins at its carried indent.
            boolean fromRowStart = from == rowStart;
            float left = (fromRowStart ? 0f : pad + editor.xOfView(viewLine, fromView.column()))
                    - editor.getScrollLeft();
            // A selected line break shows as a sliver past the end of the text, which is how every editor
            // signals "the newline is in the selection too". A soft wrap is NOT a line break, so the
            // sliver is only drawn where the selection genuinely continues onto another document row.
            boolean continuesPastRow = selection.end() > lineEnd
                    && editor.modelAt(viewLine).viewLineInRow()
                       == editor.projectionAt(viewLine).viewLineCount() - 1;
            float right = pad + editor.xOfView(viewLine, toView.column()) - editor.getScrollLeft()
                    + (continuesPastRow ? height * 0.4f : 0f);

            // THE LINE BOX, NOT THE INK -- and the two are not the same the moment `line-height` is not 1.
            //
            // This inked the band at textHeight() and centred it in the row, which leaves (height -
            // textHeight) of unpainted row on every boundary: at the shipped `line-height: 1.4` a
            // multi-line selection came out as a stack of separate stripes with the background showing
            // between them. IntelliJ and VS Code both paint the full line box, so a selection spanning
            // rows is one solid shape.
            //
            // It also had to change for a reason independent of taste: CurrentLinePart paints the full
            // height with no centring, so selecting the line the caret is on left the current-line band
            // visible as a rim above and below the selection -- two decorations describing the same row
            // and disagreeing about where it starts.
            final float bandInk = height;
            final float top = editor.topOfViewLine(viewLine);
            final float bandLeft = left;
            final float width = Math.max(1f, right - left);
            StyleGroup.defaultPipeline(bands.next().getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(bandLeft).top(top).width(width).height(bandInk));
        }
    }

}
