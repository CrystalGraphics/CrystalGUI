package com.crystalgui.workbench.toolwindow;

import com.crystalgui.serialization.UIDescriptionCodec;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.region.RegionHost;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.layout.DockPath;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.toolwindow.ToolWindowLayout;
import com.crystalgui.workbench.toolwindow.ToolWindowState;
import com.crystalgui.workbench.toolwindow.ToolWindowType;

import com.google.gson.JsonElement;
import com.crystalgui.desktop.Desktop;
import org.junit.Before;
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

    /**
     * Animations OFF, said out loud rather than inherited. This fixture asserts a window's STATE
     * straight after a gesture, and an animation defers exactly that -- `hide()` detaches and
     * `close()` destroys only once the flight ends, so the assertion reads VISIBLE for a window that
     * has been asked to go. It used to pass by picking up a flag some other class had left off.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

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
        ToolWindowState original = ToolWindowState.initial("project", DockRegion.SIDEBAR, 0);
        ToolWindowState moved = original.withRegion(DockRegion.PANEL)
                .withSide(RegionSide.SECONDARY).withWeight(0.4f);
        assertEquals(DockRegion.SIDEBAR, original.region());
        assertEquals(RegionSide.PRIMARY, original.side());
        assertEquals(ToolWindowState.DEFAULT_WEIGHT, original.weight(), 1e-6f);
        assertEquals(DockRegion.PANEL, moved.region());
        assertEquals(RegionSide.SECONDARY, moved.side());
        assertEquals(0.4f, moved.weight(), 1e-6f);
    }

    /** Order is the stripe's, and it survives independently of insertion order. */
    @Test
    public void placementsComeBackInStripeOrder() {
        ToolWindowLayout layout = new ToolWindowLayout();
        layout.put(ToolWindowState.initial("c", DockRegion.SIDEBAR, 2));
        layout.put(ToolWindowState.initial("a", DockRegion.SIDEBAR, 0));
        layout.put(ToolWindowState.initial("b", DockRegion.SIDEBAR, 1));
        assertEquals(List.of("a", "b", "c"),
                layout.ordered().stream().map(ToolWindowState::typeId).toList());
    }

    /**
     * <b>The side round-trips, which is the whole of what version 6 added.</b>
     *
     * <p>A record that dropped it would decode perfectly and be wrong: every tool window would come back in
     * the first half of its region, so a bottom-right panel would reappear bottom-left — on the other rail,
     * which is what makes it look like the button moved rather than the record being incomplete.</p>
     */
    @Test
    public void theHalfOfARegionSurvivesTheRecord() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        ToolWindowLayout source = new ToolWindowLayout();
        source.put(ToolWindowState.initial("terminal", DockRegion.PANEL, 0)
                .withSide(RegionSide.SECONDARY).withVisible(true));
        source.encodeInto(out, "toolWindows");

        ToolWindowState read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, out.encode()), "toolWindows").get("terminal");
        assertNotNull(read);
        assertEquals(DockRegion.PANEL, read.region());
        assertEquals(RegionSide.SECONDARY, read.side());
        assertTrue(read.visible());
    }

    /**
     * <b>The mode and the frame's geometry round-trip — W8.</b>
     *
     * <p>A record that dropped either would decode perfectly and be wrong in a way with no error to
     * attribute it to. Losing the mode brings every tool window back <em>docked</em>, so an arrangement
     * the user built out of floats is silently flattened on the next launch. Losing the rect brings the
     * float back at its default size in the default corner, which reads as the window manager forgetting
     * rather than the record being incomplete.</p>
     */
    @Test
    public void theModeAndItsFramesGeometrySurviveTheRecord() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        ToolWindowLayout source = new ToolWindowLayout();
        source.put(ToolWindowState.initial("inspector", DockRegion.AUXILIARY, 0)
                .withType(ToolWindowType.FLOATING)
                .withFloatingBounds(new ToolWindowState.Bounds(40f, 55f, 300f, 220f)));
        source.encodeInto(out, "toolWindows");

        ToolWindowState read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, out.encode()), "toolWindows").get("inspector");
        assertNotNull(read);
        assertEquals(ToolWindowType.FLOATING, read.type());
        assertNotNull("the rect went missing", read.floatingBounds());
        assertEquals(40f, read.floatingBounds().left(), 1e-6f);
        assertEquals(55f, read.floatingBounds().top(), 1e-6f);
        assertEquals(300f, read.floatingBounds().width(), 1e-6f);
        assertEquals(220f, read.floatingBounds().height(), 1e-6f);
        assertEquals("and the region it still belongs to came with it",
                DockRegion.AUXILIARY, read.region());
    }

    /**
     * <b>A tool window that has never floated carries no rect, and must not gain one.</b>
     *
     * <p>An absent optional is omitted rather than written as zeroes — {@code UIDescriptionCodec}'s rule,
     * and sharper here: a 0×0 frame at the origin is a legal encoding, so a reader cannot tell it from
     * "never floated". Restoring one would put a window on screen with nothing to see and nothing to
     * grab.</p>
     */
    @Test
    public void aToolWindowThatNeverFloatedCarriesNoRect() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        ToolWindowLayout source = new ToolWindowLayout();
        source.put(ToolWindowState.initial("project", DockRegion.SIDEBAR, 0));
        source.encodeInto(out, "toolWindows");

        ToolWindowState read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, out.encode()), "toolWindows").get("project");
        assertNotNull(read);
        assertEquals(ToolWindowType.DOCKED, read.type());
        assertNull(read.floatingBounds());
    }

    /**
     * A mode this build does not have falls back to docked, for the reason every other enum on this
     * record does: a session is untrusted input written by a possibly-newer build, and losing the mode
     * must not cost the region, the order and whether it was open.
     */
    @Test
    public void anUnknownModeFallsBackToDocked() {
        assertEquals(ToolWindowType.DOCKED, ToolWindowType.ofName("SLIDING"));
        assertEquals(ToolWindowType.DOCKED, ToolWindowType.ofName(""));
        assertEquals(ToolWindowType.FLOATING, ToolWindowType.ofName("FLOATING"));
    }

    /**
     * A record naming a tool window this build no longer has must not cost the others their placements —
     * removing a mod is ordinary, and a session is not a schema.
     */
    @Test
    public void aMalformedEntryIsDroppedRatherThanTakingTheRecordWithIt() {
        StateMap<JsonElement> out = new StateMap<>(JsonOps.INSTANCE);
        ToolWindowLayout source = new ToolWindowLayout();
        source.put(ToolWindowState.initial("", DockRegion.SIDEBAR, 0));   // no id
        source.put(ToolWindowState.initial("good", DockRegion.PANEL, 1).withWeight(0.3f));
        source.encodeInto(out, "toolWindows");

        ToolWindowLayout read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, out.encode()), "toolWindows");
        assertNull("an entry with no id was kept", read.get(""));
        assertNotNull("a good entry was lost with a bad one", read.get("good"));
        assertEquals(0.3f, read.get("good").weight(), 1e-6f);
    }

    /**
     * A region this build does not know costs one drag, not the whole placement.
     *
     * <p>Written as raw JSON, because that is what a newer build's record <em>is</em> — and because
     * {@code StateMap.getEnum} throws for an unknown constant, which is exactly the behaviour this decoder
     * must not inherit.</p>
     */
    @Test
    public void anUnknownRegionFallsBackWithoutLosingTheRest() {
        JsonElement record = com.google.gson.JsonParser.parseString(
                "{\"toolWindows\":[{\"id\":\"future\",\"region\":\"HOLOGRAM\",\"side\":\"TERTIARY\","
                        + "\"weight\":0.42,\"visible\":true,\"order\":3}]}");

        ToolWindowLayout read = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, record), "toolWindows");
        ToolWindowState state = read.get("future");
        assertNotNull("an unreadable region dropped the whole placement", state);
        assertEquals(DockRegion.SIDEBAR, state.region());
        assertEquals(RegionSide.PRIMARY, state.side());
        assertEquals(0.42f, state.weight(), 1e-6f);
        assertTrue(state.visible());
        assertEquals(3, state.order());
    }

    /**
     * {@code EDITOR} is a legal region and an illegal <em>placement</em>, and it is refused here.
     *
     * <p>The interesting half of the fallback: an unknown name is obviously bad input, while this one
     * decodes to a real constant that simply has no {@code RegionHost}. Honoured, it would give a tool
     * window a region that can never show it — so it would never open, and never be reachable to move.</p>
     */
    @Test
    public void aToolWindowCannotClaimTheEditorRegion() {
        JsonElement record = com.google.gson.JsonParser.parseString(
                "{\"toolWindows\":[{\"id\":\"greedy\",\"region\":\"EDITOR\",\"order\":0}]}");

        ToolWindowState state = ToolWindowLayout.decodeFrom(
                new StateMap<>(JsonOps.INSTANCE, record), "toolWindows").get("greedy");
        assertNotNull(state);
        assertEquals(DockRegion.SIDEBAR, state.region());
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
