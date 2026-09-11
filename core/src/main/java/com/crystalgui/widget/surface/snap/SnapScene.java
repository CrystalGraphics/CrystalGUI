package com.crystalgui.widget.surface.snap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgui.widget.surface.snap.SnapTarget.Kind;

/**
 * <b>What a moving box can snap to</b>: the POINTS its features may land on, and the GAPS its peers
 * already leave between them.
 *
 * <pre>{@code
 * SnapScene scene = BoxTargets.sceneFor(node);                                 // a laid-out box
 * SnapScene scene = new SnapScene(rects, rects, null);                         // plain rectangles
 * SnapScene scene = SnapScene.ofOutlines(outlines, bounds, container, frame);  // shapes as drawn
 * }</pre>
 *
 * <p>Points come from every outline — four corners and the centre, as tldraw and Excalidraw take them,
 * and a turned shape's own corners rather than its bounds'. Gaps come only from the {@code peers}, by
 * their bounds: the air between two parts deep inside a sibling is not a spacing anybody means relative
 * to what is being dragged.</p>
 *
 * <p>Build one per gesture where nothing else moves during it; gaps are found on first use and kept.</p>
 */
public final class SnapScene implements SnapTargets {

    /** Nothing to snap to. */
    public static final SnapScene EMPTY = new SnapScene(List.of(), List.of(), null);

    /** Anything thinner than this is not a gap. */
    private static final float GAP_EPSILON = 0.5f;

    /** A box in the solver's space. */
    public record Rect(float x, float y, float width, float height) {

        public float start(SnapAxis axis) {
            return axis == SnapAxis.HORIZONTAL ? x : y;
        }

        public float end(SnapAxis axis) {
            return start(axis) + (axis == SnapAxis.HORIZONTAL ? width : height);
        }

        public float crossStart(SnapAxis axis) {
            return axis == SnapAxis.HORIZONTAL ? y : x;
        }

        public float crossEnd(SnapAxis axis) {
            return crossStart(axis) + (axis == SnapAxis.HORIZONTAL ? height : width);
        }
    }

    /**
     * A shape's four corners in order round it, then its centre — as drawn, so a turned box's corners are
     * its own rather than those of its bounds.
     */
    public record Outline(float[] xs, float[] ys) {

        public static Outline of(Rect rect) {
            float x0 = rect.x();
            float y0 = rect.y();
            float x1 = x0 + rect.width();
            float y1 = y0 + rect.height();
            return new Outline(new float[] {x0, x1, x1, x0, (x0 + x1) * 0.5f},
                    new float[] {y0, y0, y1, y1, (y0 + y1) * 0.5f});
        }

        /** The axis-aligned box around it. */
        public Rect bounds() {
            float minX = xs[0];
            float maxX = xs[0];
            float minY = ys[0];
            float maxY = ys[0];
            for (int i = 1; i < xs.length; i++) {
                minX = Math.min(minX, xs[i]);
                maxX = Math.max(maxX, xs[i]);
                minY = Math.min(minY, ys[i]);
                maxY = Math.max(maxY, ys[i]);
            }
            return new Rect(minX, minY, maxX - minX, maxY - minY);
        }
    }

    /**
     * Empty space between two peers <b>that overlap on the other axis</b>, with no third peer inside it.
     *
     * @param crossFrom where the two overlap on the other axis — the row the gap is in
     */
    public record Gap(Rect before, Rect after, float crossFrom, float crossTo) {
    }

    private final List<Outline> points;
    private final List<Rect> peers;
    @Nullable
    private final Rect container;
    @Nullable
    private final Rect frame;
    /** Made once, so both axes' guides name the same outline and it is drawn once. */
    @Nullable
    private final Outline containerOutline;
    @Nullable
    private final Outline frameOutline;
    @Nullable
    private List<Gap> horizontalGaps;
    @Nullable
    private List<Gap> verticalGaps;

    /** @see #SnapScene(List, List, Rect, Rect) */
    public SnapScene(List<Rect> points, List<Rect> peers, @Nullable Rect container) {
        this(points, peers, container, null);
    }

    /**
     * @param points    every box whose corners and centre may be landed on
     * @param peers     the boxes gaps are measured between; usually also in {@code points}
     * @param container the parent's content box, ranked above everything else, or null
     * @param frame     the page everything is laid out on — an artboard — or null. Its edges and centre
     *                  are offered however deep the moving box is, ranked just below the container's
     */
    public SnapScene(List<Rect> points, List<Rect> peers, @Nullable Rect container, @Nullable Rect frame) {
        this(container, frame, outlinesOf(points), peers);
    }

    /** As the constructor, with shapes as drawn rather than rectangles. @see Outline */
    public static SnapScene ofOutlines(List<Outline> points, List<Rect> peers,
                                       @Nullable Rect container, @Nullable Rect frame) {
        return new SnapScene(container, frame, points, peers);
    }

    private SnapScene(@Nullable Rect container, @Nullable Rect frame, List<Outline> points, List<Rect> peers) {
        this.points = List.copyOf(points);
        this.peers = List.copyOf(peers);
        this.container = container;
        this.frame = frame;
        this.containerOutline = container == null ? null : Outline.of(container);
        this.frameOutline = frame == null ? null : Outline.of(frame);
    }

    private static List<Outline> outlinesOf(List<Rect> rects) {
        List<Outline> outlines = new ArrayList<>(rects.size());
        for (Rect rect : rects) outlines.add(Outline.of(rect));
        return outlines;
    }

    @Override
    public void collect(SnapAxis axis, Consumer<SnapTarget> sink) {
        if (containerOutline != null) offer(axis, containerOutline, Kind.PARENT_EDGE, Kind.PARENT_CENTRE, sink);
        if (frameOutline != null) offer(axis, frameOutline, Kind.FRAME_EDGE, Kind.FRAME_CENTRE, sink);
        for (Outline outline : points) offer(axis, outline, Kind.EDGE, Kind.CENTRE, sink);
    }

    /** Four corners and the centre, each knowing whose it is. */
    private static void offer(SnapAxis axis, Outline outline, Kind edge, Kind centre, Consumer<SnapTarget> sink) {
        float[] along = axis == SnapAxis.HORIZONTAL ? outline.xs() : outline.ys();
        float[] across = axis == SnapAxis.HORIZONTAL ? outline.ys() : outline.xs();
        for (int i = 0; i < 4; i++) sink.accept(new SnapTarget(along[i], across[i], edge, outline));
        sink.accept(new SnapTarget(along[4], across[4], centre, outline));
    }

    /**
     * This scene with {@code rect}'s corners and centre offered too, and never its gaps.
     *
     * <pre>{@code
     * scene = scene.withPoints(new SnapScene.Rect(x, y, box.width(), box.height()));
     * }</pre>
     */
    public SnapScene withPoints(Rect rect) {
        List<Outline> more = new ArrayList<>(points);
        more.add(Outline.of(rect));
        return new SnapScene(container, frame, more, peers);
    }

    /** Every gap along {@code axis}. */
    public List<Gap> gaps(SnapAxis axis) {
        if (axis == SnapAxis.HORIZONTAL) {
            if (horizontalGaps == null) horizontalGaps = findGaps(axis, peers);
            return horizontalGaps;
        }
        if (verticalGaps == null) verticalGaps = findGaps(axis, peers);
        return verticalGaps;
    }

    private static List<Gap> findGaps(SnapAxis axis, List<Rect> peers) {
        List<Gap> gaps = new ArrayList<>();
        for (Rect before : peers) {
            for (Rect after : peers) {
                if (before == after) continue;
                float from = before.end(axis);
                float to = after.start(axis);
                if (to - from <= GAP_EPSILON) continue;
                float crossFrom = Math.max(before.crossStart(axis), after.crossStart(axis));
                float crossTo = Math.min(before.crossEnd(axis), after.crossEnd(axis));
                if (crossTo <= crossFrom) continue;
                if (occupied(axis, peers, before, after, from, to, crossFrom, crossTo)) continue;
                gaps.add(new Gap(before, after, crossFrom, crossTo));
            }
        }
        return List.copyOf(gaps);
    }

    /**
     * Whether a third peer sits in the space. Excalidraw pairs any two boxes sharing a row, so the ends
     * of a row of three make a gap the middle one fills, and centring in it lands on that box.
     */
    private static boolean occupied(SnapAxis axis, List<Rect> peers, Rect before, Rect after,
                                    float from, float to, float crossFrom, float crossTo) {
        for (Rect other : peers) {
            if (other == before || other == after) continue;
            if (other.end(axis) > from && other.start(axis) < to
                    && other.crossEnd(axis) > crossFrom && other.crossStart(axis) < crossTo) {
                return true;
            }
        }
        return false;
    }
}
