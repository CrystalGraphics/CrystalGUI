package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.joml.Vector2f;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.crystaleditor.CrystalEditor;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.panel.DesignToolWindow;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.document.DocumentEditor;
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
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.dock.panel.DockInput;

/**
 * <b>The Design panel shows the hierarchy of the {@code .cgui} in front.</b>
 *
 * <p>Written against the real path rather than by constructing a {@code HierarchyPanel} — which is why
 * three attempts at this missed, each fixing something true and none of it the cause. What a panel can
 * build was never the question; what it is told, and whether what it is told is current, was.</p>
 */
public class DesignPanelFollowsTheOpenDocumentTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath FILE = CgPath.parse(PROJECT + ":page.cgui");
    private static final CgPath OTHER = CgPath.parse(PROJECT + ":notes.md");

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

        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed(FILE.toString(), SOURCE)
                .seed(OTHER.toString(), "notes\n");
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

        // THE PRODUCT'S OWN LIST, not just the builder's id: the panel came up empty in the running
        // editor while a minimal fixture passed, and the only difference left was what else is on.
        workbench = new Workbench(Workspace.of(clientEnd), CrystalEditor.EXTENSIONS);
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
    }

    @After
    public void closeWorkbench() {
        // THE WORKBENCH TOO, not just the wire. Its extensions register into process-wide registries --
        // the inspector's section set is one -- so a fixture that walks away leaves them registered and
        // the next test in the JVM counts them.
        workbench.dispose();
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

        // AND SOMEWHERE TO DRAW THEM. Rows in the model with a zero-height tree is exactly what "the
        // panel is empty" looked like, and asserting only on visibleRows() could not tell the two apart:
        // the fill idiom was missing, so the tree laid out at nothing inside a panel of the right size.
        Box treeBox = design.hierarchy().tree().box();
        assertNotNull("the tree was never laid out", treeBox);
        assertTrue("the tree has no height, so its rows cannot be seen: " + treeBox.height(),
                treeBox.height() > 1f);
        assertTrue("...nor any width: " + treeBox.width(), treeBox.width() > 1f);
    }

    /**
     * <b>Away to another tab and back.</b>
     *
     * <p>The panel came up once and never again, and the cause was not in the panel at all:
     * {@code EditorService.active} was set in exactly one place — when a document is <b>opened</b> — so
     * clicking a tab, which is a selection rather than an open, never moved it. Everything derived from
     * the dock stayed right and everything asking the editor service kept naming the last file opened.
     * </p>
     */
    @Test
    public void theHierarchyComesBackAfterSwitchingTabs() {
        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 16; i++) frameAndPump();
        assertNotNull("never came up at all", panel().hierarchy());

        workbench.open(DockInput.of(workbench.refFor(OTHER)));
        for (int i = 0; i < 12; i++) frameAndPump();
        assertNull("a text file is not a builder", panel().hierarchy());

        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 12; i++) frameAndPump();
        assertNotNull("came back to the .cgui and the panel stayed empty", panel().hierarchy());
    }

    /**
     * <b>Clicking the canvas keeps working, with the hierarchy live beside it.</b>
     *
     * <p>Reported as "click an element and the handles vanish and it stays broken until I restart" —
     * which only happens with the Design panel up, so the two selections are being kept in step by
     * something the isolated fixtures do not have.</p>
     */
    @Test
    public void clickingTheCanvasRepeatedlyKeepsSelecting() {
        workbench.open(DockInput.of(workbench.refFor(FILE)));
        for (int i = 0; i < 16; i++) frameAndPump();

        BuilderEditor editor = builder();
        UIElement title = editor.document().root().children().get(0);

        clickOn(title);
        assertSame("first click selected nothing", title, editor.selection().node());
        assertSame(title, editor.handles().target());

        // BLANK PAGE, well below the content: #root is exactly as tall as its one text child, so there
        // is no root-only area to aim at -- the honest "click away" is the artboard's own empty space.
        clickBlankPage(editor);
        assertNull("clicking blank canvas should deselect", editor.selection().node());

        clickOn(title);
        assertSame("the second click on the element did nothing -- input is stuck",
                title, editor.selection().node());
        assertSame("the handles came off and never came back", title, editor.handles().target());
    }

    private BuilderEditor builder() {
        DocumentEditor view = workbench.editors().active().editor();
        return (BuilderEditor) view;
    }

    private void clickBlankPage(BuilderEditor editor) {
        Box board = editor.artboard().box();
        Vector2f at = Transform2D.apply(board.localToWorld(),
                board.width() * 0.5f, board.height() * 0.8f);
        press(at);
        frameAndPump();
        release(at);
        frameAndPump();
    }

    private void press(Vector2f at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void release(Vector2f at) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }

    private void clickOn(UIElement element) {
        Box box = element.box();
        assertNotNull("nothing laid out to click", box);
        Vector2f at = Transform2D.apply(box.localToWorld(),
                Math.min(4f, box.width() * 0.5f), box.height() * 0.5f);
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        frameAndPump();
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(at.x()), Math.round(at.y()), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        frameAndPump();
    }

    /** With nothing open it is empty, which is the state it must not be stuck in. */
    @Test
    public void withNoBuilderInFrontItIsEmpty() {
        for (int i = 0; i < 8; i++) frameAndPump();
        assertNotNull(panel());
        assertNull(panel().hierarchy());
    }
}
