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
    /** One severity's icon-and-number pair. Carries a {@code severity-*} class the cascade draws from. */
    static final String COUNT_CLASS = "__inspection-count__";
    static final String ICON_CLASS = "__inspection-icon__";
    static final String NUMBER_CLASS = "__inspection-number__";
    /** The green tick, shown only when the file has nothing at all. */
    static final String CLEAN_MARK_CLASS = "__inspection-ok__";
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

    /**
     * Logical px between the widget and the editor's top edge — i.e. the separator above it.
     *
     * <p>Its own constant rather than {@link #CLEARANCE}, because the two answer different questions: the
     * horizontal one is "clear the scrollbar", which is a widget's width, and this one is "sit just under
     * the tab strip", which is a hairline. Sharing a number would have made either of them wrong the first
     * time the other changed.</p>
     */
    private static final float TOP_GAP = 2f;

    private UIElement panel;
    private UIElement errorCount;
    private UIElement warningCount;
    private UIElement infoCount;
    private UIElement clean;
    private Button previous;
    private Button next;

    InspectionWidgetPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        DiagnosticSet diagnostics = editor.diagnostics();
        panel();

        int errors = diagnostics.count(DiagnosticSeverity.ERROR);
        int warnings = diagnostics.count(DiagnosticSeverity.WARNING);
        int information = diagnostics.count(DiagnosticSeverity.INFORMATION);
        showCount(errorCount, errors);
        showCount(warningCount, warnings);
        showCount(infoCount, information);
        // The green tick, and only when there is genuinely nothing. IntelliJ's widget says "this file is
        // clean" with a mark rather than with the word, which is also the only state that needs saying --
        // every other one is already spelled out by the counts beside it.
        clean.setDisplayed(errors == 0 && warnings == 0 && information == 0);
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
                l -> l.positionType(TaffyPosition.ABSOLUTE).top(TOP_GAP).right(CLEARANCE));
    }

    /**
     * One severity's chip — its icon and its number — shown only when it has one.
     *
     * <p>A severity with nothing to report is not "0", it is absent: IntelliJ shows the marks that apply
     * and no others, and a row of zeroes is noise in a widget whose whole job is to be glanceable.</p>
     */
    private static void showCount(UIElement chip, int count) {
        chip.setDisplayed(count > 0);
        if (count > 0) ((UIText) chip.getChildren().get(1)).setText(Integer.toString(count));
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

        // BUILT ONCE, shown and hidden per render. Creating the chips as counts appear would put new
        // elements into the tree from inside a render pass -- the trap the editor's gutter arrows and the
        // palette's key chips each paid for -- and they would land after the frame's layout.
        errorCount = countChip("severity-error");
        warningCount = countChip("severity-warning");
        infoCount = countChip("severity-info");
        clean = new UIElement();
        clean.addClass(CLEAN_MARK_CLASS);
        clean.setHitTest(false);
        panel.addChild(errorCount);
        panel.addChild(warningCount);
        panel.addChild(infoCount);
        panel.addChild(clean);

        previous = arrow(PREVIOUS_CLASS, editor::goToPreviousProblem);
        next = arrow(NEXT_CLASS, editor::goToNextProblem);
        panel.addChild(previous);
        panel.addChild(next);

        editor.addInternalChild(panel);
        return panel;
    }

    /**
     * A severity's icon and its number, as one unit.
     *
     * <p>A container rather than two siblings on the panel, because the icon and the count <b>are</b> one
     * thing: {@code gap-all} applies between every pair of children, so laying them out flat would put the
     * same space inside a chip as between two of them. The panel therefore uses margins on the chips and
     * no gap of its own — which is also required because a chip hidden with {@code display: none}
     * <em>still counts</em> for a {@code gap-all}, so a clean file would have carried three phantom gaps.</p>
     */
    private static UIElement countChip(String severityClass) {
        UIElement chip = new UIElement();
        chip.addClass(COUNT_CLASS);
        chip.addClass(severityClass);
        chip.setHitTest(false);

        UIElement icon = new UIElement();
        icon.addClass(ICON_CLASS);
        icon.setHitTest(false);
        chip.addChild(icon);

        UIText number = new UIText("");
        number.addClass(NUMBER_CLASS);
        number.setHitTest(false);
        chip.addChild(number);
        return chip;
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
