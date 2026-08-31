package com.crystalgui.widget.texteditor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Whole-line difference bands, and the character marks inside them.
 *
 * <p>Shaped after {@code diffEditorDecorations.ts} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT. Monaco expresses these as model
 * decorations and lets its own rendering place them; this engine has no general decoration lane, so the
 * part places them itself — the same relationship {@code SelectionsPart} has to Monaco's selection
 * decorations.</p>
 *
 * <h3>Two pools, in two layers, and they cannot be one</h3>
 *
 * <p>A <b>band</b> answers "this line differs", which is true across the whole visible width however far
 * the text has been scrolled sideways — so it lives in the viewport, in screen coordinates, exactly as
 * {@code CurrentLinePart}'s band does.</p>
 *
 * <p>A <b>mark</b> answers "these characters differ", which is a claim about a position in the text — so it
 * lives in the lines layer, in document coordinates, and scrolls with the text it is marking.</p>
 *
 * <p>Putting both in one layer makes one of them wrong, and the failure is quiet: at scroll offset zero the
 * two agree exactly, so it looks correct until somebody scrolls sideways.</p>
 */
final class DiffBandsPart extends EditorViewPart {

    private final DecorationPool bands;
    private final DecorationPool marks;

    DiffBandsPart(TextEditor editor) {
        super(editor);
        this.bands = new DecorationPool(editor::textViewport, TextEditor.DIFF_BAND_CLASS, false);
        this.marks = new DecorationPool(editor::linesLayer, TextEditor.DIFF_MARK_CLASS, false);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        bands.beginPass();
        marks.beginPass();

        DiffDecorations decorations = editor.diffDecorations();
        if (decorations.isEmpty() || !hasWindow(firstViewLine, lastViewLine)) {
            bands.endPass();
            marks.endPass();
            return;
        }

        for (DiffDecorations.Band band : decorations.bands()) {
            placeBand(band, firstViewLine, lastViewLine);
        }
        for (DiffDecorations.Mark mark : decorations.marks()) {
            placeMark(mark, firstViewLine, lastViewLine);
        }

        bands.endPass();
        marks.endPass();
    }

    private void placeBand(DiffDecorations.Band band, int firstViewLine, int lastViewLine) {
        float lineHeight = editor.lineHeight();
        float width = Math.max(1f, editor.textViewportWidth());

        if (band.fromLine() == band.toLine()) {
            // A CHANGE WITH NO ROWS ON THIS SIDE. Drawn as a thin rule at the boundary, because a change
            // that is invisible in one pane is how a reader concludes nothing happened there.
            int row = Math.min(band.fromLine(), Math.max(editor.buffer().document().lineCount() - 1, 0));
            int viewLine = editor.projections().firstViewLineOfRow(row);
            if (viewLine < firstViewLine - 1 || viewLine > lastViewLine + 1) return;
            float top = editor.screenTopOfViewLine(viewLine);
            place(bands.next(), band.kind(), true, 0f, top - 1f, width, 2f);
            return;
        }

        for (int row = band.fromLine(); row < band.toLine(); row++) {
            if (row < 0 || row >= editor.buffer().document().lineCount()) continue;
            int viewLine = editor.projections().firstViewLineOfRow(row);
            int viewLines = editor.projections().projectionOf(row).viewLineCount();
            if (viewLine + viewLines < firstViewLine || viewLine > lastViewLine) continue;

            float top = editor.screenTopOfViewLine(viewLine);
            place(bands.next(), band.kind(), false, 0f, top, width, lineHeight * viewLines);
        }
    }

    private void placeMark(DiffDecorations.Mark mark, int firstViewLine, int lastViewLine) {
        if (mark.line() < 0 || mark.line() >= editor.buffer().document().lineCount()) return;

        int viewLine = editor.projections().firstViewLineOfRow(mark.line());
        if (viewLine < firstViewLine || viewLine > lastViewLine) return;

        float pad = editor.codeLeftPad();
        float left = pad + editor.xOfView(viewLine, mark.fromColumn());
        float right = pad + editor.xOfView(viewLine, mark.toColumn());
        if (right <= left) return;

        // DOCUMENT SPACE, not screen space. A mark lives in the lines layer, which carries the scroll
        // offset itself -- handing it a screen y adds that offset twice and every mark lands a row or so
        // from the text it is marking. The band above uses screenTopOfViewLine because it lives in the
        // viewport instead; the two layers are the whole reason there are two coordinate helpers.
        place(marks.next(), mark.kind(), false, left, editor.topOfViewLine(viewLine),
                right - left, editor.lineHeight());
    }

    private static void place(UINode element, DiffDecorations.Kind kind, boolean thin,
            float left, float top, float width, float height) {
        // SWAPPED, NOT ADDED. These elements are pooled, so a band that was ADDED last pass and is CHANGED
        // this one would otherwise carry both classes and the cascade would resolve whichever rule happens
        // to win -- which reads as a random colour rather than as a stale class.
        for (DiffDecorations.Kind other : DiffDecorations.Kind.values()) {
            if (other != kind) element.removeClass(classOf(other));
        }
        element.addClass(classOf(kind));
        if (thin) element.addClass(TextEditor.DIFF_THIN_CLASS);
        else element.removeClass(TextEditor.DIFF_THIN_CLASS);

        StyleGroup.defaultPipeline(element.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(top).width(width).height(height));
    }

    private static String classOf(DiffDecorations.Kind kind) {
        switch (kind) {
            case ADDED:
                return TextEditor.DIFF_ADDED_CLASS;
            case REMOVED:
                return TextEditor.DIFF_REMOVED_CLASS;
            default:
                return TextEditor.DIFF_CHANGED_CLASS;
        }
    }
}
