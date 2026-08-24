package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.transition.ActiveTransition;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;

import javax.annotation.Nullable;

import java.util.function.BooleanSupplier;

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

    private final UIElement target;

    /**
     * Whether the thing being animated is still worth writing to.
     *
     * <p>A predicate rather than a state check, because the two consumers answer it differently: a
     * window is gone once it is {@code DESTROYED}, while a taskbar preview is gone once it has left the
     * tree. A ticker whose subject has died must stop, and registration is one-way by design.</p>
     */
    private final BooleanSupplier alive;

    private ActiveTransition<UITransform> transform;
    private ActiveTransition<Float> opacity;

    /** @see #tickFrame — the clock is rebased on the first advance, the value is not. */
    private final UITransform from;
    private final UITransform to;
    private final float fromOpacity;
    private final float toOpacity;
    private final long durationNanos;
    private final Easing easing;
    private boolean clockStarted;
    private long virtualNow;
    private long lastRealNow;

    /**
     * The most an animation may be advanced by a single frame — three frames at 60Hz.
     *
     * <p>Long enough that ordinary jitter passes through untouched, short enough that one stalled frame
     * cannot swallow a whole gesture.</p>
     */
    private static final long MAX_STEP_NANOS = 50L * 1_000_000L;

    /** A gap longer than this is a stall, not a frame — nothing was presented across it. */
    private static final long STALL_NANOS = 100L * 1_000_000L;

    /** How long an animation will wait for the loop to settle before playing regardless. */
    private static final int MAX_STALLED_TICKS = 60;

    private int stalledTicks;
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
    WindowAnimation(UIElement target, BooleanSupplier alive, UITransform from, UITransform to,
                    float fromOpacity, float toOpacity, LengthPercent originX, LengthPercent originY,
                    long durationNanos, Easing easing, @Nullable Runnable onDone) {
        this.target = target;
        this.alive = alive;
        this.onDone = onDone;
        // THE VALUE STARTS HERE; THE CLOCK STARTS ON THE FIRST TICK. Both halves are load-bearing and
        // they pull in opposite directions.
        //
        // Stamping the clock here too assumes construction and the first frame are adjacent, which is
        // true for every gesture made WITH the game running and false for the one that matters most: a
        // host builds its screen and opens its first window outside the frame loop, and the editor is
        // then constructed, the workspace connected and shaders compiled before a frame is ever drawn.
        // By the first tick the whole 150ms had elapsed, so the animation completed on that tick having
        // rendered NOTHING -- `ticks=0 over 0ms` in the probe, and "it just opens instantly" on screen.
        // Every later gesture was fine, so it read as the open animation being broken rather than as a
        // clock that had run out before anyone looked at it.
        //
        // The start VALUE still cannot wait (see the javadoc above): a frame between "asked for" and
        // "showing its start value" is a frame of the END state.
        this.from = from;
        this.to = to;
        this.fromOpacity = fromOpacity;
        this.toOpacity = toOpacity;
        this.durationNanos = durationNanos;
        this.easing = easing;
        this.transform = new ActiveTransition<>(
                StylePropertyRegistry.TRANSFORM, from, to, System.nanoTime(), 0L, durationNanos, easing);
        this.opacity = new ActiveTransition<>(
                StylePropertyRegistry.OPACITY, fromOpacity, toOpacity, System.nanoTime(), 0L, durationNanos, easing);

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
        target.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_X, originX, 0);
        target.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, originY, 0);
        target.getStyle().startAnimationSlot(StylePropertyRegistry.TRANSFORM, from, 0);
        target.getStyle().startAnimationSlot(StylePropertyRegistry.OPACITY, fromOpacity, 0);
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
        if (!alive.getAsBoolean()) {
            over = true;
            return false;
        }
        // A VIRTUAL CLOCK, ADVANCED BY CAPPED STEPS -- not wall time.
        //
        // An animation should advance by RENDERED time. Wall time is the same thing right up until the
        // frame loop stalls, and the first window of a session opens into the worst stall there is: the
        // editor is being constructed and its shaders compiled, so the probe measured two frames 154ms
        // APART for a 150ms animation. It completed on its second tick having drawn one frame, which on
        // screen is indistinguishable from no animation at all.
        //
        // Capping a step is the standard frame-loop guard (the same one that stops physics spiralling on
        // a slow frame) and it is the honest reading: nobody saw the 154ms, because nothing was drawn
        // during it, so charging the animation for it animates against time the user never observed.
        // A stall now costs the animation one capped step and it plays out over the frames that follow.
        long real = System.nanoTime();
        long delta = lastRealNow == 0L ? 0L : real - lastRealNow;
        lastRealNow = real;

        if (!clockStarted) {
            // IT DOES NOT BEGIN UNTIL THE COMPOSITOR IS PRESENTING FRAMES.
            //
            // The first windows of a session open into the worst stall there is -- an editor being
            // constructed, shaders compiled, font atlases built -- and the harness measures the first two
            // gaps at 398ms and 282ms for a 150ms animation. Beginning there spends the whole gesture on
            // frames nobody saw: capping a step softened that to three visible frames, which still reads
            // as a jump rather than a motion.
            //
            // So it HOLDS at its start value (already written in the constructor, so there is nothing to
            // flash) until it sees one ordinary gap, then plays in full. Bounded, or a permanently slow
            // host would hold forever and never animate at all.
            if (delta == 0L || (delta > STALL_NANOS && stalledTicks++ < MAX_STALLED_TICKS)) return true;
            clockStarted = true;
            virtualNow = real;
            transform = new ActiveTransition<>(
                    StylePropertyRegistry.TRANSFORM, from, to, real, 0L, durationNanos, easing);
            opacity = new ActiveTransition<>(
                    StylePropertyRegistry.OPACITY, fromOpacity, toOpacity, real, 0L, durationNanos, easing);
        } else if (delta > STALL_NANOS && stalledTicks++ < MAX_STALLED_TICKS) {
            // A STALL IS NOT A SLOW FRAME, IT IS NO FRAME -- so it advances the animation by nothing.
            //
            // The same rule that decides when to begin, applied for the rest of the run. Capping the
            // step instead still charged a 154ms gap 50ms of a 150ms gesture: a third of the animation
            // spent on a frame nobody saw, which on screen is one of the jumps this whole exercise is
            // about. Bounded by the same counter, so a host that never settles still finishes.
            return true;
        } else {
            virtualNow += Math.min(delta, MAX_STEP_NANOS);
        }
        long now = virtualNow;
        UITransform value = transform.currentValue(now);
        target.getStyle().tickAnimationSlot(StylePropertyRegistry.TRANSFORM, value, 0);
        target.getStyle().tickAnimationSlot(StylePropertyRegistry.OPACITY, opacity.currentValue(now), 0);
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
        var style = target.getStyle();
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
        property.notifyListeners(target, value, value);
    }
}
