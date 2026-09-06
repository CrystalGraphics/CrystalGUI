package com.crystalgui.widget.surface;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.widget.surface.overlay.ViewMode;

/**
 * <b>A view mode in one declaration</b> — a lens over the whole surface: outline only, isolate the
 * selection, difference against a reference image.
 *
 * <p>One is current at a time, or none, and the engine derives the toggle command from here. Picking a
 * second exits the first.</p>
 *
 * <pre>{@code
 * ctx.registerViewMode(ViewModeKind.of("mymod:outline", "Outline")
 *         .command("mymod.view.outline", "Ctrl+Shift+O")
 *         .mode(OutlineMode::new));
 * }</pre>
 *
 * <p>Use this rather than an {@link OverlayKind} when the answer changes how existing content reads
 * rather than adding something on top of it — and remember that a mode must undo itself on exit.</p>
 */
public final class ViewModeKind {

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
    private String commandId;
    @Nullable
    private String accelerator;
    @Nullable
    private Function<SurfaceContext, ViewMode> factory;

    private ViewModeKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static ViewModeKind of(String id, String displayName) {
        return new ViewModeKind(id, displayName);
    }

    public ViewModeKind icon(String icon) {
        this.icon = icon;
        return this;
    }

    public ViewModeKind command(String commandId) {
        this.commandId = commandId;
        return this;
    }

    public ViewModeKind command(String commandId, String accelerator) {
        this.commandId = commandId;
        this.accelerator = accelerator;
        return this;
    }

    /** How the mode is built, once per surface. Required. */
    public ViewModeKind mode(Function<SurfaceContext, ViewMode> factory) {
        this.factory = factory;
        return this;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    @Nullable
    public String icon() {
        return icon;
    }

    @Nullable
    public String commandId() {
        return commandId;
    }

    @Nullable
    public String accelerator() {
        return accelerator;
    }

    @Nullable
    public Function<SurfaceContext, ViewMode> factory() {
        return factory;
    }

    @Override
    public String toString() {
        return "ViewModeKind[" + id + "]";
    }
}
