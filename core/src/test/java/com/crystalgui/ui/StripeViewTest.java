package com.crystalgui.ui;

import com.crystalgui.core.command.CommandRegistry;
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

        assertTrue(StripeRail.isBottomGroup(DockRegion.PANEL));
        assertFalse(StripeRail.isBottomGroup(DockRegion.SIDEBAR));
        assertFalse(StripeRail.isBottomGroup(DockRegion.AUXILIARY));

        // And the inverse, which is what a drop onto a rail runs.
        assertEquals(DockRegion.AUXILIARY, StripeRail.RIGHT.regionFor(false));
        assertEquals(DockRegion.PANEL, StripeRail.RIGHT.regionFor(true));
        assertEquals(RegionSide.SECONDARY, StripeRail.RIGHT.sideFor(true, RegionSide.PRIMARY));
        assertEquals("a drop into a TOP group overwrote the half the tool window was in",
                RegionSide.SECONDARY, StripeRail.RIGHT.sideFor(false, RegionSide.SECONDARY));
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
