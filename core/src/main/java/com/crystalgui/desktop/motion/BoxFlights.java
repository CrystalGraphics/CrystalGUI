package com.crystalgui.desktop.motion;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.CompositorAnimation;

import javax.annotation.Nullable;

/**
 * Plays a {@link CompositorAnimation} on an element inside a window, on the document's frames: the compositor moves a
 * window's surface, not what is drawn in it.
 *
 * <pre>{@code
 * CompositorAnimation pop = new CompositorAnimation(icon, Transform.scale(0.6f), Transform.IDENTITY, 0f, 1f,
 *         centre, centre, 300_000_000L, BoxFlights.outBack(2.6));
 * Connection playing = BoxFlights.play(icon, pop, 40_000_000L, () -> log("in"));   // 40 ms after its first frame
 * playing.disconnect();                                                            // stops it, at rest
 * }</pre>
 *
 * <ul>
 *   <li>Its first frame already shows the start: values are written after each layout, before paint.</li>
 *   <li>Ended or stopped, the box's transform, origin and opacity are handed back to the sheet.</li>
 *   <li>Nothing plays with window animations off, or off a document: {@code done} is not run either.</li>
 * </ul>
 */
public final class BoxFlights {

    private BoxFlights() {
    }

    /** Plays {@code flight} on {@code on}'s box from its next frame, then runs {@code done}, unless stopped first. */
    public static Connection play(UIElement on, CompositorAnimation flight, @Nullable Runnable done) {
        return play(on, flight, 0L, done);
    }

    /** As above, its clock starting {@code delayNanos} after its first frame, held at its start until then. */
    public static Connection play(UIElement on, CompositorAnimation flight, long delayNanos, @Nullable Runnable done) {
        return play(on, flight, delayNanos, false, done);
    }

    /**
     * Plays {@code flight} and leaves {@code on} at its end until the returned connection is disconnected: what
     * something leaving the screen plays while the screen still shows it.
     *
     * <pre>{@code
     * Connection gone = BoxFlights.hold(bar, slideOut, () -> closeScreen());
     * gone.disconnect();   // back, at rest: the screen opened again
     * }</pre>
     */
    public static Connection hold(UIElement on, CompositorAnimation flight, @Nullable Runnable done) {
        return play(on, flight, 0L, true, done);
    }

    private static Connection play(UIElement on, CompositorAnimation flight, long delayNanos, boolean holds,
                                   @Nullable Runnable done) {
        UIDocument document = on.document();
        if (document == null || !WindowAnimator.isEnabled()) return Connection.DISCONNECTED;
        // Ended, and released: held at the end between the two.
        boolean[] ended = {false}, released = {false};
        document.animation().afterLayout(on, delta -> {
            if (ended[0]) return false;
            long now = System.nanoTime();
            flight.startAt(now + delayNanos);
            write(on, flight.transformAt(now), flight.opacityAt(now), flight);
            if (!flight.isFinished(now)) return true;
            ended[0] = true;
            if (!holds) {
                released[0] = true;
                write(on, null, null, flight);
            }
            if (done != null) done.run();
            return false;
        });
        return () -> {
            if (released[0]) return;
            ended[0] = released[0] = true;
            write(on, null, null, flight);
        };
    }

    /** Easing.net's out-back with overshoot {@code s}: past 1 and back, further the larger {@code s}. 1.70158 is its usual. */
    public static CgEasing outBack(double s) {
        return t -> {
            double u = t - 1.0;
            return 1.0 + (s + 1.0) * u * u * u + s * u * u;
        };
    }

    private static void write(UIElement on, @Nullable Transform transform, @Nullable Float opacity,
                              CompositorAnimation flight) {
        Box box = on.box();
        if (box == null) return;
        box.setTransform(transform);
        box.setOpacity(opacity);
        if (transform == null) box.setTransformOrigin(null, null);
        else box.setTransformOrigin(flight.originX(box.width()), flight.originY(box.height()));
    }
}
