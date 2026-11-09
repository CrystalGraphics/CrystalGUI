// The blur's prefilter: a 4x box reduction of the captured backdrop into the quarter-size blur target. Four bilinear
// fetches at (+-1, +-1) texels average the 4x4 block behind each output texel, so the Gaussian sees a band-limited
// source; without it the first pass was a comb and streaked text. Taps clamp into _Bounds; premultiplied throughout.

#type none
#pragma cg_use quad

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex   ("Source",                    sampler2D) = "white"
    // One SOURCE texel, in UV: (1 / sourceWidth, 1 / sourceHeight).
    _TexelSize ("Source texel size (UV)",    vec2)      = (0.0, 0.0)
    // The region of the source holding real content, as (u0, v0, u1, v1). Taps clamp into it.
    _Bounds    ("Valid source rect",         vec4)      = (0.0, 0.0, 1.0, 1.0)
}

struct v2f {
    vec2 uv;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        // A filter, not a composite: this overwrites its target.
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // Above vertex(): a helper below it reaches the vertex stage only.
    vec4 cg_box_tap(vec2 uv) {
        return texture(_MainTex, clamp(uv, _Bounds.xy, _Bounds.zw));
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv = CG_QUAD_UV;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 d = _TexelSize;
        vec4 sum = cg_box_tap(i.uv + vec2(-d.x, -d.y))
                 + cg_box_tap(i.uv + vec2( d.x, -d.y))
                 + cg_box_tap(i.uv + vec2(-d.x,  d.y))
                 + cg_box_tap(i.uv + vec2( d.x,  d.y));
        fragColor = sum * 0.25;
    }
}
