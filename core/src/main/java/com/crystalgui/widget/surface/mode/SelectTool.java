package com.crystalgui.widget.surface.mode;

import java.util.List;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;

import javax.annotation.Nullable;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceContext;
import com.crystalgui.widget.surface.SurfacePolicy;

/**
 * The engine's one built-in tool: click to select, drag to move, band to select several.
 *
 * <p>The Select tool is what a surface does when no other tool is current, and it is registered like any
 * other — {@code SelectExtension} contributes it, and a surface built with no extensions has not even
 * this one.</p>
 *
 * <p>The press rule is the single most common thing to get wrong in a canvas editor: <b>pressing an item
 * that is already selected leaves the selection alone</b>. The gesture is "click one of the five I
 * picked and drag them all", and the naive rule — a press selects only what it hit — silently deselects
 * four of them and moves one.</p>
 */
public final class SelectTool implements Tool {

    private final SurfaceContext ctx;
    private final Marquee marquee;
    private final MoveGesture move;

    public SelectTool(SurfaceContext ctx) {
        this.ctx = ctx;
        this.marquee = new Marquee(ctx.surface(), ctx.picking(), ctx.selection());
        this.move = new MoveGesture(ctx.surface(), ctx.policy(SurfacePolicy.class), ctx.edits());
    }

    /** The band, for a theme or a test. */
    public Marquee marquee() {
        return marquee;
    }

    @Override
    public boolean pointerDown(float rawX, float rawY, int button, int modifiers) {
        if (button != CgMouseCodes.LEFT_BUTTON) return false;

        UIElement item = ctx.picking().itemAt(rawX, rawY);
        if (item != null) {
            press(item, CgModifiers.hasShift(modifiers));
            ctx.surface().raise(item);
            List<UIElement> moving = ctx.selection().contains(item) ? ctx.selection().items() : List.of(item);
            move.begin(rawX, rawY, moving);
            return true;
        }
        marquee.begin(rawX, rawY, CgModifiers.hasShift(modifiers), CgModifiers.hasAlt(modifiers));
        return true;
    }

    /**
     * Shift toggles; anything else selects only what was hit, and only when it was not already selected.
     *
     * @see SelectTool the class note on why the second half matters
     */
    private void press(UIElement item, boolean additive) {
        pendingSelectOnRelease = null;
        if (additive) {
            ctx.selection().toggle(item);
        } else if (!ctx.selection().contains(item)) {
            ctx.selection().selectOnly(item);
        } else {
            // ALREADY SELECTED, SO THE PRESS DECIDES NOTHING YET. Collapsing here would make it
            // impossible to drag a set: the press that starts the drag would first throw the set away.
            // Collapsing NEVER leaves the selection stuck instead -- shift-click three, click one, and
            // all three stay picked with no way back to one. The release settles it. @see #pointerUp
            pendingSelectOnRelease = item;
        }
    }

    /** @see #pointerUp */
    @Nullable
    private UIElement pendingSelectOnRelease;

    /**
     * <b>A click that turned out not to be a drag collapses the selection to what it hit.</b>
     *
     * <p>{@code isActivated}, not {@code isDragging}. A drag is ARMED on mouse-down, so {@code isDragging}
     * is true for every ordinary click and testing it would suppress the collapse always — which is the
     * bug this fixes, from the other end. {@code isActivated} only becomes true once the pointer has
     * passed the threshold, which is exactly "this turned out to be a drag". {@code ListView} carries the
     * same note, having had the same defect.</p>
     */
    @Override
    public boolean pointerUp(float rawX, float rawY, int button, int modifiers) {
        UIElement pending = pendingSelectOnRelease;
        pendingSelectOnRelease = null;
        if (button != CgMouseCodes.LEFT_BUTTON || pending == null) return false;
        if (ctx.picking().itemAt(rawX, rawY) != pending) return false;
        if (dragActivated()) return false;
        ctx.selection().selectOnly(pending);
        return true;
    }

    /** Whether a real drag ran — a live one is an InputMode on this engine, not a controller to ask. */
    private boolean dragActivated() {
        UIElement element = ctx.surface().element();
        UIDocument window = element == null ? null : element.document();
        Drag drag = window == null ? null : window.input().mode(Drag.class);
        return drag != null && drag.isActivated();
    }

    @Override
    public boolean pointerMoved(float rawX, float rawY, int modifiers) {
        // The DRAG service owns the pointer once a gesture is live; this only keeps the hover current.
        ctx.picking().setHovered(ctx.picking().itemAt(rawX, rawY));
        return false;
    }

    @Override
    public void deactivated() {
        ctx.picking().setHovered(null);
    }

    /** Whichever modifiers the platform reports now — a drag callback carries none of its own. */
    static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

}
