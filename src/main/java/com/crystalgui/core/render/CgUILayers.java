package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.state.CgTextureState;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.gl.render.CgBufferSource;
import io.github.somehussar.crystalgraphics.gl.render.CgDynamicTextureRenderLayer;
import io.github.somehussar.crystalgraphics.gl.render.CgLayer;
import io.github.somehussar.crystalgraphics.gl.render.CgRenderLayer;

/**
 * Typed render layer keys and factory helpers for CrystalGUI's standard UI layers.
 *
 * <p>This class holds the {@link CgLayer.Key} constants that identify each
 * UI-specific render layer, plus small factory methods to create the corresponding
 * layer instances. It does <strong>not</strong> own or redefine vertex formats —
 * those come from CrystalGraphics's {@link CgVertexFormat}.</p>
 *
 * <h3>Layer ordering (painter's order)</h3>
 * <ol>
 *   <li>{@link #SOLID} — flat colour fills (backgrounds, dividers)</li>
 *   <li>{@link #PANEL} — textured panel / nine-slice content</li>
 *   <li>{@link #ROUNDED} — SDF-rounded-rect shader layer</li>
 *   <li>{@link #OVERLAY} — additive glow / highlight effects</li>
 *   <li>{@link #TEXT} — MSDF text (dynamic texture, shared with Cg text subsystem)</li>
 * </ol>
 *
 * <h3>Buffer-source assembly</h3>
 * <p>Callers assemble a {@link CgBufferSource} explicitly using these keys and factories:</p>
 * <pre>{@code
 * CgBufferSource uiSource = CgBufferSource.builder()
 *     .layer(CgUiLayers.SOLID,   CgUiLayers.solid(solidShader))
 *     .layer(CgUiLayers.PANEL,   CgUiLayers.panel(panelShader, atlas))
 *     .layer(CgUiLayers.ROUNDED, CgUiLayers.rounded(roundedShader))
 *     .layer(CgUiLayers.OVERLAY, CgUiLayers.overlay(overlayShader))
 *     .layer(CgUiLayers.TEXT,    CgTextLayers.msdf(msdfShader))
 *     .build();
 * }</pre>
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>Keys are global constants; mutable layer instances are <strong>not</strong> singletons.</li>
 *   <li>Registration order in the builder is painter's order — do not auto-sort layers.</li>
 * </ul>
 *
 * @see CgLayer.Key
 * @see CgRenderLayer
 * @see CgDynamicTextureRenderLayer
 * @see CgBufferSource
 */
public final class CgUILayers {

    // ── Typed layer keys (global constants) ─────────────────────────────

    /** Flat-colour fill layer (backgrounds, dividers). No texture. */
    public static final CgLayer.Key<CgRenderLayer> SOLID = new CgLayer.Key<>("cgui:solid");

    /** Textured panel / nine-slice layer. Fixed texture atlas. */
    public static final CgLayer.Key<CgRenderLayer> PANEL = new CgLayer.Key<>("cgui:panel");

    /** SDF-rounded-rect shader layer. No texture. */
    public static final CgLayer.Key<CgRenderLayer> ROUNDED = new CgLayer.Key<>("cgui:rounded");

    /** Additive glow / highlight overlay layer. */
    public static final CgLayer.Key<CgRenderLayer> OVERLAY = new CgLayer.Key<>("cgui:overlay");

    /** MSDF text layer (dynamic texture, shares Cg text subsystem key space). */
    public static final CgLayer.Key<CgDynamicTextureRenderLayer> TEXT = new CgLayer.Key<>("cgui:text");

    // ── Layer factory helpers ───────────────────────────────────────────

    /**
     * Creates the solid-colour fill layer.
     *
     * <p>Uses alpha blending with no texture. Initial CPU staging sized for 2048 quads.</p>
     *
     * @param shader the compiled flat-colour shader
     * @return a new {@link CgRenderLayer} for solid fills
     */
    public static CgRenderLayer solid(CgShader shader) {
        return CgRenderLayer.create(
            "cgui:solid",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ALPHA)
                .texture(CgTextureState.none())
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            2048
        );
    }

    /**
     * Creates the textured panel / nine-slice layer.
     *
     * <p>Uses alpha blending with a fixed texture atlas. Initial CPU staging sized
     * for 4096 quads (nine-slice panels emit many quads).</p>
     *
     * @param shader the compiled textured-panel shader
     * @param atlas  the texture binding for the UI atlas
     * @return a new {@link CgRenderLayer} for textured panels
     */
    public static CgRenderLayer panel(CgShader shader, CgTextureBinding atlas) {
        return CgRenderLayer.create(
            "cgui:panel",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ALPHA)
                .texture(CgTextureState.fixed(atlas, 0, "uTexture"))
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            4096
        );
    }

    /**
     * Creates the SDF-rounded-rect shader layer.
     *
     * <p>Uses alpha blending with no texture. Initial CPU staging sized for 1024 quads.</p>
     *
     * @param shader the compiled rounded-rect SDF shader
     * @return a new {@link CgRenderLayer} for rounded rectangles
     */
    public static CgRenderLayer rounded(CgShader shader) {
        return CgRenderLayer.create(
            "cgui:rounded",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ALPHA)
                .texture(CgTextureState.none())
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            1024
        );
    }

    /**
     * Creates the additive glow / highlight overlay layer.
     *
     * <p>Uses additive blending with no texture. Initial CPU staging sized for 1024 quads.</p>
     *
     * @param shader the compiled overlay shader
     * @return a new {@link CgRenderLayer} for overlay effects
     */
    public static CgRenderLayer overlay(CgShader shader) {
        return CgRenderLayer.create(
            "cgui:overlay",
            CgRenderState.builder(shader)
                .blend(CgBlendState.ADDITIVE)
                .texture(CgTextureState.none())
                .build(),
            CgVertexFormat.POS2_UV2_COL4UB,
            1024
        );
    }

    private CgUILayers() {
        // Utility class — no instantiation
    }
}
