package com.crystalgui.widget.display;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A row whose axis is the <b>frame index</b> — one column per frame, across the whole ring.
 *
 * <p>{@link FrameStripTrack} draws bars on it and {@link CounterTrack} draws a value series; both are
 * read against the same columns, which is what makes "the {@code drawcalls} spike and the slow frame
 * are the same frame" a thing the eye can see rather than a thing two numbers have to be compared for.</p>
 *
 * <h3>Not the timeline's axis</h3>
 *
 * <p>The tracks in the middle band map <i>nanoseconds inside one frame</i> to pixels. This maps
 * <i>frames</i> to pixels. Forcing both onto one {@link TimelineAxis} would mean either a strip zoomed
 * into a single frame or a flame chart the width of a run, so this hierarchy owns its own mapping and
 * turns the base class's pan and zoom off.</p>
 *
 * <h3>It buckets, and a bucket is its worst frame</h3>
 *
 * <p>Six hundred frames do not fit in six hundred pixels, so a column covers several and takes the
 * <b>extreme</b> of them, never the mean. Averaging a bucket is exactly what hides a spike, and hiding
 * spikes is the one thing these rows exist not to do. Clicking a column selects that extreme frame,
 * which is the one the click was for.</p>
 */
public abstract class FrameSeriesTrack extends TimelineTrack {

    /** A span of frame indices, inclusive. */
    public record Range(int from, int to) {

        public int count() {
            return to - from + 1;
        }
    }

    /** How many frames the row is over. Subclasses answer from whatever series they hold. */
    protected abstract int frameCount();

    /** What a column ranks by when several frames share one — the bigger, the more it wants showing. */
    protected abstract long rankOf(int index);

    /** Paint, in box-local pixels, knowing the columns from {@link #columnCount} and {@link #xOf}. */
    protected abstract void paintSeries(CgUiPaintContext ctx, Box box);

    private int selected = -1;
    private int hovered = -1;

    private int dragAnchor = -1;
    private Range range;

    /**
     * Frames before this index were recorded under a different channel mask.
     *
     * <p>Greyed rather than hidden: they happened, and a row that simply omitted them would look like
     * a shorter run. What they are not is <b>comparable</b> with the frames after the change, which is
     * the claim the wash makes.</p>
     */
    private int comparableFrom;

    private float height = 64f;

    private final List<IntConsumer> selectionListeners = new ArrayList<>(2);
    private final List<Consumer<Range>> rangeListeners = new ArrayList<>(2);

    protected FrameSeriesTrack(Name name) {
        super(name, new TimelineAxis());
        // A DRAG HERE IS A RANGE, not a pan. Two meanings on one button cannot both be right, and
        // selecting a range is what this surface is for.
        setNavigable(false);
    }

    // ── Selection ───────────────────────────────────────────────────────────────────────────

    public int selectedIndex() {
        return selected;
    }

    /** Selects by index, clamped, and tells anyone listening. */
    public void select(int index) {
        int count = frameCount();
        if (count == 0) return;
        int wanted = Math.max(0, Math.min(count - 1, index));
        if (wanted == selected) return;
        selected = wanted;
        repaint();
        for (IntConsumer listener : selectionListeners) listener.accept(wanted);
    }

    /** Steps the selection — what an arrow key does, and the gesture for comparing neighbours. */
    public void step(int by) {
        select(selected < 0 ? 0 : selected + by);
    }

    /** Moves the mark without telling anyone — for a row FOLLOWING another row's selection. */
    public void showSelected(int index) {
        int count = frameCount();
        int wanted = count == 0 ? -1 : Math.max(0, Math.min(count - 1, index));
        if (wanted == selected) return;
        selected = wanted;
        repaint();
    }

    public Range selectedRange() {
        return range;
    }

    /**
     * As {@link #showSelected}, for a range.
     *
     * <p>Ignored while a range is being dragged here: the owner re-reads its model on a clock, and a
     * refresh that landed mid-drag wiped the half-drawn range out from under the pointer.</p>
     */
    public void showRange(Range value) {
        if (dragAnchor >= 0) return;
        range = value;
        repaint();
    }

    /**
     * Hears a press on this row, before anything is picked — so an owner that redraws on a clock can
     * hold still for the whole gesture rather than only after it ends.
     */
    public void onGestureStart(Runnable listener) {
        if (listener != null) gestureListeners.add(listener);
    }

    private final List<Runnable> gestureListeners = new ArrayList<>(2);

    public void onSelected(IntConsumer listener) {
        if (listener != null) selectionListeners.add(listener);
    }

    public void onRangeSelected(Consumer<Range> listener) {
        if (listener != null) rangeListeners.add(listener);
    }

    /** @see #comparableFrom */
    public void setComparableFrom(int index) {
        int wanted = Math.max(0, index);
        if (wanted == comparableFrom) return;
        comparableFrom = wanted;
        repaint();
    }

    protected boolean isComparable(int index) {
        return index >= comparableFrom;
    }

    public FrameSeriesTrack setRowHeight(float value) {
        height = Math.max(8f, value);
        markTreeDirty();
        return this;
    }

    @Override
    protected float trackHeight() {
        return height;
    }

    // ── Index and pixel ─────────────────────────────────────────────────────────────────────

    /** How many columns fit: one per frame until there are more frames than pixels. */
    protected final int columnCount() {
        return Math.max(1, Math.min((int) Math.max(1f, width()), Math.max(1, frameCount())));
    }

    /** The first frame in {@code column}. */
    protected final int columnFrom(int column) {
        return (int) (column / (float) columnCount() * frameCount());
    }

    /** One past the last frame in {@code column}. */
    protected final int columnTo(int column) {
        return Math.max(columnFrom(column) + 1,
                Math.min(frameCount(), (int) ((column + 1) / (float) columnCount() * frameCount())));
    }

    /** The frame a column stands for — its highest-ranked, so a spike survives bucketing. */
    protected final int extremeIn(int from, int to) {
        int best = from;
        for (int i = from; i < to && i < frameCount(); i++) {
            if (rankOf(i) > rankOf(best)) best = i;
        }
        return best;
    }

    /** Where frame {@code index} starts, in track pixels. */
    protected final float xOf(int index) {
        int count = frameCount();
        return count == 0 ? 0f : index / (float) count * Math.max(1f, width());
    }

    /** Half a frame's own slot — so a mark sits on the frame rather than on its left edge. */
    protected final float halfSlot() {
        return Math.max(1f, width()) / Math.max(1, frameCount()) * 0.5f;
    }

    /** The frame under {@code x} — the extreme one in that column. */
    protected final int indexAt(float x) {
        int count = frameCount();
        if (count == 0) return -1;
        float width = Math.max(1f, width());
        int at = (int) (Math.max(0f, Math.min(width - 1f, x)) / width * count);
        at = Math.max(0, Math.min(count - 1, at));
        int to = Math.min(count, (int) ((Math.floor(x) + 1f) / width * count) + 1);
        return extremeIn(at, to);
    }

    // ── Gestures ────────────────────────────────────────────────────────────────────────────

    @Override
    protected void onPressed(float x, float y, long nanos) {
        for (Runnable listener : gestureListeners) listener.run();
        dragAnchor = indexAt(x);
        range = null;
    }

    @Override
    protected void onHovered(float x, float y, long nanos) {
        int was = hovered;
        hovered = Float.isNaN(x) ? -1 : indexAt(x);
        if (dragAnchor >= 0 && hovered >= 0) {
            range = new Range(Math.min(dragAnchor, hovered), Math.max(dragAnchor, hovered));
        }
        if (hovered != was) repaint();
    }

    @Override
    protected void onPicked(float x, float y, long nanos) {
        int at = indexAt(x);
        if (at < 0) return;
        // A DRAG OF ONE COLUMN IS A CLICK. Requiring an exact press-and-release on one pixel would
        // make selecting a frame a test of steadiness.
        if (range != null && range.count() > 1) {
            Range picked = range;
            for (Consumer<Range> listener : rangeListeners) listener.accept(picked);
        } else {
            range = null;
            select(at);
        }
        dragAnchor = -1;
    }

    // ── Painting ────────────────────────────────────────────────────────────────────────────

    @Override
    protected final void paintTrack(CgUiPaintContext ctx, Box box) {
        if (frameCount() == 0) return;
        int accent = computedStyle().get(StylePropertyRegistry.BORDER_COLOR);
        float height = box.height();

        // THE BANDS FIRST, BEHIND the series: a mark drawn over a bar hides the bar it marks.
        if (range != null) {
            float left = xOf(range.from());
            float right = xOf(range.to() + 1);
            ctx.rect().at(left, 0f).size(Math.max(1f, right - left), height)
                    .fillColor((accent & 0x00FFFFFF) | 0x2A000000).submit();
        }
        // A COLUMN, not a hairline. With six hundred frames in a row a 1px line over one of them is
        // invisible at a glance, and the selected frame is the one thing on this row the eye has to find.
        float slot = Math.max(3f, halfSlot() * 2f);
        if (hovered >= 0 && hovered != selected) {
            ctx.rect().at(xOf(hovered) + halfSlot() - slot * 0.5f, 0f).size(slot, height)
                    .fillColor((accent & 0x00FFFFFF) | 0x1C000000).submit();
        }
        if (selected >= 0) {
            ctx.rect().at(xOf(selected) + halfSlot() - slot * 0.5f, 0f).size(slot, height)
                    .fillColor((accent & 0x00FFFFFF) | 0x40000000).submit();
        }

        paintSeries(ctx, box);

        if (comparableFrom > 0 && comparableFrom < frameCount()) {
            // WHERE THE RECORDING CHANGED SHAPE. A line rather than a label: the header says what
            // changed, this says where.
            ctx.rect().at(xOf(comparableFrom) - 0.5f, 0f).size(1f, height)
                    .fillColor((accent & 0x00FFFFFF) | 0x80000000).submit();
        }
    }

    /** The frame under the pointer, or {@code -1}. */
    protected final int hoveredIndex() {
        return hovered;
    }

    /** Whether a drag that began on this track is in progress. */
    protected final boolean isDragging() {
        return dragAnchor >= 0;
    }

    /** Whether {@code index} is the selected frame — so a series can draw that one bar in the accent. */
    protected final boolean isSelectedFrame(int index) {
        return index == selected;
    }

    /** A colour washed back toward nothing — what a frame recorded under a different mask draws in. */
    protected static int washed(int argb) {
        return (argb & 0x00FFFFFF) | 0x44000000;
    }
}
