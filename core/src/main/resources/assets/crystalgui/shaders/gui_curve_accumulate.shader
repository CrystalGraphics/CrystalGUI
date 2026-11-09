// The canvas curve with additive blending and premultiplied output: SvgRasterCache sums a fill's cells into a scratch
// target, since their exact areas add up to the shape's coverage where compositing them one by one would not. No
// _LayerOpacity: the composite applies it. No discard: a sliver's 0.02 has to add up.

#type none
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
