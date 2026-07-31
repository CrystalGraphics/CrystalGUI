package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node, as data.
 *
 * <p>Immutable, like every other edit-carrying type here: moving a node produces a new {@code NodeData}
 * rather than mutating one. That is what lets a {@code MoveNodeEdit} hold two positions and be
 * invertible by swapping them, and what stops a changeset from describing a state that has already
 * changed underneath it.</p>
 *
 * <h3>The id does three jobs</h3>
 * <p>It is what edges reference, what a diff keys on, and — per the CrystalShader manifesto — the
 * namespace prefix the compiler will emit generated GLSL under ({@code node_multiply_out}). Unity's
 * {@code objectId} is documented as usable during code generation for exactly that reason. So it is
 * stored, stable for the life of the node, and restricted to characters that are legal in an
 * identifier.</p>
 *
 * <h3>Properties are strings</h3>
 * <p>A node's own settings — the {@code Space: World} dropdown, an unconnected input's typed value —
 * are a string map the <b>node type</b> interprets. Exactly the arrangement {@code StyleValue} uses: the
 * document carries the text and the thing that understands it does the parsing, lazily and once. A
 * typed union here would mean the document knowing every type any consumer might invent.</p>
 */
public record NodeData(String id, String typeId, float x, float y,
                       List<PortSpec> ports, Map<String, String> properties) {

    public NodeData {
        GraphIds.requireValid(id);
        if (typeId == null || typeId.isEmpty()) throw new IllegalArgumentException("A node needs a type id");
        ports = List.copyOf(ports == null ? List.of() : ports);
        // LinkedHashMap, not Map.copyOf: insertion order is what makes the encoded form byte-stable,
        // which is what makes ContentHash mean anything. Map.copyOf gives no order guarantee at all.
        properties = properties == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static NodeData of(String id, String typeId, float x, float y, PortSpec... ports) {
        return new NodeData(id, typeId, x, y, List.of(ports), Map.of());
    }

    @Nullable
    public PortSpec port(String portId) {
        for (PortSpec port : ports) {
            if (port.portId().equals(portId)) return port;
        }
        return null;
    }

    public boolean hasPort(String portId) {
        return port(portId) != null;
    }

    /** The same node at a new position. */
    public NodeData movedTo(float newX, float newY) {
        if (newX == x && newY == y) return this;
        return new NodeData(id, typeId, newX, newY, ports, properties);
    }

    /** The same node with one property set — or removed, when {@code value} is null. */
    public NodeData withProperty(String key, @Nullable String value) {
        Map<String, String> next = new LinkedHashMap<>(properties);
        if (value == null) next.remove(key);
        else next.put(key, value);
        return new NodeData(id, typeId, x, y, ports, next);
    }

    /** The same node under a new id — what duplicate and paste need, since an id may exist once. */
    public NodeData withId(String newId) {
        return new NodeData(newId, typeId, x, y, ports, properties);
    }
}
