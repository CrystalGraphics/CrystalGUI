package com.crystalgui.app.uibuilder.inspect;

import javax.annotation.Nullable;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.canvas.WorldRect;

/**
 * The box model of whatever is picked, drawn over it — Chrome's element highlight.
 *
 * <pre>{@code
 * BoxModelOverlay overlay = BoxModelOverlay.over(document);
 * overlay.follow(picked);
 * overlay.follow(null);      // and it draws nothing
 * }</pre>
 *
 * <h3>Read in an afterLayout hook, never in paint</h3>
 *
 * <p>An ordinary per-frame hook runs BEFORE layout, so anything positioned from a measured box would be
 * a frame behind — visible as an outline that lags a resize by exactly one frame and snaps into place
 * when it stops. {@code afterLayout} is the hook that may read geometry, and this only reads: it moves
 * nothing and adds nothing, which is what a post-layout pass is allowed to do.</p>
 *
 * <h3>A null box is the ordinary state</h3>
 *
 * <p>{@code box()} is null for anything hidden, frozen, {@code display: none} or simply not in a
 * document — and the thing being inspected is somebody else's live screen, which does all four while
 * being looked at. Every read here is guarded; the overlay draws nothing rather than throwing on the
 * frame a picked element is hidden.</p>
 */
public final class BoxModelOverlay extends UIElement {

    public static final Name NAME = Name.of("boxmodeloverlay");

    /** Argb fills, Chrome's: content blue, padding green, border tan, margin orange. */
    private static final int CONTENT = 0x7073A5C8;

    private static final int PADDING = 0x60A5C873;

    private static final int BORDER = 0x60C8B473;

    private static final int MARGIN = 0x50C89473;

    @Nullable
    private UIElement target;

    @Nullable
    private WorldRect border;

    @Nullable
    private WorldRect padding;

    @Nullable
    private WorldRect content;

    @Nullable
    private WorldRect margin;

    private BoxModelOverlay() {
        super(NAME);
        // NOT HIT-TESTABLE. An overlay that took the pointer would sit between the user and the thing it
        // is describing, and the next pick would land on the overlay itself.
        set(Attribute.HIT_TEST, false);
    }

    /**
     * The overlay for {@code document}, attached and ticking.
     *
     * <p>Registers its own {@code afterLayout} hook, owned by this element — so it stops when the overlay
     * leaves the tree rather than running invisibly for the life of the window.</p>
     */
    public static BoxModelOverlay over(UIDocument document) {
        BoxModelOverlay overlay = new BoxModelOverlay();
        if (document == null) return overlay;
        document.append(overlay);
        document.animation().afterLayout(overlay, delta -> {
            overlay.measure();
            return true;
        });
        return overlay;
    }

    /** Draws over {@code element}, or nothing when null. */
    public void follow(@Nullable UIElement element) {
        this.target = element;
        measure();
    }

    @Nullable
    public UIElement target() {
        return target;
    }

    /** The border box in world space, or null when there is nothing laid out to describe. */
    @Nullable
    public WorldRect borderBox() {
        return border;
    }

    @Nullable
    public WorldRect contentBox() {
        return content;
    }

    @Nullable
    public WorldRect marginBox() {
        return margin;
    }

    /** Re-reads the target's geometry. Cheap, and safe on a frame where it has none. */
    public void measure() {
        Box box = target == null ? null : target.box();
        if (box == null) {
            border = null;
            padding = null;
            content = null;
            margin = null;
            return;
        }
        float x = box.x();
        float y = box.y();
        float width = box.width();
        float height = box.height();

        border = new WorldRect(x, y, width, height);
        margin = new WorldRect(x - box.margin().left, y - box.margin().top,
                width + box.margin().left + box.margin().right,
                height + box.margin().top + box.margin().bottom);
        padding = inset(border, box.border().left, box.border().top,
                box.border().right, box.border().bottom);
        content = inset(padding, box.padding().left, box.padding().top,
                box.padding().right, box.padding().bottom);
    }

    private static WorldRect inset(WorldRect rect, float left, float top, float right, float bottom) {
        return new WorldRect(rect.x() + left, rect.y() + top,
                Math.max(0f, rect.width() - left - right),
                Math.max(0f, rect.height() - top - bottom));
    }

    /**
     * Draws the four boxes, outermost first.
     *
     * <p>Every box paints in its OWN space, so the world rectangles measured above are shifted by this
     * overlay's own origin on the way out — the one conversion, in the one place that needs it.</p>
     */
    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        if (border == null || box == null) return;
        fill(ctx, margin, MARGIN, box);
        fill(ctx, border, BORDER, box);
        fill(ctx, padding, PADDING, box);
        fill(ctx, content, CONTENT, box);
    }

    private static void fill(CgUiPaintContext ctx, @Nullable WorldRect rect, int argb, Box own) {
        if (rect == null || rect.width() <= 0f || rect.height() <= 0f) return;
        ctx.fillRect(rect.x() - own.x(), rect.y() - own.y(), rect.width(), rect.height(), argb);
    }
}
