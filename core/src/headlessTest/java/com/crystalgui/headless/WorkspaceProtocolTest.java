package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceProtocol;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The whole server side, end to end, over {@code InMemoryTransport} — both halves in one JVM.
 *
 * <p>This is the property the layering was built for: a client calls, a server answers, files change,
 * conflicts are refused, and none of it needs Minecraft, a window, a socket or a disk. Every failure is
 * asserted by the <b>code</b> that reaches the client, because that is what a UI will branch on.</p>
 */
public class WorkspaceProtocolTest {

    private InMemoryFileSystem files;
    private ClientUiSession<Object> client;
    private ServerUiSession<Object> server;
    private InMemoryTransport<Object> toServer;
    private InMemoryTransport<Object> toClient;

    /** The last result of a call — RPC is async, so a test collects rather than returns. */
    private final AtomicReference<StateMap<Object>> ok = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.scripts:src/Main.java", "class Main {}")
                .seed("mymod.scripts:README.md", "# hello");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.scripts", "Scripts", Paths.get("/srv/scripts"))));

        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        toServer = pair[0];
        toClient = pair[1];

        server = new ServerUiSession<>(1, new UIElement(), toServer, PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        client = new ClientUiSession<>(toClient, PlainOps.INSTANCE);
        pump();
    }

    /**
     * Delivers everything in flight, both directions, until nothing moves.
     *
     * <p><b>Both sessions are ticked.</b> Leaving out {@code server.tick()} is why the first version of
     * this test failed every case identically with a null result: the call reached the server and the
     * answer was never pumped back, which reads as "the handler is broken" rather than "the harness is".</p>
     */
    private void pump() {
        for (int i = 0; i < 8; i++) {
            int moved = toServer.deliver() + toClient.deliver();
            client.tick();
            server.tick();
            if (moved == 0) break;
        }
    }

    /** Makes a call and pumps until it settles. */
    private void call(String method, StateMap<Object> args) {
        ok.set(null);
        failure.set(null);
        client.call(method, args, ok::set, failure::set);
        pump();
    }

    private StateMap<Object> args() {
        return new StateMap<>(PlainOps.INSTANCE);
    }

    private StateMap<Object> at(String path) {
        return args().putString(WorkspaceProtocol.PATH, path);
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aClientCanListProjects() {
        call(WorkspaceProtocol.PROJECTS, args());

        assertNull(failure.get());
        List<String> ids = new ArrayList<>();
        ok.get().getList(WorkspaceProtocol.PROJECT_LIST,
                entry -> ids.add(entry.getString(WorkspaceProtocol.ID, "")));
        assertEquals(List.of("mymod.scripts"), ids);
    }

    @Test
    public void aClientCanListADirectory() {
        call(WorkspaceProtocol.MANIFEST, at("mymod.scripts:"));

        assertNull(failure.get());
        List<String> names = new ArrayList<>();
        ok.get().getList(WorkspaceProtocol.ENTRIES,
                entry -> names.add(entry.getString(WorkspaceProtocol.NAME, "")));
        names.sort(null);
        assertEquals(List.of("README.md", "src"), names);
    }

    /** The manifest carries etags — that is the whole reason it is not a plain listing. */
    @Test
    public void aManifestCarriesEtags() {
        call(WorkspaceProtocol.MANIFEST, at("mymod.scripts:src"));

        List<String> etags = new ArrayList<>();
        ok.get().getList(WorkspaceProtocol.ENTRIES,
                entry -> etags.add(entry.getString(WorkspaceProtocol.ETAG, "")));
        assertEquals(1, etags.size());
        assertTrue("an etag must actually be present", etags.get(0).length() > 0);
    }

    @Test
    public void aClientCanReadAFile() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));

        assertNull(failure.get());
        assertEquals("# hello",
                new String(ok.get().getBytes(WorkspaceProtocol.CONTENT), StandardCharsets.UTF_8));
        assertTrue(ok.get().getString(WorkspaceProtocol.ETAG, "").length() > 0);
    }

    /**
     * <b>Binary survives the round trip.</b>
     *
     * <p>The reason bytes went into the codec at all. Under {@link PlainOps} the array is held natively;
     * under a textual ops it would base64 — and this test would pass either way, which is the point.</p>
     */
    @Test
    public void binaryContentSurvives() {
        byte[] binary = new byte[512];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i * 7 + 3);
        files.write(com.crystalgui.fs.CgPath.parse("mymod.scripts:image.png"), binary, true, true);

        call(WorkspaceProtocol.READ, at("mymod.scripts:image.png"));
        assertArrayEquals(binary, ok.get().getBytes(WorkspaceProtocol.CONTENT));
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aClientCanSaveAndGetTheNewEtagBack() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));
        String etag = ok.get().getString(WorkspaceProtocol.ETAG, "");

        call(WorkspaceProtocol.WRITE, at("mymod.scripts:README.md")
                .putBytes(WorkspaceProtocol.CONTENT, "# changed".getBytes(StandardCharsets.UTF_8))
                .putString(WorkspaceProtocol.ETAG, etag));

        assertNull(failure.get());
        assertNotEquals("the new etag must differ", etag, ok.get().getString(WorkspaceProtocol.ETAG, ""));

        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));
        assertEquals("# changed",
                new String(ok.get().getBytes(WorkspaceProtocol.CONTENT), StandardCharsets.UTF_8));
    }

    /**
     * <b>A stale save is refused across the wire, and the refusal carries the current etag.</b>
     *
     * <p>The conflict story, end to end. The client reads, the file changes behind everyone's back — as
     * an edit on the host machine would — and the save is refused with enough information to offer a
     * reload without another round trip.</p>
     */
    @Test
    public void aStaleSaveIsRefusedWithTheCurrentEtag() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));
        String stale = ok.get().getString(WorkspaceProtocol.ETAG, "");

        files.write(com.crystalgui.fs.CgPath.parse("mymod.scripts:README.md"),
                "# somebody else".getBytes(StandardCharsets.UTF_8), false, true);

        call(WorkspaceProtocol.WRITE, at("mymod.scripts:README.md")
                .putBytes(WorkspaceProtocol.CONTENT, "# mine".getBytes(StandardCharsets.UTF_8))
                .putString(WorkspaceProtocol.ETAG, stale));

        assertNull("a conflict is a failure, not a success", ok.get());
        assertNotNull(failure.get());
        assertTrue("the client must be able to recognise a conflict: " + failure.get(),
                failure.get().startsWith(WorkspaceProtocol.ERROR_CONFLICT));
        assertTrue("and be handed the etag it now needs", failure.get().length()
                > WorkspaceProtocol.ERROR_CONFLICT.length() + 1);

        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));
        assertEquals("the other write survives", "# somebody else",
                new String(ok.get().getBytes(WorkspaceProtocol.CONTENT), StandardCharsets.UTF_8));
    }

    @Test
    public void createMakesANewFileAndRefusesAnExistingOne() {
        call(WorkspaceProtocol.CREATE, at("mymod.scripts:new.txt")
                .putBytes(WorkspaceProtocol.CONTENT, "fresh".getBytes(StandardCharsets.UTF_8)));
        assertNull(failure.get());

        call(WorkspaceProtocol.CREATE, at("mymod.scripts:new.txt")
                .putBytes(WorkspaceProtocol.CONTENT, "again".getBytes(StandardCharsets.UTF_8)));
        assertEquals(CgFileError.FILE_EXISTS.name(), failure.get());
    }

    @Test
    public void mkdirCreatesADirectory() {
        call(WorkspaceProtocol.MKDIR, at("mymod.scripts:build"));
        assertNull(failure.get());

        call(WorkspaceProtocol.MANIFEST, at("mymod.scripts:build"));
        assertNull(failure.get());
    }

    // ── Failures reach the client as codes ──────────────────────────────────────────────────────

    @Test
    public void aMissingFileReportsItsCode() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:ghost.md"));
        assertEquals(CgFileError.FILE_NOT_FOUND.name(), failure.get());
    }

    @Test
    public void anUnknownProjectReportsNotFound() {
        call(WorkspaceProtocol.READ, at("nope.nope:file.txt"));
        assertEquals(CgFileError.FILE_NOT_FOUND.name(), failure.get());
    }

    @Test
    public void readingADirectoryReportsItsCode() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:src"));
        assertEquals(CgFileError.FILE_IS_A_DIRECTORY.name(), failure.get());
    }

    /**
     * <b>A path that tries to escape is refused, and never reaches the filesystem.</b>
     *
     * <p>{@code CgPath.parse} throws during argument decoding, so the traversal is stopped a layer before
     * anything could act on it. The client is told {@code INVALID_PATH} rather than anything that would
     * confirm what is up there.</p>
     */
    @Test
    public void aTraversalAttemptIsRefusedOverTheWire() {
        call(WorkspaceProtocol.READ, at("mymod.scripts:../../server.properties"));
        assertEquals(CgFileError.INVALID_PATH.name(), failure.get());
    }

    /** An unauthorised client gets a permission code, and the same one whether or not the file is there. */
    @Test
    public void anUnauthorisedClientIsRefusedIdentically() {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.scripts", "Scripts", Paths.get("/srv/scripts"))));
        WorkspaceService denied =
                new WorkspaceService(registry, files, WorkspacePermission.DENY_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        server = new ServerUiSession<>(2, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(denied, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        client = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
        toServer = pair[0];
        toClient = pair[1];
        pump();

        call(WorkspaceProtocol.READ, at("mymod.scripts:README.md"));
        String present = failure.get();
        call(WorkspaceProtocol.READ, at("mymod.scripts:ghost.md"));
        String absent = failure.get();

        assertEquals(CgFileError.NO_PERMISSIONS.name(), present);
        assertEquals("the two must be indistinguishable", present, absent);
    }
}
