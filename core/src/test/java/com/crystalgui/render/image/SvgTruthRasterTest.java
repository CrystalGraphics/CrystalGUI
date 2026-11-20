package com.crystalgui.render.image;

import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.render.ui.svg.CgSvgOps;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;

import com.crystalgraphics.util.io.CgIO;

/**
 * The truth an icon raster is judged against: a document's own ops (its tessellation, not a browser's reading of the
 * file) at the tier drawn at that size, rasterised on the CPU at 256 samples a pixel, fills by their cells and strokes as the union of their segments'
 * capsules, composited in op order. Writes straight-alpha PNGs that {@code steps/UI7/truth.py} scores a capture
 * against.
 *
 * <pre>{@code
 * ./gradlew :core:test --tests "com.crystalgui.render.image.SvgTruthRasterTest" \
 *     -Dcrystalgui.svgTruth.icon=crystalgui:ui/icons/nodes/java/package_dark.svg -Dcrystalgui.svgTruth.sizes=32,28,24,20,16
 * }</pre>
 */
public class SvgTruthRasterTest {

    private static final int SAMPLES = 16;

    @Test
    public void writeReferences() throws IOException {
        String icon = System.getProperty("crystalgui.svgTruth.icon", "crystalgui:ui/icons/nodes/java/package_dark.svg");
        String sizes = System.getProperty("crystalgui.svgTruth.sizes", "32,28,24,20,16");
        File out = new File(System.getProperty("crystalgui.svgTruth.out", "build/svg-truth"));
        out.mkdirs();
        String source = CgIO.loadSource(icon);
        for (String s : sizes.split(",")) {
            int device = Integer.parseInt(s.trim());
            CgSvg document = CgSvg.parse(source).tier(device);
            ImageIO.write(raster(document, device), "png", new File(out, "truth_" + device + ".png"));
        }
    }

    /** The document fitted into {@code device} square pixels, centred, as an icon is fitted to its box. */
    static BufferedImage raster(CgSvg document, int device) {
        float scale = Math.min(device / document.width(), device / document.height());
        float left = (device - document.width() * scale) * 0.5f, top = (device - document.height() * scale) * 0.5f;
        List<CgSvgOps.Op> ops = document.ops();
        BufferedImage image = new BufferedImage(device, device, BufferedImage.TYPE_INT_ARGB);
        float[] px = new float[4];
        float[] sample = new float[4];
        for (int y = 0; y < device; y++) {
            for (int x = 0; x < device; x++) {
                px[0] = px[1] = px[2] = px[3] = 0f;
                for (int sy = 0; sy < SAMPLES; sy++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        float u = (x + (sx + 0.5f) / SAMPLES - left) / scale;
                        float v = (y + (sy + 0.5f) / SAMPLES - top) / scale;
                        sample[0] = sample[1] = sample[2] = sample[3] = 0f;
                        for (CgSvgOps.Op op : ops) over(sample, op, u, v);
                        for (int k = 0; k < 4; k++) px[k] += sample[k];
                    }
                }
                // A PNG is straight alpha: the premultiplied average divided back out
                float n = SAMPLES * SAMPLES, alpha = px[3] / n, k = px[3] > 0f ? 1f / px[3] : 0f;
                int a = Math.round(alpha * 255f), r = Math.round(px[0] * k * 255f);
                int g = Math.round(px[1] * k * 255f), b = Math.round(px[2] * k * 255f);
                image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    /** Composites {@code op} at document point (u, v) over the premultiplied sample. */
    private static void over(float[] sample, CgSvgOps.Op op, float u, float v) {
        int argb = -1;
        if (op.fill()) {
            float[] cells = op.data();
            for (int i = 0, n = cells.length / 8; i < n; i++) {
                if (inside(cells, i * 8, u, v)) {
                    argb = colour(op, i, u, v);
                    break;
                }
            }
            if (argb == -1) return;
        } else {
            float[] seg = op.data();
            boolean hit = false;
            for (int i = 0, n = seg.length / 4; i < n && !hit; i++) {
                int caps = op.segmentCaps() != null ? op.segmentCaps()[i] : 0;
                hit = inCapsule(seg, i * 4, op.halfWidth(), caps & 3, (caps >> 2) & 3, u, v);
            }
            if (!hit) return;
            argb = op.argb();
        }
        if (op.currentColor() && op.colours() == null) argb = 0xFFFFFFFF;
        float a = (argb >>> 24) / 255f;
        float k = 1f - a;
        sample[0] = ((argb >> 16) & 255) / 255f * a + sample[0] * k;
        sample[1] = ((argb >> 8) & 255) / 255f * a + sample[1] * k;
        sample[2] = (argb & 255) / 255f * a + sample[2] * k;
        sample[3] = a + sample[3] * k;
    }

    /** A fill cell's colour at (u, v): its own, or its ramp along its axis. */
    private static int colour(CgSvgOps.Op op, int cell, float u, float v) {
        if (op.colours() == null) return op.argb();
        int c0 = op.colours()[cell];
        if (op.coloursEnd() == null || op.gradients() == null) return c0;
        int c1 = op.coloursEnd()[cell];
        float[] g = op.gradients();
        float t = Math.max(0f, Math.min(1f, (u - g[cell * 4]) * g[cell * 4 + 2] + (v - g[cell * 4 + 1]) * g[cell * 4 + 3]));
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float a = (c0 >>> shift) & 255, b = (c1 >>> shift) & 255;
            out |= Math.round(a + (b - a) * t) << shift;
        }
        return out;
    }

    /** Whether (u, v) lies in the convex quad of cell corners, whatever its winding; collapsed edges constrain nothing. */
    private static boolean inside(float[] c, int o, float u, float v) {
        int sign = 0;
        for (int e = 0; e < 4; e++) {
            float ax = c[o + e * 2], ay = c[o + e * 2 + 1];
            float bx = c[o + ((e + 1) & 3) * 2], by = c[o + ((e + 1) & 3) * 2 + 1];
            if (ax == bx && ay == by) continue;
            float cross = (bx - ax) * (v - ay) - (by - ay) * (u - ax);
            int s = cross > 0f ? 1 : cross < 0f ? -1 : 0;
            if (s == 0) continue;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    /** Whether (u, v) lies within {@code h} of the segment, its ends butt (0), round (1) or square (2). */
    private static boolean inCapsule(float[] s, int o, float h, int startCap, int endCap, float u, float v) {
        float ax = s[o], ay = s[o + 1], bx = s[o + 2], by = s[o + 3];
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float t = len2 > 0f ? ((u - ax) * dx + (v - ay) * dy) / len2 : 0f;
        float len = (float) Math.sqrt(len2);
        float along = t * len;
        float startReach = startCap == 2 ? h : 0f, endReach = endCap == 2 ? h : 0f;
        if (along < -startReach) return startCap == 1 && dist2(u, v, ax, ay) <= h * h;
        if (along > len + endReach) return endCap == 1 && dist2(u, v, bx, by) <= h * h;
        float px = ax + dx * t, py = ay + dy * t;
        boolean across = len2 > 0f ? dist2(u, v, px, py) <= h * h : false;
        if (across) return true;
        return startCap == 1 && dist2(u, v, ax, ay) <= h * h || endCap == 1 && dist2(u, v, bx, by) <= h * h;
    }

    private static float dist2(float u, float v, float x, float y) {
        return (u - x) * (u - x) + (v - y) * (v - y);
    }
}
