// crystalgui:shaders/gui_curve_accumulate.shader
//
// gui_curve.shader with ADDITIVE blending and premultiplied output -- the material SvgRasterCache
// rasterises a fill's cells through. A tessellated fill's cells each cover part of a pixel, and
// their exact areas SUM to the shape's coverage; composited one at a time they would not (two
// halves of a pixel blend to three quarters), which is why they are accumulated into a scratch
// target and composited from there as one texture.
//
// Everything that is not the blend and the output is identical to gui_curve.shader and comes
// from the same lib; see that file for the gl_FragCoord derivation. No _LayerOpacity: the
// accumulator holds pure coverage and the composite applies opacity. No discard: a sliver's
// 0.02 is exactly what has to add up.

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
        vec4 color = mix(CG_CURVE_COLOR0, CG_CURVE_COLOR1, t);
        float alpha = area * color.a;
        fragColor = vec4(color.rgb * alpha, alpha);
    }
}
