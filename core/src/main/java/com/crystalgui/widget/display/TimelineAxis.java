package com.crystalgui.widget.display;

import java.util.ArrayList;
import java.util.List;

/**
 * The map between a span of nanoseconds and a span of pixels — pan, zoom, and the way back.
 *
 * <pre>{@code
 * TimelineAxis axis = new TimelineAxis();
 * axis.setExtent(frame.beginNanos(), frame.endNanos());   // everything there is
 * axis.setPixels(track.box().width());
 * axis.showAll();
 *
 * float x = axis.xOf(zone.startNanos());                  // where to draw it
 * long  t = axis.timeAt(mouseX);                          // what was clicked
 * }</pre>
 *
 * <h3>One axis, several tracks</h3>
 *
 * <p>Threads, the GPU and every counter are drawn against the same instance, which is what makes them
 * line up and what makes one scroll move all of them. <b>The frame strip is deliberately NOT on it</b>:
 * it shows the whole ring in seconds while the tracks show one frame in milliseconds, and forcing those
 * onto one axis would mean either a strip zoomed into nothing or a flame chart the width of a run.</p>
 *
 * <h3>No UI, on purpose</h3>
 *
 * <p>Every number here is arithmetic over two longs and a float, so it is tested headlessly rather than
 * by looking at a picture. The widget that draws against it owns no mapping of its own — if a click
 * lands on the wrong zone, the fault is in one place.</p>
 *
 * <h3>Clamped at both ends</h3>
 *
 * <p>The window can neither leave the extent nor shrink below {@link #MIN_SPAN_NANOS}. Both are
 * failures a user produces in the first ten seconds: a scroll wheel that runs past the data leaves a
 * blank chart with no way back, and one that keeps dividing reaches a span where every zone is wider
 * than the screen and nothing identifies itself.</p>
 */
public final class TimelineAxis {

    /** A microsecond. Below it every span is wider than the track and the picture stops meaning anything. */
    public static final long MIN_SPAN_NANOS = 1_000L;

    private long extentFrom;
    private long extentTo = 1L;

    private long from;
    private long to = 1L;

    private float pixels = 1f;

    private final List<Runnable> listeners = new ArrayList<>(4);

    /** Everything there is to look at. Narrows the visible window if it no longer fits. */
    public void setExtent(long fromNanos, long toNanos) {
        extentFrom = fromNanos;
        extentTo = Math.max(fromNanos + MIN_SPAN_NANOS, toNanos);
        show(from, to);
    }

    public long extentFrom() {
        return extentFrom;
    }

    public long extentTo() {
        return extentTo;
    }

    /** How wide the track is. Zero or less is treated as one, so a mapping never divides by nothing. */
    public void setPixels(float value) {
        float wanted = Math.max(1f, value);
        if (wanted == pixels) return;
        pixels = wanted;
        changed();
    }

    public float pixels() {
        return pixels;
    }

    public long from() {
        return from;
    }

    public long to() {
        return to;
    }

    public long spanNanos() {
        return Math.max(MIN_SPAN_NANOS, to - from);
    }

    public double nanosPerPixel() {
        return spanNanos() / (double) pixels;
    }

    /** Whether the whole extent is visible — what a "reset" affordance asks. */
    public boolean isShowingAll() {
        return from <= extentFrom && to >= extentTo;
    }

    /** Shows the whole extent. */
    public void showAll() {
        show(extentFrom, extentTo);
    }

    /**
     * Shows {@code [fromNanos, toNanos]}, clamped into the extent and to {@link #MIN_SPAN_NANOS}.
     *
     * <p>Clamping <b>keeps the requested span</b> and slides it, rather than truncating it against the
     * edge: a pan that hits the end should stop, not shrink the window and silently zoom in.</p>
     */
    public void show(long fromNanos, long toNanos) {
        long span = Math.max(MIN_SPAN_NANOS, toNanos - fromNanos);
        long whole = extentTo - extentFrom;
        if (span > whole) span = whole;

        long start = fromNanos;
        if (start < extentFrom) start = extentFrom;
        if (start + span > extentTo) start = extentTo - span;

        if (start == from && start + span == to) return;
        from = start;
        to = start + span;
        changed();
    }

    /** Where {@code nanos} falls, in track pixels. Outside the window this runs off either end. */
    public float xOf(long nanos) {
        return (float) ((nanos - from) / nanosPerPixel());
    }

    /** How wide {@code nanos} of duration is, in pixels. */
    public float widthOf(long nanos) {
        return (float) (nanos / nanosPerPixel());
    }

    /** What is under {@code x} — the inverse of {@link #xOf}, and what a click resolves through. */
    public long timeAt(float x) {
        return from + (long) (x * nanosPerPixel());
    }

    /** Slides the window by {@code dx} pixels. Positive moves the content left, as a drag does. */
    public void panPixels(float dx) {
        long by = (long) (dx * nanosPerPixel());
        if (by == 0L) return;
        show(from + by, to + by);
    }

    /**
     * Zooms about {@code x}, keeping whatever is under that pixel exactly where it is.
     *
     * <p>Anchoring on the pointer rather than on the centre is what makes a wheel feel like a
     * magnifier: zooming about the middle walks the thing you are looking at off the side, and every
     * timeline anyone has used does it this way.</p>
     *
     * @param factor above one zooms in, below one out
     */
    public void zoomAt(float x, double factor) {
        if (factor <= 0d || factor == 1d) return;
        long anchor = timeAt(x);
        long span = (long) (spanNanos() / factor);
        span = Math.max(MIN_SPAN_NANOS, Math.min(extentTo - extentFrom, span));
        double share = pixels <= 0f ? 0d : x / pixels;
        long start = anchor - (long) (share * span);
        show(start, start + span);
    }

    /** Called whenever the window or the width moves — what a track repaints on. */
    public void onChanged(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
    }
}
