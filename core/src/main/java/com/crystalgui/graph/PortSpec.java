package com.crystalgui.graph;

/**
 * A port as the <b>document</b> knows it: an id, a side, and the name of its type.
 *
 * <p>{@code typeId} is a string, not a {@code PortType}. The document has no opinion about what
 * {@code "vec3"} means — that is the consumer's type system, and putting GLSL inside a general-purpose
 * editor framework is the mistake this whole layer is arranged to avoid. Compatibility is asked of a
 * {@link TypeCompatibility} the caller supplies.</p>
 *
 * <h3>Stored per node, even though the node type declares it</h3>
 * <p>Looking ports up from the type would keep documents smaller and make the type authoritative, and
 * it fails at the case that matters: a document whose node types are not registered — a plugin absent,
 * a mod not loaded — must still open, keep its edges, and round-trip unchanged. Storing the ports is
 * what makes a "missing node" placeholder possible instead of quietly emptying someone's graph. Unity's
 * {@code .shadergraph} stores its slots for the same reason.</p>
 */
public record PortSpec(String portId, PortDirection direction, String typeId) {

    public PortSpec {
        if (portId == null || portId.isEmpty()) throw new IllegalArgumentException("A port needs an id");
        if (direction == null) throw new IllegalArgumentException("A port needs a direction");
        if (typeId == null || typeId.isEmpty()) throw new IllegalArgumentException("A port needs a type id");
    }

    public static PortSpec input(String portId, String typeId) {
        return new PortSpec(portId, PortDirection.INPUT, typeId);
    }

    public static PortSpec output(String portId, String typeId) {
        return new PortSpec(portId, PortDirection.OUTPUT, typeId);
    }
}
