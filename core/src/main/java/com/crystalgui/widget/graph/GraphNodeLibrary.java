package com.crystalgui.widget.graph;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.graph.node.NodeCreationMenu;

/**
 * Where a graph's new nodes come from: the type library, the widget factory, and the menu that offers
 * them.
 *
 * <pre>{@code
 * view.setNodeLibrary(registry, factory, TypeCompatibility.EXACT);
 * library.openAt(worldX, worldY);            // Space
 * library.offerFor(port, worldX, worldY);    // a wire dropped on nothing
 * }</pre>
 *
 * <p>All of it is the consumer's: the library is what a shader graph and a dialogue graph disagree
 * about, and the factory is what turns a type id into the particular box somebody designed. With
 * neither set the graph still works entirely — you simply cannot add a node from inside it.</p>
 */
final class GraphNodeLibrary {

    private final GraphView view;

    @Nullable
    private NodeCreationMenu menu;

    @Nullable
    private NodeTypeRegistry types;

    @Nullable
    private NodeWidgetFactory factory;

    private TypeCompatibility typeRule = TypeCompatibility.EXACT;

    /** Where the node the menu is about to create will land, and what it should wire to. */
    private float pendingWorldX;
    private float pendingWorldY;

    @Nullable
    private NodePort pendingFrom;

    GraphNodeLibrary(GraphView view) {
        this.view = view;
    }

    @Nullable
    NodeTypeRegistry types() {
        return types;
    }

    @Nullable
    NodeWidgetFactory factory() {
        return factory;
    }

    /** The create-node menu, once a library has been set. */
    @Nullable
    NodeCreationMenu menu() {
        return menu;
    }

    /** Gives this graph a library to create nodes from, and a factory to build their widgets. */
    void set(NodeTypeRegistry library, NodeWidgetFactory widgets, @Nullable TypeCompatibility rule) {
        this.types = library;
        this.factory = widgets;
        this.typeRule = rule == null ? TypeCompatibility.EXACT : rule;
        NodeCreationMenu created = new NodeCreationMenu(library);
        created.onChosen.connect(this::createFrom);
        view.append(created);
        this.menu = created;
    }

    /** Opens the menu unfiltered, at a world position — what Space does. */
    void openAt(float worldX, float worldY) {
        if (menu == null) return;
        pendingFrom = null;
        pendingWorldX = worldX;
        pendingWorldY = worldY;
        Vector2f at = rootPositionOfWorld(worldX, worldY);
        // NO invoker. The invoker is deliberately treated as part of its own popover — that carve-out
        // exists so a dropdown button is not dismissed by the very press that opens it — and naming the
        // graph as invoker therefore made every press anywhere on the canvas count as a press INSIDE the
        // menu, so light dismiss never fired. This menu has no invoker: a gesture opened it, not a button.
        menu.openAll(at.x(), at.y(), null);
    }

    /**
     * Opens the menu filtered to what a dropped wire could connect to.
     *
     * <p>Takes WORLD coordinates. The drag reports plane space and only the wire layer knows that
     * offset, so the conversion happens on the view rather than here — a library that had to ask the
     * wires where they are would be a feature naming another feature.</p>
     */
    void offerFor(NodePort from, float worldX, float worldY) {
        if (menu == null) return;
        pendingFrom = from;
        pendingWorldX = worldX;
        pendingWorldY = worldY;

        Vector2f at = rootPositionOfWorld(worldX, worldY);
        // Invoker null — see openAt.
        if (from.getDirection().isOutput()) {
            menu.openForOutput(from.getType().id(), typeRule, at.x(), at.y(), null);
        } else {
            menu.openForInput(from.getType().id(), typeRule, at.x(), at.y(), null);
        }
    }

    /**
     * World to the root-relative logical coordinates a promoted popover is positioned in.
     *
     * <p>Through {@code worldToViewport}, so the menu opens where the wire was dropped <em>on screen</em>
     * rather than where it would be at zoom 1.</p>
     */
    private Vector2f rootPositionOfWorld(float worldX, float worldY) {
        Vector2f onScreen = view.worldToViewport(worldX, worldY);
        UIDocument window = view.document();
        if (window == null) return onScreen;
        // `worldToViewport` answers in the VIEW's local space; a promoted element's containing block is
        // the root. Two different spaces, so the conversion goes through the world matrix rather than by
        // subtracting the root's own x() — which is the root's offset in its own parent and has nothing
        // to do with this view's position.
        Box self = view.box();
        Box rootCache = window.box();
        if (self == null || rootCache == null) return onScreen;
        Vector2f origin = Box.originIn(self, rootCache);
        return new Vector2f(onScreen.x() + origin.x(), onScreen.y() + origin.y());
    }

    /**
     * Creates the chosen node and, when the menu was opened by a dropped wire, connects it — as
     * <b>one</b> undo step.
     *
     * <p>Two presses to undo a node you just made is the same failure as forty presses to undo one drag,
     * and for the same reason: the user did one thing.</p>
     */
    private void createFrom(NodeTypeRegistry.Offer offer) {
        if (factory == null) return;
        NodeData data = offer.type().create(pendingWorldX, pendingWorldY);
        GraphNode node = factory.create(offer.type(), data);
        // Bound BEFORE it is added, so the node keeps the id and ports the library built rather than
        // having a second set derived from the widget.
        node.bindToDocument(data.id(), data.typeId());
        // Into the document first, so addNode adopts the library's ports and properties instead of
        // deriving a second set from the widget.
        view.document.addNode(data);

        view.edits.begin("create " + offer.type().label());
        try {
            view.addNode(node, pendingWorldX, pendingWorldY);
            NodeData placed = view.document.node(node.getNodeId());
            if (placed != null) view.edits.record(new GraphEdits.AddNode(view, node, placed));
            NodePort source = pendingFrom;
            if (source != null && offer.port() != null) {
                for (NodePort port : node.getPorts()) {
                    if (port.getPortId().equals(offer.port().portId())) {
                        view.connect(source, port);
                        break;
                    }
                }
            }
        } finally {
            view.edits.end();
        }
        pendingFrom = null;
        view.getSelection().selectOnly(node);
    }
}
