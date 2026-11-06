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
 * The bar coming on screen and leaving it. In: it slides up out of the screen's edge, the light along its top draws out
 * from the middle, and its buttons rise into it one after another, each popping a little past its size. Out: it slides
 * back down, fading, its buttons sinking out one after another, last first, and its top light pulling into the middle.
 *
 * <pre>{@code
 * TaskbarMotion in = TaskbarMotion.enter(bar, edge, buttons);            // the start button first, then the entries
 * TaskbarMotion out = TaskbarMotion.leave(bar, edge, buttons, false, () -> hide(bar));  // hide once off screen
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
    /** The bar's leave, quicker than the arrival, as anything leaving is. */
    private static final long LEAVE_NANOS = 220_000_000L;
    private static final CgEasing LEAVE = CgEasings.OUT_CUBIC;
    /**
     * Leaving, the buttons sink out last first, {@code SINK_STAGGER} apart, but all started within {@code SINK_SPREAD}
     * however many there are: all out inside the bar's slide, which the leave lasts no longer than.
     */
    private static final long SINK_NANOS = 120_000_000L, SINK_STAGGER = 20_000_000L, SINK_SPREAD = 60_000_000L;
    /** The bar fades over its whole slide, slowly at first, or its fade takes the row with it and the one-by-one is lost. */
    private static final CgEasing LEAVE_FADE = CgEasings.IN_QUAD;
    /** In-back: a small lift before the drop, the rise's pop played backwards. */
    private static final CgEasing SINK = CgEasings.IN_BACK;

    /** The top edge's light drawing out from the middle as the buttons rise, from {@code LEAD}, and its fade over the first part of it. */
    private static final long EDGE_DRAW_NANOS = 800_000_000L, EDGE_FADE_NANOS = 200_000_000L;
    /** Out-cubic rather than the slide's out-quint, so the sweep is spread over its time instead of spent at its start. */
    private static final CgEasing EDGE_DRAW = CgEasings.OUT_CUBIC;
    /** Leaving, it pulls back into the middle, accelerating, over the bar's whole slide. */
    private static final CgEasing EDGE_RETRACT = CgEasings.IN_CUBIC;
    /** Not zero: a box scaled to nothing has no picture to grow back from. */
    private static final Transform EDGE_DRAWN = Transform.scale(1f, 1f), EDGE_GATHERED = Transform.scale(0.02f, 1f);

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

    /** {@code bar} slides in, its {@code edge} draws out from the middle, and {@code buttons} rise into it in their order. */
    static TaskbarMotion enter(UIElement bar, UIElement edge, List<? extends UIElement> buttons) {
        TaskbarMotion motion = new TaskbarMotion();
        motion.playing.add(BoxFlights.play(bar, new CompositorAnimation(bar, DOWN, UP, 0f, 1f, CENTRE, CENTRE,
                SLIDE_NANOS, SLIDE, SLIDE_FADE_NANOS, CgEasings.LINEAR), null));
        motion.playing.add(BoxFlights.play(edge, new CompositorAnimation(edge, EDGE_GATHERED, EDGE_DRAWN, 0f, 1f,
                CENTRE, CENTRE, EDGE_DRAW_NANOS, EDGE_DRAW, EDGE_FADE_NANOS, CgEasings.LINEAR), LEAD, null));
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

    /** The buttons it moves, which a stop takes their own pictures back from. */
    private final List<UIElement> rising = new ArrayList<>();

    /**
     * {@code bar} slides down out of sight, fading, its {@code buttons} sinking out last first and its {@code edge}
     * pulling back into the middle as it goes, then {@code done}; with {@code holds}, all of it stays out of sight until stopped.
     */
    static TaskbarMotion leave(UIElement bar, UIElement edge, List<? extends UIElement> buttons, boolean holds,
                               Runnable done) {
        TaskbarMotion motion = new TaskbarMotion();
        int count = buttons.size();
        long stagger = count < 2 ? 0L : Math.min(SINK_STAGGER, SINK_SPREAD / (count - 1));
        for (int i = 0; i < count; i++) {
            UIElement button = buttons.get(count - 1 - i);
            button.addClass(Taskbar.RISING_CLASS);
            // HELD SUNK, whether or not the bar holds: one back at rest would show on the bar still sliding.
            Connection sink = BoxFlights.hold(button, new CompositorAnimation(button, RISEN, SUNK, 1f, 0f, CENTRE,
                    CENTRE, SINK_NANOS, SINK, SINK_NANOS, CgEasings.LINEAR), i * stagger, null);
            if (sink == Connection.DISCONNECTED) button.removeClass(Taskbar.RISING_CLASS);
            else motion.playing.add(sink);
            motion.rising.add(button);
        }
        // Ends with the slide, so neither is seen at rest while the other still plays.
        CompositorAnimation retract = new CompositorAnimation(edge, EDGE_DRAWN, EDGE_GATHERED, 1f, 0f, CENTRE, CENTRE,
                LEAVE_NANOS, EDGE_RETRACT);
        Connection gather = holds ? BoxFlights.hold(edge, retract, null) : BoxFlights.play(edge, retract, null);
        if (gather != Connection.DISCONNECTED) motion.playing.add(gather);
        CompositorAnimation slide = new CompositorAnimation(bar, UP, DOWN, 1f, 0f, CENTRE, CENTRE, LEAVE_NANOS, LEAVE,
                LEAVE_NANOS, LEAVE_FADE);
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
