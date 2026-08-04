package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgShaderNode;
import com.crystalgraphics.shadergraph.CgShaderType;
import com.crystalgraphics.shadergraph.CgTemplateShaderNode;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.PortSpec;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * The node that <b>reads</b> a declared property — Unity's pill node, dragged from the Blackboard.
 *
 * <p>Reference: {@code docs/research/unity-blackboard/14-drag-property-to-node.png}. One output, no
 * inputs, no settings. The node <em>is</em> a reference.</p>
 *
 * <h3>Its shape is read from the document, never copied into it</h3>
 * <p>A property node stores only {@link #PROPERTY_ID} — the property's id — and everything else, its
 * label and its port's type, is looked up at build time. That is what makes renaming or retyping a
 * property update every node using it, which is the entire reason a node references by id rather than
 * by name. Copying the type in at creation would leave stale nodes the first time someone changed
 * their mind about a property, and nothing on screen would explain why one node disagreed.</p>
 *
 * <h3>A dangling reference is an error node, not a missing one</h3>
 * <p>Delete a property and the nodes reading it stay, typed {@code dynamic} and labelled as missing.
 * Same call {@code GraphDocument} makes for a node type it does not recognise, for the same reason:
 * deleting a declaration is not a statement about the graph's shape, and silently removing wired nodes
 * would destroy connections nobody asked to lose. Undoing the delete makes every one whole again with
 * no bookkeeping, because none was needed.</p>
 */
public final class ShaderPropertyNodes {

    private ShaderPropertyNodes() {
    }

    /** The document type id every property node shares. */
    public static final String TYPE_ID = "cg:property";

    /** The key holding which property this node reads. @see ShaderPropertyNodes */
    public static final String PROPERTY_ID = "propertyId";

    /** The one output every property node has. */
    public static final String OUT_PORT = "Out";

    /** Shown when the property a node points at is gone. */
    public static final String MISSING_LABEL = "Missing Property";

    /**
     * The document-side type for a node reading {@code property}.
     *
     * <p>One {@link NodeType} per property rather than one shared type, because the label and the port
     * type differ per property and {@code NodeType} is what supplies both. They are built on demand and
     * never registered — a property node is created by dragging a pill, not by picking from the create
     * menu, so nothing needs to enumerate them.</p>
     */
    public static NodeType typeFor(@Nullable GraphProperty property) {
        if (property == null) {
            return NodeType.of(TYPE_ID).label(MISSING_LABEL).category("Property")
                    .out(OUT_PORT, ShaderGraphBridge.DYNAMIC_TYPE).build();
        }
        return NodeType.of(TYPE_ID).label(property.name()).category("Property")
                .out(OUT_PORT, property.typeId())
                .defaultProperty(PROPERTY_ID, property.id())
                .build();
    }

    /** A fresh property node at a world position, reading {@code property}. */
    public static NodeData create(GraphProperty property, float x, float y) {
        return typeFor(property).create(x, y);
    }

    /** Whether {@code data} is a property node. */
    public static boolean isPropertyNode(@Nullable NodeData data) {
        return data != null && TYPE_ID.equals(data.typeId());
    }

    /** Which property {@code data} reads, or null if it is not a property node. */
    @Nullable
    public static String propertyIdOf(@Nullable NodeData data) {
        return isPropertyNode(data) ? data.properties().get(PROPERTY_ID) : null;
    }

    /** The property {@code data} reads, or null when it is dangling or not a property node. */
    @Nullable
    public static GraphProperty resolve(GraphDocument document, @Nullable NodeData data) {
        String id = propertyIdOf(data);
        return id == null ? null : document.property(id);
    }

    /**
     * The compiler-side node for one property — emits the uniform, and nothing else.
     *
     * <p>{@code {Out} = _Ref;} for most types, {@code {Out} = _Ref.xyz;} for a Vector 3, whose uniform is
     * declared wider than it is because the parser bans {@code vec3}. That narrowing comes from
     * {@link CgShaderType#propertyAccessSuffix()} rather than being spelled here, so there is one place
     * that knows the declaration and the read have to agree.</p>
     */
    public static CgShaderNode compilerNodeFor(GraphProperty property) {
        CgShaderType type = CgShaderType.parse(property.typeId());
        return CgTemplateShaderNode.of(TYPE_ID + ":" + property.id())
                .label(property.name())
                .out(OUT_PORT, type)
                .body("{" + OUT_PORT + "} = " + property.reference() + type.propertyAccessSuffix() + ";")
                .build();
    }

    /**
     * A stand-in for a node whose property is gone: emits the type's zero rather than failing the whole
     * compile.
     *
     * <p>A graph mid-edit is <b>normally</b> broken, so one dangling reference must not cost the user
     * every other node's preview. The error belongs in the status line and on the node, which is where
     * 6.3.8 puts it.</p>
     */
    public static CgShaderNode missingNodeFor(String nodeId) {
        return CgTemplateShaderNode.of(TYPE_ID + ":missing:" + nodeId)
                .label(MISSING_LABEL)
                .out(OUT_PORT, CgShaderType.FLOAT)
                .body("{" + OUT_PORT + "} = 0.0;")
                .build();
    }

    /** The ports a property node has — one output, typed by the property. */
    public static List<PortSpec> portsFor(@Nullable GraphProperty property) {
        return List.of(PortSpec.output(OUT_PORT,
                property == null ? ShaderGraphBridge.DYNAMIC_TYPE : property.typeId()));
    }

    /** The stored form of a property node, for a codec or a test. */
    public static Map<String, String> propertiesFor(GraphProperty property) {
        return Map.of(PROPERTY_ID, property.id());
    }
}
