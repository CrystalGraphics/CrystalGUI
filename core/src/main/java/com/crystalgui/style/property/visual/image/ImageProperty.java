package com.crystalgui.style.property.visual.image;

import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.style.property.StyleProperty;

/**
 * A property holding a {@link CgUiImage}: {@code background}, {@code overlay}, {@code mask}, {@code outline}.
 * Transitions interpolate the values ({@link CgUiImage#interpolate}); writing is the value's own CSS.
 */
public class ImageProperty extends StyleProperty<CgUiImage> {

    public ImageProperty(String name) {
        super(name, CgUiImage.class, CgUiImage.NONE, ImageValue::new);
        setAllowTransition(true);
        setInterpolator(CgUiImage::interpolate);
    }

    @Override
    public String write(CgUiImage value) {
        return value.css();
    }
}
