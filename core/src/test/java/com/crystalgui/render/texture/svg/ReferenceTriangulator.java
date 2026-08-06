package com.crystalgui.render.texture.svg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The trapezoid decomposition exactly as it stood before {@link SvgTriangulator} grew an active edge
 * table, kept verbatim so the rewrite can be held to producing identical meshes.
 *
 * <p>Copied rather than referenced: the point is to compare against code that is <em>not</em> maintained
 * alongside the thing under test, so a shared helper drifting would not silently make both agree.</p>
 */
final class ReferenceTriangulator {

    private ReferenceTriangulator() {
    }

    private static final int MINIMUM_POINTS = 3;
    private static final float EPSILON = 1e-5f;
    private static final int MAX_SLICES = 64;
    private static final int MAX_CELLS = 3000;
    private static final int MAX_EXTRA_BANDS = 192;
    private static final int MAX_INTERSECTION_EDGES = 1200;

    static SvgTriangulator.Fill fill(List<List<float[]>> contours, boolean evenOdd,
                                     float stepX, float stepY, float[] extraCuts) {
        List<float[]> edges = new ArrayList<>();
        for (List<float[]> contour : contours) {
            if (contour.size() < MINIMUM_POINTS) continue;
            for (int i = 0; i < contour.size(); i++) {
                float[] a = contour.get(i);
                float[] b = contour.get((i + 1) % contour.size());
                if (Math.abs(a[1] - b[1]) < EPSILON) continue;
                edges.add(new float[]{a[0], a[1], b[0], b[1]});
            }
        }
        SvgTriangulator.Fill empty =
                new SvgTriangulator.Fill(new float[0], new int[0], new boolean[0]);
        if (edges.isEmpty()) return empty;

        float[] bands = bandBoundaries(edges, stepY, extraCuts);
        if (bands.length < 2) return empty;

        List<float[]> triangles = new ArrayList<>();
        List<Integer> slices = new ArrayList<>();
        List<Boolean> uppers = new ArrayList<>();
        int sliceIndex = 0;
        int sliceAllowance = Math.max(1, MAX_CELLS / Math.max(1, bands.length - 1));
        float[] crossX = new float[edges.size()];
        int[] crossDir = new int[edges.size()];
        int[] crossEdge = new int[edges.size()];

        for (int band = 0; band + 1 < bands.length; band++) {
            float top = bands[band];
            float bottom = bands[band + 1];
            if (bottom - top < EPSILON) continue;
            float middle = (top + bottom) * 0.5f;

            int found = 0;
            for (int e = 0; e < edges.size(); e++) {
                float[] edge = edges.get(e);
                float lo = Math.min(edge[1], edge[3]);
                float hi = Math.max(edge[1], edge[3]);
                if (middle < lo || middle >= hi) continue;
                crossX[found] = xAt(edge, middle);
                crossDir[found] = edge[3] > edge[1] ? 1 : -1;
                crossEdge[found] = e;
                found++;
            }
            if (found < 2) continue;
            sortByX(crossX, crossDir, crossEdge, found);

            int winding = 0;
            for (int i = 0; i + 1 < found; i++) {
                winding += evenOdd ? 1 : crossDir[i];
                boolean inside = evenOdd ? (winding & 1) == 1 : winding != 0;
                if (!inside) continue;

                float[] left = edges.get(crossEdge[i]);
                float[] right = edges.get(crossEdge[i + 1]);
                float lt = xAt(left, top), lb = xAt(left, bottom);
                float rt = xAt(right, top), rb = xAt(right, bottom);
                if (Math.abs(rt - lt) < EPSILON && Math.abs(rb - lb) < EPSILON) continue;

                float widest = Math.max(Math.abs(rt - lt), Math.abs(rb - lb));
                int count = stepX > 0f
                        ? Math.max(1, Math.min(Math.min(MAX_SLICES, sliceAllowance),
                                (int) Math.ceil(widest / stepX)))
                        : 1;
                for (int s = 0; s < count; s++) {
                    float a = (float) s / count, b = (float) (s + 1) / count;
                    float at = lt + (rt - lt) * a, ab = lb + (rb - lb) * a;
                    float bt = lt + (rt - lt) * b, bb = lb + (rb - lb) * b;
                    add(triangles, slices, uppers, sliceIndex, true, at, top, bt, top, bb, bottom);
                    add(triangles, slices, uppers, sliceIndex, false, at, top, bb, bottom, ab, bottom);
                    sliceIndex++;
                }
            }
        }

        float[] packed = new float[triangles.size() * 6];
        int[] tags = new int[triangles.size()];
        boolean[] halves = new boolean[triangles.size()];
        for (int i = 0; i < triangles.size(); i++) {
            System.arraycopy(triangles.get(i), 0, packed, i * 6, 6);
            tags[i] = slices.get(i);
            halves[i] = uppers.get(i);
        }
        return new SvgTriangulator.Fill(packed, tags, halves);
    }

    private static void add(List<float[]> out, List<Integer> slices, List<Boolean> uppers,
                            int slice, boolean upper,
                            float x0, float y0, float x1, float y1, float x2, float y2) {
        out.add(new float[]{x0, y0, x1, y1, x2, y2});
        slices.add(slice);
        uppers.add(upper);
    }

    private static float[] bandBoundaries(List<float[]> edges, float stepY, float[] extraCuts) {
        int extra = 0;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        if (stepY > 0f) {
            for (float[] edge : edges) {
                minY = Math.min(minY, Math.min(edge[1], edge[3]));
                maxY = Math.max(maxY, Math.max(edge[1], edge[3]));
            }
            extra = Math.min(MAX_EXTRA_BANDS, (int) Math.ceil((maxY - minY) / stepY));
        }

        float[] crossings = selfIntersections(edges);
        int explicit = extraCuts == null ? 0 : extraCuts.length;
        float[] all = new float[edges.size() * 2 + Math.max(0, extra) + crossings.length + explicit];
        for (int i = 0; i < edges.size(); i++) {
            all[i * 2] = edges.get(i)[1];
            all[i * 2 + 1] = edges.get(i)[3];
        }
        int at = edges.size() * 2;
        for (int i = 0; i < extra; i++) all[at++] = minY + (maxY - minY) * (i + 0.5f) / extra;
        for (float y : crossings) all[at++] = y;
        for (int i = 0; i < explicit; i++) all[at++] = extraCuts[i];

        Arrays.sort(all);
        float[] unique = new float[all.length];
        int count = 0;
        for (float value : all) {
            if (count == 0 || value - unique[count - 1] > EPSILON) unique[count++] = value;
        }
        return Arrays.copyOf(unique, count);
    }

    private static float[] selfIntersections(List<float[]> edges) {
        int count = edges.size();
        if (count < 2 || count > MAX_INTERSECTION_EDGES) return new float[0];

        float[] found = new float[64];
        int size = 0;
        for (int i = 0; i < count; i++) {
            float[] a = edges.get(i);
            float ax = a[2] - a[0], ay = a[3] - a[1];
            for (int j = i + 1; j < count; j++) {
                float[] b = edges.get(j);
                float bx = b[2] - b[0], by = b[3] - b[1];
                float denominator = ax * by - ay * bx;
                if (Math.abs(denominator) < 1e-9f) continue;

                float dx = b[0] - a[0], dy = b[1] - a[1];
                float t = (dx * by - dy * bx) / denominator;
                float u = (dx * ay - dy * ax) / denominator;
                if (t <= EPSILON || t >= 1f - EPSILON || u <= EPSILON || u >= 1f - EPSILON) continue;

                if (size == found.length) found = Arrays.copyOf(found, size * 2);
                found[size++] = a[1] + ay * t;
            }
        }
        return Arrays.copyOf(found, size);
    }

    private static float xAt(float[] edge, float y) {
        float dy = edge[3] - edge[1];
        if (Math.abs(dy) < 1e-9f) return edge[0];
        return edge[0] + (edge[2] - edge[0]) * (y - edge[1]) / dy;
    }

    private static void sortByX(float[] x, int[] dir, int[] edge, int count) {
        for (int i = 1; i < count; i++) {
            float keyX = x[i];
            int keyDir = dir[i], keyEdge = edge[i];
            int j = i - 1;
            while (j >= 0 && x[j] > keyX) {
                x[j + 1] = x[j];
                dir[j + 1] = dir[j];
                edge[j + 1] = edge[j];
                j--;
            }
            x[j + 1] = keyX;
            dir[j + 1] = keyDir;
            edge[j + 1] = keyEdge;
        }
    }
}
