package com.crystalgui.style.property.layout.length;

import com.crystalgui.style.CssLength;
import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.StyleValue;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;

import javax.annotation.Nullable;

public class LPAValue extends StyleValue<LengthPercentageAuto>
        implements FontRelative<LengthPercentageAuto> {

    /** The {@code em} multiple, or {@code NaN} for every other kind of value. @see FontRelative */
    private final float emMultiple;

    public LPAValue(String rawValue) {
        super(rawValue);
        this.emMultiple = FontRelative.multipleIn(rawValue);
    }

    @Override
    public boolean isFontRelative() {
        return !Float.isNaN(emMultiple);
    }

    @Override
    public LengthPercentageAuto resolveAgainst(float fontSize) {
        return LengthPercentageAuto.length(emMultiple * fontSize);
    }

    @Override
    protected @Nullable LengthPercentageAuto doCompute(String rawValue) {
        return parse(rawValue);
    }

    public static LengthPercentageAuto parse(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return LengthPercentageAuto.auto();
        }

        String trimmed = rawValue.trim().toLowerCase();

        try {
            // Check for keywords
            return switch (trimmed) {
                case "auto" -> LengthPercentageAuto.auto();
                case "min-content" -> LengthPercentageAuto.minContent();
                case "max-content" -> LengthPercentageAuto.maxContent();
                case "fit-content" -> LengthPercentageAuto.fitContent();
                case "stretch" -> LengthPercentageAuto.stretch();
                default -> {
                    CssLength length = CssLength.parse(trimmed);
                    if (length == null) yield null;
                    yield switch (length.unit()) {
                        case PX -> LengthPercentageAuto.length(length.value());
                        case PERCENT -> LengthPercentageAuto.percent(length.fraction());
                        // Against the reference size; the per-element answer is resolveAgainst, from StyleEngine.
                        case EM -> LengthPercentageAuto.length(length.value() * FontRelative.REFERENCE_FONT_SIZE);
                    };
                }
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Serialization to CSS String ====================

    public static String toString(LengthPercentageAuto value) {
        if (value == null) {
            return "auto";
        }

        return switch (value.getType()) {
            case AUTO -> "auto";
            case LENGTH -> value.getValue() + "px";
            case PERCENT -> (value.getValue() * 100) + "%";  // Convert 0.0-1.0 to 0-100
            case CALC -> "calc(...)";  // calc expressions can't be fully serialized
            case MIN_CONTENT -> "min-content";
            case MAX_CONTENT -> "max-content";
            case FIT_CONTENT -> "fit-content";
            case STRETCH -> "stretch";
        };
    }
}