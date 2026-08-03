package com.crystalgui.headless;

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
import com.crystalgui.ui.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Load File System Changes, after a conflict.</b>
 *
 * <p>The harness scene's Keep button worked and its Reload button appeared to do nothing, so this walks
 * the exact sequence the scene does: read, somebody else writes, save is refused, then read again and
 * save again.</p>
 */
public class WorkspaceReloadAfterConflictTest {

    private InMemoryFileSystem files;
    private ServerUiSession<Object> server;
    private ClientUiSession<Object> session;
    private WorkspaceClient<Object> client;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;

    private static final CgPath FILE = CgPath.parse("mymod.proj:README.md");

    @Before
    public void setUp() {
        files = new InMemoryFileSystem().seed("mymod.proj:README.md", "original");
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "Proj", Paths.get("/srv/proj"))));
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = new ServerUiSession<>(1, new UIElement(), a, PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        session = new ClientUiSession<>(b, PlainOps.INSTANCE);
        client = new WorkspaceClient<>(session, PlainOps.INSTANCE);
        pump();
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            int moved = a.deliver() + b.deliver();
            session.tick();
            server.tick();
            if (moved == 0) break;
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The whole sequence the two buttons sit on top of.
     *
     * <p>Reload must give back <em>the disk's</em> content, and — the part that is easy to miss — it must
     * also leave the client holding the etag that makes the <b>next</b> save succeed. A reload that
     * refreshed the text but not the etag would clear the banner and then conflict again on the very next
     * Ctrl+S, which is indistinguishable from "the button did nothing".</p>
     */
    @Test
    public void reloadGivesTheDiskContentAndLetsTheNextSaveSucceed() {
        AtomicReference<WorkspaceClient.Document> opened = new AtomicReference<>();
        client.read(FILE, opened::set, f -> org.junit.Assert.fail("open: " + f.code()));
        pump();
        assertEquals("original", opened.get().text());

        // Somebody edits on disk.
        files.write(FILE, "edited on disk".getBytes(StandardCharsets.UTF_8), false, true);

        // The user's save is refused.
        AtomicReference<WorkspaceClient.Failure> refused = new AtomicReference<>();
        client.save(FILE, "my local edit".getBytes(StandardCharsets.UTF_8),
                e -> org.junit.Assert.fail("the save should have been refused"), refused::set);
        pump();
        assertNotNull(refused.get());
        assertTrue(refused.get().isConflict());

        // "Load File System Changes".
        AtomicReference<WorkspaceClient.Document> reloaded = new AtomicReference<>();
        AtomicReference<WorkspaceClient.Failure> reloadFailed = new AtomicReference<>();
        client.read(FILE, reloaded::set, reloadFailed::set);
        pump();

        assertNotNull("reload must produce a document — failure was " + reloadFailed.get(),
                reloaded.get());
        assertEquals("and it must be what is on disk", "edited on disk", reloaded.get().text());

        // ...and the next save must now go through, or the button only appeared to work.
        AtomicReference<String> saved = new AtomicReference<>();
        client.save(FILE, "after reload".getBytes(StandardCharsets.UTF_8), saved::set,
                f -> org.junit.Assert.fail("the save after a reload must succeed, got " + f.code()));
        pump();

        assertNotNull(saved.get());
        assertEquals("after reload", text(files.read(FILE)));
    }

    /** The Keep path, for symmetry — it was the one that already worked. */
    @Test
    public void keepOverwritesAndAlsoLeavesAUsableEtag() {
        client.read(FILE, d -> { }, f -> org.junit.Assert.fail());
        pump();
        files.write(FILE, "edited on disk".getBytes(StandardCharsets.UTF_8), false, true);

        AtomicReference<String> kept = new AtomicReference<>();
        client.overwrite(FILE, "mine wins".getBytes(StandardCharsets.UTF_8), kept::set,
                f -> org.junit.Assert.fail("overwrite: " + f.code()));
        pump();

        assertEquals("mine wins", text(files.read(FILE)));

        AtomicReference<String> again = new AtomicReference<>();
        client.save(FILE, "and again".getBytes(StandardCharsets.UTF_8), again::set,
                f -> org.junit.Assert.fail("a save after keep must succeed, got " + f.code()));
        pump();
        assertEquals("and again", text(files.read(FILE)));
    }
}
