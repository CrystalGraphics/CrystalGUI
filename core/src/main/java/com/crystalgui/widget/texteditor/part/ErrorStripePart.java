package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.text.Rope;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.texteditor.TextEditor;
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
public final class ErrorStripePart extends EditorViewPart {

    static final String STRIPE_CLASS = "__error-stripe__";

    /** @see #markAt(int) — the shadow-crossing spelling of {@link #STRIPE_CLASS}. */
    static final String STRIPE_PART = "error-stripe";
    private static final String ERROR_PART = "error-stripe-error";
    private static final String WARNING_PART = "error-stripe-warning";
    private static final String INFORMATION_PART = "error-stripe-information";
    static final String ERROR_CLASS = "__error-stripe-error__";
    static final String WARNING_CLASS = "__error-stripe-warning__";
    static final String INFORMATION_CLASS = "__error-stripe-information__";

    /** How far off a mark a press may land and still count as a press on it, as a percent of the groove. */
    private static final float SNAP_PERCENT = 1.2f;

    /** …with a floor, because that percentage is a couple of pixels in a short editor. */
    private static final float MIN_SNAP_PX = 5f;

    /**
     * What a mark is drawn at before the sheet has been matched — <b>not</b> the styling.
     *
     * <p>{@code .__error-stripe__ { height: 1.2% }} in {@code ua/editor.css} is the real value. A
     * percentage rather than pixels so it stays proportionate at any {@code uiScale}, and so the groove's
     * own height is the only geometry this part needs to know: a single-row problem in a long file rounds
     * to a fraction of a pixel, and a mark nobody can see is the same as no mark.</p>
     *
     * <p>Read rather than written for the reason {@link EditorViewPart#stylePercent} sets out — the
     * mark's <em>top</em> is clamped against its height, so the two must be one number.</p>
     */
    private static final float DEFAULT_MARK_PERCENT = 1.2f;

    private final List<UINode> marks = new ArrayList<>();

    /**
     * What each pooled slot is currently marking, parallel to {@link #marks}.
     *
     * <p>Read at event time and never captured in a listener — a mark is reused for a different problem on
     * every render, so a listener holding the diagnostic it was built with would navigate to whatever was
     * in that slot the first time it was used. The same rule the gutter's fold arrows already record, and
     * for the same reason: a listener may only be attached once, while the payload changes per frame.</p>
     */
    private final List<Diagnostic> marked = new ArrayList<>();

    /**
     * What each slot is <b>already showing</b>, so an unchanged mark is not rewritten.
     *
     * <h3>Measured: 16µs per diagnostic PER FRAME, and almost all of it was the cascade</h3>
     *
     * <p>This part is the one thing in the editor that is honestly O(document): the groove shows every
     * problem in the file, not the ones on screen. That is correct, and it was costing
     * <b>9.6ms a frame at 500 problems and 33ms at 2000</b> — measured against 524µs for the same document
     * with none. A decompiled Minecraft class has hundreds, so opening one took 120fps to 55.</p>
     *
     * <p>The walk was never the expense. {@code applySeverity} called {@code removeClass} three times and
     * {@code addClass} once on <em>every</em> mark on <em>every</em> frame, and each of those is an
     * {@code invalidateStyleMatch()} — so the cascade re-ran selector matching over two thousand elements
     * sixty times a second to arrive at the classes they already had. The layout write beside it is
     * cheaper only because {@code replaceOrPutCandidate} no-ops on an unchanged value; the class
     * mutations have no such guard, and could not have one, because a class change is exactly what
     * invalidation is for.</p>
     *
     * <p>So the fix is per-slot memory rather than a render gate. {@code EditorViewPart} records why:
     * the {@code shouldRender} flag was removed as unhonoured, and its note says the genuine costs
     * "were cached at their source, which is where they belonged either way". This is that, for the one
     * part whose cost scales with the document.</p>
     */
    private final List<Placed> shown = new ArrayList<>();

    /** What a slot is displaying — compared, never read for anything else. */
    private static final class Placed {
        DiagnosticSeverity severity;
        float top = Float.NaN;
        float height = Float.NaN;
    }

    public ErrorStripePart(TextEditor editor) {
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
     * <p>A mark is a little over one percent of the groove — around a dozen pixels in a tall
     * window and fewer in a short one — so aiming at one is aiming at a target thinner than the pointer
     * is precise. A miss does not do nothing, which would be forgivable: it lands on the groove, and the
     * groove jumps proportionally, so you arrive a screenful away from the problem you were pointing at.
     * That is the "takes me to a slightly off offset" report, and it is why the tolerance is generous.</p>
     *
     * <p>Nearest rather than first, because marks crowd together in a file with many problems and the one
     * whose centre is closest is unambiguously the one being aimed at.</p>
     */
    private boolean goToNearestMark(UINode track, float screenX, float screenY) {
        var local = track.toLocal(screenX, screenY);
        Box grooveBox = track.box();
        if (grooveBox == null) return false;
        float tolerance = Math.max(MIN_SNAP_PX, grooveBox.height() * SNAP_PERCENT / 100f);
        Diagnostic best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < marks.size(); i++) {
            Diagnostic problem = marked.get(i);
            if (problem == null) continue;
            Box box = marks.get(i).box();
            if (box == null) continue;
            // NO SUBTRACTION. A mark is appended to the TRACK, so `Box.y()` -- the offset from the
            // host's border-box origin -- is already the position within it, and `toLocal` above puts
            // the track's own origin at zero too. Taking the track's y off again would displace every
            // mark by however far down the editor the groove happens to sit. @see plan_m6.md 6.4
            float centre = box.y() + box.height() / 2f;
            float distance = Math.abs(local.y() - centre);
            if (distance <= tolerance && distance < bestDistance) {
                bestDistance = distance;
                best = problem;
            }
        }
        return best != null && editor.goToDiagnostic(best);
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
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

        UINode mark = markAt(index);
        marked.set(index, diagnostic);
        Placed placed = shown.get(index);
        index++;

        // READ FROM THE SHEET, then used for both the height and the clamp below.
        float markPercent = stylePercent(mark, LayoutProperties.HEIGHT, DEFAULT_MARK_PERCENT);
        float fraction = Math.min(1f, viewLine / (float) viewLines);
        // Kept inside the groove: at the very bottom the mark would otherwise hang off the end and be
        // clipped to nothing, so the last problem in a file would be the one you cannot see.
        float top = Math.min(100f - markPercent, fraction * 100f);

        // ONLY WHAT CHANGED. @see #shown -- the class mutations below each invalidate the style match,
        // and re-applying the classes a mark already has re-runs selector matching over every problem in
        // the file on every frame.
        if (placed.severity != diagnostic.severity()) {
            placed.severity = diagnostic.severity();
            applySeverity(mark, diagnostic.severity());
        }
        // Compared rather than left to `replaceOrPutCandidate` to no-op: the write is five properties
        // through a fluent group, and the cheapest version of that is not reaching it.
        if (placed.top != top || placed.height != markPercent) {
            placed.top = top;
            placed.height = markPercent;
            StyleGroup.defaultPipeline(mark.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(0).widthPercent(100f).topPercent(top).heightPercent(markPercent));
        }
        return index;
    }

    /** Set AND cleared, all three — marks are pooled, and one that showed an error before would otherwise
     * carry two severity classes and take whichever the cascade happened to prefer. */
    private static void applySeverity(UINode mark, DiagnosticSeverity severity) {
        mark.removeClass(ERROR_CLASS);
        mark.removeClass(WARNING_CLASS);
        mark.removeClass(INFORMATION_CLASS);
        switch (severity) {
            case ERROR -> mark.addClass(ERROR_CLASS);
            case WARNING -> mark.addClass(WARNING_CLASS);
            case INFORMATION -> mark.addClass(INFORMATION_CLASS);
            default -> { }
        }
        // ...and the PART follows it, because the part is what a rule outside this shadow tree can
        // name. The pooling rule above applies here too and is simpler: a part is one value, so
        // setting it replaces whatever the mark showed last rather than accumulating.
        mark.set(Attribute.PART, switch (severity) {
            case ERROR -> ERROR_PART;
            case WARNING -> WARNING_PART;
            case INFORMATION -> INFORMATION_PART;
            default -> STRIPE_PART;
        });
    }

    private UINode markAt(int index) {
        while (marks.size() <= index) {
            UINode mark = new UINode();
            mark.addClass(STRIPE_CLASS);
            // AND A PART NAME, because the class alone reaches nothing here. These marks are appended
            // into the scrollbar's TRACK, which lives in the Scroller's shadow tree -- so a
            // document-level `.__error-stripe__` rule cannot match them and every one of its
            // declarations (the 1.2% height, the z-index above the thumb, the opacity, and the
            // per-severity colour) was silently doing nothing. Marks drawn at zero height in no
            // colour, which reads as the stripe not being populated rather than not being styled.
            // A part IS addressable from outside the tree that owns it, which is the whole point of
            // one: `scroller::part(error-stripe)` in the sheet, beside the class rule for the old
            // engine.
            mark.set(Attribute.PART, STRIPE_PART);
            // ...AND THE PAINT ORDER FROM JAVA, because not even the part name can carry it here.
            // A mark is appended to the scrollbar's TRACK, which is itself a part -- and `::part()`
            // has no spelling for a part UNDER a part, which is the same gap the port counts across
            // the shipped sheets. So `scroller::part(error-stripe)` does not match either, and the
            // one declaration that is a correctness guarantee rather than an appearance -- sitting
            // above the thumb, or the marks are drawn underneath it and simply not visible -- is
            // stated where it can be. DEFAULT origin, so a scoped sheet supersedes it the day one
            // exists. The rest of the stripe's look stays in the sheet and is a KNOWN GAP.
            StyleGroup.defaultPipeline(mark.getStyle().getGeneralGroup(), g -> g.zIndex(1));
            // HIT-TESTABLE NOW THAT IT HAS SOMETHING TO DO. It was deliberately transparent while it did
            // not: a mark that swallowed presses without acting on them would break dragging the thumb
            // underneath it, which is the groove's actual job. It now navigates, so it earns the press.
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
            editor.verticalScroller().track().append(mark);
            marks.add(mark);
            marked.add(null);
            shown.add(new Placed());
        }
        // BACK INTO LAYOUT -- see DecorationPool.hide. It matters more here than for a squiggle: a retired
        // mark is still hit-testable, so one left in the groove would answer a press about a problem it no
        // longer marks.
        return DecorationPool.show(marks.get(index));
    }
}
