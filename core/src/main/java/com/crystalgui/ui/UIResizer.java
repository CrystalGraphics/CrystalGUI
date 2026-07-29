package com.crystalgui.ui;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.ui.input.UIDragController;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The grab handle that implements {@code resize:} — an internal child added to any element whose
 * {@code resize} is not {@link Resize#NONE}.
 *
 * <p>Engine structure rather than a widget, which is why it lives in {@code ui/} and not
 * {@code ui.elements/}: nobody constructs one, it has no public API, and it exists only because a
 * style property said so. It is the same relationship {@code __thumb__} has to {@code Slider}, except
 * the trigger is CSS rather than a constructor.</p>
 *
 * <p>The spec leaves the handle's appearance and position entirely to the UA, so both come from
 * {@code default.css} via the {@code __resizer__} class — no pixel values here, per the usual rule.</p>
 */
final class UIResizer extends UIElement {

    static final String RESIZER_CLASS = "__resizer__";

    /** Size at the moment the drag began. Resizing has to accumulate from there rather than from the
     * live box: the box changes as we resize it, so reading it each frame would compound the delta
     * and the element would race away from the cursor. */
    private float startWidth, startHeight;

    UIResizer() {
        addClass(RESIZER_CLASS);
        // Out of flow: the handle overlays its parent's corner and must not consume a slot in the
        // parent's layout, or adding `resize:` to an element would visibly reflow its content.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE));

        onMouseDown.attachListener((el, event) -> beginResize(event.getPosition().x(), event.getPosition().y()),
                false, false);
    }

    private void beginResize(float pointerX, float pointerY) {
        UIElement target = getParent();
        if (target == null || target.getAttachedWindow() == null) return;

        startWidth = target.getRuntimeCache().getWidth();
        startHeight = target.getRuntimeCache().getHeight();

        UIDragController drag = target.getAttachedWindow().getInputHandler().getDragController();
        // Positional drag: no payload, no drop targets, and no activation threshold — a resize must
        // track the very first pixel or small adjustments would be impossible.
        drag.startDrag(this, pointerX, pointerY, (mx, my, sx, sy, dx, dy) -> applyResize(target, dx, dy));
    }

    private void applyResize(UIElement target, float deltaX, float deltaY) {
        Resize mode = target.getStyle().getGeneralGroup().resize();
        if (!mode.isResizable()) return;

        final float width = startWidth + deltaX;
        final float height = startHeight + deltaY;

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
            if (mode.allowsWidth()) l.width(Math.max(0f, width));
            if (mode.allowsHeight()) l.height(Math.max(0f, height));
        });
    }

}
