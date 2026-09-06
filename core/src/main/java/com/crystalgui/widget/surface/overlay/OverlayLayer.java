package com.crystalgui.widget.surface.overlay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * What is drawn over the plane, and whether each one is showing.
 *
 * <pre>{@code
 * ctx.overlays().show("mymod:grid", true);
 * boolean on = ctx.overlays().isShowing("mymod:grid");
 * }</pre>
 *
 * <p>An overlay is built the first time it is shown and kept, so toggling one twice does not rebuild it.
 * Where it goes is the declaration's: an <b>in-plane</b> overlay is measured in world units and pans and
 * zooms with the content (a grid, a guide, a snap line); the default is pinned to the viewport and stays
 * put while the plane slides under it (a HUD, a scale readout).</p>
 */
public final class OverlayLayer {

    private final SurfaceContext ctx;

    private final Map<String, UIElement> built = new LinkedHashMap<>();
    private final Map<String, Boolean> showing = new LinkedHashMap<>();

    /** Fires after any overlay is shown or hidden. */
    public final Signal.Action onDidChange = new Signal.Action();

    public OverlayLayer(SurfaceContext ctx) {
        this.ctx = ctx;
    }

    /** Every overlay registered here, in registration order. */
    public List<OverlayKind> kinds() {
        return ctx.overlayKinds();
    }

    public boolean isShowing(String id) {
        return Boolean.TRUE.equals(showing.get(id));
    }

    /** Shows or hides one. An id nothing registered is a no-op. */
    public void show(String id, boolean visible) {
        if (isShowing(id) == visible) return;
        UIElement element = build(id);
        if (element == null) return;
        element.setDisplayed(visible);
        showing.put(id, visible);
        onDidChange.emit();
    }

    public void toggle(String id) {
        show(id, !isShowing(id));
    }

    /** Puts every overlay that declared itself visible on screen. Called once, after activation. */
    public void showDefaults() {
        for (OverlayKind kind : kinds()) {
            if (kind.isVisibleByDefault()) show(kind.id(), true);
        }
    }

    /** The element one is drawn by, or null while it has never been shown. */
    @Nullable
    public UIElement elementOf(String id) {
        return built.get(id);
    }

    @Nullable
    private UIElement build(String id) {
        UIElement existing = built.get(id);
        if (existing != null) return existing;
        for (OverlayKind kind : kinds()) {
            if (!kind.id().equals(id) || kind.factory() == null) continue;
            UIElement element = kind.factory().apply(ctx);
            element.setHitTest(false);
            element.setDisplayed(false);
            if (kind.isInPlane()) ctx.surface().place(element, 0f, 0f);
            else ctx.surface().addOverlay(element);
            built.put(id, element);
            return element;
        }
        return null;
    }
}
