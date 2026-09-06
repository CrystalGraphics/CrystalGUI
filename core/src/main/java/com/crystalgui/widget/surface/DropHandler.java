package com.crystalgui.widget.surface;

/**
 * What a surface does with something dropped on it — a file from the explorer, a colour token, a
 * template, a node type dragged out of a library panel.
 *
 * <p>Handlers are asked in registration order and the first one that {@link #accepts} the payload gets
 * it. Nothing else on the surface sees the drop.</p>
 *
 * <pre>{@code
 * ctx.registerDropHandler(new DropHandler() {
 *     public boolean accepts(Object payload) { return payload instanceof Resource; }
 *     public boolean drop(Object payload, float worldX, float worldY) {
 *         return document.placeImage((Resource) payload, worldX, worldY);
 *     }
 * });
 * }</pre>
 *
 * <p>The payload is whatever the drag carried ({@code Drag.startWithPayload}); check the type, never
 * assume it. Returning false from {@link #drop} after accepting leaves the drop unhandled, so the
 * pointer feedback stays honest.</p>
 */
public interface DropHandler {

    /** Whether this handler understands the payload — asked while the pointer is still moving. */
    boolean accepts(Object payload);

    /** @return whether the drop was handled, at the plane point it landed on. */
    boolean drop(Object payload, float worldX, float worldY);
}
