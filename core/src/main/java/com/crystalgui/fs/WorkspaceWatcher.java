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
            watched.put(path, service.stat(actor, path).etag());
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
     *
     * <h3>{@code stat}, never {@code read} — Phase 6.1</h3>
     *
     * <p>This asked {@link WorkspaceService#read} for its etag, which <b>reads the whole file</b>. An
     * etag comes from size and mtime — {@code WorkspaceService.read} says so itself, <i>"the etag comes
     * from the stat"</i> — so every byte of every watched file was loaded and discarded, twice a second,
     * per peer, with {@code MAX_FILE_BYTES} at 100 MB. Ten open files was twenty whole-file reads a
     * second per player.</p>
     *
     * <p>It also got worse rather than better on 2026-08-22: the editor stopped pausing the integrated
     * server, so that I/O now runs <em>while the world ticks</em> instead of while it is frozen.</p>
     *
     * <p><b>And this poll is not going away.</b> A real filesystem watcher cannot be trusted to be
     * complete — {@code OVERFLOW} drops events by design once a key's queue fills, and macOS's
     * {@code WatchService} is itself a poll that misses changes faster than its interval — so the
     * documented recovery is a re-scan, and this is it. @see plan_phase6.md §6.2</p>
     */
    /**
     * Turns a batch of filesystem events into changes for <b>this</b> peer — Phase 6.2.
     *
     * <p>The batch is drained once by {@link WorkspaceService} and handed to every peer, because draining
     * is destructive and a second caller would steal the first one's events.</p>
     *
     * <p>Only paths this peer is watching produce a change: an event about a file nobody here has open is
     * real and none of this peer's business, and telling it would leak which files exist to somebody who
     * never asked for them.</p>
     *
     * <p><b>An {@code OVERFLOW} falls through to the full {@link #poll}</b>, which is the whole reason the
     * etag poll survives a real watcher. Events are dropped by design once a key's queue fills, and a
     * re-scan is the only recovery the OS documents — so a consumer that ignored it would report most
     * changes, which looks like working.</p>
     */
    public List<Change> pollEvents(WorkspaceActor actor, List<CgFileEvent> events) {
        if (events.isEmpty() || watched.isEmpty()) return List.of();

        for (CgFileEvent event : events) {
            if (event.isOverflow()) return poll(actor);
        }

        List<Change> changes = new ArrayList<>();
        for (CgFileEvent event : events) {
            CgPath path = event.path();
            if (path == null || !watched.containsKey(path)) continue;
            Change change = recheck(actor, path);
            if (change != null) changes.add(change);
        }
        return changes;
    }

    /**
     * Re-stats one path and reports it if its etag moved. {@code null} when nothing actually changed.
     *
     * <p>The etag is still the arbiter even when an event prompted the look. An {@code ENTRY_MODIFY}
     * fires for a touch that changed no bytes, and a save is often several events — truncate, write,
     * rename into place — so trusting the event alone would report one save three times.</p>
     */
    private Change recheck(WorkspaceActor actor, CgPath path) {
        String last = watched.get(path);
        try {
            String now = service.stat(actor, path).etag();
            if (now.equals(last)) return null;
            watched.put(path, now);
            return new Change(path, WorkspaceProtocol.KIND_MODIFIED, now);
        } catch (CgFileSystemException gone) {
            if (gone.getError() != CgFileError.FILE_NOT_FOUND || last == null) return null;
            watched.put(path, null);
            return new Change(path, WorkspaceProtocol.KIND_DELETED, null);
        }
    }

    public List<Change> poll(WorkspaceActor actor) {
        if (watched.isEmpty()) return List.of();

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<CgPath, String> entry : watched.entrySet()) {
            CgPath path = entry.getKey();
            String last = entry.getValue();
            String now;
            try {
                now = service.stat(actor, path).etag();
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
