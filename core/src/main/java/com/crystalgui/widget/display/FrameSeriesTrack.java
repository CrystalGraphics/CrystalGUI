package com.crystalgui.widget.display;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.input.keymap.KeyStroke;

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
        // selecting a range is what this surface is for. The WHEEL is this row's own: it zooms and pans
        // the frame axis, which is how a ring of ten thousand frames is read one frame at a time.
        setNavigable(false);
        onMouseScroll.attachListener((element, event) -> {
            float notches = event.getScroll();
            if (notches == 0f || frameCount() == 0) return;
            var input = CgPlatform.input();
            int modifiers = input == null ? 0 : input.getCurrentModifiers();
            if (KeyStroke.hasMod(modifiers)) return;
            if (CgModifiers.hasShift(modifiers)) {
                panBy(notches * visible() * PAN_PER_NOTCH);
            } else {
                // A POSITIVE notch is the wheel rolled DOWN (HostPointer.scroll), which zooms OUT.
                float x = Float.isNaN(lastX) ? width() * 0.5f : lastX;
                zoomAt(x, notches > 0f ? ZOOM_PER_NOTCH : 1d / ZOOM_PER_NOTCH);
            }
            event.stopPropagation();
        }, false, true);
    }

    // ── The view: which frames are across the row ───────────────────────────────────────────

    private static final double ZOOM_PER_NOTCH = 1.25d;
    private static final double PAN_PER_NOTCH = 0.1d;
    /** The fewest frames a zoom may leave across the row — enough to still read as a series. */
    private static final double MIN_VISIBLE = 8d;

    /** The first frame across the row while zoomed; ignored while the whole ring is shown. */
    private double viewFrom;
    /** How many frames are across the row while zoomed, or 0 for the whole ring. */
    private double viewSpan;
    private float lastX = Float.NaN;

    private final List<Runnable> viewListeners = new ArrayList<>(2);

    /** Hears every zoom and pan made here — what keeps rows on the same columns in step. */
    public void onViewChanged(Runnable listener) {
        if (listener != null) viewListeners.add(listener);
    }

    /** How many frames the row is over. */
    public int frames() {
        return frameCount();
    }

    /** Whether less than the whole ring is across the row. */
    public boolean isZoomed() {
        double span = span();
        return span > 0d && span < frameCount();
    }

    /**
     * Until somebody moves the view, it is the newest frames at a readable width rather than the whole
     * ring: bars {@link #AUTO_FRAME_PX} wide, anchored at the end. The whole ring across a strip is
     * several frames to a pixel — nothing can be read one frame at a time, and a scrollbar whose thumb
     * fills its track has nothing to scroll.
     */
    private boolean autoView = true;

    /** A frame's width in the opening view, in pixels. */
    private static final float AUTO_FRAME_PX = 3f;

    /** The span in force: the opening view's, or the one set; 0 for the whole ring. */
    private double span() {
        if (!autoView) return viewSpan;
        Box box = box();
        if (box == null || box.width() <= 1f) return 0d;
        double span = Math.max(MIN_VISIBLE, box.width() / AUTO_FRAME_PX);
        return span >= frameCount() ? 0d : span;
    }

    /** The first frame across the row, fractional while zoomed. */
    public final double viewFrom() {
        if (!isZoomed()) return 0d;
        double span = span();
        return autoView ? frameCount() - span : Math.max(0d, Math.min(frameCount() - span, viewFrom));
    }

    /** How many frames are across the row. */
    public final double visible() {
        return isZoomed() ? span() : Math.max(1, frameCount());
    }

    /** Shows {@code span} frames from {@code from} and tells {@link #onViewChanged} listeners. */
    public void setView(double from, double span) {
        if (showView(from, span)) {
            for (Runnable listener : viewListeners) listener.run();
        }
    }

    /**
     * As {@link #setView}, telling nobody — for a row following another row's view.
     *
     * @return whether anything moved
     */
    public boolean showView(double from, double span) {
        if (autoView) {
            // THE OPENING VIEW ENDS HERE, starting from where it was, so "did anything move" is honest.
            viewSpan = span();
            viewFrom = viewFrom();
            autoView = false;
        }
        int count = frameCount();
        double wantedSpan = span <= 0d || span >= count ? 0d : Math.max(Math.min(MIN_VISIBLE, count), span);
        double wantedFrom = wantedSpan == 0d ? 0d : Math.max(0d, Math.min(count - wantedSpan, from));
        if (wantedSpan == viewSpan && wantedFrom == viewFrom) return false;
        viewSpan = wantedSpan;
        viewFrom = wantedFrom;
        repaint();
        return true;
    }

    /** Back to the opening view: the newest frames at a readable width. @see #autoView */
    public void resetView() {
        if (autoView) return;
        autoView = true;
        repaint();
        for (Runnable listener : viewListeners) listener.run();
    }

    /** The whole ring across the row again. */
    public void showWhole() {
        setView(0d, 0d);
    }

    /** Zooms by {@code factor} (above 1 is out) keeping the frame under {@code x} where it is. */
    public void zoomAt(float x, double factor) {
        float width = Math.max(1f, width());
        double at = viewFrom() + x / width * visible();
        double span = visible() * factor;
        setView(at - x / width * span, span);
    }

    /** Moves the view by {@code frames}; positive is later. */
    public void panBy(double frames) {
        if (!isZoomed()) return;
        setView(viewFrom() + frames, span());
    }

    /** A middle-button drag: the frame axis, not the time axis this row never shows. */
    @Override
    protected void panPixels(float pixels) {
        panBy(pixels / Math.max(1f, width()) * visible());
    }

    /** Brings {@code index} into view, centred, when it is outside it. Nothing while unzoomed. */
    public void reveal(int index) {
        if (!isZoomed() || index < 0) return;
        double from = viewFrom();
        double span = span();
        if (index >= from && index < from + span) return;
        setView(index - span * 0.5d, span);
    }

    /** Keeps the newest frame at the right edge — a live row's zoomed view moving with the ring. */
    public void followEnd() {
        // The opening view is anchored at the end already.
        if (!autoView && isZoomed()) setView(frameCount() - viewSpan, viewSpan);
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

    /*
     * ONE LAYOUT, READ BY EVERYTHING: the bars, the selection and hover marks, the range, the gap and the
     * hit test. They were two -- bars placed by pixel column counted from a whole frame, marks by the exact
     * frame-to-pixel mapping -- and with a view that starts part-way through a frame the two disagreed by up
     * to a frame, so a highlight sat beside the bar it marked.
     *
     * Two regimes. Fewer frames across than pixels: every frame has a slot of its own at its exact place,
     * which may start left of the row. More: a column per pixel, standing for the frames under it.
     */

    /** Whether every frame across the row has a slot of its own. */
    private boolean perFrame() {
        return visible() <= Math.max(1f, width());
    }

    /** How many columns: the frames touching the view, or one per pixel. */
    protected final int columnCount() {
        if (perFrame()) {
            double from = viewFrom();
            int first = (int) Math.floor(from);
            int last = Math.min(frameCount(), (int) Math.ceil(from + visible()));
            return Math.max(1, last - first);
        }
        return Math.max(1, (int) Math.max(1f, width()));
    }

    /** The first frame in {@code column}. */
    protected final int columnFrom(int column) {
        if (perFrame()) return (int) Math.floor(viewFrom()) + column;
        return (int) (viewFrom() + column / (double) columnCount() * visible());
    }

    /** One past the last frame in {@code column}. */
    protected final int columnTo(int column) {
        if (perFrame()) return Math.min(frameCount(), columnFrom(column) + 1);
        return Math.max(columnFrom(column) + 1,
                Math.min(frameCount(), (int) (viewFrom() + (column + 1) / (double) columnCount() * visible())));
    }

    /** Where {@code column} starts, in track pixels — left of 0 for a frame cut by the row's edge. */
    protected final float columnX(int column) {
        if (perFrame()) return xOf(columnFrom(column));
        return column * Math.max(1f, width()) / columnCount();
    }

    /** How wide {@code column} is, in track pixels. */
    protected final float columnW(int column) {
        return perFrame() ? halfSlot() * 2f : Math.max(1f, width()) / columnCount();
    }

    /** The column frame {@code index} is drawn in. */
    private int columnOf(int index) {
        if (perFrame()) return index - (int) Math.floor(viewFrom());
        return (int) Math.floor((index - viewFrom()) / visible() * columnCount());
    }

    /**
     * The mark for frame {@code index}: {@code [x, width]}, exactly over its bar, at least
     * {@link #MARK_MIN} wide so one frame among six hundred can still be found.
     */
    protected final float[] slotOf(int index) {
        int column = columnOf(index);
        float x = columnX(column);
        float w = columnW(column);
        if (w < MARK_MIN) {
            x -= (MARK_MIN - w) * 0.5f;
            w = MARK_MIN;
        }
        return new float[]{x, w};
    }

    private static final float MARK_MIN = 3f;

    /**
     * A bar in {@code column}, clipped to the row: {@code [x, width]}, or null when nothing of it is
     * inside. A pixel of gap between bars once they are wide enough to afford one.
     */
    protected final float[] barOf(int column) {
        float x = columnX(column);
        float w = columnW(column);
        float bar = w >= 4f ? w - 1f : Math.max(1f, w - 0.35f);
        float left = Math.max(0f, x);
        float right = Math.min(Math.max(1f, width()), x + bar);
        return right > left ? new float[]{left, right - left} : null;
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
        if (frameCount() == 0) return 0f;
        return (float) ((index - viewFrom()) / visible() * Math.max(1f, width()));
    }

    /** Half a frame's own slot — so a mark sits on the frame rather than on its left edge. */
    protected final float halfSlot() {
        return (float) (Math.max(1f, width()) / visible() * 0.5d);
    }

    /** The frame under {@code x} — in a shared column, the extreme one. The same layout the bars use. */
    protected final int indexAt(float x) {
        int count = frameCount();
        if (count == 0) return -1;
        float width = Math.max(1f, width());
        float clamped = Math.max(0f, Math.min(width - 0.001f, x));
        if (perFrame()) {
            return Math.max(0, Math.min(count - 1, (int) Math.floor(viewFrom() + clamped / width * visible())));
        }
        int column = Math.max(0, Math.min(columnCount() - 1, (int) (clamped / width * columnCount())));
        return extremeIn(columnFrom(column), Math.min(count, columnTo(column)));
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
        if (!Float.isNaN(x)) lastX = x;
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
            float[] first = slotOf(range.from());
            float[] last = slotOf(range.to());
            fillClipped(ctx, first[0], last[0] + last[1], height, (accent & 0x00FFFFFF) | 0x2A000000);
        }
        // A COLUMN, not a hairline. With six hundred frames in a row a 1px line over one of them is
        // invisible at a glance, and the selected frame is the one thing on this row the eye has to find.
        if (hovered >= 0 && hovered != selected) {
            float[] mark = slotOf(hovered);
            fillClipped(ctx, mark[0], mark[0] + mark[1], height, (accent & 0x00FFFFFF) | 0x1C000000);
        }
        if (selected >= 0) {
            float[] mark = slotOf(selected);
            fillClipped(ctx, mark[0], mark[0] + mark[1], height, (accent & 0x00FFFFFF) | 0x40000000);
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

    /** A full-height band from {@code left} to {@code right}, clipped to the row; nothing if outside it. */
    private void fillClipped(CgUiPaintContext ctx, float left, float right, float height, int argb) {
        float from = Math.max(0f, left);
        float to = Math.min(Math.max(1f, width()), right);
        if (to > from) ctx.rect().at(from, 0f).size(to - from, height).fillColor(argb).submit();
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
