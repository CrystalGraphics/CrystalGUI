package com.crystalgui.ui.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>The drag threshold is a logical distance, so it means the same thing at every scale.</b>
 *
 * <p>It was read as surface pixels, on the argument that a threshold is physical because the hand moves
 * in pixels. True of the hand and not of the interface: at the default {@code uiScale} of 2 the real
 * threshold was <b>two logical pixels</b>, and the lightest movement during an ordinary click armed a
 * drag. Reported as "even the slightest of drags" tearing a tool window off its rail.</p>
 *
 * <p>Every platform states this in the units the interface is laid out in — Windows' {@code SM_CXDRAG},
 * Qt's {@code startDragDistance}, GTK's drag threshold — and all of them scale with the display.</p>
 */
public class DragThresholdScalesTest extends UiDocumentTestBase {

    private Drag dragFrom(float scale) {
        document.boxes().setUiScale(scale);
        UIElement source = new UIElement();
        document.append(source);
        frame();
        return Drag.start(source, 100f, 100f, CgMouseCodes.LEFT_BUTTON, "payload",
                Drag.DEFAULT_THRESHOLD_PX, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                    }
                });
    }

    /** At 1x the surface and the interface agree, so the number is the number. */
    @Test
    public void atOneToOneTheThresholdIsTheDeclaredDistance() {
        Drag drag = dragFrom(1f);
        drag.pointerMoved(100f + Drag.DEFAULT_THRESHOLD_PX - 1f, 100f);
        assertFalse("a movement shorter than the threshold armed the drag", drag.isActivated());

        drag.pointerMoved(100f + Drag.DEFAULT_THRESHOLD_PX + 1f, 100f);
        assertTrue("a movement past the threshold did not arm the drag", drag.isActivated());
    }

    /**
     * At 2x it takes twice the surface distance — which is the same distance on screen.
     *
     * <p>The case that was wrong: four surface pixels is where the old threshold armed, and four surface
     * pixels at this scale is two logical ones — a wobble, not a drag.</p>
     */
    @Test
    public void atTwoToOneItTakesTwiceTheSurfaceDistance() {
        Drag drag = dragFrom(2f);
        drag.pointerMoved(100f + Drag.DEFAULT_THRESHOLD_PX, 100f);
        assertFalse("four surface pixels armed a drag at uiScale 2, which is two logical pixels -- the "
                + "wobble in an ordinary click", drag.isActivated());

        drag.pointerMoved(100f + Drag.DEFAULT_THRESHOLD_PX * 2f + 1f, 100f);
        assertTrue("twice the surface distance is the declared logical distance and did not arm the drag",
                drag.isActivated());
    }

    /**
     * The counter-control: a positional drag still has no threshold at all.
     *
     * <p>Scrollbars and dividers are live from the first movement by design — scaling a zero must leave
     * it zero, or every one of them gains a dead zone.</p>
     */
    @Test
    public void aPositionalDragIsStillLiveImmediately() {
        document.boxes().setUiScale(2f);
        UIElement source = new UIElement();
        document.append(source);
        frame();

        Drag drag = Drag.start(source, 100f, 100f, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
            }
        });
        assertTrue("a positional drag has to be live before it moves at all", drag.isActivated());
    }
}
