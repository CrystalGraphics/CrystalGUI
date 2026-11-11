// The per-pixel surfaces a colour picker is made of, drawn through CgUiPaintContext.withMaterial:
//   HUE_RING (0)   an annulus, hue from the angle
//   SV_SQUARE (1)  saturation across x, value up y, at a fixed hue
//   GRADIENT (2)   _ColorA -> _ColorB across x over a checkerboard: every channel's track, alpha's included
//   HUE_STRIP (3)  a full hue sweep across x
// Corners clip with the UI's sdf_rounded_box, as its hit test does.

#type none
#pragma cg_use quad
#pragma cg_use clip

#include "crystalgraphics:shaders/lib/sdf.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    // 0 HUE_RING, 1 SV_SQUARE, 2 GRADIENT, 3 HUE_STRIP. A uniform, not a keyword: a few thousand pixels a draw.
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
}

struct v2f {
    // Grown by half a pixel when rotated, so the edge can antialias: CG_QUAD_EDGE_* in env/buffer/quad.glsl.
    vec2 param;
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

    // The checkerboard in pixels, so its squares keep their size. Above vertex(): a helper below it misses the
    // fragment stage.
    vec3 cg_checker(vec2 px) {
        vec2 cell = floor(px / max(_CheckerSize, 1.0));
        float odd = mod(cell.x + cell.y, 2.0);
        return mix(vec3(0.62), vec3(0.44), odd);
    }

    void vertex(out v2f o) {
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // Before any discard: it takes derivatives.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        vec2 halfSize = _BoxSize * 0.5;
        vec2 uv = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
        float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
        vec2 localPos = (uv - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        float coverage = sdf_coverage(dist, ramp);

        vec4 result;

        if (_Mode < 0.5) {
            // -- HUE_RING ----------------------------------------------------
            // Round even in a non-square box; atan's +0.5 puts red at the top.
            vec2 centred = (uv - 0.5) * 2.0;
            float radius = length(centred);
            float hue = fract(atan(centred.x, centred.y) / 6.28318530718 + 0.5);

            // Both edges antialiased from the same fwidth.
            float aa = fwidth(radius) * 1.5;
            float outer = 1.0 - smoothstep(1.0 - aa, 1.0, radius);
            float inner = smoothstep(_InnerRadius - aa, _InnerRadius, radius);
            result = vec4(hsv_to_rgb(vec3(hue, 1.0, 1.0)), outer * inner);
        } else if (_Mode < 1.5) {
            // -- SV_SQUARE ---------------------------------------------------
            // Value runs up: uv runs down.
            result = vec4(hsv_to_rgb(vec3(_Hue, uv.x, 1.0 - uv.y)), 1.0);
        } else if (_Mode < 2.5) {
            // -- GRADIENT ----------------------------------------------------
            // Over the checker here, so the track is opaque whatever the stop's alpha.
            vec4 ramp = mix(_ColorA, _ColorB, uv.x);
            vec3 over = mix(cg_checker(uv * _BoxSize), ramp.rgb, ramp.a);
            result = vec4(over, 1.0);
        } else {
            // -- HUE_STRIP ---------------------------------------------------
            // A hue sweep, which a two-stop GRADIENT cannot draw: hue 0 and 1 are both red. Always fully saturated
            // and bright: at the current colour's, a pale colour washed it out.
            result = vec4(hsv_to_rgb(vec3(uv.x, 1.0, 1.0)), 1.0);
        }

        result *= i.color;

        result.a *= coverage * CG_QUAD_OPACITY;
        fragColor = vec4(result.rgb, result.a * boxClip);
    }
}
