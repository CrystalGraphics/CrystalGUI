package com.crystalgui.style.property.layout.dimension;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.StyleValue;
import dev.vfyjxf.taffy.style.TaffyDimension;

import javax.annotation.Nullable;

/**
 * Parses CSS TaffyDimension syntax.
 *
 * Supported syntax:
 * <pre>
 * auto                  // Auto sizing
 * 100px or 100          // Absolute length
 * 50%                   // Percentage
 * 1.5em                 // Multiple of the element's own font size — see FontRelative
 * </pre>
 */
public class DimensionValue extends StyleValue<TaffyDimension> implements FontRelative<TaffyDimension> {

    /** The {@code em} multiple, or {@code NaN} for every other kind of value. @see FontRelative */
    private final float emMultiple;

    public DimensionValue(String rawValue) {
        super(rawValue);
        this.emMultiple = FontRelative.multipleIn(rawValue);
    }

    @Override
    public boolean isFontRelative() {
        return !Float.isNaN(emMultiple);
    }

    @Override
    public TaffyDimension resolveAgainst(float fontSize) {
        return TaffyDimension.length(emMultiple * fontSize);
    }

    @Override
    protected @Nullable TaffyDimension doCompute(String rawValue) {
        return parse(rawValue);
    }

    public static TaffyDimension parse(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return TaffyDimension.AUTO;
        }

        String trimmed = rawValue.trim().toLowerCase();

        try {
            // Check for auto keyword
            switch (trimmed) {
                case "auto" -> {
                    return TaffyDimension.AUTO;
                }
                case "fit-content" -> {
                    return TaffyDimension.FIT_CONTENT;
                }
                case "stretch" -> {
                    return TaffyDimension.STRETCH;
                }
                case "max-content" -> {
                    return TaffyDimension.MAX_CONTENT;
                }
                case "min-content" -> {
                    return TaffyDimension.MIN_CONTENT;
                }
            }

            CssLength length = CssLength.parse(trimmed);
            if (length == null) return null;
            return switch (length.unit()) {
                case PX -> TaffyDimension.length(length.value());
                case PERCENT -> TaffyDimension.percent(length.fraction());
                // Against the reference size; the per-element answer is resolveAgainst, from StyleEngine.
                case EM -> TaffyDimension.length(length.value() * FontRelative.REFERENCE_FONT_SIZE);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Serialization to CSS String ====================

    public static String toString(TaffyDimension value) {
        if (value == null || value.isAuto()) {
            return "auto";
        }

        if (value.isLength()) {
            return value.getValue() + "px";
        }

        if (value.isPercent()) {
            // Convert 0.0-1.0 to 0-100
            return (value.getValue() * 100) + "%";
        }

        return "auto";
    }
}
