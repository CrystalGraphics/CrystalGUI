package com.crystalgui.ui.service;

import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.DocumentDriver;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** An animation handed to the compositor: played only where one presents, on a clock that starts when it does. */
public class CompositorAnimationTest {

    private static final long DURATION = 100_000_000L;

    private static CompositorAnimation flight(UIElement target) {
        return new CompositorAnimation(target, Transform.IDENTITY, Transform.scale(0.5f), 1f, 0f,
                LengthPercent.ZERO, LengthPercent.ZERO, DURATION, t -> t);
    }

    @Test
    public void anAsyncDocumentHandsItToTheCompositorAndAnInlineOneDoesNot() {
        UIDocument async = new UIDocument();
        DocumentDriver<Void> driver = DocumentDriver.attach(async, DocumentDriver.Mode.ASYNC, "compositor");
        try {
            UIElement window = new UIElement();
            CompositorAnimation played = flight(window);
            assertTrue(driver.ask(() -> async.animation().playOnCompositor(played)));
            assertSame(played, driver.ask(() -> async.animation().onCompositor(window)));
            driver.run(() -> async.animation().stopOnCompositor(played));
            assertNull(driver.ask(() -> async.animation().onCompositor(window)));
        } finally {
            driver.close();
        }

        UIDocument inline = new UIDocument();
        DocumentDriver.attach(inline, DocumentDriver.Mode.INLINE, "inline");
        assertFalse(inline.animation().playOnCompositor(flight(new UIElement())));
        assertTrue(inline.animation().onCompositor().isEmpty());
    }

    @Test
    public void theClockHoldsTheStartUntilTheCompositorStartsIt() {
        CompositorAnimation flight = flight(new UIElement());
        long now = System.nanoTime();
        assertEquals(Transform.IDENTITY, flight.transformAt(now + DURATION));
        assertEquals(1f, flight.opacityAt(now + DURATION), 0f);
        assertFalse(flight.isFinished(now + DURATION));

        flight.startAt(now);
        flight.startAt(now + DURATION);
        assertEquals("a second start moves nothing", 0.5f, flight.opacityAt(now + DURATION / 2), 1e-4f);
        assertTrue(flight.isFinished(now + DURATION));
        assertEquals(Transform.scale(0.5f), flight.transformAt(now + DURATION));
        assertEquals(0f, flight.opacityAt(now + 2 * DURATION), 0f);
    }

    @Test
    public void forgettingASubtreeDropsWhatItsBoxesPlay() {
        Animation animation = new Animation(() -> true);
        UIElement window = new UIElement();
        UIElement content = new UIElement();
        window.append(content);
        animation.playOnCompositor(flight(content));
        animation.forget(window);
        assertTrue(animation.onCompositor().isEmpty());
    }
}
