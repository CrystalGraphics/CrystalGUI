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
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.workbench.WorkbenchSession;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
     * <b>Closing the WINDOW the editor lives in writes the record too.</b>
     *
     * <p>The test above detaches the editor directly, which is not how anyone closes it. In a compositor
     * the editor is the CONTENT of a {@code WindowFrame}, and the close button goes through
     * {@code requestClose} to {@code destroy()} — so what has to reach {@code onWindowChanged} is a frame
     * being torn down several levels above, with the editor riding along inside it.</p>
     *
     * <p>Asserted on the record rather than on a call, because every part of this is plumbing that can be
     * correct and still not connected: the frame can hide, the editor can detach, and the write can still
     * never happen if the teardown order puts the dispose first.</p>
     */
    @Test
    public void closingTheWindowWritesTheSession() {
        root.removeChild(editor);
        settle();

        WindowFrame frame = new WindowFrame("Crystal Editor");
        frame.setContent(editor);
        window.openWindow(frame);
        settle();

        editor.restoreSession(PROJECT);
        editor.workbench().openFile(CgPath.parse("mymod.proj:README.md"));
        settle();
        assertNull("something wrote a record before the window was closed", record());

        frame.destroy();
        settle();

        assertNotNull("closing the window wrote no session at all", record());
        // AND WHAT IS IN IT. A record that exists but has forgotten the open tabs is the reported
        // symptom -- "it does not properly serialize the open editor region tabs" -- and asserting only
        // that something was written passes against exactly that.
        assertTrue("the session was written without the open file: " + record(),
                record().contains("README.md"));
    }

    /**
     * <b>Shutting down writes the session, without anything naming the editor.</b>
     *
     * <p>Closing a screen already detached the tree, so everything wrote itself. Quitting ran nothing:
     * Minecraft does not call {@code onGuiClosed} on the way out, and {@code CgUiScreen.disposeAll} —
     * whose javadoc says it "frees the editor at game shutdown" — had no callers at all. An arrangement
     * therefore survived being put away and did not survive being finished with.</p>
     *
     * <p>{@code UIWindow.shutdownAll()} causes the detach and nothing else. It enumerates no state and
     * knows about no widget; the writing is the elements' own rule.</p>
     */
    @Test
    public void shuttingDownWritesTheSession() {
        editor.restoreSession(PROJECT);
        editor.workbench().openFile(CgPath.parse("mymod.proj:README.md"));
        settle();
        assertNull("something wrote a record before shutdown", record());

        UIWindow.shutdownAll();
        settle();

        assertNotNull("shutting down wrote no session at all", record());
        assertTrue("the session was written without the open file: " + record(),
                record().contains("README.md"));
    }

    /**
     * <b>It is GENERAL: an ordinary element is told it left the tree too.</b>
     *
     * <p>The assertion that matters, and the one an editor-shaped test cannot make. A shutdown that
     * reached into the editor and asked it to save would satisfy every other test here and leave every
     * other widget — a desktop's arrangement, a panel's width, anything added next year — unwritten. So
     * this hangs a plain element off the same window and asks only whether it was told, which is the
     * contract every persisting widget is built on.</p>
     */
    @Test
    public void shutdownTellsEveryElementItLeftTheTree() {
        final boolean[] told = {false};
        UIElement bystander = new UIElement() {
            @Override
            protected void onWindowChanged(UIWindow previous, UIWindow current) {
                if (previous != null && current == null) told[0] = true;
            }
        };
        root.addChild(bystander);
        settle();
        assertFalse("the bystander was told before anything happened", told[0]);

        UIWindow.shutdownAll();
        settle();

        assertTrue("an ordinary element was never told the window went away", told[0]);
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
