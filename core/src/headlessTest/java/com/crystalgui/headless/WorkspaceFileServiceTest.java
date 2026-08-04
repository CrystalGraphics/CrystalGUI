package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkingCopies;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspaceFileService;
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

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link WorkspaceFileService} — the rules about open documents, with no window anywhere.
 *
 * <p><b>That is the whole reason {@link WorkingCopies} names no widget.</b> "A rename must retarget the
 * open editor" is a rule about editors that is testable without fonts, a style engine or a GL context —
 * the same extraction that earned {@code text/cursor} its correctness and found a real bug within minutes.
 * The double here records what was asked of it, so the assertions are about the <em>rule</em> rather than
 * about a map somewhere.</p>
 */
public class WorkspaceFileServiceTest {

    /** Records the calls, and models just enough of a path→document map to answer {@code openUnder}. */
    private static final class Copies implements WorkingCopies {
        final Map<CgPath, String> open = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public List<CgPath> openUnder(CgPath path) {
            List<CgPath> found = new ArrayList<>();
            for (CgPath candidate : open.keySet()) {
                if (candidate.equals(path) || path.contains(candidate)) found.add(candidate);
            }
            return found;
        }

        @Override
        public void close(CgPath path) {
            calls.add("close " + path);
            open.remove(path);
        }

        @Override
        public void retarget(CgPath from, CgPath to) {
            calls.add("retarget " + from + " -> " + to);
            String content = open.remove(from);
            if (content != null) open.put(to, content);
        }
    }

    private InMemoryFileSystem files;
    private ClientUiSession<Object> session;
    private ServerUiSession<Object> server;
    private InMemoryTransport<Object> a;
    private InMemoryTransport<Object> b;
    private WorkspaceFileService service;
    private Copies copies;

    @Before
    public void setUp() {
        files = new InMemoryFileSystem()
                .seed("mymod.proj:src/Main.java", "class Main {}")
                .seed("mymod.proj:src/Util.java", "class Util {}")
                .seed("mymod.proj:README.md", "# hello");

        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService backend = new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        a = pair[0];
        b = pair[1];
        server = new ServerUiSession<>(1, new UIElement(), a, PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(backend, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();

        session = new ClientUiSession<>(b, PlainOps.INSTANCE);
        WorkspaceClient<Object> client = new WorkspaceClient<>(session, PlainOps.INSTANCE);
        copies = new Copies();
        service = new WorkspaceFileService(client, copies);
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

    // ── Move ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A rename retargets the open editor rather than closing it.</b>
     *
     * <p>The bug this whole class exists for. Nothing updated the path→editor map, so after a rename the
     * tab kept its old title, Ctrl+S wrote to the old name, and opening the new name produced a second
     * editor for the same file.</p>
     */
    @Test
    public void movingAnOpenFileRetargetsIt() {
        copies.open.put(p("mymod.proj:README.md"), "unsaved work");

        service.move(p("mymod.proj:README.md"), p("mymod.proj:READTHIS.md"), false,
                () -> { }, f -> fail("unexpected: " + f.code()));
        pump();

        assertEquals(List.of("retarget mymod.proj:README.md -> mymod.proj:READTHIS.md"), copies.calls);
        assertEquals("the unsaved work was lost", "unsaved work",
                copies.open.get(p("mymod.proj:READTHIS.md")));
    }

    /**
     * Renaming a <b>directory</b> rebases each open file inside it onto the new root.
     *
     * <p>The moved path is the folder; what is open are the files under it. Retargeting them to the
     * folder's new path — the obvious off-by-one — would point every editor at the same directory.</p>
     */
    @Test
    public void movingADirectoryRebasesEveryFileOpenInside() {
        copies.open.put(p("mymod.proj:src/Main.java"), "a");
        copies.open.put(p("mymod.proj:src/Util.java"), "b");

        service.move(p("mymod.proj:src"), p("mymod.proj:source"), false,
                () -> { }, f -> fail("unexpected: " + f.code()));
        pump();

        assertTrue(copies.open.containsKey(p("mymod.proj:source/Main.java")));
        assertTrue(copies.open.containsKey(p("mymod.proj:source/Util.java")));
        assertFalse(copies.open.containsKey(p("mymod.proj:src/Main.java")));
    }

    /**
     * <b>A refused move touches nothing.</b>
     *
     * <p>Retargeting before the server answers would leave every open document pointing at a path that
     * does not exist — and the server refuses <em>routinely</em>, for a name collision or a stale etag,
     * which are ordinary user mistakes rather than error paths.</p>
     */
    @Test
    public void aRefusedMoveLeavesTheOpenDocumentAlone() {
        copies.open.put(p("mymod.proj:src/Main.java"), "unsaved work");

        List<String> failures = new ArrayList<>();
        service.move(p("mymod.proj:src/Main.java"), p("mymod.proj:src/Util.java"), false,
                () -> fail("clobbered an existing file"), f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertEquals("the editor was retargeted for a move that never happened",
                List.of(), copies.calls);
        assertEquals("unsaved work", copies.open.get(p("mymod.proj:src/Main.java")));
    }

    /** A move that overwrites destroys the destination's bytes, so a document open there has to go. */
    @Test
    public void overwritingAMoveClosesWhateverWasOpenAtTheDestination() {
        copies.open.put(p("mymod.proj:src/Util.java"), "about to be replaced");

        service.move(p("mymod.proj:src/Main.java"), p("mymod.proj:src/Util.java"), true,
                () -> { }, f -> fail("unexpected: " + f.code()));
        pump();

        assertTrue("the destination's editor survived a file that no longer exists",
                copies.calls.contains("close mymod.proj:src/Util.java"));
    }

    // ── Delete ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void deletingAFileClosesItsDocument() {
        copies.open.put(p("mymod.proj:README.md"), "x");

        service.delete(p("mymod.proj:README.md"), false, () -> { }, f -> fail(f.code()));
        pump();

        assertEquals(List.of("close mymod.proj:README.md"), copies.calls);
        assertTrue(copies.open.isEmpty());
    }

    /** Deleting a folder with files open inside it is exactly what a per-path lookup misses. */
    @Test
    public void deletingADirectoryClosesEveryDocumentBeneathIt() {
        copies.open.put(p("mymod.proj:src/Main.java"), "a");
        copies.open.put(p("mymod.proj:src/Util.java"), "b");
        copies.open.put(p("mymod.proj:README.md"), "untouched");

        service.delete(p("mymod.proj:src"), true, () -> { }, f -> fail(f.code()));
        pump();

        assertEquals("the file outside the deleted folder was closed too",
                List.of(p("mymod.proj:README.md")), new ArrayList<>(copies.open.keySet()));
    }

    /**
     * <b>A refused delete keeps the document open.</b>
     *
     * <p>Closing before the server answers would take the user's unsaved work with it and leave the file
     * sitting on disk — the worst of both outcomes.</p>
     */
    @Test
    public void aRefusedDeleteKeepsTheDocumentOpen() {
        copies.open.put(p("mymod.proj:src/Main.java"), "unsaved work");

        List<String> failures = new ArrayList<>();
        service.delete(p("mymod.proj:src"), false,          // non-empty, not recursive
                () -> fail("took a directory without being asked to"), f -> failures.add(f.code()));
        pump();

        assertEquals(1, failures.size());
        assertEquals(List.of(), copies.calls);
        assertEquals("unsaved work", copies.open.get(p("mymod.proj:src/Main.java")));
    }

    // ── Events ──────────────────────────────────────────────────────────────────────────────────

    /** A view renders from these, so what they carry has to be enough to invalidate both ends of a move. */
    @Test
    public void aMoveReportsBothEnds() {
        List<String> seen = new ArrayList<>();
        service.onDidRun.connect(op -> seen.add(op.kind() + " " + op.source() + " -> " + op.target()));

        service.move(p("mymod.proj:README.md"), p("mymod.proj:docs.md"), false,
                () -> { }, f -> fail(f.code()));
        pump();

        assertEquals(List.of("MOVE mymod.proj:README.md -> mymod.proj:docs.md"), seen);
    }

    @Test
    public void aFailureIsReportedWithTheOperationThatFailed() {
        List<String> seen = new ArrayList<>();
        service.onDidFail.connect((op, failure) -> seen.add(op.kind() + " " + op.target()));

        service.create(p("mymod.proj:README.md"), "boom", () -> fail("created over an existing file"),
                f -> { });
        pump();

        assertEquals(List.of("CREATE mymod.proj:README.md"), seen);
    }

    // ── Naming ──────────────────────────────────────────────────────────────────────────────────

    /** VS Code's {@code explorer.incrementalNaming} in its {@code simple} default. */
    @Test
    public void incrementalNamingMatchesVsCodesSimpleMode() {
        assertEquals("Main.java", WorkspaceFileService.incrementalName("Main.java", List.of()));
        assertEquals("Main copy.java",
                WorkspaceFileService.incrementalName("Main.java", List.of("Main.java")));
        assertEquals("Main copy 2.java", WorkspaceFileService.incrementalName(
                "Main.java", List.of("Main.java", "Main copy.java")));
        assertEquals("Main copy 3.java", WorkspaceFileService.incrementalName(
                "Main.java", List.of("Main.java", "Main copy.java", "Main copy 2.java")));
    }

    /**
     * The suffix goes <b>before</b> the extension, and a dotfile has none.
     *
     * <p>The extension decides how a file opens, so {@code Main.java copy} is a plain-text file with a
     * confusing name. A leading dot is the whole name of a dotfile rather than an extension — the same
     * rule {@code LanguageRegistry} applies when it decides a language.</p>
     */
    @Test
    public void incrementalNamingKeepsTheExtensionAndUnderstandsDotfiles() {
        assertEquals("shader copy.glsl",
                WorkspaceFileService.incrementalName("shader.glsl", List.of("shader.glsl")));
        assertEquals("README copy",
                WorkspaceFileService.incrementalName("README", List.of("README")));
        assertEquals(".gitignore copy",
                WorkspaceFileService.incrementalName(".gitignore", List.of(".gitignore")));
    }
}
