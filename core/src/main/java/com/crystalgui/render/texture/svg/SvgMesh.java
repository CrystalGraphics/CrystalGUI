package com.crystalgui.render.texture.svg;

import org.jetbrains.annotations.Nullable;

/**
 * Tessellated geometry with a colour attached to every triangle — the output of {@link SvgTessellator}.
 *
 * <h3>The lyon seam</h3>
 *
 * <p>Ported in shape from <b>lyon</b> (MIT/Apache-2.0), whose tessellators consume a flattened path and a
 * fill rule and emit vertices plus per-vertex attributes through a builder, knowing nothing about the
 * format the path came from. <b>Nothing in this class mentions SVG</b>, and that is the point: it would
 * serve a font glyph or a hand-built polygon unchanged, and the SVG-shaped decisions all sit on the far
 * side of {@link SvgScene}.</p>
 *
 * <h3>Why a triangle soup rather than vertices plus indices</h3>
 *
 * <p>lyon emits indexed geometry because its consumers upload a vertex buffer and an index buffer. This
 * engine's consumer is {@code CgVectorRenderer}, which draws each triangle as an <b>instance</b> carrying
 * its own three points — so an index buffer would be dereferenced on the CPU and thrown away every frame.
 * Six floats per triangle is the format the draw path actually wants.</p>
 *
 * <p>That is a deliberate trade and worth stating: indexed geometry drawn as real triangles would be
 * watertight by construction and cost no per-triangle SDF, which is the direction to move if fills ever
 * become the bottleneck. It would also give up the analytic antialiasing the SDF provides on the
 * silhouette, which at 16px is the whole reason icons look like icons — lyon pays for that with a
 * separately tessellated antialiasing fringe, and that is the piece that would have to be ported with it.</p>
 *
 * @param triangles six floats per triangle: {@code x0,y0, x1,y1, x2,y2}
 * @param colour0   one ARGB per triangle — the start of the ramp; null when the whole mesh is one
 *                  colour, which the draw op already carries and which would otherwise be a full array
 *                  of the same int per fill
 * @param colour1   the end of the ramp, or null when every triangle is flat
 * @param axes      four floats per triangle — {@code originX, originY, dirX, dirY}, with the reciprocal
 *                  length folded into {@code dir} so the fragment stage needs a dot product and a clamp
 *                  rather than a normalise. Null when every triangle is flat
 * @param upper     whether triangle {@code i} is the upper half of its trapezoid; see
 *                  {@link SvgTriangulator.Fill}. Drives which way a seam is nudged
 * @param opaque    every colour is fully opaque, so the fill may safely overlap its own seams
 */
record SvgMesh(float[] triangles, @Nullable int[] colour0, @Nullable int[] colour1,
               @Nullable float[] axes, boolean[] upper, boolean opaque) {

    static final SvgMesh EMPTY =
            new SvgMesh(new float[0], null, null, null, new boolean[0], true);

    boolean isEmpty() {
        return triangles.length == 0;
    }

    int triangleCount() {
        return triangles.length / 6;
    }

    /** Whether every colour is fully opaque — a ramp that fades out is not, even if it starts solid. */
    static boolean allOpaque(int[]... sets) {
        for (int[] set : sets) {
            if (set == null) continue;
            for (int argb : set) {
                if ((argb >>> 24) != 0xFF) return false;
            }
        }
        return true;
    }
}
