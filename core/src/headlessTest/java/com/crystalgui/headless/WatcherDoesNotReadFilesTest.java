package com.crystalgui.headless;

import com.crystalgui.fs.CgFileCapability;
import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileSystem;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.fs.WorkspaceWatcher;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6 <b>6.1</b> — watching a file must not read it.
 *
 * <p>{@link WorkspaceWatcher} asked {@code WorkspaceService.read} for an etag, which loads the whole file.
 * An etag is derived from size and mtime — {@code WorkspaceService.read}'s own comment says <i>"the etag
 * comes from the stat"</i> — so every byte of every watched file was read and discarded, <b>twice a second,
 * per peer</b>, with {@code MAX_FILE_BYTES} at 100 MB. Ten open files was twenty whole-file reads a second
 * per player, and after the editor stopped pausing the integrated server that I/O runs while the world
 * ticks.</p>
 *
 * <p><b>Asserted by counting reads, not by timing.</b> A throughput assertion passes whether or not the
 * file is read — the whole point is that the work was invisible.</p>
 */
public class WatcherDoesNotReadFilesTest {

    /** Counts what actually reaches the filesystem. Everything else delegates. */
    private static final class Counting implements CgFileSystem {
        private final CgFileSystem delegate;
        int reads;
        int stats;

        Counting(CgFileSystem delegate) {
            this.delegate = delegate;
        }

        @Override
        public CgFileEntry stat(CgPath path) {
            stats++;
            return delegate.stat(path);
        }

        @Override
        public byte[] read(CgPath path) {
            reads++;
            return delegate.read(path);
        }

        @Override
        public List<CgFileEntry> list(CgPath directory) {
            return delegate.list(directory);
        }

        @Override
        public void write(CgPath path, byte[] content, boolean create, boolean overwrite) {
            delegate.write(path, content, create, overwrite);
        }

        @Override
        public void mkdir(CgPath path) {
            delegate.mkdir(path);
        }

        @Override
        public void delete(CgPath path, boolean recursive) {
            delegate.delete(path, recursive);
        }

        @Override
        public void rename(CgPath from, CgPath to, boolean overwrite) {
            delegate.rename(from, to, overwrite);
        }

        @Override
        public Set<CgFileCapability> capabilities() {
            return delegate.capabilities();
        }
    }

    private static final CgPath BIG = CgPath.parse("mymod.proj:Big.java");

    private Counting files;
    private WorkspaceService service;
    private WorkspaceWatcher watcher;

    @Before
    public void setUp() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 20_000; i++) content.append("// a line that costs something to read\n");

        files = new Counting(new InMemoryFileSystem()
                .seed("mymod.proj:Big.java", content.toString())
                .seed("mymod.proj:Other.java", "class Other {}"));

        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Mod", Paths.get("/srv/mymod"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        watcher = new WorkspaceWatcher(service);
    }

    /** Beginning to watch is a stat. */
    @Test
    public void watchingSeedsWithoutReading() {
        int before = files.reads;
        watcher.watch(WorkspaceActor.LOCAL, BIG);

        assertEquals("seeding a watch must not read the file", before, files.reads);
        assertTrue("and must have stat-ed it", files.stats > 0);
    }

    /**
     * And neither does polling, however many times.
     *
     * <p>The number that mattered: this ran every 0.5 s per peer, so the cost was not one read but a
     * read per file per poll, forever, for as long as the file was open.</p>
     */
    @Test
    public void pollingNeverReadsTheFile() {
        watcher.watch(WorkspaceActor.LOCAL, BIG);
        int before = files.reads;

        for (int i = 0; i < 50; i++) watcher.poll(WorkspaceActor.LOCAL);

        assertEquals("fifty polls must not read the file once", before, files.reads);
    }

    /** It still reports a change, which is the thing it exists to do. */
    @Test
    public void aChangeIsStillReported() {
        watcher.watch(WorkspaceActor.LOCAL, BIG);
        assertTrue("nothing has changed yet", watcher.poll(WorkspaceActor.LOCAL).isEmpty());

        service.write(WorkspaceActor.LOCAL, BIG, "changed".getBytes(), null);

        List<WorkspaceWatcher.Change> changes = watcher.poll(WorkspaceActor.LOCAL);
        assertEquals(1, changes.size());
        assertEquals(BIG, changes.get(0).path());
    }

    /** And a deletion, which takes a different branch. */
    @Test
    public void aDeletionIsStillReported() {
        watcher.watch(WorkspaceActor.LOCAL, BIG);
        watcher.poll(WorkspaceActor.LOCAL);

        service.delete(WorkspaceActor.LOCAL, BIG, false);

        List<WorkspaceWatcher.Change> changes = watcher.poll(WorkspaceActor.LOCAL);
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).isDeleted());
    }
}
