package com.crystalgui.ui;

import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPath;
import com.crystalgui.ui.elements.workbench.ToolWindowLayout;
import com.crystalgui.ui.elements.workbench.ToolWindowState;

import com.google.gson.JsonElement;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The placement model — a port of IntelliJ's {@code WindowInfoImpl} / {@code DesktopLayout}.
 *
 * <p>Headless on purpose. The whole point of the model is that a tool window's placement is <b>data</b>
 * rather than something derived from a widget tree, so it must be testable without one — the same argument
 * {@code text/cursor} makes, and the reason the dock's own logic lives in {@code DockLayout} rather than in
 * {@code DockArea}.</p>
 */
public class ToolWindowPlacementTest {

    // ── DockPath ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aPathRoundTripsThroughItsText() {
        assertEquals("0.1.2", DockPath.of(0, 1, 2).toString());
        assertEquals(DockPath.of(0, 1, 2), DockPath.parse("0.1.2"));
        assertEquals(DockPath.ROOT, DockPath.parse(""));
        assertEquals(DockPath.ROOT, DockPath.of());
        assertTrue(DockPath.ROOT.isRoot());
        assertEquals(3, DockPath.of(0, 1, 2).depth());
        assertEquals(2, DockPath.of(0, 1, 2).lastIndex());
        assertEquals(DockPath.of(0, 1), DockPath.of(0, 1, 2).parent());
        assertEquals(DockPath.of(0, 1, 2), DockPath.of(0, 1).child(2));
    }

    /** A record is untrusted input, so anything malformed is null rather than an exception. */
    @Test
    public void aMalformedPathIsRejectedRatherThanGuessedAt() {
        assertNull(DockPath.parse("0.x.2"));
        assertNull(DockPath.parse("-1"));
        assertNull(DockPath.parse(null));
        assertNull(DockPath.parse("1..2"));
    }

    // ── Reading a position out of a tree, and putting one back ──────────────────────────────────

    @Test
    public void aPathNamesWhereANodeActuallyIs() {
        DockLeaf first = new DockLeaf(new DockPanelRef("a"));
        DockLayout layout = DockLayout.of(first);
        DockLeaf second = new DockLeaf(new DockPanelRef("b"));
        layout.drop(first, DockDropZone.SPLIT_RIGHT, second);

        assertEquals(DockPath.ROOT, layout.pathOf(layout.root()));
        DockPath path = layout.pathOf(second);
        assertNotNull("a node in the tree has no path", path);
        assertSameNode(layout, path, second);
    }

    /** A detached node has no position, which is why a placement must be captured before a close. */
    @Test
    public void aDetachedNodeHasNoPath() {
        DockLeaf first = new DockLeaf(new DockPanelRef("a"));
        DockLayout layout = DockLayout.of(first);
        DockLeaf second = new DockLeaf(new DockPanelRef("b"));
        layout.drop(first, DockDropZone.SPLIT_RIGHT, second);

        layout.remove(second);
        assertNull("a removed node still claims a position", layout.pathOf(second));
    }

    /**
     * <b>A nested position restores exactly — when the branch holding it survives.</b>
     *
     * <p>Three children, so removing one leaves two and {@code normalise()} has no reason to dissolve the
     * branch. That is the case a path is for.</p>
     */
    @Test
    public void insertAtPutsANodeBackWhereItWas() {
        DockLeaf left = new DockLeaf(new DockPanelRef("left"));
        DockLayout layout = DockLayout.of(left);
        DockLeaf centre = new DockLeaf(new DockPanelRef("centre"));
        layout.drop(left, DockDropZone.SPLIT_RIGHT, centre);
        DockLeaf third = new DockLeaf(new DockPanelRef("third"));
        layout.drop(centre, DockDropZone.SPLIT_RIGHT, third);

        DockPath parent = layout.pathOf(centre.parent());
        int index = centre.parent().indexOf(centre);
        assertNotNull(parent);

        layout.remove(centre);
        assertNull(layout.leafContaining(new DockPanelRef("centre")));

        DockLeaf reopened = new DockLeaf(new DockPanelRef("centre"));
        assertTrue("a remembered position could not be honoured",
                layout.insertAt(parent, index, reopened));
        assertEquals("the panel came back somewhere else", parent, layout.pathOf(reopened.parent()));
        assertEquals(index, reopened.parent().indexOf(reopened));
    }

    /**
     * <b>The limit of a path, stated as a test rather than discovered again later.</b>
     *
     * <p>A leaf alone with one sibling leaves the branch with a single child when it goes, and
     * {@code normalise()} correctly dissolves it — so the path that named that branch stops resolving. This
     * is not a defect in {@code insertAt}; it is why {@link ToolWindowState#relativeTo()} exists, and it is
     * the <b>most common</b> arrangement of all, which is what makes it worth pinning.</p>
     */
    @Test
    public void aPathDoesNotSurviveTheBranchBeingCollapsed() {
        DockLeaf left = new DockLeaf(new DockPanelRef("left"));
        DockLayout layout = DockLayout.of(left);
        DockLeaf centre = new DockLeaf(new DockPanelRef("centre"));
        layout.drop(left, DockDropZone.SPLIT_RIGHT, centre);
        DockLeaf nested = new DockLeaf(new DockPanelRef("nested"));
        layout.drop(centre, DockDropZone.SPLIT_DOWN, nested);

        DockPath parent = layout.pathOf(nested.parent());
        assertNotNull(parent);
        assertFalse("setup failed: the panel is not nested", parent.isRoot());

        layout.remove(nested);

        DockLeaf reopened = new DockLeaf(new DockPanelRef("nested"));
        assertFalse("a path outlived the branch it named",
                layout.insertAt(parent, 1, reopened));

        // What DOES survive: the neighbour it was under. Replaying the drop against that reproduces it.
        DockLeaf beside = layout.leafContaining(new DockPanelRef("centre"));
        assertNotNull("the neighbour did not survive either", beside);
        layout.drop(beside, DockDropZone.SPLIT_DOWN, reopened);
        assertNotNull(layout.leafContaining(new DockPanelRef("nested")));
    }

    /** A path that no longer resolves reports failure instead of inserting somewhere near. */
    @Test
    public void insertAtRefusesAPathTheTreeNoLongerHas() {
        DockLayout layout = DockLayout.of(new DockLeaf(new DockPanelRef("a")));
        DockLeaf orphan = new DockLeaf(new DockPanelRef("b"));
        assertFalse("a stale path was honoured anyway",
                layout.insertAt(DockPath.of(9, 9), 0, orphan));
        assertNull("the tree was modified by a refused insert", orphan.parent());
    }

    /** Indices are clamped, because siblings come and go while a panel is closed. */
    @Test
    public void insertAtClampsAnIndexThatIsNoLongerValid() {
        DockLayout layout = DockLayout.of(new DockLeaf(new DockPanelRef("a")));
        DockLeaf added = new DockLeaf(new DockPanelRef("b"));
        assertTrue(layout.insertAt(DockPath.ROOT, 99, added));
        assertNotNull(layout.leafContaining(new DockPanelRef("b")));
    }

    // ── ToolWindowState / ToolWindowLayout ──────────────────────────────────────────────────────

    /** Immutable: a wither must not mutate the record another caller is about to persist. */
    @Test
    public void withersDoNotMutateTheOriginal() {
        ToolWindowState original = ToolWindowState.initial("project", DockDropZone.SPLIT_LEFT, 0);
        ToolWindowState moved = original.withAnchor(DockDropZone.SPLIT_DOWN).withWeight(0.4f);
        assertEquals(DockDropZone.SPLIT_LEFT, original.anchor());
        assertEquals(ToolWindowState.DEFAULT_WEIGHT, original.weight(), 1e-6f);
        assertEquals(DockDropZone.SPLIT_DOWN, moved.anchor());
        assertEquals(0.4f, moved.weight(), 1e-6f);
    }

    /** Order is the activity bar's, and it survives independently of insertion order. */
    @Test
    public void placementsComeBackInStripeOrder() {
        ToolWindowLayout layout = new ToolWindowLayout();
        layout.put(ToolWindowState.initial("c", DockDropZone.SPLIT_LEFT, 2));
        layout.put(ToolWindowState.initial("a", DockDropZone.SPLIT_LEFT, 0));
        layout.put(ToolWindowState.initial("b", DockDropZone.SPLIT_LEFT, 1));
        assertEquals(List.of("a", "b", "c"),
                layout.ordered().stream().map(ToolWindowState::typeId).toList());
    }

    /**
     * A record naming a tool window this build no longer has must not cost the others their placements —
     * removing a mod is ordinary, and a session is not a schema.
     */
    @Test
    public void aMalformedEntryIsDroppedRatherThanTakingTheRecordWithIt() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        ToolWindowLayout source = new ToolWindowLayout();
        source.put(ToolWindowState.initial("", DockDropZone.SPLIT_LEFT, 0));   // no id
        source.put(ToolWindowState.initial("good", DockDropZone.SPLIT_UP, 1).withWeight(0.3f));
        source.encodeInto(out, "toolWindows");

        ToolWindowLayout read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, out.encode()), "toolWindows");
        assertNull("an entry with no id was kept", read.get(""));
        assertNotNull("a good entry was lost with a bad one", read.get("good"));
        assertEquals(0.3f, read.get("good").weight(), 1e-6f);
    }

    /**
     * An anchor this build does not know costs one drag, not the whole placement.
     *
     * <p>Written as raw JSON, because that is what a newer build's record <em>is</em> — and because
     * {@code StateMap.getEnum} throws for an unknown constant, which is exactly the behaviour this decoder
     * must not inherit.</p>
     */
    @Test
    public void anUnknownAnchorFallsBackWithoutLosingTheRest() {
        JsonElement record = com.google.gson.JsonParser.parseString(
                "{\"toolWindows\":[{\"id\":\"future\",\"anchor\":\"SPLIT_DIAGONAL\","
                        + "\"weight\":0.42,\"visible\":true,\"order\":3}]}");

        ToolWindowLayout read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, record), "toolWindows");
        ToolWindowState state = read.get("future");
        assertNotNull("an unreadable anchor dropped the whole placement", state);
        assertEquals(DockDropZone.SPLIT_LEFT, state.anchor());
        assertEquals(0.42f, state.weight(), 1e-6f);
        assertTrue(state.visible());
        assertEquals(3, state.order());
    }

    /** A missing key is a first run, not a failure. */
    @Test
    public void anAbsentSectionDecodesToAnEmptyLayout() {
        ToolWindowLayout read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE), "toolWindows");
        assertTrue(read.isEmpty());
        assertNull(read.get("anything"));
    }

    private static void assertSameNode(DockLayout layout, DockPath path, Object expected) {
        assertEquals("the path does not name the node it was read from", expected, layout.nodeAt(path));
    }
}
