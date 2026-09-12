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
 * <p><b>Answer for the point; do not put art in a slot.</b> A widget implements {@link CursorSource} and
 * the engine asks it while the pointer is over it, which is what stops art surviving onto another panel:</p>
 *
 * <pre>{@code
 * final class RotateKnob extends UIElement implements CursorSource {
 *     public CursorDecoration artAt(float x, float y) {
 *         if (!overTheRim(x, y)) return null;   // null hands the question to whoever is outward
 *         return (ctx, px, py) -> RotationCursor.paint(ctx, px, py, angleAt(x, y));
 *     }
 * }
 * }</pre>
 *
 * <p>A live gesture may push instead, for art that belongs to the drag rather than to a place — it holds
 * until the handle is disposed, wherever the pointer goes:</p>
 *
 * <pre>{@code
 * Disposable art = input.setCursorDecoration(myDecoration);
 * art.dispose();   // the gesture ended
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
