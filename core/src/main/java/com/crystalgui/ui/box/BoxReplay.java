package com.crystalgui.ui.box;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgraphics.render.graph.CgReplay;
import javax.annotation.Nullable;

/**
 * A box's two own-paint segments -- the draws under its children and the draws over them -- as last recorded: the key
 * they were recorded under, so a frame can tell whether a segment would come out the same, and what they recorded, so
 * one that would is added again instead of painted (render-graph G6). One per box that has painted.
 *
 * <pre>{@code
 * if (box.replay == null) box.replay = new BoxReplay();
 * if (box.replay.sameAs(BoxReplay.BEFORE, key, box.contentRevision, ctx.replayEpoch())
 *         && box.replay.replay(BoxReplay.BEFORE, ctx)) return;
 * ctx.beginSegment();
 * ... paint ...
 * box.replay.kept(BoxReplay.BEFORE, ctx.endSegment(box.replay.stretch(BoxReplay.BEFORE)));
 * }</pre>
 */
final class BoxReplay implements Surface.Tenant {

    /** The segment under the box's children, and the one over them. */
    static final int BEFORE = 0, AFTER = 1;
    /**
     * The key's floats: the pose in the box's spatial node (six affine components), the part of the box's own ink
     * the clip leaves (four), and the layer opacity folded into its colours (one).
     */
    static final int KEY_FLOATS = 11;

    private final float[] keys = new float[2 * KEY_FLOATS];
    private final long[] revisions = new long[2];
    private final long[] epochs = new long[2];
    private final boolean[] recorded = new boolean[2];
    private final CgReplay[] stretches = new CgReplay[2];
    private final boolean[] kept = new boolean[2];
    /** Check mode: a fresh paint to compare against what was kept, and whether a difference was named yet. */
    private final CgReplay[] fresh = new CgReplay[2];
    private final boolean[] reported = new boolean[2];
    /** Per segment, the surface it last drew in, where in it (x0, y0, x1, y1) and when. @see #place */
    private final Surface[] placedIn = new Surface[2];
    private final int[] placed = new int[8];
    private final long[] placedFrame = new long[2];

    /**
     * Whether {@code segment} is recorded under this same key; the key is stored either way, and a segment whose key
     * moved keeps nothing until it is painted again.
     */
    boolean sameAs(int segment, float[] key, long revision, long epoch) {
        int o = segment * KEY_FLOATS;
        boolean same = recorded[segment] && revisions[segment] == revision && epochs[segment] == epoch;
        for (int i = 0; same && i < KEY_FLOATS; i++) same = keys[o + i] == key[i];
        System.arraycopy(key, 0, keys, o, KEY_FLOATS);
        revisions[segment] = revision;
        epochs[segment] = epoch;
        recorded[segment] = true;
        if (!same) kept[segment] = false;
        return same;
    }

    /** Forgets {@code segment}: painted where no key speaks for it, so what it kept no longer stands. */
    void forget(int segment) {
        recorded[segment] = false;
        kept[segment] = false;
        if (stretches[segment] != null) stretches[segment].clear();
    }

    /** Where {@code segment}'s recording is kept, made on first use. */
    CgReplay stretch(int segment) {
        CgReplay stretch = stretches[segment];
        if (stretch == null) stretches[segment] = stretch = new CgReplay();
        return stretch;
    }

    /** Whether {@code segment}'s last paint was kept. */
    void kept(int segment, boolean kept) {
        this.kept[segment] = kept;
    }

    /** Whether {@code segment} kept a recording: what a check compares a fresh paint against. */
    boolean kept(int segment) {
        return kept[segment];
    }

    /**
     * Check mode: ends {@code segment}'s paint, which its key said would come out as kept, keeps it, and answers how
     * it came out otherwise -- once per segment, null where it matched or was named before.
     */
    @Nullable
    String check(int segment, CgUiPaintContext ctx) {
        CgReplay paint = fresh[segment];
        if (paint == null) fresh[segment] = paint = new CgReplay();
        boolean keptNow = ctx.endSegment(paint);
        String differs = keptNow ? stretches[segment].differs(paint) : null;
        fresh[segment] = stretches[segment];
        stretches[segment] = paint;
        kept[segment] = keptNow;
        if (differs == null || reported[segment]) return null;
        reported[segment] = true;
        return differs;
    }

    /**
     * Places {@code segment} at {@code (x0, y0)-(x1, y1)} in {@code surface}, which it drew into this walk: drawn anew
     * ({@code changed}) or moved, it damages both its places. @see Surface.Tenant
     */
    void place(Surface surface, int segment, int x0, int y0, int x1, int y1, boolean changed, long frame) {
        int o = segment * 4;
        boolean here = placedIn[segment] == surface;
        if (changed || !here || placed[o] != x0 || placed[o + 1] != y0 || placed[o + 2] != x1 || placed[o + 3] != y1) {
            surface.damage(x0, y0, x1, y1);
            if (here) surface.damage(placed[o], placed[o + 1], placed[o + 2], placed[o + 3]);
        }
        placed[o] = x0;
        placed[o + 1] = y0;
        placed[o + 2] = x1;
        placed[o + 3] = y1;
        placedIn[segment] = surface;
        placedFrame[segment] = frame;
        surface.visit(this, segment);
    }

    @Override
    public void vacate(Surface surface, int part, long frame) {
        if (placedIn[part] != surface || placedFrame[part] == frame) return;
        int o = part * 4;
        surface.damage(placed[o], placed[o + 1], placed[o + 2], placed[o + 3]);
        placedIn[part] = null;
    }

    /** Records {@code segment} again from what it kept, when it kept anything: answers whether it did. */
    boolean replay(int segment, CgUiPaintContext ctx) {
        return kept[segment] && ctx.replay(stretches[segment]);
    }
}
