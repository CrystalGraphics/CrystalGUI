package com.crystalgui.widget.surface.mode;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.cursor.Cursor;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * What the pointer looks like while a gesture owns it.
 *
 * <pre>{@code
 * ctx.cursors().set(Cursor.GRABBING);   // a drag begins
 * ctx.cursors().clear();                  // and ends
 * }</pre>
 *
 * <p>An override, deliberately: a marquee is over no element, and a resize drag has to keep its arrow
 * after the pointer has left the handle — neither is something the {@code cursor} property can say. Every
 * other cursor in the engine is the cascade's.</p>
 *
 * <p><b>It reaches only this surface.</b> The pointer leaves for another panel without telling whoever set
 * it — a tool that re-decides its cursor every frame has no leave to react to — so the override is scoped
 * and simply stops applying out there. A drag still keeps its cursor anywhere, because pointer capture
 * resolves the hover to the capturing element.</p>
 *
 * <p>A gesture that forgets to {@link #clear} still leaves this surface pointing the wrong way, so set it
 * in the same place you clear it.</p>
 */
public final class Cursors {

    private final Supplier<UIDocument> window;

    /** The surface this speaks for: where an override applies. */
    private final Supplier<UIElement> within;

    @Nullable
    private Cursor current;

    public Cursors(Supplier<UIDocument> window, Supplier<UIElement> within) {
        this.window = window;
        this.within = within;
    }

    /** Forces {@code cursor} while the pointer is over this surface, until it is cleared. Null is {@link #clear}. */
    public void set(@Nullable Cursor cursor) {
        if (current == cursor) return;
        current = cursor;
        UIDocument document = window.get();
        if (document != null) document.input().setCursorOverride(cursor, within.get());
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
