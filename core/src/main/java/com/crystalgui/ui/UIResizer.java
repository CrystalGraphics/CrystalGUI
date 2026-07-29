package com.crystalgui.ui;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * One grab handle implementing {@code resize:}. An element whose {@code resize} is not
 * {@link Resize#NONE} gets a set of these as internal children — see {@link Handle}.
 *
 * <p>Engine structure rather than a widget, which is why it lives in {@code ui/} and not
 * {@code ui.elements/}: nobody constructs one, it has no public API, and it exists only because a
 * style property said so. Same relationship {@code __thumb__} has to {@code Slider}, except the
 * trigger is CSS rather than a constructor.</p>
 *
 * <h3>Eight handles is not a divergence</h3>
 * <p>CSS UI 4 says only that the UA "presents a bidirectional resizing mechanism" — it never
 * prescribes a single bottom-right grabber. Browsers ship one because theirs lives in the scrollbar
 * corner and has nowhere else to go; we draw our own, so all four edges and all four corners are
 * available. LDLib2's {@code WindowDragHelper.ResizeHandle} offers the same eight, which is where the
 * idea came from.</p>
 *
 * <p>Position and appearance are left entirely to {@code default.css} via the per-handle classes —
 * no pixel values here, per the usual rule.</p>
 */
final class UIResizer extends UIElement {

    static final String RESIZER_CLASS = "__resizer__";

    /**
     * Which edges a handle moves.
     *
     * <p>{@code dx}/{@code dy} are −1, 0 or +1: the sign says which edge follows the pointer, and a
     * <b>negative</b> one means the opposite edge stays put — so the element has to move as well as
     * resize. That is the entire reason {@link UIElement#applyResizeOrigin} exists, and it is also
     * why the web only ever offers the bottom-right corner: that is the one handle that never needs
     * to reposition anything.</p>
     */
    enum Handle {
        TOP(0, -1), BOTTOM(0, 1), LEFT(-1, 0), RIGHT(1, 0),
        TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1);

        final int dx, dy;

        Handle(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        /** Lower-case, dash-separated: {@code TOP_LEFT} → {@code __resizer-top-left__}. */
        String styleClass() {
            return "__resizer-" + name().toLowerCase().replace('_', '-') + "__";
        }

        /**
         * A handle exists only if <em>every</em> axis it touches is resizable. So
         * {@code resize: horizontal} yields the two side edges and no corners — a corner would imply
         * a vertical resize the mode forbids.
         */
        boolean appliesTo(Resize mode) {
            if (!mode.isResizable()) return false;
            if (dx != 0 && !mode.allowsWidth()) return false;
            return dy == 0 || mode.allowsHeight();
        }
    }

    private final Handle handle;

    /** Box at the moment the drag began. Resizing accumulates from here rather than from the live
     * box: the box changes as we resize it, so reading it each frame would compound the delta and the
     * element would race away from the cursor. */
    private float startWidth, startHeight, startLeft, startTop;

    UIResizer(Handle handle) {
        this.handle = handle;
        addClass(RESIZER_CLASS);
        addClass(handle.styleClass());

        // Out of flow: handles overlay their parent's edges and must not consume a slot in its
        // layout, or adding `resize:` to an element would visibly reflow its content.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));

        onMouseDown.attachListener((el, event) -> beginResize(event.getPosition().x(), event.getPosition().y()),
                false, false);
    }

    Handle handle() {
        return handle;
    }

    private void beginResize(float pointerX, float pointerY) {
        UIElement target = getParent();
        if (target == null || target.getAttachedWindow() == null) return;

        startWidth = target.getRuntimeCache().getWidth();
        startHeight = target.getRuntimeCache().getHeight();
        startLeft = target.resizeOriginLeft();
        startTop = target.resizeOriginTop();

        UIDragController drag = target.getAttachedWindow().getInputHandler().getDragController();
        // Positional drag: no payload, no drop targets, and no activation threshold — a resize must
        // track the very first pixel or small adjustments would be impossible.
        drag.startDrag(this, pointerX, pointerY, (mx, my, sx, sy, dx, dy) -> applyResize(target, dx, dy));
    }

    private void applyResize(UIElement target, float deltaX, float deltaY) {
        Resize mode = target.getStyle().getGeneralGroup().resize();
        if (!handle.appliesTo(mode)) return;

        // A trailing edge grows by the drag; a leading edge grows by its negation and moves the
        // origin to match, so the opposite edge stays where it was.
        final float width = startWidth + handle.dx * deltaX;
        final float height = startHeight + handle.dy * deltaY;

        // INLINE origin, NOT IMPORTANT. The spec is explicit that a user resize writes the style
        // attribute "without !important", so an author's !important rule still wins. Everything else
        // in this engine that writes geometry from code uses IMPORTANT; this is the deliberate
        // exception, and swapping it would silently break that guarantee. See Resize's javadoc.
        //
        // INLINE also happens to be StyleGroup's default priority — the same slot `element.layout(…)`
        // writes to. That is the correct collision, not an accident: the spec says the UA replaces
        // "existing property declaration(s)" in the style attribute, which is precisely where an
        // author's inline width already lives. It also means a resize is NOT undone by setting
        // `resize: none` later, matching browsers.
        //
        // No clamping here either: min-width/max-width/min-height/max-height are the spec's *only*
        // constraints on a resize, and Taffy already applies them. Clamping again would double-apply
        // them and desync from whatever the cascade currently says.
        StyleGroup.inlinePipeline(target.getStyle().getLayoutGroup(), l -> {
            if (handle.dx != 0) l.width(Math.max(0f, width));
            if (handle.dy != 0) l.height(Math.max(0f, height));
        });

        if (handle.dx < 0 || handle.dy < 0) {
            target.applyResizeOrigin(
                    handle.dx < 0 ? startLeft + deltaX : target.resizeOriginLeft(),
                    handle.dy < 0 ? startTop + deltaY : target.resizeOriginTop());
        }
    }
}
