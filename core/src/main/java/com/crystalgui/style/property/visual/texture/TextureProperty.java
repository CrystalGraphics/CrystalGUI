package com.crystalgui.style.property.visual.texture;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.render.texture.CgUiCrossFade;
import com.crystalgui.render.texture.CgUiDrawable;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class TextureProperty extends StyleProperty<CgUiDrawable> {
    public TextureProperty(String name, CgUiDrawable initialValue) {
        super(name, CgUiDrawable.class, initialValue, TextureValue::new);
        setAllowTransition(true);
        setInterpolator(this::interpolate);
    }

    /**
     * {@code background}/{@code overlay} only ever hold a {@code CgUiQuad} or {@code CgUiSprite}
     * now (rounding/border is a separate wrapping layer applied in {@code UIElement.paintSelf}, not
     * a background value type) — there's no shared parameter space to lerp between two arbitrary
     * drawables, so this always draw-both-and-blend-opacity via {@link CgUiCrossFade}.
     */
    private CgUiDrawable interpolate(CgUiDrawable from, CgUiDrawable to, float lerp) {
        return new CgUiCrossFade(from, to, lerp);
    }
}
