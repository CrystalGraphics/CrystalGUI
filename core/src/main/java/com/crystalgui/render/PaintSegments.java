package com.crystalgui.render;

import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgReplay;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * A paint context's segments (Blink's paint chunks, render-graph's replay): what one box drew, bracketed so it can be
 * kept and recorded again unchanged rather than painted. {@link CgUiPaintContext}'s {@code beginSegment},
 * {@code endSegment}, {@code replay} and {@code replayEpoch} are its doors.
 */
final class PaintSegments {

    /** Off with {@code -Dcrystalgui.paint.segments=false}: a box's paint shares chunks with its neighbours'. */
    static final boolean SEGMENTS = !"false".equals(System.getProperty("crystalgui.paint.segments"));

    private static final int MARKS = 5;

    private final CgUiPaintContext ctx;
    private final CgCanvas recorder;
    private final CgRecording recording;

    /** Per open segment: where it started in the recorder's chunks, its state changes and degraded text draws. */
    private long[] starts = new long[8], states = new long[8], degraded = new long[8];
    /** Per open segment: the recording's operations and clip entries, then the clip entry and nodes it began under. */
    private int[] marks = new int[8 * MARKS];
    private int depth;

    private long epoch, seenFontGeneration, seenEvictions;
    private int seenIconResets;

    /** What the last segment drew, x0, y0, x1, y1 in the target's pixels, and whether that is known. */
    private final float[] drawn = new float[4];
    private boolean drawnKnown;

    PaintSegments(CgUiPaintContext ctx, CgCanvas recorder, CgRecording recording) {
        this.ctx = ctx;
        this.recorder = recorder;
        this.recording = recording;
    }

    /** At a frame's start: an evicted glyph page ends every kept segment. */
    void beginFrame() {
        // A page evicted names a layer another page now holds. New glyphs move nothing: a segment whose text drew
        // without its glyphs is not kept, and growth keeps the atlas's texture.
        long evicted = CgFontRegistry.get().getAtlasEvictionGeneration();
        if (evicted != seenFontGeneration) {
            seenFontGeneration = evicted;
            invalidate("replay-epoch-evicted");
        }
    }

    /** Ends every kept segment: what they recorded no longer draws as it did. */
    void invalidate(String why) {
        CgTrace.add(UiTrace.FRAME, why, 1);
        epoch++;
    }

    void begin() {
        if (!SEGMENTS) return;
        ctx.flush();
        if (depth == starts.length) {
            starts = Arrays.copyOf(starts, depth * 2);
            states = Arrays.copyOf(states, depth * 2);
            degraded = Arrays.copyOf(degraded, depth * 2);
            marks = Arrays.copyOf(marks, depth * 2 * MARKS);
        }
        int s = depth++, m = s * MARKS;
        starts[s] = recorder.chunksTaken();
        states[s] = recorder.stateChanges();
        degraded[s] = recorder.textDegradedDraws();
        marks[m] = recording.operations();
        marks[m + 1] = recording.clips().count();
        marks[m + 2] = ctx.clipEntry();
        marks[m + 3] = ctx.spatialNode();
        marks[m + 4] = ctx.effectNode();
    }

    boolean end(@Nullable CgReplay keep) {
        if (!SEGMENTS) return false;
        ctx.flush();
        int s = --depth, m = s * MARKS;
        drawnSince(starts[s]);
        CgTrace.add(UiTrace.FRAME, "segments", 1);
        CgTrace.add(UiTrace.FRAME, "segment-chunks", recorder.chunksTaken() - starts[s]);
        if (keep == null || !CgUiPaintContext.REPLAY) return false;
        if (recorder.textDegradedDraws() != degraded[s]) {
            keep.clear();
            CgTrace.add(UiTrace.FRAME, "segments-unkept-degraded", 1);
            return false;
        }
        boolean kept = recorder.stateChanges() == states[s] && recording.operations() == marks[m]
                && keep.capture(recorder, starts[s], recording, marks[m + 1], marks[m + 2], marks[m + 3],
                        marks[m + 4]);
        if (!kept) keep.clear();
        CgTrace.add(UiTrace.FRAME, kept ? "segments-kept" : "segments-unkept", 1);
        return kept;
    }

    boolean replay(CgReplay kept) {
        if (!CgUiPaintContext.REPLAY) return false;
        ctx.flush();
        long before = recorder.chunksTaken();
        if (!kept.replay(recorder, recording, ctx.clipEntry(), ctx.spatialNode(), ctx.effectNode())) return false;
        drawnSince(before);
        CgTrace.add(UiTrace.FRAME, "segments-replayed", 1);
        CgTrace.add(UiTrace.FRAME, "chunks-replayed", recorder.chunksTaken() - before);
        return true;
    }

    boolean drawn(float[] out) {
        if (!drawnKnown) return false;
        System.arraycopy(drawn, 0, out, 0, 4);
        return true;
    }

    /** Evictions and icon resets are read live: they move a placement in the middle of a frame. */
    long epoch(int iconResets) {
        long evictions = CgGlyphAtlas.evictions();
        if (evictions != seenEvictions || iconResets != seenIconResets) {
            invalidate(iconResets != seenIconResets ? "replay-epoch-icons" : "replay-epoch-evicted");
            seenEvictions = evictions;
            seenIconResets = iconResets;
        }
        return epoch;
    }

    /** The union of the bounds of every draw taken since chunk {@code from}, carried into the target's pixels. */
    private void drawnSince(long from) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        drawnKnown = true;
        int spatial = ctx.spatialNode();
        for (long at = from, to = recorder.chunksTaken(); at < to; at++) {
            CgDrawChunk chunk = recorder.taken(at);
            if (chunk == null || chunk.spatial() != spatial) {
                drawnKnown = false;
                return;
            }
            for (int d = 0; d < chunk.draws(); d++) {
                float a = chunk.x0(d), b = chunk.y0(d), c = chunk.x1(d), e = chunk.y1(d);
                if (Float.isInfinite(a) || Float.isInfinite(b) || Float.isInfinite(c) || Float.isInfinite(e)) {
                    drawnKnown = false;   // a draw covering everything: its box's ink speaks for it
                    return;
                }
                x0 = Math.min(x0, a);
                y0 = Math.min(y0, b);
                x1 = Math.max(x1, c);
                y1 = Math.max(y1, e);
            }
        }
        if (x1 <= x0 || y1 <= y0) {
            Arrays.fill(drawn, 0f);
            return;
        }
        Matrix4f m = ctx.drawToTarget();
        float ax = m.m00() * x0 + m.m10() * y0 + m.m30(), ay = m.m01() * x0 + m.m11() * y0 + m.m31();
        float bx = m.m00() * x1 + m.m10() * y0 + m.m30(), by = m.m01() * x1 + m.m11() * y0 + m.m31();
        float cx = m.m00() * x1 + m.m10() * y1 + m.m30(), cy = m.m01() * x1 + m.m11() * y1 + m.m31();
        float dx = m.m00() * x0 + m.m10() * y1 + m.m30(), dy = m.m01() * x0 + m.m11() * y1 + m.m31();
        drawn[0] = Math.min(Math.min(ax, bx), Math.min(cx, dx));
        drawn[1] = Math.min(Math.min(ay, by), Math.min(cy, dy));
        drawn[2] = Math.max(Math.max(ax, bx), Math.max(cx, dx));
        drawn[3] = Math.max(Math.max(ay, by), Math.max(cy, dy));
    }
}
