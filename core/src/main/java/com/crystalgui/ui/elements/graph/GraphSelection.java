package com.crystalgui.ui.elements.graph;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.elements.inspector.InspectorRegistry;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What is selected in a graph: nodes, and at most one wire.
 *
 * <h3>A model, not a flag per node</h3>
 * <p>6.2.3 stored a boolean on each {@link GraphNode} and said in its own javadoc that this was the
 * smallest thing that worked. This replaces it, because a marquee, a delete command and (later) an
 * inspector all need to read and write the same answer, and three consumers each walking the tree
 * asking every node is three chances to disagree.</p>
 *
 * <p>The node's boolean is still there and is still what {@code graphnode:checked} reads — but it is now
 * a <em>projection</em> of this set rather than the truth. One writer, many readers.</p>
 *
 * <h3>Selection is not undoable</h3>
 * <p>Deliberately, and it is a live disagreement rather than an obvious call. Blender records selection
 * in its undo history and is criticised for it in nearly the same words every time — that it is counter
 * to almost every other application; Figma has a standing request for it as a <em>preference</em>; Silo
 * puts selection undo on a separate shortcut entirely.</p>
 *
 * <p>The case in favour is real: losing a laborious multi-selection to one misclick genuinely hurts. Our
 * answer is VS Code's — selection stays view state, and an <em>edit</em>'s undo restores the selection
 * that edit applied to, because the edit knows what it touched. That returns the case people actually
 * lose without putting a click in the history.</p>
 *
 * <h3>Insertion-ordered</h3>
 * <p>A {@code LinkedHashSet}, so "the first node you picked" is answerable. Alignment, distribution and
 * a future "make this the active one" all key off it, and none of them can be added later to a
 * {@code HashSet} without changing behaviour nobody documented.</p>
 */
public final class GraphSelection {

    private final Set<GraphNode> nodes = new LinkedHashSet<>();

    /** At most one, because a wire has nothing a multi-wire operation would want yet — and one is
     * enough to delete it, which is the only thing a wire can currently be selected for. */
    @Nullable
    private GraphConnection wire;

    /**
     * Fires after any change. One signal, because every consumer re-reads the whole selection.
     *
     * <p>Also announces to {@link InspectorRegistry} — see {@link #announce}.</p>
     */
    public final Signal.Action onChanged = new Signal.Action();

    {
        // ANNOUNCED BY THE WIDGET, not by whoever built it.
        //
        // Every contribution used to wire `selection.onChanged -> InspectorRegistry::subjectChanged`
        // itself, which is boilerplate that is not contribution-specific: a graph's selection changing is
        // always a change to what an inspector would show. Forgetting the line produced a graph whose
        // Node tab never appeared while an identical one beside it worked -- the failure is silent,
        // remote from its cause, and every future graph feature had to remember it.
        //
        // Cheap by design: the inspector defers to the next frame and drops the rebuild entirely when its
        // subject key has not moved, so an unobserved selection change costs one boolean.
        onChanged.connect(InspectorRegistry::subjectChanged);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The selected nodes, in the order they were added. */
    public List<GraphNode> nodes() {
        return List.copyOf(nodes);
    }

    public Set<GraphNode> nodeSet() {
        return Collections.unmodifiableSet(nodes);
    }

    @Nullable
    public GraphConnection wire() {
        return wire;
    }

    public boolean contains(GraphNode node) {
        return nodes.contains(node);
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && wire == null;
    }

    public int size() {
        return nodes.size() + (wire == null ? 0 : 1);
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Replaces the whole selection with one node. */
    public void selectOnly(GraphNode node) {
        if (nodes.size() == 1 && nodes.contains(node) && wire == null) return;
        clearSilently();
        nodes.add(node);
        node.setSelected(true);
        onChanged.emit();
    }

    /** Replaces the whole selection with a wire — selecting a wire deselects the nodes, since the two
     * are never operated on together. */
    public void selectOnly(GraphConnection connection) {
        clearSilently();
        wire = connection;
        onChanged.emit();
    }

    public void add(GraphNode node) {
        if (!nodes.add(node)) return;
        node.setSelected(true);
        onChanged.emit();
    }

    public void remove(GraphNode node) {
        if (!nodes.remove(node)) return;
        node.setSelected(false);
        onChanged.emit();
    }

    /** Adds if absent, removes if present — what {@code Shift+click} does. */
    public void toggle(GraphNode node) {
        if (nodes.contains(node)) remove(node);
        else add(node);
    }

    /** Replaces the selection with {@code replacement}, in one signal rather than one per node. */
    public void replaceWith(Collection<GraphNode> replacement) {
        if (nodes.equals(new LinkedHashSet<>(replacement)) && wire == null) return;
        clearSilently();
        for (GraphNode node : replacement) {
            if (nodes.add(node)) node.setSelected(true);
        }
        onChanged.emit();
    }

    /** Adds several at once — a marquee with Shift held. */
    public void addAll(Collection<GraphNode> more) {
        boolean changed = false;
        for (GraphNode node : more) {
            if (nodes.add(node)) {
                node.setSelected(true);
                changed = true;
            }
        }
        if (changed) onChanged.emit();
    }

    /** Removes several at once — a marquee with Alt held. */
    public void removeAll(Collection<GraphNode> fewer) {
        boolean changed = false;
        for (GraphNode node : fewer) {
            if (nodes.remove(node)) {
                node.setSelected(false);
                changed = true;
            }
        }
        if (changed) onChanged.emit();
    }

    public void clear() {
        if (isEmpty()) return;
        clearSilently();
        onChanged.emit();
    }

    /**
     * Drops anything no longer in {@code graph} — a node that was deleted, or a wire that was
     * disconnected.
     *
     * <p>Called after any structural change. Without it the selection pins a detached widget: the node
     * is out of the tree but still "selected", so the next move-many re-adds it at a position nobody
     * asked for, and a delete tries to remove it twice.</p>
     */
    void prune(GraphView graph) {
        boolean changed = nodes.removeIf(node -> node.getParent() != graph.content());
        if (wire != null && !graph.getConnections().contains(wire)) {
            wire = null;
            changed = true;
        }
        if (changed) onChanged.emit();
    }

    private void clearSilently() {
        for (GraphNode node : nodes) node.setSelected(false);
        nodes.clear();
        wire = null;
    }
}
