package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.api.state.CgCullState;
import io.github.somehussar.crystalgraphics.api.state.CgDepthState;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Global UI runtime singleton. All UIContainers share this.
 *
 * <p>Owns all long-lived, shared UI rendering resources that must not be
 * recreated per-container: the UI shader, the prebuilt solid-fill draw
 * state, and the shared text renderer + default font family.</p>
 *
 * <p>Call {@link #initialize(CgTextRenderer, CgFontFamily)} once at
 * application startup (harness init, Minecraft pre-init, etc.) before
 * constructing any {@code UIContainer}. After that, all UI code obtains
 * the runtime via {@link #get()}.</p>
 */
public final class CgUiRuntime {

    private static final String UI_VERT_PATH = "/shader/default.vert";
    private static final String UI_FRAG_PATH = "/shader/default.frag";
    private static final CgShader UI_SHADER = CgShaderFactory.load(UI_VERT_PATH, UI_FRAG_PATH, CgVertexFormat.POS2_UV2_COL4UB);


    private static CgUiRuntime INSTANCE;

    // ── Owned globals ──

 
    /** Shared stateless text rendering façade. Null if text is unavailable. */
    @Getter @Nullable
    private final CgTextRenderer textRenderer;

    /** Default font family for UI labels. Null if text is unavailable. */
    @Getter @Nullable
    private final CgFontFamily defaultFontFamily;
    
    /**
     * Prebuilt solid-fill render state for UI rectangles.
     * Uses the shared UI shader with alpha blend, no depth, no cull.
     */
    private CgRenderState solidFill;


    private CgUiRuntime(@Nullable CgTextRenderer textRenderer,
                        @Nullable CgFontFamily defaultFontFamily) {
        this.textRenderer = textRenderer;
        this.defaultFontFamily = defaultFontFamily;
    }

    // ── Initialization ──

    /**
     * One-time initialization with full text support.
     *
     * <p>Loads the shared UI shader, builds the solid-fill draw state,
     * and stores the provided text renderer and default font family.
     * Must be called exactly once before any {@code UIContainer} is created.</p>
     *
     * @param textRenderer      the shared text renderer (created via {@code CgTextRenderer.create()})
     * @param defaultFontFamily the default font family for UI text labels
     * @throws IllegalArgumentException if either argument is null
     * @throws IllegalStateException    if already initialized
     */
    public static synchronized void initialize(CgTextRenderer textRenderer,
                                               CgFontFamily defaultFontFamily) {
        if (INSTANCE != null) return;
        if (textRenderer == null) throw new IllegalArgumentException("textRenderer must not be null");
        if (defaultFontFamily == null) throw new IllegalArgumentException("defaultFontFamily must not be null");

        INSTANCE = new CgUiRuntime(textRenderer, defaultFontFamily);
    }

    /**
     * One-time initialization without text support (shapes only).
     *
     * <p>Loads the shared UI shader and builds the solid-fill draw state,
     * but leaves text services unavailable. UI elements that require text
     * will silently skip rendering.</p>
     *
     * @throws IllegalStateException if already initialized
     */
    public static synchronized void initialize() {
        if (INSTANCE != null) return;
        INSTANCE = new CgUiRuntime(null, null);
    }

    /**
     * Returns the global runtime instance.
     *
     * @return the singleton runtime, never null
     * @throws IllegalStateException if {@link #initialize} has not been called
     */
    public static CgUiRuntime get() {
        return INSTANCE;
    }

    /**
     * Returns whether the runtime has been initialized.
     *
     * @return true if {@link #initialize} has been called
     */
    public static boolean isInitialized() {
        return INSTANCE != null;
    }



    // ── Convenience accessors ──

    /**
     * Returns whether text rendering services (text renderer + default font)
     * are available.
     *
     * @return true if both text renderer and default font family are non-null
     */
    public boolean hasTextServices() {
        return textRenderer != null && defaultFontFamily != null;
    }

    // ── Internal ──

    public CgRenderState solidFill() {
        if(solidFill != null) return solidFill;
        CgRenderState uiRenderState = CgRenderState.builder(UI_SHADER)
                .blend(CgBlendState.ALPHA)
                .depth(CgDepthState.NONE)
                .cull(CgCullState.NONE)
                .build();
        return solidFill = uiRenderState;
    }
}
