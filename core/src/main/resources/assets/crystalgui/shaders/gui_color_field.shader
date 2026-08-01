// crystalgui:shaders/gui_color_field.shader
//
// The continuously-varying surfaces a colour picker is made of. Every one is a per-pixel function of
// position, so none can be a texture or a batched quad — the same reason gui_rounded_rect.shader
// exists and is drawn through CgUiPaintContext.withMaterial(...).
//
//   HUE_RING (0)   an annulus, hue taken from the angle
//   SV_SQUARE (1)  saturation across x, value up y, at a fixed hue
//   GRADIENT (2)   _ColorA -> _ColorB across x, composited OVER a checkerboard
//   HUE_STRIP (3)  a full hue sweep across x
//
// GRADIENT covers R, G, B and A alike, and that is deliberate rather than a saving: an R/G/B track is
// opaque so its checkerboard is invisible, while the A track's is the whole point. One mode, one code
// path, and the alpha slider cannot drift from the others.
//
// HUE_STRIP exists because H is the one channel GRADIENT cannot express. Hue 0 and hue 1 are both
// red, so a two-stop ramp between them is a flat red bar — which is exactly what the H slider drew
// until this mode was added.
//
// Rounded corners come from the same sdf_rounded_box the rest of the UI uses, so a picker's
// swatches clip identically to every other rounded surface — including against UIElement's
// hit-test, which uses the same per-corner radii.

#type pos2_uv2_col4ub
#pragma cg_use quad

#include "crystalgraphics:shaders/lib/sdf.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    // 0 = HUE_RING, 1 = SV_SQUARE, 2 = GRADIENT, 3 = HUE_STRIP. A uniform rather than a keyword variant: the
    // branch is uniform across every pixel of a draw, these surfaces are a few thousand pixels
    // each, and three compiled programs for one picker is the worse trade.
    _Mode          ("Mode",                 float) = 0.0
    _ColorA        ("Gradient From",        color) = (0.0, 0.0, 0.0, 1.0)
    _ColorB        ("Gradient To",          color) = (1.0, 1.0, 1.0, 1.0)
    // HUE_RING and SV_SQUARE: the hue the square is drawn at, 0..1.
    _Hue           ("Hue",                  float) = 0.0
    // HUE_RING: inner radius as a fraction of the outer, so the band scales with the widget.
    _InnerRadius   ("Ring Inner Radius",    float) = 0.72
    _CheckerSize   ("Checker Square (px)",  float) = 4.0
    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize       ("Box Size (px)",        vec2)  = (0.0, 0.0)
    _LayerOpacity  ("Layer Opacity",        float) = 1.0
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA, ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // The transparency checkerboard, in PIXELS rather than uv, so the squares stay the same size
    // whatever the widget's dimensions — a checker that stretched with its box would read as a
    // pattern belonging to the colour rather than to the surface behind it.
    //
    // DECLARED BEFORE vertex(), and that is not style. The pass body is split at the stage functions,
    // so a helper written below vertex() lands outside the fragment scope: it reports as an undefined
    // variable, and every Property in the block reports the same way right after it, because the
    // mis-split takes the uniform declarations with it. gui_rounded_rect.shader puts its own helper
    // here for the same reason.
    vec3 cg_checker(vec2 px) {
        vec2 cell = floor(px / max(_CheckerSize, 1.0));
        float odd = mod(cell.x + cell.y, 2.0);
        return mix(vec3(0.62), vec3(0.44), odd);
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv    = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (i.uv - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        float coverage = sdf_coverage(dist);

        vec4 result;

        if (_Mode < 0.5) {
            // ── HUE_RING ────────────────────────────────────────────────────
            // Distance from centre normalised so the ring is round even when the box is not, then
            // banded between the inner radius and the edge. atan gives -pi..pi; the +0.5 turns it
            // into 0..1 with red at the top, which is the orientation every picker uses.
            vec2 centred = (i.uv - 0.5) * 2.0;
            float radius = length(centred);
            float hue = fract(atan(centred.x, centred.y) / 6.28318530718 + 0.5);

            // Antialiased on BOTH edges from the same fwidth, so the band's two boundaries are
            // equally soft. A hard edge here is the most visible aliasing in the whole widget,
            // because it is a large smooth curve against a flat background.
            float aa = fwidth(radius) * 1.5;
            float outer = 1.0 - smoothstep(1.0 - aa, 1.0, radius);
            float inner = smoothstep(_InnerRadius - aa, _InnerRadius, radius);
            result = vec4(hsv_to_rgb(vec3(hue, 1.0, 1.0)), outer * inner);
        } else if (_Mode < 1.5) {
            // ── SV_SQUARE ───────────────────────────────────────────────────
            // x is saturation, y is value with 1 at the TOP — uv runs downward, so the flip is
            // what puts white in the corner every user expects it in.
            result = vec4(hsv_to_rgb(vec3(_Hue, i.uv.x, 1.0 - i.uv.y)), 1.0);
        } else if (_Mode < 2.5) {
            // ── GRADIENT ────────────────────────────────────────────────────
            // Composited over the checker HERE rather than relying on blending, so the result is
            // opaque and the surface behind the widget never shows through a low-alpha stop.
            vec4 ramp = mix(_ColorA, _ColorB, i.uv.x);
            vec3 over = mix(cg_checker(i.uv * _BoxSize), ramp.rgb, ramp.a);
            result = vec4(over, 1.0);
        } else {
            // ── HUE_STRIP ───────────────────────────────────────────────────
            // The H channel's track, and it cannot be a GRADIENT: hue 0 and hue 1 are BOTH red, so a
            // two-stop ramp between them is a flat red bar — which is exactly what the H slider drew
            // before this mode existed. A hue sweep is not expressible as two endpoints.
            //
            // ALWAYS fully saturated and bright. Drawing it at the current saturation and value was
            // tried and is wrong: a pale colour washed the strip out to near-white, so the one control
            // whose job is choosing a hue became unreadable exactly when hue was hardest to judge. It
            // is a palette, not a preview of the current colour.
            result = vec4(hsv_to_rgb(vec3(i.uv.x, 1.0, 1.0)), 1.0);
        }

        result *= i.color;

        result.a *= coverage * _LayerOpacity;
        fragColor = result;
    }
}
