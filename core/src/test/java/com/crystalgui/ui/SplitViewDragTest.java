package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.elements.SplitView;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Divider dragging, driven through the same entry point real input uses
 * ({@link com.crystalgui.ui.input.UIInputHandler#consumeMouseEvent}).
 *
 * <p>Mirrors {@code SliderDragTest}. A percentage-maths test can't catch a drag that never starts,
 * and neither can a static capture — only pushing events through the handler can. The
 * {@code uiScale}-invariance case in particular guards the physical-vs-logical coordinate confusion
 * that silently broke Slider dragging.</p>
 *
 * <p>Runs headless: layout and input need no GL context, only painting does.</p>
 */
public class SplitViewDragTest extends UiTestBase {

    private static final float EPS = 0.001f;

    private UIWindow window;
    private SplitView split;

    /** A split view filling a fixed-size root, laid out at the given scale. */
    private SplitView setUp(float uiScale, SplitView.Orientation orientation) {
        split = new SplitView();
        split.setOrientation(orientation);

        UIElement root = new UIElement().layout(l -> l.width(400).height(300)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(split);

        window = new UIWindow(Ui.of(root));
        window.setUiScale(uiScale);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(800, 600);
        frame();
        return split;
    }

    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void mouseTo(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, -1, false, 0f, -1L));
    }

    private void press(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void release(int physX, int physY) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(physX, physY, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }

    /** Physical-pixel centre of the divider — where a user would actually grab it. */
    private int[] dividerCentrePhys(float uiScale) {
        var d = split.divider().getRuntimeCache();
        return new int[]{
                Math.round((d.getX() + d.getWidth() / 2f) * uiScale),
                Math.round((d.getY() + d.getHeight() / 2f) * uiScale)
        };
    }

    @Test
    public void pressOnTheDividerStartsADrag() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        int[] c = dividerCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        var d = split.divider().getRuntimeCache();
        var sv = split.getRuntimeCache();
        assertTrue("press on the divider did not start a drag."
                        + " splitview=(" + sv.getX() + "," + sv.getY() + " " + sv.getWidth() + "x" + sv.getHeight() + ")"
                        + " divider=(" + d.getX() + "," + d.getY() + " " + d.getWidth() + "x" + d.getHeight() + ")"
                        + " pressedPhys=(" + c[0] + "," + c[1] + ")"
                        + " hovered=" + window.getHoveredElement(c[0], c[1]),
                window.getInputHandler().getDragController().isDragging());
    }

    /** A press inside a pane must NOT start a drag — the root filters on the event's target, so only
     * the divider itself is grabbable. */
    @Test
    public void pressInAPaneDoesNotStartADrag() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        var pane = split.first().getRuntimeCache();
        int physX = Math.round((pane.getX() + pane.getWidth() / 4f) * 2f);
        int physY = Math.round((pane.getY() + pane.getHeight() / 2f) * 2f);

        mouseTo(physX, physY);
        frame();
        press(physX, physY);

        assertFalse("a press inside a pane started a drag",
                window.getInputHandler().getDragController().isDragging());
    }

    @Test
    public void draggingHorizontallyMovesTheSplit() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        int[] c = dividerCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        // A quarter of the travel to the right, in physical pixels.
        float travel = travelLength();
        mouseTo(c[0] + Math.round(travel * 0.25f * 2f), c[1]);
        frame();

        assertEquals(75f, split.getPercentage(), 1.5f);

        release(c[0], c[1]);
        assertFalse(window.getInputHandler().getDragController().isDragging());
    }

    /** Vertical must track the Y delta, not X — an axis mix-up would leave the split unmoved. */
    @Test
    public void draggingVerticallyMovesTheSplit() {
        setUp(2f, SplitView.Orientation.VERTICAL);
        int[] c = dividerCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        float travel = travelLength();
        mouseTo(c[0], c[1] + Math.round(travel * 0.25f * 2f));
        frame();

        assertEquals(75f, split.getPercentage(), 1.5f);
    }

    /** Dragging past the end must clamp, not run away. */
    @Test
    public void draggingBeyondTheEndClampsToTheLimit() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        int[] c = dividerCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);
        mouseTo(c[0] + 10_000, c[1]);
        frame();

        assertEquals(split.getMaxPercentage(), split.getPercentage(), EPS);
    }

    /**
     * The load-bearing guard: the same gesture expressed in each scale's own physical pixels must
     * produce the same split. Any surviving physical/logical coordinate mismatch scales the result.
     */
    @Test
    public void dragResultIsIndependentOfUiScale() {
        float atOne = dragQuarterTravelAtScale(1f);
        float atTwo = dragQuarterTravelAtScale(2f);
        // Assert movement first: comparing two results that are both still the untouched default
        // would make this pass while proving nothing — which is exactly what it did before the
        // bubble-listener fix, when no drag ever started at either scale.
        assertNotEquals("the drag never moved the split, so the comparison below is vacuous",
                50f, atOne, 1f);
        assertEquals(atOne, atTwo, 1.5f);
    }

    private float dragQuarterTravelAtScale(float uiScale) {
        setUp(uiScale, SplitView.Orientation.HORIZONTAL);
        int[] c = dividerCentrePhys(uiScale);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        float travel = travelLength();
        mouseTo(c[0] + Math.round(travel * 0.25f * uiScale), c[1]);
        frame();
        return split.getPercentage();
    }

    /** Logical travel: the split view's content box along the axis, minus the divider. */
    private float travelLength() {
        var sv = split.getRuntimeCache();
        var d = split.divider().getRuntimeCache();
        return split.getOrientation() == SplitView.Orientation.VERTICAL
                ? sv.getHeight() - d.getHeight()
                : sv.getWidth() - d.getWidth();
    }

    /**
     * <b>A pane's own CSS {@code min-width} bounds the divider, so dragging back has no dead zone.</b>
     *
     * <p>Taffy refuses to shrink a pane past its {@code min-width}, but the divider's weight is a number
     * SplitView keeps and nothing stopped it going lower. Drag left past the minimum and the pane stops
     * while the weight keeps falling; release, and the stored split says one thing where the layout shows
     * another. The next rightward drag spends all of that difference before anything moves — the divider
     * ignores the first stretch of the gesture and then jumps.</p>
     *
     * <p>Asserted on the stored percentage <em>after release</em>, which is where the discrepancy lives.
     * Asserting the pane's rendered width instead passes either way: Taffy clamps it to 150 regardless,
     * which is exactly what makes this bug invisible from the picture.</p>
     */
    @Test
    public void aPanesCssMinWidthBoundsTheDivider() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        split.first().layout(l -> l.minWidth(150));
        frame();

        int[] c = dividerCentrePhys(2f);
        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);
        // Hard left, well past the minimum and past SplitView's own 5% floor.
        mouseTo(0, c[1]);
        frame();
        release(0, c[1]);
        frame();

        float total = split.getRuntimeCache().getWidth();
        float dividerWidth = split.divider().getRuntimeCache().getWidth();
        float storedPx = split.getPercentage() / 100f * (total - dividerWidth);
        assertTrue("the stored split fell to " + storedPx + "px, below the pane's 150px CSS minimum"
                        + " -- dragging back will have a dead zone that wide",
                storedPx >= 150f - 1f);
    }

    /**
     * <b>And the minimum on a pane's CONTENT bounds it too</b> — which is the case that actually occurs.
     *
     * <p>The test above styles {@code split.first()}, and {@code first()} is the {@code __split-pane__}
     * wrapper SplitView makes for itself. Nothing else does that: a caller puts its own element
     * <em>inside</em> the pane, so every real rule — {@code workbench .__region-sidebar__ {'{'} min-width:
     * 120px {'}'}} — lands one level below where the clamp was looking. The clamp read correct and reached
     * the wrong element, so it was as absent as before it was written.</p>
     *
     * <p>What it looks like is not a divider bug at all. Taffy still refuses to shrink the content, so the
     * pane <b>overhangs the split</b> and whatever paints later covers the overhang — the sidebar ran on
     * under the editor, and the file tree inside it sized its scroll viewport to the full overhanging
     * width. Scrolling to the very end still left names cut off, because the last stretch of the viewport
     * was underneath another region.</p>
     */
    @Test
    public void aPaneContentsCssMinWidthBoundsTheDivider() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        split.first().addChild(new UIElement().layout(l -> l.minWidth(150).heightPercent(100f)));
        frame();

        int[] c = dividerCentrePhys(2f);
        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);
        mouseTo(0, c[1]);
        frame();
        release(0, c[1]);
        frame();

        float total = split.getRuntimeCache().getWidth();
        float dividerWidth = split.divider().getRuntimeCache().getWidth();
        float storedPx = split.getPercentage() / 100f * (total - dividerWidth);
        assertTrue("the stored split fell to " + storedPx + "px, below the 150px minimum its CONTENT"
                        + " declares -- so the pane overhangs the split and is painted over",
                storedPx >= 150f - 1f);
    }

    /**
     * The flexbox trap this widget would otherwise ship with: items default to {@code min-size: auto},
     * which refuses to shrink one below its own content. A pane holding something large would jam the
     * split — and an empty-pane demo would look perfect. SplitView sets {@code min-width}/
     * {@code min-height} to 0 on the panes to prevent exactly this.
     */
    @Test
    public void oversizedPaneContentDoesNotJamTheSplit() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        split.first().addChild(new UIElement().layout(l -> l.width(2000).height(50)));
        frame();

        split.setPercentage(10f);
        frame();

        float firstWidth = split.first().getRuntimeCache().getWidth();
        float total = split.getRuntimeCache().getWidth();
        assertTrue("pane refused to shrink below its content: " + firstWidth + " of " + total,
                firstWidth < total * 0.25f);
    }
}
