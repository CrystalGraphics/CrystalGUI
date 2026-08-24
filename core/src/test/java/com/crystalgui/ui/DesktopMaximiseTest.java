package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CrystalOS W6 — maximise and restore ({@code plan_windowing.md}).
 *
 * <p>Deferred in the first draft of the plan <em>because</em> nothing could be less than full-screen;
 * with a compositor that reason is gone. The interesting half is not filling the screen, it is coming
 * back: the restore rect has to survive a maximise, a work-area resize, and the clamp that runs on
 * every layout pass and would otherwise write a position over the top of it.</p>
 */
public class DesktopMaximiseTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private Desktop desktop;
    private UIInputHandler input;

    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    private WindowFrame open(String title) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(160, 120).moveTo(40, 30);
        settle();
        return frame;
    }

    private float areaWidth() {
        return desktop.windowLayer().getRuntimeCache().getWidth();
    }

    private float areaHeight() {
        return desktop.windowLayer().getRuntimeCache().getHeight();
    }

    private int handleCountOf(UIElement target) {
        int handles = 0;
        for (UIElement child : target.getChildren()) {
            if (child.hasClass(UIElement.RESIZER_CLASS)) handles++;
        }
        return handles;
    }

    /**
     * A gesture clock.
     *
     * <p>Multi-click counting is real: a press within {@code multiClickInterval} of the last one, and
     * within a few pixels of it, continues the run. So a fixture that stamps every event with the same
     * millisecond does not send two double-clicks — it sends one four-click, and the second gesture
     * toggles twice. Each gesture starts past the interval, which is what makes it a new run.</p>
     */
    private long clock = 10_000L;

    /** Presses at a LOGICAL point, converted to the surface pixels the input layer actually takes. */
    private void clickTimes(float x, float y, int presses) {
        clock += UIInputHandler.multiClickInterval + 50L;
        for (int i = 0; i < presses; i++) {
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, clock));
            input.beginFrame();
            input.endFrame();
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, false, 0f, clock));
            input.beginFrame();
            input.endFrame();
        }
    }

    /** Two presses on the caption <b>as it is now</b> — see the note in the double-click test. */
    private void doubleClickCaption(WindowFrame frame) {
        UIElement bar = frame.titleBar();
        clickTimes(bar.getRuntimeCache().getX() + 30f,
                bar.getRuntimeCache().getY() + bar.getRuntimeCache().getHeight() / 2f, 2);
    }

    // ── The rect ────────────────────────────────────────────────────────────

    /** Maximise fills the WORK AREA, which needs no special case: the window layer's box already is it,
     * because the taskbar is laid out rather than overlaid. */
    @Test
    public void maximiseFillsTheWorkAreaAndRestoreReturnsTheExactRect() {
        build();
        WindowFrame frame = open("One");
        settle();
        float width = frame.getRuntimeCache().getWidth();
        float height = frame.getRuntimeCache().getHeight();

        frame.maximize();
        settle();

        assertTrue(frame.isMaximized());
        assertEquals("fills the work area's width", areaWidth(), frame.getRuntimeCache().getWidth(), 0.51f);
        assertEquals("and its height — the taskbar's row is not part of it",
                areaHeight(), frame.getRuntimeCache().getHeight(), 0.51f);
        assertTrue("which is genuinely short of the desktop, or this proves nothing",
                areaHeight() < desktop.getRuntimeCache().getHeight() - 1f);

        frame.restore();
        settle();

        assertFalse(frame.isMaximized());
        assertEquals(width, frame.getRuntimeCache().getWidth(), 0.51f);
        assertEquals(height, frame.getRuntimeCache().getHeight(), 0.51f);
        assertEquals(40f, frame.left(), 0.51f);
        assertEquals(30f, frame.top(), 0.51f);
    }

    /**
     * <b>The clamp must not write a position over a maximised window.</b> It runs from the layout
     * callback and from the work-area re-clamp — so on the very next pass it would put the window back
     * where it was before maximising, and the fill would last one frame.
     */
    @Test
    public void theClampLeavesAMaximisedWindowAlone() {
        build();
        WindowFrame frame = open("One");
        frame.maximize();

        for (int pass = 0; pass < 4; pass++) settle();

        assertEquals(areaWidth(), frame.getRuntimeCache().getWidth(), 0.51f);
        assertEquals(0f, frame.getRuntimeCache().getX() - desktop.windowLayer().getRuntimeCache().getX(), 0.51f);
    }

    /** A maximised window follows the work area, because it is sized in per cent of it rather than
     * given the numbers once. */
    @Test
    public void aMaximisedWindowFollowsAResizingWorkArea() {
        build();
        WindowFrame frame = open("One");
        frame.maximize();
        settle();

        root.layout(l -> l.width(260));
        settle();

        assertEquals(areaWidth(), frame.getRuntimeCache().getWidth(), 0.51f);
        assertTrue("...which really did change", areaWidth() < 300f);
    }

    /** And the rect it comes back to is the one from before, not one the resize happened to leave. */
    @Test
    public void restoringAfterAWorkAreaResizeStillReturnsTheOriginalRect() {
        build();
        WindowFrame frame = open("One");
        frame.maximize();
        settle();
        root.layout(l -> l.width(260));
        settle();

        frame.restore();
        settle();

        assertEquals(160f, frame.getRuntimeCache().getWidth(), 0.51f);
        assertEquals(40f, frame.left(), 0.51f);
    }

    // ── The chrome ──────────────────────────────────────────────────────────

    /** A maximised window is not resizable on any desktop — and the handles go through CSS, so nothing
     * in Java has to know they exist. */
    @Test
    public void maximisingTakesTheResizeHandlesAway() {
        build();
        WindowFrame frame = open("One");
        settle();
        assertEquals("eight handles while floating", 8, handleCountOf(frame));

        frame.maximize();
        settle();
        assertEquals("none while maximised", 0, handleCountOf(frame));

        frame.restore();
        settle();
        assertEquals("and back again", 8, handleCountOf(frame));
    }

    @Test
    public void theMaximizeButtonToggles() {
        build();
        WindowFrame frame = open("One");

        frame.maximizeButton().onPressed.emit();
        assertTrue(frame.isMaximized());

        frame.maximizeButton().onPressed.emit();
        assertFalse(frame.isMaximized());
    }

    // ── The gestures ────────────────────────────────────────────────────────

    /** Double-clicking the caption toggles — Windows' gesture, through the real mouse path so the
     * click counting is the engine's rather than the fixture's. */
    @Test
    public void doubleClickingTheTitleBarToggles() {
        build();
        WindowFrame frame = open("One");
        settle();

        // RE-MEASURED PER GESTURE. Maximising moves the caption to the top of the work area, so a point
        // taken from the floating window lands in the content area afterwards and the second gesture
        // hits nothing — which reads as "restore is broken" and is really the fixture aiming at where
        // the bar used to be.
        doubleClickCaption(frame);
        settle();
        assertTrue("two presses in the same place is a double-click", frame.isMaximized());

        doubleClickCaption(frame);
        settle();
        assertFalse(frame.isMaximized());
    }

    /** A single press must NOT toggle — the same listener sees both, so a fixture that only ever
     * double-clicks would pass against a version that toggled on every press. */
    @Test
    public void aSinglePressOnTheTitleBarDoesNotMaximise() {
        build();
        WindowFrame frame = open("One");
        settle();

        UIElement bar = frame.titleBar();
        clickTimes(bar.getRuntimeCache().getX() + 30f,
                bar.getRuntimeCache().getY() + bar.getRuntimeCache().getHeight() / 2f, 1);
        settle();

        assertFalse(frame.isMaximized());
    }

    /**
     * <b>Dragging a maximised window restores it under the pointer — anchored, not proportional.</b>
     *
     * <p>The cursor keeps its DISTANCE FROM THE NEARER CAPTION EDGE, because that is how the caption's
     * own content is anchored: an adopted menu bar runs from the left, the window controls sit at the
     * right, and neither rescales when the window shrinks. A fraction of the caption preserves nothing
     * you can see — grabbing a menu item a fifth of the way along a wide caption and landing a fifth of
     * the way along a narrow one puts the cursor over a different item entirely.</p>
     *
     * <p>Where the grab is further from either edge than half the restored window is wide, there is
     * nothing to anchor to — that part of a maximised caption is empty — and the window is centred under
     * the cursor instead. Half the width is each edge's reach precisely because it makes the three cases
     * one continuous function.</p>
     */
    @Test
    public void draggingAMaximisedCaptionRestoresUnderThePointer() {
        build();
        WindowFrame frame = open("One");
        frame.maximize();
        settle();

        UIElement bar = frame.titleBar();
        float grabX = bar.getRuntimeCache().getX() + 20f;
        assertTrue("the fixture must grab within an edge's reach, or it tests the middle case",
                20f < 160f / 2f);

        assertEquals("the grabbed point left the cursor", 20f, tearLooseAt(frame, grabX), 6f);
        assertFalse("the movement restored it", frame.isMaximized());
        assertEquals("and it is the size it was", 160f, frame.getRuntimeCache().getWidth(), 0.51f);
    }

    /**
     * <b>...and a grab in the empty middle centres the window under the cursor.</b>
     *
     * <p>The counter-assertion the one above needs: preserving the left offset for every grab is the
     * obvious fix for the menu-bar case and it drags the window out from under a hand that grabbed
     * anywhere past the restored width, leaving the whole window hanging off one side of the pointer.</p>
     */
    @Test
    public void aGrabInTheEmptyMiddleCentresTheWindow() {
        build();
        WindowFrame frame = open("One");
        frame.maximize();
        settle();

        UIElement bar = frame.titleBar();
        float grabX = bar.getRuntimeCache().getX() + bar.getRuntimeCache().getWidth() * 0.75f;

        assertEquals("a grab too far from either edge to anchor did not centre the window",
                160f / 2f, tearLooseAt(frame, grabX), 6f);
    }

    /**
     * Presses a maximised caption at {@code grabX}, moves 12 surface px, and answers how far the pointer
     * then sits from the restored window's left edge.
     *
     * <p>The press and the movement are separate on purpose: a maximised window restores on the first
     * MOVEMENT and never on the press, or the first press of a double-click would restore and the second
     * re-maximise, which makes double-clicking a maximised caption appear to do nothing.</p>
     */
    private float tearLooseAt(WindowFrame frame, float grabX) {
        UIElement bar = frame.titleBar();
        float grabY = bar.getRuntimeCache().getY() + bar.getRuntimeCache().getHeight() / 2f;
        clock += UIInputHandler.multiClickInterval + 50L;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(grabX * 2f), Math.round(grabY * 2f), 0, 0, 0, true, 0f, clock));
        input.beginFrame();
        input.endFrame();
        settle();

        assertTrue("the PRESS alone must not restore — that is what breaks double-click",
                frame.isMaximized());

        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(grabX * 2f) + 12, Math.round(grabY * 2f), 0, 0, -1, false, 0f, clock + 10));
        input.beginFrame();
        input.endFrame();
        settle();

        // A SECOND FRAME, because the anchor is re-derived from the width the window HAS and that lags
        // the width it was just given by one layout pass. On the tear frame the runtime cache still holds
        // the maximised width, so the first placement is computed against it -- which is not a defect but
        // the feature: with the shrink animating, re-anchoring every frame is what makes the window close
        // in AROUND the cursor instead of collapsing toward one edge. It simply means the settled answer
        // is the second one, and a test that reads the first is reading the start of the shrink.
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(grabX * 2f) + 24, Math.round(grabY * 2f), 0, 0, -1, false, 0f, clock + 20));
        input.beginFrame();
        input.endFrame();
        settle();

        return (grabX + 12f) - frame.left();
    }

    /** Restoring only happens for a window that was maximised — a press on an ordinary caption must
     * not disturb its rect at all. */
    @Test
    public void pressingAnOrdinaryCaptionChangesNothing() {
        build();
        WindowFrame frame = open("One");
        settle();

        UIElement bar = frame.titleBar();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((bar.getRuntimeCache().getX() + 20f) * 2f),
                Math.round((bar.getRuntimeCache().getY() + 4f) * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        settle();

        assertEquals(40f, frame.left(), 0.51f);
        assertEquals(30f, frame.top(), 0.51f);
        assertNull(null);
    }
}
