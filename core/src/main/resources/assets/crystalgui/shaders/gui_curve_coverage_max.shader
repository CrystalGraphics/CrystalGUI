// crystalgui:shaders/gui_curve_coverage_max.shader
//
// gui_curve_coverage.shader for STROKES: the same straight-alpha white coverage, combined by MAX
// rather than by sum. A fill's cells partition the shape and their areas add; a stroke's segments
// overlap at every joint (each carries the cap that meets the next), so adding them draws a dark
// bead on each joint. The maximum is the union of the segments -- exact at a joint, and only under
// where two strokes genuinely cross, which is where the direct path composites twice anyway.

#type pos2_uv2_col4ub
#pragma cg_use curve

#include "crystalgraphics:shaders/lib/stroke.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

struct v2f {
    vec2 posXy;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE
        BlendEquation MAX
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
        vec2 p = vec2(gl_FragCoord.x, cg_Resolution.y - gl_FragCoord.y);
        float t;
        float area = curve_instance_coverage(p,
                                             CG_CURVE_P0.xy, CG_CURVE_P1.xy, CG_CURVE_P2.xy,
                                             CG_CURVE_WIDTHS, CG_CURVE_FEATHER,
                                             int(CG_CURVE_FLAGS + 0.5), CG_CURVE_GRADIENT, t);
        fragColor = vec4(1.0, 1.0, 1.0, area);
    }
}
