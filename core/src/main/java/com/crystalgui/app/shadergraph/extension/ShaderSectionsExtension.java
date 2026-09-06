package com.crystalgui.app.shadergraph.extension;

import com.crystalgui.app.shadergraph.ShaderInspectorSections;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.graph.GraphContext;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * The inspector's Node and Graph tabs for a shader graph — what the selected node exposes, and what the
 * graph as a whole does.
 *
 * <p>The sections register against the inspector itself rather than against this surface, because an
 * inspector is a workbench panel that outlives any one editor and asks what is selected. The extension
 * is what ties their lifetime to a graph being open.</p>
 */
public final class ShaderSectionsExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:shadergraph.inspector";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof GraphContext)) return () -> { };
        return ShaderInspectorSections.register();
    }
}
