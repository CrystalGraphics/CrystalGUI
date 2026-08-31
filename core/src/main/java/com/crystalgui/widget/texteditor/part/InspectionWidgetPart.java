package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.texteditor.TextEditor;
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
public final class InspectionWidgetPart extends EditorViewPart {

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

    // THE INSETS ARE IN ua/workbench.css, on `.__inspection__`, and the note that used to sit here about
    // never deriving the right-hand one from verticalBarThickness() went with them -- it is a rule about
    // what the number may be, so it belongs where the number is.

    private UINode panel;
    private UINode errorCount;
    private UINode warningCount;
    private UINode infoCount;
    private UINode clean;
    private Button previous;
    private Button next;

    public InspectionWidgetPart(TextEditor editor) {
        super(editor);
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
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

        // OUT OF FLOW, and that is all this writes. The insets themselves are `.__inspection__`'s in
        // ua/workbench.css: nothing here is computed from either one, so unlike the squiggle's height or
        // the stripe mark's, there is nothing to read back -- the part simply stops writing them.
        StyleGroup.defaultPipeline(panel.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));
    }

    /**
     * One severity's chip — its icon and its number — shown only when it has one.
     *
     * <p>A severity with nothing to report is not "0", it is absent: IntelliJ shows the marks that apply
     * and no others, and a row of zeroes is noise in a widget whose whole job is to be glanceable.</p>
     */
    private static void showCount(UINode chip, int count) {
        chip.setDisplayed(count > 0);
        if (count > 0) ((UIText) chip.children().get(1)).setText(Integer.toString(count));
    }

    /**
     * Set AND cleared, all three — the panel is long-lived, so a file that was fixed would otherwise keep
     * the class that made it red.
     *
     * <h3>...and only when the answer has actually changed</h3>
     *
     * <p>This runs from {@code render}, so it ran <b>every frame</b>: three {@code removeClass} calls and
     * one {@code addClass}, each of which invalidates the style match, to arrive at the classes the panel
     * already had. Measured in a client — {@code style:rematch x15} at 400-500us appearing on 3,773 of
     * 3,920 profiled steps, blamed on this line. That is 5-6% of a 120Hz budget spent permanently, on
     * every frame the editor is open, whatever the file is doing.</p>
     *
     * <p><b>A class mutation cannot no-op the way a style write does.</b> {@code replaceOrPutCandidate}
     * suppresses an unchanged value, which is what makes per-frame geometry writes cheap; a class change
     * is precisely what invalidation exists for, so it has no such guard and one is owed here instead.
     * {@code ErrorStripePart} learned the same lesson from the same symptom and remembers what each mark
     * already shows.</p>
     *
     * <p>{@code applied} is tracked separately from the value, because {@code null} is a real severity
     * here — it means clean — and cannot double as "nothing applied yet".</p>
     */
    private void applyWorst(DiagnosticSeverity worst) {
        if (worstApplied && appliedWorst == worst) return;
        worstApplied = true;
        appliedWorst = worst;
        panel.removeClass(CLEAN_CLASS);
        panel.removeClass(HAS_ERRORS_CLASS);
        panel.removeClass(HAS_WARNINGS_CLASS);
        if (worst == null) panel.addClass(CLEAN_CLASS);
        else if (worst == DiagnosticSeverity.ERROR) panel.addClass(HAS_ERRORS_CLASS);
        else panel.addClass(HAS_WARNINGS_CLASS);
    }

    /** What {@link #applyWorst} last wrote, and whether it has written at all. */
    private DiagnosticSeverity appliedWorst;

    private boolean worstApplied;

    private UINode panel() {
        if (panel != null) return panel;

        panel = new UINode();
        panel.addClass(PANEL_CLASS);
        // Chrome, not content: it must stay in the corner rather than sliding away as the text scrolls.
        panel.setScrollExempt(true);

        // BUILT ONCE, shown and hidden per render. Creating the chips as counts appear would put new
        // elements into the tree from inside a render pass -- the trap the editor's gutter arrows and the
        // palette's key chips each paid for -- and they would land after the frame's layout.
        errorCount = countChip("severity-error");
        warningCount = countChip("severity-warning");
        infoCount = countChip("severity-info");
        clean = new UINode();
        clean.addClass(CLEAN_MARK_CLASS);
        clean.setHitTest(false);
        panel.append(errorCount);
        panel.append(warningCount);
        panel.append(infoCount);
        panel.append(clean);

        previous = arrow(PREVIOUS_CLASS, editor::goToPreviousProblem);
        next = arrow(NEXT_CLASS, editor::goToNextProblem);
        panel.append(previous);
        panel.append(next);

        editor.append(panel);
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
    private static UINode countChip(String severityClass) {
        UINode chip = new UINode();
        chip.addClass(COUNT_CLASS);
        chip.addClass(severityClass);
        chip.setHitTest(false);

        UINode icon = new UINode();
        icon.addClass(ICON_CLASS);
        icon.setHitTest(false);
        chip.append(icon);

        UIText number = new UIText("");
        number.addClass(NUMBER_CLASS);
        number.setHitTest(false);
        chip.append(number);
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
