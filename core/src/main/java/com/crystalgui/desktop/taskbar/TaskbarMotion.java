package com.crystalgui.desktop.taskbar;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.desktop.motion.BoxFlights;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.CompositorAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * The bar coming on screen and leaving it. In: it slides up out of the screen's edge, and its buttons rise into it one
 * after another, each popping a little past its size. Out: it slides back down, fading.
 *
 * <pre>{@code
 * TaskbarMotion in = TaskbarMotion.enter(bar, buttons);          // the start button first, then the entries
 * TaskbarMotion out = TaskbarMotion.leave(bar, false, () -> hide(bar));  // hide once it is off screen
 * in.stop();                                                       // everything back at rest, at once
 * }</pre>
 *
 * <ul>
 *   <li>Plays nothing with window animations off; {@code leave}'s {@code done} then runs at once.</li>
 *   <li>A stopped leave never runs its {@code done}: whoever stops it decides where the bar ends up.</li>
 * </ul>
 */
final class TaskbarMotion {

    /** The bar's slide, and its fade over the first part of it. */
    private static final long SLIDE_NANOS = 380_000_000L, SLIDE_FADE_NANOS = 120_000_000L;
    private static final CgEasing SLIDE = CgEasings.OUT_QUINT;
    /** The first button starts {@code LEAD} into the slide, each next one {@code STAGGER} after it. */
    private static final long LEAD = 110_000_000L, STAGGER = 35_000_000L;
    /** Each button's rise and pop; it fades in over the first part of it. */
    private static final long RISE_NANOS = 320_000_000L, RISE_FADE_NANOS = 120_000_000L;
    /** Out-back's overshoot: 2.6 peaks a grow from {@code RISE_SCALE} at about 1.08. */
    private static final CgEasing RISE = BoxFlights.outBack(2.6);
    private static final float RISE_SCALE = 0.6f, RISE_DROP = 12f;
    /** The leave, quicker than the arrival, as anything leaving is. */
    private static final long LEAVE_NANOS = 220_000_000L;
    private static final CgEasing LEAVE = CgEasings.OUT_CUBIC;

    private static final LengthPercent CENTRE = LengthPercent.percent(0.5f), NONE = LengthPercent.percent(0f),
            BELOW = LengthPercent.percent(1f);
    /** Translates in percent at both ends: a px against a % does not interpolate. */
    private static final Transform UP = Transform.of(Transform.Op.translate(NONE, NONE)),
            DOWN = Transform.of(Transform.Op.translate(NONE, BELOW));
    private static final Transform RISEN = Transform.translate(0f, 0f).withScale(1f, 1f),
            SUNK = Transform.translate(0f, RISE_DROP).withScale(RISE_SCALE, RISE_SCALE);

    private final List<Connection> playing = new ArrayList<>();

    private TaskbarMotion() {
    }

    /** {@code bar} slides in, and {@code buttons} rise into it in their order. */
    static TaskbarMotion enter(UIElement bar, List<? extends UIElement> buttons) {
        TaskbarMotion motion = new TaskbarMotion();
        motion.playing.add(BoxFlights.play(bar, new CompositorAnimation(bar, DOWN, UP, 0f, 1f, CENTRE, CENTRE,
                SLIDE_NANOS, SLIDE, SLIDE_FADE_NANOS, CgEasings.LINEAR), null));
        for (int i = 0; i < buttons.size(); i++) {
            UIElement button = buttons.get(i);
            // A PICTURE OF ITS OWN WHILE IT RISES: drawn at rest and moved whole, so its icon is drawn as it is at rest
            button.addClass(Taskbar.RISING_CLASS);
            Connection rise = BoxFlights.play(button, new CompositorAnimation(button, SUNK, RISEN, 0f, 1f, CENTRE,
                    CENTRE, RISE_NANOS, RISE, RISE_FADE_NANOS, CgEasings.LINEAR), LEAD + i * STAGGER,
                    () -> button.removeClass(Taskbar.RISING_CLASS));
            if (rise == Connection.DISCONNECTED) button.removeClass(Taskbar.RISING_CLASS);
            else motion.playing.add(rise);
            motion.rising.add(button);
        }
        return motion;
    }

    /** The buttons it raised, which a stop takes their own pictures back from. */
    private final List<UIElement> rising = new ArrayList<>();

    /** {@code bar} slides down out of sight, fading, then {@code done}; with {@code holds}, it stays out of sight until stopped. */
    static TaskbarMotion leave(UIElement bar, boolean holds, Runnable done) {
        TaskbarMotion motion = new TaskbarMotion();
        CompositorAnimation slide = new CompositorAnimation(bar, UP, DOWN, 1f, 0f, CENTRE, CENTRE, LEAVE_NANOS, LEAVE);
        Connection out = holds ? BoxFlights.hold(bar, slide, done) : BoxFlights.play(bar, slide, done);
        if (out == Connection.DISCONNECTED) done.run();
        else motion.playing.add(out);
        return motion;
    }

    /** Ends it now, everything at rest. */
    void stop() {
        for (Connection connection : playing) connection.disconnect();
        playing.clear();
        for (UIElement button : rising) button.removeClass(Taskbar.RISING_CLASS);
        rising.clear();
    }
}
