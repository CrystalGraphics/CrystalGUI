package com.crystalgui.widget.control;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.Drag;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Dragging, driven through the same entry point real input uses
 * ({@link com.crystalgui.ui.input.Input#consumeMouseEvent}).
 *
 * <p><b>Why this exists.</b> {@link com.crystalgui.ui.elements.SliderValueTest} covers the value math
 * and passed while dragging was completely broken, because the break was never in the math — raw
 * pointer positions arrive in <em>physical</em> pixels while element geometry is in <em>logical</em>
 * units, so at the default {@code uiScale} of 2 the slider's own bounds check rejected almost every
 * real press and no drag ever started. Neither a value-math test nor a static screenshot can catch
 * that; only pushing events through the handler can.</p>
 *
 * <p>Runs headless: layout and input need no GL context, only painting does, so these drive
 * {@code calculateStyle} + {@code calculateLayout} directly instead of {@code paintFrame()}.</p>
 */
public class SliderDragTest extends UiDocumentTestBase {

    private static final float EPS = 0.001f;


    private Slider slider;

    /** {@code Input}'s constructor asks the adapter how many mouse buttons the platform
     * has, so one has to be registered before any {@code UIDocument} exists. Nothing here needs real
     * platform state — the tests synthesize events directly rather than polling. */
    /** Builds a real document/stylesheet/layout at the given scale and returns the laid-out slider. */
    private Slider setUp(float uiScale) {
        // Called twice by dragResultIsIndependentOfUiScale, and the base\'s document
        // persists where the old fixture built a fresh UIWindow each time.
        document.removeAll();
        slider = new Slider();
        document.append(slider);
        document.boxes().setUiScale(uiScale);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));

        frame();
        return slider;
    }



    private void mouseTo(int physX, int physY) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, -1, false, 0f, -1L));
    }

    private void press(int physX, int physY) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void release(int physX, int physY) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }

    /** Physical-pixel centre of the thumb, i.e. where a user would actually grab it. */
    private int thumbCentrePhysX(float uiScale) {
        var thumb = slider.box();
        return Math.round(thumb.worldX() + thumb.width() / 2f * uiScale);
    }

    /** The regression that was actually shipped broken: a press at the default scale must start a
     * drag at all. Before the fix the slider compared physical coordinates against logical geometry
     * and rejected the press outright. */
    @Test
    public void pressOnTheSliderStartsADragAtDefaultUiScale() {
        setUp(2f);
        var cache = slider.box();
        int physX = Math.round(cache.worldX() + cache.width() / 2f * uiScale());
        int physY = Math.round(cache.worldY() + cache.height() / 2f * uiScale());

        mouseTo(physX, physY);
        frame();
        press(physX, physY);

        assertTrue("press over the slider did not start a drag",
                document.input().mode(Drag.class) != null);
    }

    /** A click on the track jumps to that position — the thumb's centre lands under the cursor. */
    @Test
    public void clickingTheTrackJumpsToThatPosition() {
        setUp(2f);
        var layout = slider.box();
        var cache = slider.box();
        // Three-quarters along the content box, in physical pixels.
        float localX = cache.x() + layout.border().left + layout.padding().left
                + layout.contentBoxWidth() * 0.75f;
        int physY = Math.round(cache.worldY() + cache.height() / 2f * uiScale());

        mouseTo(Math.round(localX * 2f), physY);
        frame();
        press(Math.round(localX * 2f), physY);

        assertEquals(0.75f, slider.getValue(), 0.02f);
    }

    /** Dragging tracks the cursor by delta from the grab point. */
    @Test
    public void draggingMovesTheValueByTheCursorDelta() {
        setUp(2f);
        slider.setValue(0.5f);
        frame();

        var cache = slider.box();
        int physY = Math.round(cache.worldY() + cache.height() / 2f * uiScale());
        int grabX = thumbCentrePhysX(2f);

        mouseTo(grabX, physY);
        frame();
        press(grabX, physY);

        // Quarter of the travel, expressed in physical pixels.
        float travel = slider.box().contentBoxWidth();
        int moveTo = grabX + Math.round(travel * 0.25f * 2f);
        mouseTo(moveTo, physY);
        frame();

        assertEquals(0.75f, slider.getValue(), 0.02f);

        release(moveTo, physY);
        assertFalse(document.input().mode(Drag.class) != null);
    }

    /**
     * The load-bearing guard against the physical/logical confusion coming back: the same gesture,
     * expressed in each scale's own physical pixels, must produce the same value. Any surviving
     * coordinate-space mismatch scales the result and breaks this.
     */
    @Test
    public void dragResultIsIndependentOfUiScale() {
        assertEquals(dragQuarterTravelAtScale(1f), dragQuarterTravelAtScale(2f), 0.02f);
    }

    private float dragQuarterTravelAtScale(float uiScale) {
        setUp(uiScale);
        slider.setValue(0.5f);
        frame();

        var cache = slider.box();
        int physY = Math.round(cache.worldY() + cache.height() / 2f * uiScale);
        int grabX = thumbCentrePhysX(uiScale);

        mouseTo(grabX, physY);
        frame();
        press(grabX, physY);

        float travel = slider.box().contentBoxWidth();
        mouseTo(grabX + Math.round(travel * 0.25f * uiScale), physY);
        frame();
        return slider.getValue();
    }

    /**
     * Changing {@code uiScale} on a live document must re-point hit-testing, which means invalidating
     * every cached transform — they all derive from the root transform. Lombok's generated setter
     * didn't, so the tree kept resolving pointers at the old scale.
     */
    @Test
    public void changingUiScaleAtRuntimeUpdatesHitTesting() {
        setUp(2f);
        var cache = slider.box();
        float localCentreX = cache.x() + cache.width() / 2f;
        float localCentreY = cache.y() + cache.height() / 2f;

        assertTrue(slider.containsSurfacePoint(localCentreX * 2f, localCentreY * 2f));

        document.boxes().setUiScale(1f);
        frame();

        assertTrue("hit-testing did not follow the new scale",
                slider.containsSurfacePoint(localCentreX, localCentreY));
        assertFalse("hit-testing still resolving at the old scale",
                slider.containsSurfacePoint(localCentreX * 2f, localCentreY * 2f));
    }

    /** The thumb must stay inside the root at both extremes — hit-testing uses the root, so a thumb
     * overhanging it would have dead edges. This is what the padding/negative-margin pairing in
     * ore.css buys, and it silently breaks if either number is edited alone. */
    @Test
    public void thumbStaysWithinTheRootAtBothExtremes() {
        setUp(2f);
        var root = slider.box();

        slider.setValue(0f);
        frame();
        var thumb = thumbCache();
        assertTrue("thumb overhangs the root's left edge at min",
                thumb.x() >= root.x() - EPS);

        slider.setValue(1f);
        frame();
        thumb = thumbCache();
        assertTrue("thumb overhangs the root's right edge at max",
                thumb.x() + thumb.width() <= root.x() + root.width() + EPS);
    }

    /** The fill's right edge and the spacer's left edge must both fall inside the thumb's span, so
     * their 9-slice end caps are hidden underneath it rather than visible either side. */
    @Test
    public void barEndsAreHiddenBeneathTheThumb() {
        setUp(2f);
        slider.setValue(0.5f);
        frame();

        var thumb = thumbCache();
        var fill = childWithClass(Slider.FILL_PART);
        var spacer = childWithClass(Slider.SPACER_PART);

        float thumbLeft = thumb.x(), thumbRight = thumbLeft + thumb.width();
        float fillEnd = fill.x() + fill.width();
        float spacerStart = spacer.x();

        assertTrue("fill ends at " + fillEnd + ", outside the thumb [" + thumbLeft + ", " + thumbRight + "]",
                fillEnd >= thumbLeft - EPS && fillEnd <= thumbRight + EPS);
        assertTrue("spacer starts at " + spacerStart + ", outside the thumb",
                spacerStart >= thumbLeft - EPS && spacerStart <= thumbRight + EPS);
    }

    private Box thumbCache() {
        return childWithClass(Slider.THUMB_PART);
    }

    /** A part lives in the slider's SHADOW tree, which no outer selector reaches. */
    private Box childWithClass(String partName) {
        return boxOf(part(slider, partName));
    }
}
