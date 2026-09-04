package com.crystalgui.fs.provider;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.project.Excludes;
import com.crystalgui.core.CrystalGuiCore;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link CgFileEvent.Source} over Java's {@link WatchService} — Phase 6.2.
 *
 * <h3>Why this one and not a native library</h3>
 *
 * <p>Faster watchers exist and they all ship platform binaries. This is <b>server-side by definition</b>,
 * and {@code language/} exists precisely so that tree-sitter's natives stay off a dedicated server; a
 * watcher may not reintroduce that problem pointing the other way. {@code java.nio} is on every JVM this
 * runs on, including 1.7.10's Java 8, and needs no packaging at all.</p>
 *
 * <h3>What it cannot do, which is the part that shapes every caller</h3>
 *
 * <ul>
 *   <li><b>It is not recursive.</b> {@code WatchService} watches one directory. A tree means registering
 *       every directory in it and registering new ones as they appear — which is what
 *       {@link #registerTree} does, and why a created directory is followed immediately rather than at
 *       the next re-scan.</li>
 *   <li><b>It drops events under load.</b> {@code OVERFLOW} once a key's queue fills — 512 by default on
 *       Linux. Surfaced as {@link CgFileEvent#overflow()} rather than swallowed, because the only correct
 *       recovery is a re-scan by somebody who knows what they are watching.</li>
 *   <li><b>On macOS it is a poll.</b> The JDK still ships {@code PollingWatchService} there — there is no
 *       POSIX equivalent of inotify, and
 *       <a href="https://bugs.openjdk.org/browse/JDK-8293067">JDK-8293067</a> to implement it on FSEvents
 *       is open. So on that platform this has real latency and <b>misses changes faster than its
 *       interval</b>. That is not a reason to avoid it; it is a reason the etag poll is not optional.</li>
 *   <li><b>Linux caps watches per user</b> — 8,192 by default. Which is why {@code excludes} is a
 *       constructor argument rather than a later refinement: one unexcluded {@code node_modules} can
 *       exhaust the limit for every process the user owns.</li>
 * </ul>
 *
 * <h3>Polled, not threaded</h3>
 *
 * <p>{@link #drain()} calls {@code WatchService.poll()}, which returns immediately. No thread is created,
 * so nothing this owns can reach a consumer on a thread the consumer does not expect — see
 * {@link CgFileEvent.Source}'s note on why that matters here specifically. The cost is that latency is
 * bounded below by the caller's tick, which for a 20 Hz server is 50 ms and far better than the 500 ms
 * the etag poll gives.</p>
 */
public final class NioFileEventSource implements CgFileEvent.Source {

    private final String project;
    private final Path root;
    private final Excludes excludes;
    private final WatchService service;

    /** Every directory currently registered, so an event can be turned back into a {@link CgPath}. */
    private final Map<WatchKey, Path> registered = new HashMap<>();

    private boolean closed;

    private NioFileEventSource(String project, Path root, Excludes excludes, WatchService service) {
        this.project = project;
        this.root = root;
        this.excludes = excludes;
        this.service = service;
    }

    /**
     * Watches {@code root} and everything under it.
     *
     * @param project  the project id, so events come back as {@link CgPath}s rather than OS paths
     * @param root     the project's directory on disk
     * @param excludes name patterns never to watch — {@link WorkspaceProject#excludes()}
     * @return a live source, or {@link CgFileEvent.Source#NONE} if the platform refused. <b>Never
     *         throws:</b> a workspace that cannot be watched still works, one poll interval behind, and
     *         failing to open the editor over it would be a far worse answer
     */
    public static CgFileEvent.Source open(String project, Path root, List<String> excludes) {
        try {
            WatchService service = root.getFileSystem().newWatchService();
            NioFileEventSource source =
                    new NioFileEventSource(project, root.toAbsolutePath().normalize(),
                            Excludes.of(excludes), service);
            source.registerTree(source.root);
            CrystalGuiCore.LOGGER.info("[cgui-fs] watching {} ({} director{})",
                    root, source.registered.size(), source.registered.size() == 1 ? "y" : "ies");
            return source;
        } catch (IOException | RuntimeException refused) {
            // SAID OUT LOUD. "Watching" and "not watching" are indistinguishable from the outside until
            // somebody edits a file externally and nothing happens, which is a bad moment to find out.
            CrystalGuiCore.LOGGER.warn("[cgui-fs] cannot watch {} ({}) — falling back to the etag poll",
                    root, refused.toString());
            return CgFileEvent.Source.NONE;
        }
    }

    @Override
    public List<CgFileEvent> drain() {
        if (closed) return java.util.Collections.emptyList();

        List<CgFileEvent> events = new ArrayList<>();
        WatchKey key;
        // poll(), not take(): this runs on the caller's tick and must never be the thing that waits.
        while ((key = service.poll()) != null) {
            Path directory = registered.get(key);
            for (WatchEvent<?> raw : key.pollEvents()) {
                if (raw.kind() == StandardWatchEventKinds.OVERFLOW) {
                    events.add(CgFileEvent.overflow());
                    continue;
                }
                if (directory == null) continue;   // a key we have already forgotten

                Path child = directory.resolve((Path) raw.context());
                CgPath path = toCgPath(child);
                if (path == null || isExcluded(child)) continue;

                if (raw.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    events.add(CgFileEvent.of(CgFileEvent.Kind.CREATED, path));
                    // A NEW DIRECTORY IS FOLLOWED NOW, not at the next re-scan. WatchService is not
                    // recursive, so a directory created and filled between two drains would otherwise
                    // have every file in it appear from nowhere -- or never, since nothing watches it.
                    if (Files.isDirectory(child)) registerTree(child);
                } else if (raw.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                    events.add(CgFileEvent.of(CgFileEvent.Kind.DELETED, path));
                } else if (raw.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                    events.add(CgFileEvent.of(CgFileEvent.Kind.MODIFIED, path));
                }
            }
            // RESET, or this key never reports again. A key whose directory has gone answers false, and
            // is dropped rather than re-registered.
            if (!key.reset()) registered.remove(key);
        }
        return events;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        registered.clear();
        try {
            service.close();
        } catch (IOException ignored) {
            // Closing a watch service that is already gone is not a failure anybody can act on.
        }
    }

    /** How many directories are registered. Diagnostics, and what an exhausted watch limit shows up in. */
    public int watchedDirectories() {
        return registered.size();
    }

    // ── Registration ────────────────────────────────────────────────────────────────────────────

    /**
     * Registers {@code start} and every directory beneath it.
     *
     * <p>Failures are skipped rather than propagated: a directory that vanished mid-walk, or one the
     * server may not read, must not stop the rest of the tree being watched.</p>
     */
    private void registerTree(Path start) {
        try {
            Files.walkFileTree(start, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (isExcluded(directory)) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        WatchKey key = directory.register(service,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                        registered.put(key, directory);
                    } catch (IOException | RuntimeException skip) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException walkFailed) {
            CrystalGuiCore.LOGGER.warn("[cgui-fs] could not walk {}: {}", start, walkFailed.getMessage());
        }
    }

    // ── Paths ───────────────────────────────────────────────────────────────────────────────────

    /** {@code null} when the file is outside the project, which an event never should be. */
    private CgPath toCgPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) return null;
        Path relative = root.relativize(absolute);
        if (relative.getNameCount() == 0) return null;

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) joined.append('/');
            joined.append(relative.getName(i).toString());
        }
        try {
            return CgPath.of(project, joined.toString());
        } catch (RuntimeException notAPath) {
            // A name this workspace cannot represent -- a NUL, a reserved character. Dropping it is
            // right: nothing downstream could have addressed it anyway.
            return null;
        }
    }

    /**
     * The same rule the manifest honours, because it is now literally the same object.
     *
     * <p>This javadoc used to claim that already, and the method below it matched a <b>leading star
     * only</b> while {@code WorkspaceService} matched {@code *} and {@code ?} anywhere. So a project
     * excluding {@code build/*.class} filtered its listings and watched every one of those files, and a
     * client was told about changes to files it could not see. {@link Excludes} is the one matcher.</p>
     */
    private boolean isExcluded(Path file) {
        if (excludes.isEmpty()) return false;
        Path name = file.getFileName();
        return name != null && excludes.excludes(name.toString());
    }
}
