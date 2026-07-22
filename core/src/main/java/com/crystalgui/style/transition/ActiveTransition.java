package com.crystalgui.style.transition;

import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/** One in-flight state transition for a single (element, property) pair. Retargeting (e.g. rapid
 * hover on/off) replaces the map entry wholesale rather than mutating in place. */
public record ActiveTransition<T>(
        StyleProperty<T> property,
        T fromValue,
        T toValue,
        long startNanos,
        long delayNanos,
        long durationNanos,
        Easing easing
) {
    /** Progress in [0,1]. 0 while still within the delay window; a zero-duration transition is
     * instantly done (1.0) the moment the delay elapses. */
    public double progress(long nowNanos) {
        long elapsed = nowNanos - startNanos - delayNanos;
        if (elapsed < 0) return 0.0;
        if (durationNanos <= 0) return 1.0;
        return Math.min(1.0, elapsed / (double) durationNanos);
    }

    public boolean isFinished(long nowNanos) {
        return progress(nowNanos) >= 1.0;
    }

    /** The value this transition should currently be showing, given the wall-clock time. */
    public T currentValue(long nowNanos) {
        float easedT = (float) easing.ease(progress(nowNanos));
        return property.getInterpolator().interpolate(fromValue, toValue, easedT);
    }
}
