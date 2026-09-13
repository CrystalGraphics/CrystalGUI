package com.crystalgui.style.property.visual.color;

/**
 * A computed CSS {@code <color>} that may be {@code currentcolor}, including part-way through a transition
 * between the two.
 *
 * <pre>{@code
 * StyleColor black = StyleColor.of(0xFF000000);
 * StyleColor half = StyleColor.lerp(black, StyleColor.CURRENT, 0.5f);   // half black, half currentcolor
 * int argb = half.resolve(elementColor);                                 // paint time, never earlier
 * }</pre>
 *
 * <p>Blink's {@code StyleColor} and {@code InterpolableColor} in one value: premultiplied channels on a
 * 0-255 scale, plus a weight of {@code currentcolor}. CSS keeps {@code currentcolor} unresolved in the
 * computed value, so an inherited colour takes the colour of the element that paints it; and a transition
 * between {@code black} and {@code currentcolor} is a mix of the two, which no single ARGB can hold. The
 * resolved colour is {@code (red, green, blue, alpha) + currentColor * premultiplied(element colour)},
 * clamped when painted. Channels are doubles so a transition lands on a whole level where CSS says it
 * does.</p>
 */
public record StyleColor(double red, double green, double blue, double alpha, double currentColor) {

    /** {@code currentcolor}. */
    public static final StyleColor CURRENT = new StyleColor(0.0, 0.0, 0.0, 0.0, 1.0);

    /** {@code transparent}: transparent black, and no share of {@code currentcolor}. */
    public static final StyleColor TRANSPARENT = new StyleColor(0.0, 0.0, 0.0, 0.0, 0.0);

    /**
     * A transition's progress arrives as a float, which is up to 1.2e-8 off the decimal progress CSS
     * resolves at; on a level exactly half way that flips the rounding. A hundred-thousandth of a level
     * absorbs it and moves nothing else.
     */
    private static final double PROGRESS_SLOP = 1.0e-5;

    /** A literal colour, straight ARGB. */
    public static StyleColor of(int argb) {
        double a = (argb >>> 24) & 0xFF;
        return new StyleColor(
                ((argb >>> 16) & 0xFF) * a / 255.0,
                ((argb >>> 8) & 0xFF) * a / 255.0,
                (argb & 0xFF) * a / 255.0,
                a, 0.0);
    }

    /** The colour to paint, straight ARGB, with {@code currentcolor} resolved against {@code currentArgb}. */
    public int resolve(int currentArgb) {
        double ca = (currentArgb >>> 24) & 0xFF;
        double r = red + currentColor * ((currentArgb >>> 16) & 0xFF) * ca / 255.0;
        double g = green + currentColor * ((currentArgb >>> 8) & 0xFF) * ca / 255.0;
        double b = blue + currentColor * (currentArgb & 0xFF) * ca / 255.0;
        return pack(r, g, b, alpha + currentColor * ca);
    }

    /** Whether this is exactly {@code currentcolor}. */
    public boolean isCurrentColor() {
        return currentColor == 1.0 && red == 0.0 && green == 0.0 && blue == 0.0 && alpha == 0.0;
    }

    /** The literal part, straight ARGB; {@code currentcolor}'s share is left out. */
    public int literalArgb() {
        return pack(red, green, blue, alpha);
    }

    /** CSS Color 4 interpolation: premultiplied, with {@code currentcolor}'s weight carried alongside. */
    public static StyleColor lerp(StyleColor from, StyleColor to, float t) {
        return new StyleColor(
                lerp(from.red, to.red, t),
                lerp(from.green, to.green, t),
                lerp(from.blue, to.blue, t),
                lerp(from.alpha, to.alpha, t),
                lerp(from.currentColor, to.currentColor, t));
    }

    /**
     * Alpha is clamped BEFORE dividing, as CSS resolves an extrapolated colour: a transition run past its
     * end brightens rather than holding still.
     */
    private static int pack(double r, double g, double b, double a) {
        double alpha = Math.max(0.0, Math.min(255.0, a));
        if (alpha <= 0.0) return 0;
        return ((int) Math.round(alpha + PROGRESS_SLOP) << 24)
                | (channel(r * 255.0 / alpha) << 16)
                | (channel(g * 255.0 / alpha) << 8)
                | channel(b * 255.0 / alpha);
    }

    private static int channel(double level) {
        return (int) Math.max(0, Math.min(255, Math.round(level + PROGRESS_SLOP)));
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
