package com.crystalgui.widget.display;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

/**
 * One counter across the ring — a value per frame, on the frame strip's own columns.
 *
 * <pre>{@code
 * CounterTrack drawcalls = new CounterTrack();
 * drawcalls.setSeries("drawcalls", values);   // one entry per frame, oldest first
 * drawcalls.showSelected(strip.selectedIndex());
 * }</pre>
 *
 * <h3>Why it is on the frame axis and not the timeline's</h3>
 *
 * <p>A counter is recorded <b>per frame</b>, not at an instant: {@code drawcalls} has one value for the
 * whole of frame 412. Drawing it inside a frame's milliseconds would mean drawing one flat line and
 * calling it a series. Across the ring it is the thing anybody actually wants — where the spike is —
 * and the shared playhead is what ties it to the slow frame beside it.</p>
 *
 * <h3>It states its own scale</h3>
 *
 * <p>The peak is printed in the row, because a sparkline with no number answers "this went up" and not
 * "this went up to twenty-four megapixels" — and the second is the one that decides anything. An
 * absent value is a GAP rather than a zero: a counter nothing wrote in a frame did not measure zero.</p>
 */
public class CounterTrack extends FrameSeriesTrack {

    public static final Name NAME = Name.of("countertrack");

    /** No value recorded for this frame. Distinct from zero, and drawn as a gap. */
    public static final long ABSENT = Long.MIN_VALUE;

    private String label = "";
    private long[] values = new long[0];
    private long peak;

    public CounterTrack() {
        super(NAME);
        setRowHeight(28f);
    }

    /** One value per frame, oldest first; {@link #ABSENT} where nothing was recorded. */
    public CounterTrack setSeries(String name, long[] perFrame) {
        label = name == null ? "" : name;
        values = perFrame == null ? new long[0] : perFrame;
        peak = 0L;
        for (long value : values) {
            if (value != ABSENT) peak = Math.max(peak, value);
        }
        repaint();
        return this;
    }

    public String label() {
        return label;
    }

    public long peak() {
        return peak;
    }

    /** The value at {@code index}, or {@link #ABSENT}. */
    public long valueAt(int index) {
        return index >= 0 && index < values.length ? values[index] : ABSENT;
    }

    @Override
    protected int frameCount() {
        return values.length;
    }

    @Override
    protected long rankOf(int index) {
        long value = valueAt(index);
        return value == ABSENT ? Long.MIN_VALUE : value;
    }

    @Override
    protected void paintSeries(CgUiPaintContext ctx, Box box) {
        // THE LABEL HAS A BAND OF ITS OWN, above the bars rather than over them: drawn over the series
        // it collided with the first spike on every row that had one near the start, which is most.
        float bottom = box.height() - 1f;
        float usable = bottom - LABEL_BAND;
        int fill = computedStyle().get(StylePropertyRegistry.COLOR);
        int columns = columnCount();
        long ceiling = Math.max(1L, peak);

        for (int column = 0; column < columns; column++) {
            int at = extremeIn(columnFrom(column), columnTo(column));
            long value = valueAt(at);
            if (value == ABSENT) continue;      // a gap, not a zero
            // A FLOOR OF ONE PIXEL only for a value that is genuinely non-zero: a real zero draws
            // nothing, or every counter reads as a solid bar the whole width of the ring.
            float barHeight = value == 0L ? 0f : Math.max(1f, (float) value / ceiling * usable);
            if (barHeight <= 0f) continue;
            int colour = isComparable(at) ? fill : washed(fill);
            float[] bar = barOf(column);
            if (bar == null) continue;
            ctx.rect().at(bar[0], bottom - barHeight).size(bar[1], barHeight)
                    .fillColor(colour).submit();
        }

        CgFontFamily font = labelFont();
        // QUIET, and never the accent: the accent on this row means "the selected frame", and a label in
        // it read as a second selection.
        int text = mix(fill, 0xFFFFFFFF, 0.45f);
        // THE HOVERED FRAME while the pointer is on the bars, else the selected one: a click here picks a
        // frame, and the label naming which one is what makes that read as intended.
        int shown = hoveredIndex() >= 0 ? hoveredIndex() : selectedIndex();
        String shownValue = "";
        if (shown >= 0) {
            long at = valueAt(shown);
            shownValue = "   " + (at == ABSENT ? "\u2014" : at);
        }
        label(ctx, font, label + shownValue + "   peak " + peak, 6f, 1f, LABEL_BAND - 1f,
                box.width() - 12f, text);
    }

    // ── Only the bars pick a frame ───────────────────────────────────────────────────────────
    // A click on a counter's NAME selected whatever frame sat under it, which read as a stray click.

    private boolean pressedOnLabel;

    @Override
    protected void onPressed(float x, float y, long nanos) {
        pressedOnLabel = y < LABEL_BAND;
        if (!pressedOnLabel) super.onPressed(x, y, nanos);
    }

    @Override
    protected void onHovered(float x, float y, long nanos) {
        super.onHovered(!isDragging() && y < LABEL_BAND ? Float.NaN : x, y, nanos);
    }

    @Override
    protected void onPicked(float x, float y, long nanos) {
        if (!pressedOnLabel) super.onPicked(x, y, nanos);
        pressedOnLabel = false;
    }

    /** The height of the label's own band, above the bars. */
    private static final float LABEL_BAND = 13f;

    private static int mix(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
