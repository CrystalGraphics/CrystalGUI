package com.crystalgui.widget.surface.snap;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * <b>One axis, solved against points.</b> Every (point, base) inside the tolerance is a candidate; the
 * nearest decides where the box goes, and every other candidate landing it in the same place is kept.
 *
 * <pre>{@code
 * SnapSolution x = new Snapper(SnapAxis.HORIZONTAL, wantX, width, 8f, zoom).solve(targets);
 * write(x.value());
 * }</pre>
 *
 * <pre>{@code
 * // An edge alone, which is what a resize snaps.
 * SnapSolution right = Snapper.edge(SnapAxis.HORIZONTAL, x + width, 8f, zoom).solve(targets);
 * }</pre>
 *
 * <p><b>The tolerance is in SCREEN pixels, against a scale</b>, so snapping feels the same distance at
 * any zoom. A scale rather than a pre-divided number because a perspective camera cannot live without
 * it: under one, screen distance is not world distance and varies with depth.</p>
 *
 * <p>Most callers want {@link SnapSolver}, which adds gaps and what to draw.</p>
 */
public final class Snapper {

    /** Two origins closer than this are the same place. */
    private static final float SAME = 0.01f;

    private static final SnapBase[] ALL = SnapBase.values();

    /** At zero extent the three bases coincide; one is enough, and three would mark the edge thrice. */
    private static final SnapBase[] EDGE_ONLY = {SnapBase.LEADING};

    private record Candidate(float origin, float distance, SnapSolution.Hit hit) {
    }

    private final SnapAxis axis;
    private final float wanted;
    private final float extent;
    private final float screenTolerance;
    private final float scale;
    private final SnapBase[] bases;
    private final List<Candidate> candidates = new ArrayList<>();

    /**
     * @param wanted          where the box's origin would go unsnapped
     * @param extent          its width or height on this axis
     * @param screenTolerance how close counts, in screen pixels
     * @param scale           world units to screen pixels — the zoom, in 2D
     */
    public Snapper(SnapAxis axis, float wanted, float extent, float screenTolerance, float scale) {
        this(axis, wanted, extent, screenTolerance, scale, ALL);
    }

    private Snapper(SnapAxis axis, float wanted, float extent, float screenTolerance, float scale,
                    SnapBase[] bases) {
        this.axis = axis;
        this.wanted = wanted;
        this.extent = extent;
        this.screenTolerance = screenTolerance;
        this.scale = scale <= 0f ? 1f : scale;
        this.bases = bases;
    }

    /** One edge, landed by itself: a box of no extent. */
    public static Snapper edge(SnapAxis axis, float wanted, float screenTolerance, float scale) {
        return new Snapper(axis, wanted, 0f, screenTolerance, scale, EDGE_ONLY);
    }

    /** Asks {@code targets} for this axis and answers where the box goes and what put it there. */
    public SnapSolution solve(SnapTargets targets) {
        candidates.clear();
        targets.collect(axis, this::offer);
        return settle();
    }

    /** One point, against each of the moving box's features. */
    public void offer(@Nullable SnapTarget target) {
        if (target == null) return;
        for (SnapBase base : bases) {
            float origin = base.originFor(target.at(), extent);
            float distance = Math.abs(origin - wanted) * scale;
            if (distance <= screenTolerance) {
                candidates.add(new Candidate(origin, distance, new SnapSolution.Hit(target, base)));
            }
        }
    }

    private SnapSolution settle() {
        if (candidates.isEmpty()) return SnapSolution.none(wanted);
        Candidate best = candidates.get(0);
        for (Candidate c : candidates) {
            if (better(c, best)) best = c;
        }
        List<SnapSolution.Hit> hits = new ArrayList<>();
        hits.add(best.hit());
        for (Candidate c : candidates) {
            if (c != best && Math.abs(c.origin() - best.origin()) < SAME) hits.add(c.hit());
        }
        return new SnapSolution(best.origin(), true, List.copyOf(hits));
    }

    /** Nearest, then what the point belongs to, then which feature landed. */
    private static boolean better(Candidate a, Candidate b) {
        if (a.distance() != b.distance()) return a.distance() < b.distance();
        int kind = Integer.compare(a.hit().target().kind().ordinal(), b.hit().target().kind().ordinal());
        if (kind != 0) return kind < 0;
        return a.hit().base().ordinal() < b.hit().base().ordinal();
    }
}
