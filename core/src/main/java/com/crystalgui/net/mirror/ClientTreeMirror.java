package com.crystalgui.net.mirror;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.TreeSource;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * <b>The receiving half of the mirror.</b> Applies what {@link ServerTreeMirror} produced.
 *
 * <p>Written against {@link TreeSource} and {@link NodeMirror} and nothing else. Like its sending
 * counterpart it does not touch a transport: it is handed a decoded payload and applies it.</p>
 *
 * <h3>What it refuses, and what it merely warns about</h3>
 *
 * <p>An op naming a node this side has never heard of is <b>skipped with a warning</b> — one message
 * that arrived out of order, or about a subtree already gone, must not take the rest of the batch with
 * it. A described-count mismatch is different and <b>tears the window down</b>: it means the two sides
 * are building different structure, so every id in that block is wrong and every message after it would
 * land somewhere plausible and incorrect. Refusing loudly beats mirroring a lie.</p>
 *
 * @param <N> the tree's node type
 * @param <T> the serialization form
 */
public final class ClientTreeMirror<N, T> {

    private final TreeSource<N> source;
    private final NodeMirror<N, T> nodes;
    private final DynamicOps<T> ops;

    /** Told about each subtree that arrives, so an owner can bind what came in rather than re-walking. */
    @Nullable private Consumer<N> onSubtreeInserted;

    /** Told when the two sides have been proven to disagree and this mirror is no longer usable. */
    @Nullable private Runnable onIrrecoverable;

    public ClientTreeMirror(TreeSource<N> source, NodeMirror<N, T> nodes, DynamicOps<T> ops) {
        this.source = source;
        this.nodes = nodes;
        this.ops = ops;
    }

    public TreeSource<N> source() {
        return source;
    }

    public NodeMirror<N, T> nodes() {
        return nodes;
    }

    /**
     * Called with each subtree an {@code insert} brings in.
     *
     * <p>Per SUBTREE, not per batch and not from the root. An owner that binds by walking from the root
     * never binds a nested panel that arrived through a delta at all — it draws correctly and answers
     * nothing, which reads as the panel being broken rather than unbound.</p>
     */
    public void onSubtreeInserted(@Nullable Consumer<N> hook) {
        this.onSubtreeInserted = hook;
    }

    public void onIrrecoverable(@Nullable Runnable hook) {
        this.onIrrecoverable = hook;
    }

    /**
     * Numbers a freshly built tree.
     *
     * <p>Two shapes arrive. A <b>pristine</b> description carried no ids, so both sides derive them from
     * the same document-order walk. A <b>live</b> one carried them, and they are used as given: after a
     * reshape a walk no longer reproduces the numbering the other viewers hold.</p>
     *
     * @param carriedIds how many ids the description carried, or {@code 0} if it was pristine
     * @return how many nodes are numbered
     */
    public int number(N root, int carriedIds) {
        return carriedIds > 0 ? carriedIds : source.assignInDocumentOrder(root);
    }

    /** Numbers {@code node}'s subtree from {@code base}, the way an {@code insert} says to. */
    public int numberFrom(N node, int base) {
        if (base < 0) return base;
        int next = base;
        source.assignAt(node, next++);
        for (N child : source.childrenOf(node)) next = numberFrom(child, next);
        return next;
    }

    // ── Applying ─────────────────────────────────────────────────────────────

    /**
     * Applies one edit script, in order.
     *
     * @return false when the two sides were proven to disagree and the window must be torn down
     */
    public boolean applyStructure(StateMap<T> in) {
        for (StateMap<T> op : in.getList(TreeOps.OPS, e -> e)) {
            String kind = op.getString(TreeOps.OP, "");

            if (TreeOps.INSERT.equals(kind)) {
                N parent = source.byId(op.getInt(TreeOps.PARENT, -1));
                if (parent == null) {
                    CrystalGuiCore.LOGGER.warn("Insert under unknown element {}",
                            op.getInt(TreeOps.PARENT, -1));
                    continue;
                }
                T described = op.getRaw(TreeOps.NODE);
                if (described == null) continue;

                N decoded = nodes.decode(described);
                int declared = op.getInt(TreeOps.COUNT, -1);
                int actual = source.describedCount(decoded);
                if (declared != actual) {
                    /*
                     * PER SUBTREE, not per tree.
                     *
                     * Still catches a registry or codec disagreement -- which really would make every
                     * id in this block wrong -- while a composite whose constructor differs by an
                     * INTERNAL child no longer trips it, because internals are not numbered on either
                     * side. That skew used to refuse the whole window, and the description could not
                     * reveal why, because internals are never serialized.
                     */
                    CrystalGuiCore.LOGGER.error("Refusing an insert: the server described {} elements "
                            + "and this client decoded {} — the two sides are building different "
                            + "structure", declared, actual);
                    if (onIrrecoverable != null) onIrrecoverable.run();
                    return false;
                }

                nodes.insertChild(parent, decoded,
                        op.getInt(TreeOps.INDEX, source.childrenOf(parent).size()));
                numberFrom(decoded, op.getInt(TreeOps.BASE, -1));
                if (onSubtreeInserted != null) onSubtreeInserted.accept(decoded);

            } else if (TreeOps.MOVE.equals(kind)) {
                N node = source.byId(op.getInt(TreeOps.NID, -1));
                N parent = source.byId(op.getInt(TreeOps.PARENT, -1));
                if (node == null || parent == null) continue;
                // THE SAME INSTANCE, reparented. Everything local to it survives, which is the whole
                // reason a move is distinguishable from a remove followed by an insert.
                nodes.insertChild(parent, node, op.getInt(TreeOps.INDEX, -1));

            } else if (TreeOps.REMOVE.equals(kind)) {
                N node = source.byId(op.getInt(TreeOps.NID, -1));
                if (node == null) continue;
                N parent = source.parentOf(node);
                if (parent != null) nodes.removeChild(parent, node);
                source.release(node);
            }
        }
        return true;
    }

    /**
     * Applies one batch of per-node changes.
     *
     * <p>Per entry AND per field: one malformed part cannot take the rest of the batch — or the rest of
     * that node — with it.</p>
     *
     * @param skip nodes to leave alone, whatever the delta says about them. A focused text field is the
     *             case: applying a value the user is in the middle of editing resets the caret.
     */
    public void applyState(StateMap<T> in, @Nullable java.util.function.Predicate<N> skip) {
        for (StateMap<T> entry : in.getList("entries", e -> e)) {
            int nid = entry.getInt("nid", -1);
            N target = source.byId(nid);
            if (target == null) {
                CrystalGuiCore.LOGGER.warn("State update for unknown element {}", nid);
                continue;
            }
            if (skip != null && skip.test(target)) continue;

            T attributes = entry.getRaw("a");
            if (attributes != null) apply(nid, "attributes", () -> nodes.applyAttributes(attributes, target));

            T style = entry.getRaw("y");
            if (style != null) apply(nid, "inline style", () -> nodes.applyInlineStyle(style, target));

            T state = entry.getRaw("s");
            if (state != null) apply(nid, "state", () -> nodes.applyState(state, target));
        }
    }

    private void apply(int nid, String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException bad) {
            CrystalGuiCore.LOGGER.warn("Bad {} for element {}: {}", what, nid, bad.getMessage());
        }
    }

    /** The ops this mirror speaks, for a caller routing raw payloads. */
    public DynamicOps<T> ops() {
        return ops;
    }
}
