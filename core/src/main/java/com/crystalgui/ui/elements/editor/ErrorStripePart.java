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
 * A mark in the vertical scrollbar's groove for every problem in the document — IntelliJ's error stripe,
 * VS Code's overview ruler.
 *
 * <p>The single most valuable thing this whole feature has: it shows every problem in a three-thousand-line
 * file <em>without scrolling</em>, and turns "is this file clean?" from a question you answer by reading
 * into one you answer by glancing.</p>
 *
 * <h3>It draws the whole document, not the visible window</h3>
 *
 * <p>Unlike every other {@link EditorViewPart}, the {@code firstViewLine}/{@code lastViewLine} arguments
 * are ignored. That is the point — a stripe that only marked what is already on screen would be a strictly
 * worse squiggle. It stays a view part regardless so it shares the one render pass and the one pooling
 * idiom rather than growing its own lifecycle.</p>
 *
 * <h3>Position is a fraction of VIEW lines, not of document rows</h3>
 *
 * <p>The mark has to line up with where the thumb ends up when you scroll to it, and the thumb is driven by
 * the scroll extent — which is measured in view lines. With any region folded the two disagree: a document
 * row inside a fold occupies no vertical space at all, so {@code row / rowCount} would place its mark
 * somewhere the thumb can never reach.</p>
 *
 * <p>Resolving through {@code viewLineOf} also handles the folded case correctly for free: a hidden row
 * resolves to its fold header's view line, so the mark appears at the fold — which is exactly where
 * clicking it should take you, because that is the only place the problem can be revealed from.</p>
 *
 * <h3>Marks live in the groove, not on the editor</h3>
 *
 * <p>Parented to {@code verticalScroller().track()}, whose box is already the full height of the scrollable
 * range and — critically — does <b>not</b> move with the scroll offset. An overlay on the editor would be
 * translated by the pose every frame and would have to subtract the scroll back out by hand, which is the
 * mistake {@code SelectionsPart} records having made in the other direction.</p>
 */
final class ErrorStripePart extends EditorViewPart {

    static final String STRIPE_CLASS = "__error-stripe__";
    static final String ERROR_CLASS = "__error-stripe-error__";
    static final String WARNING_CLASS = "__error-stripe-warning__";
    static final String INFORMATION_CLASS = "__error-stripe-information__";

    /** How far off a mark a press may land and still count as a press on it, as a percent of the groove. */
    private static final float SNAP_PERCENT = 1.2f;

    /** …with a floor, because that percentage is a couple of pixels in a short editor. */
    private static final float MIN_SNAP_PX = 5f;

    /**
     * Percent of the groove's height. A single-row problem in a long file rounds to a fraction of a pixel,
     * and a mark nobody can see is the same as no mark — so it is given a floor instead.
     *
     * <p>A percentage rather than pixels so it stays proportionate at any {@code uiScale}, and so the
     * groove's own height is the only geometry this part needs to know.</p>
     */
    private static final float MARK_HEIGHT_PERCENT = 1.2f;

    private final List<UIElement> marks = new ArrayList<>();

    /**
     * What each pooled slot is currently marking, parallel to {@link #marks}.
     *
     * <p>Read at event time and never captured in a listener — a mark is reused for a different problem on
     * every render, so a listener holding the diagnostic it was built with would navigate to whatever was
     * in that slot the first time it was used. The same rule the gutter's fold arrows already record, and
     * for the same reason: a listener may only be attached once, while the payload changes per frame.</p>
     */
    private final List<Diagnostic> marked = new ArrayList<>();

    ErrorStripePart(TextEditor editor) {
        super(editor);
        editor.verticalScroller().track().onMouseDown.attachListener((element, event) -> {
            if (goToNearestMark(element, event.getPosition().x(), event.getPosition().y())) {
                // CONSUMED so the groove does not also jump. The Scroller's own handler is on the
                // Scroller and fires in the BUBBLE phase for a press whose target is the track, so
                // stopping here in the target phase is what keeps the two from both acting.
                event.stopPropagation();
            }
        }, false, true);
    }

    /**
     * A press near a mark counts as a press on it.
     *
     * <p>A mark is {@value #MARK_HEIGHT_PERCENT} percent of the groove — around a dozen pixels in a tall
     * window and fewer in a short one — so aiming at one is aiming at a target thinner than the pointer
     * is precise. A miss does not do nothing, which would be forgivable: it lands on the groove, and the
     * groove jumps proportionally, so you arrive a screenful away from the problem you were pointing at.
     * That is the "takes me to a slightly off offset" report, and it is why the tolerance is generous.</p>
     *
     * <p>Nearest rather than first, because marks crowd together in a file with many problems and the one
     * whose centre is closest is unambiguously the one being aimed at.</p>
     */
    private boolean goToNearestMark(UIElement track, float screenX, float screenY) {
        var local = track.screenToLocal(screenX, screenY);
        float tolerance = Math.max(MIN_SNAP_PX, track.getRuntimeCache().getHeight() * SNAP_PERCENT / 100f);
        Diagnostic best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < marks.size(); i++) {
            Diagnostic problem = marked.get(i);
            if (problem == null) continue;
            var box = marks.get(i).getRuntimeCache();
            float centre = box.getY() - track.getRuntimeCache().getY() + box.getHeight() / 2f;
            float distance = Math.abs(local.y() - centre);
            if (distance <= tolerance && distance < bestDistance) {
                bestDistance = distance;
                best = problem;
            }
        }
        return best != null && editor.goToDiagnostic(best);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        int used = 0;
        int viewLines = editor.viewLineCount();
        if (viewLines > 0) {
            Rope document = editor.buffer().document();
            int lastRow = document.lineCount() - 1;
            for (Diagnostic diagnostic : editor.diagnostics().all()) {
                if (diagnostic.severity() == DiagnosticSeverity.HINT) continue;
                // Stale, describing a document that has since shrunk. Dropped rather than clamped to the
                // end: a mark at the bottom of the groove would claim there is a problem on the last line.
                if (diagnostic.start().row() > lastRow) continue;
                used = place(diagnostic, document, viewLines, used);
            }
        }
        for (int i = used; i < marks.size(); i++) {
            DecorationPool.hide(marks.get(i));
            // CLEARED, not merely hidden. A hidden mark keeps its slot, and a stale payload there is a
            // click that navigates to a problem that no longer exists.
            marked.set(i, null);
        }
    }

    private int place(Diagnostic diagnostic, Rope document, int viewLines, int index) {
        int row = Math.max(0, Math.min(diagnostic.start().row(), document.lineCount() - 1));
        int offset = document.lineStartOffset(row);
        int viewLine = editor.viewLineOf(offset, LineProjection.Affinity.RIGHT);
        if (viewLine < 0) return index;

        float fraction = Math.min(1f, viewLine / (float) viewLines);
        // Kept inside the groove: at the very bottom the mark would otherwise hang off the end and be
        // clipped to nothing, so the last problem in a file would be the one you cannot see.
        float top = Math.min(100f - MARK_HEIGHT_PERCENT, fraction * 100f);

        UIElement mark = markAt(index);
        marked.set(index, diagnostic);
        index++;
        applySeverity(mark, diagnostic.severity());
        StyleGroup.defaultPipeline(mark.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0).widthPercent(100f).topPercent(top).heightPercent(MARK_HEIGHT_PERCENT));
        return index;
    }

    /** Set AND cleared, all three — marks are pooled, and one that showed an error before would otherwise
     * carry two severity classes and take whichever the cascade happened to prefer. */
    private static void applySeverity(UIElement mark, DiagnosticSeverity severity) {
        mark.removeClass(ERROR_CLASS);
        mark.removeClass(WARNING_CLASS);
        mark.removeClass(INFORMATION_CLASS);
        switch (severity) {
            case ERROR -> mark.addClass(ERROR_CLASS);
            case WARNING -> mark.addClass(WARNING_CLASS);
            case INFORMATION -> mark.addClass(INFORMATION_CLASS);
            default -> { }
        }
    }

    private UIElement markAt(int index) {
        while (marks.size() <= index) {
            UIElement mark = new UIElement();
            mark.addClass(STRIPE_CLASS);
            // HIT-TESTABLE NOW THAT IT HAS SOMETHING TO DO. It was deliberately transparent while it did
            // not: a mark that swallowed presses without acting on them would break dragging the thumb
            // underneath it, which is the groove's actual job. It now navigates, so it earns the press.
            mark.markAsInternal();
            final int slot = marks.size();
            mark.onMouseEnter.attachListener((element, event) -> {
                Diagnostic problem = marked.get(slot);
                if (problem == null) return;
                // THE MARK IS THE ANCHOR, not the pointer. It is the thing being described, it does not
                // move while the box is open, and anchoring to it is what puts the box beside the groove
                // instead of on top of it.
                editor.langFeatures().showProblemPopupAt(problem, element);
            }, false, false);
            mark.onMouseDown.attachListener((element, event) -> {
                Diagnostic problem = marked.get(slot);
                if (problem == null) return;
                editor.goToDiagnostic(problem);
                // CONSUMED, so the press does not also reach the groove and jump the thumb to the pointer.
                event.stopPropagation();
            }, false, true);
            editor.verticalScroller().track().addInternalChild(mark);
            marks.add(mark);
            marked.add(null);
        }
        return marks.get(index);
    }
}
