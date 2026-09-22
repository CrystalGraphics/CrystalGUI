package com.crystalgui.widget.display;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

/**
 * The time scale over a set of tracks — ticks and labels on their shared axis.
 *
 * <pre>{@code
 * TimelineAxis axis = new TimelineAxis();
 * column.append(new TimelineRuler(axis));   // above the tracks, same axis, same width
 * column.append(new SpanTrack(axis));
 * }</pre>
 *
 * <h3>Relative to the extent, not to the clock</h3>
 *
 * <p>Labels count from the start of what is being shown — {@code 0}, {@code 2 ms}, {@code 4 ms} — not
 * from the process's nanosecond clock, which would print twelve digits nobody can read. A frame's zones
 * are read as "how far into the frame", and that is the number drawn.</p>
 *
 * <h3>Ticks land on round numbers</h3>
 *
 * <p>The step is the smallest of 1, 2 and 5 times a power of ten that leaves room for a label, so the
 * scale reads {@code 0 · 2 · 4 · 6} rather than {@code 0 · 1.73 · 3.46}. It is recomputed on every
 * paint, so zooming walks it through microseconds and milliseconds with no state of its own.</p>
 *
 * <p>Wheel and drag work here as they do on any track — the ruler is where a user reaches for a zoom
 * most often.</p>
 */
public class TimelineRuler extends TimelineTrack {

    public static final Name NAME = Name.of("timelineruler");

    /** Roughly how far apart labelled ticks should sit, in pixels. */
    private static final float TARGET_SPACING = 90f;

    public TimelineRuler(TimelineAxis axis) {
        super(NAME, axis);
    }

    @Override
    protected float trackHeight() {
        return 18f;
    }

    @Override
    protected void paintTrack(CgUiPaintContext ctx, Box box) {
        TimelineAxis axis = axis();
        int color = computedStyle().get(StylePropertyRegistry.COLOR);
        int tick = (color & 0x00FFFFFF) | 0x55000000;
        CgFontFamily font = labelFont();
        float bottom = box.height();

        long step = niceStep((long) (axis.nanosPerPixel() * TARGET_SPACING));
        long origin = axis.extentFrom();
        // THE FIRST TICK AT OR BEFORE THE LEFT EDGE, on the origin's own grid, so ticks do not drift
        // as the view pans: 4 ms stays at 4 ms however the window slides past it.
        long first = origin + Math.floorDiv(axis.from() - origin, step) * step;

        ctx.rect().at(0f, bottom - 1f).size(box.width(), 1f).fillColor(tick).submit();
        for (long at = first; at <= axis.to(); at += step) {
            float x = axis.xOf(at);
            if (x < -1f) continue;
            ctx.rect().at(x, bottom - 6f).size(1f, 6f).fillColor(tick).submit();
            // MINOR TICKS, four between each pair: enough to judge a bar's width by eye, too few to
            // become a texture.
            for (int minor = 1; minor < 5; minor++) {
                float mx = axis.xOf(at + step * minor / 5);
                if (mx > box.width()) break;
                ctx.rect().at(mx, bottom - 3f).size(1f, 3f).fillColor((color & 0x00FFFFFF) | 0x30000000).submit();
            }
            label(ctx, font, format(at - origin, step), x + 3f, 0f, bottom - 5f, TARGET_SPACING - 8f,
                    (color & 0x00FFFFFF) | 0xB0000000);
        }
        ctx.flush();
    }

    /** The smallest 1, 2 or 5 times a power of ten that is at least {@code rough}. */
    static long niceStep(long rough) {
        long step = 1L;
        while (step * 10L <= Math.max(1L, rough)) step *= 10L;
        if (step >= rough) return step;
        if (step * 2L >= rough) return step * 2L;
        if (step * 5L >= rough) return step * 5L;
        return step * 10L;
    }

    /** A tick's label at a precision the step warrants: no {@code 2.000 ms} when the step is 2 ms. */
    private static String format(long nanos, long step) {
        if (nanos == 0L) return "0";
        if (step >= 1_000_000L) return String.format("%d ms", nanos / 1_000_000L);
        if (step >= 100_000L) return String.format("%.1f ms", nanos / 1_000_000d);
        if (step >= 1_000L) return String.format("%d µs", nanos / 1_000L);
        return nanos + " ns";
    }
}
