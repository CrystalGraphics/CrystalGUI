package com.crystalgui.ui.service;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.UIElement;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A transform and opacity timeline played by the compositor on one box, at the display's rate whatever the
 * document's frames cost. Its clock starts when the compositor first presents a frame carrying it; the document
 * reads the same clock, so its own values and its continuation land with what is on screen.
 *
 * <pre>{@code
 * CompositorAnimation flight = new CompositorAnimation(window, from, to, 1f, 0f, originX, originY, nanos, easing);
 * boolean played = document.animation().playOnCompositor(flight);  // false unless an async driver presents it
 *
 * // each document frame, while played:
 * long now = System.nanoTime();
 * if (flight.startNanos() != 0L) box.setTransform(flight.transformAt(now));
 * if (flight.isFinished(now)) document.animation().stopOnCompositor(flight);
 * }</pre>
 *
 * <ul>
 *   <li>Immutable but for the start, which the render thread writes once.</li>
 *   <li>Stop it however it ends, finished or cancelled, or the box keeps a layer for nothing.</li>
 *   <li>Only a box's own transform and opacity: anything that reflows is the document's.</li>
 * </ul>
 */
public final class CompositorAnimation {

    private final UIElement target;
    private final Transform from, to;
    private final float fromOpacity, toOpacity;
    private final LengthPercent originX, originY;
    private final long durationNanos;
    private final CgEasing easing;
    private final AtomicLong start = new AtomicLong();

    public CompositorAnimation(UIElement target, Transform from, Transform to, float fromOpacity, float toOpacity,
                               LengthPercent originX, LengthPercent originY, long durationNanos, CgEasing easing) {
        this.target = target;
        this.from = from;
        this.to = to;
        this.fromOpacity = fromOpacity;
        this.toOpacity = toOpacity;
        this.originX = originX;
        this.originY = originY;
        this.durationNanos = durationNanos;
        this.easing = easing;
    }

    /** The element whose box it plays on. */
    public UIElement target() {
        return target;
    }

    /** The transform origin, resolved against the box's size. */
    public float originX(float width) {
        return originX.resolve(width);
    }

    public float originY(float height) {
        return originY.resolve(height);
    }

    /** When the compositor first presented it, in {@link System#nanoTime}; 0 before. */
    public long startNanos() {
        return start.get();
    }

    /** Starts the clock at {@code now} unless it has started. Render thread. */
    public void startAt(long now) {
        start.compareAndSet(0L, now);
    }

    /** Eased progress at {@code now}: 0 before it starts, 1 once done. */
    public float progressAt(long now) {
        long started = start.get();
        if (started == 0L) return 0f;
        if (durationNanos <= 0L) return 1f;
        double linear = Math.min(1.0, Math.max(0.0, (now - started) / (double) durationNanos));
        return (float) easing.ease(linear);
    }

    public boolean isFinished(long now) {
        long started = start.get();
        return started != 0L && now - started >= durationNanos;
    }

    public Transform transformAt(long now) {
        return StylePropertyRegistry.TRANSFORM.getInterpolator().interpolate(from, to, progressAt(now));
    }

    public float opacityAt(long now) {
        float t = progressAt(now);
        return fromOpacity + (toOpacity - fromOpacity) * t;
    }
}
