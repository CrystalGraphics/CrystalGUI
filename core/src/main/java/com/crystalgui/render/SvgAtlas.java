package com.crystalgui.render;

import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgui.render.texture.svg.SvgDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The one icon atlas every document's {@link SvgRasterCache} shares: a cell is rasterised once, by whichever
 * document asks first, and drawn by all.
 *
 * <p>Each document records on its own sequence, so a cell's build is part of one document's recording and executes
 * with that document's frame, in an order against the others' frames that nothing fixes. The rule that makes this
 * safe is that <b>a cell is drawn only by its builder until the build has executed</b>: a callback recorded after
 * the build ({@code ready}) runs on the render thread and opens the cell to everyone. Another document that meets
 * a pending cell draws nothing of that icon this frame, and its segments are painted again once the cell is ready.
 * The same holds for the atlas itself: a {@link Generation} is cleared by the recording of the document that made
 * it, and nobody else claims a cell in it until that clear has executed.</p>
 *
 * <p>When the atlas is full a new generation (a new texture) replaces it, so a recording made before the reset
 * still writes into the texture its cells name. The generation before the last is released by the document that
 * caused the reset.</p>
 */
final class SvgAtlas {

    /** The process's atlas. */
    static final SvgAtlas SHARED = new SvgAtlas();

    static final int SIZE = 1024;
    static final CgFormat FORMAT = CgFormat.RGBA16_FLOAT;

    /** A raster's identity. Mutable so a lookup reuses one probe; only a miss stores a {@link #copy()}. */
    static final class Key {
        SvgDocument document;
        int op, deviceScaleBits, halfWidthBits;
        boolean flat;

        Key set(SvgDocument document, int op, int deviceScaleBits, boolean flat, int halfWidthBits) {
            this.document = document;
            this.op = op;
            this.deviceScaleBits = deviceScaleBits;
            this.flat = flat;
            this.halfWidthBits = halfWidthBits;
            return this;
        }

        Key copy() {
            return new Key().set(document, op, deviceScaleBits, flat, halfWidthBits);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.document == document && key.op == op
                    && key.deviceScaleBits == deviceScaleBits && key.flat == flat && key.halfWidthBits == halfWidthBits;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(document);
            h = 31 * h + op;
            h = 31 * h + deviceScaleBits;
            h = 31 * h + (flat ? 1 : 0);
            return 31 * h + halfWidthBits;
        }
    }

    /** One texture of cells, and whether its clear has executed. */
    static final class Generation {
        final CgGraphTexture texture;
        final SvgRasterCache owner;
        volatile boolean ready;

        Generation(CgGraphTexture texture, SvgRasterCache owner) {
            this.texture = texture;
            this.owner = owner;
        }
    }

    /** One rasterised fill: its rect, and where the document's origin sits inside it, in device pixels. */
    static final class Entry {
        Generation atlas;
        int x, y, width, height;
        float originX, originY;
        /** The fill's own colours are in the raster, premultiplied; otherwise it is straight-alpha white coverage. */
        boolean baked;
        /** The build has executed: any document may draw it. */
        volatile boolean ready;
        SvgRasterCache owner;
        /** For the builder alone, once: it records the generation's clear first. */
        boolean clears;
        /** For the builder alone, once: it has yet to record the build. */
        boolean needsBuild;
        /** For the builder alone, once: a generation it replaced, to release in its recording. */
        @Nullable
        CgGraphTexture releases;
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final List<CgGraphTexture> lived = new ArrayList<>();
    @Nullable
    private Generation current;
    @Nullable
    private CgGraphTexture previous;
    private int shelfX, shelfY, shelfHeight, generations;
    private volatile int epoch;
    private volatile int resets;
    /** A document drew without a pending cell since the last one became ready: its segments need painting again. */
    private boolean missed;

    private SvgAtlas() {
    }

    /** The cell for {@code key}, built or pending; null when there is none. Lock-free. */
    @Nullable
    Entry find(Key key) {
        return entries.get(key);
    }

    /**
     * The cell for {@code key}, placed if no one has: the caller is its builder when {@code needsBuild} is set on the
     * answer. Null when the atlas is another document's to clear first.
     */
    @Nullable
    synchronized Entry claim(Key key, int width, int height, SvgRasterCache by) {
        Entry existing = entries.get(key);
        if (existing != null) return existing;
        CgGraphTexture released = null;
        boolean clears = false;
        if (current == null) {
            current = made(by);
            clears = true;
        } else if (!current.ready && current.owner != by) {
            return null;
        }
        if (width > SIZE || height > SIZE) {
            throw new IllegalArgumentException("SVG raster " + width + "x" + height + " exceeds the atlas");
        }
        if (shelfX + width > SIZE) {
            shelfY += shelfHeight;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > SIZE) {
            released = previous;
            lived.remove(released);
            previous = current.texture;
            current = made(by);
            clears = true;
            entries.clear();
            shelfX = shelfY = shelfHeight = 0;
            resets++;
            epoch++;
        }
        Entry entry = new Entry();
        entry.atlas = current;
        entry.x = shelfX;
        entry.y = shelfY;
        entry.width = width;
        entry.height = height;
        entry.owner = by;
        entry.needsBuild = true;
        entry.clears = clears;
        entry.releases = released;
        shelfX += width;
        shelfHeight = Math.max(shelfHeight, height);
        entries.put(key.copy(), entry);
        return entry;
    }

    private Generation made(SvgRasterCache by) {
        CgGraphTexture texture = CgGraphTexture.requested("cgui_svg_raster_" + generations++,
                CgTextureDesc.of(SIZE, SIZE, FORMAT));
        lived.add(texture);
        return new Generation(texture, by);
    }

    /** Render thread: the generation's clear has executed. */
    synchronized void ready(Generation generation) {
        generation.ready = true;
    }

    /** Render thread: the build of {@code entry} has executed. */
    synchronized void ready(Entry entry) {
        entry.ready = true;
        if (missed) {
            missed = false;
            epoch++;
        }
    }

    /** A draw found a cell it may not use yet. */
    synchronized void missed() {
        missed = true;
    }

    /**
     * Whatever {@code by} claimed and never executed goes: its frame was abandoned or its view closed, so nothing
     * will ever make the cells ready, and everyone else would wait for them.
     */
    synchronized void abandon(SvgRasterCache by) {
        entries.values().removeIf(entry -> entry.owner == by && !entry.ready);
        if (current != null && !current.ready && current.owner == by) {
            current = null;
            entries.clear();
            shelfX = shelfY = shelfHeight = 0;
            epoch++;
        }
    }

    /** What replayed segments are keyed under: moves when a cell moved or a missed draw can now be made. */
    int epoch() {
        return epoch;
    }

    /** Times the atlas was cleared and its icons placed again. */
    int resets() {
        return resets;
    }

    /** The graphics context is going: free every generation made. Render thread. */
    synchronized void destroy() {
        for (CgGraphTexture texture : lived) CgExecutor.freeStorage(texture);
        lived.clear();
        entries.clear();
        current = null;
        previous = null;
        missed = false;
        shelfX = shelfY = shelfHeight = 0;
        epoch++;
    }
}
