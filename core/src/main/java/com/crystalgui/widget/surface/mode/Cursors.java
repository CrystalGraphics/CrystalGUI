package com.crystalgui.widget.surface.mode;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.cursor.Cursor;

import com.crystalgui.ui.dom.UIDocument;

/**
 * What the pointer looks like while a gesture OWNS it.
 *
 * <pre>{@code
 * ctx.cursors().set(Cursor.GRABBING);   // a drag begins
 * ctx.cursors().clear();                  // and ends
 * }</pre>
 *
 * <p>For a gesture with a definite beginning and end, and nothing else: a marquee is over no element, and
 * a drag must keep its cursor after the pointer has left the thing it started on — neither is something
 * the {@code cursor} property can say.</p>
 *
 * <p><b>Chrome a surface re-decides every frame does NOT belong here.</b> Such a surface is never told the
 * pointer has left it — it simply stops being asked — so a cursor pushed from one is a cursor nothing
 * takes down, and it holds the whole window. That case is
 * {@link com.crystalgui.ui.service.CursorSource}, which the engine ASKS while the pointer is over the
 * element: a canvas answers for its own area and cannot answer for anywhere else.</p>
 *
 * <p>A gesture that forgets to {@link #clear} leaves the window pointing the wrong way, so set it in the
 * same place you clear it.</p>
 */
public final class Cursors {

    private final Supplier<UIDocument> window;

    @Nullable
    private Cursor current;

    public Cursors(Supplier<UIDocument> window) {
        this.window = window;
    }

    /** Forces {@code cursor} until it is cleared. Null is {@link #clear}. */
    public void set(@Nullable Cursor cursor) {
        if (current == cursor) return;
        current = cursor;
        UIDocument document = window.get();
        if (document != null) document.input().setCursorOverride(cursor);
    }

    /** Gives the pointer back to the cascade. */
    public void clear() {
        set(null);
    }

    @Nullable
    public Cursor current() {
        return current;
    }
}
