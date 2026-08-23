package com.crystalgui.headless;

import com.crystalgui.fs.CgFileEvent;
import com.crystalgui.fs.CgFileEventSource;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.NioFileEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 6 <b>6.2</b> — the OS-level watcher, against a real directory.
 *
 * <p>Against real files on a real filesystem, because that is the entire subject: a fake would only
 * assert that the fake works. The cost is that this is the one test here with a <b>timeout</b> in it,
 * since the platforms differ by an order of magnitude in how promptly they report — inotify and
 * {@code ReadDirectoryChangesW} are immediate, while macOS's {@code WatchService} is a poll and takes
 * seconds. The waits are therefore generous and the assertions are about <em>what</em> arrives, never
 * <em>how fast</em>.</p>
 */
public class NioFileEventSourceTest {

    private static final String PROJECT = "mymod.proj";

    /** Generous: macOS's PollingWatchService reports on its own schedule, not ours. */
    private static final long WAIT_MILLIS = 20_000L;

    private Path root;
    private CgFileEventSource source;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("cgui-watch-");
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/Main.java"), "class Main {}".getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() throws IOException {
        if (source != null) source.close();
        if (root != null && Files.exists(root)) {
            Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                            // A temp directory that will not delete is the OS's business, not the test's.
                        }
                    });
        }
    }

    private void open(String... excludes) {
        source = NioFileEventSource.open(PROJECT, root, java.util.Arrays.asList(excludes));
    }

    /** Everything drained so far. @see #await */
    private final List<CgFileEvent> seen = new ArrayList<>();

    /**
     * Drains until {@code path} shows up with {@code kind}, or gives up.
     *
     * <p>Returns rather than asserting so a caller can express "and this one must NOT arrive".</p>
     *
     * <p><b>Accumulates rather than consuming</b>, and the first version did not — which failed the move
     * test against a perfectly correct watcher. A rename arrives as a DELETE and a CREATE, usually in the
     * SAME drain; a helper that returned on the first match threw the rest of that batch away, so the
     * second assertion had nothing left to find. A destructive read is fine for one assertion and wrong
     * the moment a test makes two.</p>
     */
    private boolean await(CgFileEvent.Kind kind, CgPath path) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (true) {
            for (CgFileEvent event : seen) {
                if (event.kind() == kind && path.equals(event.path())) return true;
            }
            if (System.currentTimeMillis() >= deadline) return false;
            seen.addAll(source.drain());
            sleep(25);
        }
    }

    /** Everything seen so far plus anything arriving in the next {@code millis}. */
    private List<CgFileEvent> drainFor(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            seen.addAll(source.drain());
            sleep(25);
        }
        return new ArrayList<>(seen);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    // ── The four things a watcher is for ────────────────────────────────────────────────────────

    @Test
    public void aCreatedFileIsReported() throws IOException {
        open();
        Files.write(root.resolve("src/New.java"), "x".getBytes(StandardCharsets.UTF_8));

        assertTrue("a file created outside the editor must be reported",
                await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/New.java")));
    }

    @Test
    public void aModifiedFileIsReported() throws IOException {
        open();
        Files.write(root.resolve("src/Main.java"), "class Main { int x; }".getBytes(StandardCharsets.UTF_8));

        assertTrue("an external save must be reported",
                await(CgFileEvent.Kind.MODIFIED, CgPath.of(PROJECT, "src/Main.java")));
    }

    @Test
    public void aDeletedFileIsReported() throws IOException {
        open();
        Files.delete(root.resolve("src/Main.java"));

        assertTrue("a file deleted outside the editor must be reported",
                await(CgFileEvent.Kind.DELETED, CgPath.of(PROJECT, "src/Main.java")));
    }

    /**
     * A move is a delete and a create, and that is the honest reading.
     *
     * <p>The OS reports a rename within a watched tree as two events on two directories, and there is no
     * portable identity linking them. Pretending otherwise would mean inventing a MOVED event that is
     * right on one platform and a guess on the others.</p>
     */
    @Test
    public void aMoveIsReportedAsBothHalves() throws IOException {
        open();
        Files.move(root.resolve("src/Main.java"), root.resolve("src/Renamed.java"));

        assertTrue("the old name must go", await(CgFileEvent.Kind.DELETED, CgPath.of(PROJECT, "src/Main.java")));
        assertTrue("and the new one arrive", await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/Renamed.java")));
    }

    // ── The parts the prior art warned about ────────────────────────────────────────────────────

    /**
     * A directory created after opening is watched too.
     *
     * <p>{@code WatchService} is <b>not recursive</b>, so without following new directories a folder
     * created and then filled would have its contents appear from nowhere — or never, since nothing
     * would be watching it. The failure is silent and permanent, which is the worst combination.</p>
     */
    @Test
    public void aDirectoryCreatedLaterIsFollowed() throws IOException {
        open();
        Path nested = root.resolve("src/deep");
        Files.createDirectories(nested);
        assertTrue("the directory itself", await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/deep")));

        Files.write(nested.resolve("Inner.java"), "y".getBytes(StandardCharsets.UTF_8));
        assertTrue("and a file inside it, which needs the new directory to have been registered",
                await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/deep/Inner.java")));
    }

    /**
     * Excludes are honoured, and they are not cosmetic.
     *
     * <p>Linux caps watches per user at 8,192 by default, so one unexcluded dependency directory can
     * exhaust the limit for every process the user owns. The same list the manifest already filters on
     * is used here, or a file would be invisible to a listing and still report changes.</p>
     */
    @Test
    public void anExcludedDirectoryIsNotWatched() throws IOException {
        Files.createDirectories(root.resolve("node_modules/left-pad"));
        open("node_modules");

        Files.write(root.resolve("node_modules/left-pad/index.js"),
                "module.exports = 1;".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("src/Watched.java"), "z".getBytes(StandardCharsets.UTF_8));

        // The watched write is the SYNCHRONISER: once it has arrived, anything from the excluded tree
        // that was going to arrive has had at least as long.
        assertTrue(await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/Watched.java")));

        for (CgFileEvent event : drainFor(200)) {
            if (event.path() != null && event.path().toString().contains("node_modules")) {
                fail("an excluded directory must not be watched: " + event);
            }
        }
    }

    /** Nothing outside the project can be addressed, so nothing outside it is reported. */
    @Test
    public void eventsAreScopedToTheProject() throws IOException {
        open();
        Files.write(root.resolve("src/Inside.java"), "a".getBytes(StandardCharsets.UTF_8));
        assertTrue(await(CgFileEvent.Kind.CREATED, CgPath.of(PROJECT, "src/Inside.java")));

        for (CgFileEvent event : drainFor(100)) {
            if (event.path() == null) continue;
            assertEquals("every event belongs to this project", PROJECT, event.path().project());
        }
    }

    /** Closing twice is not a failure, and a closed source is quiet rather than broken. */
    @Test
    public void closingIsIdempotentAndSilencesIt() throws IOException {
        open();
        source.close();
        source.close();

        Files.write(root.resolve("src/After.java"), "b".getBytes(StandardCharsets.UTF_8));
        assertTrue("a closed source reports nothing rather than throwing", source.drain().isEmpty());
    }

    /**
     * A root that cannot be watched degrades rather than throwing.
     *
     * <p>A workspace with no watcher still works, one poll interval behind. Refusing to open the editor
     * over it would be a far worse answer than being slightly late.</p>
     */
    @Test
    public void anImpossibleRootFallsBackRatherThanThrowing() {
        CgFileEventSource missing = NioFileEventSource.open(
                PROJECT, root.resolve("does-not-exist"), Collections.emptyList());

        assertTrue("must answer something usable", missing.drain().isEmpty());
        assertFalse("and must not pretend it is watching",
                missing instanceof NioFileEventSource
                        && ((NioFileEventSource) missing).watchedDirectories() > 0);
        missing.close();
    }
}
