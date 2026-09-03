package com.crystalgui.headless;

import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link WorkspaceClient} — the typed facade, and the etag bookkeeping a UI would otherwise have to do.
 *
 * <p>Whole stack in one JVM: client facade → session → transport → session → RPC → service → filesystem.
 * No Minecraft, no window, no disk.</p>
 */
public class WorkspaceClientTest {

    private InMemoryFileSystem files;
    private ClientUiSession<UIElement, Object> session;
    private ServerUiSession<UIElement, Object> server;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private WorkspaceClient<Object> client;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.proj:src/Main.java", "class Main {}")
                .seed("mymod.proj:README.md", "# hello");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = Sessions.serve(1, new UIElement(), a);
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

    // ── Typed calls ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void projectsComeBackTyped() {
        AtomicReference<List<ProjectInfo>> got = new AtomicReference<>();
        client.projects(got::set, f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(1, got.get().size());
        assertEquals("mymod.proj", got.get().get(0).id());
        assertEquals("My Project", got.get().get(0).displayName());
    }

    @Test
    public void listingComesBackTyped() {
        AtomicReference<List<CgFileEntry>> got = new AtomicReference<>();
        client.list(p("mymod.proj:"), got::set, f -> fail("unexpected: " + f.code()));
        pump();

        List<String> names = new ArrayList<>();
        boolean sawDirectory = false;
        for (CgFileEntry entry : got.get()) {
            names.add(entry.name());
            if (entry.name().equals("src")) sawDirectory = entry.isDirectory();
        }
        names.sort(null);
        assertEquals(List.of("README.md", "src"), names);
        assertTrue("the directory flag must survive the round trip", sawDirectory);
    }

    @Test
    public void readingGivesADocumentWithItsText() {
        AtomicReference<WorkspaceClient.Document> got = new AtomicReference<>();
        client.read(p("mymod.proj:README.md"), got::set, f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals("# hello", got.get().text());
        assertNotNull(got.get().etag());
    }

    // ── The etag bookkeeping, which is the point of this class ──────────────────────────────────

    /** <b>A read remembers the etag, so a save needs nothing but the bytes.</b> */
    @Test
    public void saveUsesTheEtagFromTheRead() {
        client.read(p("mymod.proj:README.md"), d -> { }, f -> fail());
        pump();
        String afterRead = client.etagOf(p("mymod.proj:README.md"));
        assertNotNull(afterRead);

        AtomicReference<String> saved = new AtomicReference<>();
        client.save(p("mymod.proj:README.md"), "# changed".getBytes(StandardCharsets.UTF_8),
                saved::set, f -> fail("unexpected: " + f.code()));
        pump();

        assertNotEquals("the remembered etag must advance", afterRead, saved.get());
        assertEquals("and be what the client now holds", saved.get(),
                client.etagOf(p("mymod.proj:README.md")));
    }

    /**
     * <b>Saving a file that was never read is refused before anything is sent.</b>
     *
     * <p>There is no etag to quote, so the only options are to write unconditionally or to fail. Writing
     * unconditionally is exactly the silent clobber the whole mechanism exists to prevent, so a caller
     * that means it has to say {@code overwrite}.</p>
     */
    @Test
    public void savingWithoutAReadIsAProgrammingError() {
        try {
            client.save(p("mymod.proj:README.md"), new byte[0], e -> fail(), f -> fail());
            fail("expected an IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue("the message should point at the alternative",
                    e.getMessage().contains("overwrite"));
        }
    }

    @Test
    public void overwriteWritesWithoutAnEtag() {
        AtomicReference<String> saved = new AtomicReference<>();
        client.overwrite(p("mymod.proj:README.md"), "# forced".getBytes(StandardCharsets.UTF_8),
                saved::set, f -> fail("unexpected: " + f.code()));
        pump();

        assertNotNull(saved.get());
        assertEquals("# forced", new String(files.read(p("mymod.proj:README.md")), StandardCharsets.UTF_8));
    }

    /**
     * <b>A conflict arrives typed, carrying the live etag.</b>
     *
     * <p>Everything a "reload or keep?" prompt needs, without a second round trip — which is the whole
     * reason the server puts the etag in the failure rather than making the client ask again.</p>
     */
    @Test
    public void aConflictIsTypedAndCarriesTheLiveEtag() {
        client.read(p("mymod.proj:README.md"), d -> { }, f -> fail());
        pump();
        String stale = client.etagOf(p("mymod.proj:README.md"));

        // Somebody else writes -- an edit on the host machine looks exactly like this.
        files.write(p("mymod.proj:README.md"), "# theirs".getBytes(StandardCharsets.UTF_8), false, true);

        AtomicReference<WorkspaceClient.Failure> failure = new AtomicReference<>();
        client.save(p("mymod.proj:README.md"), "# mine".getBytes(StandardCharsets.UTF_8),
                e -> fail("the save should not have succeeded"), failure::set);
        pump();

        assertNotNull(failure.get());
        assertTrue("a UI must be able to branch on this", failure.get().isConflict());
        assertNotNull(failure.get().actualEtag());
        assertNotEquals(stale, failure.get().actualEtag());
        assertEquals("their content survives", "# theirs",
                new String(files.read(p("mymod.proj:README.md")), StandardCharsets.UTF_8));
    }

    /** Ordinary failures decode to a {@link CgFileError} rather than a conflict. */
    @Test
    public void ordinaryFailuresDecodeToACode() {
        AtomicReference<WorkspaceClient.Failure> failure = new AtomicReference<>();
        client.read(p("mymod.proj:ghost.md"), d -> fail(), failure::set);
        pump();

        assertTrue(failure.get() != null);
        assertEquals(false, failure.get().isConflict());
        assertEquals(CgFileError.FILE_NOT_FOUND, failure.get().error());
        assertNull(failure.get().actualEtag());
    }

    /** An unrecognised code degrades to UNKNOWN rather than throwing inside the client. */
    @Test
    public void anUnrecognisedCodeDegradesToUnknown() {
        assertEquals(CgFileError.UNKNOWN,
                new WorkspaceClient.Failure("SOMETHING_NEW", null).error());
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void createRemembersTheEtagToo() {
        AtomicReference<String> etag = new AtomicReference<>();
        client.create(p("mymod.proj:new.txt"), "fresh".getBytes(StandardCharsets.UTF_8),
                etag::set, f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(etag.get(), client.etagOf(p("mymod.proj:new.txt")));
        // ...so a save straight after a create needs no read.
        client.save(p("mymod.proj:new.txt"), "second".getBytes(StandardCharsets.UTF_8),
                e -> { }, f -> fail("unexpected: " + f.code()));
        pump();
        assertEquals("second", new String(files.read(p("mymod.proj:new.txt")), StandardCharsets.UTF_8));
    }

    /**
     * <b>Closing a document forgets its etag.</b>
     *
     * <p>A stale entry would make a save in a later session quote an etag from an older one and be
     * refused for a reason the user cannot act on.</p>
     */
    @Test
    public void forgettingClearsTheEtag() {
        client.read(p("mymod.proj:README.md"), d -> { }, f -> fail());
        pump();
        assertNotNull(client.etagOf(p("mymod.proj:README.md")));

        client.forget(p("mymod.proj:README.md"));
        assertNull(client.etagOf(p("mymod.proj:README.md")));
    }

    @Test
    public void mkdirRoundTrips() {
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        client.mkdir(p("mymod.proj:build"), () -> done.set(true), f -> fail("unexpected: " + f.code()));
        pump();

        assertTrue(done.get());
        assertTrue(files.exists(p("mymod.proj:build")));
    }
}
