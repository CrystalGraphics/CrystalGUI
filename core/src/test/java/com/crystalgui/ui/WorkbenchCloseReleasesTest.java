package com.crystalgui.ui;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.Resource;
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
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>Closing a tab releases what it was showing.</b>
 *
 * <p>It did not, and nothing could make it: the dock knew a panel had gone and had no way to say so, so
 * the document stayed open, its editor stayed reachable, and anything it owned — a preview pool, a
 * renderer — lived until the process did. {@code Disposer} could not help with a fact nobody announced.</p>
 *
 * <p>The services doc listed this as an open gap under "what disposes, and what deliberately does not".
 * These are the assertions that close it.</p>
 */
public class WorkbenchCloseReleasesTest extends UiTestBase {

    private static final String DISPOSABLE_TYPE = "disposabledoc";

    /** A second panel type over the same file, so "one document, two tabs" is reachable. */
    private static final String PREVIEW_TYPE = "docpreview";

    private UIWindow window;
    private Workbench workbench;

    private static InMemoryTransport<Object> serverSide;
    private static InMemoryTransport<Object> clientSide;
    private static ClientUiSession<Object> clientSession;
    private static ServerUiSession<Object> serverSession;

    /** A document that records its own release, so disposal is observable rather than inferred. */
    private static final class Tracked implements FileDocument, Disposable {
        private final Resource resource;
        private final UIElement view = new UIElement();
        boolean disposed;

        Tracked(Resource resource) {
            this.resource = resource;
        }

        @Override public Resource resource() { return resource; }
        @Override public UIElement view() { return view; }
        @Override public byte[] encode() { return new byte[0]; }
        @Override public void adopt(byte[] bytes) { }
        @Override public Connection onDidChange(Runnable listener) { return () -> { }; }
        @Override public void dispose() { disposed = true; }
    }

    private final List<Tracked> built = new ArrayList<>();

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:a.doc", "a")
                .seed("mymod.proj:b.doc", "b");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverSide = pair[0];
        clientSide = pair[1];
        serverSession = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(serverSession::onCall);
        serverSession.open();
        clientSession = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        return new WorkspaceClient<>(clientSession, PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        workbench = new Workbench(client());
        workbench.registerPanel(DockPanelDescriptor.document(DISPOSABLE_TYPE, "Doc"),
                ref -> workbench.documentFor(CgPath.parse(ref.state(DockPanelRef.PATH, ""))).view());
        workbench.registerDocumentType(DISPOSABLE_TYPE, "Doc", path -> {
            Tracked document = new Tracked(Resource.of(path));
            built.add(document);
            return document;
        });
        workbench.registerPanel(DockPanelDescriptor.document(PREVIEW_TYPE, "Preview"),
                ref -> new UIElement());
        workbench.bindEditorExtensions(DISPOSABLE_TYPE, "doc");

        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 8; i++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    private DockPanelRef openDoc(String path) {
        CgPath target = CgPath.parse(path);
        workbench.documentFor(target);
        DockPanelRef ref = workbench.refFor(target);
        workbench.open(DockInput.of(ref));
        settle();
        return ref;
    }

    private Tracked documentAt(String path) {
        for (Tracked candidate : built) {
            if (candidate.resource().toString().equals(path)) return candidate;
        }
        throw new AssertionError("no document built for " + path + " -- fixture wrong");
    }

    @Test
    public void closingTheLastTabReleasesItsDocument() {
        DockPanelRef ref = openDoc("mymod.proj:a.doc");
        Tracked document = documentAt("mymod.proj:a.doc");
        assertFalse("fixture wrong -- released before anything closed", document.disposed);

        workbench.dock().closePanelDiscarding(ref);
        settle();

        assertTrue("closing the last tab did not release the document", document.disposed);
        assertFalse("and it should no longer be an open document",
                workbench.openPaths().contains(CgPath.parse("mymod.proj:a.doc")));
    }

    /** The close is announced, so anything else that cared can act on it too. */
    @Test
    public void closingAnnouncesTheDocument() {
        List<CgPath> closed = new ArrayList<>();
        workbench.onDidCloseDocument.connect(closed::add);

        DockPanelRef ref = openDoc("mymod.proj:a.doc");
        workbench.dock().closePanelDiscarding(ref);
        settle();

        assertEquals(List.of(CgPath.parse("mymod.proj:a.doc")), closed);
    }

    /**
     * <b>A second panel on the same file keeps its document alive.</b>
     *
     * <p>Reachable through two panel <em>types</em> over one path — a file tab and a preview of it — which
     * is one document and two tabs. (The same <b>ref</b> cannot be opened twice: a ref is the panel's
     * identity, so {@code open} treats a repeat as "show me that one".)</p>
     *
     * <p>Releasing on the first close would leave the surviving tab drawing something torn down, which is
     * worse than the leak it replaces because it fails while looking fine.</p>
     */
    @Test
    public void closingOneOfTwoPanelsOnTheSameFileReleasesNothing() {
        DockPanelRef first = openDoc("mymod.proj:a.doc");
        Tracked document = documentAt("mymod.proj:a.doc");

        DockPanelRef second = new DockPanelRef(PREVIEW_TYPE)
                .withState(DockPanelRef.PATH, "mymod.proj:a.doc");
        workbench.open(DockInput.of(second), DockPlacement.side(DockDropZone.SPLIT_RIGHT),
                DockOpenOptions.INACTIVE);
        settle();

        workbench.dock().closePanelDiscarding(first);
        settle();

        assertFalse("a document was released while another panel was still showing it",
                document.disposed);

        // And closing the LAST one does release it.
        workbench.dock().closePanelDiscarding(second);
        settle();
        assertTrue("the last panel closed and nothing was released", document.disposed);
    }

    /** Closing one file does not touch another's document. */
    @Test
    public void closingOneDocumentLeavesTheOthersAlone() {
        DockPanelRef a = openDoc("mymod.proj:a.doc");
        openDoc("mymod.proj:b.doc");

        workbench.dock().closePanelDiscarding(a);
        settle();

        assertTrue(documentAt("mymod.proj:a.doc").disposed);
        assertFalse(documentAt("mymod.proj:b.doc").disposed);
    }
}
