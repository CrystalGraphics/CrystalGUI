package com.crystalgui.desktop.window;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.desktop.motion.BoxFlights;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.CompositorAnimation;

import javax.annotation.Nullable;

/**
 * An application's icon: what its starting window shows while it builds, in scale and fade alone. It grows in from
 * small as the window opens, past its size and back, and once the application's window has taken the starter's place
 * it fades out growing over it, the starter's backdrop fading last to show the application beneath.
 *
 * <pre>{@code
 * starter.content().append(new StartingMark(kind.icon(), kind.displayName()));   // grows in once attached
 * // the application's main window has taken the starter's place:
 * StartingMark.leaveOver(starter, successor);                                     // before the starter is destroyed
 * }</pre>
 *
 * <ul>
 *   <li>Its application builds once it has come in ({@link #settled}): about 1.45 s, which the build's own time then
 *       overlaps.</li>
 *   <li>Played on the document's frames, as anything inside a window is: the compositor moves a window's surface, not
 *       what is drawn in it.</li>
 *   <li>{@link #leaveOver} covers nothing when the mark was never seen: a build that beat the window's open shows the
 *       application at once.</li>
 * </ul>
 */
public final class StartingMark extends UIElement {

    /** On the mark; its icon is the {@code __window-icon__} child. */
    public static final String MARK_CLASS = "__starting-mark__";
    /** The starter's backdrop and mark laid over its successor's content while they leave. */
    public static final String COVER_CLASS = "__starting-cover__";

    /** Of its 72 px at rest: it grows in from {@code ENTER_SCALE}, past its size to a peak of 4/3, and leaves at {@code LEAVE_SCALE}. */
    private static final float ENTER_SCALE = 0.4f / 1.5f * 2f / 3f, LEAVE_SCALE = 2f;
    /** An out-back's overshoot constant: 4.2 peaks a grow from 0.18 at 4/3. Easing.net's is 1.70158. */
    private static final double OVERSHOOT = 4.2;
    /** Where on the curve it peaks, 0..1. */
    private static final double PEAK = 1.0 - 2.0 * OVERSHOOT / (3.0 * (OVERSHOOT + 1.0));
    /**
     * The in begins {@code IN_DELAY} after the window's first frame and plays the curve {@code SLOWER} times slower
     * than {@code PACE}, its settle from the peak {@code SETTLE_SLOWER} times slower again and eased out further; it
     * fades in over {@code FADE_NANOS}. The out is the loading screen's.
     * @see com.crystalgraphics.loading.view.CgLogoLoadingView
     */
    private static final long IN_DELAY = 200_000_000L, PACE = 900_000_000L, FADE_NANOS = 315_000_000L,
            OUT_NANOS = 700_000_000L;
    private static final double SLOWER = 1.5, SETTLE_SLOWER = 1.5;
    private static final long RISE_NANOS = Math.round(PACE * SLOWER * PEAK);
    /**
     * Within this much scale of rest, half a pixel of its 72, the settle stops: what is left of an ease-out moves
     * nothing visibly, and a picture scaled by less than a pixel only shimmers as it goes.
     */
    private static final double REST_WITHIN = 0.5 / 72.0;
    /** The settle's ease-out, over the out-back's own. */
    private static final CgEasing SETTLE = CgEasings.OUT_CUBIC;
    /** The share of the settle played: up to where it comes within {@code REST_WITHIN} of rest. */
    private static final double SETTLE_SEEN = settleSeen();
    private static final long IN_NANOS = RISE_NANOS
            + Math.round(PACE * SLOWER * (1.0 - PEAK) * SETTLE_SLOWER * SETTLE_SEEN);
    private static final LengthPercent CENTRE = LengthPercent.percent(0.5f);
    private static final Transform REST = scaled(1f);

    private final String icon;
    private final String title;
    @Nullable
    private CompositorAnimation in;

    /** A mark that grows in once attached. */
    public StartingMark(@Nullable String icon, @Nullable String title) {
        this(icon, title, true);
    }

    private StartingMark(@Nullable String icon, @Nullable String title, boolean settles) {
        this.icon = icon;
        this.title = title;
        addClass(MARK_CLASS);
        append(new WindowIcon().show(icon, title));
        if (settles) {
            whileConnected(() -> {
                in = new CompositorAnimation(this, scaled(ENTER_SCALE), REST, 0f, 1f, CENTRE, CENTRE,
                        IN_NANOS, settleEased(BoxFlights.outBack(OVERSHOOT), PEAK, (double) RISE_NANOS / IN_NANOS, SETTLE_SEEN), FADE_NANOS, CgEasings.LINEAR);
                return BoxFlights.play(this, in, IN_DELAY, null);
            });
        }
    }

    /**
     * Lays {@code starter}'s mark and backdrop over {@code successor}'s content and plays them out from where the mark
     * is now: the mark fading as it grows, then the backdrop. Nothing when {@code starter} shows no mark yet.
     */
    public static void leaveOver(WindowFrame starter, WindowFrame successor) {
        StartingMark mark = markOf(starter);
        if (mark == null || mark.in == null || mark.in.startNanos() == 0L) return;
        long now = System.nanoTime();
        float opacity = mark.in.opacityAt(now);
        if (opacity <= 0f) return;
        Transform at = mark.in.transformAt(now);

        UIElement cover = new UIElement();
        cover.addClass(COVER_CLASS);
        StartingMark leaving = new StartingMark(mark.icon, mark.title, false);
        cover.append(leaving);
        successor.content().append(cover);
        // Stopped with the cover, should the application's window close before they end
        leaving.whileConnected(() -> BoxFlights.play(leaving, new CompositorAnimation(leaving, at, scaled(LEAVE_SCALE),
                opacity, 0f, CENTRE, CENTRE, OUT_NANOS, stage(0.1, 0.8), OUT_NANOS, stage(0.1, 0.7)), null));
        cover.whileConnected(() -> BoxFlights.play(cover, new CompositorAnimation(cover, Transform.IDENTITY, Transform.IDENTITY,
                1f, 0f, CENTRE, CENTRE, OUT_NANOS, stage(0.35, 1.0)), () -> leave(cover)));
    }

    /**
     * Whether {@code starter}'s mark has come in whole, its settle included, so its application may build: a build
     * holds the document's thread, so begun sooner the mark would stand still part way in. True with no mark to wait
     * for, or with window animations off.
     */
    public static boolean settled(WindowFrame starter) {
        StartingMark mark = markOf(starter);
        if (mark == null || !WindowAnimator.isEnabled()) return true;
        return mark.in != null && mark.in.isFinished(System.nanoTime());
    }

    /** Off whatever holds it now: an application may have put other content in its window since. */
    private static void leave(UIElement cover) {
        UINode parent = cover.parent();
        if (parent != null) parent.remove(cover);
    }

    @Nullable
    private static StartingMark markOf(WindowFrame frame) {
        for (UIElement child : frame.content().children()) {
            if (child instanceof StartingMark mark) return mark;
        }
        return null;
    }

    private static Transform scaled(float scale) {
        return Transform.scale(scale);
    }

    /**
     * {@code curve} retimed: its part before {@code peak} over the flight's first {@code split}, the rest over the
     * remainder through {@link #SETTLE}, so it comes down from the peak sooner and eases into rest, played up to
     * {@code seen} of the way. Smooth only where the curve stands still at {@code peak}.
     */
    private static CgEasing settleEased(CgEasing curve, double peak, double split, double seen) {
        return t -> curve.ease(t < split ? t / split * peak : settling(curve, peak, (t - split) / (1.0 - split) * seen));
    }

    /** Where on {@code curve} the settle is at {@code x} of the way, 0..1. */
    private static double settling(CgEasing curve, double peak, double x) {
        return peak + SETTLE.ease(x) * (1.0 - peak);
    }

    /** The first share of the settle that leaves it within {@link #REST_WITHIN} of rest. */
    private static double settleSeen() {
        CgEasing curve = BoxFlights.outBack(OVERSHOOT);
        for (int i = 0; i <= 1000; i++) {
            double x = i / 1000.0;
            if ((1.0 - ENTER_SCALE) * (curve.ease(settling(curve, PEAK, x)) - 1.0) <= REST_WITHIN) return x;
        }
        return 1.0;
    }

    /** The loading screen's {@code stage}: smoothstep over {@code from}..{@code to} of the flight, flat outside it. */
    private static CgEasing stage(double from, double to) {
        return t -> {
            double x = Math.min(1.0, Math.max(0.0, (t - from) / (to - from)));
            return x * x * (3.0 - 2.0 * x);
        };
    }
}
