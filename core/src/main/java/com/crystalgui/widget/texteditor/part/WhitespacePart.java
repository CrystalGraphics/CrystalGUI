package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.text.view.WhitespaceMarkers;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.TextEditor;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * A marker glyph over every whitespace character the mode asks for.
 *
 * <p>Ported in shape from {@code browser/viewParts/whitespace/whitespace.ts} (MIT), with the one
 * divergence that matters: <b>Monaco substitutes the marker into the line's own text and this draws
 * separate elements at measured x.</b> Substitution is right there because the editor is monospaced —
 * here a middot and a space have different advances, so replacing one with the other would shift every
 * glyph after it and the caret would stop landing where the text is.</p>
 */
public final class WhitespacePart extends EditorViewPart {

    /** Cap on marker elements — see {@code IndentGuidesPart.MAX_GUIDES} for the same reasoning. */
    private static final int MAX_MARKS = 2048;

    private final DecorationPool pool;

    /** The font the markers were last styled for, so the push happens while it is settling and not after. */
    private String markerFontKey;

    public WhitespacePart(TextEditor editor) {
        super(editor);
        this.pool = new DecorationPool(editor::linesLayer, TextEditor.WHITESPACE_CLASS, true);
    }

    /** Forces the next pass to re-push the font, after a zoom or a theme change. */
    public void forgetFont() {
        markerFontKey = null;
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
        RenderWhitespace mode = editor.getRenderWhitespace();
        if (mode == RenderWhitespace.NONE || lastViewLine < firstViewLine) {
            pool.hideAll();
            return;
        }
        float height = editor.lineHeight();
        float ink = editor.textHeight();
        final float pad = editor.codeLeftPad();
        String fontKey = editor.measuredFontKey();

        pool.beginPass();
        for (int viewLine = Math.max(0, firstViewLine);
             viewLine <= Math.min(lastViewLine, editor.viewLineCount() - 1) && pool.used() < MAX_MARKS;
             viewLine++) {
            ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
            LineProjection projection = editor.projectionAt(viewLine);
            String row = editor.buffer().line(model.row());
            int from = projection.viewLineStart(model.viewLineInRow());
            int to = Math.min(row.length(), projection.viewLineEnd(model.viewLineInRow()));
            // TRAILING asks whether this segment is the row's last, which is what stops a soft wrap from
            // reporting its own break as trailing whitespace.
            boolean continues = model.viewLineInRow() < projection.viewLineCount() - 1;
            boolean[] marked = WhitespaceMarkers.shouldMark(row, mode, editor.getTabSize(), continues);

            final float top = editor.topOfViewLine(viewLine) + (height - ink) / 2f;
            for (int column = from; column < to && pool.used() < MAX_MARKS; column++) {
                if (!marked[column]) continue;
                char marker = WhitespaceMarkers.markerFor(row.charAt(column));
                if (marker == '\0') continue;

                LineProjection.ViewPosition at =
                        projection.toViewPosition(column, LineProjection.Affinity.RIGHT);
                final float left = pad + editor.xOfView(viewLine, at.column());
                UIElement mark = pool.next();
                UIText label = (UIText) mark.children().get(0);
                label.setText(String.valueOf(marker));
                // The font is pushed only while it is actually settling, not per marker per frame. There
                // can be hundreds of markers and the pipeline is not free; a line number pays this
                // because there are ~25 of them, a marker cannot.
                if (markerFontKey == null || !markerFontKey.equals(fontKey)) {
                    editor.pushEditorFontTo(label);
                }
                StyleGroup.defaultPipeline(mark.getStyle().getLayoutGroup(),
                        l -> l.positionType(TaffyPosition.ABSOLUTE)
                                .left(left).top(top).height(ink));
            }
        }
        pool.endPass();
        markerFontKey = fontKey;
    }
}
