package com.crystalgui.widget.graph;


/**
 * One edge: from an output port to an input port.
 *
 * <p>Direction is fixed by the field names rather than by a flag, so there is no such thing as a
 * backwards connection to check for later — {@link GraphView#connect} normalises whichever order the
 * user dragged in, once, at the point where the two ports are known.</p>
 *
 * <p>This is the <b>view's</b> edge, holding widgets. 6.2.5's document model gets its own, holding
 * ids — a server has no ports, only data, and a record of live UI elements could never travel over a
 * wire or outlive a rebuild.</p>
 */
public record GraphConnection(NodePort from, NodePort to) {

    public GraphConnection {
        if (from == null || to == null) throw new IllegalArgumentException("A connection needs both ends");
        if (!from.getDirection().isOutput()) throw new IllegalArgumentException("from must be an output port");
        if (!to.getDirection().isInput()) throw new IllegalArgumentException("to must be an input port");
    }

    public boolean touches(NodePort port) {
        return from == port || to == port;
    }

    /** Whether this edge joins the same two ports as {@code other} — the duplicate check. */
    public boolean sameEndsAs(GraphConnection other) {
        return other != null && from == other.from && to == other.to;
    }
}
