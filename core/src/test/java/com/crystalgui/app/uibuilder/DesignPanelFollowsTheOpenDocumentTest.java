package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.panel.DesignToolWindow;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.dock.panel.DockInput;

/**
 * <b>The Design panel shows the hierarchy of the {@code .cgui} in front.</b>
 *
 * <p>Written against the real path rather than by constructing a {@code HierarchyPanel} — which is why
 * two attempts at this missed. A panel is fed by <em>signals</em>, and the question is not whether it can
 * build rows but whether anything ever tells it to: opening a file is asynchronous, so the dock builds
 * its panels and announces its active one while the read is still in flight, and a panel that listens
 * only to the dock hears that one useless announcement and nothing afterwards.</p>
 */
public class DesignPanelFollowsTheOpenDocumentTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath FILE = CgPath.parse(PROJECT + ":page.cgui");

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [ { \"kind\": \"text\", \"id\": \"title\","
            + " \"state\": { \"text\": \"bao\" } } ] }\n"
            + "}\n";

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();
        UIElementRegistry.bootstrap();

        InMemoryFileSystem files = new InMemoryFileSystem().seed(FILE.toString(), SOURCE);
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files,
                (actor, project, path, operation) -> true);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workbench = new Workbench(Workspace.of(clientEnd),
                List.of(UiBuilderContribution.ID));
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    @After
    public void closeWorkbench() {
        Protocols.resetForTesting();
    }

    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    /** The panel the extension registered, found the way the dock holds it. */
    private DesignToolWindow panel() {
        return find(workbench);
    }

    private static DesignToolWindow find(UIElement from) {
        if (from instanceof DesignToolWindow found) return found;
        for (UIElement child : from.children()) {
            DesignToolWindow found = find(child);
            if (found != null) return found;
        }
        return null;
    }

    /** <b>The report.</b> Open a {@code .cgui} and the panel describes it. */
    @Test
    public void openingACguiFillsTheDesignPanel() {
        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 16; i++) frameAndPump();

        DesignToolWindow design = panel();
        assertNotNull("the Design tool window was never built", design);
        assertNotNull("the panel is empty with a .cgui in front", design.hierarchy());
        assertTrue("and it has the document's own nodes in it",
                design.hierarchy().tree().visibleRows().size() >= 2);
    }

    /** With nothing open it is empty, which is the state it must not be stuck in. */
    @Test
    public void withNoBuilderInFrontItIsEmpty() {
        for (int i = 0; i < 8; i++) frameAndPump();
        assertNotNull(panel());
        assertNull(panel().hierarchy());
    }
}
