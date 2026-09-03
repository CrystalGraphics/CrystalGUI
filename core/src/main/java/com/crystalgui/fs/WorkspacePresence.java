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

    /**
     * Who has UNSAVED changes, per path.
     *
     * <p>{@code plan_fs_rewrite.md} D12. Presence answered "who has this open", which is the wrong
     * question for the moment it matters: two people find out they are both editing a file when the
     * second one saves and is refused with a conflict, by which point both have work to reconcile.
     * "X is editing this file" on the first keystroke is what every collaborative editor shows, and it
     * costs one flag.</p>
     *
     * <p>Set by the CLIENT, because only the client knows: dirtiness is {@code version !=
     * savedVersion} on a document the server does not hold. It is cleared by a save, which the server
     * does see, so a client that disconnects mid-edit leaves a flag {@link #left} takes away.</p>
     */
    private final Map<CgPath, java.util.Set<String>> editing = new LinkedHashMap<>();

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
        // AND THE FLAG. A dirty marker outliving the document it describes is a banner saying somebody
        // is editing a file they closed, which nothing would ever take down.
        java.util.Set<String> dirtyHere = editing.get(path);
        if (dirtyHere != null) {
            dirtyHere.remove(actor.id());
            if (dirtyHere.isEmpty()) editing.remove(path);
        }
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
        for (java.util.Iterator<Map.Entry<CgPath, java.util.Set<String>>> it = editing.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<CgPath, java.util.Set<String>> entry = it.next();
            if (entry.getValue().remove(actor.id())) changed = true;
            if (entry.getValue().isEmpty()) it.remove();
        }
        if (changed) version++;
    }

    /**
     * Records whether this actor has unsaved changes to this path.
     *
     * <p>Only meaningful for a path the actor has open — an editing flag on a file nobody has open is
     * a flag nothing will ever clear.</p>
     */
    public synchronized void setEditing(WorkspaceActor actor, CgPath path, boolean dirty) {
        Map<String, String> here = byPath.get(path);
        if (here == null || !here.containsKey(actor.id())) return;
        java.util.Set<String> dirtyHere = editing.get(path);
        boolean was = dirtyHere != null && dirtyHere.contains(actor.id());
        if (was == dirty) return;
        if (dirty) {
            editing.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(actor.id());
        } else if (dirtyHere != null) {
            dirtyHere.remove(actor.id());
            if (dirtyHere.isEmpty()) editing.remove(path);
        }
        version++;
    }

    /** Whether that actor has unsaved changes to that path. */
    public synchronized boolean isEditing(WorkspaceActor actor, CgPath path) {
        java.util.Set<String> here = editing.get(path);
        return here != null && here.contains(actor.id());
    }

    /** Everyone editing {@code path} except {@code actor}, by display name. */
    public synchronized List<String> whoElseIsEditing(WorkspaceActor actor, CgPath path) {
        java.util.Set<String> dirtyHere = editing.get(path);
        Map<String, String> here = byPath.get(path);
        if (dirtyHere == null || here == null) return Collections.emptyList();
        List<String> others = new ArrayList<>();
        for (String id : dirtyHere) {
            if (!id.equals(actor.id()) && here.containsKey(id)) others.add(here.get(id));
        }
        return others;
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
