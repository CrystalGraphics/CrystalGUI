package com.crystalgui.ui.service;

import com.crystalgui.render.CgUiPaintContext;

/**
 * <b>Cursor art this engine draws, over the top of everything, at the pointer.</b>
 *
 * <p>For the one thing a real cursor cannot do: turn. A cursor is a NATIVE object — {@code CursorArt}
 * is one picture and a loader caches one native per picture — so art that has to follow an angle can
 * only be quantised into a fixed set of pictures. Photoshop's rotate cursor is eight of them, wrong by
 * up to 22.5 degrees at exactly the moment you are watching the angle change. Drawing it instead is
 * smooth at every angle and every zoom, because it goes through {@code ctx.curve()} like the rest of
 * the design-time chrome.</p>
 *
 * <pre>{@code
 * // While a gesture is live -- set per frame, cleared with null.
 * input.setCursorDecoration((ctx, x, y) -> RotationCursor.paint(ctx, x, y, angle));
 *
 * // Or set once and released by a handle, the way everything else here is scoped.
 * Disposable art = input.setCursorDecoration(myDecoration);
 * }</pre>
 *
 * <h3>It AUGMENTS the cursor; it never replaces it</h3>
 *
 * <p>Keep a real cursor under it — {@code grab}/{@code grabbing} for a rotation, as Paint.NET does.
 * Anything drawn is a frame behind the pointer, which is invisible on a decoration beside the hand and
 * feels broken on the pointer itself. Felt hit exactly this and chose to regenerate a real cursor per
 * angle instead; that option is a browser's, where a cursor is a string. Here it is a native handle.</p>
 *
 * <p>Drawn after the tree, so it is above everything, clipped by nothing, and outside layer retention —
 * which is what a decoration that moves with the pointer needs, since no box moved.</p>
 *
 * @see Input#setCursorDecoration
 */
@FunctionalInterface
public interface CursorDecoration {

    /**
     * Draws at the pointer.
     *
     * @param x the pointer's position in the document's own coordinates — the same space
     *          {@link Input#pointer()} reports, with no pose applied
     */
    void paint(CgUiPaintContext ctx, float x, float y);
}
