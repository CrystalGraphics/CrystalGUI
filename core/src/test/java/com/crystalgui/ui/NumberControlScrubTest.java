package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.control.NumberControl;
import com.crystalgui.ui.input.DragScrub;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.3.10 — dragging a number's label to change it, at the widget level.
 *
 * <h3>What this covers that {@code DragScrubTest} cannot</h3>
 * <p>The arithmetic is pinned there. What is left is everything the gesture has to negotiate with the
 * rest of the engine: that a press which does not travel is still a click, that the value the box
 * <em>shows</em> follows the value it holds, that a cancelled drag puts things back, and that a host
 * hears one gesture rather than a hundred changes.</p>
 *
 * <p>Not extending {@code UiTestBase} for the reason its own javadoc gives: this needs an input service
 * reporting live modifier state, which is a real fixture rather than duplication.</p>
 */
public class NumberControlScrubTest {

    private static final float LABEL = 40f;

    private int modifiers;
    private UIWindow window;
    private NumberControl number;
    private UIText label;

    /** Every value the control reported, in order. */
    private final List<Double> reported = new ArrayList<>();

    /** Every gesture boundary the control reported, in order. */
    private final List<Boolean> gestures = new ArrayList<>();

    @Before
    public void setUp() {
        modifiers = 0;
        reported.clear();
        gestures.clear();

        TestPlatformService.install().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return modifiers; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return ""; }
            @Override public void setClipboard(String text) { }
        });

        label = new UIText("X");
        label.layout(l -> l.width(LABEL).height(LABEL));

        number = new NumberControl(ConfigDescriptor.number("value", "X"), 10d);
        number.layout(l -> l.width(80).height(LABEL));
        number.changed.connect(v -> reported.add((Double) v));
        number.interacting.connect(gestures::add);
        number.scrubWith(label);

        UIElement row = new UIElement().layout(l -> l.width(400).height(400));
        row.addChild(label);
        row.addChild(number);

        window = new UIWindow(Ui.of(row));
        // uiScale 1 so logical and physical coordinates coincide and the test can speak in one of them.
        window.init(400, 400);
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    // ── Pointer plumbing ────────────────────────────────────────────────────

    /**
     * The label's centre as a <b>physical</b> pointer position.
     *
     * <p>Through the element's own {@code localToWorld}, never a hand-rolled offset: {@code getX()} is
     * expressed in the frame {@code screenToLocal} maps into, which is not screen space — the root
     * carries a transform, and here the box reports a negative origin. Computing the pointer position
     * any other way tests the test.</p>
     */
    private org.joml.Vector2f labelCentre() {
        var cache = label.getRuntimeCache();
        return com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    private float labelCentreX() {
        return labelCentre().x();
    }

    private float labelCentreY() {
        return labelCentre().y();
    }

    private void press() {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(labelCentreX()), Math.round(labelCentreY()), 0, 0, 0, true, 0f, 1L));
        frame();
    }

    /** Moves the pointer to an offset from where the press landed, and lets a frame tick the drag. */
    private void moveBy(float dx, float dy) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(labelCentreX() + dx), Math.round(labelCentreY() + dy), 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void release(float dx, float dy) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(labelCentreX() + dx), Math.round(labelCentreY() + dy), 0, 0, 0, false, 0f, 2L));
        frame();
    }

    private double value() {
        Double held = number.getValue();
        return held == null ? Double.NaN : held;
    }

    // ── The gesture ─────────────────────────────────────────────────────────

    @Test
    public void draggingRightIncreasesAndLeftDecreases() {
        press();
        moveBy(60f, 0f);
        double increased = value();
        release(60f, 0f);
        assertTrue("dragging right should raise the value, got " + increased, increased > 10d);

        press();
        moveBy(-60f, 0f);
        double decreased = value();
        release(-60f, 0f);
        assertTrue("dragging left should lower the value, got " + decreased, decreased < increased);
    }

    @Test
    public void draggingUpIncreases() {
        press();
        moveBy(0f, -60f);
        assertTrue(value() > 10d);
        release(0f, -60f);
    }

    /**
     * <b>A press that does not travel is still a click.</b>
     *
     * <p>Without this the label stops being a way <em>into</em> the box and becomes only a way to change
     * it — and an accidental one-unit edit to a shader parameter is both easy to cause and hard to spot.
     */
    @Test
    public void aPressBelowTheThresholdChangesNothing() {
        press();
        moveBy(DragScrub.DEFAULT_THRESHOLD_PX - 2f, 0f);
        release(DragScrub.DEFAULT_THRESHOLD_PX - 2f, 0f);

        assertEquals(10d, value(), 0d);
        assertTrue("a click must not report a change", reported.isEmpty());
        assertTrue("a click must not open a gesture", gestures.isEmpty());
    }

    /** ...and it focuses the field instead, so the label remains the obvious place to press. */
    @Test
    public void aClickOnTheLabelFocusesTheField() {
        press();
        release(0f, 0f);
        assertSame(number.field(), window.getInputHandler().getFocusedElement());
    }

    /**
     * The box has to repaint as the value moves. Committing without writing the widgets leaves the field
     * showing the value the drag started on for the whole gesture — a scrub that looks broken while
     * working perfectly.
     */
    @Test
    public void theFieldTextFollowsTheValue() {
        press();
        moveBy(60f, 0f);
        assertNotEquals("10", number.field().getText());
        assertEquals(Double.parseDouble(number.field().getText()), value(), 0.01d);
        release(60f, 0f);
    }

    // ── Boundaries the host depends on ──────────────────────────────────────

    /**
     * <b>One gesture, not one per frame.</b> This is what a host maps onto a held merge run; without the
     * pair, a two-second scrub becomes ~120 undo steps.
     */
    @Test
    public void aScrubReportsExactlyOneGesture() {
        press();
        moveBy(20f, 0f);
        moveBy(40f, 0f);
        moveBy(60f, 0f);
        release(60f, 0f);

        assertEquals("one open and one close", List.of(true, false), gestures);
        assertTrue("values must arrive live, not only at release", reported.size() > 1);
    }

    /**
     * Escape mid-drag puts the value back.
     *
     * <p>Driven through {@code cancelDrag} directly, which is exactly what {@code UIInputHandler} calls
     * when Escape arrives during a drag — it consumes the key before anything else sees it.</p>
     */
    @Test
    public void cancellingRestoresTheValueTheDragStartedOn() {
        press();
        moveBy(80f, 0f);
        assertNotEquals(10d, value(), 0.001d);

        window.getInputHandler().getDragController().cancelDrag();
        frame();

        assertEquals("a cancelled scrub must land back on its anchor", 10d, value(), 0d);
        assertEquals("the gesture must still be closed on the cancel path", List.of(true, false), gestures);
    }

    /**
     * <b>Drag out and back, and the value returns exactly.</b> The widget-level statement of the property
     * {@code DragScrubTest} pins arithmetically: every frame recomputes from the anchor, so nothing
     * accumulates across the ~dozen frames this gesture actually takes.
     */
    @Test
    public void returningThePointerReturnsTheValue() {
        press();
        for (float dx = 10f; dx <= 100f; dx += 10f) moveBy(dx, 0f);
        for (float dx = 100f; dx >= 0f; dx -= 10f) moveBy(dx, 0f);
        release(0f, 0f);

        assertEquals(10d, value(), 0d);
    }

    // ── Modifiers, through the real service ─────────────────────────────────

    /**
     * Each gesture is measured from the same anchor. Chaining them instead would compare deltas taken at
     * three different magnitudes — and since the rate scales with magnitude, that measures the sensitivity
     * curve rather than the modifiers.
     */
    private double scrubDelta(int heldModifiers) {
        modifiers = heldModifiers;
        number.setValue(10d);   // silent: a programmatic write, not a user edit
        press();
        moveBy(40f, 0f);
        double moved = value() - 10d;
        release(40f, 0f);
        modifiers = 0;
        return moved;
    }

    @Test
    public void shiftScrubsFasterAndCtrlSlower() {
        double plain = scrubDelta(0);
        double coarse = scrubDelta(com.crystalgraphics.platform.input.CgModifiers.SHIFT);
        double fine = scrubDelta(com.crystalgraphics.platform.input.CgModifiers.CTRL);

        assertTrue("shift should move further: " + coarse + " vs " + plain, coarse > plain);
        assertTrue("ctrl should move less: " + fine + " vs " + plain, fine < plain);
    }
}
