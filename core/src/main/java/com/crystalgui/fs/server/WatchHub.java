package com.crystalgui.fs.server;

import com.crystalgui.fs.provider.CgFileEntry;
import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.provider.CgFileEvent;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.protocol.FsMessages;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Who is watching what, for the whole workspace — <b>one hub, not one watcher per peer</b>.
 *
 * <pre>{@code
 * hub.watch(peer, actor, folder, true);              // a subscription
 * Map<Object, List<FileChange>> out = hub.tick(actor, service.drainFileEvents());
 * out = hub.poll(actor);                             // the reconciling rescan
 * }</pre>
 *
 * <p>A host drains the filesystem's events once a tick and hands them here; the hub answers a list per
 * peer, and a peer with nothing to hear about is absent from the map rather than present with an empty
 * list. What it does on the way:</p>
 *
 * <ul>
 *   <li><b>Stats a path once</b> however many peers watch it — the cost is per file, not per peer.</li>
 *   <li><b>Coalesces per path</b>, so a save that raised three events (truncate, write, rename into
 *       place) is one change rather than three reloads on the far side.</li>
 *   <li><b>Pairs a deletion and a creation carrying one etag into a rename</b>, which is the only way
 *       to get one out of a filesystem watcher: NIO, {@code inotify} and
 *       {@code ReadDirectoryChangesW} all report the two halves separately.</li>
 *   <li><b>Watches directories</b>, recursively or not, so another client's create inside a folder you
 *       have expanded reaches you.</li>
 * </ul>
 *
 * <h3>The etag poll is the reconciliation, not a fallback</h3>
 *
 * <p>Every OS primitive underneath drops events under load — a {@code WatchKey} raises OVERFLOW once
 * its queue fills, and macOS's {@code WatchService} is itself a poll. The documented recovery is a
 * re-scan, so the poll is the reconciliation rather than the mechanism, and an OVERFLOW falls straight
 * through to it.</p>
 */
public final class WatchHub {

    /**
     * How many paths one peer may subscribe to.
     *
     * <p>A subscription costs a map entry here and a stat per poll, so an unbounded one is a peer
     * making the server do arbitrary work. Generous enough that no honest editor reaches it — VS Code
     * with a large project open watches folders, not files, and a folder is one entry.</p>
     */
    public static final int MAX_SUBSCRIPTIONS_PER_PEER = 512;

    private final WorkspaceService service;

    /** Per peer: what it asked for. */
    private final Map<Object, Map<CgPath, Subscription>> byPeer = new LinkedHashMap<>();

    /**
     * The etag each watched FILE last had, shared by every peer.
     *
     * <p>The hub's, not a peer's, so two peers watching one file cost one stat between them. A peer
     * that subscribes later is seeded from here rather than re-stat-ing.</p>
     */
    private final Map<CgPath, String> lastEtag = new LinkedHashMap<>();

    public WatchHub(WorkspaceService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    // ── Subscribing ─────────────────────────────────────────────────────────────────────────────

    /**
     * @param recursive whether descendants of a directory count. A file subscription ignores it
     * @throws CgFileSystemException {@link CgFileError#NO_PERMISSIONS} when the peer is over its cap
     */
    public Subscription watch(Object peer, WorkspaceActor actor, CgPath path, boolean recursive) {
        Map<CgPath, Subscription> mine = byPeer.computeIfAbsent(peer, key -> new LinkedHashMap<>());
        Subscription existing = mine.get(path);
        if (existing != null && existing.recursive() == recursive) return existing;
        if (existing == null && mine.size() >= MAX_SUBSCRIPTIONS_PER_PEER) {
            throw new CgFileSystemException(CgFileError.NO_PERMISSIONS,
                    "this connection is watching " + mine.size() + " paths, which is the limit");
        }
        Subscription subscription = new Subscription(path, recursive);
        mine.put(path, subscription);
        // Seeded so the first poll does not report every watched file as changed. A DIRECTORY has no
        // etag worth holding -- what changes inside it is what matters, and those are found by event.
        if (!lastEtag.containsKey(path)) {
            try {
                CgFileEntry entry = service.stat(actor, path);
                if (!entry.isDirectory()) lastEtag.put(path, entry.etag());
                else if (recursive) prime(actor, path, 0);
            } catch (CgFileSystemException absent) {
                // Watching something that is not there yet is legitimate -- a file about to be created.
                lastEtag.put(path, null);
            }
        }
        return subscription;
    }

    /** How deep a priming walk goes, and how many files it will hold. */
    private static final int MAX_PRIME_DEPTH = 16;
    private static final int MAX_PRIMED_FILES = 10_000;

    /**
     * Stat-s a watched tree once, so a change to a file nobody has open is something this hub can see.
     *
     * <p>Without it {@link #lastEtag} holds only what a client OPENED, and the rescan re-stats exactly
     * that — which left a recursive watch reporting creations (they need no history) while a rename
     * could not pair at all, since pairing matches the etag the vanished half <em>used to</em> have and
     * nobody had ever taken it. So a rename of a file nobody had open arrived as a delete and a create,
     * and the backup and history filed under it did not follow.</p>
     *
     * <p><b>Bounded, and through {@code manifest} rather than the raw filesystem</b>, so the project's
     * own excludes apply and a walk never descends into a build directory. A tree past the cap is left
     * to events alone, which is the arrangement the reconcile is a backstop for anyway.</p>
     */
    private void prime(WorkspaceActor actor, CgPath directory, int depth) {
        if (depth > MAX_PRIME_DEPTH || lastEtag.size() >= MAX_PRIMED_FILES) return;
        List<CgFileEntry> entries;
        try {
            entries = service.manifest(actor, directory);
        } catch (RuntimeException unreadable) {
            // Priming is an optimisation and never worth failing a watch for: a directory this actor
            // may not read, or one that went away mid-walk, simply is not primed.
            return;
        }
        for (CgFileEntry entry : entries) {
            CgPath child = directory.resolve(entry.name());
            if (entry.isDirectory()) prime(actor, child, depth + 1);
            else lastEtag.putIfAbsent(child, entry.etag());
        }
    }

    public void unwatch(Object peer, CgPath path) {
        Map<CgPath, Subscription> mine = byPeer.get(peer);
        if (mine == null) return;
        mine.remove(path);
        if (mine.isEmpty()) byPeer.remove(peer);
        forgetUnwatched(path);
    }

    /** A peer disconnected. Everything it was watching goes with it. */
    public void forget(Object peer) {
        Map<CgPath, Subscription> mine = byPeer.remove(peer);
        if (mine == null) return;
        for (CgPath path : new ArrayList<>(mine.keySet())) forgetUnwatched(path);
    }

    public int subscriptionCount(Object peer) {
        Map<CgPath, Subscription> mine = byPeer.get(peer);
        return mine == null ? 0 : mine.size();
    }

    /** Drops the shared etag once nobody is watching that path, so the map cannot grow for ever. */
    private void forgetUnwatched(CgPath path) {
        for (Map<CgPath, Subscription> mine : byPeer.values()) {
            if (mine.containsKey(path)) return;
        }
        lastEtag.remove(path);
    }

    // ── The tick ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>One tick's worth of changes, per peer.</b>
     *
     * <p>Called once with the batch {@code WorkspaceService.drainFileEvents} produced — draining is
     * destructive, so a second caller would steal the first one's events, which is why the hub takes
     * the batch rather than draining it itself.</p>
     *
     * <p>Every path is stat-ed at most once here whatever the number of peers, and every peer gets one
     * list. A peer with nothing to hear about is absent from the answer rather than present with an
     * empty list, so a caller can send only to peers with something to say.</p>
     */
    public Map<Object, List<FsMessages.FileChange>> tick(WorkspaceActor actor,
                                                         List<CgFileEvent> events) {
        Map<CgPath, FsMessages.FileChange> coalesced = new LinkedHashMap<>();

        boolean overflowed = false;
        for (CgFileEvent event : events) {
            if (event.isOverflow()) {
                overflowed = true;
                break;
            }
        }

        if (overflowed) {
            // EVENTS WERE LOST, so nothing in this batch can be trusted to be the whole story. The
            // documented recovery is a re-scan and this is it -- the same reason the poll exists at all.
            rescan(actor, coalesced);
        } else {
            for (CgFileEvent event : events) {
                CgPath path = event.path();
                if (path == null || !anybodyWatches(path)) continue;
                FsMessages.FileChange change = recheck(actor, path, event.kind());
                // COALESCED PER PATH: a save is often several events -- truncate, write, rename into
                // place -- and reporting each would make one save three reloads on the far side.
                if (change != null) coalesced.put(path, change);
            }
        }

        pairRenames(coalesced);

        // STATED LAST AND AUTHORITATIVE. The server performed these and knows exactly what happened,
        // where the watcher infers it from bytes moving -- and only these carry a name.
        for (Stated said : stated) {
            coalesced.put(CgPath.parse(said.change().path()), said.change());
        }
        stated.clear();
        // Consumed per tick. A deletion whose partner never arrived was a deletion, and holding its
        // etag any longer would let a create minutes later be reported as a rename of it.
        etagBefore.clear();
        renamedAway.entrySet().removeIf(each -> each.setValue(each.getValue() - 1) <= 1);
        if (coalesced.isEmpty()) return Map.of();

        Map<Object, List<FsMessages.FileChange>> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Map<CgPath, Subscription>> peer : byPeer.entrySet()) {
            List<FsMessages.FileChange> mine = new ArrayList<>();
            for (FsMessages.FileChange change : coalesced.values()) {
                // INCLUDING WHOEVER ASKED. Withholding it looked right -- they already know -- and was
                // wrong twice over: nothing else updates that client's own tree, so a folder they moved
                // a file into never listed it, and their own open tab never retargeted. The two harms
                // withholding was for are handled where they arise instead: a reload is skipped when
                // the etag already matches, and a notification is skipped when the author is you.
                if (covers(peer.getValue().values(), CgPath.parse(change.path()))) mine.add(change);
            }
            if (!mine.isEmpty()) out.put(peer.getKey(), mine);
        }
        return out;
    }

    /**
     * The reconciliation: re-stat everything watched and report what moved.
     *
     * <p>Once per file over the union of every peer's subscriptions, so the cost is the number of
     * watched files rather than that times the number of peers.</p>
     */
    public Map<Object, List<FsMessages.FileChange>> poll(WorkspaceActor actor) {
        Map<CgPath, FsMessages.FileChange> found = new LinkedHashMap<>();
        rescan(actor, found);
        if (found.isEmpty()) return Map.of();

        Map<Object, List<FsMessages.FileChange>> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Map<CgPath, Subscription>> peer : byPeer.entrySet()) {
            List<FsMessages.FileChange> mine = new ArrayList<>();
            for (FsMessages.FileChange change : found.values()) {
                if (covers(peer.getValue().values(), CgPath.parse(change.path()))) mine.add(change);
            }
            if (!mine.isEmpty()) out.put(peer.getKey(), mine);
        }
        return out;
    }

    private void rescan(WorkspaceActor actor, Map<CgPath, FsMessages.FileChange> into) {
        for (CgPath path : new ArrayList<>(lastEtag.keySet())) {
            FsMessages.FileChange change = recheck(actor, path, null);
            if (change != null) into.put(path, change);
        }
    }

    /**
     * Re-stats one path and reports it if its etag moved.
     *
     * <p><b>The etag is the arbiter even when an event prompted the look.</b> An {@code ENTRY_MODIFY}
     * fires for a touch that changed no bytes, so trusting the event alone reports changes that did not
     * happen — and a client that reloads on those loses an unsaved buffer to a file that is identical.
     * The kind is consulted for one thing only: a DELETED event is trusted on its own, because the
     * file is gone and there is nothing left to arbitrate.</p>
     */
    @Nullable
    private FsMessages.FileChange recheck(WorkspaceActor actor, CgPath path,
                                          @Nullable CgFileEvent.Kind hint) {
        boolean known = lastEtag.containsKey(path);
        String last = lastEtag.get(path);
        try {
            CgFileEntry entry = service.stat(actor, path);
            if (entry.isDirectory()) {
                // A DIRECTORY'S OWN APPEARANCE IS NEWS; its mtime is not. A parent's timestamp moves
                // whenever a child is added, so reporting a directory by etag would make every file
                // created inside a folder a change to the folder as well -- what is inside it is
                // already found by the events for the things inside it. So this reports one only when
                // the WATCHER SAW IT APPEAR, which is the same evidence a deletion is trusted on.
                // Dropping it outright meant a new folder never reached the tree while a new file did.
                if (hint != CgFileEvent.Kind.CREATED || lastEtag.containsKey(path)) return null;
                lastEtag.put(path, "");
                return new FsMessages.FileChange(
                        path.toString(), FsMessages.ChangeKind.CREATED, "", "", "", true);
            }
            String now = entry.etag();
            if (known && now.equals(last)) return null;
            boolean created = last == null;
            lastEtag.put(path, now);
            return new FsMessages.FileChange(path.toString(),
                    created ? FsMessages.ChangeKind.CREATED : FsMessages.ChangeKind.MODIFIED, now);
        } catch (CgFileSystemException gone) {
            if (gone.getError() != CgFileError.FILE_NOT_FOUND) return null;
            // THE WATCHER SAW IT GO, and that is evidence in its own right. A rescan re-stats paths
            // speculatively, so "not there now, not there before" is genuinely not news there -- but
            // only what a client OPENED is ever in lastEtag, since a recursive watch primes nothing
            // for its descendants. Applying the rescan's rule to an explicit event meant a deletion
            // was reported for a file somebody had open and for no other file in the project.
            // ...unless the server itself moved it away, and this is that move coming back.
            boolean sighted = hint == CgFileEvent.Kind.DELETED && renamedAway.remove(path) == null;
            if (!sighted && (!known || last == null)) {
                lastEtag.put(path, null);
                return null;
            }
            // RECORDED AS IT GOES, because it is the only evidence a rename pairing has of its source
            // and this is the last moment anybody holds it. Absent for a file never stat-ed, which is
            // why such a move arrives as a delete and a create rather than as one RENAMED.
            if (last != null) etagBefore.put(path.toString(), last);
            lastEtag.put(path, null);
            // A DIRECTORY IS WHAT WE RECORDED IT AS. It is gone, so nothing can be asked about it now;
            // the empty etag is the marker the directory branch above wrote when it first appeared.
            return new FsMessages.FileChange(path.toString(), FsMessages.ChangeKind.DELETED, "", "", "",
                    "".equals(last));
        }
    }

    /**
     * <b>A deletion and a creation in one tick, with one etag, are a rename.</b>
     *
     * <p>The only way to get a rename out of a filesystem watcher: NIO, {@code inotify} and
     * {@code ReadDirectoryChangesW} all report the two halves separately, and both VS Code and IntelliJ
     * pair them exactly this way. The etag is {@code mtime + size}, so two files that agree on it in the
     * same tick are the same bytes — a copy would too, and a copy reported as a rename costs the client
     * a retarget it can undo, where a rename reported as a delete costs it the tab.</p>
     *
     * <p>A server-initiated rename never reaches this: {@link #noteRenamed} states it exactly.</p>
     */
    private void pairRenames(Map<CgPath, FsMessages.FileChange> coalesced) {
        List<FsMessages.FileChange> deletions = new ArrayList<>();
        List<FsMessages.FileChange> creations = new ArrayList<>();
        for (FsMessages.FileChange change : coalesced.values()) {
            if (change.kind() == FsMessages.ChangeKind.DELETED) deletions.add(change);
            else if (change.kind() == FsMessages.ChangeKind.CREATED) creations.add(change);
        }
        if (deletions.isEmpty() || creations.isEmpty()) return;

        for (FsMessages.FileChange created : creations) {
            String etag = created.etag();
            if (etag.isEmpty()) continue;
            for (FsMessages.FileChange deleted : new ArrayList<>(deletions)) {
                String had = etagBefore.get(deleted.path());
                if (had == null || !had.equals(etag)) continue;
                coalesced.put(CgPath.parse(created.path()), new FsMessages.FileChange(
                        created.path(), FsMessages.ChangeKind.RENAMED, etag, deleted.path()));
                coalesced.remove(CgPath.parse(deleted.path()));
                deletions.remove(deleted);
                break;
            }
        }
    }

    /**
     * What the server itself did, waiting for the next tick to carry it.
     *
     * <p>A filesystem event says bytes moved; it cannot say who asked, because the OS was never told.
     * So an operation the server performed is queued here <b>with a name on it</b> and merged into the
     * tick, where it also beats the watcher's version of the same path — the server knows exactly what
     * it did, and the watcher is guessing from what it can see.</p>
     *
     * @param origin the peer that asked, so the answer is not sent back to them: they already know, and
     *               a client told about its own save reloads the file it has just written
     */
    private record Stated(FsMessages.FileChange change, @Nullable Object origin) {
    }

    private final List<Stated> stated = new ArrayList<>();

    /**
     * Paths the server itself renamed away, and how many ticks to disbelieve the watcher about them.
     *
     * <p>A rename is one operation and the watcher sees it as two halves: the source vanishing and the
     * destination appearing. The destination is suppressed by its etag already matching, but the source
     * looks exactly like a deletion — and a deletion the watcher SAW is trusted on its own, because that
     * is the only way a file nobody had open can be reported gone. Both rules are right; they just meet
     * here, and the answer is that the server already said what happened to this path.</p>
     */
    private final Map<CgPath, Integer> renamedAway = new LinkedHashMap<>();

    /** Ticks an echo is disbelieved for. The watcher is milliseconds behind, not tenths of a second. */
    private static final int ECHO_TICKS = 4;

    /** The etag a path held before this tick removed it — the only evidence a rename has of its source. */
    private final Map<String, String> etagBefore = new LinkedHashMap<>();

    // ── What the server itself did ──────────────────────────────────────────────────────────────

    /**
     * The server wrote this file, so nobody needs telling it changed.
     *
     * <p>The write went through the service, which means the etag is known exactly — recording it here
     * is what stops the next poll reporting the server's own write back to the peer that asked for it,
     * and to everybody else as a change they should reload.</p>
     */
    public void noteWritten(CgPath path, @Nullable String etag) {
        if (lastEtag.containsKey(path)) lastEtag.put(path, etag);
    }

    /**
     * The server renamed this file. Stated rather than inferred.
     *
     * <p>{@link #pairRenames} is a heuristic for renames that happen outside; this one is a fact, and a
     * fact should never be re-derived from evidence when it can be recorded.</p>
     */
    public FsMessages.FileChange noteRenamed(CgPath from, CgPath to, @Nullable String etag) {
        renamedAway.put(from, ECHO_TICKS);
        if (lastEtag.containsKey(from)) {
            lastEtag.remove(from);
            lastEtag.put(to, etag);
        }
        return new FsMessages.FileChange(to.toString(), FsMessages.ChangeKind.RENAMED,
                etag == null ? "" : etag, from.toString());
    }

    /**
     * The server changed this file <b>for somebody</b>, and every other peer should hear so by name.
     *
     * @param author what to call them on the far side
     * @param origin the peer that asked, excluded from the fan-out
     */
    public void noteChanged(CgPath path, FsMessages.ChangeKind kind, @Nullable String etag,
                            String author, @Nullable Object origin) {
        noteWritten(path, etag);
        stated.add(new Stated(new FsMessages.FileChange(
                path.toString(), kind, etag == null ? "" : etag, "", author), origin));
    }

    /** As {@link #noteRenamed}, and told to everybody but whoever asked for it. */
    public void noteRenamed(CgPath from, CgPath to, @Nullable String etag,
                            String author, @Nullable Object origin) {
        stated.add(new Stated(noteRenamed(from, to, etag).by(author), origin));
    }

    /** As {@link #noteDeleted}, and told to everybody but whoever asked for it. */
    public void noteDeleted(CgPath path, String author, @Nullable Object origin) {
        noteDeleted(path);
        stated.add(new Stated(new FsMessages.FileChange(
                path.toString(), FsMessages.ChangeKind.DELETED, "", "", author), origin));
    }

    /**
     * Whether an operation is waiting to be carried.
     *
     * <p>A caller ticks the hub when the WATCHER produced something, which is the wrong question once a
     * tick also carries what the server itself did: a workspace where nothing else is happening would
     * hold somebody's rename until an unrelated file moved, and then deliver it out of order with
     * reality.</p>
     */
    public boolean hasStated() {
        return !stated.isEmpty();
    }

    /** The server deleted this file. */
    public void noteDeleted(CgPath path) {
        if (lastEtag.containsKey(path)) {
            etagBefore.put(path.toString(), lastEtag.get(path));
            lastEtag.put(path, null);
        }
    }

    // ── Matching ────────────────────────────────────────────────────────────────────────────────

    private boolean anybodyWatches(CgPath path) {
        for (Map<CgPath, Subscription> mine : byPeer.values()) {
            if (covers(mine.values(), path)) return true;
        }
        return false;
    }

    private static boolean covers(Collection<Subscription> subscriptions, CgPath path) {
        for (Subscription subscription : subscriptions) {
            if (subscription.covers(path)) return true;
        }
        return false;
    }

    /**
     * One peer's claim on one path.
     *
     * @param recursive whether descendants count. A folder the explorer has expanded is watched
     *                  non-recursively — its own entries are what is on screen — while a project root
     *                  a build watches is recursive
     */
    public record Subscription(CgPath path, boolean recursive) {

        /** Whether an event about {@code candidate} concerns this subscription. */
        public boolean covers(CgPath candidate) {
            return path.covers(candidate, recursive);
        }
    }
}
