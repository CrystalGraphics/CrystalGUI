package com.crystalgui.widget.surface;

import java.util.List;

import org.joml.Vector2f;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.canvas.WorldRect;

/**
 * The unbounded plane a surface's items sit on, and the view onto it.
 *
 * <p>Reached through {@link SurfaceContext#surface()} — it is what an extension is given instead of the
 * canvas widget itself, so a feature can place, measure and frame without being able to restructure the
 * editor. Nothing here knows what an item <em>is</em>; that is {@link SurfacePolicy}.</p>
 *
 * <pre>{@code
 * Surface surface = ctx.surface();
 * surface.place(node, 40f, 40f);                        // world units
 * Vector2f world = surface.toWorld(event.getPosition().x(), event.getPosition().y());
 * surface.frame(surface.boundsOf(node));
 * }</pre>
 *
 * <p>Three spaces, and mixing them is the classic bug: <b>world</b> is where items live and what you
 * author; <b>viewport</b> is the surface's own layout space; <b>raw</b> is pointer pixels as delivered
 * by a {@code MouseEvent}. {@link #toWorld} takes raw pixels, {@link #toViewport} answers layout space.
 * </p>
 */
public final class Surface {

    private final CanvasView canvas;

    /** Fires after any change to zoom or pan, from any source. */
    public final Signal.Action onDidChangeView;

    Surface(CanvasView canvas) {
        this.canvas = canvas;
        this.onDidChangeView = canvas.onViewChanged;
    }

    // ── Items ───────────────────────────────────────────────────────────────

    /** Everything on the plane, in insertion order. Overlays are not here. */
    public List<UIElement> items() {
        return List.copyOf(canvas.content().children());
    }

    /** Adds {@code item} at a world position. */
    public Surface place(UIElement item, float worldX, float worldY) {
        canvas.addNode(item, worldX, worldY);
        return this;
    }

    /** Moves an item already on the plane. Rewrites its position; nothing is rebuilt. */
    public Surface move(UIElement item, float worldX, float worldY) {
        canvas.moveNode(item, worldX, worldY);
        return this;
    }

    public Surface remove(UIElement item) {
        canvas.content().remove(item);
        return this;
    }

    /** Where an item is, in world units, from the last computed layout. */
    public WorldRect boundsOf(UIElement item) {
        return canvas.worldBoundsOf(item);
    }

    /** Everything on the plane, as one rectangle. */
    public WorldRect contentBounds() {
        return canvas.contentBounds();
    }

    /** What the viewport currently shows, in world units. */
    public WorldRect visibleBounds() {
        return canvas.visibleWorldRect();
    }

    // ── The view ────────────────────────────────────────────────────────────

    public float zoom() {
        return canvas.getZoom();
    }

    public Surface setZoom(float zoom) {
        canvas.setZoom(zoom);
        return this;
    }

    /** Zooms about a raw pointer position, so the point under the cursor stays put. */
    public Surface zoomAt(float zoom, float rawX, float rawY) {
        canvas.zoomAt(zoom, rawX, rawY);
        return this;
    }

    public Surface panBy(float dx, float dy) {
        canvas.panBy(dx, dy);
        return this;
    }

    public Surface centerOn(float worldX, float worldY) {
        canvas.centerOnWorld(worldX, worldY);
        return this;
    }

    /** Fits everything on the plane into the viewport. */
    public Surface fit() {
        canvas.fitToContent();
        return this;
    }

    /** Raw pointer pixels to world units. */
    public Vector2f toWorld(float rawX, float rawY) {
        return canvas.screenToWorld(rawX, rawY);
    }

    /** World units to the surface's own layout space. */
    public Vector2f toViewport(float worldX, float worldY) {
        return canvas.worldToViewport(worldX, worldY);
    }

    // ── Theme ───────────────────────────────────────────────────────────────

    /**
     * Installs a stylesheet on the window this surface is in, once.
     *
     * <p>Safe to call every frame — it returns immediately when the sheet is already there, which is
     * how a consumer gets its theme in without knowing when a window arrives.</p>
     */
    public Surface installSheet(String sheetId) {
        UIDocument window = canvas.document();
        if (window == null) return this;
        StyleSheet sheet = StyleSheetRegistry.of(sheetId);
        if (!window.styles().getSheets().contains(sheet)) window.styles().addStylesheet(sheet);
        return this;
    }

    /** The canvas itself. Package-private: a feature that could reach this could restructure the editor. */
    CanvasView canvas() {
        return canvas;
    }
}
