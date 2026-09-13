package com.crystalgui.style.property.visual.shadow;

import com.crystalgui.style.property.visual.color.StyleColor;

/**
 * One computed CSS shadow: offsets, blur radius and spread in pixels, a colour, and {@code inset}.
 *
 * <pre>{@code
 * Shadow s = Shadow.of(2f, 3f, 4f, 0f, 0xFF000000, false);     // black 2px 3px 4px
 * Shadow c = Shadow.currentColor(0f, 0f, 8f, 0f, false);        // 0 0 8px, in the element's colour
 * int argb = c.color().resolve(elementColor);                   // paint time, never earlier
 * }</pre>
 *
 * <p>Easy to get wrong: {@code blur} is the CSS blur <b>radius</b>. What a renderer blurs with is
 * {@link #sigma()}, half of it.</p>
 */
public record Shadow(float x, float y, float blur, float spread, StyleColor color, boolean inset) {

    /** A shadow in a literal colour, straight ARGB. */
    public static Shadow of(float x, float y, float blur, float spread, int argb, boolean inset) {
        return new Shadow(x, y, blur, spread, StyleColor.of(argb), inset);
    }

    /** A shadow in the painting element's {@code color}, which is what an omitted colour means. */
    public static Shadow currentColor(float x, float y, float blur, float spread, boolean inset) {
        return new Shadow(x, y, blur, spread, StyleColor.CURRENT, inset);
    }

    /**
     * What a shorter shadow list is padded with: {@code transparent 0 0 0 0}, keeping the counterpart's
     * {@code inset} so the pair can still interpolate. Blink's {@code InterpolableShadow::RawCloneAndZero}.
     */
    public static Shadow neutralLike(Shadow counterpart) {
        return new Shadow(0f, 0f, 0f, 0f, StyleColor.TRANSPARENT, counterpart.inset());
    }

    /**
     * The Gaussian's standard deviation: exactly half the blur radius, as CSS Backgrounds 3 defines a
     * shadow's blur. Blink's {@code ShadowData::BlurAsSigma}, which is also where Blink converts: a renderer
     * is handed a sigma, never a radius.
     */
    public float sigma() {
        return blur * 0.5f;
    }

    /** Pairwise linear, colour included; the caller has already checked {@code inset} matches. */
    static Shadow lerp(Shadow from, Shadow to, float t) {
        return new Shadow(
                lerp(from.x, to.x, t),
                lerp(from.y, to.y, t),
                // Blink resolves the blur through Length::ValueRange::kNonNegative; the spread is not.
                Math.max(0f, lerp(from.blur, to.blur, t)),
                lerp(from.spread, to.spread, t),
                StyleColor.lerp(from.color, to.color, t),
                from.inset);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
