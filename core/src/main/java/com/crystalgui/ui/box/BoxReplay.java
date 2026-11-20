package com.crystalgui.ui.box;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgKeptLayer;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgui.render.CgUiPaintContext;
import javax.annotation.Nullable;

/**
 * A box's two own-paint segments (the draws under its children and the draws over them) as last recorded: the key
 * they were recorded under, so a frame can tell whether a segment would come out the same, and the canvas chunk they
 * drew, so one that would is added again instead of painted. One per box that has painted.
 *
 * <pre>{@code
 * if (box.replay == null) box.replay = new BoxReplay();
 * if (box.replay.sameAs(BoxReplay.BEFORE, key, box.contentRevision, ctx.replayEpoch())
 *         && box.replay.replay(BoxReplay.BEFORE, ctx)) return;
 * ctx.beginSegment();
 * ... paint ...
 * box.replay.kept(BoxReplay.BEFORE, ctx.endSegment(box.replay.chunk(BoxReplay.BEFORE)));
 * }</pre>
 */
final class BoxReplay {

    /** The segment under the box's children, and the one over them. */
    static final int BEFORE = 0, AFTER = 1;
    /**
     * The key's floats: the canvas's transform (six affine components), the part of the box's own ink the clip leaves
     * (four, in device pixels), and the canvas's opacity, which a chunk's colours carry (one).
     */
    static final int KEY_FLOATS = 11;

    private final float[] keys = new float[2 * KEY_FLOATS];
    private final long[] revisions = new long[2];
    private final long[] epochs = new long[2];
    private final boolean[] recorded = new boolean[2];
    private final CgPaintChunk[] chunks = new CgPaintChunk[2];
    private final boolean[] kept = new boolean[2];
    /** Check mode: a fresh paint to compare against what was kept, and whether a difference was named yet. */
    private final CgPaintChunk[] fresh = new CgPaintChunk[2];
    private final boolean[] reported = new boolean[2];
    /** Per segment, where it last drew in a surface. @see #place */
    private final CgKeptLayer.Placement[] placements = new CgKeptLayer.Placement[2];

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
        if (chunks[segment] != null) chunks[segment].clear();
    }

    /** Where {@code segment}'s drawing is kept, made on first use. */
    CgPaintChunk chunk(int segment) {
        CgPaintChunk chunk = chunks[segment];
        if (chunk == null) chunks[segment] = chunk = new CgPaintChunk();
        return chunk;
    }

    /** Whether {@code segment}'s last paint was kept. */
    void kept(int segment, boolean kept) {
        this.kept[segment] = kept;
    }

    /** Whether {@code segment} kept a chunk: what a check compares a fresh paint against. */
    boolean kept(int segment) {
        return kept[segment];
    }

    /**
     * Check mode: ends {@code segment}'s paint, which its key said would come out as kept, keeps it, and answers how
     * it came out otherwise: once per segment, null where it matched or was named before.
     */
    @Nullable
    String check(int segment, CgUiPaintContext ctx) {
        CgPaintChunk paint = fresh[segment];
        if (paint == null) fresh[segment] = paint = new CgPaintChunk();
        boolean keptNow = ctx.endSegment(paint);
        String differs = keptNow ? chunks[segment].differs(paint) : null;
        fresh[segment] = chunks[segment];
        chunks[segment] = paint;
        kept[segment] = keptNow;
        if (differs == null || reported[segment]) return null;
        reported[segment] = true;
        return differs;
    }

    /**
     * Places {@code segment} at {@code (x0, y0)-(x1, y1)}, the device pixels of the node drawn in, in the surface
     * {@code walk} draws: drawn anew ({@code changed}) or moved, it damages both its places.
     */
    void place(CgCanvas canvas, CgKeptLayer.Walk walk, int segment, float x0, float y0, float x1, float y1,
               boolean changed) {
        CgKeptLayer.Placement placement = placements[segment];
        if (placement == null) placements[segment] = placement = new CgKeptLayer.Placement();
        canvas.save().setTransform(1f, 0f, 0f, 1f, 0f, 0f);
        placement.place(walk, x0, y0, x1 - x0, y1 - y0, changed);
        canvas.restore();
    }

    /** Adds {@code segment} again from what it kept, when it kept anything: answers whether it did. */
    boolean replay(int segment, CgUiPaintContext ctx) {
        return kept[segment] && ctx.replay(chunks[segment]);
    }
}
