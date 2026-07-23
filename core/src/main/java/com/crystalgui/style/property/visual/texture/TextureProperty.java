package com.crystalgui.style.property.visual.texture;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiRoundedRect;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class TextureProperty extends StyleProperty<CgUiDrawable> {
    public TextureProperty(String name, CgUiDrawable initialValue) {
        super(name, CgUiDrawable.class, initialValue, TextureValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    /**
     * Two {@link CgUiRoundedRect}s are the same procedural shape family — their parameters (corner
     * radii, border) morph as a true lerp, a single draw, no compositing. Every other drawable
     * pairing falls back to {@link CgUiCrossFade}'s draw-both-and-blend-opacity approach, since
     * there's no shared parameter space to lerp between (e.g. two unrelated 9-slice sprites, or a
     * color and a texture).
     *
     * <p>{@code morph(...)} additionally requires both sides to be {@link CgUiRoundedRect#isPureFill()}
     * — a retargeted (interrupted-and-restarted) transition's {@code fromValue} can itself be a
     * mixed-fill snapshot produced by an earlier {@code morph()} call (see
     * {@code TransitionEngine.tryStart}, which snapshots {@code currentValue(now)} as the new
     * {@code fromValue}). Morphing from that snapshot again would read only its "A" fill, silently
     * discarding the "B" fill and blend progress. Falling back to {@link CgUiCrossFade} instead is
     * always safe here — it only ever calls {@code draw()} polymorphically, never reads into either
     * side's fields, so it can wrap a mixed-fill snapshot without losing any information.</p>
     */
    private CgUiDrawable interpolate(CgUiDrawable from, CgUiDrawable to, float lerp) {
        if (from instanceof CgUiRoundedRect fromRect && to instanceof CgUiRoundedRect toRect
                && fromRect.isPureFill() && toRect.isPureFill()) {
            return CgUiRoundedRect.morph(fromRect, toRect, lerp);
        }
        return new CgUiCrossFade(from, to, lerp);
    }
}
