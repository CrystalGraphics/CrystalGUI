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
}
