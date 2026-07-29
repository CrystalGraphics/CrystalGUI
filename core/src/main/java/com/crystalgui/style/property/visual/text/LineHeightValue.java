package com.crystalgui.style.property.visual.text;

import com.crystalgui.style.property.StyleValue;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * CSS {@code line-height: normal | <number>}.
 *
 * <p>{@code normal} — the CSS initial value — computes to {@link Float#NaN}, the sentinel meaning "take
 * the line box from the font's own metrics rather than from a multiple of {@code font-size}". Anything
 * else is a unitless multiplier, as in CSS.</p>
 *
 * <p>The sentinel keeps the property a plain {@code Float}, which is why it is worth the small
 * awkwardness: {@code Float} already has a codec, so inline {@code line-height} still crosses the wire,
 * and the existing float interpolator still applies to two numeric endpoints. A dedicated
 * {@code normal | <number>} union type would have neither until a codec was written for it. The
 * {@code NaN}-as-keyword idiom is already established here by {@code AutoFloatProperty}, which uses it
 * for {@code flex} and {@code aspect-rate}.</p>
 *
 * <p><b>Resolving the sentinel needs a font, so it must not happen here.</b> {@link #doCompute} runs
 * during cascade resolution, which a dedicated server performs with no CrystalGraphics on the classpath
 * at all — see {@code TextField.paintOverlay}, the one place that turns {@code NaN} into pixels.</p>
 */
public class LineHeightValue extends StyleValue<Float> {

    /** CSS's {@code normal}: derive the line box from the font. */
    public static final float NORMAL = Float.NaN;

    public LineHeightValue(String rawValue) {
        super(rawValue);
    }

    /** Whether {@code lineHeight} is CSS's {@code normal} rather than a multiplier. */
    public static boolean isNormal(float lineHeight) {
        return Float.isNaN(lineHeight);
    }

    @Override
    protected @Nullable Float doCompute(String rawValue) {
        String trimmed = rawValue.trim();
        if (trimmed.equalsIgnoreCase("normal")) return NORMAL;
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
