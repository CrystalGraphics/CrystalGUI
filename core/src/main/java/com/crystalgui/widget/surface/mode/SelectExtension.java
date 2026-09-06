package com.crystalgui.widget.surface.mode;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.extension.SurfaceExtension;

/**
 * The engine's one built-in feature: the Select tool.
 *
 * <p>It is an extension like any other — a surface built with {@code List.of()} has not even this, which
 * is what {@code SurfaceShipsNothingTest} holds the engine to. Enable it by naming {@link #ID}, which
 * every real consumer does.</p>
 *
 * <pre>{@code
 * new SurfaceEditor(policy, List.of(SelectExtension.ID, "mymod:grid"));
 * }</pre>
 */
public final class SelectExtension implements SurfaceExtension {

    public static final String ID = "crystalgui:select";

    /** The tool's own id, for a consumer that makes it current by hand. */
    public static final String TOOL = "crystalgui:select";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public SelectExtension() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(SurfaceContext surface) {
        Disposable tool = surface.registerTool(ToolKind.of(TOOL, "Select")
                .icon("crystalgui:cursor")
                .command("surface.tool.select", "V")
                .tool(SelectTool::new));
        // CURRENT IMMEDIATELY: select is what a surface does when nothing else is chosen, and a surface
        // that opened with no tool would take no clicks at all.
        surface.modes().use(TOOL);
        return tool;
    }
}
