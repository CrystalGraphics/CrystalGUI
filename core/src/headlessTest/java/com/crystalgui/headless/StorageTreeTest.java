package com.crystalgui.headless;

import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.core.storage.LocalConfigStorage;
import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Backup;
import com.crystalgui.fs.client.LocalHistory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/crystalgui/fs-rewrite/fs-storage-layout.md} — the {@code crystalgui/} tree.
 *
 * <p>A real directory rather than {@code InMemoryConfigStorage}, because the thing under test is
 * <em>nesting</em>: the in-memory store scopes by key prefix, so it would answer every question here
 * correctly and prove nothing about the layout on disk.</p>
 */
public class StorageTreeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static Resource file(String path) {
        return Resource.of(CgPath.parse(path));
    }

    /**
     * These two collide under {@code String.hashCode}, which is what named a backup before, and they
     * share a leaf name so the readable suffix could not tell them apart either.
     *
     * <p>{@code "Aa"} and {@code "BB"} is the textbook 32-bit collision; the polynomial makes it survive
     * any common prefix and suffix.</p>
     */
    private static final Resource COLLIDING_A = file("proj:Aa/Main.java");
    private static final Resource COLLIDING_B = file("proj:BB/Main.java");

    /** A config store over a real directory, reached the way a host reaches it. */
    private ConfigStorage rootStore() throws IOException {
        return new LocalConfigStorage(StorageLayout.configIn(folder.newFolder("install").toPath()));
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void scopingTwiceMakesTwoRealDirectories() throws IOException {
        Path root = folder.newFolder("install").toPath();
        ConfigStorage config = new LocalConfigStorage(StorageLayout.configIn(root));
        config.scoped(StorageLayout.PROJECTS).scoped("abc123").write("session.json", "{}");

        assertTrue("the record goes where the tree says",
                Files.isRegularFile(StorageLayout.configIn(root)
                        .resolve("projects").resolve("abc123").resolve("session.json")));
    }

    @Test
    public void aScopeIsNotVisibleToTheStoreAboveIt() throws IOException {
        ConfigStorage config = rootStore();
        config.write("settings.json", "{}");
        config.scoped(StorageLayout.PROJECTS).scoped("abc123").write("session.json", "{}");

        // list() skipping directories is what lets projects/ sit beside settings.json without the
        // settings store ever seeing it -- load-bearing now rather than incidental.
        assertEquals(List.of("settings.json"), config.list());
    }

    @Test
    public void aSlashedNameIsWrittenWhereListCannotFindIt() throws IOException {
        ConfigStorage config = rootStore();
        config.write("projects/abc123/session.json", "{}");

        // The trap S5 documents: write() creates parent directories, so this WORKS and is invisible.
        // Pinned so that anyone who reaches for a slashed name meets it here rather than in a bug
        // report about unrestorable unsaved work.
        assertTrue(Files.isRegularFile(config.directory()
                .resolve("projects").resolve("abc123").resolve("session.json")));
        assertFalse("list() does not recurse", config.list().contains("projects/abc123/session.json"));
    }

    @Test
    public void everyRootHasTheSameShape() {
        Path installation = folder.getRoot().toPath();
        Path world = installation.resolve("saves").resolve("New World");
        for (Path root : List.of(installation, world)) {
            assertEquals(root.resolve("crystalgui"), StorageLayout.rootIn(root));
            assertEquals(root.resolve("crystalgui").resolve("workspace-config"),
                    StorageLayout.configIn(root));
            assertEquals(root.resolve("crystalgui").resolve("cache"), StorageLayout.cacheIn(root));
            assertEquals(root.resolve("crystalgui").resolve("projects"),
                    StorageLayout.projectsIn(root));
        }
    }

    // ── Backups ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void twoResourcesThatCollidedUnderTheOldKeyNoLongerShareABackup() throws IOException {
        assertEquals("the pair must actually collide, or this test proves nothing",
                COLLIDING_A.toString().hashCode(), COLLIDING_B.toString().hashCode());

        ConfigStorage store = rootStore();
        Backup backup = new Backup(store);
        backup.save(COLLIDING_A, "first", "e1");
        backup.save(COLLIDING_B, "second", "e2");

        assertEquals("two files, not one overwriting the other", 2, store.list().size());
        assertEquals(2, backup.restorable().size());
        assertNotNull(backup.get(COLLIDING_A));
        assertEquals("first", new String(backup.get(COLLIDING_A).content(), StandardCharsets.UTF_8));
        assertEquals("second", new String(backup.get(COLLIDING_B).content(), StandardCharsets.UTF_8));
    }

    @Test
    public void everyFileInTheStoreIsABackup() throws IOException {
        ConfigStorage store = rootStore();
        Backup backup = new Backup(store);
        backup.save(file("proj:src/Main.java"), "unsaved", null);

        // No prefix filter any more -- the directory says what these are. A name that no longer starts
        // with "backup." must still be found, which is the half a prefix filter would silently drop.
        assertEquals(1, backup.restorable().size());
        backup.discardAll();
        assertTrue(store.list().isEmpty());
    }

    // ── History ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void historySweepsDownToTheFileCap() throws IOException {
        ConfigStorage store = rootStore();
        AtomicLong clock = new AtomicLong(1_000L);
        LocalHistory history = new LocalHistory(store, clock::get, 10, Long.MAX_VALUE, 3);

        for (int i = 0; i < 6; i++) {
            clock.addAndGet(1_000L);
            history.record(file("proj:src/File" + i + ".java"), ("v" + i).getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("three files kept, the three oldest gone", 3, store.list().size());
        // The newest three are the survivors, and they still read.
        for (int i = 3; i < 6; i++) {
            assertEquals("File" + i + " survived",
                    1, history.entriesOf(file("proj:src/File" + i + ".java")).size());
        }
        for (int i = 0; i < 3; i++) {
            assertTrue("File" + i + " was swept",
                    history.entriesOf(file("proj:src/File" + i + ".java")).isEmpty());
        }
    }

    @Test
    public void discardingBackupsDoesNotTouchHistory() throws IOException {
        // Both stores own everything in the store they are given -- discardAll() clears one, the file
        // sweep clears the other -- so they must never be handed the same one. Shared, a successful
        // save deleted the history it had just written, and the merge base with it.
        ConfigStorage workspaceStore = rootStore();
        Backup backup = new Backup(workspaceStore.scoped("backups"));
        LocalHistory history = new LocalHistory(workspaceStore.scoped("history"));
        Resource main = file("proj:src/Main.java");

        history.record(main, "saved".getBytes(StandardCharsets.UTF_8));
        backup.save(main, "unsaved", null);
        backup.discardAll();

        assertEquals("history survives a successful save", 1, history.entriesOf(main).size());
        assertNotNull("and so does the merge base", history.mergeBase(main));
    }

    @Test
    public void deletingTheCacheTreeLosesNothing() throws IOException {
        Path installation = folder.newFolder("install").toPath();
        ConfigStorage config = new LocalConfigStorage(StorageLayout.configIn(installation));
        ConfigStorage workspace = config.scoped(StorageLayout.PROJECTS).scoped("abc123");
        Backup backup = new Backup(workspace);
        backup.save(file("proj:src/Main.java"), "unsaved", "e1");
        config.write("settings.json", "{\"theme\":\"Crystal Dark\"}");

        // Something derived, in the sibling tree.
        Path cache = StorageLayout.cacheIn(installation).resolve("apps").resolve("crystalgui.editor");
        Files.createDirectories(cache);
        Files.write(cache.resolve("Compiled.class"), new byte[] {1, 2, 3});

        deleteTree(StorageLayout.cacheIn(installation));

        // The operational claim the sibling split exists to make.
        assertEquals(1, backup.restorable().size());
        assertEquals("{\"theme\":\"Crystal Dark\"}", config.read("settings.json"));
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
            });
        }
    }
}
