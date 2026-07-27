package com.crystalgui.render.texture;

import com.crystalgui.style.property.visual.DrawableAlign;
import com.crystalgui.style.property.visual.DrawableFit;

/**
 * The final rect a drawable layer paints into, after applying {@code *-fit} and {@code *-position}
 * to the layer's origin box.
 *
 * <p>Standalone rather than inlined into {@code UIElement} because {@code background} needs exactly
 * this same math once its coupling to the SDF rounded-rect shape is resolved — today the background
 * rect doubles as {@code CgUiRoundedRect}'s {@code _BoxSize} and as the basis for percentage
 * {@code border-radius}, so it can't be shrunk independently without redefining what
 * {@code border-radius} means.</p>
 */
public record CgUiLayerBox(float x, float y, float width, float height) {

    /**
     * Fits {@code drawable} into the given box.
     *
     * <p>Every mode falls back to {@link DrawableFit#FILL} when the drawable reports no natural size
     * ({@code -1}) — a solid colour or SDF shape has nothing to preserve the aspect of, and filling
     * is a far better failure mode than drawing nothing.</p>
     */
    public static CgUiLayerBox resolve(CgUiDrawable drawable,
                                       float boxX, float boxY, float boxWidth, float boxHeight,
                                       DrawableFit fit, DrawableAlign align) {
        if (fit == DrawableFit.FILL) {
            return new CgUiLayerBox(boxX, boxY, boxWidth, boxHeight);
        }

        float naturalWidth = drawable.intrinsicWidth();
        float naturalHeight = drawable.intrinsicHeight();
        if (naturalWidth <= 0f || naturalHeight <= 0f) {
            return new CgUiLayerBox(boxX, boxY, boxWidth, boxHeight);
        }

        float width;
        float height;
        switch (fit) {
            case NONE -> {
                width = naturalWidth;
                height = naturalHeight;
            }
            case CONTAIN -> {
                float scale = Math.min(boxWidth / naturalWidth, boxHeight / naturalHeight);
                width = naturalWidth * scale;
                height = naturalHeight * scale;
            }
            case COVER -> {
                float scale = Math.max(boxWidth / naturalWidth, boxHeight / naturalHeight);
                width = naturalWidth * scale;
                height = naturalHeight * scale;
            }
            default -> {
                width = boxWidth;
                height = boxHeight;
            }
        }

        // Leftover can be negative (COVER), in which case the factors pick which side overflows.
        float x = boxX + align.xFactor() * (boxWidth - width);
        float y = boxY + align.yFactor() * (boxHeight - height);
        return new CgUiLayerBox(x, y, width, height);
    }
}
