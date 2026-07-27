package com.crystalgui.style.property.visual;

/**
 * How a drawable is scaled into its layer box — CSS {@code object-fit}.
 *
 * <p>{@code object-fit} rather than {@code background-size} is the honest analogue: this engine
 * fits <em>one</em> drawable into a box, it does not tile a repeating background.</p>
 *
 * <p>{@link #CONTAIN}/{@link #COVER}/{@link #NONE} need the drawable's natural size
 * ({@code CgUiDrawable.intrinsicWidth()}/{@code intrinsicHeight()}). Drawables with no natural size
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
    NONE
}
