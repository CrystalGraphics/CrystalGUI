package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.transition.ActiveTransition;
import com.crystalgui.ui.UITransform;

import javax.annotation.Nullable;

/**
 * One window animation, driven imperatively — the {@code AnimationController} shape.
 *
 * <h3>Why this does not go through the cascade</h3>
 *
 * <p>The first version of this drove everything with CSS {@code transition}s, and it accumulated four
 * distinct failure modes from a single root: a compositor animation changes several properties that must
 * move <em>together</em>, and the cascade resolves them independently. Every one of them was silent.</p>
 *
 * <ul>
 *   <li>{@code transition} is itself a property, resolved in the same pass as the properties it governs —
 *       so lifting a suppression and changing a value in one frame could resolve the value first, find
 *       {@code transition: none}, and apply it instantly. Stable resolution order made that happen every
 *       time, so the whole feature simply read as unwired.</li>
 *   <li>A value written at {@code INLINE} to end one animation permanently outranks a stylesheet class
 *       used to start the next, so a window that had once been maximised could never animate closed
 *       again.</li>
 *   <li>{@code transform-origin} is not interpolable and must be pinned for an animation's whole life.
 *       Reset in the same write that was being transitioned, the mapping eased about the centre when it
 *       had been computed about the corner — which is what a chopped, flickering maximise looks like.</li>
 *   <li>Completion could only be discovered by polling, so the teardown an animation guards was always
 *       a few frames late or early.</li>
 * </ul>
 *
 * <p>None of that is the cascade being bad at its job. It is the wrong instrument: declarative state is
 * for what a thing looks like at rest, and this is a timeline. Every real compositor separates the two —
 * Core Animation's {@code CABasicAnimation}, Android's {@code ValueAnimator}, Flutter's
 * {@code AnimationController} — and each is the same object: from, to, duration, curve, a per-frame tick
 * and a completion callback. This is that, and nothing more.</p>
 *
 * <h3>It borrows the engine's interpolator rather than reimplementing one</h3>
 *
 * <p>{@link ActiveTransition} already holds the from/to/duration/easing maths, and
 * {@code StyleProperty}'s interpolators already know how to lerp a {@link UITransform} by CSS's
 * list-matching rule. What is not reused is the part that caused the trouble — the cascade deciding
 * <em>whether</em> to animate. So this is a port of the engine's own machinery into a driver that runs
 * it directly.</p>
 *
 * <h3>ANIMATION origin, which is what makes it invisible to the cascade</h3>
 *
 * <p>Values are written through {@code startAnimationSlot}/{@code tickAnimationSlot}, the same channel
 * {@code TransitionEngine} writes on. That origin sits above {@code IMPORTANT} so nothing outranks a
 * running animation, and the cascade's own diff deliberately ignores it — so these writes cannot start a
 * transition, retarget one, or fight a stylesheet. When the animation ends the slots are dropped and
 * whatever the sheet says takes over, with no cleanup value to write and get wrong.</p>
 */
final class WindowAnimation implements WindowMotion {

    private final WindowFrame frame;
    private final ActiveTransition<UITransform> transform;
    private final ActiveTransition<Float> opacity;
    @Nullable
    private final Runnable onDone;
    private boolean over;

    /**
     * Starts immediately — the first values are written here, not on the first tick.
     *
     * <p>A frame's gap between "the animation was asked for" and "the animation is showing its start
     * value" is a frame of the END state, which is a visible flash at the beginning of every gesture.
     * The ticker exists to advance it, not to begin it.</p>
     */
    WindowAnimation(WindowFrame frame, UITransform from, UITransform to,
                    float fromOpacity, float toOpacity, LengthPercent originX, LengthPercent originY,
                    long durationNanos, Easing easing, @Nullable Runnable onDone) {
        this.frame = frame;
        this.onDone = onDone;
        long now = System.nanoTime();
        this.transform = new ActiveTransition<>(
                StylePropertyRegistry.TRANSFORM, from, to, now, 0L, durationNanos, easing);
        this.opacity = new ActiveTransition<>(
                StylePropertyRegistry.OPACITY, fromOpacity, toOpacity, now, 0L, durationNanos, easing);

        // THE ORIGIN IS PINNED FOR THE WHOLE ANIMATION, and it is PER ANIMATION.
        //
        // A transform that MAPS ONE BOX ONTO ANOTHER -- the window's rect onto a taskbar button's, a
        // restored rect onto a maximised one -- is only expressible from a fixed corner, so those pass
        // the top-left. An open or a close is not a mapping but a gesture, and where it grows FROM is
        // the whole character of it: GNOME Shell opens a window from bottom-centre and closes it into
        // its own middle, and using one origin for both makes one of them wrong.
        //
        // Written on the same channel as everything else and dropped with it, so an origin cannot
        // outlive the animation that needed it and silently re-anchor an unrelated one later -- which is
        // exactly how the previous version came to ease a maximise about the centre having computed it
        // about the corner.
        frame.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_X, originX, 0);
        frame.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, originY, 0);
        frame.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM, from, 0);
        frame.getStyle().startAnimationSlot(StylePropertyRegistry.OPACITY, fromOpacity, 0);
        notifyTransform(from);
    }

    /** Where this animation is going. The only observable of a motion whose whole point is its target. */
    UITransform target() {
        return transform.toValue();
    }

    /** Where it started. Paired with {@link #target()} so a test can check the two actually LERP. */
    UITransform startValue() {
        return transform.fromValue();
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (over) return false;
        // A TICKER WHOSE ELEMENT HAS LEFT THE TREE MUST STOP, and a destroyed window has no style to
        // write to. Registration is one-way by design, so nothing else will stop it.
        if (frame.state() == WindowState.DESTROYED) {
            over = true;
            return false;
        }
        long now = System.nanoTime();
        UITransform value = transform.currentValue(now);
        frame.getStyle().tickAnimationSlot(StylePropertyRegistry.TRANSFORM, value, 0);
        frame.getStyle().tickAnimationSlot(StylePropertyRegistry.OPACITY, opacity.currentValue(now), 0);
        notifyTransform(value);

        if (!transform.isFinished(now)) return true;
        finish();
        return false;
    }

    /**
     * Ends it early, leaving nothing behind — for a gesture that interrupts another.
     *
     * <p>Maximise then immediately restore is the ordinary case, and two drivers writing the same slot
     * would trade frames with each other for as long as both ran. The completion callback does NOT fire:
     * a cancelled minimise must not go on to hide the window somebody has just re-shown.</p>
     */
    @Override
    public void cancel() {
        if (over) return;
        over = true;
        clearSlots();
    }

    private void finish() {
        over = true;
        clearSlots();
        if (onDone != null) onDone.run();
    }

    /**
     * Drops every slot this animation owned, revealing whatever the cascade says.
     *
     * <p>No end value is written, which is the point of animating on this origin: the resting state is
     * the stylesheet's business, and writing our own copy of it is how the previous version came to have
     * an {@code INLINE} identity transform outranking the class that was supposed to start the next
     * animation.</p>
     */
    private void clearSlots() {
        var style = frame.getStyle();
        style.endAnimationSlot(StylePropertyRegistry.TRANSFORM);
        style.endAnimationSlot(StylePropertyRegistry.OPACITY);
        style.endAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
        style.endAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
        notifyTransform(style.getComputed(StylePropertyRegistry.TRANSFORM));
    }

    /**
     * Tells {@code transform}'s own listener the value moved.
     *
     * <p>Not optional and not bookkeeping: {@code StylePropertyRegistry.TRANSFORM} carries a listener
     * that calls {@code invalidatePoseCachesRecursively}, which is what makes a new transform reach the
     * {@code PoseStack} and the hit-test chain at all. {@code tickAnimationSlot} deliberately does not
     * notify — it is the raw write — so {@code TransitionEngine} does this itself on every tick too.
     * Without it the window's cached pose is whatever it was when the animation started, and the whole
     * thing runs invisibly.</p>
     */
    private void notifyTransform(@Nullable UITransform value) {
        StyleProperty<UITransform> property = StylePropertyRegistry.TRANSFORM;
        property.notifyListeners(frame, value, value);
    }
}
