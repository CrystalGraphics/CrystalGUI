package com.crystalgui.workbench.toolwindow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

/**
 * <b>Everything a {@link ToolWindowKind} declares reaches the descriptor the dock registers.</b>
 *
 * <p>{@code DockPanelDescriptor} is half immutable builder and half mutable one: {@code icon} and
 * {@code anchor} answer a <em>new</em> descriptor while {@code region} and {@code side} mutate and
 * return {@code this}. Registration wrote all four as bare statements, so the two that copy were
 * discarded - every panel declared through a kind registered with no icon and no anchor while its
 * placement worked perfectly.</p>
 *
 * <p>On screen that is an activity bar of blank buttons, which is how it was reported. Nothing else
 * could see it: the panels opened, in the right rails, with the right titles and working toggles.</p>
 */
public class ToolWindowKindReachesTheDescriptorTest {

    private static final String PROJECT = "scratch";
    private static final String TYPE = "test:panel";

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private Workbench workbench;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        Workspace workspace = Workspace.of(clientEnd);
        // NO EXTENSIONS: this is about what registration carries, not about what ships.
        workbench = new Workbench(workspace, List.of());
    }

    @After
    public void closeWorkbench() {
        if (workbench != null) workbench.dispose();
        if (clientEnd != null) clientEnd.close("test over");
        if (serverEnd != null) serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    @Test
    public void aDeclaredIconAndAnchorSurviveRegistration() {
        Disposable handle = workbench.registerToolWindow(
                ToolWindowKind.of(TYPE, "Test Panel")
                        .icon("crystalgui:toolwindows/problems")
                        .anchor(DockDropZone.SPLIT_DOWN)
                        .region(DockRegion.AUXILIARY)
                        .side(RegionSide.SECONDARY)
                        .view(ctx -> new UIElement()));
        try {
            DockPanelDescriptor registered = workbench.panels().descriptor(TYPE);
            assertNotNull("the panel was not registered at all", registered);

            assertEquals("the icon never reached the descriptor, so the rail button draws nothing",
                    "crystalgui:toolwindows/problems", registered.icon());
            assertEquals("the anchor never reached the descriptor", DockDropZone.SPLIT_DOWN,
                    registered.anchor());

            // THE CONTROL. These two mutate and return `this`, so they were carried even by the broken
            // version - which is exactly what made the failure look like an icon problem rather than a
            // registration one, and why asserting placement alone would pass against no fix.
            assertEquals("the region moved", DockRegion.AUXILIARY, registered.region());
            assertEquals("the side moved", RegionSide.SECONDARY, registered.side());
        } finally {
            handle.dispose();
        }
    }
}
