package com.crystalgui.graph;

/**
 * One edge: from an output port to an input port, by reference.
 *
 * <p>The direction is fixed by the field names rather than by a flag, exactly as the view's own
 * {@code GraphConnection} does — so there is no such thing as a backwards edge to check for later.
 * {@link GraphDocument#connect} normalises whichever order the user dragged in, once.</p>
 */
public record EdgeData(PortRef from, PortRef to) {

    public EdgeData {
        if (from == null || to == null) throw new IllegalArgumentException("An edge needs both ends");
        if (from.equals(to)) throw new IllegalArgumentException("An edge cannot join a port to itself");
    }

    public static EdgeData of(String fromNode, String fromPort, String toNode, String toPort) {
        return new EdgeData(new PortRef(fromNode, fromPort), new PortRef(toNode, toPort));
    }

    public boolean touches(String nodeId) {
        return from.nodeId().equals(nodeId) || to.nodeId().equals(nodeId);
    }

    public boolean touches(PortRef port) {
        return from.equals(port) || to.equals(port);
    }
}
