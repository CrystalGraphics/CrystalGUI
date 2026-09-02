package com.crystalgui.headless;

import com.crystalgui.fs.CgFileEntry;
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
import com.crystalgui.ui.dom.UINode;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code fs.delete} and {@code fs.rename} — the two methods the service always had and the protocol never
 * carried.
 *
 * <p>Whole stack in one JVM: client facade → session → transport → session → RPC → service → filesystem.
 * No Minecraft, no window, no disk.</p>
 *
 * <h3>Why the etag guard is asserted harder here than on write</h3>
 *
 * <p>A stale write loses the other author's edit. A stale <b>delete</b> loses the file, and in a workspace
 * with no version control that is the end of it. Both operations run the same re-stat, so what is worth
 * pinning is that they genuinely do — and that an <em>absent</em> etag still means "unconditionally"
 * rather than an empty-string expectation nothing can satisfy.</p>
 */
public class WorkspaceMutationTest {

    private InMemoryFileSystem files;
    private ClientUiSession<UINode, Object> session;
    private ServerUiSession<UINode, Object> server;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private WorkspaceClient<Object> client;
    private WorkspaceService service;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.proj:src/Main.java", "class Main {}")
                .seed("mymod.proj:src/Util.java", "class Util {}")
                .seed("mymod.proj:README.md", "# hello");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        service = new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = Sessions.serve(1, new UINode(), a);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        session = Sessions.view(b);
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

    private static CgPath p(String path) {
        return CgPath.parse(path);
    }

    private boolean exists(String path) {
        return files.exists(p(path));
    }

    /** Reads a file through the client, so its etag lands in the client's bookkeeping. */
    private String readAndRemember(String path) {
        AtomicReference<String> etag = new AtomicReference<>();
        client.read(p(path), doc -> etag.set(doc.etag()), f -> fail("unexpected: " + f.code()));
        pump();
        assertNotNull("the read never completed", etag.get());
        return etag.get();
    }

    // ── Delete ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void deleteRemovesTheFile() {
        readAndRemember("mymod.proj:README.md");
        List<String> done = new ArrayList<>();
        client.delete(p("mymod.proj:README.md"), false,
                () -> done.add("ok"), f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(List.of("ok"), done);
        assertFalse("the file is still there", exists("mymod.proj:README.md"));
    }

    /** A path this client never read carries no etag, and must still delete rather than refusing. */
    @Test
    public void deleteWithNoKnownEtagIsUnconditional() {
        assertNull("fixture wrong -- this path should be unread", client.etagOf(p("mymod.proj:README.md")));
        List<String> done = new ArrayList<>();
        client.delete(p("mymod.proj:README.md"), false,
                () -> done.add("ok"), f -> fail("refused a delete it had no etag for: " + f.code()));
        pump();

        assertEquals(List.of("ok"), done);
        assertFalse(exists("mymod.proj:README.md"));
    }

    /**
     * <b>A delete whose base moved is refused.</b>
     *
     * <p>The client reads, somebody else writes, and the delete then quotes an etag that is no longer
     * true. Without the guard the file the user looked at and the file they destroyed are different
     * files.</p>
     */
    @Test
    public void deleteRefusesWhenTheFileMovedUnderneath() {
        readAndRemember("mymod.proj:README.md");
        service.write(WorkspaceActor.LOCAL, p("mymod.proj:README.md"),
                "# changed by somebody else".getBytes(StandardCharsets.UTF_8), null);

        List<String> failures = new ArrayList<>();
        client.delete(p("mymod.proj:README.md"), false,
                () -> fail("deleted a file that had changed underneath it"),
                f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertTrue("a stale delete must report a conflict, not a generic failure, or the client cannot "
                + "offer a reload: " + failures.get(0), failures.get(0).contains("CONFLICT"));
        assertTrue("the file was destroyed anyway", exists("mymod.proj:README.md"));
    }

    /** A non-empty directory needs {@code recursive}, and refusing is the safe default. */
    @Test
    public void deletingANonEmptyDirectoryNeedsRecursive() {
        List<String> failures = new ArrayList<>();
        client.delete(p("mymod.proj:src"), false,
                () -> fail("took a whole directory without being asked to"),
                f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertTrue("src/Main.java went with it", exists("mymod.proj:src/Main.java"));
    }

    @Test
    public void recursiveDeleteTakesTheSubtree() {
        List<String> done = new ArrayList<>();
        client.delete(p("mymod.proj:src"), true,
                () -> done.add("ok"), f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(List.of("ok"), done);
        assertFalse(exists("mymod.proj:src/Main.java"));
        assertFalse(exists("mymod.proj:src/Util.java"));
    }

    /** After a delete the client must forget the path, or a later save quotes an etag for a dead file. */
    @Test
    public void deleteForgetsTheEtag() {
        readAndRemember("mymod.proj:README.md");
        assertNotNull(client.etagOf(p("mymod.proj:README.md")));

        client.delete(p("mymod.proj:README.md"), false, () -> { }, f -> fail(f.code()));
        pump();

        assertNull("the client still holds an etag for a file that no longer exists",
                client.etagOf(p("mymod.proj:README.md")));
    }

    // ── Rename ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void renameMovesTheFile() {
        List<String> done = new ArrayList<>();
        client.rename(p("mymod.proj:README.md"), p("mymod.proj:READTHIS.md"), false,
                () -> done.add("ok"), f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(List.of("ok"), done);
        assertFalse(exists("mymod.proj:README.md"));
        assertTrue(exists("mymod.proj:READTHIS.md"));
    }

    /**
     * <b>The etag moves with the file.</b>
     *
     * <p>Nothing else read the bytes, so what the client knew about the source is exactly what is true of
     * the destination. Dropping it would make the next save quote nothing and write unconditionally —
     * silently giving up the conflict guard for every file that has ever been renamed, which is the kind
     * of regression that shows up as data loss months later.</p>
     */
    @Test
    public void renameCarriesTheEtagToTheNewPath() {
        String before = readAndRemember("mymod.proj:README.md");

        client.rename(p("mymod.proj:README.md"), p("mymod.proj:READTHIS.md"), false,
                () -> { }, f -> fail(f.code()));
        pump();

        assertNull("the old path still has an etag", client.etagOf(p("mymod.proj:README.md")));
        assertEquals("the etag did not move with the file",
                before, client.etagOf(p("mymod.proj:READTHIS.md")));
    }

    @Test
    public void renameRefusesWhenTheSourceMovedUnderneath() {
        readAndRemember("mymod.proj:README.md");
        service.write(WorkspaceActor.LOCAL, p("mymod.proj:README.md"),
                "# changed".getBytes(StandardCharsets.UTF_8), null);

        List<String> failures = new ArrayList<>();
        client.rename(p("mymod.proj:README.md"), p("mymod.proj:READTHIS.md"), false,
                () -> fail("renamed a file that had changed underneath it"), f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertTrue(exists("mymod.proj:README.md"));
        assertFalse(exists("mymod.proj:READTHIS.md"));
    }

    /** Refusing to clobber is the default; {@code overwrite} is the caller saying they meant it. */
    @Test
    public void renameOntoAnExistingPathRefusesUnlessOverwriting() {
        List<String> failures = new ArrayList<>();
        client.rename(p("mymod.proj:src/Main.java"), p("mymod.proj:src/Util.java"), false,
                () -> fail("clobbered an existing file"), f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertTrue("Util.java was destroyed", exists("mymod.proj:src/Util.java"));
        assertTrue("Main.java was moved anyway", exists("mymod.proj:src/Main.java"));
    }

    @Test
    public void renameAcrossProjectsIsRefused() {
        List<String> failures = new ArrayList<>();
        client.rename(p("mymod.proj:README.md"), p("other.proj:README.md"), false,
                () -> fail("moved a file into another project"), f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertTrue(exists("mymod.proj:README.md"));
    }

    /** A directory rename is the same operation, and is how "move into a folder" is expressed. */
    @Test
    public void renameMovesADirectory() {
        client.rename(p("mymod.proj:src"), p("mymod.proj:source"), false,
                () -> { }, f -> fail("unexpected: " + f.code()));
        pump();

        assertTrue(exists("mymod.proj:source/Main.java"));
        assertFalse(exists("mymod.proj:src/Main.java"));
    }

    /** The listing is the client's view of a directory, so it has to reflect both operations. */
    @Test
    public void theDirectoryListingReflectsBothOperations() {
        client.delete(p("mymod.proj:src/Util.java"), false, () -> { }, f -> fail(f.code()));
        pump();
        client.rename(p("mymod.proj:src/Main.java"), p("mymod.proj:src/Entry.java"), false,
                () -> { }, f -> fail(f.code()));
        pump();

        AtomicReference<List<CgFileEntry>> got = new AtomicReference<>();
        client.list(p("mymod.proj:src"), got::set, f -> fail("unexpected: " + f.code()));
        pump();

        List<String> names = new ArrayList<>();
        for (CgFileEntry entry : got.get()) names.add(entry.name());
        assertEquals(List.of("Entry.java"), names);
    }
}
