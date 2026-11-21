package com.crystalgui.render;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * A paint context's segments: what one box drew, taken as a canvas {@link CgPaintChunk} so it can be kept and added
 * again unchanged rather than painted. {@link CgUiPaintContext}'s {@code beginSegment}, {@code endSegment},
 * {@code replay} and {@code replayEpoch} are its doors.
 */
final class PaintSegments {

    /** Read once: a segment opened under one setting and closed under another would unbalance the canvas. */
    static final CgVar.Bool SEGMENTS = CgVar.bool("crystalgui.paint.segments", true, CgVar.DEV | CgVar.RESTART,
            "Takes each box's own paint apart from its neighbours' as a canvas chunk. Off: no segment, no replay.");
    private static final CgVar.Bool REPLAY = CgVar.bool("crystalgui.paint.replay", true, CgVar.DEV,
            "Adds a box's kept segment again when its key holds, instead of painting it. Off: every box paints.");

    /** Whether a kept segment may stand in for a paint. */
    static boolean replays() {
        return SEGMENTS.get() && REPLAY.get();
    }

    private final CgPaintChunk scratch = new CgPaintChunk();

    /** Per open segment: whether it took a chunk. Chunks do not nest: one opened inside another's takes none. */
    private boolean[] chunked = new boolean[8];
    private int depth;

    private long epoch, seenFontGeneration, seenEvictions;

    /** What the last segment drew, x0, y0, x1, y1 in the device pixels of the node drawn in, and whether that is known. */
    private final float[] drawn = new float[4];
    private boolean drawnKnown;

    /** At a frame's start: an evicted glyph page ends every kept surface. */
    void beginFrame() {
        long evicted = CgFontRegistry.get().getAtlasEvictionGeneration();
        if (evicted != seenFontGeneration) {
            seenFontGeneration = evicted;
            invalidate("replay-epoch-evicted");
        }
        depth = 0;
    }

    /** Ends every kept surface: what they hold no longer draws as it did. */
    void invalidate(String why) {
        CgTrace.add(UiTrace.FRAME, why, 1);
        epoch++;
    }

    void begin(CgCanvas canvas) {
        if (!SEGMENTS.get()) return;
        if (depth == chunked.length) chunked = Arrays.copyOf(chunked, depth * 2);
        boolean take = !canvas.chunking();
        chunked[depth++] = take;
        if (take) canvas.beginChunk();
    }

    boolean end(CgCanvas canvas, @Nullable CgPaintChunk keep) {
        if (!SEGMENTS.get()) return false;
        drawnKnown = false;
        CgTrace.add(UiTrace.FRAME, "segments", 1);
        if (!chunked[--depth]) {
            if (keep != null) keep.clear();
            return false;
        }
        CgPaintChunk into = keep != null && replays() ? keep : scratch;
        CgPaintChunk.Refusal why = canvas.endChunk(into);
        if (into.kept()) drawn(into);
        if (into == scratch) {
            scratch.clear();
            return false;
        }
        CgTrace.add(UiTrace.FRAME, why == CgPaintChunk.Refusal.NONE ? "segments-kept" : "segments-unkept", 1);
        if (why != CgPaintChunk.Refusal.NONE) CgTrace.add(UiTrace.FRAME, "segments-unkept-" + why.name(), 1);
        return why == CgPaintChunk.Refusal.NONE;
    }

    boolean replay(CgCanvas canvas, CgPaintChunk kept) {
        drawnKnown = false;
        // Inside another's chunk a replay would be taken twice: the box paints
        if (!replays() || canvas.chunking() || !canvas.replay(kept)) return false;
        drawn(kept);
        CgTrace.add(UiTrace.FRAME, "segments-replayed", 1);
        return true;
    }

    boolean drawn(float[] out) {
        if (!drawnKnown) return false;
        System.arraycopy(drawn, 0, out, 0, 4);
        return true;
    }

    private void drawn(CgPaintChunk chunk) {
        drawnKnown = true;
        drawn[0] = chunk.x0();
        drawn[1] = chunk.y0();
        drawn[2] = chunk.x1();
        drawn[3] = chunk.y1();
    }

    /** Evictions are read live: they move a kept picture in the middle of a frame. */
    long epoch() {
        long evictions = CgGlyphAtlas.evictions();
        if (evictions != seenEvictions) {
            invalidate("replay-epoch-evicted");
            seenEvictions = evictions;
        }
        return epoch;
    }
}
