package com.crystalgui.app.shadergraph.extension;

import javax.annotation.Nullable;

import com.crystalgui.app.shadergraph.ShaderGraphServices;
import com.crystalgui.app.shadergraph.blackboard.BlackboardPanel;
import com.crystalgui.app.shadergraph.blackboard.PropertyPill;
import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.widget.graph.GraphContext;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.NodeWidgetFactory;
import com.crystalgui.widget.surface.DropHandler;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * The blackboard: the graph's declared properties, as a panel over the canvas.
 *
 * <p>Owns the whole of what a property IS to a graph — the board, dragging one onto the plane to make a
 * node, and keeping the two views of one property in step.</p>
 */
public final class BlackboardExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:shadergraph.blackboard";

    private static final String DOCUMENT_NAME = "shader_graph";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof GraphContext graph)) return () -> { };
        ShaderGraphServices shader = ShaderGraphServices.of(graph.getDocument());

        BlackboardPanel board =
                new BlackboardPanel(graph.getDocument(), DOCUMENT_NAME, graph.undoStack());

        ConnectionGroup connections = new ConnectionGroup();
        // THE PILL AND THE NODE ARE TWO VIEWS OF ONE PROPERTY, so selecting either lights both. Unity
        // does the same, and it is what makes a board of a dozen properties navigable: click a pill to
        // find its nodes, click a node to find its pill.
        //
        // The loop this obviously risks closes itself: both GraphSelection.replaceWith and
        // BlackboardPanel.select return early when handed what they already hold, so the second hop is a
        // no-op rather than a bounce.
        connections.add(board.onPropertySelected.connect(id -> highlightNodesFor(graph, id)));
        connections.add(graph.getSelection().onChanged.connect(() -> selectBoardFromGraph(graph, board)));
        // A rename, a retype or an Exposed toggle has to reach the nodes reading that property -- they
        // show what it IS, not a copy taken when they were made.
        connections.add(graph.getDocument().onChanged.connect(() -> syncPropertyNodes(graph)));

        graph.mountOverlay(board);
        shader.publishBlackboard(board);
        Disposable drop = surface.registerDropHandler(new PropertyDrop(graph, shader));

        return () -> {
            connections.disconnectAll();
            drop.dispose();
        };
    }

    /** Dropping a pill on the plane makes a node reading that property. */
    private record PropertyDrop(GraphContext graph, ShaderGraphServices shader) implements DropHandler {

        @Override
        public boolean accepts(Object payload) {
            return payload instanceof PropertyPill.Payload;
        }

        @Override
        public boolean drop(Object payload, float worldX, float worldY) {
            if (!(payload instanceof PropertyPill.Payload dropped)) return false;
            GraphProperty property = graph.getDocument().property(dropped.propertyId());
            if (property == null) return false;
            NodeWidgetFactory factory = graph.getNodeFactory();
            if (factory == null) return false;

            NodeData data = ShaderPropertyNodes.create(property, worldX, worldY);
            GraphNode node = factory.create(ShaderPropertyNodes.typeFor(property), data);
            ShaderPropertyNodes.decorate(node, property);
            graph.placeNode(node, worldX, worldY);
            shader.requestRecompile();
            return true;
        }
    }

    private static void highlightNodesFor(GraphContext graph, @Nullable String propertyId) {
        for (GraphNode node : graph.nodes()) {
            String id = ShaderPropertyNodes.propertyIdOf(graph.getDocument().node(node.getNodeId()));
            boolean linked = propertyId != null && propertyId.equals(id);
            if (linked == node.hasClass(ShaderPropertyNodes.LINKED_CLASS)) continue;
            if (linked) node.addClass(ShaderPropertyNodes.LINKED_CLASS);
            else node.removeClass(ShaderPropertyNodes.LINKED_CLASS);
        }
    }

    private static void selectBoardFromGraph(GraphContext graph, BlackboardPanel board) {
        String property = null;
        for (GraphNode node : graph.getSelection().nodes()) {
            String id = ShaderPropertyNodes.propertyIdOf(graph.getDocument().node(node.getNodeId()));
            if (id == null) continue;
            property = id;
            break;
        }
        board.select(property);
    }

    private static void syncPropertyNodes(GraphContext graph) {
        for (GraphNode node : graph.nodes()) {
            NodeData data = graph.getDocument().node(node.getNodeId());
            if (!ShaderPropertyNodes.isPropertyNode(data)) continue;
            ShaderPropertyNodes.sync(node, ShaderPropertyNodes.resolve(graph.getDocument(), data));
        }
    }
}
