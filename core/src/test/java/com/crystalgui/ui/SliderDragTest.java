package com.crystalgui.ui;

import com.crystalgui.core.input.SystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.elements.Slider;
import org.joml.Matrix4f;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Dragging, driven through the same entry point real input uses
 * ({@link com.crystalgui.ui.input.UIInputHandler#consumeMouseEvent}).
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
public class SliderDragTest extends UiTestBase {

    private static final float EPS = 0.001f;

    private UIWindow window;
    private Slider slider;

    /** {@code UIInputHandler}'s constructor asks the adapter how many mouse buttons the platform
     * has, so one has to be registered before any {@code UIWindow} exists. Nothing here needs real
     * platform state — the tests synthesize events directly rather than polling. */
    /** Builds a real window/stylesheet/layout at the given scale and returns the laid-out slider. */
    private Slider setUp(float uiScale) {
        slider = new Slider();
        window = new UIWindow(Ui.of(slider));
        window.setUiScale(uiScale);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(800, 600);
        frame();
        return slider;
    }

    /**
     * One full frame's worth of style + layout + input bookkeeping, minus the painting.
     *
     * <p>Note there is no transform-seeding step here. Hit-testing works without ever painting
     * because {@code RuntimeCache.localToWorld} falls back to {@link UIWindow#getRootTransform()},
     * the same matrix {@code paintFrame} seeds the {@code PoseStack} from. It used to fall back to
     * identity — wrong by exactly {@code uiScale} — and these tests had to reproduce the scale matrix
     * by hand to compensate. That hack being unnecessary is the check that the fallback is right.</p>
     */
    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void mouseTo(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(physX, physY, 0, 0, -1, false, 0f, -1L));
    }

    private void press(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(physX, physY, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void release(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new SystemInput.Mouse.Event(physX, physY, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }

    /** Physical-pixel centre of the thumb, i.e. where a user would actually grab it. */
    private int thumbCentrePhysX(float uiScale) {
        var thumb = slider.getRuntimeCache();
        return Math.round((thumb.getX() + thumb.getWidth() / 2f) * uiScale);
    }

    /** The regression that was actually shipped broken: a press at the default scale must start a
     * drag at all. Before the fix the slider compared physical coordinates against logical geometry
     * and rejected the press outright. */
    @Test
    public void pressOnTheSliderStartsADragAtDefaultUiScale() {
        setUp(2f);
        var cache = slider.getRuntimeCache();
        int physX = Math.round((cache.getX() + cache.getWidth() / 2f) * 2f);
        int physY = Math.round((cache.getY() + cache.getHeight() / 2f) * 2f);

        mouseTo(physX, physY);
        frame();
        press(physX, physY);

        assertTrue("press over the slider did not start a drag",
                window.getInputHandler().getDragController().isDragging());
    }

    /** A click on the track jumps to that position — the thumb's centre lands under the cursor. */
    @Test
    public void clickingTheTrackJumpsToThatPosition() {
        setUp(2f);
        var layout = slider.getTaffyLayout();
        var cache = slider.getRuntimeCache();
        // Three-quarters along the content box, in physical pixels.
        float localX = cache.getX() + layout.border().left + layout.padding().left
                + layout.contentBoxWidth() * 0.75f;
        int physY = Math.round((cache.getY() + cache.getHeight() / 2f) * 2f);

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

        var cache = slider.getRuntimeCache();
        int physY = Math.round((cache.getY() + cache.getHeight() / 2f) * 2f);
        int grabX = thumbCentrePhysX(2f);

        mouseTo(grabX, physY);
        frame();
        press(grabX, physY);

        // Quarter of the travel, expressed in physical pixels.
        float travel = slider.getTaffyLayout().contentBoxWidth();
        int moveTo = grabX + Math.round(travel * 0.25f * 2f);
        mouseTo(moveTo, physY);
        frame();

        assertEquals(0.75f, slider.getValue(), 0.02f);

        release(moveTo, physY);
        assertFalse(window.getInputHandler().getDragController().isDragging());
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

        var cache = slider.getRuntimeCache();
        int physY = Math.round((cache.getY() + cache.getHeight() / 2f) * uiScale);
        int grabX = thumbCentrePhysX(uiScale);

        mouseTo(grabX, physY);
        frame();
        press(grabX, physY);

        float travel = slider.getTaffyLayout().contentBoxWidth();
        mouseTo(grabX + Math.round(travel * 0.25f * uiScale), physY);
        frame();
        return slider.getValue();
    }

    /**
     * Changing {@code uiScale} on a live window must re-point hit-testing, which means invalidating
     * every cached transform — they all derive from the root transform. Lombok's generated setter
     * didn't, so the tree kept resolving pointers at the old scale.
     */
    @Test
    public void changingUiScaleAtRuntimeUpdatesHitTesting() {
        setUp(2f);
        var cache = slider.getRuntimeCache();
        float localCentreX = cache.getX() + cache.getWidth() / 2f;
        float localCentreY = cache.getY() + cache.getHeight() / 2f;

        assertTrue(slider.containsScreenPoint(localCentreX * 2f, localCentreY * 2f));

        window.setUiScale(1f);
        frame();

        assertTrue("hit-testing did not follow the new scale",
                slider.containsScreenPoint(localCentreX, localCentreY));
        assertFalse("hit-testing still resolving at the old scale",
                slider.containsScreenPoint(localCentreX * 2f, localCentreY * 2f));
    }

    /** The thumb must stay inside the root at both extremes — hit-testing uses the root, so a thumb
     * overhanging it would have dead edges. This is what the padding/negative-margin pairing in
     * ore.css buys, and it silently breaks if either number is edited alone. */
    @Test
    public void thumbStaysWithinTheRootAtBothExtremes() {
        setUp(2f);
        var root = slider.getRuntimeCache();

        slider.setValue(0f);
        frame();
        var thumb = thumbCache();
        assertTrue("thumb overhangs the root's left edge at min",
                thumb.getX() >= root.getX() - EPS);

        slider.setValue(1f);
        frame();
        thumb = thumbCache();
        assertTrue("thumb overhangs the root's right edge at max",
                thumb.getX() + thumb.getWidth() <= root.getX() + root.getWidth() + EPS);
    }

    /** The fill's right edge and the spacer's left edge must both fall inside the thumb's span, so
     * their 9-slice end caps are hidden underneath it rather than visible either side. */
    @Test
    public void barEndsAreHiddenBeneathTheThumb() {
        setUp(2f);
        slider.setValue(0.5f);
        frame();

        var thumb = thumbCache();
        var fill = childWithClass(Slider.FILL_CLASS);
        var spacer = childWithClass(Slider.SPACER_CLASS);

        float thumbLeft = thumb.getX(), thumbRight = thumbLeft + thumb.getWidth();
        float fillEnd = fill.getX() + fill.getWidth();
        float spacerStart = spacer.getX();

        assertTrue("fill ends at " + fillEnd + ", outside the thumb [" + thumbLeft + ", " + thumbRight + "]",
                fillEnd >= thumbLeft - EPS && fillEnd <= thumbRight + EPS);
        assertTrue("spacer starts at " + spacerStart + ", outside the thumb",
                spacerStart >= thumbLeft - EPS && spacerStart <= thumbRight + EPS);
    }

    private UIElement.RuntimeCache thumbCache() {
        return childWithClass(Slider.THUMB_CLASS);
    }

    private UIElement.RuntimeCache childWithClass(String cssClass) {
        UIElement found = slider.querySelector("." + cssClass);
        if (found == null) throw new AssertionError("no child with class " + cssClass);
        return found.getRuntimeCache();
    }
}
