package com.crystalgui.desktop.app;

import java.util.Arrays;
import java.util.List;

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
 * <p>A popup is laid out against the application's document, which is as large as the window it last took input in
 * ({@code AppDocument.windowWidth}), so it centres and flips within that window; the shell's {@link PopupPortal} shows
 * it where it was laid out. Nothing is surfaced while nothing is shown.</p>
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
    /** One {x0, y0, x1, y1} per popup, in the same pixels as {@link #occupied}; empty when none. */
    private volatile float[][] shapes = NONE;
    private long seenRevision = -1L;
    @Nullable
    private volatile Runnable changed;

    private static final float[][] NONE = new float[0][];

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

    /**
     * The rectangle of each popup, {x0, y0, x1, y1} in the document's logical pixels: what the shell takes the pointer
     * over, since the space between two popups is not theirs. Empty when none is shown.
     */
    public float[][] rectangles() {
        return shapes;
    }

    /** What runs, on the application's thread, when the popups or their picture changed. */
    public void onChanged(@Nullable Runnable then) {
        this.changed = then;
    }

    /** The application's thread, after each commit of its document. */
    void committed() {
        Box box = box();
        boolean shown = box != null && !box.children().isEmpty();
        float[][] rects = shown ? rectangles(box) : NONE;
        float[] now = shown ? union(rects) : null;
        if (shown != hasClass(CLASS)) {
            if (shown) addClass(CLASS);
            else removeClass(CLASS);
        }
        long revision = box == null ? -1L : box.innerRevision();
        float[] before = occupied;
        float[][] shapesBefore = shapes;
        occupied = now;
        shapes = rects;
        if (revision == seenRevision && sameBounds(before, now) && Arrays.deepEquals(shapesBefore, rects)) return;
        seenRevision = revision;
        Runnable then = changed;
        if (then != null) then.run();
    }

    private static float[][] rectangles(Box layer) {
        // A box's world position is in surface pixels and its size is logical: the position is converted first.
        float scale = layer.tree().uiScale();
        List<Box> popups = layer.children();
        float[][] out = new float[popups.size()][];
        for (int i = 0; i < out.length; i++) {
            Box each = popups.get(i);
            float x = each.worldX() / scale, y = each.worldY() / scale;
            out[i] = new float[] {x, y, x + each.width(), y + each.height()};
        }
        return out;
    }

    private static float[] union(float[][] rects) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (float[] r : rects) {
            x0 = Math.min(x0, r[0]);
            y0 = Math.min(y0, r[1]);
            x1 = Math.max(x1, r[2]);
            y1 = Math.max(y1, r[3]);
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
