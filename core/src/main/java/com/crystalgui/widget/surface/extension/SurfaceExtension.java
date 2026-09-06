package com.crystalgui.widget.surface.extension;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * <b>A feature that attaches itself to a surface</b> — one interface, one moment, one handle back.
 *
 * <p>This is how anything reaches an editing surface: a tool, an overlay, a view mode, a source of
 * things to insert, a drop handler, an inspector section, a set of commands. Implement it, ship a
 * services entry, and a consumer enables it by naming your id. The engine's own Select tool arrives
 * through exactly this door, so a third-party feature is never second-class.</p>
 *
 * <pre>{@code
 * public final class GridExtension implements SurfaceExtension {
 *     public static final String ID = "mymod:grid";
 *
 *     public GridExtension() { }                    // ServiceLoader's rule
 *     public String id() { return ID; }
 *     public Disposable activate(SurfaceContext surface) {
 *         return surface.registerOverlay(GRID);
 *     }
 * }
 * }</pre>
 *
 * <p>plus one line in
 * {@code META-INF/services/com.crystalgui.widget.surface.extension.SurfaceExtension}.</p>
 *
 * <h3>The handle is the contract</h3>
 *
 * <p>{@link #activate} returns everything it registered, as one {@link Disposable}, so nothing has to
 * enumerate what any extension did when a surface closes. Use {@code Disposer} or a small composite when
 * you register more than one thing.</p>
 *
 * <h3>Written against {@link SurfaceContext}, never the engine</h3>
 *
 * <p>An extension that could name {@code SurfaceEditor} could reach into it, which is the whole reason
 * the interface exists. It must also not name another feature: two features that talk to each other are
 * the thing this seam is here to prevent.</p>
 *
 * <h3>Available is not enabled</h3>
 *
 * <p>Shipping the jar makes the feature <em>available</em>; a consumer naming its id is what turns it
 * <em>on</em>, which is how one engine serves a node graph and a UI builder without either seeing the
 * other's tools. An id nothing ships is a logged absence rather than an error.</p>
 */
public interface SurfaceExtension {

    /** Namespaced, stable, and the string a consumer names to enable it. */
    String id();

    /**
     * Attaches to {@code surface}, and hands back everything that has to be taken away again.
     *
     * <p>Called once per surface, while it is being built — so the plane exists and a window does not.
     * Anything needing geometry, a document or a frame waits for one rather than asking here.</p>
     */
    Disposable activate(SurfaceContext surface);
}
