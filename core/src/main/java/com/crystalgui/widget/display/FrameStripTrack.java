package com.crystalgui.widget.display;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

/**
 * The frame strip — one bar per frame, and the window's navigation surface.
 *
 * <pre>{@code
 * FrameStripTrack strip = new FrameStripTrack();
 * strip.setBudgetNanos(16_666_667L);        // 60Hz; the scale and the verdicts follow it
 * strip.setFrames(wallNanos, health);       // one entry per frame, oldest first
 * strip.onSelected(index -> show(index));
 * strip.onRangeSelected(range -> aggregate(range.from(), range.to()));
 * }</pre>
 *
 * <h3>A fixed scale, with the budget drawn on it</h3>
 *
 * <p>The top of the strip is twice the frame budget, always, and the budget itself is a labelled line
 * halfway up. A scale taken from the ring's own worst frame lets one 300ms startup hitch flatten every
 * ordinary frame into a sliver — and makes the strip rescale whenever an outlier enters or leaves the
 * ring, so the same frame changes height while you look at it. A bar past the top is clipped and
 * capped; its number is in the header one click away.</p>
 *
 * <h3>Colour only where something is wrong</h3>
 *
 * <p>An in-budget bar takes the element's own quiet {@code color}. Amber and red are kept for frames
 * that missed, so the eye lands on the misses rather than on six hundred identical green bars. The
 * selected frame takes the accent, which is the one other thing on this row worth finding.</p>
 *
 * <p>Everything about the axis — bucketing, the selection, range drag, the mask boundary — is
 * {@link FrameSeriesTrack}'s, shared with the counter rows.</p>
 */
public class FrameStripTrack extends FrameSeriesTrack {

    public static final Name NAME = Name.of("framestrip");

    /** A verdict per frame, mirroring the readout's so one vocabulary covers both. */
    public enum Health {
        GOOD, WARN, BAD
    }

    /** 60Hz. */
    public static final long DEFAULT_BUDGET_NANOS = 16_666_667L;

    private long[] wallNanos = new long[0];
    private Health[] health = new Health[0];
    private boolean[] gc = new boolean[0];
    private long budgetNanos = DEFAULT_BUDGET_NANOS;

    public FrameStripTrack() {
        super(NAME);
        setRowHeight(72f);
    }

    /** One entry per frame, oldest first. */
    public FrameStripTrack setFrames(long[] wall, Health[] verdicts) {
        wallNanos = wall == null ? new long[0] : wall;
        health = verdicts == null ? new Health[0] : verdicts;
        if (selectedIndex() >= wallNanos.length) showSelected(-1);
        showRange(null);
        repaint();
        return this;
    }

    /**
     * Which frames a garbage collection ran in, oldest first — each marked by a tick under its bar.
     *
     * <p>Marked rather than left to the header: a pause is one frame in a few hundred, and a reader
     * who has to click every bar to find the one that says "GC" never finds it.</p>
     */
    public FrameStripTrack setGcFrames(boolean[] collected) {
        gc = collected == null ? new boolean[0] : collected;
        repaint();
        return this;
    }

    /** The frame budget the scale and the budget line are drawn from. */
    public FrameStripTrack setBudgetNanos(long nanos) {
        budgetNanos = Math.max(1_000_000L, nanos);
        repaint();
        return this;
    }

    public long budgetNanos() {
        return budgetNanos;
    }

    @Override
    protected int frameCount() {
        return wallNanos.length;
    }

    @Override
    protected long rankOf(int index) {
        return index >= 0 && index < wallNanos.length ? wallNanos[index] : 0L;
    }

    /** How many frames the strip is over. */
    public int frames() {
        return wallNanos.length;
    }

    @Override
    protected void paintSeries(CgUiPaintContext ctx, Box box) {
        float bottom = box.height() - BASELINE_INSET;
        float usable = bottom - TOP_INSET;
        long ceiling = budgetNanos * 2L;

        int quiet = computedStyle().get(StylePropertyRegistry.COLOR);
        int accent = computedStyle().get(StylePropertyRegistry.BORDER_COLOR);

        // THE BUDGET LINES FIRST, under the bars: a frame reaching the line should be seen touching
        // it, not hidden by it.
        float budgetY = bottom - usable * 0.5f;
        ctx.rect().at(0f, budgetY).size(box.width(), 1f).fillColor((quiet & 0x00FFFFFF) | 0x40000000).submit();
        ctx.rect().at(0f, TOP_INSET).size(box.width(), 1f).fillColor((quiet & 0x00FFFFFF) | 0x22000000).submit();

        int columns = columnCount();
        float columnWidth = box.width() / columns;
        // A GAP BETWEEN BARS once they are wide enough to afford one, so a run of frames reads as
        // separate frames rather than as an area chart.
        float barWidth = columnWidth >= 4f ? columnWidth - 1f : Math.max(1f, columnWidth - 0.35f);

        for (int column = 0; column < columns; column++) {
            int at = extremeIn(columnFrom(column), columnTo(column));
            long worst = rankOf(at);
            if (worst <= 0L) continue;
            Health verdict = at < health.length && health[at] != null ? health[at] : Health.GOOD;
            int fill = isSelectedFrame(at) ? accent : colorOf(verdict, quiet);
            if (!isComparable(at)) fill = washed(fill);

            boolean clipped = worst > ceiling;
            float barHeight = clipped ? usable : Math.max(1.5f, (float) worst / ceiling * usable);
            float x = column * columnWidth;
            ctx.rect().at(x, bottom - barHeight).size(barWidth, barHeight).fillColor(fill).submit();
            if (collectedIn(columnFrom(column), columnTo(column))) {
                ctx.rect().at(x, bottom + GC_GAP).size(Math.max(2f, barWidth), GC_TICK).fillColor(GC_COLOR).submit();
            }
            if (clipped) {
                // OFF THE SCALE: a cap in the same colour, lifted, so a clipped bar cannot be mistaken
                // for one that stopped exactly at the top.
                ctx.rect().at(x, TOP_INSET - 3f).size(barWidth, 2f).fillColor(fill).submit();
            }
        }

        CgFontFamily font = labelFont();
        int dim = (quiet & 0x00FFFFFF) | 0xB0000000;
        String budget = formatMillis(budgetNanos);
        String top = formatMillis(ceiling);
        float labelWidth = LABEL_ADVANCE * 8f;
        // ON A PLATE: the labels sit at the right edge, which is where the newest frames are, and a
        // tall one ran straight through them.
        // NEAR-OPAQUE: at seventy percent a red bar still showed through the digits. Dark enough to
        // read as the well itself, which is almost black in every theme this ships.
        //
        // SQUARE, and that is load-bearing rather than taste: a rounded rect takes the SDF material, which
        // did not keep painter's order with the batched bars, so a tall bar drew through the plate. A plain
        // rect goes down the same batch as the bars and lands on top of them.
        int plate = 0xF0101114;
        ctx.rect().at(box.width() - labelWidth - 4f, budgetY - 12f).size(labelWidth + 2f, 12f)
                .fillColor(plate).submit();
        ctx.rect().at(box.width() - labelWidth - 4f, TOP_INSET).size(labelWidth + 2f, 12f)
                .fillColor(plate).submit();
        label(ctx, font, budget, box.width() - labelWidth, budgetY - 12f, 12f, labelWidth, dim);
        label(ctx, font, top, box.width() - labelWidth, TOP_INSET, 12f, labelWidth, dim);
    }

    private static String formatMillis(long nanos) {
        return String.format("%.1f ms", nanos / 1_000_000d);
    }

    /** Room above the ceiling for a clipped bar's cap. */
    private static final float TOP_INSET = 5f;

    /** A pixel of floor, so a zero-height bar still has a line to sit on. */
    private static final float BASELINE_INSET = 5f;
    /** The GC tick sits in the baseline inset, one pixel under the bars. */
    private static final float GC_GAP = 1f;
    private static final float GC_TICK = 3f;
    /** Violet, the colour Chrome's profiler gives GC — distinct from every health colour on this row. */
    private static final int GC_COLOR = 0xFFB083F0;

    private boolean collectedIn(int from, int to) {
        for (int i = Math.max(0, from); i <= to && i < gc.length; i++) {
            if (gc[i]) return true;
        }
        return false;
    }

    private static final float LABEL_ADVANCE = 6f;

    /**
     * The bar's colour.
     *
     * <p>GOOD is the element's own {@code color}, so a theme owns the resting case through
     * {@code --profiler-bar-good}. <b>WARN and BAD are literals</b>, and that is a gap rather than a
     * decision: {@code --profiler-bar-warn} and {@code --profiler-bar-bad} are defined in
     * {@code themes/base.css} and nothing reads them, because a bar is not an element and the cascade
     * hands this track one colour. Closing it needs either a registered CSS property per state or a
     * palette seam on the track — both are real design choices, and pinning the values here is honest
     * until one is made.</p>
     */
    private static int colorOf(Health verdict, int good) {
        return switch (verdict) {
            case WARN -> 0xFFE3B341;
            case BAD -> 0xFFE5534B;
            default -> good;
        };
    }
}
