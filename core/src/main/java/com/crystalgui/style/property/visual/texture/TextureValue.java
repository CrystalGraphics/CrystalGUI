package com.crystalgui.style.property.visual.texture;

import com.crystalgui.style.property.StyleValue;
import com.crystalgui.texture.CgUiDrawable;
import com.crystalgui.texture.CgUiSprite;

import javax.annotation.Nullable;

public class TextureValue extends StyleValue<CgUiDrawable> {

    public TextureValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected @Nullable CgUiDrawable doCompute(String rawValue) {
        return new CgUiSprite().setTexture(rawValue);
    }

}
