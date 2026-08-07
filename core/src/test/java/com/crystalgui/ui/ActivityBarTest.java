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
import com.crystalgui.ui.elements.workbench.ActivityBar;
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
 * The tool-window rail — IntelliJ's stripe, VS Code's Activity Bar.
 *
 * <p>What is worth pinning is not that buttons appear. It is the three rules both originals share and
 * that are each individually easy to lose:</p>
 *
 * <ol>
 *   <li><b>Documents never appear on it.</b> The bar lists tool windows; a rail that listed open files
 *       would grow without bound and duplicate the tab strip.</li>
 *   <li><b>Click toggles.</b> Clicking the visible panel's button hides it, in both editors. Open-only
 *       gives you a bar that can fill the screen and not clear it.</li>
 *   <li><b>The button and its command are one thing.</b> Running the command must do exactly what
 *       pressing the button does, because a keybinding will run the command — and "the button works but
 *       the shortcut doesn't" is what a second code path buys you.</li>
 * </ol>
 */
public class ActivityBarTest extends UiTestBase {

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
        workbench.activityBar().sync(commands);
    }

    /** Rule 1 — and rule 3's registration half, which is what a keybinding would later bind to. */
    @Test
    public void onlySingletonPanelsGetAButtonAndACommand() {
        register();
        assertTrue("a singleton panel got no command",
                commands.contains(ActivityBar.commandIdFor(TOOL_TYPE)));
        assertFalse("a DOCUMENT type was given an activity bar entry",
                commands.contains(ActivityBar.commandIdFor(DOC_TYPE)));

        // The workbench's own two are singletons and must be there without anyone registering them here.
        assertTrue(commands.contains(ActivityBar.commandIdFor(Workbench.PROJECT_TYPE)));
        assertTrue(commands.contains(ActivityBar.commandIdFor(Workbench.PROBLEMS_TYPE)));
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
        String command = ActivityBar.commandIdFor(TOOL_TYPE);
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
     * <b>A panel dragged to a different edge reopens there, not where its type was registered.</b>
     *
     * <p>The descriptor's anchor answers "where does this open the <em>first</em> time". After that the
     * user may have moved it, and reopening at the registered edge silently undoes a deliberate change —
     * which is how this was reported: an Inspector moved to the bottom came back on the right, where it
     * had been several opens earlier. IntelliJ treats the anchor as mutable for the same reason: dragging
     * a tool window to another stripe changes it.</p>
     */
    @Test
    public void aPanelReopensWhereItWasMovedToRatherThanWhereItWasRegistered() {
        register();
        // Registered against the LEFT wall, then moved to the bottom -- the reported sequence.
        assertEquals(DockDropZone.SPLIT_LEFT,
                workbench.panels().descriptor(Workbench.PROJECT_TYPE).anchor());
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();
        // Moved to the BOTTOM WALL, which is what a drag to the window edge produces. Deliberately not
        // openPanelBeside: that nests the panel next to a leaf rather than against the root, and a nested
        // panel is genuinely not "on an edge" -- see the limitation noted on Workbench.outerEdgeOf.
        workbench.dock().layout().dropOnOuterEdge(DockDropZone.SPLIT_DOWN,
                new DockLeaf(new DockPanelRef(Workbench.PROJECT_TYPE)));
        workbench.dock().requestRebuild();
        settle();

        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        settle();

        var reopened = workbench.dock().layout().leafContaining(new DockPanelRef(Workbench.PROJECT_TYPE));
        assertNotNull("the panel did not reopen", reopened);
        assertEquals("a panel moved to the bottom came back at its registered edge",
                DockDropZone.SPLIT_DOWN, edgeOf(reopened));
    }

    /** Which outer edge a leaf sits against — the same rule {@code dropOnOuterEdge} inverts. */
    private DockDropZone edgeOf(DockLeaf leaf) {
        com.crystalgui.ui.elements.dock.DockNode node = leaf;
        var root = workbench.dock().layout().root();
        while (node.parent() != null && node.parent() != root) node = node.parent();
        int index = root.children().indexOf(node);
        boolean after = index == root.childCount() - 1;
        boolean horizontal = root.orientation(workbench.dock().layout().rootOrientation())
                == com.crystalgui.ui.elements.dock.DockOrientation.HORIZONTAL;
        if (horizontal) return after ? DockDropZone.SPLIT_RIGHT : DockDropZone.SPLIT_LEFT;
        return after ? DockDropZone.SPLIT_DOWN : DockDropZone.SPLIT_UP;
    }

    /** A panel type registered after the bar was first synced still gets its button. */
    @Test
    public void aLateRegisteredPanelStillGetsAButton() {
        register();
        String late = "late";
        assertFalse(commands.contains(ActivityBar.commandIdFor(late)));

        workbench.registerPanel(DockPanelDescriptor.singleton(late, "Late")
                .icon("crystalgui:package"), ref -> new UIElement());
        workbench.activityBar().sync(commands);

        assertTrue("a panel registered after the first sync never got a button",
                commands.contains(ActivityBar.commandIdFor(late)));
    }

    /** Closing the last tool window must leave the rail — it is the only way back. */
    @Test
    public void theBarSurvivesEveryPanelBeingClosed() {
        register();
        workbench.togglePanel(Workbench.PROJECT_TYPE);
        workbench.togglePanel(Workbench.PROBLEMS_TYPE);
        assertFalse(workbench.isPanelOpen(Workbench.PROJECT_TYPE));
        assertFalse(workbench.isPanelOpen(Workbench.PROBLEMS_TYPE));

        assertNotNull("the activity bar went away with the panels", workbench.activityBar());
        assertEquals("the rail left the workbench tree",
                workbench.activityBar().getParent() != null, true);

        // And it can put one back, which is the whole point of it outliving them.
        commands.run(ActivityBar.commandIdFor(Workbench.PROJECT_TYPE));
        assertTrue("the rail could not reopen a closed panel",
                workbench.isPanelOpen(Workbench.PROJECT_TYPE));
    }

    /** A descriptor with no icon is legal; the button simply has no glyph. */
    @Test
    public void aPanelWithoutAnIconStillGetsAButton() {
        commands = new CommandRegistry();
        workbench.registerPanel(DockPanelDescriptor.singleton("plain", "Plain"), ref -> new UIElement());
        workbench.activityBar().sync(commands);
        assertTrue(commands.contains(ActivityBar.commandIdFor("plain")));
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
