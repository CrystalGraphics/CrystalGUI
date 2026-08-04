package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The problem readout in the editor's top-right corner, with its two navigation arrows — IntelliJ's
 * inspection widget.
 *
 * <p>An <b>overlay widget</b> in Monaco's sense, like {@link ZoomIndicatorPart}: positioned against the
 * viewport rather than the document, so it neither scrolls nor lives in the text's coordinate space.</p>
 *
 * <h3>It is shown even when the file is clean, and that is the point</h3>
 *
 * <p>"No problems" is the single most reassuring thing this widget says, and a readout that appears only
 * when something is wrong cannot say it — its absence would be indistinguishable from the feature being
 * broken, which is precisely the doubt it exists to remove. IntelliJ shows a green tick for the same
 * reason.</p>
 *
 * <h3>The arrows are drawn shapes, not glyphs</h3>
 *
 * <p>{@code overlay: shape("triangle-up")} in {@code default.css}. The bundled {@code MinecraftRegular.otf}
 * has no {@code U+25B2}, and a missing glyph draws a <b>blank advance</b> rather than failing — so a
 * text arrow would render as an empty button and read as a layout bug. {@code NodeMenuTree} records
 * hitting exactly this with {@code U+25B8}, and {@code UIText}'s ellipsis fallback with {@code U+2026}.
 * Java names no character at all here.</p>
 */
final class InspectionWidgetPart extends EditorViewPart {

    static final String PANEL_CLASS = "__inspection__";
    static final String STATUS_CLASS = "__inspection-status__";
    static final String PREVIOUS_CLASS = "__inspection-previous__";
    static final String NEXT_CLASS = "__inspection-next__";

    /** Set on the panel so the cascade can colour the whole readout by the worst thing in the file. */
    static final String CLEAN_CLASS = "__inspection-clean__";
    static final String HAS_ERRORS_CLASS = "__inspection-errors__";
    static final String HAS_WARNINGS_CLASS = "__inspection-warnings__";

    /**
     * Logical px of clearance from the editor's top-right corner.
     *
     * <p>A <b>constant</b>, and never {@code verticalBarThickness()}. That term is zero or eight depending
     * on whether the content currently overflows, so a widget positioned by it jumps sideways the moment a
     * document grows past one screenful — which is the flick {@link ZoomIndicatorPart} records chasing for
     * the same reason. Sized to clear the bar outright instead.</p>
     */
    private static final float CLEARANCE = 14f;

    private UIElement panel;
    private UIText status;
    private Button previous;
    private Button next;

    InspectionWidgetPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        DiagnosticSet diagnostics = editor.diagnostics();
        panel();

        status.setText(summaryOf(diagnostics));
        applyWorst(diagnostics.worst());

        // Disabled rather than hidden when there is nothing to visit: a control that vanishes changes the
        // widget's width, so the readout would shift sideways the instant a file became clean.
        boolean navigable = !diagnostics.isEmpty();
        previous.setEnabled(navigable);
        next.setEnabled(navigable);

        // Content-sized: right/top insets only, no width. Taffy shrink-to-fits an absolutely positioned
        // box with no definite size, which means the panel never needs to measure its own text -- and so
        // the readout cannot be clipped by a width computed against the wrong font.
        StyleGroup.defaultPipeline(panel.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).top(CLEARANCE).right(CLEARANCE));
    }

    /**
     * "No problems", or the non-zero counts.
     *
     * <p>Words rather than symbols, and singular/plural handled, because this is the one line in the editor
     * a person reads to decide whether they are done.</p>
     */
    private static String summaryOf(DiagnosticSet diagnostics) {
        int errors = diagnostics.count(DiagnosticSeverity.ERROR);
        int warnings = diagnostics.count(DiagnosticSeverity.WARNING);
        int information = diagnostics.count(DiagnosticSeverity.INFORMATION);
        if (errors == 0 && warnings == 0 && information == 0) return "No problems";

        StringBuilder out = new StringBuilder();
        append(out, errors, "error");
        append(out, warnings, "warning");
        append(out, information, "note");
        return out.toString();
    }

    private static void append(StringBuilder out, int count, String noun) {
        if (count == 0) return;
        if (out.length() > 0) out.append("  ");
        out.append(count).append(' ').append(noun);
        if (count != 1) out.append('s');
    }

    /** Set AND cleared, all three — the panel is long-lived, so a file that was fixed would otherwise keep
     * the class that made it red. */
    private void applyWorst(DiagnosticSeverity worst) {
        panel.removeClass(CLEAN_CLASS);
        panel.removeClass(HAS_ERRORS_CLASS);
        panel.removeClass(HAS_WARNINGS_CLASS);
        if (worst == null) panel.addClass(CLEAN_CLASS);
        else if (worst == DiagnosticSeverity.ERROR) panel.addClass(HAS_ERRORS_CLASS);
        else panel.addClass(HAS_WARNINGS_CLASS);
    }

    private UIElement panel() {
        if (panel != null) return panel;

        panel = new UIElement();
        panel.addClass(PANEL_CLASS);
        panel.markAsInternal();
        // Chrome, not content: it must stay in the corner rather than sliding away as the text scrolls.
        panel.setScrollExempt(true);

        status = new UIText("");
        status.addClass(STATUS_CLASS);
        status.setHitTest(false);
        panel.addChild(status);

        previous = arrow(PREVIOUS_CLASS, editor::goToPreviousProblem);
        next = arrow(NEXT_CLASS, editor::goToNextProblem);
        panel.addChild(previous);
        panel.addChild(next);

        editor.addInternalChild(panel);
        return panel;
    }

    /**
     * One navigation arrow.
     *
     * <p>{@code FocusPolicy.NONE}, like the zoom indicator's reset button and for the same reason: a
     * {@code Button} takes focus on click by default, so pressing an arrow would move focus out of the
     * editor and the next keystroke would go nowhere — from a control whose entire purpose is to put you
     * back in the text at the problem.</p>
     */
    private Button arrow(String cssClass, Runnable action) {
        Button button = new Button("");
        button.addClass(cssClass);
        button.setFocusPolicy(FocusPolicy.NONE);
        button.attachListener(action);
        return button;
    }
}
