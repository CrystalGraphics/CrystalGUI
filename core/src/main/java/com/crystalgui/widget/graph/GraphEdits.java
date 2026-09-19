package com.crystalgui.widget.graph;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;

/**
 * Every undoable change a graph can make, in one place — <b>made to the document</b>, never to a view.
 *
 * <pre>{@code
 * view.attachNode(widget, data);                              // the view makes the change it is showing...
 * edits.record(new GraphEdits.AddNode(document, data));       // ...and the history remembers it
 * }</pre>
 *
 * <p>A document may be shown by several views sharing one history, and any of them may be gone by the time an edit
 * is undone. So an edit reverses the DOCUMENT, and every view catches up from its own changeset. The view that made
 * the change does it itself first and {@code record}s, which keeps the widget under the pointer.</p>
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
     * Puts a node in the graph.
     *
     * <p><b>It carries the {@link NodeData}, not a position</b>, and that is what makes delete-then-undo
     * safe. The id has to come back <em>unchanged</em>, or every edge that referenced the node points at
     * nothing — and since the edges are restored by the same transaction, one fresh id would silently
     * drop every wire the node had. Re-adding the stored data restores the id, the ports and the
     * properties together.</p>
     */
    record AddNode(GraphDocument document, NodeData data) implements Edit {
        @Override public void apply() { if (!document.hasNode(data.id())) document.addNode(data); }
        @Override public void undo() { document.removeNode(data.id()); }
        @Override public String label() { return "add node"; }
    }

    /** Takes a node out of the graph. The inverse of {@link AddNode}, and it carries the same data for the same
     * reason. */
    record DeleteNode(GraphDocument document, NodeData data) implements Edit {
        @Override public void apply() { document.removeNode(data.id()); }
        @Override public void undo() { if (!document.hasNode(data.id())) document.addNode(data); }
        @Override public String label() { return "delete node"; }
    }

    /**
     * Joins two ports.
     *
     * <p>Restores the edge rather than re-running validation: an undo must put back exactly the edge that was there,
     * and re-validating at that point can only ever refuse it — the graph it was legal in is precisely the graph the
     * undo is restoring.</p>
     */
    record Connect(GraphDocument document, EdgeData edge) implements Edit {
        @Override public void apply() { if (!document.edges().contains(edge)) document.restoreEdge(edge); }
        @Override public void undo() { document.disconnect(edge); }
        @Override public String label() { return "connect"; }
    }

    /** Parts two ports. The inverse of {@link Connect}. */
    record Disconnect(GraphDocument document, EdgeData edge) implements Edit {
        @Override public void apply() { document.disconnect(edge); }
        @Override public void undo() { if (!document.edges().contains(edge)) document.restoreEdge(edge); }
        @Override public String label() { return "disconnect"; }
    }

    /** Two positions and the node's id. Invertible by swapping them, and it keeps working across a
     * delete-then-undo because the id is what comes back, not the widget. */
    record MoveNode(GraphDocument document, String nodeId,
                    float fromX, float fromY, float toX, float toY) implements Edit {
        @Override public void apply() { document.moveNode(nodeId, toX, toY); }
        @Override public void undo() { document.moveNode(nodeId, fromX, fromY); }
        @Override public String label() { return "move"; }
    }
}
