package com.crystalgui.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What changed in a {@link GraphDocument} since the view last looked.
 *
 * <h3>Why a changeset and not "the view rebuilds"</h3>
 * <p>Because rebuilding detaches the element under the pointer, and in a graph editor the pointer is
 * routinely <em>on</em> the thing that just changed — dragging a node, dropping a wire onto a port. That
 * failure has a name in this project: it is what froze the table header, and it is the first trap 6.2.3
 * wrote down.</p>
 *
 * <p>It is also what Unity and LDLib2 both arrived at independently — {@code GraphData} accumulates
 * added and removed edges for its view to drain, and LDLib2 has a {@code GraphChangeset} doing the
 * same. Two implementations converging on a shape is the strongest evidence available short of
 * building it twice.</p>
 *
 * <p>Mutable and drained, rather than immutable and diffed: the document already knows precisely what
 * it changed, so computing a diff afterwards would be re-deriving information that was in hand.</p>
 */
public final class GraphChangeset {

    private final Set<String> addedNodes = new LinkedHashSet<>();
    private final Set<String> removedNodes = new LinkedHashSet<>();
    private final Set<String> movedNodes = new LinkedHashSet<>();
    private final List<EdgeData> addedEdges = new ArrayList<>();
    private final List<EdgeData> removedEdges = new ArrayList<>();

    public boolean isEmpty() {
        return addedNodes.isEmpty() && removedNodes.isEmpty() && movedNodes.isEmpty()
                && addedEdges.isEmpty() && removedEdges.isEmpty();
    }

    public Set<String> addedNodes() {
        return addedNodes;
    }

    public Set<String> removedNodes() {
        return removedNodes;
    }

    /** Nodes whose position changed. Separate from added/removed because a move needs no widget work
     * beyond writing two insets — the expensive paths are the ones that build or destroy. */
    public Set<String> movedNodes() {
        return movedNodes;
    }

    public List<EdgeData> addedEdges() {
        return addedEdges;
    }

    public List<EdgeData> removedEdges() {
        return removedEdges;
    }

    void nodeAdded(String id) {
        // A node removed and re-added within one batch is a node that never left, as far as the view is
        // concerned — and rebuilding it would be exactly the detach this class exists to avoid.
        if (!removedNodes.remove(id)) addedNodes.add(id);
    }

    void nodeRemoved(String id) {
        if (!addedNodes.remove(id)) removedNodes.add(id);
        movedNodes.remove(id);
    }

    void nodeMoved(String id) {
        if (!addedNodes.contains(id)) movedNodes.add(id);
    }

    void edgeAdded(EdgeData edge) {
        if (!removedEdges.remove(edge)) addedEdges.add(edge);
    }

    void edgeRemoved(EdgeData edge) {
        if (!addedEdges.remove(edge)) removedEdges.add(edge);
    }

    /** Empties this changeset. The view calls it after applying, and the document after handing it over. */
    public void clear() {
        addedNodes.clear();
        removedNodes.clear();
        movedNodes.clear();
        addedEdges.clear();
        removedEdges.clear();
    }

    @Override
    public String toString() {
        return "GraphChangeset[+" + addedNodes.size() + " nodes, -" + removedNodes.size()
                + " nodes, ~" + movedNodes.size() + " moved, +" + addedEdges.size()
                + " edges, -" + removedEdges.size() + " edges]";
    }
}
