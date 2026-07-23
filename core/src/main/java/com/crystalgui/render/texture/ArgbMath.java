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
}
