package com.crystalgui.widget.surface;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * <b>An overlay in one declaration</b> — something drawn over the surface that is not part of what is
 * being edited: a box-model diagram, redlines, a grid, a marquee band.
 *
 * <p>An overlay <em>is</em> an element, so it paints, lays out and is styled like any other widget; the
 * engine decides only where it goes and when it is shown, and derives the toggle command from here.</p>
 *
 * <pre>{@code
 * ctx.registerOverlay(OverlayKind.of("mymod:grid", "Grid")
 *         .inPlane()                                  // pans and zooms with the content
 *         .visibleByDefault()
 *         .command("mymod.view.grid", "Ctrl+G")
 *         .element(c -> new GridOverlay(c.surface())));
 * }</pre>
 *
 * <p>{@link #inPlane()} is the choice that is easy to get wrong: an in-plane overlay is measured in
 * world units and moves with the content (a grid, a guide, a snap line), while the default is pinned to
 * the viewport and stays put while the plane slides under it (a HUD, a scale readout).</p>
 */
public final class OverlayKind {

    private final String id;
    private final String displayName;

    @Nullable
    private String icon;
    @Nullable
    private String commandId;
    @Nullable
    private String accelerator;
    @Nullable
    private Function<SurfaceContext, UIElement> factory;

    private boolean inPlane;
    private boolean visibleByDefault;

    private OverlayKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public static OverlayKind of(String id, String displayName) {
        return new OverlayKind(id, displayName);
    }

    public OverlayKind icon(String icon) {
        this.icon = icon;
        return this;
    }

    /** Draws in world space, so it pans and zooms with the content. The default is viewport-pinned. */
    public OverlayKind inPlane() {
        this.inPlane = true;
        return this;
    }

    public OverlayKind visibleByDefault() {
        this.visibleByDefault = true;
        return this;
    }

    public OverlayKind command(String commandId) {
        this.commandId = commandId;
        return this;
    }

    public OverlayKind command(String commandId, String accelerator) {
        this.commandId = commandId;
        this.accelerator = accelerator;
        return this;
    }

    /** What is drawn, built once per surface. Required. */
    public OverlayKind element(Function<SurfaceContext, UIElement> factory) {
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
    public Function<SurfaceContext, UIElement> factory() {
        return factory;
    }

    public boolean isInPlane() {
        return inPlane;
    }

    public boolean isVisibleByDefault() {
        return visibleByDefault;
    }

    @Override
    public String toString() {
        return "OverlayKind[" + id + "]";
    }
}
