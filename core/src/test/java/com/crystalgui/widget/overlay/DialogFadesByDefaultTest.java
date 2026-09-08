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
import com.crystalgui.ui.box.Box;
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
     * <b>Three edges, and the fourth is covered rather than not drawn.</b>
     *
     * <p>The body carries the outline — {@code --dialog-bg} and {@code --surface-editor} are the same
     * colour, so it is the half with nothing separating it. Per-edge width does not exist here
     * ({@code BoxPainter} strokes with {@code border().left} for all four sides, and {@code outline} has
     * one width), so the fourth side is <b>covered</b>: the caption carries a z-index and paints after
     * the body, and the body's top edge is pushed onto the caption's last row by
     * {@code outline-offset-top: 0} — outlines are drawn INSIDE the border box here, because
     * {@code ua/core.css} sets {@code outline-offset: -1px} on {@code *}.</p>
     */
    @Test
    public void theBodyCarriesTheEdgeAndTheCaptionCoversItsTop() {
        dialog.show();
        frame();

        // THE WIDTH, not the colour: outline-color has an opaque initial value whether or not an
        // outline is declared, so reading it says nothing about whether one is drawn. paintOutline
        // itself returns early on a zero stroke, which is the same question asked the same way.
        LengthPercent onBody = dialog.getContent().getStyle().getGeneralGroup().outlineWidth();
        assertNotNull(onBody);
        assertTrue("the body draws one, was " + onBody, onBody.resolve(100f) > 0f);

        int bodyColor = dialog.getContent().getStyle().getGeneralGroup().outlineColor();
        assertTrue("…and visibly, was " + Integer.toHexString(bodyColor),
                ((bodyColor >>> 24) & 0xFF) > 0x80);

        assertEquals("the dialog itself draws none", 0,
                (dialog.getStyle().getGeneralGroup().outlineColor() >>> 24) & 0xFF);

        // THE COVER. Children paint in z-index order, so a caption above the body hides the 1px of the
        // body's outline that lands on the caption's last row. Without it the header and the body are
        // ruled apart, which says they are two surfaces when they are one window.
        assertTrue("the caption has to paint after the body",
                dialog.getTitleBar().getStyle().getGeneralGroup().zIndex()
                        > dialog.getContent().getStyle().getGeneralGroup().zIndex());
    }

    /**
     * <b>A dialog does not ring itself on open.</b>
     *
     * <p>{@code :focus-visible} rings whatever holds focus, and {@code Dialog.show} focuses the dialog
     * programmatically — which rings by definition. So every dialog drew a 1px {@code --focus-ring}
     * around its whole self from the moment it appeared, which is the edge four rounds of reports were
     * actually about: it survived every change to the dialog's own border because it was never the
     * dialog's own border. {@code ua/core.css} carves pane-sized containers out of that rule and a
     * dialog is one, for the same two reasons {@code window} is.</p>
     */
    @Test
    public void aFocusedDialogDrawsNoRingOfItsOwn() {
        dialog.show();
        frame();
        frame();

        // NOTHING VISIBLE, which is the claim -- not "the width is zero". The carve-out zeroes the width
        // only while the dialog is focus-visible; unfocused it keeps the 1px ring the pulse eases from,
        // at zero alpha. Either way paintOutline draws nothing, and asserting one of the two mechanisms
        // would pass or fail on which state the fixture happened to be in.
        LengthPercent ring = dialog.getStyle().getGeneralGroup().outlineWidth();
        int colour = dialog.getStyle().getGeneralGroup().outlineColor();
        boolean invisible = ring == null || ring.resolve(100f) <= 0f || ((colour >>> 24) & 0xFF) == 0;
        assertTrue("a dialog is pane-sized and focuses itself, so it must not ring: width=" + ring
                + " colour=" + Integer.toHexString(colour), invisible);
    }

    /**
     * …and the carve-out must not take the blocked-modal pulse with it. The two rules are the same
     * weight, so the pulse wins only by sitting in a later part of the sheet than {@code ua/core.css}.
     */
    @Test
    public void theBlockedPulseStillOutranksTheCarveOut() {
        dialog.show();
        frame();
        dialog.addClass("__pulse__");
        frame();

        LengthPercent pulsing = dialog.getStyle().getGeneralGroup().outlineWidth();
        assertNotNull(pulsing);
        // THE WIDTH ONLY. outline-color is in the transition list, so a frame after the class lands it
        // is still easing up from transparent -- reading it here asserts the wall clock, not the sheet.
        assertTrue("the pulse has to draw an edge, was " + pulsing, pulsing.resolve(100f) > 0f);
    }

    /**
     * <b>The body's top edge is pushed out of the body, or the caption cannot cover it.</b>
     *
     * <p>{@code ua/core.css} sets {@code outline-offset: -1px} on {@code *} so a focus ring survives an
     * ancestor's clip, which means outlines are drawn INSIDE the border box. At that inherited offset
     * the body's top edge sits on the body's own first row — below the caption, and impossible to hide
     * behind it. This is the one pixel that puts it back on the caption's last row.</p>
     */
    @Test
    public void theBodysTopEdgeSitsOnTheCaptionNotOnTheBody() {
        dialog.show();
        frame();

        LengthPercent top = dialog.getContent().getStyle()
                .getComputed(StylePropertyRegistry.OUTLINE_OFFSET_TOP);
        assertNotNull("the top offset has to be stated, or `*` gives it -1px", top);
        assertEquals("…and be zero, which is what lifts the stroke off the body's first row",
                0f, top.resolve(100f), 0.01f);

        LengthPercent left = dialog.getContent().getStyle()
                .getComputed(StylePropertyRegistry.OUTLINE_OFFSET_LEFT);
        assertNotNull(left);
        assertTrue("the other three keep the inherited inset, was " + left, left.resolve(100f) < 0f);
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
