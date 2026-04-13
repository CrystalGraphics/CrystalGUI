package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;

import javax.annotation.Nullable;

/**
 * Command-local resolved draw state for one UI draw run.
 *
 * <p>Wraps a reusable {@link CgRenderState} plus any command-local overrides
 * that are not appropriate to bake into the generic render state. Instances
 * must be prebuilt and cached — never constructed in the recording hot path.</p>
 *
 * <h3>Merge identity</h3>
 * <p>Hot-path merge detection in {@link CgUiDrawList} uses <strong>reference
 * identity</strong> ({@code ==}) on prebuilt cached states, not {@code equals()}.
 * This is intentional and fast.</p>
 *
 * <h3>Ownership</h3>
 * <p>Instances are owned by shared UI state libraries, text atlas caches,
 * or element style presets — not by element draw methods.</p>
 */
public final class CgUiDrawState {

    private final CgRenderState renderState;
    @Nullable
    private final CgTextureBinding textureOverride;
    private final float textPxRange;

    public CgUiDrawState(CgRenderState renderState, @Nullable CgTextureBinding textureOverride, float textPxRange) {
        if (renderState == null) throw new IllegalArgumentException("renderState must not be null");
        this.renderState = renderState;
        this.textureOverride = textureOverride;
        this.textPxRange = textPxRange;
    }

    public CgUiDrawState(CgRenderState renderState) {
        this(renderState, null, Float.NaN);
    }

    public CgUiDrawState(CgRenderState renderState, CgTextureBinding textureOverride) {
        this(renderState, textureOverride, Float.NaN);
    }

    public CgRenderState getRenderState() { return renderState; }
    @Nullable
    public CgTextureBinding getTextureOverride() { return textureOverride; }
    public float getTextPxRange() { return textPxRange; }
    public boolean hasTextureOverride() { return textureOverride != null; }
    public boolean hasTextPxRange() { return !Float.isNaN(textPxRange); }
}
