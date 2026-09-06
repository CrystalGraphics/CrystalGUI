package com.crystalgui.widget.graph;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.NodeData;

/**
 * Every undoable change a graph can make, in one place.
 *
 * <pre>{@code
 * edits.apply(new GraphEdits.AddNode(view, widget, data));
 * edits.apply(new GraphEdits.Disconnect(wires, edge));
 * }</pre>
 *
 * <p>Each pair is written as two records that are each other's inverse rather than one carrying a
 * direction flag, so an edit reads as what it does at the call site and neither half can be reached by
 * passing the wrong boolean.</p>
 *
 * <p><b>They are data, never closures.</b> A captured lambda could not be inverted without remembering
 * what it closed over, and could not be sent to a server at all.</p>
 */
final class GraphEdits {

    private GraphEdits() {
    }

    /**
     * Puts a node on the plane.
     *
     * <p><b>It carries the {@link NodeData}, not a position</b>, and that is what makes delete-then-undo
     * safe. The id has to come back <em>unchanged</em>, or every edge that referenced the node points at
     * nothing — and since the edges are restored by the same transaction, one fresh id would silently
     * drop every wire the node had. Re-adding the stored data restores the id, the ports and the
     * properties together.</p>
     */
    record AddNode(GraphView view, GraphNode node, NodeData data) implements Edit {
        @Override public void apply() { view.attachNode(node, data); }
        @Override public void undo() { view.detachNode(node); }
        @Override public String label() { return "add node"; }
    }

    /** Takes a node off the plane. The inverse of {@link AddNode}, and it carries the same data for the
     * same reason. */
    record DeleteNode(GraphView view, GraphNode node, NodeData data) implements Edit {
        @Override public void apply() { view.detachNode(node); }
        @Override public void undo() { view.attachNode(node, data); }
        @Override public String label() { return "delete node"; }
    }

    /**
     * Joins two ports.
     *
     * <p>Restores the edge rather than re-running {@link GraphWires#canConnect}: an undo must put back
     * exactly the edge that was there, and re-validating at that point can only ever refuse it — the
     * graph it was legal in is precisely the graph the undo is restoring.</p>
     */
    record Connect(GraphWires wires, EdgeData edge) implements Edit {
        @Override public void apply() { wires.addEdge(edge); }
        @Override public void undo() { wires.removeEdge(edge); }
        @Override public String label() { return "connect"; }
    }

    /** Parts two ports. The inverse of {@link Connect}. */
    record Disconnect(GraphWires wires, EdgeData edge) implements Edit {
        @Override public void apply() { wires.removeEdge(edge); }
        @Override public void undo() { wires.addEdge(edge); }
        @Override public String label() { return "disconnect"; }
    }

    /** Two positions and the node's id. Invertible by swapping them, and it keeps working across a
     * delete-then-undo because the id is what comes back, not the widget. */
    record MoveNode(GraphView view, String nodeId,
                    float fromX, float fromY, float toX, float toY) implements Edit {
        @Override public void apply() { move(toX, toY); }
        @Override public void undo() { move(fromX, fromY); }

        private void move(float x, float y) {
            GraphNode widget = view.widgetFor(nodeId);
            if (widget != null) view.moveNode(widget, x, y);
            else view.document.moveNode(nodeId, x, y);
        }

        @Override public String label() { return "move"; }
    }
}
