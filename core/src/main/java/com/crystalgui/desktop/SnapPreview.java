package com.crystalgui.desktop;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.desktop.manager.WindowManager;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.motion.WindowGeometryAnimation;
import com.crystalgui.desktop.motion.WindowMotion;
import com.crystalgui.desktop.window.SnapZones;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * The translucent rectangle that shows where a dragged window would snap, morphing out of the window and back into it.
 * The desktop owns one; a drag drives it.
 *
 * <pre>{@code
 * desktop.snapPreview().show(SnapZones.Zone.LEFT, frame);   // each frame of the drag; a no-op for an unchanged zone
 * desktop.snapPreview().hide();                              // abandoned: contracts back into the window
 * desktop.snapPreview().hideNow();                           // accepted: the window animates into the rect itself
 * }</pre>
 *
 * <p>Built once and hidden: it is {@code display: none} until shown, so it holds no box while idle. Unhittable, because
 * it only exists during a drag, which has pointer capture.</p>
 */
public final class SnapPreview {

    /** What the desktop's sheet addresses it by. */
    public static final String CLASS = "__snap-preview__";

    /** Short: it answers a question the hand is still asking. */
    private static final long NANOS = 150L * 1_000_000L;
    /** {@code OUT_QUAD}: a preview crosses much of the work area, and expo would cover a quarter of it in one frame. */
    private static final CgEasing EASING = CgEasings.OUT_QUAD;

    private final UIElement layer;
    private final WindowManager manager;
    private final Consumer<UIElement> host;
    private final UIElement preview = new UIElement();

    private boolean showing;
    @Nullable
    private SnapZones.Zone zone;
    @Nullable
    private WindowFrame source;
    @Nullable
    private WindowMotion motion;

    /**
     * @param layer   the work area's window layer; its box is the space the zones are cut from
     * @param manager the group whose live cuts the preview lands on
     * @param host    parents the preview on the layer, which refuses other non-window children
     */
    SnapPreview(UIElement layer, WindowManager manager, Consumer<UIElement> host) {
        this.layer = layer;
        this.manager = manager;
        this.host = host;
        preview.addClass(CLASS);
        preview.setHitTest(false);
        preview.setDisplayed(false);
    }

    /**
     * Shows or re-aims the rect {@code source} would land in, animating between shapes. Restarting the timeline per frame
     * would ease toward the target without arriving, hence the no-op for an unchanged zone.
     */
    public void show(SnapZones.Zone zone, WindowFrame source) {
        Box box = layer.box();
        if (box == null || box.width() <= 0f || box.height() <= 0f) return;
        if (showing && zone == this.zone) return;
        // The group's live cuts, not halves: snapping into an occupied zone gives that zone's current size.
        float[] to = SnapZones.rectFor(zone, box.width(), box.height(), manager.splitX(), manager.splitY());
        float[] from = showing ? live() : rectOf(source);
        this.source = source;
        this.zone = zone;
        showing = true;
        host.accept(preview);
        // Out-of-flow is the sheet's (`desktop .__snap-preview__`); the rect is inline, where a timeline writes it.
        stackUnder(source);
        preview.setDisplayed(true);
        animate(from, to, null);
    }

    /** Above every other window and below the dragged one: the dragged window takes a slot up. */
    private void stackUnder(WindowFrame dragged) {
        int z = manager.stackTop();
        manager.raise(dragged);
        // Inline, the slot a per-gesture value belongs in.
        StyleGroup.inlinePipeline(preview.getStyle().getGeneralGroup(), g -> g.zIndex(z));
    }

    /** Takes it off screen, contracting back into the window. Safe when it was never shown. */
    public void hide() {
        if (!showing) return;
        showing = false;
        zone = null;
        animate(live(), rectOf(source), this::hideNow);
    }

    /** Takes it off screen at once, for an accepted snap. */
    public void hideNow() {
        cancel();
        showing = false;
        zone = null;
        source = null;
        preview.setDisplayed(false);
    }

    /** Whether it is on screen or on its way off. */
    public boolean isShowing() {
        return showing;
    }

    private void animate(@Nullable float[] from, @Nullable float[] to, @Nullable Runnable then) {
        cancel();
        UIDocument document = layer.document();
        // No rect means no box, so nothing to animate between; `then` still runs so a hide cannot stick.
        if (to == null) {
            if (then != null) then.run();
            return;
        }
        if (from == null || !WindowAnimator.isEnabled() || document == null) {
            apply(to);
            if (then != null) then.run();
            return;
        }
        WindowGeometryAnimation next = new WindowGeometryAnimation(preview, () -> preview.parent() != null,
                from[0], from[1], from[2], from[3], to[0], to[1], to[2], to[3], true, true, NANOS, EASING, () -> {
                    motion = null;
                    if (then != null) then.run();
                });
        motion = next;
        // Owned by the element it moves: detaching it drops the hook.
        document.animation().every(preview, next);
    }

    private void cancel() {
        if (motion != null) motion.cancel();
        motion = null;
    }

    /** Inline: the slot {@link WindowGeometryAnimation} writes, so the two never disagree. */
    private void apply(float[] rect) {
        StyleGroup.inlinePipeline(preview.getStyle().getLayoutGroup(),
                l -> l.left(rect[0]).top(rect[1]).width(rect[2]).height(rect[3]));
    }

    /** Where it is now, in the work area's coordinates: a box's x is its parent's inset, so no subtraction. */
    @Nullable
    private float[] live() {
        Box box = preview.box();
        return box == null ? null : new float[] {box.x(), box.y(), box.width(), box.height()};
    }

    /** A frame's rect in the same space; {@code left()}/{@code top()} are already work-area insets. */
    @Nullable
    private static float[] rectOf(WindowFrame frame) {
        Box box = frame.box();
        return box == null ? null : new float[] {frame.left(), frame.top(), box.width(), box.height()};
    }
}
