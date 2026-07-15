package com.crystalgui.style.property.visual.texture;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.texture.CgUiDrawable;
import lombok.experimental.Accessors;

@Accessors(chain = true)
public class TextureProperty extends StyleProperty<CgUiDrawable> {
    public TextureProperty(String name, CgUiDrawable initialValue) {
        super(name, CgUiDrawable.class, initialValue, TextureValue::new);
        setAllowTransition(true);
//        setInterpolator(this::interpolate);
    }

//    private CgUiDrawable interpolate(CgUiDrawable from, CgUiDrawable to, float lerp) {
//        return from.interpolate(to, lerp);
//    }
}
