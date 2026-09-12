package com.crystalgui.ui.service;

import javax.annotation.Nullable;

import com.crystalgui.core.cursor.Cursor;

/**
 * <b>What the pointer looks like over this element</b> — implemented by a widget, asked by the engine.
 *
 * <p>For chrome the {@code cursor} property cannot state: a canvas whose answer depends on WHERE in it
 * the pointer is, which is every gesture surface. A transform box says "resize" over a handle, "rotate"
 * just outside a corner and "move" inside, and none of that is a property of any element the pointer is
 * actually over.</p>
 *
 * <pre>{@code
 * public final class Canvas extends UIElement implements CursorSource {
 *     public Cursor cursorAt(float x, float y) {
 *         return handleNear(x, y) ? Cursor.NWSE_RESIZE : null;   // null: the cascade's answer stands
 *     }
 * }
 * }</pre>
 *
 * <h3>Asked, not told, and that is the whole point</h3>
 *
 * <p>The engine resolves the cursor every frame by walking out from whatever the pointer is over, and the
 * first source that answers wins. So a widget answers only for points inside itself, and a pointer that
 * has moved on to another panel never asks it at all.</p>
 *
 * <p>The alternative — a gesture PUSHING a cursor into a global slot and withdrawing it later — cannot be
 * made to work for a surface that re-decides its chrome every frame, because there is no moment at which
 * it is told the pointer has left: it simply stops being asked about it. Three separate leaks were fixed
 * by hand before the mechanism was turned around, each one a cursor or a piece of art left holding the
 * whole window. {@link Input#setCursorOverride} remains for the case it was always right for: a live
 * gesture that owns the pointer and has a definite end.</p>
 *
 * <p>Both methods are given the pointer in the DOCUMENT's own coordinates — the space
 * {@link Input#pointer()} reports — so an implementor converts into whatever space it thinks in.</p>
 */
public interface CursorSource {

    /** The cursor over this point, or null to leave the answer to the cascade. */
    @Nullable
    default Cursor cursorAt(float x, float y) {
        return null;
    }

    /** Art to draw AT the pointer over this point, or null for none. @see CursorDecoration */
    @Nullable
    default CursorDecoration artAt(float x, float y) {
        return null;
    }
}
