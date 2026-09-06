package com.crystalgui.widget.graph;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.widget.surface.select.SurfaceSelection;

/**
 * What is selected in a graph: nodes, and at most one wire.
 *
 * <p>The graph's view of {@link SurfaceSelection} — the engine owns the set, the signal and the
 * announcement to the inspector; this names the two things a graph selects. Read it from
 * {@link GraphView#getSelection()}.</p>
 *
 * <pre>{@code
 * graph.getSelection().selectOnly(node);
 * graph.getSelection().nodes();        // typed, in the order they were picked
 * graph.getSelection().wire();         // the one selected edge, or null
 * }</pre>
 *
 * <p>The boolean on {@link GraphNode} is a <em>projection</em> of this set, not the truth — one writer,
 * many readers. Why selection is not undoable, and why it is insertion-ordered, are in
 * {@link SurfaceSelection}.</p>
 */
public final class GraphSelection extends SurfaceSelection {

    public GraphSelection() {
        // The cast is safe because GraphView is the only thing that adds to this, and it adds nodes.
        super((item, selected) -> ((GraphNode) item).setSelected(selected));
    }

    /** The selected nodes, in the order they were added. */
    public List<GraphNode> nodes() {
        List<GraphNode> nodes = new ArrayList<>(itemSet().size());
        for (var item : itemSet()) nodes.add((GraphNode) item);
        return List.copyOf(nodes);
    }

    /** The selected wire, or null. A wire and nodes are never selected together. */
    @Nullable
    public GraphConnection wire() {
        return (GraphConnection) secondary();
    }

    /** Replaces the whole selection with a wire. */
    public void selectOnly(GraphConnection connection) {
        selectSecondary(connection);
    }

    /** Drops anything the graph no longer holds — a deleted node, a disconnected wire. */
    void prune(GraphView graph) {
        retain(node -> node.parent() == graph.content(),
                wire -> graph.getConnections().contains(wire));
    }
}
