package com.crystalgui.widget.surface.mode;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgCursor;

import com.crystalgui.ui.dom.UIDocument;

/**
 * What the pointer looks like while a gesture owns it.
 *
 * <pre>{@code
 * ctx.cursors().set(CgCursor.GRABBING);   // a drag begins
 * ctx.cursors().clear();                  // and ends
 * }</pre>
 *
 * <p>An override, deliberately: a marquee is over no element, and a resize drag has to keep its arrow
 * after the pointer has left the handle — neither is something the {@code cursor} property can say. Every
 * other cursor in the engine is the cascade's.</p>
 *
 * <p>A gesture that forgets to {@link #clear} leaves the whole window pointing the wrong way, so set it
 * in the same place you clear it.</p>
 */
public final class Cursors {

    private final Supplier<UIDocument> window;

    @Nullable
    private CgCursor current;

    public Cursors(Supplier<UIDocument> window) {
        this.window = window;
    }

    /** Forces {@code cursor} until it is cleared. Null is {@link #clear}. */
    public void set(@Nullable CgCursor cursor) {
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
    public CgCursor current() {
        return current;
    }
}
