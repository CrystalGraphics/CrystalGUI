package com.crystalgui.app.shadergraph.extension;

import com.crystalgui.app.shadergraph.ShaderGraphBridge;
import com.crystalgui.app.shadergraph.ShaderGraphServices;
import com.crystalgui.app.shadergraph.ShaderNodeLibrary;
import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.widget.graph.GraphContext;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.NodeWidgetFactory;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * Teaches a graph the shader node set — the create menu's contents and the widgets its types build.
 *
 * <p>First of the five, and the others depend on it having run: with no library a graph is a working
 * canvas you cannot add a node to.</p>
 */
public final class NodeLibraryExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:shadergraph.node-library";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof GraphContext graph)) return () -> { };
        // The library IS the shader node set -- the create menu, its search and the widget factory all
        // come from one bridge call, so there is no shader-specific UI code below this line. `of` also
        // installs this domain's field widgets, which is why building a library is the moment the
        // vocabulary has to exist and why nothing else installs them.
        NodeTypeRegistry types = ShaderNodeLibrary.of(ShaderGraphServices.of(graph.getDocument()).nodes());
        graph.useNodeLibrary(types, propertyAware(graph, NodeWidgetFactory.of(types).build()),
                ShaderGraphBridge.GLSL_PROMOTION);
        // The library is the graph's for as long as the graph lives; there is nothing to withdraw.
        return () -> { };
    }

    /**
     * The widget factory, taught about property nodes.
     *
     * <p><b>A property node's type is synthesised per property and never registered</b>, which is
     * deliberate — a type per declared property would put the blackboard's contents in the create menu.
     * The consequence is that the graph cannot look one up, so it built a plain node from the ports the
     * document stored and a property came back from a file as an ordinary two-row box with the capsule
     * styling gone.</p>
     *
     * <p>Fixed at the factory rather than after the fact, because every path that makes a widget goes
     * through it — loading a file, undoing a delete, a server sync, the create menu — and patching them
     * up afterwards would mean finding all of them, and finding each new one.</p>
     */
    private static NodeWidgetFactory propertyAware(GraphContext graph, NodeWidgetFactory base) {
        return (type, data) -> {
            if (!ShaderPropertyNodes.isPropertyNode(data)) return base.create(type, data);
            // Resolved from the DOCUMENT, not from the stored type: the node holds a property id, and
            // what that property IS lives on the blackboard.
            GraphProperty property = ShaderPropertyNodes.resolve(graph.getDocument(), data);
            GraphNode node = base.create(ShaderPropertyNodes.typeFor(property), data);
            node.addClass(ShaderPropertyNodes.NODE_CLASS);
            ShaderPropertyNodes.sync(node, property);
            return node;
        };
    }
}
