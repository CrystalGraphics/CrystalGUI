package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexConsumer;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.state.CgScissorRect;
import io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer;
import io.github.somehussar.crystalgraphics.text.render.CgTextEmissionTarget;
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

    // Context-owned text services (optional until configured by the host)
    private CgTextRenderer textRenderer;
    private CgFontFamily defaultFontFamily;
    private CgTextRenderContext textRenderContext;
    private long textFrame;

    // Current recording state
    private CgUiDrawState currentDrawState;
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
        currentDrawState = null;
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
        currentDrawState = null;
        recording = false;
        for (int s = 0; s < batchSlots.size(); s++) {
            batchSlots.renderer(s).end();
        }
    }

    // ── Low-level recording API ─────────────────────────────────────────

    /**
     * Sets the active draw state and vertex format for subsequent vertex recording.
     * Must be called before {@link #vertex()} or {@link #reserveQuads(int)}.
     */
    public void setDrawState(CgUiDrawState state, CgVertexFormat format) {
        if (!recording) throw new IllegalStateException("Not recording");
        if (state == null) throw new IllegalArgumentException("state must not be null");
        currentDrawState = state;
        currentBatchSlot = batchSlots.slotIndexFor(format);
    }

    public CgVertexConsumer vertex() {
        if (!recording) throw new IllegalStateException("Not recording");
        if (currentDrawState == null) throw new IllegalStateException("No draw state set — call setDrawState() first");
        return batchSlots.renderer(currentBatchSlot).vertex();
    }

    /**
     * Pre-allocates staging buffer space for the given number of quads.
     * Call before emitting quad vertices to avoid mid-burst growth.
     */
    public void reserveQuads(int count) {
        if (!recording) throw new IllegalStateException("Not recording");
        if (currentDrawState == null) throw new IllegalStateException("No draw state set");
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
        drawList.record(currentDrawState, currentBatchSlot, sx, sy, sw, sh, vtxStart, vtxCount);
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
     * Emits a positioned quad (4 vertices) with the given draw state, UVs, and color,
     * and records the draw command. This is the general-purpose quad helper.
     *
     * @param state the prebuilt draw state
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
    public void quad(CgUiDrawState state, float x0, float y0, float x1, float y1,
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
     * Fills a rectangle with the given draw state and color.
     */
    public void fillRect(CgUiDrawState state, float x, float y, float w, float h, int rgba) {
        quad(state, x, y, x + w, y + h, 0, 0, 1, 1, rgba);
    }

    /**
     * Draws a textured image quad.
     */
    public void drawImage(CgUiDrawState state, float x, float y, float w, float h,
                          float u0, float v0, float u1, float v1, int rgba) {
        quad(state, x, y, x + w, y + h, u0, v0, u1, v1, rgba);
    }

    /**
     * Fills a rectangle with separate float color components (0.0-1.0).
     */
    public void fillRect(CgUiDrawState state, float x, float y, float w, float h,
                         float r, float g, float b, float a) {
        fillRect(state, x, y, w, h, packRgba(r, g, b, a));
    }

    /**
     * Draws the border (outline) of a rectangle as four thin quads.
     *
     * @param state     the prebuilt draw state
     * @param x         left edge
     * @param y         top edge
     * @param w         width of the rectangle
     * @param h         height of the rectangle
     * @param thickness border thickness in pixels
     * @param rgba      packed RGBA color (0xRRGGBBAA)
     */
    public void strokeRect(CgUiDrawState state, float x, float y, float w, float h,
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
    public void hLine(CgUiDrawState state, float x, float y, float length, int rgba) {
        fillRect(state, x, y, length, 1, rgba);
    }

    /**
     * Draws a vertical line as a 1px-wide filled rectangle.
     */
    public void vLine(CgUiDrawState state, float x, float y, float length, int rgba) {
        fillRect(state, x, y, 1, length, rgba);
    }

    /**
     * Draws text into the draw list via a {@link CgTextEmissionTarget} adapter.
     *
     * <p>The caller provides a callback that receives the emission target and uses
     * the CrystalGraphics text renderer's target-based API to emit glyphs. All
     * emitted quads are recorded into the draw list in painter's order.</p>
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * paintContext.drawText(target -> {
     *     textRenderer.drawInternalTarget(target, layout, font, x, y, color, frame, renderCtx, poseStack);
     * });
     * }</pre>
     *
     * @param emitter a callback that receives the text emission target
     */
    public void drawText(TextEmitter emitter) {
        if (!recording) throw new IllegalStateException("Not recording");
        emitter.emit(textEmissionTarget());
    }

    /**
     * Convenience helper that emits text through the generic draw-list text target.
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

        textRenderer.drawInternalTarget(textEmissionTarget(), layout, family, x, y, rgba, frame, context, pose);
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

        textRenderer.drawInternalTarget(textEmissionTarget(), text, family, x, y, rgba, frame, context, pose);
    }

    /**
     * Direct convenience text helper using the context-owned text services.
     */
    public void drawText(String text, float x, float y, int rgba) {
        ensureTextConfigured();
        if (text == null || text.isEmpty()) return;
        textRenderer.drawInternalTarget(textEmissionTarget(), text, defaultFontFamily, x, y, rgba, textFrame, textRenderContext, new PoseStack());
    }

    /**
     * Direct convenience text helper using the context-owned renderer/context and an explicit font family.
     */
    public void drawText(CgFontFamily family, String text, float x, float y, int rgba) {
        ensureTextConfigured();
        if (family == null) throw new IllegalArgumentException("family must not be null");
        if (text == null || text.isEmpty()) return;
        textRenderer.drawInternalTarget(textEmissionTarget(), text, family, x, y, rgba, textFrame, textRenderContext, new PoseStack());
    }

    /**
     * Functional interface for text emission into the draw list.
     */
    public interface TextEmitter {
        void emit(CgTextEmissionTarget target);
    }

    /**
     * Returns a {@link CgTextEmissionTarget} adapter that the CrystalGraphics text
     * renderer can emit into. Quads emitted through this target are recorded into
     * the draw list in painter's order.
     */
    public CgTextEmissionTarget textEmissionTarget() {
        if (!recording) throw new IllegalStateException("Not recording");
        return new DrawListEmissionTarget(this);
    }

    // ── Access ──────────────────────────────────────────────────────────

    public CgUiDrawList getDrawList() { return drawList; }
    public CgUiBatchSlots getBatchSlots() { return batchSlots; }
    public ScissorStack getScissorStack() { return scissorStack; }
    public CgUiRuntime getRuntime() { return runtime; }
    public boolean isRecording() { return recording; }

    public boolean hasTextServices() {
        return textRenderer != null && defaultFontFamily != null && textRenderContext != null;
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

    // ── Inner text emission target adapter ──────────────────────────────

    /**
     * Bridges CrystalGraphics' generic {@link CgTextEmissionTarget} to the
     * draw-list recording path. Converts render-state/texture/pxRange transitions
     * into {@link CgUiDrawState} instances for the draw list.
     */
    private static final class DrawListEmissionTarget implements CgTextEmissionTarget {
        private final CgUiPaintContext ctx;
        DrawListEmissionTarget(CgUiPaintContext ctx) { this.ctx = ctx; }

        @Override
        public void switchBatch(CgRenderState renderState, int textureId, float pxRange) {
            CgTextureBinding texOverride = textureId >= 0 ? CgTextureBinding.texture2D(textureId) : null;
            CgUiDrawState state = new CgUiDrawState(renderState, texOverride, pxRange);
            ctx.setDrawState(state, CgVertexFormat.POS2_UV2_COL4UB);
        }

        @Override
        public CgVertexConsumer vertexConsumer() {
            return ctx.vertex();
        }

        @Override
        public void reserveQuads(int count) {
            ctx.reserveQuads(count);
        }

        @Override
        public void recordQuad(int vtxStart, int vtxCount) {
            ctx.recordCommand(vtxStart, vtxCount);
        }

        @Override
        public int currentVertexCount() {
            CgBatchRenderer renderer = ctx.batchSlots.renderer(ctx.currentBatchSlot);
            return renderer.staging().vertexCount();
        }
    }
}
