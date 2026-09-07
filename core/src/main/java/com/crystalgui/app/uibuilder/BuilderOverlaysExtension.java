package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.HoverHighlight;
import com.crystalgui.app.uibuilder.canvas.SelectionOutline;
import com.crystalgui.app.uibuilder.canvas.TreeSelectTool;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;
import com.crystalgui.widget.surface.mode.ToolKind;
import com.crystalgui.widget.surface.overlay.OverlayKind;

/**
 * What the canvas draws over the document: the hover outline and the selection.
 *
 * <p>Both are overlays rather than anything on the tree, so they are toggles like every other, they are
 * viewport-space, and turning them off costs the designer nothing but the outline.</p>
 *
 * <p>An extension because that is how a surface gains a feature — the builder is a consumer of the shared
 * engine, and a first-party path more capable than the public one is how an extension API rots.</p>
 */
public final class BuilderOverlaysExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:uibuilder.overlays";

    /** The hover outline and its tag. */
    public static final String HOVER = "crystalgui:uibuilder.hover";

    /** The selection, and its parent's dashed context. */
    public static final String SELECTION = "crystalgui:uibuilder.selection";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        if (!(surface instanceof BuilderContext builder)) return () -> { };

        ConnectionGroup connections = new ConnectionGroup();
        // THE BUILDER'S OWN SELECT, made current in place of the engine's. It composes SelectTool rather
        // than forking it -- what a tree adds is that a press on a POSITIONED node is a move.
        Disposable tool = surface.registerTool(ToolKind.of(TreeSelectTool.ID, "Select")
                .icon("crystalgui:cursor")
                .command("uibuilder.tool.select", "V")
                .tool(TreeSelectTool::new));
        surface.modes().use(TreeSelectTool.ID);
        Disposable hover = surface.registerOverlay(OverlayKind.of(HOVER, "Hover highlight")
                .visibleByDefault()
                .element(HoverHighlight::new));
        Disposable selection = surface.registerOverlay(OverlayKind.of(SELECTION, "Selection")
                .visibleByDefault()
                .element(ctx -> new SelectionOutline(builder)));

        // NOTHING TO HIGHLIGHT IN PREVIEW. The UI is being used rather than designed, and an outline
        // following the pointer over a live screen is the design surface refusing to get out of the way.
        connections.add(builder.onDidChangeDesignMode().connect(design -> {
            surface.overlays().show(HOVER, Boolean.TRUE.equals(design));
            surface.overlays().show(SELECTION, Boolean.TRUE.equals(design));
        }));

        return () -> {
            connections.disconnectAll();
            selection.dispose();
            hover.dispose();
            tool.dispose();
        };
    }
}
