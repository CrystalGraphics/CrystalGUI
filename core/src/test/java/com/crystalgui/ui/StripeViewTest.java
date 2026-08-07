package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;
import com.crystalgui.ui.elements.workbench.RegionDropOverlay;
import com.crystalgui.ui.elements.workbench.StripeRail;
import com.crystalgui.ui.elements.workbench.StripeView;
import com.crystalgui.ui.elements.workbench.Workbench;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.ui.elements.dock.DockLeaf;

/**
 * The tool-window rails — IntelliJ's stripes, VS Code's Activity Bar.
 *
 * <p>What is worth pinning is not that buttons appear. It is the rules both originals share and that are
 * each individually easy to lose:</p>
 *
 * <ol>
 *   <li><b>Documents never appear on one.</b> A rail lists tool windows; one that listed open files would
 *       grow without bound and duplicate the tab strip.</li>
 *   <li><b>Click toggles.</b> Clicking the visible panel's button hides it, in both editors. Open-only
 *       gives you a bar that can fill the screen and not clear it.</li>
 *   <li><b>The button and its command are one thing.</b> Running the command must do exactly what
 *       pressing the button does, because a keybinding will run the command — and "the button works but
 *       the shortcut doesn't" is what a second code path buys you.</li>
 *   <li><b>Exactly one rail carries a given button.</b> Membership is derived from placement rather than
 *       stored, so the two rails cannot disagree — and a move must therefore be seen by both.</li>
 * </ol>
 */
public class StripeViewTest extends UiTestBase {

    private static final String TOOL_TYPE = "console";
    private static final String DOC_TYPE = "sometext";

    private CommandRegistry commands;
    private UIWindow window;
    private Workbench workbench;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:README.md", "hi");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server =
                new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE), PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        workbench = new Workbench(client());
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 6; i++) window.updateWithoutPainting();
    }

    private void register() {
        commands = new CommandRegistry();
        workbench.registerPanel(
                DockPanelDescriptor.singleton(TOOL_TYPE, "Console")
                        .icon("crystalgui:code").anchor(DockDropZone.SPLIT_DOWN),
                ref -> new UIElement());
        workbench.registerPanel(DockPanelDescriptor.document(DOC_TYPE, "Doc"), ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
    }

    /** Rule 1 — and rule 3's registration half, which is what a keybinding would later bind to. */
    @Test
    public void onlySingletonPanelsGetAButtonAndACommand() {
        register();
        assertTrue("a singleton panel got no command",
                commands.contains(StripeView.commandIdFor(TOOL_TYPE)));
        assertFalse("a DOCUMENT type was given an activity bar entry",
                commands.contains(StripeView.commandIdFor(DOC_TYPE)));

        // The workbench's own two are singletons and must be there without anyone registering them here.
        assertTrue(commands.contains(StripeView.commandIdFor(Workbench.PROJECT_TYPE)));
        assertTrue(commands.contains(StripeView.commandIdFor(Workbench.PROBLEMS_TYPE)));
    }

    /**
     * Rule 2 — and rule 3's other half: the command IS the button's behaviour, not a parallel path.
     *
     * <h3>Asserted on the WIDGETS, not on the layout model</h3>
     *
     * <p>This test originally asked {@code isPanelOpen}, which reads the layout and nothing else. It
     * passed against a build where the rail could close a panel and not reopen it — because closing and
     * opening both updated the model correctly, and only <em>opening</em> needed a structural rebuild that
     * was not requested. The model toggled; the screen did not.</p>
     *
     * <p>So the assertion is that a {@link DockGroup} exists for the panel's leaf: that is the widget the
     * rebuild is responsible for producing, and it is absent in exactly the broken case.</p>
     */
    @Test
    public void runningTheCommandTogglesThePanelOnScreen() {
        register();
        String command = StripeView.commandIdFor(TOOL_TYPE);
        assertFalse("the console started open", workbench.isPanelOpen(TOOL_TYPE));

        commands.run(command);
        settle();
        assertTrue("running the command did not open the panel", workbench.isPanelOpen(TOOL_TYPE));
        assertEquals("the panel opened in the model but its region does not show it",
                TOOL_TYPE, workbench.regions()
                        .host(workbench.toolWindowManager().regionOf(TOOL_TYPE)).showing());

        commands.run(command);
        settle();
        assertFalse("running it again did not hide the panel", workbench.isPanelOpen(TOOL_TYPE));
        assertNull("the panel closed in the model but its region still shows it",
                workbench.regions()
                        .host(workbench.toolWindowManager().regionOf(TOOL_TYPE)).showing());

        // And back again, which is the reported bug: it closed and then would not reopen.
        commands.run(command);
        settle();
        assertEquals("the panel could not be reopened after being closed", TOOL_TYPE,
                workbench.regions()
                        .host(workbench.toolWindowManager().regionOf(TOOL_TYPE)).showing());
    }

    /** The built group showing a panel type, or null when nothing on screen holds it. */
    private DockGroup groupFor(String typeId) {
        var leaf = workbench.dock().layout().leafContaining(new DockPanelRef(typeId));
        return leaf == null ? null : workbench.dock().groupFor(leaf);
    }

    /**
     * <b>Hiding a tool window and showing it again gives back the size it had.</b>
     *
     * <p>{@code dropOnOuterEdge} assigns {@code size(1f)} itself, so a reopened panel claims a full weight
     * against siblings summing to one — which is what made a reopened Project take half the window
     * regardless of how narrow it had been. Restoring is not a nicety: the share is a setting the user
     * spent a drag on, and losing it on every hide makes the button not worth pressing.</p>
     */
    @Test
    public void aReopenedPanelKeepsTheSizeItHad() {
        register();
        var region = workbench.toolWindowManager().regionOf(Workbench.PROJECT_TYPE);
        workbench.regions().setWeight(region, 0.13f);

        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();

        assertEquals("the panel did not reopen at all",
                Workbench.PROJECT_TYPE, workbench.regions().host(region).showing());
        assertEquals("a reopened panel lost the share its region was hidden at",
                0.13f, workbench.regions().weightOf(region), 1e-4f);
    }

    /**
     * <b>The 2x2 — which rail and which end of it, derived from placement alone.</b>
     *
     * <p>Pure function, so it is pinned directly rather than through four widget arrangements. The row
     * worth reading twice is the last pair: {@code PANEL} is the only region whose <em>side</em> changes
     * which rail its button is in, because the New UI has no bottom stripe and the bottom region's two
     * halves borrow the two rails' bottom groups.</p>
     */
    @Test
    public void theRailAndGroupAreDerivedFromRegionAndSide() {
        assertEquals(StripeRail.LEFT, StripeRail.of(DockRegion.SIDEBAR, RegionSide.PRIMARY));
        assertEquals("a split sidebar moved its button to the other rail",
                StripeRail.LEFT, StripeRail.of(DockRegion.SIDEBAR, RegionSide.SECONDARY));
        assertEquals(StripeRail.RIGHT, StripeRail.of(DockRegion.AUXILIARY, RegionSide.PRIMARY));
        assertEquals(StripeRail.LEFT, StripeRail.of(DockRegion.PANEL, RegionSide.PRIMARY));
        assertEquals("the panel's right half did not borrow the right rail",
                StripeRail.RIGHT, StripeRail.of(DockRegion.PANEL, RegionSide.SECONDARY));

        // The rail's two groups, said as the pair StripeView actually lays out.
        assertEquals(DockRegion.SIDEBAR, StripeRail.LEFT.topRegion());
        assertEquals(DockRegion.AUXILIARY, StripeRail.RIGHT.topRegion());
        assertEquals(RegionSide.PRIMARY, StripeRail.LEFT.bottomSide());
        assertEquals("the bottom strip's right half does not belong to the right rail",
                RegionSide.SECONDARY, StripeRail.RIGHT.bottomSide());
    }

    /**
     * <b>A tool window moved to another rail opens there, and exactly one rail carries its button.</b>
     *
     * <p>Two failures in one assertion, and they look identical on screen until you count. A sync that only
     * ever <em>added</em> leaves the button on both rails — two buttons for one container, each lighting up
     * when it opens. A sync that only ever removed leaves it on neither.</p>
     *
     * <p>The reopen half is the older rule this replaced: a descriptor's region answers "where does this
     * open the <em>first</em> time", and reopening there afterwards silently undoes a deliberate move —
     * which is how it was reported, an Inspector moved to the bottom coming back on the right.</p>
     */
    @Test
    public void movingAToolWindowMovesItsButtonToTheOtherRail() {
        register();
        assertEquals("Project did not start in the sidebar",
                DockRegion.SIDEBAR, workbench.toolWindowManager().regionOf(Workbench.PROJECT_TYPE));
        assertTrue(workbench.stripe(StripeRail.LEFT).holds(Workbench.PROJECT_TYPE));
        assertFalse(workbench.stripe(StripeRail.RIGHT).holds(Workbench.PROJECT_TYPE));

        workbench.toolWindowManager()
                .moveTo(Workbench.PROJECT_TYPE, DockRegion.AUXILIARY, RegionSide.PRIMARY);
        settle();

        assertEquals(DockRegion.AUXILIARY, workbench.toolWindowManager().regionOf(Workbench.PROJECT_TYPE));
        assertTrue("a moved tool window did not reopen in the region it moved to",
                workbench.isPanelOpen(Workbench.PROJECT_TYPE));
        assertEquals(Workbench.PROJECT_TYPE,
                workbench.regions().host(DockRegion.AUXILIARY).showing());
        assertNull("the region it left is still showing it",
                workbench.regions().host(DockRegion.SIDEBAR).showing());

        assertTrue("the gaining rail never got the button",
                workbench.stripe(StripeRail.RIGHT).holds(Workbench.PROJECT_TYPE));
        assertFalse("the losing rail kept its button, so the container has two",
                workbench.stripe(StripeRail.LEFT).holds(Workbench.PROJECT_TYPE));
    }

    /**
     * <b>A move is announced even when the tool window is closed.</b>
     *
     * <p>A closed tool window still has a button, and moving that button is the ordinary way to say where
     * it should open next time. Announcing only on the visible case leaves both rails showing the placement
     * they had before the drag — and the mistake survives a restart, because the model was right all
     * along.</p>
     */
    @Test
    public void aClosedToolWindowStillMovesItsButton() {
        register();
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();
        assertFalse("Project did not close", workbench.isPanelOpen(Workbench.PROJECT_TYPE));

        workbench.toolWindowManager()
                .moveTo(Workbench.PROJECT_TYPE, DockRegion.AUXILIARY, RegionSide.PRIMARY);
        settle();

        assertFalse("moving a closed tool window opened it", workbench.isPanelOpen(Workbench.PROJECT_TYPE));
        assertTrue(workbench.stripe(StripeRail.RIGHT).holds(Workbench.PROJECT_TYPE));
        assertFalse(workbench.stripe(StripeRail.LEFT).holds(Workbench.PROJECT_TYPE));
    }

    /**
     * <b>A real drag, from a rail button to the right-hand band, moves the tool window.</b>
     *
     * <p>End-to-end through the input handler rather than by calling {@code moveTo}, because everything
     * that has gone wrong here has gone wrong <em>between</em> the pieces rather than inside them. The
     * one that cost the most: the overlay's listeners were attached for the target phase only, and
     * {@code attachListener}'s two booleans are <b>additive</b> — {@code (false, false)} means "target,
     * no bubble". {@code DragEvent.Over} is dispatched to whatever is geometrically under the pointer, so
     * the workbench's content box was never it and heard nothing at all: no highlight, no label, and a
     * drop that could not be accepted because {@code preventDefault} was never reached. Every unit
     * involved was correct.</p>
     */
    @Test
    public void draggingAButtonIntoTheRightBandMovesTheToolWindow() {
        register();
        // SETTLE FIRST. register() adds a button, and a button's rect is read from the LAST layout pass --
        // so measuring straight after it aims at where the rail was before the newcomer pushed everything
        // down. The press then lands on a neighbour, and the test reads as "the drag moved nothing" when
        // it moved the wrong tool window perfectly.
        settle();
        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(Workbench.PROBLEMS_TYPE);
        assertNotNull("no Problems button to drag", button);
        assertEquals(DockRegion.PANEL,
                workbench.toolWindowManager().regionOf(Workbench.PROBLEMS_TYPE));

        var cache = button.getRuntimeCache();
        var centre = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int fromX = Math.round(centre.x());
        int fromY = Math.round(centre.y());

        move(fromX, fromY);
        press(fromX, fromY);
        // Well past the activation threshold, and into the right-hand band -- with the auxiliary region
        // closed the band is a fraction of the width, so a few pixels off the edge is inside it either way.
        int toX = 1200 - 6;
        int toY = 400;
        for (int i = 0; i < 3; i++) move(toX, toY);
        // Asserted BEFORE the release, so a failure says which half of the chain broke: the overlay never
        // hearing the drag reads identically to the drop never firing if you only check the outcome.
        assertNotNull("the overlay resolved no slot -- it is not hearing the drag at all",
                workbench.dropOverlay().currentTarget());
        assertEquals(DockRegion.AUXILIARY, workbench.dropOverlay().currentTarget().region());
        assertTrue("the overlay resolved a slot but never accepted the drop",
                window.getInputHandler().getDragController().isDropAccepted());
        release(toX, toY);
        settle();

        assertEquals("the Drop event never reached the overlay", 1, workbench.dropOverlay().dropsSeen());

        // THE HIGHLIGHT GOES WHEN THE DROP DOES. It stayed lit over the region the tool window had just
        // been moved into -- opacity alone is not enough, because a faded box still has a rect and
        // anything painting outside the opacity layer still has somewhere to paint.
        assertNull("the overlay still claims a destination after the drop",
                workbench.dropOverlay().currentTarget());
        UIElement preview = workbench.dropOverlay()
                .querySelector("." + RegionDropOverlay.PREVIEW_CLASS);
        assertNotNull(preview);
        assertEquals("the drop preview still covers the region it moved the panel into",
                0f, preview.getRuntimeCache().getWidth(), 1e-4f);
        assertEquals("the drag did not reach the auxiliary band",
                DockRegion.AUXILIARY, workbench.toolWindowManager().regionOf(Workbench.PROBLEMS_TYPE));
        assertTrue("the right rail never got the button",
                workbench.stripe(StripeRail.RIGHT).holds(Workbench.PROBLEMS_TYPE));
    }

    /**
     * <b>The sidebar's own tool window drags to the right band too.</b>
     *
     * <p>Reported as "not even on the right stripe" after a drag from the left rail appeared to do nothing.
     * Half of that report is not a bug: the drag in the screenshot was over the <em>sidebar</em>, and
     * Project is already Left Top, so {@code moveTo} correctly does nothing. This pins the other half —
     * that a SIDEBAR-anchored window really can cross to the auxiliary band, which is a different path from
     * the PANEL-anchored case above because it starts in the rail's top group rather than its bottom.</p>
     */
    @Test
    public void aSidebarToolWindowDragsToTheRightBand() {
        register();
        settle();
        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(Workbench.PROJECT_TYPE);
        assertNotNull("no Project button to drag", button);

        var cache = button.getRuntimeCache();
        var centre = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        move(Math.round(centre.x()), Math.round(centre.y()));
        press(Math.round(centre.x()), Math.round(centre.y()));
        for (int i = 0; i < 3; i++) move(1200 - 6, 400);
        release(1200 - 6, 400);
        settle();

        assertEquals("a sidebar tool window could not be dragged to the auxiliary band",
                DockRegion.AUXILIARY, workbench.toolWindowManager().regionOf(Workbench.PROJECT_TYPE));
    }

    /**
     * <b>Dropping a tool window where it already is changes nothing, and must not break it.</b>
     *
     * <p>{@code moveTo} early-returns on an unchanged placement. That is what "Move to Left Top did not
     * move it" was: Project <em>is</em> Left Top. The label is still offered, which is IntelliJ's behaviour
     * too — what must not happen is the no-op leaving the tool window hidden, which is the shape a
     * hide-then-reshow would take if the guard were removed.</p>
     */
    @Test
    public void droppingAToolWindowWhereItAlreadyIsLeavesItOpen() {
        register();
        settle();
        assertTrue(workbench.isPanelOpen(Workbench.PROJECT_TYPE));

        workbench.toolWindowManager()
                .moveTo(Workbench.PROJECT_TYPE, DockRegion.SIDEBAR, RegionSide.PRIMARY);
        settle();

        assertEquals(DockRegion.SIDEBAR, workbench.toolWindowManager().regionOf(Workbench.PROJECT_TYPE));
        assertTrue("a no-op move closed the tool window", workbench.isPanelOpen(Workbench.PROJECT_TYPE));
        assertEquals(Workbench.PROJECT_TYPE, workbench.regions().host(DockRegion.SIDEBAR).showing());
    }

    /**
     * <b>A drop honours the index the insertion marker promised, and renumbers the group.</b>
     *
     * <p>Orders start as registration order — dense, but arbitrary — so inserting "between 3 and 4" has no
     * integer to use and the whole group is renumbered. Both references do the same.</p>
     *
     * <p>Honouring the region and dropping the index would be the worst outcome available: right about the
     * region every time, so it reads as working, and wrong about the position only when you were watching
     * the marker.</p>
     */
    @Test
    public void aDropLandsAtTheIndexTheMarkerPromised() {
        register();
        settle();
        var toolWindows = workbench.toolWindowManager();
        // Console and Problems are both PANEL/PRIMARY, so they share one stripe group.
        toolWindows.moveTo(TOOL_TYPE, DockRegion.PANEL, RegionSide.PRIMARY, 1);
        settle();
        assertEquals(List.of(Workbench.PROBLEMS_TYPE, TOOL_TYPE),
                toolWindows.groupOf(DockRegion.PANEL, RegionSide.PRIMARY));

        // Now to the front.
        toolWindows.moveTo(TOOL_TYPE, DockRegion.PANEL, RegionSide.PRIMARY, 0);
        settle();
        assertEquals("the drop ignored the index it was given",
                List.of(TOOL_TYPE, Workbench.PROBLEMS_TYPE),
                toolWindows.groupOf(DockRegion.PANEL, RegionSide.PRIMARY));

        // PAST THE END is an append, not a refusal -- the far end of a list is the index most easily lost.
        toolWindows.moveTo(TOOL_TYPE, DockRegion.PANEL, RegionSide.PRIMARY, 99);
        settle();
        assertEquals(List.of(Workbench.PROBLEMS_TYPE, TOOL_TYPE),
                toolWindows.groupOf(DockRegion.PANEL, RegionSide.PRIMARY));
    }

    /**
     * <b>A drag that ends where it started leaves the order alone.</b>
     *
     * <p>The bug this pins had nothing to do with dragging <em>far</em>. Hiding the button collapses its
     * group by one cell, so a pointer that has barely moved is suddenly inside its neighbour's cell and the
     * midpoint rule answers with the neighbour's index — a press and release shuffled the button one place
     * down, and a deliberate one-place drag appeared to do nothing because the two cancelled.</p>
     *
     * <p>The fix is that the gap opens in the cell the button vacated, so the group keeps its length and
     * the geometry at rest matches the pre-drag layout. Asserted on the <b>order</b> rather than on any
     * marker, because the order is what survives the drag.</p>
     */
    @Test
    public void aDragThatGoesNowhereChangesNothing() {
        register();
        // The bare workbench ships ONE sidebar tool window, and one button cannot be reordered.
        workbench.registerPanel(DockPanelDescriptor.singleton("outline", "Outline")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        workbench.registerPanel(DockPanelDescriptor.singleton("marks", "Bookmarks")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        settle();

        var toolWindows = workbench.toolWindowManager();
        List<String> before = toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY);
        assertTrue("setup: the sidebar group needs several buttons to reorder", before.size() >= 3);

        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(before.get(1));
        assertNotNull(button);
        var cache = button.getRuntimeCache();
        var centre = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x());
        int y = Math.round(centre.y());

        move(x, y);
        press(x, y);
        // Past the activation threshold and no further -- still inside the button's own cell.
        for (int i = 0; i < 3; i++) move(x + 6, y + 2);
        release(x + 6, y + 2);
        settle();

        assertEquals("a drag that went nowhere reordered the stripe",
                before, toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY));
    }

    /**
     * <b>A reorder inside one half lands, even though the stripe shows both halves.</b>
     *
     * <p>The bug this pins: a rail's top group holds a region's PRIMARY and SECONDARY buttons in one
     * stripe — IntelliJ keeps them together with a separator — so an index computed over the whole group
     * was being applied to {@code groupOf(region, side)}, which is one half of it. The number was right
     * about a list that was not the list being renumbered, and the button came back where it started.</p>
     */
    @Test
    public void aReorderInsideOneHalfLandsThoughTheStripeShowsBoth() {
        register();
        workbench.registerPanel(DockPanelDescriptor.singleton("outline", "Outline")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        workbench.registerPanel(DockPanelDescriptor.singleton("marks", "Bookmarks")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        // A SECOND HALF in the same stripe, which is what made the two lists disagree.
        workbench.registerPanel(DockPanelDescriptor.singleton("commit", "Commit")
                .icon("crystalgui:image").region(DockRegion.SIDEBAR).side(RegionSide.SECONDARY),
                ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        settle();

        var toolWindows = workbench.toolWindowManager();
        List<String> before = toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY);
        assertEquals("setup", 3, before.size());

        UIElement from = workbench.stripe(StripeRail.LEFT).buttonFor(before.get(2));
        UIElement to = workbench.stripe(StripeRail.LEFT).buttonFor(before.get(0));
        assertNotNull(from);
        assertNotNull(to);
        int[] start = centreOf(from);
        // THE UPPER QUARTER of the target, not its centre. The rule is "the first item whose midpoint is
        // past the pointer", so a pointer exactly ON a midpoint resolves to the NEXT index -- landing the
        // button after the one it was aimed at. That is correct behaviour and a trap for a test.
        int[] end = pointIn(to, 0.25f);

        move(start[0], start[1]);
        press(start[0], start[1]);
        for (int i = 0; i < 3; i++) move(end[0], end[1]);
        release(end[0], end[1]);
        settle();

        assertEquals("the last button did not move to the head of its half",
                List.of(before.get(2), before.get(0), before.get(1)),
                toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY));
    }

    private static int[] centreOf(UIElement element) {
        return pointIn(element, 0.5f);
    }

    /** A screen point at {@code fraction} down the element, centred horizontally. */
    private static int[] pointIn(UIElement element, float fraction) {
        var cache = element.getRuntimeCache();
        var point = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * fraction);
        return new int[]{Math.round(point.x()), Math.round(point.y())};
    }

    /**
     * <b>A button can be dropped below one that has never been moved.</b>
     *
     * <p>The renumber read {@code toolWindows.get(...)} and skipped anything that came back null — and a
     * tool window has no {@code ToolWindowState} until something asks where it is. So an untouched button
     * kept {@code Integer.MAX_VALUE} while its neighbours took {@code 0..n-1}, and {@code MAX_VALUE} sorts
     * last <em>permanently</em>: nothing could be placed after it until it had itself been moved once and
     * earned a real order. Reported exactly that precisely, which is what made it findable.</p>
     */
    @Test
    public void aButtonCanBeMovedBelowOneThatHasNeverMoved() {
        register();
        workbench.registerPanel(DockPanelDescriptor.singleton("outline", "Outline")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        workbench.registerPanel(DockPanelDescriptor.singleton("marks", "Bookmarks")
                .icon("crystalgui:folder").region(DockRegion.SIDEBAR), ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        settle();

        var toolWindows = workbench.toolWindowManager();
        List<String> before = toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY);
        assertEquals("setup", 3, before.size());
        // The LAST member has never been moved, so before the fix it had no order at all.
        String last = before.get(2);
        assertEquals("setup: the last member should be untouched",
                Integer.MAX_VALUE, toolWindows.orderOf(last));

        // Send the FIRST one past it, which is the move that was impossible.
        toolWindows.moveTo(before.get(0), DockRegion.SIDEBAR, RegionSide.PRIMARY, 2);
        settle();

        assertEquals("a button could not be placed after one that had never been moved",
                List.of(before.get(1), last, before.get(0)),
                toolWindows.groupOf(DockRegion.SIDEBAR, RegionSide.PRIMARY));
        assertTrue("the untouched member still has no real order",
                toolWindows.orderOf(last) < Integer.MAX_VALUE);
    }

    /**
     * <b>The rule between an anchor's two halves appears exactly when both halves have something.</b>
     *
     * <p>The halves are separate reorder units that share one stripe, and without a visible boundary the
     * rail reads as one list that refuses to be rearranged — which is how it was reported: the fourth
     * button could not be dragged below the fifth, because the fifth was in the other half, and it started
     * working the moment the fifth was dragged across into the same one.</p>
     */
    @Test
    public void theHalvesAreSeparatedOnlyWhenBothArePopulated() {
        register();
        settle();
        StripeView left = workbench.stripe(StripeRail.LEFT);
        assertNull("a rule was drawn with only one half populated",
                left.querySelector("." + StripeView.SEPARATOR_CLASS));

        workbench.registerPanel(DockPanelDescriptor.singleton("commit", "Commit")
                .icon("crystalgui:image").region(DockRegion.SIDEBAR).side(RegionSide.SECONDARY),
                ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        settle();
        assertNotNull("the two halves of the sidebar are not separated",
                left.querySelector("." + StripeView.SEPARATOR_CLASS));

        // Moved into the first half, so the second is empty again and the rule goes with it.
        workbench.toolWindowManager().moveTo("commit", DockRegion.SIDEBAR, RegionSide.PRIMARY);
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        settle();
        assertNull("the rule outlived the half it was separating",
                left.querySelector("." + StripeView.SEPARATOR_CLASS));
    }

    /**
     * <b>A drop over the editor moves nothing.</b>
     *
     * <p>The centre is a real answer and it is "no" — nothing there calls {@code preventDefault}, so the
     * drop is refused. A resolver that fell back to the nearest band instead would make every release
     * land somewhere, which is how a drag ends up putting a panel where nobody asked.</p>
     */
    @Test
    public void droppingOverTheEditorLeavesTheToolWindowWhereItWas() {
        register();
        settle();
        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(Workbench.PROBLEMS_TYPE);
        assertNotNull(button);
        var cache = button.getRuntimeCache();
        var centre = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int fromX = Math.round(centre.x());
        int fromY = Math.round(centre.y());

        move(fromX, fromY);
        press(fromX, fromY);
        for (int i = 0; i < 3; i++) move(600, 400);
        release(600, 400);
        settle();

        assertEquals("a drop over the editor area moved the tool window anyway",
                DockRegion.PANEL, workbench.toolWindowManager().regionOf(Workbench.PROBLEMS_TYPE));
    }

    private void move(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.updateWithoutPainting();
    }

    private void press(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.updateWithoutPainting();
    }

    private void release(int x, int y) {
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.updateWithoutPainting();
    }

    /** A panel type registered after the bar was first synced still gets its button. */
    @Test
    public void aLateRegisteredPanelStillGetsAButton() {
        register();
        String late = "late";
        assertFalse(commands.contains(StripeView.commandIdFor(late)));

        workbench.registerPanel(DockPanelDescriptor.singleton(late, "Late")
                .icon("crystalgui:package"), ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);

        assertTrue("a panel registered after the first sync never got a button",
                commands.contains(StripeView.commandIdFor(late)));
    }

    /** Closing the last tool window must leave the rail — it is the only way back. */
    @Test
    public void theBarSurvivesEveryPanelBeingClosed() {
        register();
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        workbench.togglePanel(Workbench.PROBLEMS_TYPE);
        assertFalse(workbench.isPanelOpen(Workbench.PROJECT_TYPE));
        assertFalse(workbench.isPanelOpen(Workbench.PROBLEMS_TYPE));

        for (StripeView stripe : workbench.stripes()) {
            assertNotNull("a rail went away with the panels", stripe);
            assertNotNull("a rail left the workbench tree", stripe.getParent());
        }

        // And it can put one back, which is the whole point of it outliving them.
        commands.run(StripeView.commandIdFor(Workbench.PROJECT_TYPE));
        assertTrue("the rail could not reopen a closed panel",
                workbench.isPanelOpen(Workbench.PROJECT_TYPE));
    }

    /** A descriptor with no icon is legal; the button simply has no glyph. */
    @Test
    public void aPanelWithoutAnIconStillGetsAButton() {
        commands = new CommandRegistry();
        workbench.registerPanel(DockPanelDescriptor.singleton("plain", "Plain"), ref -> new UIElement());
        for (StripeView stripe : workbench.stripes()) stripe.sync(commands);
        assertTrue(commands.contains(StripeView.commandIdFor("plain")));
        assertNull("a descriptor with no icon invented one",
                workbench.panels().descriptor("plain").icon());
    }

    /** Tiny helper so the assertion reads as what it means rather than as a tree walk. */
    private static final class DockLeafAssert {
        static void assertNotCentral(Workbench workbench, String typeId) {
            var leaf = workbench.dock().layout().leafContaining(new DockPanelRef(typeId));
            assertNotNull("the panel is not open at all", leaf);
            assertFalse("an anchored tool window opened into the central work area", leaf.isCentral());
        }
    }
}
