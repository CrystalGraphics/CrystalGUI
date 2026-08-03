package com.crystalgui.headless;

import com.crystalgui.ui.elements.dock.DockBranch;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockNode;
import com.crystalgui.ui.elements.dock.DockOrientation;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The layout tree — every structural operation, with no window, no fonts and no GL.
 *
 * <p>That this file runs in {@code headlessTest} is the point rather than a convenience: the tree is where
 * a docking system is actually right or wrong, and reaching it through a widget would mean testing splits
 * by waving a mouse at a scene.</p>
 */
public class DockLayoutTest {

    private static DockLeaf leaf(String id) {
        return new DockLeaf(new DockPanelRef(id));
    }

    /** The axis a branch divides, for readability in assertions. */
    private static DockOrientation axisOf(DockLayout layout, DockBranch branch) {
        return branch.orientation(layout.rootOrientation());
    }

    private static String ids(DockLayout layout) {
        StringBuilder sb = new StringBuilder();
        for (DockLeaf leaf : layout.leaves()) {
            if (sb.length() > 0) sb.append(',');
            for (int i = 0; i < leaf.panelCount(); i++) {
                if (i > 0) sb.append('+');           // panels sharing one strip
                sb.append(leaf.panel(i).typeId());
            }
        }
        return sb.toString();
    }

    // ── Dropping ────────────────────────────────────────────────────────────────────────────────

    /** A split along the axis the parent already divides is a plain sibling insert — no new branch. */
    @Test
    public void splittingAlongTheParentAxisInsertsASibling() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a); // root divides HORIZONTAL

        layout.drop(a, DockDropZone.SPLIT_RIGHT, leaf("B"));

        assertEquals(2, layout.root().childCount());
        assertTrue("no wrapper branch should have been created", layout.root().child(0).isLeaf());
        assertEquals("A,B", ids(layout));
        layout.checkInvariants();
    }

    /** A split across the other axis wraps the target, and the wrapper divides the axis asked for. */
    @Test
    public void splittingAcrossTheParentAxisWrapsTheTarget() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);

        layout.drop(a, DockDropZone.SPLIT_DOWN, leaf("B"));

        assertEquals(1, layout.root().childCount());
        DockNode wrapper = layout.root().child(0);
        assertTrue("the target should now sit inside a branch", !wrapper.isLeaf());
        assertEquals("and that branch divides the axis the drop asked for",
                DockOrientation.VERTICAL, axisOf(layout, (DockBranch) wrapper));
        assertEquals("A,B", ids(layout));
        layout.checkInvariants();
    }

    /**
     * <b>The orthogonal case is always exactly right, and nothing computes it.</b>
     *
     * <p>The wrapper takes the target's slot and therefore its depth, so its children land one level
     * deeper and the derived orientation flips for them automatically. There are only two axes, so "not
     * the parent's" is "the one we wanted" — which is why storing an orientation per branch would be a
     * chance to get this wrong.</p>
     */
    @Test
    public void aWrapperAlwaysDividesTheOppositeAxisAtEveryDepth() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);

        layout.drop(a, DockDropZone.SPLIT_DOWN, leaf("B"));   // wrapper divides VERTICAL
        DockBranch wrapper = (DockBranch) layout.root().child(0);
        assertEquals(DockOrientation.VERTICAL, axisOf(layout, wrapper));

        layout.drop(a, DockDropZone.SPLIT_RIGHT, leaf("C"));  // across VERTICAL -> a deeper wrapper
        DockBranch inner = (DockBranch) wrapper.child(0);
        assertEquals(DockOrientation.HORIZONTAL, axisOf(layout, inner));
        assertEquals("C should land to the right of A, inside it", "A,C,B", ids(layout));
        layout.checkInvariants();
    }

    /** MERGE appends to the strip — the drop the whole system is used for most. */
    @Test
    public void mergeAppendsToTheTabStrip() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);

        DockLeaf landed = layout.drop(a, DockDropZone.MERGE, leaf("B"));

        assertSame("a merge lands in the target itself", a, landed);
        assertEquals(1, layout.leaves().size());
        assertEquals(2, a.panelCount());
        assertEquals("a merged panel becomes active", 1, a.activeIndex());
        layout.checkInvariants();
    }

    /** Dropping a group into itself would detach the tree from its own root. */
    @Test
    public void droppingANodeIntoItselfIsRefused() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        layout.drop(a, DockDropZone.SPLIT_RIGHT, leaf("B"));

        DockBranch root = layout.root();
        DockLeaf target = layout.leaves().get(0);
        try {
            layout.drop(target, DockDropZone.SPLIT_RIGHT, root);
            fail("dropping the root into one of its own leaves must be refused");
        } catch (IllegalArgumentException expected) {
            // the message names the condition, not the call
        }
    }

    /** A sibling insert splits only the target's weight — every other pane keeps its proportion. */
    @Test
    public void aSiblingInsertOnlyDividesTheTargetsWeight() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        a.size(6f);
        b.size(2f);

        layout.drop(a, DockDropZone.SPLIT_RIGHT, leaf("C"));

        assertEquals("A halves", 3f, a.size(), 1e-4f);
        assertEquals("C takes the other half", 3f, layout.root().child(1).size(), 1e-4f);
        assertEquals("B is untouched", 2f, b.size(), 1e-4f);
    }

    // ── Removal and collapse ────────────────────────────────────────────────────────────────────

    /** A branch left with one leaf dissolves into its grandparent. */
    @Test
    public void aOneChildBranchCollapsesIntoItsGrandparent() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);       // B is now inside a vertical wrapper

        assertEquals(2, layout.root().childCount());
        assertTrue(!layout.root().child(1).isLeaf());

        layout.remove(c);

        assertEquals("the wrapper is gone", 2, layout.root().childCount());
        assertTrue("B sits directly under the root again", layout.root().child(1).isLeaf());
        assertEquals("A,B", ids(layout));
        layout.checkInvariants();
    }

    /** The dissolved branch's weight goes to the survivor, not back to the whole row. */
    @Test
    public void aCollapseGivesTheBranchsWeightToTheSurvivor() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);

        DockNode wrapper = layout.root().child(1);
        wrapper.size(7f);
        a.size(3f);

        layout.remove(c);

        assertEquals("A untouched by a collapse elsewhere", 3f, a.size(), 1e-4f);
        assertEquals("B inherits the whole wrapper's weight", 7f, b.size(), 1e-4f);
    }

    /**
     * <b>A surviving branch has its children spliced in, and their proportions are preserved.</b>
     *
     * <p>Not the branch itself: a branch at depth {@code D+1} divides the same axis as the grandparent at
     * {@code D-1}, so its children slot straight in while it would divide the wrong one.</p>
     */
    @Test
    public void aSurvivingBranchIsSplicedIntoTheGrandparent() {
        // root(H): [A, wrapper(V): [ B, inner(H): [C, D] ] ] — then remove B.
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);       // wrapper(V) = [B, C]
        DockLeaf d = leaf("D");
        layout.drop(c, DockDropZone.SPLIT_RIGHT, d);      // inner(H) = [C, D] inside the wrapper

        DockBranch wrapper = (DockBranch) layout.root().child(1);
        wrapper.size(8f);
        c.size(1f);
        d.size(3f);

        layout.remove(b);

        assertEquals("the inner branch's CHILDREN were spliced in, not the branch itself",
                3, layout.root().childCount());
        assertTrue(layout.root().child(1).isLeaf());
        assertTrue(layout.root().child(2).isLeaf());
        assertEquals("A,C,D", ids(layout));

        assertEquals("the spliced children fill exactly the weight the branch had",
                8f, c.size() + d.size(), 1e-4f);
        assertEquals("and keep their 1:3 proportion", 3f, d.size() / c.size(), 1e-4f);
        layout.checkInvariants();
    }

    /**
     * <b>Promoting a branch to root flips the root orientation, and the picture does not move.</b>
     *
     * <p>Everything under a promoted branch loses a level, so every derived axis flips with it. VS Code
     * gets this free because its root orientation simply <em>is</em> {@code this.root.orientation}; here it
     * is one deliberate flip, and forgetting it would turn the whole layout on its side after an unrelated
     * close.</p>
     */
    @Test
    public void promotingABranchToRootFlipsTheRootOrientation() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);       // wrapper(V) = [B, C]

        DockBranch wrapper = (DockBranch) layout.root().child(1);
        assertEquals(DockOrientation.VERTICAL, axisOf(layout, wrapper));

        layout.remove(a);                                  // root left holding only the wrapper

        assertSame("the wrapper became the root", wrapper, layout.root());
        assertEquals("the root orientation flipped to keep it dividing VERTICAL",
                DockOrientation.VERTICAL, layout.rootOrientation());
        assertEquals("so it still divides exactly what it divided before",
                DockOrientation.VERTICAL, axisOf(layout, layout.root()));
        assertEquals("B,C", ids(layout));
        layout.checkInvariants();
    }

    /** A root branch holding one leaf is the resting state, not something to collapse further. */
    @Test
    public void aRootHoldingOneLeafIsLeftAlone() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);

        layout.remove(b);

        assertEquals(1, layout.root().childCount());
        assertSame(a, layout.root().child(0));
        layout.checkInvariants();
    }

    // ── Panels ──────────────────────────────────────────────────────────────────────────────────

    /** Closing the last panel in a leaf takes the leaf with it. */
    @Test
    public void closingTheLastPanelRemovesTheLeaf() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockPanelRef bPanel = new DockPanelRef("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, new DockLeaf(bPanel));

        layout.closePanel(bPanel);

        assertEquals(1, layout.leaves().size());
        assertEquals("A", ids(layout));
        layout.checkInvariants();
    }

    /** Closing the active tab selects its predecessor — walking back through what you had open. */
    @Test
    public void closingTheActiveTabSelectsItsPredecessor() {
        DockPanelRef one = new DockPanelRef("1");
        DockPanelRef two = new DockPanelRef("2");
        DockPanelRef three = new DockPanelRef("3");
        DockLeaf leaf = new DockLeaf(one, two, three);
        leaf.activate(2);

        leaf.remove(three);

        assertSame(two, leaf.activePanel());
    }

    /** The central leaf is the guarantee the main work area exists — it survives being emptied. */
    @Test
    public void theCentralLeafSurvivesBeingEmptiedAndCannotBeRemoved() {
        DockPanelRef doc = new DockPanelRef("doc");
        DockLeaf central = new DockLeaf(doc);
        central.setCentral(true);
        DockLayout layout = DockLayout.of(central);
        layout.drop(central, DockDropZone.SPLIT_RIGHT, leaf("side"));

        layout.closePanel(doc);
        assertTrue("emptied but still there", central.isEmpty());
        assertSame(central, layout.centralLeaf());

        assertNull("and it refuses to be removed outright", layout.remove(central));
        layout.checkInvariants();
    }

    /** Moving a panel out of a leaf collapses the leaf it emptied. */
    @Test
    public void movingTheLastPanelOutCollapsesTheSourceLeaf() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockPanelRef bPanel = new DockPanelRef("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, new DockLeaf(bPanel));

        layout.movePanel(bPanel, a, 0);

        assertEquals(1, layout.leaves().size());
        assertEquals("both panels now share one strip", "B+A", ids(layout));
        layout.checkInvariants();
    }

    /** Only one leaf is maximized at a time. */
    @Test
    public void maximizingOneLeafRestoresTheOther() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);

        layout.maximize(a);
        layout.maximize(b);

        assertSame(b, layout.maximizedLeaf());
        assertTrue(!a.isMaximized());
        layout.checkInvariants();
    }

    // ── Outer edge ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>An outer-edge drop across the root's axis re-roots the tree instead of wrapping a pane.</b>
     *
     * <p>This is what "a full-height column beside all four of these rows" needs, and it is the one thing
     * VS Code's per-group drop targets cannot express.</p>
     */
    @Test
    public void anOuterEdgeDropAcrossTheRootAxisRerootsAndKeepsThePicture() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);           // root divides HORIZONTAL
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);    // [A | B]

        layout.dropOnOuterEdge(DockDropZone.SPLIT_UP, leaf("top"));

        assertEquals("the root now divides VERTICAL", DockOrientation.VERTICAL, layout.rootOrientation());
        assertEquals(2, layout.root().childCount());
        assertTrue("the old row was carried down a level", !layout.root().child(1).isLeaf());
        assertEquals("and still divides HORIZONTAL, so it looks identical",
                DockOrientation.HORIZONTAL, axisOf(layout, (DockBranch) layout.root().child(1)));
        assertEquals("top,A,B", ids(layout));
        layout.checkInvariants();
    }

    /** Along the root's own axis it is a plain append — no re-rooting. */
    @Test
    public void anOuterEdgeDropAlongTheRootAxisJustAppends() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);

        layout.dropOnOuterEdge(DockDropZone.SPLIT_RIGHT, leaf("right"));

        assertEquals(DockOrientation.HORIZONTAL, layout.rootOrientation());
        assertEquals("A,right", ids(layout));
        layout.checkInvariants();
    }

    // ── Paths ───────────────────────────────────────────────────────────────────────────────────

    /** A path identifies a node without holding it — what makes a drop describable before it happens. */
    @Test
    public void pathsAddressNodesFromTheRoot() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);

        assertEquals(List.of(), layout.root().path());
        assertEquals(List.of(0), a.path());
        assertEquals(List.of(1, 0), b.path());
        assertEquals(List.of(1, 1), c.path());
        assertNotNull(c.parent());
    }
}
