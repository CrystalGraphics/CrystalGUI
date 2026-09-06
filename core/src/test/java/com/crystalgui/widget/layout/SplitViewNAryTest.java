package com.crystalgui.widget.layout;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link SplitView} with more than two panes — the reason it was generalised.
 *
 * <p>The binary behaviour is pinned separately and unchanged by {@code SplitViewTest} and
 * {@code SplitViewDragTest}; those twenty tests are the net under this surgery. What is here is only what
 * was not expressible before.</p>
 */
public class SplitViewNAryTest extends UiDocumentTestBase {

    private static final float EPS = 0.001f;

    private SplitView split;

    private SplitView setUp(SplitView.Orientation orientation) {
        split = new SplitView();
        split.setOrientation(orientation);

        UIElement root = new UIElement().layout(l -> l.width(400).height(300)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(split);

        document.append(root);
        // The old UIWindow defaulted to uiScale 2 and this test's arithmetic was written against
        // that; UiDocumentTestBase sets none, so saying it is what keeps the drag distances honest.
        document.boxes().setUiScale(UI_SCALE);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        return split;
    }


    private void press(int x, int y) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void mouseTo(int x, int y) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
    }

    private void release(int x, int y) {
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }


    /** Physical pixels per logical pixel, matching {@code UIDocument}'s default. */
    private static final float UI_SCALE = 2f;

    private float widthOf(int paneIndex) {
        return split.pane(paneIndex).box().width();
    }

    // ── Structure ───────────────────────────────────────────────────────────────────────────────

    /** A third pane brings a second divider — and takes its space from the pane it follows. */
    @Test
    public void addingAPaneAddsADividerAndHalvesTheDonor() {
        setUp(SplitView.Orientation.HORIZONTAL);
        assertEquals(2, split.paneCount());
        assertEquals(1, split.dividerCount());

        split.addPane();
        frame();

        assertEquals(3, split.paneCount());
        assertEquals(2, split.dividerCount());
        assertEquals("the first pane is untouched", 50f, widthOf(0) / totalPaneWidth() * 100f, 1f);
        assertEquals("the second gave away half of itself", 25f,
                widthOf(1) / totalPaneWidth() * 100f, 1f);
        assertEquals(25f, widthOf(2) / totalPaneWidth() * 100f, 1f);
    }

    private float totalPaneWidth() {
        float total = 0f;
        for (int i = 0; i < split.paneCount(); i++) total += widthOf(i);
        return total;
    }

    /** Removing a pane gives its share back in proportion, and takes a divider with it. */
    @Test
    public void removingAPaneRedistributesItsShare() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.addPane();
        frame();

        assertTrue(split.removePane(2));
        frame();

        assertEquals(2, split.paneCount());
        assertEquals(1, split.dividerCount());
        assertEquals("the survivors are back to filling the whole width",
                400f, totalPaneWidth() + split.divider().box().width(), 1f);
    }

    /**
     * The last two panes stay.
     *
     * <p>A split view with one pane is a container with a divider in it, and every caller would have to
     * check for that shape — so it is refused here rather than represented.</p>
     */
    @Test
    public void theLastTwoPanesAreNotRemovable() {
        setUp(SplitView.Orientation.HORIZONTAL);
        assertFalse(split.removePane(0));
        assertFalse(split.removePane(1));
        assertEquals(2, split.paneCount());
    }

    /**
     * <b>{@code __first__} and {@code __second__} follow position, not identity.</b>
     *
     * <p>They are public constants and part of the widget's contract, so a theme may target them. A pane
     * inserted at the front has to take the class with it — otherwise the rule stays on whatever used to
     * be first and the new leading pane gets nothing.</p>
     */
    @Test
    public void paneClassesTrackPositionAfterAnInsert() {
        setUp(SplitView.Orientation.HORIZONTAL);
        UIElement wasFirst = split.pane(0);

        UIElement inserted = split.insertPane(0);
        frame();

        assertSame(inserted, split.pane(0));
        assertNotSame(wasFirst, split.pane(0));
        assertTrue("the new leading pane carries __first__", inserted.hasClass(SplitView.FIRST_CLASS));
        assertFalse("and the old one gave it up", wasFirst.hasClass(SplitView.FIRST_CLASS));
        assertTrue(wasFirst.hasClass(SplitView.SECOND_CLASS));
        assertTrue("every pane keeps the uniform hook", inserted.hasClass(SplitView.PANE_CLASS));
        assertTrue(wasFirst.hasClass(SplitView.PANE_CLASS));
    }

    // ── The semantic nesting cannot reproduce ───────────────────────────────────────────────────

    /**
     * <b>Dragging a divider moves only the two panes it sits between.</b>
     *
     * <p>This is the whole reason {@link SplitView} was generalised. Nested binary splits produce the same
     * picture: with {@code (A | (B | C))}, dragging the A/B divider resizes A against the <em>whole</em>
     * {@code (B|C)} group and hands the change to B and C in proportion. Every IDE moves only A and B, and
     * no screenshot shows the difference.</p>
     */
    @Test
    public void draggingADividerMovesOnlyItsOwnPair() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.addPane();
        frame();

        float thirdBefore = widthOf(2);

        int[] centre = centreOf(split.divider(0));
        mouseTo(centre[0], centre[1]);
        frame();
        press(centre[0], centre[1]);
        mouseTo(centre[0] - Math.round(60 * UI_SCALE), centre[1]);
        frame();
        release(centre[0] - Math.round(60 * UI_SCALE), centre[1]);
        frame();

        assertTrue("the first pane shrank", widthOf(0) < 190f);
        assertEquals("the third pane did not move at all", thirdBefore, widthOf(2), 1f);
    }

    /** The same, expressed without input: only the addressed pair changes. */
    @Test
    public void setPercentageAtOnlyTouchesItsPair() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.addPane();
        frame();
        float thirdBefore = widthOf(2);

        split.setPercentageAt(0, 20f);
        frame();

        assertEquals("the third pane is untouched", thirdBefore, widthOf(2), 1f);
        assertEquals("and the pair split 20/80 between themselves",
                widthOf(0) / (widthOf(0) + widthOf(1)) * 100f, 20f, 1.5f);
    }

    /** The second divider is a real, separately draggable divider — not a decoration. */
    @Test
    public void theSecondDividerDragsIndependently() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.addPane();
        frame();
        float firstBefore = widthOf(0);

        int[] centre = centreOf(split.divider(1));
        mouseTo(centre[0], centre[1]);
        frame();
        press(centre[0], centre[1]);
        mouseTo(centre[0] + Math.round(40 * UI_SCALE), centre[1]);
        frame();
        release(centre[0] + Math.round(40 * UI_SCALE), centre[1]);
        frame();

        assertTrue("the middle pane grew", widthOf(1) > 100f);
        assertEquals("the first pane never moved", firstBefore, widthOf(0), 1f);
    }

    // ── Pixel limits and snap ───────────────────────────────────────────────────────────────────

    /**
     * <b>A pixel minimum is what a weight cannot express.</b>
     *
     * <p>"At least 150px" stays true at every document size; "at least 15%" does not, and the difference
     * only shows up on the document sizes nobody tested at.</p>
     */
    @Test
    public void aPixelMinimumClampsTheSplit() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.setPaneSizeLimits(0, 150f, Float.MAX_VALUE);
        frame();

        split.setPercentage(1f);   // would be ~4px without the limit, and 5% with only the percentage one
        frame();

        assertTrue("the pane stopped at its pixel minimum, not at 5%",
                widthOf(0) >= 150f - 2f);
    }

    /** A maximum is the mirror image, and clamps from the other side. */
    @Test
    public void aPixelMaximumClampsTheSplit() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.setPaneSizeLimits(0, 0f, 120f);
        frame();

        split.setPercentage(99f);
        frame();

        assertTrue("the pane stopped at its pixel maximum", widthOf(0) <= 120f + 2f);
    }

    /**
     * <b>Snap collapses a pane dragged well past its minimum, rather than stopping it dead.</b>
     *
     * <p>The half-a-minimum threshold matters: triggering on the way to the minimum would make the pane
     * unreachable at its smallest legal size, which is exactly the size a sidebar is usually left at.</p>
     */
    @Test
    public void snapCollapsesAPaneDraggedWellPastItsMinimum() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.setLimits(20f, 95f);
        split.setPaneSnap(0, true);
        frame();

        split.setPercentage(15f);            // past the minimum, but not past half of it
        assertEquals("still clamped to the minimum", 20f, split.getPercentage(), EPS);

        split.setPercentage(5f);             // past half of 20
        assertEquals("collapsed", 0f, split.getPercentage(), EPS);
    }

    /** Without snap, the same drag stops at the minimum — the default, and what every existing pane does. */
    @Test
    public void withoutSnapThePaneStopsAtTheMinimum() {
        setUp(SplitView.Orientation.HORIZONTAL);
        split.setLimits(20f, 95f);
        frame();

        split.setPercentage(1f);

        assertEquals(20f, split.getPercentage(), EPS);
    }

    // ── Vertical ────────────────────────────────────────────────────────────────────────────────

    /** Everything above holds on the other axis; the dividers simply move the other way. */
    @Test
    public void threePanesWorkVerticallyToo() {
        setUp(SplitView.Orientation.VERTICAL);
        split.addPane();
        frame();

        assertEquals(3, split.paneCount());
        float firstBefore = split.pane(0).box().height();
        float middleBefore = split.pane(1).box().height();

        int[] centre = centreOf(split.divider(1));
        mouseTo(centre[0], centre[1]);
        frame();
        press(centre[0], centre[1]);
        mouseTo(centre[0], centre[1] + Math.round(30 * UI_SCALE));
        frame();
        release(centre[0], centre[1] + Math.round(30 * UI_SCALE));
        frame();

        assertEquals("the first pane never moved",
                firstBefore, split.pane(0).box().height(), 1f);
        assertTrue("the middle one grew",
                split.pane(1).box().height() > middleBefore + 20f);
    }
}
