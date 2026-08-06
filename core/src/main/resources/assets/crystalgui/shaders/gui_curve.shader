// crystalgui:shaders/gui_curve.shader
//
// Shared material for all CrystalGUI Bézier strokes — the curve counterpart to gui_quad.shader.
// Geometry, colour, width, feather and cap style all come from CgVectorRenderer's per-instance
// SSBO/TBO record via the CG_CURVE_* macros in cg_env.glsl; there is nothing per-draw here except
// _LayerOpacity.
//
// This is a SEPARATE material from crystalgraphics:shaders/curve.shader on purpose, exactly as
// gui_quad.shader is separate from the engine's own quad consumers. Two UI-specific things differ
// and neither belongs in the backend's reference material:
//
//   * DepthTest ALWAYS / DepthWrite OFF — the UI is painter's-order 2D. The engine's curve.shader
//     uses LEQUAL because a stroke in a 3D scene should respect the depth already there.
//   * _LayerOpacity — the whole-draw compositing opacity CgUiPaintContext.withLayerOpacity() drives
//     (one side of a cross-fade, an FBO layer). Distinct from the per-instance colour alpha, which
//     rides on the instance record; see gui_quad.shader's note on the same split.
//
// Pure screen-space 2D: deliberately does NOT reference CG_OBJECT_TO_WORLD / CG_MATRIX_MVP, so no
// per-instance object-buffer record is needed. gl_Position comes straight from cg_ProjMatrix, set
// once per frame by CgUiPaintContext.beginFrame().

#type pos2_uv2_col4ub
#pragma cg_use curve

// Shared with crystalgraphics:shaders/curve.shader — see that file and lib/stroke.glsl. This
// material owns render state and the final alpha; it owns NONE of the stroke maths.
#include "crystalgraphics:shaders/lib/stroke.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _LayerOpacity ("Layer Opacity", float) = 1.0
}

struct v2f {
    // Fragment position in the same space as the control points — i.e. after the PoseStack was
    // baked in CPU-side, before projection. The only true per-vertex quantity here; the control
    // points themselves are re-read per instance in the fragment stage, since the v2f DSL has no
    // flat qualifier to carry them with.
    vec2 posXy;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        // A FILL GETS ITS OWN GEOMETRY, NOT A BOX AROUND IT.
        //
        // CG_CURVE_WORLD_POS derives an axis-aligned box from the three points, which is right for a
        // Bezier and very loose for a triangle -- and worst exactly where this engine needs it most, since
        // a gradient fill's bands are cut perpendicular to the ramp and are therefore diagonal. Measured
        // before this branch: 55.7x the icon's area in fragments for 1.44x covered.
        //
        // fill_hull_vertex collapses the unit quad onto the triangle itself. Strokes keep the box, which
        // they need: a curve is not its control hull.
        // A FILL USES THE SAME AXIS-ALIGNED HULL AS A STROKE, and that is a decision rather than an
        // oversight. It is loose for a triangle -- measured at 9.4x the icon's area in fragments against
        // 1.4x covered on the JetBrains mark -- and the tight alternative does not fit the mesh.
        //
        // Expanding a triangle outward by a uniform distance produces a bevelled HEXAGON. The unit quad
        // this instance is drawn from has four corners, so the only way to express that in three points is
        // a miter, and a miter's length runs to infinity as a corner sharpens. Every triangle here is a
        // long thin iso-line strip with two very acute corners, so clamping the miter under-expands them
        // -- which clipped the coverage that closes the seams and showed as dots and dashes along every
        // one -- and not clamping produces quads larger than the box this was meant to replace.
        //
        // Doing it properly means a fill-specific mesh with enough vertices for the bevel. Worth it only
        // if fragments become the bottleneck: the icons a file tree actually draws measure 1.1-1.3x here,
        // and the cheaper lever is capping the aspect ratio of the cells themselves.
        vec3 worldPos = CG_CURVE_WORLD_POS;
        o.posXy = worldPos.xy;
        gl_Position = cg_ProjMatrix * vec4(worldPos, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // curve_instance_coverage, not stroke_coverage directly -- it is the one place stroke vs.
        // filled-triangle is decided (lib/stroke.glsl), so ctx.triangle()'s instances render
        // correctly through this material too, not just through the engine's own curve.shader.
        // THE PIXEL CENTRE COMES FROM gl_FragCoord, NOT FROM THE INTERPOLATED VARYING.
        //
        // i.posXy is interpolated across THIS INSTANCE'S hull quad, and every instance derives its own
        // hull from its own three points (CG_CURVE_WORLD_POS). So two triangles sharing a seam edge
        // reconstruct the same pixel's position through two different interpolations, and the values
        // disagree by roughly 1e-5 of the hull's extent -- about 0.008px on an 800px-wide icon.
        //
        // A tessellated fill decides seam ownership by nudging the two sides of a shared edge in
        // opposite directions (SvgDocument.FILL_OFFSET, ~0.005px). That nudge is SMALLER than the
        // disagreement, so it cannot arbitrate: near a seam both instances may claim a pixel, or
        // neither. And a horizontal seam is axis-aligned, so every pixel in the row has the same
        // distance to it and the whole row flips together -- a full-width line, present at some zooms
        // and absent either side of them. Off-axis seams flip pixel by pixel, which is the same bug
        // wearing sparse dashes.
        //
        // gl_FragCoord is exact and identical for every instance covering the pixel, so both sides
        // evaluate at the SAME point and only their own SDF rounding (~1e-5px) separates them -- three
        // orders below the offset instead of level with it.
        //
        // MEASURED, on the GPU, at a placement whose seam falls on a row of pixel centres: 25 one-pixel
        // artefact rows on the varying, 0 taking the point from here, with everything else held. A CPU
        // replay of this shader using exact pixel centres never reproduced the artefact at all, which is
        // what identified the varying as the input that differed. The offset in SvgDocument.FILL_OFFSET
        // is the other half and its size was measured against this; see that constant.
        //
        // THE FLIP IS REQUIRED, NOT COSMETIC. beginFrame sets ortho(0, w, h, 0) -- y down, origin
        // top-left, one unit per pixel -- so the control points are already in window pixels, while
        // gl_FragCoord is y-up from the bottom. cg_Resolution is set from the same viewport the ortho
        // was built from, including the layer-FBO path, so the two cannot drift apart.
        //
        // This is UI-only and belongs here rather than in lib/stroke.glsl: crystalgraphics'
        // curve.shader draws in world space, where gl_FragCoord cannot reconstruct the point at all.
        // That difference is one more reason the two materials are separate.
        vec2 p = vec2(gl_FragCoord.x, cg_Resolution.y - gl_FragCoord.y);

        float t;
        float alpha = curve_instance_coverage(p,
                                              CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                              CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                              int(CG_CURVE_FLAGS + 0.5), CG_CURVE_GRADIENT, t);

        vec4 color = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
        // The ONE line that differs from the engine's curve.shader, and the reason this material
        // exists at all alongside the RenderState above.
        alpha *= color.a * _LayerOpacity;

        if (alpha <= (1.0 / 255.0)) discard;

        fragColor = vec4(color.rgb, alpha);
    }
}
