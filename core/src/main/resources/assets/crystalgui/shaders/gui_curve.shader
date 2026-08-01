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
        vec3 worldPos = CG_CURVE_WORLD_POS;
        o.posXy = worldPos.xy;
        gl_Position = cg_ProjMatrix * vec4(worldPos, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // curve_instance_coverage, not stroke_coverage directly -- it is the one place stroke vs.
        // filled-triangle is decided (lib/stroke.glsl), so ctx.triangle()'s instances render
        // correctly through this material too, not just through the engine's own curve.shader.
        float t;
        float alpha = curve_instance_coverage(i.posXy,
                                              CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                              CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                              int(CG_CURVE_FLAGS + 0.5), t);

        vec4 color = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
        // The ONE line that differs from the engine's curve.shader, and the reason this material
        // exists at all alongside the RenderState above.
        alpha *= color.a * _LayerOpacity;

        if (alpha <= (1.0 / 255.0)) discard;

        fragColor = vec4(color.rgb, alpha);
    }
}
