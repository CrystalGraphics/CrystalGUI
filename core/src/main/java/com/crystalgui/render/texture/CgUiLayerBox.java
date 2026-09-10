package com.crystalgui.render.texture;

import com.crystalgui.style.property.visual.DrawableAlign;
import com.crystalgui.style.property.visual.DrawableFit;

/**
 * The final rect a drawable layer paints into, after applying {@code *-fit} and {@code *-position}
 * to the layer's origin box.
 *
 * <p>Standalone rather than inlined into {@code UIElement} because {@code background} needs exactly
 * this same math once its coupling to the SDF rounded-rect shape is resolved — today the background
 * rect doubles as {@code CgUiRect}'s {@code _BoxSize} and as the basis for percentage
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
        float[] out = new float[4];
        resolveInto(drawable, boxX, boxY, boxWidth, boxHeight, fit, align, out);
        return new CgUiLayerBox(out[0], out[1], out[2], out[3]);
    }

    /**
     * {@link #resolve} writing {@code x, y, width, height} into {@code out} instead of answering a
     * record — for the painter, which lays out a mask and an overlay per element per frame.
     */
    public static void resolveInto(CgUiDrawable drawable,
                                   float boxX, float boxY, float boxWidth, float boxHeight,
                                   DrawableFit fit, DrawableAlign align, float[] out) {
        if (fit == DrawableFit.FILL) {
            fill(out, boxX, boxY, boxWidth, boxHeight);
            return;
        }

        float naturalWidth = drawable.intrinsicWidth();
        float naturalHeight = drawable.intrinsicHeight();
        if (naturalWidth <= 0f || naturalHeight <= 0f) {
            fill(out, boxX, boxY, boxWidth, boxHeight);
            return;
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
        fill(out, boxX + align.xFactor() * (boxWidth - width),
                boxY + align.yFactor() * (boxHeight - height), width, height);
    }

    private static void fill(float[] out, float x, float y, float width, float height) {
        out[0] = x;
        out[1] = y;
        out[2] = width;
        out[3] = height;
    }
}
