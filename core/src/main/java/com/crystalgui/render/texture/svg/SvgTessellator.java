package com.crystalgui.render.texture.svg;

import com.crystalgraphics.util.profiling.CgProfiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a resolved fill into triangles with colours — the tessellation half, shaped after <b>lyon</b>
 * (MIT/Apache-2.0).
 *
 * <p>Takes rings, a fill rule and a paint; returns an {@link SvgMesh}. It never sees a tag, a style, an
 * element transform or a bounding box, because {@link SvgResolver} has already answered all of those. The
 * only reason it knows about {@link SvgScene.Paint} at all is that <b>paint decides where the mesh is
 * cut</b> — a gradient needs bands that a flat colour does not — which is exactly the coupling lyon has
 * between its tessellator and its fill options.</p>
 *
 * <h3>Everything happens in one space</h3>
 *
 * <p>Contours arrive absolute, and the gradient arrives with a transform mapping its own space into that
 * one. Rather than tessellating in gradient space and mapping the triangles back — which round-trips every
 * point through a matrix and its inverse, and puts float drift straight into the seams — the <b>ramp</b>
 * is mapped into absolute space instead and the geometry never moves. A linear gradient is an affine
 * scalar function of position, so it survives that intact; see {@link SvgTransform#mapCovector} for why
 * the direction is the inverse transpose and not the transformed axis.</p>
 */
final class SvgTessellator {

    private SvgTessellator() {
    }

    static SvgMesh tessellate(List<SvgPath.Polyline> contours, boolean evenOdd, SvgScene.Paint paint) {
        try (CgProfiler.Scope ignored = CgProfiler.scope("svg.tessellate")) {
            return tessellateImpl(contours, evenOdd, paint);
        }
    }

    private static SvgMesh tessellateImpl(List<SvgPath.Polyline> contours, boolean evenOdd,
                                          SvgScene.Paint paint) {
        List<List<float[]>> rings = SvgGeometry.ringsOf(contours);
        if (paint instanceof SvgScene.Gradient gradient) {
            return gradient.gradient().radial()
                    ? radial(rings, evenOdd, gradient)
                    : linear(rings, evenOdd, gradient);
        }
        return flat(rings, evenOdd, ((SvgScene.Solid) paint).argb());
    }

    /** No gradient: one colour, and the mesh is cut only where the geometry demands. */
    private static SvgMesh flat(List<List<float[]>> rings, boolean evenOdd, int argb) {
        SvgTriangulator.Fill mesh = SvgTriangulator.fill(rings, evenOdd, 0f, 0f);
        if (mesh.triangles().length == 0) return SvgMesh.EMPTY;
        return new SvgMesh(mesh.triangles(), null, null, null, mesh.upper(), (argb >>> 24) == 0xFF);
    }

    /**
     * A linear gradient, cut into strips that lie ALONG the ramp rather than across it.
     *
     * <h3>Why the frame is rotated</h3>
     *
     * <p>The scanline cuts in {@code y}. For a gradient running any other direction, a band therefore
     * spans a <em>range</em> of ramp positions, so no single colour is right for it — the flat colour is
     * correct only down the band's middle and wrong at both edges, and the error reverses at the next
     * band. That reads as visible strips with a smooth fade inside each one, which is exactly what it is:
     * the fade is the interpolation doing its job, and the strip edges are the bands failing at theirs.</p>
     *
     * <p>Rotating the shape so the gradient points along {@code +y} makes every band an <b>iso-line
     * strip</b> — constant ramp position from end to end — so one two-colour lerp per band is not an
     * approximation at all. The triangles are rotated back before they are stored, so nothing downstream
     * knows this happened.</p>
     *
     * <h3>One cut per stop, and nothing else</h3>
     *
     * <p>The fragment stage interpolates {@code color0 -> color1} across each triangle, so a band no
     * longer has to be small enough that a flat colour passes for a ramp — it only has to stay inside ONE
     * stop interval, because a two-colour lerp is exact there and wrong across a stop. That takes the band
     * count from "however many the colour delta demands" to "however many stops the gradient has", which
     * for real artwork is three or four.</p>
     */
    private static SvgMesh linear(List<List<float[]>> rings, boolean evenOdd, SvgScene.Gradient paint) {
        SvgGradient gradient = paint.gradient();
        float dx = gradient.x2() - gradient.x1(), dy = gradient.y2() - gradient.y1();
        float lengthSq = dx * dx + dy * dy;
        if (lengthSq < 1e-9f) return SvgMesh.EMPTY;

        // The ramp as an affine functional in the gradient's own space: t(p) = dot(p - origin, g).
        // Mapping THAT into absolute space is what keeps the ramp exact under any transform.
        float[] g = paint.transform().mapCovector(dx / lengthSq, dy / lengthSq);
        float gLength = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1]);
        if (gLength < 1e-12f) return SvgMesh.EMPTY;
        float ux = g[0] / gLength, uy = g[1] / gLength;
        float originX = paint.transform().applyX(gradient.x1(), gradient.y1());
        float originY = paint.transform().applyY(gradient.x1(), gradient.y1());

        // R maps the ramp onto +y, so v IS the gradient parameter (unnormalised) and a band cut in v is an
        // iso-line. The inverse is the transpose, so mapping back costs the same two multiply-adds.
        List<List<float[]>> rotated = new ArrayList<>(rings.size());
        for (List<float[]> ring : rings) {
            List<float[]> out = new ArrayList<>(ring.size());
            for (float[] p : ring) out.add(new float[]{uy * p[0] - ux * p[1], ux * p[0] + uy * p[1]});
            rotated.add(out);
        }
        float originV = ux * originX + uy * originY;

        float[] offsets = gradient.offsets();
        float[] cuts = new float[offsets.length];
        for (int i = 0; i < offsets.length; i++) cuts[i] = originV + offsets[i] / gLength;

        SvgTriangulator.Fill mesh = SvgTriangulator.fill(rotated, evenOdd, 0f, 0f, cuts);
        float[] triangles = mesh.triangles();
        if (triangles.length == 0) return SvgMesh.EMPTY;

        int count = triangles.length / 6;
        int[] colour0 = new int[count];
        int[] colour1 = new int[count];
        float[] axes = new float[count * 4];

        for (int i = 0; i < count; i++) {
            int at = i * 6;
            float vMin = Math.min(triangles[at + 1], Math.min(triangles[at + 3], triangles[at + 5]));
            float vMax = Math.max(triangles[at + 1], Math.max(triangles[at + 3], triangles[at + 5]));
            if (vMax - vMin < 1e-6f) vMax = vMin + 1e-6f;

            colour0[i] = SvgColor.withOpacity(
                    gradient.colourAt(gradient.spreadPublic((vMin - originV) * gLength)), paint.alpha());
            colour1[i] = SvgColor.withOpacity(
                    gradient.colourAt(gradient.spreadPublic((vMax - originV) * gLength)), paint.alpha());

            // The band's own axis, in absolute space: it starts where the band starts and reaches 1 where
            // the band ends, so the fragment stage's clamp(dot(p - origin, dir)) spans exactly this
            // triangle. Deriving it from the band's extent rather than from the whole ramp is what lets
            // one lerp be exact per band.
            float span = vMax - vMin;
            float[] a = unrotate(0f, vMin, ux, uy);
            axes[i * 4] = a[0];
            axes[i * 4 + 1] = a[1];
            axes[i * 4 + 2] = ux / span;
            axes[i * 4 + 3] = uy / span;
        }

        for (int i = 0; i < triangles.length; i += 2) {
            float[] p = unrotate(triangles[i], triangles[i + 1], ux, uy);
            triangles[i] = p[0];
            triangles[i + 1] = p[1];
        }
        return new SvgMesh(triangles, colour0, colour1, axes, mesh.upper(),
                SvgMesh.allOpaque(colour0, colour1));
    }

    /**
     * A radial gradient keeps the subdivided path: its iso-lines are circles, so no rotation makes a band
     * constant, and its colour still has to be approximated per cell.
     */
    private static SvgMesh radial(List<List<float[]>> rings, boolean evenOdd, SvgScene.Gradient paint) {
        SvgGradient gradient = paint.gradient();
        // The cell size is a property of the ramp, which is stated in the gradient's own space; the box it
        // is measured against therefore has to be too. Sampling later uses the same space for the same
        // reason -- both go through the paint's transform exactly once, at the end.
        float[] box = boundsIn(rings, paint.transform());
        float[] spacing = gradient.sampleSpacing(box);
        SvgTriangulator.Fill mesh = SvgTriangulator.fill(rings, evenOdd, spacing[0], spacing[1]);
        float[] triangles = mesh.triangles();
        if (triangles.length == 0) return SvgMesh.EMPTY;

        int[] colours = gradientColours(mesh, gradient, box, paint.alpha(), paint.transform());
        return new SvgMesh(triangles, colours, null, null, mesh.upper(), SvgMesh.allOpaque(colours));
    }

    /**
     * One colour per <b>slice</b> rather than per triangle.
     *
     * <p>The two halves of a slice are split along a diagonal, so their centroids sit on opposite sides of
     * it and pick up different colours. That is invisible on its own, and stops being invisible the moment
     * the draw nudges each triangle to settle its seams: each half then claims a strip of the other along
     * the shared diagonal, and whichever is submitted second wins. The result is a diagonal hatch of the
     * wrong colour across the whole shape, strongest exactly where the gradient is steepest. Giving a
     * slice one colour makes that overlap land on itself.</p>
     */
    private static int[] gradientColours(SvgTriangulator.Fill mesh, SvgGradient gradient, float[] box,
                                         float alpha, SvgTransform toGradient) {
        int[] slice = mesh.slice();
        float[] triangles = mesh.triangles();
        int count = slice.length;
        if (count == 0) return new int[0];

        int sliceCount = slice[count - 1] + 1;
        float[] sumX = new float[sliceCount];
        float[] sumY = new float[sliceCount];
        int[] samples = new int[sliceCount];
        for (int i = 0; i < count; i++) {
            int at = i * 6;
            for (int v = 0; v < 6; v += 2) {
                sumX[slice[i]] += triangles[at + v];
                sumY[slice[i]] += triangles[at + v + 1];
            }
            samples[slice[i]] += 3;
        }

        SvgTransform inverse = inverseOf(toGradient);
        int[] resolved = new int[sliceCount];
        for (int s = 0; s < sliceCount; s++) {
            if (samples[s] == 0) continue;
            float cx = sumX[s] / samples[s], cy = sumY[s] / samples[s];
            resolved[s] = SvgColor.withOpacity(
                    gradient.colourAt(inverse.applyX(cx, cy), inverse.applyY(cx, cy), box), alpha);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = resolved[slice[i]];
        return out;
    }

    /** The rings' bounding box expressed in the gradient's own space. */
    private static float[] boundsIn(List<List<float[]>> rings, SvgTransform toGradient) {
        SvgTransform inverse = inverseOf(toGradient);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (List<float[]> ring : rings) {
            for (float[] p : ring) {
                float x = inverse.applyX(p[0], p[1]), y = inverse.applyY(p[0], p[1]);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX > maxX) return new float[]{0f, 0f, 1f, 1f};
        return new float[]{minX, minY, Math.max(1e-6f, maxX - minX), Math.max(1e-6f, maxY - minY)};
    }

    /** Affine inverse; identity for a degenerate matrix, which has no shape to sample anyway. */
    private static SvgTransform inverseOf(SvgTransform t) {
        float det = t.a() * t.d() - t.b() * t.c();
        if (Math.abs(det) < 1e-12f) return SvgTransform.IDENTITY;
        float ia = t.d() / det, ib = -t.b() / det, ic = -t.c() / det, id = t.a() / det;
        return new SvgTransform(ia, ib, ic, id,
                -(ia * t.e() + ic * t.f()), -(ib * t.e() + id * t.f()));
    }

    /** Rotated ramp frame back to the space the contours are already in. */
    private static float[] unrotate(float u, float v, float ux, float uy) {
        return new float[]{uy * u + ux * v, -ux * u + uy * v};
    }
}
