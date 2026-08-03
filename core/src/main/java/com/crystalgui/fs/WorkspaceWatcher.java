package com.crystalgui.fs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notices when a watched file moves, by asking.
 *
 * <p>G3's <b>promptness</b> layer, and deliberately only that. Correctness never rests here: a stale write
 * is refused by the re-stat in {@link WorkspaceService#write} whatever this does or fails to do. Building
 * it the other way round — a conflict story that depends on a watcher firing — is the version that works
 * on a developer's machine and loses data on somebody's network mount.</p>
 *
 * <h3>Polling what is open, rather than watching a tree</h3>
 * <p>{@code WatchService} is quirky per platform and unreliable on network mounts, and watching a whole
 * project costs a stat per file for files nobody has open. A client says what it is looking at; this polls
 * exactly that. Bounded by the number of open editors, portable everywhere, and it needs no OS support at
 * all.</p>
 *
 * <p>A faster path can be layered under this later without changing anything above it, which is the point
 * of the split.</p>
 */
public final class WorkspaceWatcher {

    /** What a poll found. */
    public record Change(CgPath path, String kind, String etag) {

        public boolean isDeleted() {
            return WorkspaceProtocol.KIND_DELETED.equals(kind);
        }
    }

    private final WorkspaceService service;

    /** Watched path to the etag last reported. Insertion-ordered, so changes come out predictably. */
    private final Map<CgPath, String> watched = new LinkedHashMap<>();

    public WorkspaceWatcher(WorkspaceService service) {
        this.service = service;
    }

    /**
     * Begins watching, seeded with the file's current etag.
     *
     * <p>Seeded rather than started empty: an unseeded entry looks like a change on the very first poll,
     * and the client would be told the file it just opened had already moved.</p>
     */
    public void watch(WorkspaceActor actor, CgPath path) {
        try {
            watched.put(path, service.read(actor, path).etag());
        } catch (RuntimeException e) {
            // Unreadable now -- watch it anyway, so the client hears about it if it appears.
            watched.put(path, null);
        }
    }

    /**
     * Records an etag this side already knows about, so it is not reported as somebody else's change.
     *
     * <p>Called after a write that went through the server. Without it, a client's own save comes back as
     * a notification and the user is asked whether to reload their own work — which looks exactly like the
     * conflict the feature exists to report, so it would be believed.</p>
     *
     * <p>Only updates a path already being watched: this is not a way to start watching.</p>
     */
    public void noteWritten(CgPath path, String etag) {
        if (watched.containsKey(path)) watched.put(path, etag);
    }

    public void unwatch(CgPath path) {
        watched.remove(path);
    }

    public boolean isWatching(CgPath path) {
        return watched.containsKey(path);
    }

    public int size() {
        return watched.size();
    }

    /**
     * Everything that has moved since the last poll.
     *
     * <p>The reported etag is recorded, so a change is announced <b>once</b>. A watcher that re-reported
     * on every poll would put a client into a reload prompt it could not dismiss.</p>
     */
    public List<Change> poll(WorkspaceActor actor) {
        if (watched.isEmpty()) return List.of();

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<CgPath, String> entry : watched.entrySet()) {
            CgPath path = entry.getKey();
            String last = entry.getValue();
            String now;
            try {
                now = service.read(actor, path).etag();
            } catch (CgFileSystemException e) {
                if (e.getError() == CgFileError.FILE_NOT_FOUND && last != null) {
                    entry.setValue(null);
                    changes.add(new Change(path, WorkspaceProtocol.KIND_DELETED, null));
                }
                continue;
            }
            if (!now.equals(last)) {
                entry.setValue(now);
                changes.add(new Change(path, WorkspaceProtocol.KIND_MODIFIED, now));
            }
        }
        return changes;
    }
}
