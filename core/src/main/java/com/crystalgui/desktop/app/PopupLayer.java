package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * The top layer of an application's document, drawn into a surface of its own: its menus, tooltips, dropdowns and
 * completion lists, which the shell shows above the window they belong to.
 *
 * <pre>{@code
 * document.useTopLayer(new PopupLayer());     // by the host, once, as the document is made
 * popover.show();                             // the application's own code: unchanged
 * }</pre>
 *
 * <p>A popup is laid out against the application's document, whose size is its window's, so the application keeps it
 * inside the window; the shell's {@link PopupPortal} shows it where it was laid out. Nothing is surfaced while nothing
 * is shown.</p>
 */
public final class PopupLayer extends UIElement implements Surface.Owner {

    /** What makes it a surface, while something is shown; installed with the document. */
    public static final String SHEET = ".__popups__ { will-change: transform; }";

    private static final String CLASS = "__popups__";

    @Nullable
    private volatile Surface surface;
    /** Where the popups are, in the document's logical pixels as {x0, y0, x1, y1}; null when none. */
    @Nullable
    private volatile float[] occupied;
    private long seenRevision = -1L;
    @Nullable
    private volatile Runnable changed;

    public PopupLayer() {
        super(UIDocument.TOP_LAYER);
    }

    /** Its picture as it last painted, or null while nothing is shown. Any thread: a frame draws it. */
    @Nullable
    public Surface picture() {
        Surface held = surface;
        return occupied != null && held != null && held.hasPicture() ? held : null;
    }

    /** The rectangle the popups cover, {x0, y0, x1, y1} in logical pixels of the application's document; null if none. */
    @Nullable
    public float[] occupied() {
        return occupied;
    }

    /** What runs, on the application's thread, when the popups or their picture changed. */
    public void onChanged(@Nullable Runnable then) {
        this.changed = then;
    }

    /** The application's thread, after each commit of its document. */
    void committed() {
        Box box = box();
        boolean shown = box != null && !box.children().isEmpty();
        float[] now = shown ? bounds(box) : null;
        if (shown != hasClass(CLASS)) {
            if (shown) addClass(CLASS);
            else removeClass(CLASS);
        }
        long revision = box == null ? -1L : box.innerRevision();
        float[] before = occupied;
        occupied = now;
        if (revision == seenRevision && sameBounds(before, now)) return;
        seenRevision = revision;
        Runnable then = changed;
        if (then != null) then.run();
    }

    private static float[] bounds(Box layer) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        // A box's world position is in surface pixels and its size is logical: the position is converted first.
        float scale = layer.tree().uiScale();
        for (Box each : layer.children()) {
            float x = each.worldX() / scale, y = each.worldY() / scale;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x + each.width());
            y1 = Math.max(y1, y + each.height());
        }
        return new float[] {x0, y0, x1, y1};
    }

    private static boolean sameBounds(@Nullable float[] a, @Nullable float[] b) {
        if (a == null || b == null) return a == b;
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    @Override
    public void surface(Surface surface) {
        this.surface = surface;
    }

    /** Not kept: the picture is freed once nothing is shown. */
    @Override
    public boolean keepsSurface() {
        return false;
    }
}
