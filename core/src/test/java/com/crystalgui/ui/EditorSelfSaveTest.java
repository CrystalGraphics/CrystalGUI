package com.crystalgui.ui;

import com.crystalgui.editor.CrystalEditor;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryConfigStorage;
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
import com.crystalgui.ui.elements.workbench.WorkbenchSession;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * <b>The editor writes its own session; a host only says where the config lives.</b>
 *
 * <p>It used to be the other way round, and the cost was three copies: {@code CgUiScreen}, the desktop
 * harness scene and the dock harness scene each called {@code saveSession} and {@code savePreferences}
 * with the project id and the viewport size handed back in. One policy in three files nobody reads
 * together, each free to forget a half — and forgetting one is silent, because a session that is never
 * written looks exactly like a session with nothing in it.</p>
 *
 * <p>Going off screen is the moment, and the editor is what knows it: a screen closing detaches the
 * compositor, which detaches every window on it and the editor inside one.</p>
 */
public class EditorSelfSaveTest extends UiTestBase {

    private static final String PROJECT = "mymod.proj";

    private final InMemoryConfigStorage storage = new InMemoryConfigStorage();

    private UIWindow window;
    private UIElement root;
    private CrystalEditor editor;

    private InMemoryTransport<Object> serverSide;
    private InMemoryTransport<Object> clientSide;
    private ClientUiSession<Object> clientSession;
    private ServerUiSession<Object> serverSession;

    @Before
    public void setUp() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:README.md", "# hello");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT, "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverSide = pair[0];
        clientSide = pair[1];
        serverSession = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(serverSession::onCall);
        serverSession.open();
        clientSession = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);

        editor = new CrystalEditor(new WorkspaceClient<>(clientSession, PlainOps.INSTANCE));
        editor.useConfig(storage);
        root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();
        }
    }

    private String record() {
        return storage.read(WorkbenchSession.fileNameFor(PROJECT));
    }

    /** <b>Leaving the tree is what writes the record — no host asks for it.</b> */
    @Test
    public void goingOffScreenWritesTheSession() {
        editor.restoreSession(PROJECT);
        editor.workbench().openFile(CgPath.parse("mymod.proj:README.md"));
        settle();
        assertNull("something wrote a record before the editor went anywhere", record());

        root.removeChild(editor);
        settle();

        assertNotNull("the editor went off screen without recording anything", record());
    }

    /**
     * <b>An editor that was never told which project it is holding writes nothing.</b>
     *
     * <p>There is no record to write yet, and inventing an id to write one under would put a session
     * under a name nothing will ever ask for — a file that grows and is never read.</p>
     */
    @Test
    public void anEditorWithNoProjectWritesNothing() {
        editor.workbench().openFile(CgPath.parse("mymod.proj:README.md"));
        settle();

        root.removeChild(editor);
        settle();

        assertNull("a session was written under a project nobody named", record());
    }
}
