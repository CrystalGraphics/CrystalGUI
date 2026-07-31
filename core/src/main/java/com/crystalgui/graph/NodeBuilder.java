package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent construction for a {@link NodeData}.
 *
 * <pre>{@code
 * doc.newNode("shader.PerlinNoise3D").at(250, 200)
 *    .in("Sampling Coordinates", "vec3")
 *    .in("Noise Scale", "float")
 *    .out("Value", "float")
 *    .prop("Noise Scale", "0.9")
 *    .add();
 * }</pre>
 *
 * <h3>Fluent like {@code CgQuadRenderer.Quad}, but emphatically not scratch</h3>
 * <p>Those builders return a <b>shared mutable instance</b> that must be submitted in the expression
 * that built it and never held — an allocation-avoidance measure for a record submitted hundreds of
 * thousands of times a frame. Nothing like that applies here: a session builds a few hundred nodes, and
 * every one of them is stored, referenced by edges and serialized. So this allocates one builder per
 * node and yields a fresh immutable record, and a caller may hold either as long as they like.</p>
 *
 * <p>Copying the scratch half would import that hazard for no benefit at all, which is worth writing
 * down because the surface reads so similarly.</p>
 *
 * <h3>The id defaults to a generated one</h3>
 * <p>Which is the correct default and was not available before: {@link #id} exists for a loader or a
 * test that must name a node, and every other caller should let the id be generated. An id invented by
 * hand is one that eventually collides, and the collision surfaces as an
 * {@link IllegalArgumentException} half a graph later.</p>
 */
public final class NodeBuilder {

    private final String typeId;

    @Nullable
    private final GraphDocument target;

    private String id = GraphIds.generate();
    private float x, y;
    private final List<PortSpec> ports = new ArrayList<>();
    private final Map<String, String> properties = new LinkedHashMap<>();

    NodeBuilder(String typeId, @Nullable GraphDocument target) {
        if (typeId == null || typeId.isEmpty()) throw new IllegalArgumentException("A node needs a type id");
        this.typeId = typeId;
        this.target = target;
    }

    /** Detached construction — for a test, or a loader translating some other format. */
    public static NodeBuilder of(String typeId) {
        return new NodeBuilder(typeId, null);
    }

    /** Names the node explicitly. Loaders and tests only — see the class note. */
    public NodeBuilder id(String value) {
        this.id = GraphIds.requireValid(value);
        return this;
    }

    public NodeBuilder at(float worldX, float worldY) {
        this.x = worldX;
        this.y = worldY;
        return this;
    }

    public NodeBuilder in(String portId, String typeId) {
        ports.add(PortSpec.input(portId, typeId));
        return this;
    }

    public NodeBuilder out(String portId, String typeId) {
        ports.add(PortSpec.output(portId, typeId));
        return this;
    }

    public NodeBuilder port(PortSpec spec) {
        ports.add(spec);
        return this;
    }

    /** A setting the node <em>type</em> interprets — the {@code Space: World} dropdown, an unconnected
     * input's value. Insertion-ordered, because that order is part of what makes the document hash. */
    public NodeBuilder prop(String key, String value) {
        properties.put(key, value);
        return this;
    }

    /** The node, unattached. */
    public NodeData build() {
        return new NodeData(id, typeId, x, y, ports, properties);
    }

    /**
     * Builds it and puts it in the document this builder came from.
     *
     * @return the node, so a caller can immediately wire it up
     * @throws IllegalStateException if this builder was made by {@link #of} and has no document
     */
    public NodeData add() {
        if (target == null) {
            throw new IllegalStateException("This builder has no document — use build(), or start from "
                    + "GraphDocument.newNode(type)");
        }
        return target.addNode(build());
    }
}
