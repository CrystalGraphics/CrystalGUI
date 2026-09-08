package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.transition.TransitionSpec;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Every dialog fades in and out, with nothing said about it.</b>
 *
 * <h3>Why nothing here asserts an opacity</h3>
 *
 * <p>{@code TransitionEngine} runs on {@code System.nanoTime()} and ignores the delta it is handed, so
 * pumping frames advances a transition by <em>nothing</em> — a test that measures the curve is measuring
 * how long its own loop took to run, which is why one appeared to show a 120ms fade taking 1.6 seconds.
 * What can be asserted is everything the fade is MADE of: the pair that gives it a value to start from,
 * the class arriving a frame late so there is a laid-out box to start from, {@code display} in the list
 * so the box outlives the close, and the element going unhittable the moment it stops showing.</p>
 */
public class DialogFadesByDefaultTest extends UiDocumentTestBase {

    private Dialog dialog;

    @Before
    public void aDialogCarryingAVariantClass() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        dialog = new Dialog("Paste Attributes");
        // CARRYING A VARIANT CLASS, and an arbitrary one on purpose: what has to hold is that the base
        // rule reaches a dialog somebody has classed, whichever class it is. A variant declaring its own
        // `transition` replaces the whole list, which is how a dialog silently loses the default.
        dialog.addClass("__variant__");
        dialog.layout(l -> l.width(120).height(80));
        document.addOverlay(dialog, document);
        frame();
    }

    /** The {@code @starting-style} half: a closed dialog has a real 0 to interpolate away from. */
    @Test
    public void aClosedDialogRestsAtZeroRatherThanAtNothing() {
        Float resting = dialog.getStyle().computed().get(StylePropertyRegistry.OPACITY);
        assertNotNull(resting);
        assertTrue("it has to be a declared value, not an absence, was " + resting, resting == 0f);
    }

    /**
     * The other half, and the one that is pure timing: the class lands a frame AFTER the box exists.
     * Added on the same frame as {@code display: flex}, the opacity would go 0 to 1 with nothing laid
     * out behind it and snap, however the sheet is written.
     */
    @Test
    public void theOpenClassArrivesAFrameAfterTheBoxDoes() {
        dialog.show();
        assertTrue("not on the frame it opens", !dialog.hasClass(Dialog.OPEN_CLASS));

        frame();
        assertTrue("on the next one, with a box to fade from", dialog.hasClass(Dialog.OPEN_CLASS));
    }

    /** Without {@code display} in the list there is no fade OUT at all — the box goes on the same frame. */
    @Test
    public void displayIsInTheTransitionListOrThereIsNoFadeOut() {
        boolean named = false;
        for (TransitionSpec spec : dialog.getStyle().getComputed(StylePropertyRegistry.TRANSITION)) {
            if ("display".equals(spec.propertyNameOrAll())) named = true;
        }
        assertTrue("display has to be named, or the box snaps to none and takes the fade with it", named);
    }

    /**
     * <b>A single-use dialog waits for its own fade before leaving the tree.</b>
     *
     * <p>The bug this replaces: four callers wrote {@code onClosed.connect(dialog::removeSelf)}, and
     * {@code onClosed} fires at the END of {@code close()} — so the dialog left on the very frame the
     * fade was meant to begin. No box, nothing to fade, and the detach demoted it too. It presented as
     * the sheet simply having no fade-out in it.</p>
     */
    @Test
    public void aSingleUseDialogStaysParentedUntilItHasFaded() {
        dialog.removeWhenClosed();
        dialog.show();
        frame();

        dialog.close();
        frame();

        assertNotNull("it is still on its way out, so it is still in the tree", dialog.parent());
        assertNotNull("and still laid out, or there would be nothing to fade", dialog.box());
    }

    /**
     * <b>No visible edge, but a ring to pulse from.</b>
     *
     * <p>A dialog has no outline for the same reason a window frame has none. The declaration is kept at
     * zero alpha rather than deleted, because {@code .__pulse__} eases {@code outline-color} from
     * whatever rests here — and a property with no resting value declines to transition and snaps, which
     * would cost the blocked-modal pulse the half of it that is an edge.</p>
     */
    @Test
    public void theEdgeIsInvisibleButStillThereToEaseFrom() {
        int resting = dialog.getStyle().getGeneralGroup().outlineColor();
        assertEquals("nothing is drawn at rest", 0, (resting >>> 24) & 0xFF);

        // THE RING IS STILL DECLARED, which is what gives `outline-color` a resting value to ease from.
        // Asserted through the WIDTH rather than by pulsing and reading the colour back: the colour is in
        // the transition list, so a frame after the class lands it holds whatever the wall clock has got
        // to by then, which is not a fact about this sheet. @see the class note above.
        LengthPercent width = dialog.getStyle().getGeneralGroup().outlineWidth();
        assertNotNull("the declaration has to survive, or the pulse's edge snaps instead of easing",
                width);
        assertTrue("…with something to draw in, was " + width, width.resolve(100f) > 0f);
    }

    /** And the box that outlives the close must not go on taking clicks while it fades. */
    @Test
    public void aClosingDialogStopsShowingAndStopsTakingClicks() {
        dialog.show();
        frame();
        assertTrue(dialog.hasClass(Dialog.OPEN_CLASS));

        dialog.close();
        assertTrue("the fade starts at once", !dialog.hasClass(Dialog.OPEN_CLASS));
        assertTrue("and it is unhittable from that moment, not when the box finally goes",
                !dialog.isHitTest());
    }
}
