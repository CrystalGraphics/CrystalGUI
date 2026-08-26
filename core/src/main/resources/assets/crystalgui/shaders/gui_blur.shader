// crystalgui:shaders/gui_blur.shader
//
// ONE AXIS OF A SEPARABLE GAUSSIAN, with the kernel derived from sigma rather than fixed.
//
// The taps sit ONE SOURCE TEXEL apart (`_Step` is a texel along the axis), there are `_Radius` of them
// either side of the centre, and `_Radius` is ceil(3 * sigma) -- the point past which a tap carries less
// than half a percent of the weight. That pair of facts is what makes a discrete Gaussian correct: the
// weights are the continuous curve sampled at unit spacing, and every sample that matters is present.
// The caller keeps sigma small by SCALING THE INPUT DOWN first (CgUiBackdrop.blurredBackdrop, Skia's
// rule), so the loop stays short whatever reach the sheet asks for.
//
// The version this replaced had nine fixed taps with sigma ~2 in TAP units, and stretched the distance
// between taps to reach the asked-for radius. Over a full-resolution source that put the taps six
// pixels apart: a comb, not a kernel. Text survived it as the stems the taps happened to land on, and
// the vertical pass smeared those into streaks. "Not a proper blur" was exactly right.
//
// WEIGHTS ARE COMPUTED INCREMENTALLY (GPU Gems 3, ch. 40 "Incremental Computation of the Gaussian"):
// three multiplies per tap instead of an exp(), and no table to upload. They are normalised over the
// taps actually used, so a kernel cut short by `_Radius` never darkens the result -- a truncated
// Gaussian that is not renormalised loses energy, which reads as a tint.
//
// PREMULTIPLIED ALPHA IS CARRIED THROUGH, NOT DISCARDED -- see the note on the alpha write below; it
// is the single thing this pass most easily gets wrong, and the symptom does not look like alpha.
//
// EDGE CLAMPING IS THE OTHER HALF. Taps that fall outside the captured region must not drag its clear
// colour inward. `_Bounds` is the sub-rect of the source that holds real content, and every tap is
// clamped into it, so a tap that would leave simply re-reads the nearest real pixel. That is what
// CLAMP_TO_EDGE would do if the content filled the texture, and the content never does.

#type pos2_uv2_col4ub
#pragma cg_use quad
// The loop's bound is a CONSTANT and the radius breaks out of it: a uniform loop bound is legal
// GLSL 3.30 but some drivers unroll nothing they cannot see the end of, and a constant bound with an
// early break is the spelling every one of them handles. Material scope, so the compiler hoists it
// into both stages. Keep in step with CgUiBackdrop.MAX_KERNEL_RADIUS.
#define CG_BLUR_MAX_RADIUS 16

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex   ("Source",                    sampler2D) = "white"
    // ONE source texel along the axis of this pass, in UV; zero on the other axis.
    _Step      ("Per-tap UV step",           vec2)      = (0.0, 0.0)
    // Sigma in source texels. The caller keeps it small by scaling the source down first.
    _Sigma     ("Sigma (texels)",            float)     = 2.0
    // Taps either side of the centre: ceil(3 * sigma), at most CG_BLUR_MAX_RADIUS.
    _Radius    ("Taps per side",             float)     = 6.0
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
        float sigma = max(_Sigma, 0.25);
        // Incremental Gaussian: g.x is the weight at the current tap, g.y the ratio to the next, g.z the
        // ratio's own growth. Starting from the centre, each step multiplies the pair through.
        vec3 g;
        g.x = 1.0 / (sqrt(2.0 * 3.14159265) * sigma);
        g.y = exp(-0.5 / (sigma * sigma));
        g.z = g.y * g.y;

        vec4 sum = cg_tap(i.uv) * g.x;
        float weight = g.x;
        for (int k = 1; k <= CG_BLUR_MAX_RADIUS; k++) {
            if (float(k) > _Radius) break;
            g.xy *= g.yz;
            vec2 d = _Step * float(k);
            sum += (cg_tap(i.uv + d) + cg_tap(i.uv - d)) * g.x;
            weight += 2.0 * g.x;
        }

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
        fragColor = sum / weight;
    }
}
