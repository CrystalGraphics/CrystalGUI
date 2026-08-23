package com.crystalgui.fs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Who else has this file open. <b>Shared across every peer, unlike everything else in this package.</b>
 *
 * <h3>Why it could not live on the watcher</h3>
 *
 * <p>The server already knows the answer and has since Phase 4 — {@code fs.watch} is sent for every file
 * a client reads, so the set of watched paths <em>is</em> the set of open files. But a
 * {@link WorkspaceWatcher} belongs to one {@link WorkspaceRpc}, which belongs to one peer, so each
 * knows only its own. Presence is the question every other peer's answer, which makes it the first
 * piece of workspace state that is genuinely per <b>server</b> rather than per connection — hence its
 * home on {@link WorkspaceService}, the one object every {@code WorkspaceRpc} already shares.</p>
 *
 * <h3>A version counter rather than a listener</h3>
 *
 * <p>Broadcasting a change would need a fan-out across connections, and {@code core} has no such thing:
 * the loader holds the connection map. So this counts instead, and each peer's poll — which the host
 * already calls every tick — notices when the number has moved since that peer last saw it. Cheap
 * (one {@code int} compare per peer per tick), needs no new wiring, and cannot deliver to a peer that
 * has gone away, because a peer that is gone is not being polled.</p>
 *
 * <h3>Display names, never ids</h3>
 *
 * <p>{@link WorkspaceActor#id()} is what permission decisions are made on and is explicitly documented as
 * such; {@code displayName()} is <i>"for logs and for the UI"</i>. This is the UI, so it carries the
 * display name and nothing else — an id leaking into a presence line would be an identifier the user did
 * not choose, shown to other players.</p>
 */
public final class WorkspacePresence {

    /** path → the actors who have it open, in the order they opened it. */
    private final Map<CgPath, Map<String, String>> byPath = new LinkedHashMap<>();

    /** Bumped by every change. @see #version */
    private int version;

    /** Notes that {@code actor} has {@code path} open. */
    public synchronized void opened(WorkspaceActor actor, CgPath path) {
        Map<String, String> here = byPath.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
        if (here.put(actor.id(), actor.displayName()) == null) version++;
    }

    /** Notes that {@code actor} no longer has {@code path} open. */
    public synchronized void closed(WorkspaceActor actor, CgPath path) {
        Map<String, String> here = byPath.get(path);
        if (here == null) return;
        if (here.remove(actor.id()) != null) version++;
        if (here.isEmpty()) byPath.remove(path);
    }

    /**
     * Forgets an actor entirely. <b>Call when a peer disconnects.</b>
     *
     * <p>Without it, a player who crashed or logged out is still shown as having the file open — and
     * indefinitely, since the {@code fs.unwatch} that would have cleared them is exactly what a lost
     * connection does not send. A presence list nobody prunes describes the past.</p>
     */
    public synchronized void left(WorkspaceActor actor) {
        boolean changed = false;
        for (java.util.Iterator<Map.Entry<CgPath, Map<String, String>>> it = byPath.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<CgPath, Map<String, String>> entry = it.next();
            if (entry.getValue().remove(actor.id()) != null) changed = true;
            if (entry.getValue().isEmpty()) it.remove();
        }
        if (changed) version++;
    }

    /** Everyone with {@code path} open, by display name. */
    public synchronized List<String> whoHasOpen(CgPath path) {
        Map<String, String> here = byPath.get(path);
        return here == null ? Collections.emptyList() : new ArrayList<>(here.values());
    }

    /** Everyone <em>except</em> {@code actor} — which is what a UI ever wants to say. */
    public synchronized List<String> whoElseHasOpen(WorkspaceActor actor, CgPath path) {
        Map<String, String> here = byPath.get(path);
        if (here == null) return Collections.emptyList();
        List<String> others = new ArrayList<>(here.size());
        for (Map.Entry<String, String> entry : here.entrySet()) {
            if (!entry.getKey().equals(actor.id())) others.add(entry.getValue());
        }
        return others;
    }

    /** Every path somebody has open. */
    public synchronized Set<CgPath> paths() {
        return new LinkedHashSet<>(byPath.keySet());
    }

    /**
     * Bumped by every change, so a poll can tell whether it has anything to say.
     *
     * <p>Deliberately not a timestamp: {@code System.nanoTime()} has an arbitrary origin and may be
     * negative, which this codebase has already been bitten by once.</p>
     */
    public synchronized int version() {
        return version;
    }
}
