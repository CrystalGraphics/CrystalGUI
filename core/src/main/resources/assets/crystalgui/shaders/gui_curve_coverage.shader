// crystalgui:shaders/gui_curve_coverage.shader
//
// gui_curve_accumulate.shader for a fill with no colour of its own: the cells add their exact areas
// into ALPHA ONLY, and the colour channels are written white. The atlas then holds a straight-alpha
// texture -- (1, 1, 1, coverage) -- which the ordinary box-model material composites with the tint as
// its colour, so a cached icon batches with every other quad instead of costing a material switch.
// A fill whose colours are baked in cannot be stored straight (its channels are sums) and takes the
// premultiplied material instead.

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
        Blend ONE ZERO, ONE ONE
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
