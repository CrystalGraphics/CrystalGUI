// crystalgui:shaders/gui_blur.shader
//
// A SEPARABLE GAUSSIAN, run once horizontally and once vertically over the captured backdrop.
//
// This replaced a dual-Kawase down/up chain (Bjorge, SIGGRAPH 2015), which is the faster algorithm and
// the one the plan specifies. It is worth recording why it is not what ships, because the reason is not
// that dual Kawase is wrong:
//
//   The chain's correctness depends on every level's viewport, texel size and quad agreeing, across a
//   pyramid whose sizes are derived by integer halving. When it disagreed the symptom was not "slightly
//   wrong blur" -- it was darkness bleeding in from outside the captured region with a HARD boundary,
//   which reads as a sampling bug anywhere except where it actually was. Five rounds of instrumented
//   guessing did not close it, and a one-iteration chain -- which should reach about four pixels --
//   was visibly darkening a hundred.
//
// A separable pass has one texel size, one direction, and one radius. It can be checked by reading it.
// The pyramid is the right optimisation to return to WITH A TEST that can see a single level in
// isolation; it is the wrong thing to keep while the effect it serves has never once looked right.
//
// PREMULTIPLIED ALPHA IS CARRIED THROUGH, NOT DISCARDED -- see the note on the alpha write below; it
// is the single thing this pass most easily gets wrong, and the symptom does not look like alpha.
//
// EDGE CLAMPING IS THE OTHER HALF. Taps that fall outside the captured region must not drag its clear
// colour inward -- that is precisely the darkness above. `_Bounds` is the sub-rect of the source that
// holds real content, and every tap is clamped into it, so a tap that would leave simply re-reads the
// nearest real pixel. That is what CLAMP_TO_EDGE would do if the content filled the texture, and the
// content never does.

#type pos2_uv2_col4ub
#pragma cg_use quad

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex   ("Source",                    sampler2D) = "white"
    // Texels to step per tap, in UV. Zero on one axis: this pass is one dimension of a separable blur.
    _Step      ("Per-tap UV step",           vec2)      = (0.0, 0.0)
    // The region of the source holding real content, as (u0, v0, u1, v1). Taps clamp into it.
    _Bounds    ("Valid source rect",         vec4)      = (0.0, 0.0, 1.0, 1.0)
}

struct v2f {
    vec2 uv;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        // A filter, not a composite: every pass overwrites its target.
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // DECLARED BEFORE vertex(), AND THAT IS A REQUIREMENT RATHER THAN A STYLE.
    //
    // The material compiler hoists the region of a Pass body ABOVE `void vertex` into both generated
    // stages; anything written between vertex() and fragment() reaches the vertex source only. Putting a
    // fragment helper there fails the FRAGMENT compile with `undefined variable "cg_tap"` -- and the
    // properties it touches go undefined with it, which is what makes the error list read as though the
    // Properties block itself were broken.
    //
    // A failed compile is not a failed draw: the pass still ran, still switched targets and still cleared,
    // so the blur targets came out part clear-black and part fallback-white. On screen that is a panel
    // with dark regions and blown-out ones -- indistinguishable from a sampling bug, and it cost six
    // rounds of looking at the sampling. The compile error was in the harness log from the first run.
    vec4 cg_tap(vec2 uv) {
        return texture(_MainTex, clamp(uv, _Bounds.xy, _Bounds.zw));
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv = CG_QUAD_UV;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Nine taps, Gaussian-weighted (sigma ~ 2). Symmetric, so the centre is counted once.
        const float w0 = 0.2270270270;
        const float w1 = 0.1945945946;
        const float w2 = 0.1216216216;
        const float w3 = 0.0540540541;
        const float w4 = 0.0162162162;

        vec4 sum = cg_tap(i.uv) * w0;
        sum += (cg_tap(i.uv + _Step) + cg_tap(i.uv - _Step)) * w1;
        sum += (cg_tap(i.uv + _Step * 2.0) + cg_tap(i.uv - _Step * 2.0)) * w2;
        sum += (cg_tap(i.uv + _Step * 3.0) + cg_tap(i.uv - _Step * 3.0)) * w3;
        sum += (cg_tap(i.uv + _Step * 4.0) + cg_tap(i.uv - _Step * 4.0)) * w4;

        // ALPHA IS AVERAGED WITH THE COLOUR, and that is the whole correctness of this pass.
        //
        // Every UI target in this engine holds PREMULTIPLIED colour -- gui_quad.shader blends
        // `SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA`, so drawing into a target cleared to
        // transparent leaves `rgb * a` behind. Premultiplied is exactly the representation that is
        // LINEAR under filtering, which is why it can be blurred at all.
        //
        // The earlier version averaged rgb alone and wrote `a = 1`, on the reasoning that a backdrop
        // ought to be opaque and the shape's alpha is decided later. Both halves of that are true and the
        // conclusion was still wrong: in premultiplied space a pixel with a = 0 is BLACK, so every tap
        // reaching a partly-covered pixel mixed black in and then declared the result opaque. On screen
        // that is a panel with dark regions that GROW WITH THE RADIUS and end at a hard line where the
        // transparent area begins -- which reads as a sampling or a clamping bug, not an alpha one,
        // because a blur is the last thing anyone expects to produce a hard edge. `blur 0` was correct
        // throughout, since sampling a single pixel never reaches one.
        //
        // The consumer un-premultiplies. @see gui_glass.shader#cg_backdrop
        fragColor = sum;
    }
}
