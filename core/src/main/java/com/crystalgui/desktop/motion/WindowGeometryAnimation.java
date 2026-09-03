package com.crystalgui.desktop.motion;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.easing.Easing;
import com.crystalgui.ui.dom.UIElement;

import java.util.function.BooleanSupplier;

/**
 * A window changing SIZE — driven through layout, not through a transform.
 *
 * <h3>Why this one is not FLIP, when everything else here is</h3>
 *
 * <p>{@link WindowAnimation} animates a transform, which is right for open, close and minimise: those move
 * a window without changing what is inside it, so scaling the surface is exactly what a compositor does and
 * it costs no layout at all. A maximise is different in kind, because the window's CONTENT reflows.</p>
 *
 * <p>FLIP — jump layout to the destination, apply the inverse transform, ease it away — draws the
 * <b>destination's layout at the source's geometry</b>. Restoring a maximised window therefore reflowed the
 * text for a 600px-wide window and then drew that 3x magnified, so the animation opened on a frame of
 * enormous text and shrank to normal. Reported exactly that way: <em>"it eases back out to the original
 * size, it's just that the animation starts super scaled in"</em>. Maximising has the same fault pointing
 * the other way — content laid out full-screen and drawn at 31%, so the text starts far too small.</p>
 *
 * <p>GNOME solves it with a painted clone of the old frame cross-fading against the live actor, and it is
 * telling that it does so for {@code _sizeChangeWindow} and for <em>nothing else</em> — its map, destroy
 * and minimise animations need no clone, for exactly the reason ours do not.</p>
 *
 * <p>We take the other road, which needs no snapshot infrastructure and cannot be visually wrong: animate
 * the LAYOUT. Every frame gets a correctly laid-out window at an intermediate size. The cost is a reflow
 * per frame, and it is a cost this engine already pays and considers acceptable — {@code UIResizer} writes
 * {@code width}/{@code height} on every frame of a resize drag, and that is smooth on the heaviest window
 * in the harness. A drag is a hand-driven size animation; this is the same thing on a timer.</p>
 *
 * <h3>What it does NOT touch</h3>
 *
 * <p>No transform and no opacity, so it cannot collide with {@link WindowAnimation}'s ANIMATION-origin
 * slots, and a window that is mid-resize still hit-tests exactly where it is drawn — because where it is
 * drawn <em>is</em> its layout, with no transform in between.</p>
 */
public final class WindowGeometryAnimation implements WindowMotion {

    private final UIElement target;

    /** Whether the thing being animated is still worth writing to. @see WindowAnimation */
    private final BooleanSupplier alive;

    private final float fromLeft;
    private final float fromTop;
    private final float fromWidth;
    private final float fromHeight;
    private final float toLeft;
    private final float toTop;
    private final float toWidth;
    private final float toHeight;
    /**
     * Whether the SIZE is animated as well as the position.
     *
     * <p>False for anything that is merely travelling. Writing a width and a height every frame does not
     * merely animate them — it PINS them, at INLINE origin, to whatever they were when the animation
     * started, for good. A taskbar preview borrowed this to slide between entries and was frozen at the
     * size of the first window it ever showed: every later preview's panel stayed that width while its
     * thumbnail changed underneath, so a wide window's picture hung out past the panel and every
     * stylesheet rule aimed at the problem landed underneath an inline write and did nothing.</p>
     */
    private final boolean animateSize;

    /**
     * Whether the POSITION is animated.
     *
     * <p>False for something being resized in place — a preview's thumbnail, which is an ordinary
     * in-flow child and has no {@code left}/{@code top} of its own to write. Writing them anyway would
     * turn it into an offset box sliding over its siblings.</p>
     */
    private final boolean animatePosition;

    private final long startNanos;
    private final long durationNanos;
    private final Easing easing;
    private final Runnable onDone;
    private boolean over;

    public WindowGeometryAnimation(UIElement target, BooleanSupplier alive,
                                   float fromLeft, float fromTop, float fromWidth, float fromHeight,
                                   float toLeft, float toTop, float toWidth, float toHeight,
                                   boolean animatePosition, boolean animateSize,
                                   long durationNanos, Easing easing, Runnable onDone) {
        this.target = target;
        this.alive = alive;
        this.fromLeft = fromLeft;
        this.fromTop = fromTop;
        this.fromWidth = fromWidth;
        this.fromHeight = fromHeight;
        this.toLeft = toLeft;
        this.toTop = toTop;
        this.toWidth = toWidth;
        this.toHeight = toHeight;
        this.animatePosition = animatePosition;
        this.animateSize = animateSize;
        this.durationNanos = durationNanos;
        this.easing = easing;
        this.onDone = onDone;
        this.startNanos = System.nanoTime();
        // THE START RECT NOW, not on the first tick. A frame's gap here is a frame of the DESTINATION
        // rect, which for a maximise is the window snapping to full screen and then animating out of it.
        apply(0.0);
    }

    @Override
    public boolean frame(float deltaSeconds) {
        if (over) return false;
        // A ticker whose element has left the tree must stop; registration is one-way by design.
        if (!alive.getAsBoolean()) {
            over = true;
            return false;
        }
        long elapsed = System.nanoTime() - startNanos;
        double progress = durationNanos <= 0 ? 1.0 : Math.min(1.0, elapsed / (double) durationNanos);
        apply(easing.ease(progress));
        if (progress < 1.0) return true;
        over = true;
        onDone.run();
        return false;
    }

    /**
     * Ends it early without settling — for a gesture that interrupts another.
     *
     * <p>Maximise then immediately restore-down is the ordinary case. The completion callback does not
     * fire, because that callback writes the FINAL rect and the interrupting gesture is about to write a
     * different one.</p>
     */
    @Override
    public void cancel() {
        over = true;
    }

    /**
     * Writes the intermediate rect.
     *
     * <p><b>INLINE</b>, which is where a maximise, a restore and {@code UIResizer} all already write, so
     * an animation replaces what they said rather than layering over it — and an author's
     * {@code !important} still wins, which is the rule {@code UIResizer} states for its own writes.</p>
     */
    private void apply(double progress) {
        float p = (float) progress;
        float left = fromLeft + (toLeft - fromLeft) * p;
        float top = fromTop + (toTop - fromTop) * p;
        float width = fromWidth + (toWidth - fromWidth) * p;
        float height = fromHeight + (toHeight - fromHeight) * p;
        StyleGroup.inlinePipeline(target.getStyle().getLayoutGroup(), l -> {
            if (animatePosition) l.left(left).top(top);
            if (animateSize) l.width(width).height(height);
        });
    }
}
