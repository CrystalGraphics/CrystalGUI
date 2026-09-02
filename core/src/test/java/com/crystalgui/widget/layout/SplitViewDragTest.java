package com.crystalgui.widget.layout;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.widget.layout.SplitView;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.testsupport.UiDocumentTestBase;
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
public class SplitViewDragTest extends UiDocumentTestBase {

    private static final float EPS = 0.001f;

    private SplitView split;

    /** A split view filling a fixed-size root, laid out at the given scale. */
    private SplitView setUp(float uiScale, SplitView.Orientation orientation) {
        // The base's document persists across calls where the old fixture built a fresh UIWindow
        // each time; `dragResultIsIndependentOfUiScale` calls this twice, so the second split would
        // otherwise lay out below the first.
        document.removeAll();
        split = new SplitView();
        split.setOrientation(orientation);

        UINode root = new UINode().layout(l -> l.width(400).height(300)
                .flexDirection(FlexDirection.COLUMN));
        root.append(split);

        document.append(root);
        document.boxes().setUiScale(uiScale);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        return split;
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

    /** Physical-pixel centre of the divider — where a user would actually grab it. */
    private int[] dividerCentrePhys(float uiScale) {
        // `centreOf` is the base's, and it is right about both halves the ported arithmetic got
        // wrong: `Box.x()` is PARENT-RELATIVE here, and the world position already carries the
        // scale so only the half-extent takes it.
        return centreOf(split.divider());
    }

    @Test
    public void pressOnTheDividerStartsADrag() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        int[] c = dividerCentrePhys(2f);

        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);

        var d = split.divider().box();
        var sv = split.box();
        assertTrue("press on the divider did not start a drag."
                        + " splitview=(" + sv.x() + "," + sv.y() + " " + sv.width() + "x" + sv.height() + ")"
                        + " divider=(" + d.x() + "," + d.y() + " " + d.width() + "x" + d.height() + ")"
                        + " pressedPhys=(" + c[0] + "," + c[1] + ")"
                        + " hovered=" + hit(c[0], c[1]),
                document.input().mode(Drag.class) != null);
    }

    /** A press inside a pane must NOT start a drag — the root filters on the event's target, so only
     * the divider itself is grabbable. */
    @Test
    public void pressInAPaneDoesNotStartADrag() {
        setUp(2f, SplitView.Orientation.HORIZONTAL);
        var pane = split.first().box();
        int physX = Math.round((pane.x() + pane.width() / 4f) * 2f);
        int physY = Math.round((pane.y() + pane.height() / 2f) * 2f);

        mouseTo(physX, physY);
        frame();
        press(physX, physY);

        assertFalse("a press inside a pane started a drag",
                document.input().mode(Drag.class) != null);
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
        assertFalse(document.input().mode(Drag.class) != null);
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
        var sv = split.box();
        var d = split.divider().box();
        return split.getOrientation() == SplitView.Orientation.VERTICAL
                ? sv.height() - d.height()
                : sv.width() - d.width();
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

        float total = split.box().width();
        float dividerWidth = split.divider().box().width();
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
        split.first().append(new UINode().layout(l -> l.minWidth(150).heightPercent(100f)));
        frame();

        int[] c = dividerCentrePhys(2f);
        mouseTo(c[0], c[1]);
        frame();
        press(c[0], c[1]);
        mouseTo(0, c[1]);
        frame();
        release(0, c[1]);
        frame();

        float total = split.box().width();
        float dividerWidth = split.divider().box().width();
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
        split.first().append(new UINode().layout(l -> l.width(2000).height(50)));
        frame();

        split.setPercentage(10f);
        frame();

        float firstWidth = split.first().box().width();
        float total = split.box().width();
        assertTrue("pane refused to shrink below its content: " + firstWidth + " of " + total,
                firstWidth < total * 0.25f);
    }
}
