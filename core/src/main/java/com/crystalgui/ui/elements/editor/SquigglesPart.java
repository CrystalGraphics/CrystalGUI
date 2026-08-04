package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.Rope;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The underline beneath every diagnostic — one band per visible <b>view line</b> it covers.
 *
 * <p>Structurally {@link SelectionsPart}, and deliberately so: a range decoration that survives soft wrap
 * has exactly one correct shape, which is to work in view space and emit a band per visual row. A single
 * band spanning a wrap would underline text that has no diagnostic on it.</p>
 *
 * <h3>Colour comes from the cascade, not from Java</h3>
 *
 * <p>Each band carries {@code __squiggle__} plus a per-severity class, and {@code default.css} decides what
 * red and amber mean. Same rule the whole widget layer follows, and the same one the node graph already
 * relies on for its per-type port palette — a new severity is a stylesheet edit, not a recompile.</p>
 *
 * <h3>{@link DiagnosticSeverity#HINT} draws nothing</h3>
 *
 * <p>Not an omission. A hint is a suggestion, and underlining it in the text makes a style note look like a
 * compile error — VS Code renders hints only as a lightbulb for the same reason. The band is skipped here
 * rather than made transparent in CSS so it costs no element at all.</p>
 *
 * <h3>Positions are converted here, against the live buffer</h3>
 *
 * <p>{@link Diagnostic} stores row/column because that is what every compiler reports and because a row
 * survives edits elsewhere in the file. The offset it corresponds to is a property of the <em>current</em>
 * document, so it is resolved at render time and clamped to the row that exists now. A diagnostic naming a
 * row past the end of a shrunken buffer is dropped for this frame rather than throwing — it describes a
 * document that no longer exists, and the next compile will replace it.</p>
 */
final class SquigglesPart extends EditorViewPart {

    static final String SQUIGGLE_CLASS = "__squiggle__";
    static final String ERROR_CLASS = "__squiggle-error__";
    static final String WARNING_CLASS = "__squiggle-warning__";
    static final String INFORMATION_CLASS = "__squiggle-information__";

    private final List<UIElement> bands = new ArrayList<>();

    SquigglesPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        int used = 0;
        if (lastViewLine >= firstViewLine) {
            for (Diagnostic diagnostic : editor.diagnostics().all()) {
                if (diagnostic.severity() == DiagnosticSeverity.HINT) continue;
                used = place(diagnostic, firstViewLine, lastViewLine, used);
            }
        }
        for (int i = used; i < bands.size(); i++) DecorationPool.hide(bands.get(i));
    }

    private int place(Diagnostic diagnostic, int firstViewLine, int lastViewLine, int index) {
        Rope document = editor.buffer().document();
        int lastRow = document.lineCount() - 1;
        if (diagnostic.start().row() > lastRow) return index;

        int from = offsetOf(document, diagnostic.start().row(), diagnostic.start().column());
        int to = offsetOf(document, Math.min(diagnostic.end().row(), lastRow), diagnostic.end().column());
        // A zero-width diagnostic still has to be visible -- "expected ';'" points between two characters,
        // and a band of width 0 is a band nobody can see. One character's worth is the smallest honest mark.
        if (to <= from) to = Math.min(document.length(), from + 1);

        int startView = editor.viewLineOf(from, LineProjection.Affinity.RIGHT);
        int endView = editor.viewLineOf(to, LineProjection.Affinity.LEFT);
        float height = editor.lineHeight();
        float pad = editor.codeLeftPad();

        for (int viewLine = Math.max(firstViewLine, startView);
             viewLine <= Math.min(lastViewLine, endView); viewLine++) {
            if (viewLine < 0 || viewLine >= editor.viewLineCount()) continue;
            int lineStart = editor.viewLineStartOffset(viewLine);
            int lineEnd = editor.viewLineEndOffset(viewLine);
            int segmentFrom = Math.max(lineStart, from);
            int segmentTo = Math.min(lineEnd, to);
            if (segmentTo < segmentFrom) continue;

            int rowStart = document.lineStartOffset(editor.modelAt(viewLine).row());
            LineProjection.ViewPosition fromView = editor.projectionAt(viewLine)
                    .toViewPosition(segmentFrom - rowStart, LineProjection.Affinity.RIGHT);
            LineProjection.ViewPosition toView = editor.projectionAt(viewLine)
                    .toViewPosition(segmentTo - rowStart, LineProjection.Affinity.LEFT);

            float left = pad + editor.xOfView(viewLine, fromView.column()) - editor.getScrollLeft();
            float right = pad + editor.xOfView(viewLine, toView.column()) - editor.getScrollLeft();
            float width = Math.max(1f, right - left);
            // Under the text rather than through it: the band sits at the bottom of the line box, so it
            // never overlaps a glyph and never fights the selection band drawn behind the same characters.
            float top = editor.textOriginY() + viewLine * height + height - SQUIGGLE_HEIGHT
                    - editor.getScrollTop();

            UIElement band = bandAt(index++);
            applySeverity(band, diagnostic.severity());
            StyleGroup.defaultPipeline(band.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(left).top(top).width(width).height(SQUIGGLE_HEIGHT));
        }
        return index;
    }

    /** Logical px. Here rather than in the sheet because the band's TOP is computed from it, and a height
     * the cascade could change independently would put the underline somewhere other than the bottom of
     * the line. The sheet still owns the colour. */
    private static final float SQUIGGLE_HEIGHT = 1f;

    /** Resolves a row/column against the document as it is now, clamping a column past the end of its row —
     * which is what {@link Diagnostic#onRow} deliberately produces. */
    private static int offsetOf(Rope document, int row, int column) {
        int clampedRow = Math.max(0, Math.min(row, document.lineCount() - 1));
        int rowStart = document.lineStartOffset(clampedRow);
        int rowEnd = document.lineEndOffset(clampedRow);
        if (column >= rowEnd - rowStart) return rowEnd;
        return rowStart + Math.max(0, column);
    }

    /** Set AND cleared, all three, because bands are recycled — a band that underlined an error and is
     * reused for a warning would otherwise carry both classes and take whichever the cascade preferred. */
    private static void applySeverity(UIElement band, DiagnosticSeverity severity) {
        band.removeClass(ERROR_CLASS);
        band.removeClass(WARNING_CLASS);
        band.removeClass(INFORMATION_CLASS);
        switch (severity) {
            case ERROR -> band.addClass(ERROR_CLASS);
            case WARNING -> band.addClass(WARNING_CLASS);
            case INFORMATION -> band.addClass(INFORMATION_CLASS);
            default -> { }
        }
    }

    private UIElement bandAt(int index) {
        while (bands.size() <= index) {
            UIElement band = new UIElement();
            band.addClass(SQUIGGLE_CLASS);
            band.setHitTest(false);
            band.markAsInternal();
            // In the viewport, in document coordinates, like every other decoration -- see SelectionsPart
            // for what happens when one of these is parented to the editor instead.
            editor.textViewport().addInternalChild(band);
            bands.add(band);
        }
        return bands.get(index);
    }
}
