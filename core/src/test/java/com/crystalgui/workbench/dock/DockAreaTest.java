package com.crystalgui.workbench.dock;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.dnd.InsertionMarker;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The widget half of the dock: hit testing, element reuse, and when it is safe to rebuild.
 *
 * <p>What a drop <em>means</em> is decided by {@code DockDropZones} and {@code DockLayout}, both of which
 * are tested headlessly and exhaustively. What is left for here is only what genuinely needs a document —
 * which is why this file is short and those are not.</p>
 */
public class DockAreaTest extends UiDocumentTestBase {

    /** Physical pixels per logical pixel — {@code UIDocument}'s default, and easy to forget. */
    private static final float UI_SCALE = 2f;

    private DockArea area;
    private DockLayout layout;

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");

    private DockPanelRegistry<UIElement> registry() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta", "gamma", "delta", "console"}) {
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
        root.append(area);
        area.layout(l -> l.width(600).height(400));

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();   // the ticker registers on the first layout, so the rebuild lands on the second
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

    /**
     * A point a fraction of the way across an element, in SURFACE pixels.
     *
     * <p>{@code worldX()} rather than {@code x()}: the box's own offset is PARENT-RELATIVE on this
     * engine where the old runtime cache was absolute, so a tab sitting inside its strip reads a
     * small number and every drop landed near the origin. The world position already carries the
     * scale, so only the FRACTION of the box takes it.</p>
     */
    private int[] at(UIElement element, float fx, float fy) {
        var box = element.box();
        return new int[]{
                Math.round(box.worldX() + box.width() * fx * uiScale()),
                Math.round(box.worldY() + box.height() * fy * uiScale())
        };
    }

    /** Surface centre of an element. */
    private int[] centre(UIElement element) {
        return at(element, 0.5f, 0.5f);
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

        UIElement built = area.builtRoot();
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
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        frame();
        frame();

        UIElement built = area.builtRoot();
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
        UIElement contentBefore = group.tabFor(alpha).content().children().get(0);

        area.requestRebuild();
        frame();
        frame();

        UIElement contentAfter = area.groupFor(layout.leafContaining(alpha))
                                     .tabFor(alpha).content().children().get(0);
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
        UIElement before = area.builtRoot();

        layout.drop(layout.leaves().get(0), DockDropZone.SPLIT_DOWN, new DockLeaf(new DockPanelRef("gamma")));
        area.requestRebuild();

        assertSame("nothing moved yet", before, area.builtRoot());
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
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
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

    // ── The strip must match the model ──────────────────────────────────────────────────────────

    /**
     * Every group's tab strip holds exactly its leaf's panels, in order.
     *
     * <p>Asserted after every structural change, because until now every drag test checked
     * {@code panelIdsPerGroup()} — which reads the <b>layout</b>. The tree can be perfectly correct while
     * the strips show stale tabs from before the rebuild, and that is exactly what shipped.</p>
     */
    private void assertStripsMatchModel() {
        for (DockLeaf leaf : layout.leaves()) {
            DockGroup group = area.groupFor(leaf);
            assertNotNull("no group for " + leaf, group);
            List<String> inStrip = new ArrayList<>();
            for (Tab tab : group.tabView().getTabs()) inStrip.add(tab.getText());
            List<String> inModel = new ArrayList<>();
            for (int i = 0; i < leaf.panelCount(); i++) {
                inModel.add(area.registry().titleOf(leaf.panel(i)));
            }
            assertEquals("strip does not match model for " + leaf, inModel, inStrip);
        }
    }

    /** The reported bug: drag a tab into another group and both strips keep their old tabs too. */
    @Test
    public void aCrossGroupMergeLeavesNoStaleTabsBehind() {
        setUpTwoGroups();
        assertStripsMatchModel();

        DockGroup right = area.groupFor(layout.leafContaining(beta));
        dragPanelTo(alpha, right, 0.5f, 0.5f);

        assertStripsMatchModel();
    }

    /** The same for a split, which rebuilds a different part of the tree. */
    @Test
    public void aSplitLeavesNoStaleTabsBehind() {
        setUpTwoGroups();
        DockGroup right = area.groupFor(layout.leafContaining(beta));

        dragPanelTo(alpha, right, 0.05f, 0.5f);

        assertStripsMatchModel();
    }

    /** And for a group that keeps some panels while losing one. */
    @Test
    public void movingOnePanelOutOfAPairLeavesNoStaleTabs() {
        DockLeaf pair = new DockLeaf(alpha, beta);
        layout = DockLayout.of(pair);
        DockPanelRef gamma = new DockPanelRef("gamma");
        layout.drop(pair, DockDropZone.SPLIT_RIGHT, new DockLeaf(gamma));

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();
        assertStripsMatchModel();

        dragPanelTo(beta, area.groupFor(layout.leafContaining(gamma)), 0.5f, 0.5f);

        assertStripsMatchModel();
    }

    /** Every DockGroup actually attached under the area, found by walking the element tree. */
    private List<DockGroup> attachedGroups() {
        List<DockGroup> out = new ArrayList<>();
        collectGroups(area, out);
        return out;
    }

    private static void collectGroups(UIElement element, List<DockGroup> out) {
        if (element instanceof DockGroup group) out.add(group);
        for (UIElement child : element.children()) collectGroups(child, out);
    }

    /**
     * <b>The scene's own layout, which is the shape that broke.</b>
     *
     * <p>A central leaf holding two documents, inside a nested branch with a console below it, with a
     * tool panel either side. The two-group test could not produce the bug; this is the difference
     * between testing a widget and testing the thing people actually build with it.</p>
     */
    private void setUpSceneLayout() {
        DockLeaf centre = new DockLeaf(alpha, beta);
        centre.setCentral(true);
        layout = DockLayout.of(centre);
        layout.drop(centre, DockDropZone.SPLIT_LEFT, new DockLeaf(new DockPanelRef("gamma")));
        layout.drop(centre, DockDropZone.SPLIT_RIGHT, new DockLeaf(new DockPanelRef("delta")));
        layout.drop(centre, DockDropZone.SPLIT_DOWN, new DockLeaf(new DockPanelRef("console")));

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(900).height(600)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(900).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();
    }

    /** One group per leaf in the tree — no stale one left attached by a rebuild. */
    private void assertOneGroupPerLeaf() {
        assertEquals("attached DockGroups should equal leaves",
                layout.leaves().size(), attachedGroups().size());

        // Counted as ELEMENTS, not through TabView.getTabs().
        //
        // This is the assertion that found the bug, after two rounds of tests that could not. A Tab can
        // survive a rebuild while being absent from every TabView's list -- markAsInternal() recurses, so
        // one addInternalChild on a built tree stamped every Tab internal and removeChild silently refused
        // them from then on. The list stayed correct; the rail grew a dead tab per rebuild. Anything
        // asserted through getTabs() agreed with the model the whole time.
        int panels = 0;
        for (DockLeaf leaf : layout.leaves()) panels += leaf.panelCount();
        StringBuilder where = new StringBuilder();
        reportStrayTabs(area, where);
        assertEquals("Tab elements in the tree should equal panels in the model." + where,
                panels, countTabElements(area));
    }

    /** Per group: what its tab list says, versus how many Tab elements are actually under it. */
    private static void reportStrayTabs(UIElement element, StringBuilder out) {
        if (element instanceof DockGroup group) {
            out.append(" | group(").append(group.leaf().panelCount()).append(" panels) list=")
               .append(group.tabView().getTabCount())
               .append(" elements=").append(countTabElements(group));
        }
        for (UIElement child : element.children()) reportStrayTabs(child, out);
    }

    private static int countTabElements(UIElement element) {
        int count = element instanceof Tab ? 1 : 0;
        for (UIElement child : element.children()) count += countTabElements(child);
        return count;
    }

    /** The reported bug, in the layout it was reported in. */
    @Test
    public void draggingADocumentIntoTheConsoleLeavesNoStaleTabs() {
        setUpSceneLayout();
        assertOneGroupPerLeaf();
        assertStripsMatchModel();

        DockLeaf consoleLeaf = layout.leafContaining(new DockPanelRef("console"));
        dragPanelTo(beta, area.groupFor(consoleLeaf), 0.5f, 0.5f);

        assertOneGroupPerLeaf();
        assertStripsMatchModel();
    }

    /** And the same drag onto the console's tab strip rather than its body. */
    @Test
    public void draggingADocumentOntoTheConsolesStripLeavesNoStaleTabs() {
        setUpSceneLayout();
        DockLeaf consoleLeaf = layout.leafContaining(new DockPanelRef("console"));
        DockGroup console = area.groupFor(consoleLeaf);

        int[] from = centre(tabOf(beta));
        int[] to = centre(console.tabView().getTabs().get(0));
        mouseTo(from[0], from[1]);
        frame();
        press(from[0], from[1]);
        mouseTo(to[0], to[1]);
        frame();
        release(to[0], to[1]);
        frame();
        frame();

        assertOneGroupPerLeaf();
        assertStripsMatchModel();
    }

    // ── Splitting must not resize anything else ─────────────────────────────────────────────────

    /**
     * <b>A split divides the pane it lands on, and touches nothing else.</b>
     *
     * <p>Reported from the scene: splitting a shared dock produced a new pane of equal size and shrank an
     * unrelated column. The cause was the weight write-back resolving each split view to a branch by
     * positional index against the <em>already-mutated</em> tree — correct only while the shape has not
     * changed, which is precisely never at the moment it runs.</p>
     */
    @Test
    public void aSplitDoesNotResizeUnrelatedPanes() {
        DockLeaf a = new DockLeaf(alpha);
        layout = DockLayout.of(a);
        DockLeaf b = new DockLeaf(beta);
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockPanelRef gammaRef = new DockPanelRef("gamma");
        DockLeaf c = new DockLeaf(gammaRef);
        layout.drop(b, DockDropZone.SPLIT_RIGHT, c);

        a.size(1f);
        b.size(2f);
        c.size(3f);

        area = new DockArea(registry(), layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();

        // Split A in two by dropping a fresh panel on its right edge, through the layout exactly as a
        // drop does, then let the rebuild run.
        layout.drop(a, DockDropZone.SPLIT_RIGHT, new DockLeaf(new DockPanelRef("delta")));
        area.requestRebuild();
        frame();
        frame();

        assertEquals("A halved", 0.5f, a.size(), 1e-3f);
        assertEquals("and the newcomer took the other half",
                0.5f, layout.root().child(1).size(), 1e-3f);
        assertEquals("B untouched", 2f, b.size(), 1e-3f);
        assertEquals("C untouched", 3f, c.size(), 1e-3f);
    }

    /** A divider the user dragged keeps its position across an unrelated rebuild. */
    @Test
    public void aDividerDragSurvivesARebuild() {
        setUpTwoGroups();
        SplitView split = (SplitView) area.builtRoot();
        split.setPercentage(25f);
        frame();

        area.requestRebuild();
        frame();
        frame();

        SplitView after = (SplitView) area.builtRoot();
        assertEquals("the drag was not thrown away by the rebuild",
                25f, after.getPercentage(), 1.5f);
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
                !(area.builtRoot() instanceof SplitView));
    }

    // ── Opening panels at runtime ───────────────────────────────────────────────────────────────

    /**
     * <b>Opening a panel selects it — and exactly one tab is selected afterwards.</b>
     *
     * <p>This failed with a one-step lag: opening a third document left the <em>second</em> looking
     * selected. The cause was a feedback loop between the two directions of the same binding.
     * {@code DockGroup} writes {@code onTabSelected} back into the leaf so a user's click moves the model —
     * but {@code rebuildStrip} changes the strip's selection repeatedly for its own reasons
     * ({@code clearTabs} promotes a survivor per removal; the first {@code addTab} selects itself), and
     * every one of those was written back. The model's selection was destroyed by the act of displaying
     * it, and {@code sync()} then faithfully showed the corrupted value.</p>
     *
     * <p>Every piece was individually correct — {@code DockLeaf.add} activates what it inserts,
     * {@code TabView.selectTab} is exclusive, computed styles tracked the model exactly — which is why
     * nothing here caught it. <b>Nothing exercised both directions at once.</b> Asserting the count of
     * selected tabs as well as which one is what makes that reachable.</p>
     */
    @Test
    public void openingAPanelSelectsItAndLeavesExactlyOneTabSelected() {
        setUpTwoGroups();
        DockLeaf target = layout.leaves().get(0);

        for (String id : new String[]{"gamma", "delta", "console"}) {
            DockPanelRef opened = new DockPanelRef(id);
            target.add(opened);
            target.activate(opened);
            area.requestRebuild();
            frame();
            frame();

            assertEquals("the leaf forgot what was just opened", opened, target.activePanel());

            List<Tab> selected = new ArrayList<>();
            List<Tab> all = new ArrayList<>();
            collectTabs(area, all, selected);
            assertEquals("more than one tab is selected after opening " + id,
                    layout.leaves().size(), selected.size());

            Tab shown = area.groupFor(target).tabView().getSelectedTab();
            assertSame("the strip is showing a different tab than the model says",
                    area.groupFor(target).tabFor(opened), shown);
        }
    }

    private static void collectTabs(UIElement element, List<Tab> all, List<Tab> selected) {
        if (element instanceof Tab tab) {
            all.add(tab);
            if (tab.isChecked()) selected.add(tab);
        }
        for (UIElement child : element.children()) collectTabs(child, all, selected);
    }

    // ── The dragged tab leaves the strip (W9) ───────────────────────────────────────────────────

    /** The insertion gap in a group's tab rail, whether or not it is currently showing. */
    private UIElement gapIn(DockGroup group) {
        for (UIElement child : group.tabView().rail().children()) {
            if (child.hasClass(InsertionMarker.MARKER_CLASS)) return child;
        }
        return null;
    }

    /**
     * Whether an element is out of the layout.
     *
     * <p>Asked this way round because {@code getComputed} answers <b>null</b> for a property nothing has
     * written — the initial value is not a candidate — so a tab that has never been touched reports
     * neither {@code FLEX} nor {@code NONE}. Only "is it hidden" is a question every state can answer.</p>
     */
    private boolean isHidden(UIElement element) {
        assertNotNull(element);
        return element.getStyle().getComputed(LayoutProperties.DISPLAY) == TaffyDisplay.NONE;
    }

    /**
     * <b>The tab being carried leaves the strip, and a gap the same size stands in its place.</b>
     *
     * <p>Both halves matter and only one of them is cosmetic. Leaving the tab in the strip shows the
     * same file twice — once where it is and once where it is going — and, worse, a hidden tab left in
     * the list has no box, so every midpoint test after it answers against a cell that is not there.
     * The gap in the cell it just left is what keeps the arithmetic honest: without it the strip
     * collapses by one tab, so a pointer that has not moved is suddenly in its neighbour's cell.</p>
     */
    @Test
    public void aDraggedTabLeavesTheStripAndAGapStandsInForIt() {
        DockGroup group = setUpOneGroupOfTwo();
        Tab tab = tabOf(alpha);
        assertFalse(isHidden(tab));
        assertTrue("the gap rests closed", isHidden(gapIn(group)));

        int[] from = centre(tab);
        mouseTo(from[0], from[1]);
        frame();
        press(from[0], from[1]);
        mouseTo(from[0] + 30, from[1]);
        frame();

        assertTrue("out of the strip for the duration", isHidden(tab));
        UIElement gap = gapIn(group);
        assertNotNull("the gap is a sibling of the tabs, in the rail", gap);
        assertFalse("and it is open", isHidden(gap));

        release(from[0] + 30, from[1]);
        frame();
        frame();

        // ASKED OF THE LIVE TAB, never of the one captured above: a drop rebuilds the strip, so the
        // element that was hidden may already have left the tree -- which is exactly the case
        // InsertionMarker.restore refuses to write to.
        assertFalse("back in the strip", isHidden(tabOf(alpha)));
        assertTrue("and the gap has closed", isHidden(gapIn(group)));
    }

    /**
     * <b>A click is not a drag, so the tab never moves.</b>
     *
     * <p>The withdrawal happens on the drag's first TICK rather than on the press, and this is the
     * difference: a payload drag fires nothing until the pointer passes its activation threshold.
     * Hiding on the press instead makes a tab vanish the instant you touch it and reappear when you let
     * go — a flicker on every single click in the strip.</p>
     */
    @Test
    public void pressingATabWithoutMovingNeverTakesItOutOfTheStrip() {
        setUpOneGroupOfTwo();
        Tab tab = tabOf(alpha);

        int[] on = centre(tab);
        mouseTo(on[0], on[1]);
        frame();
        press(on[0], on[1]);
        frame();
        assertFalse("still there mid-press", isHidden(tab));

        release(on[0], on[1]);
        frame();
        assertFalse(isHidden(tabOf(alpha)));
    }
}
