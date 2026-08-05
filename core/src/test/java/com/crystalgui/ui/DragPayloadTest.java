package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Payload drags and drop targets — the part of P2 with <b>no web spec behind it</b>.
 *
 * <p>The web's only drop-target protocol is HTML5 drag-and-drop, which this engine deliberately does
 * not implement. Modern pointer-based libraries hit-test themselves each move and dispatch their own
 * events, and so does {@link UIDragController}. These tests are therefore pinning a design, not a
 * port — which is exactly why they spell out the intent rather than citing a spec line.</p>
 *
 * <p>The subtle part is the interaction with pointer capture: capture makes ordinary hit testing
 * report the drag <em>source</em> for the whole drag, so drop targeting has to ask the window for the
 * geometric answer separately. If those two ever get conflated, every drop lands on the thing being
 * dragged.</p>
 */
public class DragPayloadTest extends UiTestBase {

    private static final Object PAYLOAD = "the-dragged-thing";

    private UIWindow window;
    private UIInputHandler input;
    private UIDragController drag;
    private UIElement root, source, targetA, targetB;
    private final List<String> log = new ArrayList<>();

    @Before
    public void buildTree() {
        log.clear();
        root = new UIElement().layout(l -> l.width(400).height(200)
                .flexDirection(dev.vfyjxf.taffy.style.FlexDirection.ROW));
        source = new UIElement().layout(l -> l.width(100).height(100));
        targetA = new UIElement().layout(l -> l.width(100).height(100));
        targetB = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(source);   // x 0..100
        root.addChild(targetA);  // x 100..200
        root.addChild(targetB);  // x 200..300

        record("A", targetA);
        record("B", targetB);

        window = new UIWindow(Ui.of(root));
        window.init(800, 400); // uiScale 2
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        input = window.getInputHandler();
        drag = input.getDragController();

        input.beginFrame();
        input.endFrame(); // firstFrameOver — input is dropped before a frame exists
    }

    /** Records boundary events AND opts in to receiving drops — rejection is the default, so a
     * target that never calls {@code preventDefault()} gets no {@code Drop} at all. */
    private void record(String name, UIElement element) {
        element.onDragEnter.attachListener((el, e) -> log.add("enter:" + name), false, false);
        element.onDragLeave.attachListener((el, e) -> log.add("leave:" + name), false, false);
        element.onDragOver.attachListener((el, e) -> e.preventDefault(), false, false);
        element.onDrop.attachListener((el, e) -> log.add("drop:" + name + ":" + e.getPayload()), false, false);
    }

    private void press(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void move(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    private void release(float x, float y) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, false, 0f, 2L));
        input.beginFrame();
        input.endFrame();
    }

    private void startPayloadDrag() {
        press(50f, 50f);
        drag.startDrag(source, 100f, 100f, PAYLOAD, (mx, my, sx, sy, dx, dy) -> { });
    }

    // ── Drop targeting ──────────────────────────────────────────────────────

    /**
     * The capture interaction. Pointer capture makes ordinary hit testing answer "the source" for the
     * whole drag; drop targeting must use the geometric answer instead, or every drop lands on the
     * thing being dragged.
     */
    @Test
    public void dropTargetIsWhatIsGeometricallyUnderThePointerNotTheCaptureTarget() {
        startPayloadDrag();

        move(150f, 50f); // over targetA

        assertSame("capture keeps mouse events on the source…", source, input.getPointerCaptureTarget());
        assertSame("…but the drop target is what is actually underneath", targetA, drag.getDropTarget());
    }

    @Test
    public void crossingTargetsFiresLeaveThenEnter() {
        startPayloadDrag();

        move(150f, 50f); // A
        log.clear();
        move(250f, 50f); // B

        assertEquals("leaving A and entering B, in that order; log=" + log,
                List.of("leave:A", "enter:B"), log);
    }

    /** The drag source is dragging itself around; it can never be its own drop target. */
    @Test
    public void theSourceIsNeverItsOwnDropTarget() {
        startPayloadDrag();

        move(150f, 50f);
        move(50f, 50f); // back over the source

        assertNull("the source must not become a drop target", drag.getDropTarget());
    }

    @Test
    public void releasingOverATargetDropsThePayloadOnIt() {
        startPayloadDrag();
        move(150f, 50f);
        log.clear();

        release(150f, 50f);

        assertTrue("the drop must carry the payload; log=" + log,
                log.contains("drop:A:" + PAYLOAD));
        assertFalse("the drag must be over", drag.isDragging());
    }

    @Test
    public void releasingOverNothingDropsNothing() {
        startPayloadDrag();
        move(350f, 50f); // past every target, still inside the root

        release(350f, 50f);

        assertTrue("no target, so no drop; log=" + log,
                log.stream().noneMatch(s -> s.startsWith("drop:")));
    }

    // ── Accepting a drop ────────────────────────────────────────────────────

    /**
     * <b>Rejection is the default.</b> HTML5 drag-and-drop's one genuinely good idea, kept even
     * though the rest of that API was discarded: a target opts in by calling {@code preventDefault()}
     * on its {@code DragOver}. Without it, every element in the tree is silently a drop target and a
     * payload lands wherever the pointer happened to be.
     *
     * <p>This was documented in two places and implemented in none — {@code isDefaultPrevented()} was
     * never read, so drops fired unconditionally. Found by re-reading the claims rather than by any
     * test, which is why it is pinned now.</p>
     */
    @Test
    public void anElementThatNeverOptsInReceivesNoDrop() {
        List<String> drops = new ArrayList<>();
        UIElement inert = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(inert); // x 300..400 — listens for the drop but never accepts
        inert.onDrop.attachListener((el, e) -> drops.add("inert"), false, false);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        startPayloadDrag();
        move(350f, 50f);

        assertSame("it is still offered the drag…", inert, drag.getDropTarget());
        assertFalse("…but it never accepted", drag.isDropAccepted());

        release(350f, 50f);

        assertTrue("a target that did not opt in must receive no drop; log=" + drops, drops.isEmpty());
    }

    @Test
    public void acceptingViaPreventDefaultIsReportedAndDelivers() {
        startPayloadDrag();
        move(150f, 50f);

        assertTrue("targetA opts in via preventDefault", drag.isDropAccepted());

        release(150f, 50f);
        assertTrue("and therefore receives the drop; log=" + log, log.contains("drop:A:" + PAYLOAD));
    }

    /** Acceptance is re-read every frame, never latched — a target may stop accepting (a slot fills,
     * a panel disables) and a stale "yes" would let a drop through after it became invalid. */
    @Test
    public void acceptanceIsReEvaluatedEveryFrameNotLatched() {
        startPayloadDrag();
        move(150f, 50f);          // over targetA, which accepts
        assertTrue(drag.isDropAccepted());

        move(350f, 50f);          // off every accepting target

        assertFalse("acceptance must not survive leaving the target that granted it",
                drag.isDropAccepted());
    }

    // ── Re-entrancy ─────────────────────────────────────────────────────────

    /** Starting a second drag while one is live used to overwrite the state outright: the old
     * listener never heard it ended, its target stayed highlighted, its ghost stayed promoted. */
    @Test
    public void startingASecondDragCancelsTheFirstCleanly() {
        List<String> outcome = new ArrayList<>();
        press(50f, 50f);
        drag.startDrag(source, 100f, 100f, PAYLOAD, new UIDragController.DragListener() {
            @Override public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) { }
            @Override public void onDragCancel() { outcome.add("cancel"); }
        });
        move(150f, 50f);
        log.clear();

        drag.startDrag(targetB, 100f, 100f, "second", (mx, my, sx, sy, dx, dy) -> { });

        assertEquals("the displaced drag must be cancelled, not silently dropped", List.of("cancel"), outcome);
        assertTrue("and its stranded target told; log=" + log, log.contains("leave:A"));
        assertSame("the new drag is the live one", targetB, drag.getSource());
    }

    // ── Threshold ───────────────────────────────────────────────────────────

    /** A press that never really moved must stay a click, not become a drag. */
    @Test
    public void aPayloadDragDoesNotActivateBelowTheThreshold() {
        startPayloadDrag();

        move(50.5f, 50f); // 1 physical px — under DEFAULT_THRESHOLD_PX

        assertFalse("must not activate on a jitter-sized movement", drag.isActivated());
        assertNull("and must not be offering itself to any target yet", drag.getDropTarget());
    }

    @Test
    public void movingPastTheThresholdActivates() {
        startPayloadDrag();

        move(150f, 50f);

        assertTrue(drag.isActivated());
    }

    /** Positional drags (Slider, Scroller, SplitView) have no threshold — they must track the very
     * first pixel, or a slider would refuse small adjustments. */
    @Test
    public void positionalDragsActivateImmediately() {
        press(50f, 50f);
        drag.startDrag(source, 100f, 100f, (mx, my, sx, sy, dx, dy) -> { });

        assertTrue("a positional drag is active from the press", drag.isActivated());
    }

    // ── Ghost ───────────────────────────────────────────────────────────────

    private UIElement ghostOn(UIElement parent) {
        UIElement g = new UIElement().layout(l -> l.width(40).height(20));
        parent.addChild(g);
        drag.setGhost(g);
        return g;
    }

    /** Idle ghosts are {@code display: none}, so parking one inside the source costs no layout. */
    @Test
    public void anIdleGhostIsOutOfLayoutAndUnhittable() {
        UIElement g = ghostOn(source);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        assertFalse("a ghost must never take the pointer", g.isHitTest());
        assertFalse("and must not be promoted while idle", g.isInTopLayer());
        assertEquals("nor take up space", 0f, g.getRuntimeCache().getHeight(), 0.001f);
    }

    /** The ghost has to outrank every clip on screen, which is the top layer's whole job. */
    @Test
    public void theGhostIsPromotedOnceTheDragActivates() {
        UIElement g = ghostOn(source);
        startPayloadDrag();

        assertFalse("not before the threshold", g.isInTopLayer());
        move(150f, 50f);

        assertTrue("an active drag's ghost belongs in the top layer", g.isInTopLayer());
    }

    /**
     * Ending a drag withdraws the ghost <b>and forgets it</b>.
     *
     * <p>This assertion used to say the opposite — that the ghost stayed registered for the next drag —
     * which is the contract P2 shipped with and which turned out to be wrong in practice: a retained ghost
     * outlived the drag that registered it and reappeared on unrelated pages the next time anything was
     * dragged. "Drag controller never nulled, continuing drags" fixed that by dropping the reference, and
     * this test was simply left behind asserting the old behaviour.</p>
     *
     * <p>So the rule is <b>register per drag</b>: the ghost is per-gesture state, not configuration.</p>
     */
    @Test
    public void theGhostIsWithdrawnAndForgottenWhenTheDragEnds() {
        UIElement g = ghostOn(source);
        startPayloadDrag();
        move(150f, 50f);

        release(150f, 50f);

        assertFalse("withdrawn from the top layer", g.isInTopLayer());
        assertNull("and forgotten, or it leaks into the next drag on some unrelated screen",
                drag.getGhost());
    }

    @Test
    public void theGhostIsWithdrawnOnCancelToo() {
        UIElement g = ghostOn(source);
        startPayloadDrag();
        move(150f, 50f);

        escape();

        assertFalse("a cancelled drag must not strand its ghost on screen", g.isInTopLayer());
    }

    /**
     * <b>Where the ghost actually lands.</b> The earlier tests only checked promotion and teardown, so
     * a completely wrong position sailed through: {@code screenToLocal} returns coordinates in the
     * frame {@code getX()/getY()} live in — <em>absolute logical units</em>, not an offset within the
     * element — and subtracting that directly pinned the ghost near the origin, tracking the cursor
     * 1:1 from there.
     *
     * <p>The invariant is that the grab point stays under the pointer: grab a chip 10px in from its
     * left edge and the ghost keeps sitting 10px to the left of the cursor, wherever it goes.</p>
     */
    @Test
    public void theGhostKeepsTheGrabPointUnderThePointer() {
        // Dragged from targetA, which starts at x=100 — NOT from `source` at x=0. That matters: at
        // the origin the absolute press coordinate and the grab offset are numerically identical, so
        // a fixture anchored there cannot tell the correct formula from the broken one. That is
        // exactly how the bug reached the harness.
        UIElement g = ghostOn(targetA);

        press(150f, 50f);
        // Grab 30 logical px into targetA (so absolute x=130), 10 down from its top.
        drag.startDrag(targetA, 130f * 2f, 10f * 2f, PAYLOAD, (mx, my, sx, sy, dx, dy) -> { });
        move(250f, 60f);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        float rootX = root.getRuntimeCache().getX(), rootY = root.getRuntimeCache().getY();
        float ghostX = g.getRuntimeCache().getX() - rootX;
        float ghostY = g.getRuntimeCache().getY() - rootY;

        assertEquals("ghost must sit one grab-offset left of the pointer", 250f - 30f, ghostX, 0.5f);
        assertEquals("and one grab-offset above it", 60f - 10f, ghostY, 0.5f);
    }

    /**
     * Hit-testing off must take the whole <b>subtree</b> with it, like CSS {@code pointer-events: none}.
     *
     * <p>This is the bug that made drop targeting look random. Children are tested before the
     * parent's own flag is consulted, so a pointer-transparent ghost with a text label inside was
     * transparent everywhere <em>except</em> exactly where its text was — and since the ghost lives
     * inside the drag source, every position over that text was rejected as "inside the source".
     * Dragging across a bin therefore lit it only some of the time.</p>
     *
     * <p>It went unnoticed for so long because every other user of {@code setHitTest(false)} — Button's
     * label, Checkbox's mark, Slider's parts, Switch's knob — is a childless leaf.</p>
     */
    @Test
    public void hitTestingOffAppliesToTheWholeSubtree() {
        UIElement g = ghostOn(source);
        UIElement ghostLabel = new UIElement().layout(l -> l.width(60).height(40));
        g.addChild(ghostLabel); // deliberately left hit-testable, as a naive caller would

        // Grab the source at its very top-left, so the grab offset is ~0 and the ghost's own box
        // lands directly under the cursor. With any other offset the ghost sits away from the
        // pointer and the test cannot see this bug at all — which is exactly why the first version
        // of it passed against the broken code.
        press(1f, 1f);
        drag.startDrag(source, 2f, 2f, PAYLOAD, (mx, my, sx, sy, dx, dy) -> { });

        // Two frames, with layout between. Within one tick the ghost is positioned AFTER the drop
        // hit test, so its new box is not laid out until the following frame — meaning the ghost only
        // actually sits under the cursor from the second frame onward. A single move therefore cannot
        // see this bug, which is also why it presented as intermittent on screen rather than constant.
        move(150f, 50f);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        move(150f, 50f);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();

        // Sanity: the fixture is only meaningful if the ghost really is under the pointer.
        float gx = g.getRuntimeCache().getX(), gy = g.getRuntimeCache().getY();
        assertTrue("fixture broken — the ghost is not under the pointer (" + gx + "," + gy + ")",
                150f >= gx && 150f <= gx + g.getRuntimeCache().getWidth()
                        && 50f >= gy && 50f <= gy + g.getRuntimeCache().getHeight());

        assertSame("the ghost's content must not swallow the hit; the bin underneath wins",
                targetA, drag.getDropTarget());
    }

    /** A ghost parented inside the source is excluded from drop targeting by the source rule — it
     * sits under the cursor by construction, so without that it would eat every drop. */
    @Test
    public void aGhostInsideTheSourceNeverBecomesTheDropTarget() {
        ghostOn(source);
        startPayloadDrag();
        move(150f, 50f);

        assertSame("the real target under the cursor still wins", targetA, drag.getDropTarget());
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    private void escape() {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                '\0', CgKeyCodes.KEY_ESCAPE, true, false, 3L));
    }

    @Test
    public void escapeCancelsTheDrag() {
        startPayloadDrag();
        move(150f, 50f);

        escape();

        assertFalse(drag.isDragging());
        assertFalse("cancelling must release pointer capture too", input.hasPointerCapture());
    }

    /** A target that highlighted itself on enter must get a symmetric leave, or it stays lit forever. */
    @Test
    public void cancellingTellsTheCurrentTargetTheDragLeft() {
        startPayloadDrag();
        move(150f, 50f);
        log.clear();

        escape();

        assertTrue("the stranded target must be told; log=" + log, log.contains("leave:A"));
    }

    @Test
    public void cancellingFiresNoDrop() {
        startPayloadDrag();
        move(150f, 50f);
        log.clear();

        escape();
        release(150f, 50f);

        assertTrue("a cancelled drag must never drop; log=" + log,
                log.stream().noneMatch(s -> s.startsWith("drop:")));
    }

    @Test
    public void theListenerIsToldItWasCancelledNotEnded() {
        List<String> outcome = new ArrayList<>();
        press(50f, 50f);
        drag.startDrag(source, 100f, 100f, PAYLOAD, new UIDragController.DragListener() {
            @Override public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) { }
            @Override public void onDragEnd(float mx, float my) { outcome.add("end"); }
            @Override public void onDragCancel() { outcome.add("cancel"); }
        });
        move(150f, 50f);

        escape();

        assertEquals(List.of("cancel"), outcome);
    }

    /**
     * <b>A drop handler may detach the drag source, and the drag must end rather than crash.</b>
     *
     * <p>Detaching the source cancels the drag on the spot — {@code UIInputHandler.forgetElement} has to,
     * since every coordinate a drag reports is converted through that element's transform. But the drop
     * is dispatched from <em>inside</em> {@code endDrag}, so the cancel runs underneath it, clears every
     * field, and the code that follows read one of them back. A {@code NullPointerException} out of a
     * <em>successful</em> drop.</p>
     *
     * <p>The trigger sounds exotic and is not: any target that rebuilds the list its source lives in
     * reaches it. The Blackboard did, the first time a property was dragged within it to reorder — the
     * rows are made of the pill being dragged.</p>
     */
    @Test
    public void aDropThatDetachesTheSourceEndsTheDragInsteadOfCrashing() {
        List<String> outcome = new ArrayList<>();
        // The target reacts by destroying the source, which is what "rebuild the list" amounts to.
        targetA.onDrop.attachListener((el, e) -> source.removeSelf(), false, false);

        press(50f, 50f);
        drag.startDrag(source, 100f, 100f, PAYLOAD, new UIDragController.DragListener() {
            @Override public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) { }
            @Override public void onDragEnd(float mx, float my) { outcome.add("end"); }
            @Override public void onDragCancel() { outcome.add("cancel"); }
        });
        move(150f, 50f);

        release(150f, 50f);

        assertTrue("the drop still reached the target; log=" + log,
                log.stream().anyMatch(s -> s.startsWith("drop:A")));
        // ONE ending, not two. The source was already told the drag was cancelled by the detach, so also
        // reporting a completed drag would be two contradictory endings for one gesture -- and a listener
        // acting on both would act twice.
        assertEquals("exactly one ending; got " + outcome, List.of("cancel"), outcome);
        assertFalse("and the controller is not left holding a dead drag", drag.isDragging());
    }
}
