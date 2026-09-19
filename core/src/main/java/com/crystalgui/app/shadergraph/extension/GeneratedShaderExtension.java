package com.crystalgui.app.shadergraph.extension;

import com.crystalgui.app.shadergraph.ShaderGraphServices;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.widget.graph.GraphContext;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * Compiles the graph to GLSL and publishes the result.
 *
 * <p><b>It emits; it does not display.</b> The editor's source pane and its compile status entry are
 * listeners on {@link ShaderGraphServices#compiled} like anything else, which is what lets a second
 * viewer of the same graph exist without a second compile.</p>
 *
 * <p>A connection is a discrete user action, so a structural change compiles immediately. A field edit
 * is per-keystroke and arrives already debounced through {@link ShaderGraphServices#requestRecompile}.</p>
 */
public final class GeneratedShaderExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:shadergraph.generated";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof GraphContext graph)) return () -> { };
        ShaderGraphServices shader = ShaderGraphServices.of(graph.getDocument());

        // STALE-ONLY: every pane onto a graph sees every wire change, and one compile covers them all.
        Connection wires = graph.connectionsChanged().connect(shader::compileIfStale);
        // The first one, so a graph that opens already wired shows its source rather than waiting for
        // an edit that may never come.
        shader.compileIfStale();
        return wires::disconnect;
    }
}
