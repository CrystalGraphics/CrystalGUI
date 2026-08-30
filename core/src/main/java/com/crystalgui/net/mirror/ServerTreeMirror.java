package com.crystalgui.net.mirror;

import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.TreeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * <b>The sending half of the mirror.</b> Watches a tree and turns what happened to it into messages.
 *
 * <p>Written against {@link TreeSource} and {@link NodeMirror} and nothing else — it names no widget,
 * no element, no session and no transport. It does not send: it <b>produces payloads</b> and lets its
 * owner decide who they go to, which is what lets one window fan out to several viewers with different
 * visibility without this class knowing viewers exist.</p>
 *
 * <h3>Two streams, deliberately separate</h3>
 *
 * <table>
 *   <tr><td>{@link #drainStructure()}</td><td>an EDIT SCRIPT — {@code insert}/{@code remove}/{@code
 *       move}, in the order they happened</td></tr>
 *   <tr><td>{@link #drainState()}</td><td>per-node changes — state, attributes, inline style, keyed by
 *       id</td></tr>
 * </table>
 *
 * <p><b>Structure must be drained and sent first.</b> A tree delta renumbers both sides, so a state
 * delta computed after one has to be applied after it; and a caller that gates sending must gate
 * <em>both</em> together or neither. Gating only the send while still letting structure drain is the
 * subtle version: this peer renumbers, the other does not, and every state delta afterwards lands on
 * the wrong node — silently, because an id is an int and every one of them still resolves to
 * something.</p>
 *
 * <h3>An id is allocated when the op is ENCODED</h3>
 *
 * <p>Not when the node is inserted. Ids are handed out in the order ops are encoded, which is what lets
 * one {@code insert} name a whole subtree as {@code base}+{@code count} rather than an id per node. The
 * cost is stated on {@link TreeSource#allocate} and paid in {@link #removed}.</p>
 *
 * @param <N> the tree's node type
 * @param <T> the serialization form
 */
public final class ServerTreeMirror<N, T> implements TreeObserver<N> {

    private final TreeSource<N> source;
    private final NodeMirror<N, T> nodes;
    private final DynamicOps<T> ops;

    /**
     * Off until the far side has been told what the tree looks like.
     *
     * <p>Before that there is nothing to be a delta against, and a "change" is just the caller still
     * building. It is not the same question as whether a viewer is watching — that one belongs to the
     * owner, because two viewers can disagree about it and this class serves both.</p>
     */
    private boolean live;

    /** Ops in the order they happened. Order is the message. */
    private final List<PendingOp<N>> pendingOps = new ArrayList<>();

    private final Set<N> dirtyState = new LinkedHashSet<>();
    private final Set<N> dirtyIdentity = new LinkedHashSet<>();

    private boolean reshaped;

    private record PendingOp<N>(String kind, N node, @Nullable N parent, int index) { }

    public ServerTreeMirror(TreeSource<N> source, NodeMirror<N, T> nodes, DynamicOps<T> ops) {
        this.source = source;
        this.nodes = nodes;
        this.ops = ops;
    }

    // ── What the owner asks ──────────────────────────────────────────────────

    public TreeSource<N> source() {
        return source;
    }

    public NodeMirror<N, T> nodes() {
        return nodes;
    }

    /**
     * True once the tree has been structurally changed since it was first described.
     *
     * <p>What the owner reads to decide whether a LATE viewer can be handed a pristine description or
     * must be told the ids ({@link NodeMirror#describeLive}).</p>
     */
    public boolean reshaped() {
        return reshaped;
    }

    /** Numbers the tree in document order and answers the count. What opening does. */
    public int describeAndNumber() {
        live = true;
        return source.assignInDocumentOrder(source.root());
    }

    /** Stops recording. Anything already pending is discarded, having nowhere left to go. */
    public void stop() {
        live = false;
        pendingOps.clear();
        dirtyState.clear();
        dirtyIdentity.clear();
    }

    /** The nodes whose state has changed and not yet been drained. For diagnostics and tests. */
    public Set<N> pendingStateChanges() {
        return Set.copyOf(dirtyState);
    }

    // ── The two streams ──────────────────────────────────────────────────────

    /**
     * The edit script since the last drain, or {@code null} if the tree did not change shape.
     *
     * <p>Ids are allocated and released here, so a caller that drains and then declines to send has
     * renumbered one side of a conversation on its own. Drain only when you will send.</p>
     */
    @Nullable
    public StateMap<T> drainStructure() {
        if (!live || pendingOps.isEmpty()) return null;

        List<T> encoded = new ArrayList<>(pendingOps.size());
        for (PendingOp<N> pending : pendingOps) {
            StateMap<T> op = new StateMap<>(ops);
            op.putString(TreeOps.OP, pending.kind());

            if (TreeOps.INSERT.equals(pending.kind())) {
                if (pending.parent() == null || source.peekId(pending.parent()) < 0) continue;
                // Counted BEFORE allocating, so the number describes the subtree rather than the
                // allocator's opinion of it. The receiver counts what it decoded and compares.
                int count = source.describedCount(pending.node());
                int base = source.allocate(pending.node());
                op.putInt(TreeOps.PARENT, source.idOf(pending.parent()));
                op.putInt(TreeOps.INDEX, pending.index());
                op.putInt(TreeOps.BASE, base);
                op.putInt(TreeOps.COUNT, count);
                op.putRaw(TreeOps.NODE, nodes.describe(pending.node()));

            } else if (TreeOps.MOVE.equals(pending.kind())) {
                if (pending.parent() == null || source.peekId(pending.parent()) < 0) continue;
                if (source.peekId(pending.node()) < 0) continue;
                op.putInt(TreeOps.NID, source.idOf(pending.node()));
                op.putInt(TreeOps.PARENT, source.idOf(pending.parent()));
                op.putInt(TreeOps.INDEX, pending.index());

            } else {
                int id = source.peekId(pending.node());
                if (id < 0) continue;
                op.putInt(TreeOps.NID, id);
                source.release(pending.node());
            }
            encoded.add(op.encode());
        }
        pendingOps.clear();
        if (encoded.isEmpty()) return null;

        reshaped = true;
        StateMap<T> out = new StateMap<>(ops);
        out.putRaw(TreeOps.OPS, ops.createList(encoded));
        return out;
    }

    /**
     * The per-node changes since the last drain, or {@code null} if nothing changed.
     *
     * <p>One message carrying three independent kinds, keyed by id — see {@link NodeMirror}. A node
     * that is not numbered is skipped rather than dropped from the dirty set early: it is not part of
     * the described tree, so there is nothing on the far side to address.</p>
     */
    @Nullable
    public Map<N, StateMap<T>> drainState() {
        if (!live || (dirtyState.isEmpty() && dirtyIdentity.isEmpty())) return null;

        Map<N, StateMap<T>> entries = new LinkedHashMap<>();
        for (N node : dirtyState) {
            if (source.peekId(node) < 0) continue;
            T state = nodes.encodeState(node);
            if (state != null) entryFor(entries, node).putRaw("s", state);
        }
        for (N node : dirtyIdentity) {
            if (source.peekId(node) < 0) continue;
            StateMap<T> entry = entryFor(entries, node);
            T attributes = nodes.encodeAttributes(node);
            if (attributes != null) entry.putRaw("a", attributes);
            T style = nodes.encodeInlineStyle(node);
            if (style != null) entry.putRaw("y", style);
        }
        dirtyState.clear();
        dirtyIdentity.clear();
        if (entries.isEmpty()) return null;

        for (Map.Entry<N, StateMap<T>> entry : entries.entrySet()) {
            entry.getValue().putInt("nid", source.idOf(entry.getKey()));
        }
        return entries;
    }

    /**
     * Packs entries into one message.
     *
     * <p>Separate from draining them because <b>who gets which entry is not the mirror's question</b>:
     * a viewer may be owed everything except the one element it just changed itself, and the mirror
     * knows nothing about viewers. It hands back what changed; the session decides who hears about it.</p>
     */
    public StateMap<T> pack(Collection<StateMap<T>> entries) {
        List<T> encoded = new ArrayList<>(entries.size());
        for (StateMap<T> entry : entries) encoded.add(entry.encode());
        StateMap<T> out = new StateMap<>(ops);
        out.putRaw("entries", ops.createList(encoded));
        return out;
    }

    private StateMap<T> entryFor(Map<N, StateMap<T>> entries, N node) {
        return entries.computeIfAbsent(node, n -> new StateMap<>(ops));
    }

    // ── TreeObserver ─────────────────────────────────────────────────────────

    @Override
    public void inserted(N node, N parent, int index) {
        if (!live) return;
        // Covered by an insert already pending in this batch? Then it is a descendant of a graft and
        // travels inside that op's description.
        for (PendingOp<N> pending : pendingOps) {
            if (TreeOps.INSERT.equals(pending.kind()) && isAncestorOrSelf(pending.node(), node)) return;
        }
        // The parent has to be addressable, or the far side has nowhere to put this.
        if (source.peekId(parent) < 0) return;
        pendingOps.add(new PendingOp<>(TreeOps.INSERT, node, parent, index));
    }

    @Override
    public void moved(N node, N parent, int index) {
        if (!live) return;
        if (source.peekId(node) < 0 || source.peekId(parent) < 0) return;
        pendingOps.add(new PendingOp<>(TreeOps.MOVE, node, parent, index));
    }

    @Override
    public void removed(N node, N parent) {
        dirtyState.remove(node);
        dirtyIdentity.remove(node);
        if (!live) return;

        /*
         * THE COALESCING SCAN COMES FIRST, AND HAS TO.
         *
         * An id is allocated at DRAIN time, not at insert time, so a subtree added and removed inside
         * one tick has NO id at the moment it is removed. An "is it described?" guard placed above this
         * scan answers no and returns, leaving the INSERT standing: the far side is told to build a
         * subtree that no longer exists and is never told to take it away. Nothing throws -- the
         * receiver dutifully builds it -- so it shows as a row that outlives whatever briefly created
         * it.
         */
        for (int i = pendingOps.size() - 1; i >= 0; i--) {
            PendingOp<N> pending = pendingOps.get(i);
            if (TreeOps.INSERT.equals(pending.kind()) && pending.node() == node) {
                pendingOps.remove(i);
                if (source.peekId(node) >= 0) source.release(node);
                return;
            }
        }

        if (source.peekId(node) < 0) return;   // never described; the far side has never heard of it
        pendingOps.add(new PendingOp<>(TreeOps.REMOVE, node, parent, -1));
    }

    private boolean isAncestorOrSelf(N ancestor, N node) {
        for (N at = node; at != null; at = source.parentOf(at)) {
            if (at == ancestor) return true;
        }
        return false;
    }

    @Override
    public void stateChanged(N node) {
        dirtyState.add(node);
    }

    @Override
    public void attributeChanged(N node) {
        dirtyIdentity.add(node);
    }

    @Override
    public void inlineStyleChanged(N node) {
        // Collected with attributes because they are drained together, and kept SEPARATE on the way in
        // because one flag that carried neither is how they came to be conflated.
        dirtyIdentity.add(node);
    }
}
