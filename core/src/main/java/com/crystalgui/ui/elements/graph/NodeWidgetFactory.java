package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortSpec;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns a {@link NodeData} into a {@link GraphNode} — the one thing the document cannot do for itself.
 *
 * <p>A document knows a node's type id, its ports, its position and its settings. It does not know that
 * {@code shader.Position} has a title reading "Position", a Space dropdown and a preview slot, because
 * that is arbitrary UI a consumer assembled. This is where that knowledge lives, and it is deliberately
 * on the {@code ui} side of the line: registering one is how a graph gets its <em>look</em>, and the
 * model stays able to run on a server that has none.</p>
 *
 * <h3>A missing factory is not an error</h3>
 * <p>{@link #placeholder} builds a structurally correct node from the document's own stored ports —
 * right title, right ports on the right sides, every wire attached where it belongs. So a graph whose
 * types are unregistered still renders, pans, selects, rewires and saves without losing anything.</p>
 *
 * <p>That is the same mechanism as the "opened without the plugin" case the model is built around, and
 * it is worth having permanently rather than as a fallback: it is exactly what a graph should look like
 * when a mod is missing, and it is the cheapest possible way to put a document on screen.</p>
 */
@FunctionalInterface
public interface NodeWidgetFactory {

    /**
     * Builds the widget for one node.
     *
     * @param type the registered type, or {@code null} when nothing is registered under the node's id
     * @param data the node itself — the source of truth for ports, position and properties
     */
    GraphNode create(@Nullable NodeType type, NodeData data);

    /**
     * The structural node any document can produce unaided: a title from the type (or the raw type id),
     * and every stored port in its place.
     *
     * <p>The label falls back to the type <em>id</em> rather than to "Unknown", because the id is the
     * one piece of information that tells a user which plugin they are missing.</p>
     */
    static GraphNode placeholder(@Nullable NodeType type, NodeData data, PortTypeRegistryLookup types) {
        // A node authored as a widget has no library type to take a label from, so it stores its own —
        // see GraphView.dataFor. Without this it would reload titled "crystalgui:widget", which is true
        // and useless.
        String stored = data.properties().get(TITLE_PROPERTY);
        String label = type != null ? type.label() : stored != null ? stored : data.typeId();
        GraphNode node = new GraphNode(label);
        if (type == null) node.addClass(UNKNOWN_TYPE_CLASS);
        for (PortSpec spec : data.ports()) {
            PortType portType = types.lookup(spec.typeId());
            node.addPort(new NodePort(spec.direction(), portType, spec.portId()));
        }
        return node;
    }

    /** On a node whose type is not registered, so a theme can mark it as missing rather than broken. */
    String UNKNOWN_TYPE_CLASS = "__unknown-type__";

    /** Property key under which a widget-authored node keeps its own title, since it has no library
     * type to take one from. */
    String TITLE_PROPERTY = "title";

    /**
     * Resolves a document's {@code typeId} string to a {@link PortType}, which is what a port needs in
     * order to have a colour and a compatibility rule.
     *
     * <p>Separate from {@link PortTypeRegistry} the class so a caller can substitute one — and so an
     * unregistered type resolves to something drawable instead of throwing, which is the whole point of
     * the placeholder path.</p>
     */
    @FunctionalInterface
    interface PortTypeRegistryLookup {
        PortType lookup(String typeId);

        /** The registry, with an unregistered id becoming a grey type of the right name rather than an
         * exception — a port that cannot be drawn loses the wire attached to it. */
        PortTypeRegistryLookup DEFAULT = typeId -> {
            PortType known = PortTypeRegistry.get(typeId);
            return known != null ? known : new BasicPortType(typeId, 0);
        };
    }

    /**
     * A factory backed by a map of per-type builders, falling through to {@link #placeholder}.
     *
     * <pre>{@code
     * var factory = NodeWidgetFactory.of(library)
     *         .register("shader.Position", data -> positionNode(data))
     *         .build();
     * }</pre>
     */
    static Builder of(NodeTypeRegistry library) {
        return new Builder(library);
    }

    final class Builder {
        private final NodeTypeRegistry library;
        private final Map<String, Function<NodeData, GraphNode>> builders = new HashMap<>();
        private PortTypeRegistryLookup types = PortTypeRegistryLookup.DEFAULT;

        private Builder(NodeTypeRegistry library) {
            this.library = library;
        }

        public Builder register(String typeId, Function<NodeData, GraphNode> builder) {
            builders.put(typeId, builder);
            return this;
        }

        public Builder portTypes(PortTypeRegistryLookup lookup) {
            this.types = lookup;
            return this;
        }

        public NodeWidgetFactory build() {
            Map<String, Function<NodeData, GraphNode>> registered = Map.copyOf(builders);
            PortTypeRegistryLookup lookup = types;
            return (type, data) -> {
                Function<NodeData, GraphNode> builder = registered.get(data.typeId());
                GraphNode node = builder != null
                        ? builder.apply(data)
                        : placeholder(type != null ? type : library.get(data.typeId()), data, lookup);
                // Bound HERE, not at the call sites. A registered builder is a consumer's lambda and
                // usually ignores the data entirely (it just builds its widget), so leaving this to the
                // builder means every one of them has to remember — and the ones that forget produce a
                // node the document files under "authored as a widget", which then reloads as a
                // placeholder titled by that pseudo-type. The gallery hit exactly that.
                node.bindToDocument(data.id(), data.typeId());
                return node;
            };
        }
    }
}
