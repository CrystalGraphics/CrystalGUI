package com.crystalgui.ui;

import com.crystalgui.fs.CgPath;
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
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.ui.elements.workbench.decoration.FileDecoration;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6 <b>6.3</b> — what an open editor does when its file changes on the server.
 *
 * <p>The notification has crossed the wire since Phase 4 and reached only the file tree. An open editor
 * was never told, so a clean buffer showed stale content for ever, a deleted file left a normal-looking
 * tab, and a change under a dirty buffer was discovered at save time or not at all.</p>
 *
 * <p>The interesting assertions are the two that <b>do not</b> reload: a dirty buffer must not be
 * overwritten without asking, and a deleted file must not take its buffer with it.</p>
 */
public class ExternalChangeTest extends UiTestBase {

    private static final CgPath README = CgPath.parse("mymod.proj:README.md");
    private static final String ORIGINAL = "# hello\n";
    private static final String EXTERNAL = "# changed by somebody else\n";

    private InMemoryFileSystem files;
    private InMemoryTransport<Object>[] pair;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> session;
    private WorkspaceService service;
    private WorkspaceRpc<Object> rpc;

    private UIWindow window;
    private Workbench workbench;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", ORIGINAL)
                .seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        pair = InMemoryTransport.pair();
        server = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        rpc = new WorkspaceRpc<>(service, WorkspaceActor.LOCAL);
        rpc.installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        workbench = new Workbench(new WorkspaceClient<>(session, PlainOps.INSTANCE));
        workbench.layout(l -> l.widthPercent(100f).heightPercent(100f));

        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1400, 900);
        settle();

        workbench.fileTree().loadProjects();
        settle();
        workbench.openFile(README);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            pair[0].deliver();
            pair[1].deliver();
            // BOTH SESSIONS. They own their transports in this shape, so a settle that only delivers
            // moves bytes into two mailboxes nobody drains -- which reads as the read never answering.
            session.tick();
            server.tick();
            window.updateWithoutPainting();
        }
    }

    /** Changes the file behind the editor's back and lets the watcher notice. */
    private void changeOnServer(String content) {
        files.seed("mymod.proj:README.md", content);
        for (int i = 0; i < 24; i++) {
            rpc.pollAndNotify((method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
            settle();
        }
    }

    private void deleteOnServer() {
        service.delete(WorkspaceActor.LOCAL, README, false);
        for (int i = 0; i < 24; i++) {
            rpc.pollAndNotify((method, args) -> server.call(method, args, null, null), PlainOps.INSTANCE);
            settle();
        }
    }

    /** Through the editor, which is what the person is looking at. */
    private String textOnScreen() {
        TextEditor editor = workbench.editorFor(README);
        return editor == null ? null : editor.getText();
    }

    private void typeInto(String text) {
        TextEditor editor = workbench.editorFor(README);
        editor.setText(text + editor.getText());
    }

    private FileDecoration decoration() {
        return workbench.fileTree().getDecorations().resolve(README, false);
    }

    // ── The three states ────────────────────────────────────────────────────────────────────────

    /**
     * A clean buffer takes the new bytes without asking.
     *
     * <p>The overwhelmingly common case — a git checkout, an external save — and prompting for it is
     * what makes a watcher naggy rather than helpful. There is nothing to lose: the buffer and the file
     * agreed a moment ago.</p>
     */
    @Test
    public void aCleanBufferIsReloadedSilently() {
        assertEquals(ORIGINAL, textOnScreen());

        changeOnServer(EXTERNAL);

        assertEquals("the editor must show what is on the server now", EXTERNAL, textOnScreen());
        assertNull("and must not be marked, because there is nothing to resolve", decoration());
    }

    /**
     * <b>A dirty buffer is marked and left alone.</b>
     *
     * <p>Reloading would destroy unsaved work without asking. The decision belongs at save time, where
     * {@code ConflictDialog} makes it with all three answers on the table.</p>
     */
    @Test
    public void aDirtyBufferIsMarkedRatherThanOverwritten() {
        typeInto("my own edit\n");
        settle();

        changeOnServer(EXTERNAL);

        assertTrue("the user's text must survive", textOnScreen().startsWith("my own edit"));
        FileDecoration mark = decoration();
        assertNotNull("and the tab must say the file moved", mark);
        assertEquals("!", mark.letter());
    }

    /**
     * A deleted file keeps its buffer.
     *
     * <p>Closing the tab throws away text the user may well want to write back — which is the whole
     * reason a buffer is worth more than the file it came from.</p>
     */
    @Test
    public void aDeletedFileKeepsItsBufferAndSaysSo() {
        deleteOnServer();

        assertEquals("the text is still there", ORIGINAL, textOnScreen());
        FileDecoration mark = decoration();
        assertNotNull("and the tab says it is gone", mark);
        assertTrue("struck through, as a deleted thing should be", mark.strikethrough());
    }

    /**
     * A plain save of an externally-changed file is <b>refused</b>, and the mark stays.
     *
     * <p>Which is 6.3 meeting 5.5, and the first version of this test asserted the opposite — that
     * saving clears the mark. It does not, because there is nothing to clear yet: the write quotes an
     * etag the server no longer has, so it comes back as a conflict and {@code ConflictDialog} asks
     * which version survives. The mark is <em>correct</em> until somebody answers.</p>
     *
     * <p>The mark only clears on a write that actually lands — which is what {@code Workbench.saved}
     * does, on both the ordinary path and the "keep mine" overwrite the dialog offers.</p>
     */
    @Test
    public void aSaveOverAChangedFileIsRefusedAndStaysMarked() {
        typeInto("mine\n");
        settle();
        changeOnServer(EXTERNAL);
        assertNotNull("marked first", decoration());

        workbench.saveActiveFile();
        settle();

        assertNotNull("the disagreement is not resolved by asking for it again", decoration());
        assertTrue("and the user's text is still theirs", textOnScreen().startsWith("mine"));
    }

    /** A write that lands does clear it — the file and the buffer now agree. */
    @Test
    public void aWriteThatLandsClearsTheMark() {
        typeInto("mine\n");
        settle();
        changeOnServer(EXTERNAL);
        assertNotNull("marked first", decoration());

        // "Keep mine": the overwrite quotes no etag, so it cannot be refused as stale. This is the
        // branch ConflictDialog runs when the user chooses it.
        workbench.overwriteActiveFile();
        settle();

        assertNull("a successful write means this buffer is what the server holds", decoration());
    }
}
