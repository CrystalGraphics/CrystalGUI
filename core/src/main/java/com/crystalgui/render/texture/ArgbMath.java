package com.crystalgui.render.texture;

/** Small shared packed-ARGB channel math, used wherever a drawable needs to fold a fixed tint
 * together with the paint context's ambient tint. */
public final class ArgbMath {

    private ArgbMath() {
    }

    /** Channel-wise ARGB multiply (each 0-255 channel independently, normalized, then repacked). */
    public static int multiply(int a, int b) {
        int a1 = mulChannel((a >>> 24) & 0xFF, (b >>> 24) & 0xFF);
        int r1 = mulChannel((a >>> 16) & 0xFF, (b >>> 16) & 0xFF);
        int g1 = mulChannel((a >>> 8) & 0xFF, (b >>> 8) & 0xFF);
        int b1 = mulChannel(a & 0xFF, b & 0xFF);
        return (a1 << 24) | (r1 << 16) | (g1 << 8) | b1;
    }

    private static int mulChannel(int a, int b) {
        return (a * b) / 255;
    }

    /** Channel-wise ARGB lerp (each 0-255 channel interpolated independently, then repacked). */
    public static int lerp(int from, int to, float t) {
        int a = lerpChannel((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
        int r = lerpChannel((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, t);
        int g = lerpChannel((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    // ── HSV ─────────────────────────────────────────────────────────────────
    //
    // The same conversion `color.glsl` does on the GPU, needed here because a colour picker's
    // WIDGET has to reason in HSV: the ring's angle is a hue and the square's axes are saturation
    // and value, so a drag has to travel back to RGB before anything can store it. Duplicating the
    // formula is unavoidable — the shader cannot answer a question about a pointer position — but
    // the two must agree, or the handle sits somewhere other than the colour under it.

    /**
     * ARGB to {@code [hue, saturation, value]}, each 0..1.
     *
     * <p>Hue is <b>undefined for a grey</b> (saturation 0) and returns 0 there. A picker must not
     * write that back blindly: dragging value down to black would otherwise reset the ring to red,
     * losing the hue the user chose. Keep the hue in widget state, not derived from the colour.</p>
     */
    public static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float hue = 0f;
        if (delta > 0f) {
            if (max == r) hue = ((g - b) / delta) / 6f;
            else if (max == g) hue = (2f + (b - r) / delta) / 6f;
            else hue = (4f + (r - g) / delta) / 6f;
            if (hue < 0f) hue += 1f;
        }
        return new float[] { hue, max <= 0f ? 0f : delta / max, max };
    }

    /** {@code [hue, saturation, value]} (each 0..1) plus an alpha 0..255, back to ARGB. */
    public static int fromHsv(float hue, float saturation, float value, int alpha) {
        float h = (hue - (float) Math.floor(hue)) * 6f;
        float s = clamp01(saturation);
        float v = clamp01(value);

        int sector = (int) h;
        float f = h - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));

        float r, g, b;
        switch (sector % 6) {
            case 0:  r = v; g = t; b = p; break;
            case 1:  r = q; g = v; b = p; break;
            case 2:  r = p; g = v; b = t; break;
            case 3:  r = p; g = q; b = v; break;
            case 4:  r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return ((alpha & 0xFF) << 24)
                | (Math.round(r * 255f) << 16)
                | (Math.round(g * 255f) << 8)
                | Math.round(b * 255f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
