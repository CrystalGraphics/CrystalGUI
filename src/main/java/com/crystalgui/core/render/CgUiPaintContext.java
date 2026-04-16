package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexConsumer;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.state.CgScissorRect;
import io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer;
import io.github.somehussar.crystalgraphics.text.render.CgTextQuadSink;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;
import io.github.somehussar.crystalgraphics.api.PoseStack;

import javax.annotation.Nullable;

/**
 * Primary paint surface passed through UI traversal in the draw-list architecture.
 *
 * <p>Provides both a high-level convenience API ({@link #fillRect}, {@link #drawImage})
 * and a low-level vertex recording API ({@link #setDrawState}, {@link #vertex}).
 * All geometry is recorded into the underlying {@link CgUiDrawList} in painter's
 * order during DOM traversal — no GL state is mutated during recording.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 * beginRecord()
 *   → element.draw(paintContext) traversal
 * endRecord()
 * </pre>
 *
 * @see CgUiDrawList
 * @see CgUiDrawListExecutor
 */
public final class CgUiPaintContext {

    private final CgUiDrawList drawList;
    private final CgUiBatchSlots batchSlots;
    private final ScissorStack scissorStack;
    @Nullable
    private final CgUiRuntime runtime;

    // Cached solid-fill render state resolved from the runtime at construction time.
    // Elements use fillRect(x,y,w,h,rgba) which delegates here instead of reaching
    // into the runtime directly.
    @Nullable
    private final CgRenderState solidFillState;

    // Context-owned text services (optional until configured by the host)
    private CgTextRenderer textRenderer;
    private CgFontFamily defaultFontFamily;
    private CgTextRenderContext textRenderContext;
    private long textFrame;

    // Reusable builder for measureTextWidth — avoids allocation per call
    private CgTextLayoutBuilder measureBuilder;

    // Current recording state
    private CgRenderState currentRenderState;
    private int currentBatchSlot;
    private boolean recording;

    public CgUiPaintContext(CgUiDrawList drawList,
                            CgUiBatchSlots batchSlots,
                            ScissorStack scissorStack,
                            @Nullable CgUiRuntime runtime) {
        this.drawList = drawList;
        this.batchSlots = batchSlots;
        this.scissorStack = scissorStack;
        this.runtime = runtime;
        this.solidFillState = (runtime != null) ? runtime.solidFill() : null;
        if (runtime != null && runtime.hasTextServices()) {
            this.textRenderer = runtime.getTextRenderer();
            this.defaultFontFamily = runtime.getDefaultFontFamily();
        }
    }

    // ── Frame lifecycle ─────────────────────────────────────────────────

    public void beginRecord() {
        drawList.beginRecord();
        scissorStack.reset();
        for (int s = 0; s < batchSlots.size(); s++) {
            batchSlots.renderer(s).begin();
        }
        currentRenderState = null;
        recording = true;
    }

    public void endRecord() {
        recording = false;
        drawList.endRecord();
    }

    /**
     * Configures the context-owned text services used by the direct drawText helpers.
     * Hosts (harness, Minecraft UI integration) set this once during setup and may
     * update the frame counter each render.
     */
    public void configureText(CgTextRenderer textRenderer,
                              CgFontFamily defaultFontFamily,
                              CgTextRenderContext textRenderContext) {
        if (textRenderer == null) throw new IllegalArgumentException("textRenderer must not be null");
        if (defaultFontFamily == null) throw new IllegalArgumentException("defaultFontFamily must not be null");
        if (textRenderContext == null) throw new IllegalArgumentException("textRenderContext must not be null");
        this.textRenderer = textRenderer;
        this.defaultFontFamily = defaultFontFamily;
        this.textRenderContext = textRenderContext;
    }

    public void setTextFrame(long frame) {
        this.textFrame = frame;
    }

    /**
     * Completes the frame lifecycle for all owned batch slots after replay has finished.
     * Must be called once per frame after {@link CgUiDrawListExecutor#execute}.
     *
     * <p>This closes every begun {@link CgBatchRenderer}, releasing the per-frame
     * recording lifecycle so the next {@link #beginRecord()} can safely call
     * {@link CgBatchRenderer#begin()} again.</p>
     */
    public void finishFrame() {
        currentRenderState = null;
        recording = false;
        for (int s = 0; s < batchSlots.size(); s++) {
            batchSlots.renderer(s).end();
        }
    }

    // ── Low-level recording API ─────────────────────────────────────────

    /**
     * Sets the active render state and vertex format for subsequent vertex recording.
     * Must be called before {@link #vertex()} or {@link #reserveQuads(int)}.
     */
    public void setDrawState(CgRenderState state, CgVertexFormat format) {
        if (!recording) throw new IllegalStateException("Not recording");
        if (state == null) throw new IllegalArgumentException("state must not be null");
        currentRenderState = state;
        currentBatchSlot = batchSlots.slotIndexFor(format);
    }

    public CgVertexConsumer vertex() {
        if (!recording) throw new IllegalStateException("Not recording");
        if (currentRenderState == null) throw new IllegalStateException("No draw state set — call setDrawState() first");
        return batchSlots.renderer(currentBatchSlot).vertex();
    }

    /**
     * Pre-allocates staging buffer space for the given number of quads.
     * Call before emitting quad vertices to avoid mid-burst growth.
     */
    public void reserveQuads(int count) {
        if (!recording) throw new IllegalStateException("Not recording");
        if (currentRenderState == null) throw new IllegalStateException("No draw state set");
        batchSlots.renderer(currentBatchSlot).staging().ensureRoomForQuads(count);
    }

    /**
     * Emits a recorded quad (4 vertices) into the current batch slot and
     * records the corresponding draw command in the draw list.
     *
     * <p>Callers should have already written 4 vertices via {@link #vertex()}
     * before calling this. This method records the command that references
     * those vertices.</p>
     *
     * @param vtxStart the vertex index of the first of the 4 vertices in the batch slot
     * @param vtxCount must be 4 (one quad)
     */
    public void recordCommand(int vtxStart, int vtxCount) {
        if (!recording) throw new IllegalStateException("Not recording");
        CgScissorRect sc = scissorStack.current();
        int sx = 0, sy = 0, sw = 0, sh = 0;
        if (sc != null) {
            sx = sc.getX(); sy = sc.getY(); sw = sc.getWidth(); sh = sc.getHeight();
        }
        drawList.record(currentRenderState, currentBatchSlot, sx, sy, sw, sh, vtxStart, vtxCount);
    }

    // ── Scissor stack ───────────────────────────────────────────────────

    public void pushScissor(int x, int y, int w, int h) {
        if (!recording) throw new IllegalStateException("Not recording");
        scissorStack.push(x, y, w, h);
    }

    public void popScissor() {
        if (!recording) throw new IllegalStateException("Not recording");
        scissorStack.pop();
    }

    // ── High-level convenience API ──────────────────────────────────────

    /**
     * Emits a positioned quad (4 vertices) with the given render state, UVs, and color,
     * and records the draw command. This is the general-purpose quad helper.
     *
     * @param state the prebuilt render state
     * @param x0    left X
     * @param y0    top Y
     * @param x1    right X
     * @param y1    bottom Y
     * @param u0    left UV
     * @param v0    top UV
     * @param u1    right UV
     * @param v1    bottom UV
     * @param rgba  packed RGBA color (0xRRGGBBAA)
     */
    public void quad(CgRenderState state, float x0, float y0, float x1, float y1,
                     float u0, float v0, float u1, float v1, int rgba) {
        setDrawState(state, CgVertexFormat.POS2_UV2_COL4UB);
        CgBatchRenderer renderer = batchSlots.renderer(currentBatchSlot);
        renderer.staging().ensureRoomForQuads(1);

        int vtxBefore = renderer.staging().vertexCount();

        int r = (rgba >>> 24) & 0xFF;
        int g = (rgba >>> 16) & 0xFF;
        int b = (rgba >>> 8)  & 0xFF;
        int a =  rgba         & 0xFF;

        CgVertexConsumer vc = renderer.vertex();
        vc.vertex(x0, y0).uv(u0, v0).color(r, g, b, a).endVertex();
        vc = renderer.vertex();
        vc.vertex(x1, y0).uv(u1, v0).color(r, g, b, a).endVertex();
        vc = renderer.vertex();
        vc.vertex(x1, y1).uv(u1, v1).color(r, g, b, a).endVertex();
        vc = renderer.vertex();
        vc.vertex(x0, y1).uv(u0, v1).color(r, g, b, a).endVertex();

        recordCommand(vtxBefore, 4);
    }

    /**
     * Fills a rectangle with the given render state and color.
     */
    public void fillRect(CgRenderState state, float x, float y, float w, float h, int rgba) {
        quad(state, x, y, x + w, y + h, 0, 0, 1, 1, rgba);
    }

    /**
     * Fills a solid-color rectangle using the context's cached solid-fill render state.
     * Elements should use this instead of reaching into the runtime for a draw state.
     */
    public void fillRect(float x, float y, float w, float h, int rgba) {
        if (solidFillState == null) {
            throw new IllegalStateException("No solid-fill render state available — runtime not configured");
        }
        fillRect(solidFillState, x, y, w, h, rgba);
    }

    /**
     * Draws a textured image quad.
     */
    public void drawImage(CgRenderState state, float x, float y, float w, float h,
                          float u0, float v0, float u1, float v1, int rgba) {
        quad(state, x, y, x + w, y + h, u0, v0, u1, v1, rgba);
    }

    /**
     * Fills a rectangle with separate float color components (0.0-1.0).
     */
    public void fillRect(CgRenderState state, float x, float y, float w, float h,
                         float r, float g, float b, float a) {
        fillRect(state, x, y, w, h, packRgba(r, g, b, a));
    }

    /**
     * Draws the border (outline) of a rectangle as four thin quads.
     *
     * @param state     the prebuilt render state
     * @param x         left edge
     * @param y         top edge
     * @param w         width of the rectangle
     * @param h         height of the rectangle
     * @param thickness border thickness in pixels
     * @param rgba      packed RGBA color (0xRRGGBBAA)
     */
    public void strokeRect(CgRenderState state, float x, float y, float w, float h,
                           float thickness, int rgba) {
        // Top edge
        fillRect(state, x, y, w, thickness, rgba);
        // Bottom edge
        fillRect(state, x, y + h - thickness, w, thickness, rgba);
        // Left edge (between top and bottom)
        fillRect(state, x, y + thickness, thickness, h - 2 * thickness, rgba);
        // Right edge (between top and bottom)
        fillRect(state, x + w - thickness, y + thickness, thickness, h - 2 * thickness, rgba);
    }

    /**
     * Draws a horizontal line as a 1px-high filled rectangle.
     */
    public void hLine(CgRenderState state, float x, float y, float length, int rgba) {
        fillRect(state, x, y, length, 1, rgba);
    }

    /**
     * Draws a vertical line as a 1px-wide filled rectangle.
     */
    public void vLine(CgRenderState state, float x, float y, float length, int rgba) {
        fillRect(state, x, y, 1, length, rgba);
    }

    /**
     * Draws text into the draw list via a {@link CgTextQuadSink} adapter.
     *
     * <p>The caller provides a callback that receives the text quad sink and uses
     * the CrystalGraphics text renderer's target-based API to emit glyphs. All
     * emitted quads are recorded into the draw list in painter's order.</p>
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * paintContext.drawText(sink -> {
     *     textRenderer.drawInternalTarget(sink, layout, font, x, y, color, frame, renderCtx, poseStack);
     * });
     * }</pre>
     *
     * @param emitter a callback that receives the text quad sink
     */
    public void drawText(TextEmitter emitter) {
        if (!recording) throw new IllegalStateException("Not recording");
        DrawListTextSink sink = new DrawListTextSink(this);
        emitter.emit(sink);
        sink.endText();
    }

    /**
     * Convenience helper that emits text through the generic draw-list text sink.
     * This is the normal text path for UI elements that already have a text renderer,
     * font family, and render context available.
     */
    public void drawText(CgTextRenderer textRenderer,
                         CgTextLayout layout,
                         CgFontFamily family,
                         float x,
                         float y,
                         int rgba,
                         long frame,
                         CgTextRenderContext context,
                         PoseStack pose) {
        if (textRenderer == null) throw new IllegalArgumentException("textRenderer must not be null");
        if (layout == null) throw new IllegalArgumentException("layout must not be null");
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (pose == null) throw new IllegalArgumentException("pose must not be null");
        if (!recording) throw new IllegalStateException("Not recording");

        DrawListTextSink sink = new DrawListTextSink(this);
        textRenderer.drawInternalTarget(sink, layout, family, x, y, rgba, frame, context, pose);
        sink.endText();
    }

    /**
     * Convenience overload from raw string. Builds layout on demand using the renderer.
     */
    public void drawText(CgTextRenderer textRenderer,
                         String text,
                         CgFontFamily family,
                         float x,
                         float y,
                         int rgba,
                         long frame,
                         CgTextRenderContext context,
                         PoseStack pose) {
        if (textRenderer == null) throw new IllegalArgumentException("textRenderer must not be null");
        if (text == null || text.isEmpty()) return;
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (pose == null) throw new IllegalArgumentException("pose must not be null");
        if (!recording) throw new IllegalStateException("Not recording");

        DrawListTextSink sink = new DrawListTextSink(this);
        textRenderer.drawInternalTarget(sink, text, family, x, y, rgba, frame, context, pose);
        sink.endText();
    }

    /**
     * Direct convenience text helper using the context-owned text services.
     */
    public void drawText(String text, float x, float y, int rgba) {
        ensureTextConfigured();
        if (text == null || text.isEmpty()) return;
        DrawListTextSink sink = new DrawListTextSink(this);
        textRenderer.drawInternalTarget(sink, text, defaultFontFamily, x, y, rgba, textFrame, textRenderContext, new PoseStack());
        sink.endText();
    }

    /**
     * Direct convenience text helper using the context-owned renderer/context and an explicit font family.
     */
    public void drawText(CgFontFamily family, String text, float x, float y, int rgba) {
        ensureTextConfigured();
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (text == null || text.isEmpty()) return;
        DrawListTextSink sink = new DrawListTextSink(this);
        textRenderer.drawInternalTarget(sink, text, family, x, y, rgba, textFrame, textRenderContext, new PoseStack());
        sink.endText();
    }

    /**
     * Functional interface for text emission into the draw list.
     */
    public interface TextEmitter {
        void emit(CgTextQuadSink sink);
    }

    // ── Access ──────────────────────────────────────────────────────────

    public CgUiDrawList getDrawList() { return drawList; }
    public CgUiBatchSlots getBatchSlots() { return batchSlots; }
    public ScissorStack getScissorStack() { return scissorStack; }

    /**
     * @deprecated Elements must not use the runtime directly. Use {@link #fillRect(float, float, float, float, int)}
     *             or other context convenience methods instead.
     */
    @Deprecated
    public CgUiRuntime getRuntime() { return runtime; }
    public boolean isRecording() { return recording; }

    public boolean hasTextServices() {
        return textRenderer != null && defaultFontFamily != null && textRenderContext != null;
    }

    public CgFontFamily getDefaultFontFamily() {
        return defaultFontFamily;
    }

    public float measureTextWidth(CgFontFamily family, String text) {
        if (family == null || text == null || text.isEmpty()) return 0;
        if (measureBuilder == null) {
            measureBuilder = new CgTextLayoutBuilder();
        }
        CgTextLayout layout = measureBuilder.layout(text, family, 0, 0);
        return layout.getTotalWidth();
    }

    // ── Color packing utility ───────────────────────────────────────────

    /**
     * Packs float color components (0.0-1.0) into a single RGBA int (0xRRGGBBAA).
     */
    public static int packRgba(float r, float g, float b, float a) {
        return ((int) (r * 255.0f) & 0xFF) << 24
             | ((int) (g * 255.0f) & 0xFF) << 16
             | ((int) (b * 255.0f) & 0xFF) << 8
             | ((int) (a * 255.0f) & 0xFF);
    }

    private void ensureTextConfigured() {
        if (!hasTextServices()) {
            throw new IllegalStateException("Text services are not configured on this CgUiPaintContext");
        }
        if (!recording) {
            throw new IllegalStateException("Not recording");
        }
    }

    // ── Package-private helpers for DrawListTextSink ─────────────────────

    /**
     * Records a text draw command in the draw list with the current scissor state.
     * Package-private: called by {@link DrawListTextSink} to flush pending text vertices.
     */
    void recordTextCommand(CgRenderState renderState, int textureId, float pxRange,
                           int vtxStart, int vtxCount) {
        CgScissorRect sc = scissorStack.current();
        int sx = 0, sy = 0, sw = 0, sh = 0;
        if (sc != null) {
            sx = sc.getX(); sy = sc.getY(); sw = sc.getWidth(); sh = sc.getHeight();
        }
        drawList.recordText(renderState, textureId, pxRange, currentBatchSlot,
                            sx, sy, sw, sh, vtxStart, vtxCount);
    }

    // ── Inner text quad sink adapter ────────────────────────────────────

    /**
     * Bridges CrystalGraphics' generic {@link CgTextQuadSink} to the draw-list
     * recording path. Converts {@link CgTextQuadSink#beginBatch} transitions into
     * text draw commands with the appropriate render state, texture, and pxRange.
     *
     * <p>Tracks the vertex cursor internally. On each {@code beginBatch()}, any
     * pending vertices from the previous batch are flushed as a draw command.
     * After the text renderer returns, the caller invokes {@link #endText()}
     * to record any remaining vertices.</p>
     */
    private static final class DrawListTextSink implements CgTextQuadSink {
        private final CgUiPaintContext ctx;
        private CgRenderState batchRenderState;
        private int batchTextureId;
        private float batchPxRange;
        private int vtxCursorStart = -1;

        DrawListTextSink(CgUiPaintContext ctx) { this.ctx = ctx; }

        @Override
        public void beginBatch(CgRenderState renderState, int textureId, float pxRange) {
            flushPendingVertices();
            batchRenderState = renderState;
            batchTextureId = textureId;
            batchPxRange = pxRange;
            // Set the render state + batch slot on the paint context
            ctx.setDrawState(renderState, CgVertexFormat.POS2_UV2_COL4UB);
            vtxCursorStart = currentStagingVertexCount();
        }

        @Override
        public void emitQuad(float x0, float y0, float x1, float y1,
                             float u0, float v0, float u1, float v1,
                             int r, int g, int b, int a) {
            CgBatchRenderer renderer = ctx.batchSlots.renderer(ctx.currentBatchSlot);
            renderer.staging().ensureRoomForQuads(1);
            CgVertexConsumer vc = renderer.vertex();
            vc.vertex(x0, y0).uv(u0, v0).color(r, g, b, a).endVertex();
            vc = renderer.vertex();
            vc.vertex(x1, y0).uv(u1, v0).color(r, g, b, a).endVertex();
            vc = renderer.vertex();
            vc.vertex(x1, y1).uv(u1, v1).color(r, g, b, a).endVertex();
            vc = renderer.vertex();
            vc.vertex(x0, y1).uv(u0, v1).color(r, g, b, a).endVertex();
        }

        @Override
        public void endText() {
            flushPendingVertices();
        }

        private void flushPendingVertices() {
            if (vtxCursorStart < 0) return;
            int vtxNow = currentStagingVertexCount();
            int vtxCount = vtxNow - vtxCursorStart;
            if (vtxCount > 0) {
                ctx.recordTextCommand(batchRenderState, batchTextureId, batchPxRange,
                                      vtxCursorStart, vtxCount);
            }
            vtxCursorStart = -1;
        }

        private int currentStagingVertexCount() {
            CgBatchRenderer renderer = ctx.batchSlots.renderer(ctx.currentBatchSlot);
            return renderer.staging().vertexCount();
        }
    }
}
