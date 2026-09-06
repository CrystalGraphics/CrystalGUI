package com.crystalgui.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.document.Document;
import com.crystalgui.document.BytesDocumentModel;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.document.EditorInput;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.fs.CgPath;
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
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.fs.Resource;

/**
 * <b>Closing a tab and reopening it puts the editor back the way it was.</b>
 *
 * <p>{@code DocumentEditor.writeViewState} was reached by exactly one caller — {@code WorkbenchSession},
 * at session save and restore — so quitting and relaunching kept an editor's camera and floating panels
 * while closing the tab and reopening it lost them. That is the wrong way round: the shorter the round
 * trip, the more certain a user is that nothing should have moved.</p>
 *
 * <p>Written against a probe editor rather than the shader graph, because what is under test is the
 * WIRING — that something captures before the dock detaches the widget, and that a reopen replays it.</p>
 */
public class ClosingKeepsViewStateTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";

    private static final CgPath FILE = CgPath.of(PROJECT, "notes.probe");

    private static final String KEY = "probe.state";

    /** Records what it was handed, and hands back whatever it was last set to. */
    private static final class ProbeEditor implements DocumentEditor {
        static int writes;
        static int reads;
        private final UIElement element = new UIElement();
        private String value = "";

        @Override
        public UIElement view() {
            return element;
        }

        @Override
        public <T> void writeViewState(StateMap<T> out) {
            writes++;
            out.putString(KEY, value);
        }

        @Override
        public <T> void readViewState(StateMap<T> in) {
            reads++;
            value = in.getString(KEY, "");
        }
    }

    private Workbench workbench;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        ProbeEditor.writes = 0;
        ProbeEditor.reads = 0;
        Protocols.resetForTesting();
        InMemoryFileSystem files =
                new InMemoryFileSystem().seed(PROJECT + ":notes.probe", "nothing in particular");
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
        workbench = new Workbench(workspace);
        workbench.kinds().register(DocumentKind.of("test:probe", "Probe")
                .files(DocumentKind.FilePatterns.extension("probe"))
                .model(bytes -> new BytesDocumentModel(bytes))
                .editor(this::probeFor));

        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    private DocumentEditor probeFor(Document ignored) {
        return new ProbeEditor();
    }

    @After
    public void closeWorkbench() {
        if (workbench != null) workbench.dispose();
        if (clientEnd != null) clientEnd.close("test over");
        if (serverEnd != null) serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 12; i++) {
            frame();
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
        }
    }

    private ProbeEditor editor() {
        EditorService.Tab tab = workbench.editors.tabFor(EditorInput.of(Resource.of(FILE)));
        if (tab == null) return null;
        return (ProbeEditor) tab.editor();
    }

    private DockPanelRef openPanel() {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            DockGroup group = workbench.dock().groupFor(leaf);
            if (group == null) continue;
            for (DockPanelRef panel : group.panels()) {
                if (FILE.toString().equals(panel.state(Workbench.PATH_STATE, ""))) return panel;
            }
        }
        return null;
    }

    @Test
    public void reopeningAFileRestoresWhatItsEditorWasShowing() {
        workbench.openFile(FILE);
        pump();

        ProbeEditor first = editor();
        assertNotNull("the probe editor opened", first);
        first.value = "where I left it";

        DockPanelRef panel = openPanel();
        assertNotNull("the tab is on screen", panel);
        workbench.dock().closePanel(panel);
        pump();

        workbench.openFile(FILE);
        pump();

        ProbeEditor reopened = editor();
        assertNotNull("it opened again", reopened);
        assertEquals("writes=" + ProbeEditor.writes + " reads=" + ProbeEditor.reads
                        + " -- a reopen replays what the close captured",
                "where I left it", reopened.value);
    }
}
