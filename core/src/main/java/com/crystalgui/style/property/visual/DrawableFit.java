package com.crystalgui.style.property.visual;

import com.crystalgui.style.property.StyleValue;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * How a drawable is scaled into its layer box — CSS {@code object-fit}.
 *
 * <p>{@code object-fit} rather than {@code background-size} is the honest analogue: this engine
 * fits <em>one</em> drawable into a box, it does not tile a repeating background.</p>
 *
 * <p>{@link #CONTAIN}/{@link #COVER}/{@link #NONE} need the drawable's natural size
 * ({@code CgUiImagePainter.intrinsicWidth}/{@code intrinsicHeight}). Images with no natural size
 * — solid colours, SDF rounded rects — report {@code -1}, and every mode then degrades to
 * {@link #FILL} rather than drawing nothing.</p>
 */
public enum DrawableFit {
    /** Stretch to exactly fill the box, ignoring aspect ratio. The default, and the engine's
     * behaviour before this property existed. */
    FILL,
    /** Scale uniformly to the largest size that fits entirely inside the box. */
    CONTAIN,
    /** Scale uniformly to the smallest size that fully covers the box (overflowing it). */
    COVER,
    /** Draw at natural size, unscaled. */
    NONE;

    /** {@code mask-size} as CSS writes it. */
    public static String writeMaskSize(DrawableFit fit) {
        return switch (fit) {
            case FILL -> "100% 100%";
            case NONE -> "auto";
            case CONTAIN -> "contain";
            case COVER -> "cover";
        };
    }

    /**
     * CSS {@code mask-size}, the keyword forms: {@code auto} (natural size), {@code contain},
     * {@code cover}, and {@code 100% 100%} to stretch. {@code overlay-size}, ours, keeps
     * {@code object-fit}'s names.
     */
    public static final class MaskSize extends StyleValue<DrawableFit> {

        public MaskSize(String raw) {
            super(raw);
        }

        @Override
        protected @Nullable DrawableFit doCompute(String raw) {
            String t = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            return switch (t) {
                case "auto", "auto auto" -> NONE;
                case "contain" -> CONTAIN;
                case "cover" -> COVER;
                case "100% 100%" -> FILL;
                default -> null;
            };
        }
    }
}
