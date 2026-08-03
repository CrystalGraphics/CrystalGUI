package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The widget half of the dock: hit testing, element reuse, and when it is safe to rebuild.
 *
 * <p>What a drop <em>means</em> is decided by {@code DockDropZones} and {@code DockLayout}, both of which
 * are tested headlessly and exhaustively. What is left for here is only what genuinely needs a window —
 * which is why this file is short and those are not.</p>
 */
public class DockAreaTest extends UiTestBase {

    /** Physical pixels per logical pixel — {@code UIWindow}'s default, and easy to forget. */
    private static final float UI_SCALE = 2f;

    private UIWindow window;
    private DockArea area;
    private DockLayout layout;

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");

    private DockPanelRegistry<UIElement> registry() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta", "gamma"}) {
            registry.register(new DockPanelDescriptor(id, id), ref -> new UIElement());
        }
        return registry;
    }

    /** Two side-by-side groups filling a 600x400 root. */
    private void setUpTwoGroups() {
        DockLeaf left = new DockLeaf(alpha);
        layout = DockLayout.of(left);
        layout.drop(left, DockDropZone.SPLIT_RIGHT, new DockLeaf(beta));

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
        frame();
        frame();   // the ticker registers on the first layout, so the rebuild lands on the second
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void press(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, System.currentTimeMillis()));
    }

    private void mouseTo(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
    }

    private void release(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, System.currentTimeMillis()));
    }

    /** Physical centre of an element. */
    private int[] centre(UIElement element) {
        var cache = element.getRuntimeCache();
        return new int[]{
                Math.round((cache.getX() + cache.getWidth() / 2f) * UI_SCALE),
                Math.round((cache.getY() + cache.getHeight() / 2f) * UI_SCALE)
        };
    }

    /** A point a fraction of the way across an element, in physical pixels. */
    private int[] at(UIElement element, float fx, float fy) {
        var cache = element.getRuntimeCache();
        return new int[]{
                Math.round((cache.getX() + cache.getWidth() * fx) * UI_SCALE),
                Math.round((cache.getY() + cache.getHeight() * fy) * UI_SCALE)
        };
    }

    private List<String> panelIdsPerGroup() {
        List<String> out = new ArrayList<>();
        for (DockLeaf leaf : layout.leaves()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < leaf.panelCount(); i++) {
                if (i > 0) sb.append('+');
                sb.append(leaf.panel(i).typeId());
            }
            out.add(sb.toString());
        }
        return out;
    }

    private Tab tabOf(DockPanelRef panel) {
        DockGroup group = area.groupFor(layout.leafContaining(panel));
        assertNotNull("no group for " + panel, group);
        Tab tab = group.tabFor(panel);
        assertNotNull("no tab for " + panel, tab);
        return tab;
    }

    /** Drags {@code panel}'s tab to a point in the target group and drops it there. */
    private void dragPanelTo(DockPanelRef panel, DockGroup target, float fx, float fy) {
        int[] from = centre(tabOf(panel));
        int[] to = at(target, fx, fy);

        mouseTo(from[0], from[1]);
        frame();
        press(from[0], from[1]);
        mouseTo(to[0], to[1]);
        frame();                 // Over is dispatched here, which is what sets the preview
        release(to[0], to[1]);
        frame();                 // Drop, then the deferred rebuild
        frame();
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────

    /** A two-leaf branch becomes one split view with two panes, and each pane holds a group. */
    @Test
    public void aBranchBecomesASplitViewOfGroups() {
        setUpTwoGroups();

        UIElement built = area.getChildren().get(0);
        assertTrue("a branch of two is a SplitView", built instanceof SplitView);
        SplitView split = (SplitView) built;
        assertEquals(2, split.paneCount());
        assertEquals(SplitView.Orientation.HORIZONTAL, split.getOrientation());

        assertNotNull(area.groupFor(layout.leaves().get(0)));
        assertNotNull(area.groupFor(layout.leaves().get(1)));
    }

    /** A vertical branch produces a vertical split — the derived orientation reaching the widget. */
    @Test
    public void aVerticalBranchProducesAVerticalSplit() {
        DockLeaf top = new DockLeaf(alpha);
        layout = DockLayout.of(top);
        layout.drop(top, DockDropZone.SPLIT_DOWN, new DockLeaf(beta));

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));
        window = new UIWindow(Ui.of(root));
        window.init(1200, 800);
        frame();
        frame();

        UIElement built = area.getChildren().get(0);
        assertTrue(built instanceof SplitView);
        assertEquals(SplitView.Orientation.VERTICAL, ((SplitView) built).getOrientation());
    }

    /**
     * <b>Panel content survives a rebuild.</b>
     *
     * <p>A split rebuilds the whole element tree. If content were rebuilt with it, every split would throw
     * away the editor's scroll position, its undo stack and its selection — reported as "the layout is
     * fine but everything resets", which points at the wrong place entirely.</p>
     */
    @Test
    public void panelContentSurvivesARebuild() {
        setUpTwoGroups();
        DockGroup group = area.groupFor(layout.leafContaining(alpha));
        UIElement contentBefore = group.tabFor(alpha).content().getChildren().get(0);

        area.requestRebuild();
        frame();
        frame();

        UIElement contentAfter = area.groupFor(layout.leafContaining(alpha))
                .tabFor(alpha).content().getChildren().get(0);
        assertSame("the very same element, not an equal one", contentBefore, contentAfter);
    }

    /**
     * <b>A rebuild is deferred by a frame, never done inline.</b>
     *
     * <p>A drop arrives while the drag controller still holds references into the tree it is about to
     * restructure. The table header froze exactly this way — sort once and no header could be clicked
     * again — because the handler detached the element under the cursor and {@code screenToLocal} went
     * stale.</p>
     */
    @Test
    public void aRebuildIsDeferredToTheNextFrame() {
        setUpTwoGroups();
        UIElement before = area.getChildren().get(0);

        layout.drop(layout.leaves().get(0), DockDropZone.SPLIT_DOWN, new DockLeaf(new DockPanelRef("gamma")));
        area.requestRebuild();

        assertSame("nothing moved yet", before, area.getChildren().get(0));
        frame();
        assertEquals("and on the next frame it has", 3, layout.leaves().size());
    }

    // ── Dragging ────────────────────────────────────────────────────────────────────────────────

    /** Dropping a tab in the middle of another group merges it into that group's strip. */
    @Test
    public void droppingOnTheCentreMergesIntoTheStrip() {
        setUpTwoGroups();
        DockGroup right = area.groupFor(layout.leafContaining(beta));

        dragPanelTo(alpha, right, 0.5f, 0.5f);

        assertEquals("one group left, holding both panels",
                List.of("beta+alpha"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    /** Dropping near an edge splits instead — and along the axis that edge names. */
    @Test
    public void droppingOnAnEdgeSplits() {
        setUpTwoGroups();
        DockGroup right = area.groupFor(layout.leafContaining(beta));

        // Near the right group's LEFT edge -- and deliberately not near the AREA's edge, which is its
        // own drop target and would take the drop before any group saw it.
        dragPanelTo(alpha, right, 0.05f, 0.5f);

        assertEquals(2, layout.leaves().size());
        assertEquals("alpha landed to the left of beta", List.of("alpha", "beta"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    /**
     * <b>The source group collapses when its last panel leaves.</b>
     *
     * <p>The detach and the collapse are the same call a close goes through, which is the whole reason
     * "tear a tab out" and "close a pane" are not two code paths.</p>
     */
    @Test
    public void movingTheLastPanelOutCollapsesTheSourceGroup() {
        setUpTwoGroups();
        DockGroup right = area.groupFor(layout.leafContaining(beta));
        DockLeaf sourceLeaf = layout.leafContaining(alpha);

        dragPanelTo(alpha, right, 0.5f, 0.5f);

        assertEquals(1, layout.leaves().size());
        assertNull("and its group is forgotten, not leaked", area.groupFor(sourceLeaf));
    }

    /** Dropping a lone panel back on its own strip is refused rather than producing an empty pane. */
    @Test
    public void droppingALonePanelBackOnItsOwnGroupDoesNothing() {
        setUpTwoGroups();
        DockGroup own = area.groupFor(layout.leafContaining(alpha));

        dragPanelTo(alpha, own, 0.95f, 0.5f);

        assertEquals("still two groups, unchanged", List.of("alpha", "beta"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    // ── Reordering within a strip ───────────────────────────────────────────────────────────────

    /** Two panels in one group, so there is a strip to reorder in. */
    private DockGroup setUpOneGroupOfTwo() {
        DockLeaf only = new DockLeaf(alpha, beta);
        layout = DockLayout.of(only);

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
        frame();
        frame();
        return area.groupFor(only);
    }

    /** Drags {@code panel}'s tab onto another tab, at the given fraction across that tab. */
    private void dragTabOntoTab(DockPanelRef panel, DockPanelRef onto, float fx) {
        int[] from = centre(tabOf(panel));
        int[] to = at(tabOf(onto), fx, 0.5f);

        mouseTo(from[0], from[1]);
        frame();
        press(from[0], from[1]);
        mouseTo(to[0], to[1]);
        frame();
        release(to[0], to[1]);
        frame();
        frame();
    }

    /**
     * <b>A drag inside the strip reorders; it never splits.</b>
     *
     * <p>Aiming at a strip is unambiguous, so offering a split there would make the top tenth of every
     * group unable to do the one thing its tabs are for.</p>
     */
    @Test
    public void draggingATabWithinItsStripReordersIt() {
        setUpOneGroupOfTwo();
        assertEquals(List.of("alpha+beta"), panelIdsPerGroup());

        dragTabOntoTab(alpha, beta, 0.9f);   // past beta's midpoint, so alpha lands after it

        assertEquals("still one group -- no split", 1, layout.leaves().size());
        assertEquals(List.of("beta+alpha"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    /** Dropping on the left half of a tab lands before it, the rule every tab strip uses. */
    @Test
    public void droppingOnTheLeftHalfOfATabLandsBeforeIt() {
        setUpOneGroupOfTwo();

        dragTabOntoTab(beta, alpha, 0.1f);   // before alpha's midpoint

        assertEquals(List.of("beta+alpha"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    /**
     * <b>Reordering a single-tab group must not delete it.</b>
     *
     * <p>A reorder inside one strip is a move, not a detach-and-reinsert: going through the detach path
     * would remove the panel, find the leaf empty, and collapse the very pane being dragged within.</p>
     */
    @Test
    public void reorderingWithinASingleTabGroupDoesNotDeleteIt() {
        setUpTwoGroups();
        DockGroup own = area.groupFor(layout.leafContaining(alpha));

        int[] tab = centre(tabOf(alpha));
        mouseTo(tab[0], tab[1]);
        frame();
        press(tab[0], tab[1]);
        mouseTo(tab[0] + 12, tab[1]);
        frame();
        release(tab[0] + 12, tab[1]);
        frame();
        frame();

        assertEquals("both groups still there", List.of("alpha", "beta"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    /** A panel dragged into another group's strip lands at the position aimed at, not at the end. */
    @Test
    public void aPanelDroppedOnAnotherStripLandsWhereItWasAimed() {
        setUpTwoGroups();
        DockGroup right = area.groupFor(layout.leafContaining(beta));

        dragTabOntoTab(alpha, beta, 0.1f);   // before beta, not appended after it

        assertEquals(List.of("alpha+beta"), panelIdsPerGroup());
        layout.checkInvariants();
    }

    // ── Active group ────────────────────────────────────────────────────────────────────────────

    /** Something is always active once there is anything to be active. */
    @Test
    public void anAreaAlwaysHasAnActiveGroupOnceBuilt() {
        setUpTwoGroups();
        assertNotNull(area.activeGroup());
        assertTrue(area.activeGroup().hasClass(DockGroup.ACTIVE_CLASS));
    }

    /** Activating one group deactivates the other — the class is a single-winner marker. */
    @Test
    public void onlyOneGroupIsActiveAtATime() {
        setUpTwoGroups();
        DockGroup left = area.groupFor(layout.leafContaining(alpha));
        DockGroup right = area.groupFor(layout.leafContaining(beta));

        area.setActiveGroup(left);
        area.setActiveGroup(right);

        assertTrue(right.hasClass(DockGroup.ACTIVE_CLASS));
        assertTrue(!left.hasClass(DockGroup.ACTIVE_CLASS));
    }

    // ── Closing ─────────────────────────────────────────────────────────────────────────────────

    /** Closing the last panel in a group takes the group with it. */
    @Test
    public void closingTheLastPanelRemovesTheGroup() {
        setUpTwoGroups();

        area.closePanel(beta);
        frame();
        frame();

        assertEquals(List.of("alpha"), panelIdsPerGroup());
        assertTrue("and the split view is gone with it",
                !(area.getChildren().get(0) instanceof SplitView));
    }
}
