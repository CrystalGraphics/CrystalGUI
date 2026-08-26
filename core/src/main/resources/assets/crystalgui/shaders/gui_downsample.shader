// crystalgui:shaders/gui_downsample.shader
//
// THE PREFILTER THE BLUR WAS MISSING. A 4x box reduction of the captured backdrop into the quarter-res
// blur target, run once per frame before the two Gaussian passes.
//
// gui_blur.shader's own note says why it is nine taps at a quarter of the resolution: over a
// quarter-size source the taps "land barely more than a texel apart", where over the full-size one they
// leave gaps. What the note describes was only ever true of the SECOND pass. The first read the
// full-resolution capture directly, with its taps a quarter of the radius apart -- six pixels at the
// taskbar's radius of 24 -- so nine taps skipped five pixels between each and the pass was a comb, not
// a kernel. A glyph stem survives a comb wherever a tap happens to land on it, so the horizontal pass
// passed text through nearly intact and the vertical pass, which WAS running over a quarter-res source
// and was correct, then smeared those surviving stems into vertical streaks. Reported as "smudgy
// artifacts, not a proper blur", over the one backdrop that has text in it.
//
// Reducing first fixes it at the source: one bilinear fetch already averages a 2x2 block, so four
// fetches at (+-1, +-1) source texels average the 4x4 block behind each output texel -- a box filter
// wide enough to stop a 4x decimation aliasing -- and every later tap of the Gaussian sees a
// band-limited source. The whole thing runs over the captured sub-rect only, at a sixteenth of its
// pixel count, so it costs less than either Gaussian pass.
//
// Same edge rule as the blur: taps clamp into `_Bounds`, the sub-rect that holds real content, so a
// tap that would leave re-reads the nearest real pixel rather than the target's transparent clear.
// Same premultiplied contract too -- alpha is averaged with the colour, never rewritten.

#type pos2_uv2_col4ub
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

    // ABOVE vertex(), as gui_blur.shader records: a helper written below it reaches the vertex source only.
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
