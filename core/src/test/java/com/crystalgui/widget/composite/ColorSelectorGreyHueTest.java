package com.crystalgui.widget.composite;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.event.MouseEvent;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The hue ring must respond even when the colour it produces cannot change.
 *
 * <p>At {@code saturation == 0} every hue composes to the same grey, and at {@code value == 0} to the
 * same black — so a genuine move on the ring can leave the ARGB byte-identical. Everything the ring
 * drives hangs off {@code color.changed}, and {@code Property.set} suppresses an equal value, so the
 * whole widget would sit still under the drag: handle frozen, SV square still showing the previous hue.
 * That reads as <b>the ring being unclickable</b>, not as the colour being unchanged.</p>
 *
 * <p>It is the first thing a user meets rather than an edge case: a shader node's colour defaults to
 * {@code vec4(1.0, 1.0, 1.0, 1.0)}, which is white, which is exactly the case with no hue to show. The
 * gallery missed it by opening on a saturated purple.</p>
 */
public class ColorSelectorGreyHueTest extends UiDocumentTestBase {


    private ColorSelector picker;

    private void open(int initial) {
        UIElement root = new UIElement();
        document.append(root);
        // The ring's size and the handle's percentage insets both live in the user-agent sheet, and it is
        // not installed for you — without it every box is 0 and the handle can never move anywhere.
        document.styleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);

        picker = new ColorSelector();
        root.append(picker);
        picker.setInitialColor(initial);
        frame();
    }

    private UIElement part(String cssClass) {
        return deepAll(picker, "." + cssClass).get(0);
    }

    /**
     * Presses the ring where the given hue is drawn — the real gesture, through the real listener.
     *
     * <p>Dispatched at the ring directly rather than through hit-testing: the defect is in what the
     * press does, so routing is a different test's business.</p>
     */
    private void pressRingAt(float hue) {
        UIElement ring = part(ColorSelector.RING_CLASS);
        var cache = ring.box();
        // Local space includes the element's own layout position — see ColorSelector.withinX.
        float[] offset = ColorSelector.offsetForHue(hue, 0.43f);
        float localX = cache.x() + cache.width() * (0.5f + offset[0]);
        float localY = cache.y() + cache.height() * (0.5f + offset[1]);
        var world = Transform2D.apply(cache.localToWorld(), localX, localY);

        var handler = document.input();
        handler.send(ring, new MouseEvent.Down(ring,
                new ReadOnlyVec2f(new org.joml.Vector2f(world.x(), world.y())), 0, 1));
        frame();
    }

    /** The handle's resolved position is the only observable proof the press was acted on. */
    private float handleX() {
        return part(ColorSelector.RING_HANDLE_CLASS).box().x();
    }

    @Test
    public void pressingTheRingOnWhiteStillMovesTheHandle() {
        open(0xFFFFFFFF); // a Color node's default — saturation 0, so every hue composes to this
        float before = handleX();

        pressRingAt(0.25f); // green, hard left of the ring — the largest possible move

        assertEquals("white composes from every hue, so an unchanged colour is CORRECT here",
                0xFFFFFFFF, picker.getColor());
        assertNotEquals("the handle must still travel, or the ring reads as dead",
                before, handleX(), 0.5f);
    }

    @Test
    public void theSameHoldsAtValueZero() {
        open(0xFF000000);
        float before = handleX();
        pressRingAt(0.25f);
        assertEquals(0xFF000000, picker.getColor());
        assertNotEquals("black hides its hue too, and for the same reason",
                before, handleX(), 0.5f);
    }

    /** The ordinary path must not have gained a double refresh or lost its emit. */
    @Test
    public void aSaturatedPressStillEmitsExactlyOnce() {
        open(0xFFB00DDB);
        int[] emits = { 0 };
        picker.onColorChanged.connect(argb -> emits[0]++);

        pressRingAt(0.25f);

        assertEquals("one press, one emit", 1, emits[0]);
        assertNotEquals(0xFFB00DDB, picker.getColor());
    }
}
