package com.crystalgui.graph;

/**
 * What an edge points at: a node, and a port on it.
 *
 * <p>Two strings rather than an object reference, because that is what survives being written to disk
 * and read back by a different process — and because an edge must be able to name a port on a node
 * whose type is not registered here. A reference cannot do either.</p>
 */
public record PortRef(String nodeId, String portId) {

    public PortRef {
        if (nodeId == null || nodeId.isEmpty()) throw new IllegalArgumentException("A port ref needs a node id");
        if (portId == null || portId.isEmpty()) throw new IllegalArgumentException("A port ref needs a port id");
    }

    @Override
    public String toString() {
        return nodeId + ":" + portId;
    }
}
