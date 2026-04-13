package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps vertex formats to {@link CgBatchRenderer} instances for the UI draw list.
 *
 * <p>The primary API is {@link #rendererFor(CgVertexFormat)}, which returns the
 * batch renderer for a given format. Internally, each format also has a stable
 * integer slot index (assigned in insertion order) for compact storage in the
 * packed {@link CgUiDrawList} command pool.</p>
 *
 * <p>V1 uses a single slot via {@link #single(CgVertexFormat, int)}. The
 * multi-slot {@link Builder} supports future multi-format scenarios.</p>
 *
 * @see CgUiDrawList
 * @see CgUiPaintContext
 */
public final class CgUiBatchSlots {

    /**
     * Format → renderer mapping. Insertion order is stable (LinkedHashMap).
     * This is the primary lookup structure.
     */
    private final Map<CgVertexFormat, CgBatchRenderer> renderersByFormat;

    /**
     * Indexed array for slot-index-based lookup from the draw list's packed
     * command pool. Index order matches insertion order into {@link #renderersByFormat}.
     */
    private final CgBatchRenderer[] indexedRenderers;

    /**
     * Parallel array mapping slot index → vertex format, same order as
     * {@link #indexedRenderers}.
     */
    private final CgVertexFormat[] indexedFormats;

    /**
     * Format → slot index mapping for draw list recording. Computed once at
     * construction time from the insertion order of {@link #renderersByFormat}.
     */
    private final Map<CgVertexFormat, Integer> slotIndices;

    private CgUiBatchSlots(Map<CgVertexFormat, CgBatchRenderer> renderersByFormat) {
        this.renderersByFormat = Collections.unmodifiableMap(
                new LinkedHashMap<CgVertexFormat, CgBatchRenderer>(renderersByFormat));

        int size = renderersByFormat.size();
        this.indexedRenderers = new CgBatchRenderer[size];
        this.indexedFormats = new CgVertexFormat[size];
        this.slotIndices = new LinkedHashMap<CgVertexFormat, Integer>();

        int idx = 0;
        for (Map.Entry<CgVertexFormat, CgBatchRenderer> entry : renderersByFormat.entrySet()) {
            indexedRenderers[idx] = entry.getValue();
            indexedFormats[idx] = entry.getKey();
            slotIndices.put(entry.getKey(), idx);
            idx++;
        }
    }

    // ── Factories ───────────────────────────────────────────────────────

    /**
     * Creates a single-slot instance — the standard factory for V1.
     *
     * @param format          the vertex format for slot 0
     * @param initialMaxQuads initial CPU staging capacity in quads
     */
    public static CgUiBatchSlots single(CgVertexFormat format, int initialMaxQuads) {
        if (format == null) throw new IllegalArgumentException("format must not be null");
        Map<CgVertexFormat, CgBatchRenderer> map = new LinkedHashMap<CgVertexFormat, CgBatchRenderer>();
        map.put(format, CgBatchRenderer.create(format, initialMaxQuads));
        return new CgUiBatchSlots(map);
    }

    // ── Primary API: format-based ───────────────────────────────────────

    /**
     * Returns the batch renderer for the given format.
     *
     * @throws IllegalArgumentException if no slot is configured for the format
     */
    public CgBatchRenderer rendererFor(CgVertexFormat format) {
        CgBatchRenderer renderer = renderersByFormat.get(format);
        if (renderer == null) {
            throw new IllegalArgumentException("No slot configured for format: " + format);
        }
        return renderer;
    }

    /**
     * Returns the slot index for the given format. Used by draw list recording
     * to store a compact integer reference in the packed command pool.
     *
     * @throws IllegalArgumentException if no slot is configured for the format
     */
    public int slotIndexFor(CgVertexFormat format) {
        Integer idx = slotIndices.get(format);
        if (idx == null) {
            throw new IllegalArgumentException("No slot configured for format: " + format);
        }
        return idx;
    }

    /**
     * Returns whether a slot is configured for the given format.
     */
    public boolean hasSlot(CgVertexFormat format) {
        return renderersByFormat.containsKey(format);
    }

    // ── Index-based access (for draw list replay) ───────────────────────

    /**
     * Returns the renderer at the given slot index. Used by the draw list
     * executor during replay.
     */
    public CgBatchRenderer renderer(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= indexedRenderers.length) {
            throw new IndexOutOfBoundsException("slotIndex=" + slotIndex + ", size=" + indexedRenderers.length);
        }
        return indexedRenderers[slotIndex];
    }

    /**
     * Returns the vertex format at the given slot index.
     */
    public CgVertexFormat format(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= indexedFormats.length) {
            throw new IndexOutOfBoundsException("slotIndex=" + slotIndex + ", size=" + indexedFormats.length);
        }
        return indexedFormats[slotIndex];
    }

    /** Returns the total number of configured format slots. */
    public int size() { return indexedRenderers.length; }

    /**
     * Returns an unmodifiable view of the format → renderer mapping.
     */
    public Map<CgVertexFormat, CgBatchRenderer> allSlots() {
        return renderersByFormat;
    }

    /**
     * Deletes all owned batch renderers, releasing CPU staging resources.
     */
    public void delete() {
        for (CgBatchRenderer renderer : indexedRenderers) {
            renderer.delete();
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Builder for multi-slot configurations.
     */
    public static final class Builder {
        private final Map<CgVertexFormat, CgBatchRenderer> slots = new LinkedHashMap<CgVertexFormat, CgBatchRenderer>();

        public Builder slot(CgVertexFormat format, int initialMaxQuads) {
            if (format == null) throw new IllegalArgumentException("format must not be null");
            if (slots.containsKey(format)) throw new IllegalArgumentException("Duplicate format: " + format);
            slots.put(format, CgBatchRenderer.create(format, initialMaxQuads));
            return this;
        }

        public CgUiBatchSlots build() {
            if (slots.isEmpty()) throw new IllegalStateException("At least one slot must be configured");
            return new CgUiBatchSlots(new LinkedHashMap<CgVertexFormat, CgBatchRenderer>(slots));
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
