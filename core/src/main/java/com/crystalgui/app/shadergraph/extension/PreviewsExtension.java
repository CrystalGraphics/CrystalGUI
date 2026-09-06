package com.crystalgui.app.shadergraph.extension;

import com.crystalgui.app.shadergraph.ShaderGraphServices;
import com.crystalgui.app.shadergraph.preview.MainPreviewPanel;
import com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.widget.graph.GraphContext;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * The live previews: a thumbnail per node, and the floating main preview over the canvas.
 *
 * <p>Both are one feature because they compile the same graph against the same node set — splitting
 * them would mean two schedulers deciding independently when a node's source had changed.</p>
 */
public final class PreviewsExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:shadergraph.previews";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof GraphContext graph)) return () -> { };
        ShaderGraphServices shader = ShaderGraphServices.of(graph.getDocument());

        ShaderGraphPreviews previews =
                new ShaderGraphPreviews(graph, shader.nodes(), shader.master());
        previews.attach();

        ConnectionGroup connections = new ConnectionGroup();
        // A dropdown on a node changes the emitted GLSL but not the graph's SHAPE, so
        // connectionsChanged never fires for it -- without this the source pane silently shows the
        // previous variant.
        connections.add(previews.onPropertyChanged.connect(shader::requestRecompile));
        // And the other direction: a finished compile is when a thumbnail's source may have changed.
        connections.add(shader.compiled.connect(result -> previews.invalidate()));

        // Over the canvas, not beside it: an overlay sits in the VIEWPORT rather than on the plane, so it
        // stays put while the graph pans underneath -- which is what "floating preview" means.
        // Deliberately NOT promoted to the top layer, which would put it above every dialog too.
        MainPreviewPanel mainPreview =
                new MainPreviewPanel(graph.getDocument(), shader.nodes(), shader.master());
        graph.mountOverlay(mainPreview);
        shader.publishPreviews(previews, mainPreview);

        return connections::disconnectAll;
    }
}
