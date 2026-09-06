package com.crystalgui.widget.surface.overlay;

import javax.annotation.Nullable;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.dnd.CanvasOverlayMove;
import com.crystalgui.widget.surface.Surface;

/**
 * A panel that floats over the plane, dragged by a handle and remembered where it was left — a preview,
 * a properties board, a minimap.
 *
 * <pre>{@code
 * FloatingPanel preview = FloatingPanel.over(surface, panel, panel.titleBar());
 * preview.placeAt(24f, 24f);
 * String saved = preview.rect();        // into the session
 * preview.applyRect(saved);             // and back
 * }</pre>
 *
 * <p>It does not pan or zoom with the content: a floating panel is <em>over</em> the surface, not on it.
 * Position is kept in the surface's own space and re-clamped when the surface resizes.</p>
 *
 * <p><b>A restored position is a deliberate one</b>, so {@link #applyRect} marks the panel placed. The
 * re-clamp that tracks a resizing surface is gated on having been placed, which otherwise only a drag
 * ever sets — a restored panel then ignores the surface shrinking until it has been grabbed once, which
 * reads as a redraw bug rather than as a flag nothing set.</p>
 */
public final class FloatingPanel {

    private final UIElement panel;
    private final CanvasOverlayMove move;

    private FloatingPanel(Surface surface, UIElement panel, UIElement handle) {
        this.panel = panel;
        surface.addOverlay(panel);
        this.move = CanvasOverlayMove.install(panel, handle, () -> surface.element());
    }

    /** Adds {@code panel} over {@code surface}, dragged by {@code handle}. */
    public static FloatingPanel over(Surface surface, UIElement panel, UIElement handle) {
        return new FloatingPanel(surface, panel, handle);
    }

    public UIElement element() {
        return panel;
    }

    /** Moves it, clamped into the surface. */
    public FloatingPanel placeAt(float left, float top) {
        move.placeAt(left, top);
        return this;
    }

    /** Says this position was chosen rather than defaulted. @see FloatingPanel */
    public FloatingPanel markPlaced() {
        move.markPlaced();
        return this;
    }

    public boolean isPlaced() {
        return move.isPlaced();
    }

    /**
     * Where it is, as {@code left,top,width,height} — or empty when there is nothing worth recording.
     *
     * <p>Empty for an unmeasured panel, and a caller must not write that: it would erase a good rect
     * rather than leave the one already stored.</p>
     */
    public String rect() {
        UIElement block = panel.parentElement();
        if (block == null) return "";
        Box box = panel.box();
        Box blockBox = block.box();
        if (box == null || blockBox == null) return "";
        if (box.width() <= 0f || box.height() <= 0f) return "";
        if (blockBox.width() < box.width() || blockBox.height() < box.height()) return "";
        // The panel's origin IN THE BLOCK'S SPACE. Box.x() is parent-relative, so subtracting two boxes'
        // raw offsets only means anything when they share a parent.
        var origin = Box.originIn(box, blockBox);
        return origin.x() + "," + origin.y() + "," + box.width() + "," + box.height();
    }

    /**
     * Puts it back where {@link #rect} said, and marks it placed.
     *
     * @return whether anything was applied — false for an empty or malformed record
     */
    public boolean applyRect(@Nullable String recorded) {
        if (recorded == null || recorded.isEmpty()) return false;
        String[] parts = recorded.split(",");
        if (parts.length != 4) return false;
        try {
            float left = Float.parseFloat(parts[0]);
            float top = Float.parseFloat(parts[1]);
            float width = Float.parseFloat(parts[2]);
            float height = Float.parseFloat(parts[3]);
            if (width > 0f && height > 0f) {
                StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(),
                        l -> l.width(width).height(height));
            }
            move.placeAt(left, top);
            move.markPlaced();
            return true;
        } catch (NumberFormatException malformed) {
            // A session record somebody edited by hand. Ignored rather than thrown: a panel in the
            // wrong place is recoverable and a workbench that will not open is not.
            return false;
        }
    }
}
