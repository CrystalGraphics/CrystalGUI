// crystalgui:shaders/gui_rounded_rect.shader
//
// SDF-based rounded-rectangle "canvas": interior filled by _FillColor or a sampled _MainTex
// (WITH_TEXTURE_FILL), with an optional _BorderColor stroke band (WITH_BORDER) along the
// outer edge. Both bands share one rounded-box SDF, so corners clip fill and border
// consistently. _CornerRadiusX/_CornerRadiusY each hold four independent per-corner radii
// (TL,TR,BR,BL, CSS order) — elliptical corners (rx != ry). UIElement's rounded-corner hit-test
// uses the same per-corner (rx,ry) values and the same approximate elliptical SDF technique, so
// rendering and hit-testing stay consistent.
//
// Not part of CgUiPaintContext's shared box-model batch (see gui_quad.shader) — drawn via
// CgUiPaintContext.withMaterial(...) since it needs its own per-instance uniforms (corner
// radius, border, box size) that don't fit the shared quad batch's per-vertex-only tint.

#type pos2_uv2_col4ub

#pragma cg_feature WITH_BORDER
#pragma cg_feature WITH_TEXTURE_FILL

#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex      ("Main Texture", sampler2D) = "white"
    _FillColor    ("Fill Color",   color)     = (1.0, 1.0, 1.0, 1.0)
    _BorderColor  ("Border Color", color)     = (0.0, 0.0, 0.0, 1.0)
    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BorderWidth  ("Border Width", float)     = 0.0
    _BoxSize      ("Box Size (px)", vec2)     = (0.0, 0.0)
    _LayerOpacity ("Layer Opacity", float)    = 1.0
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(cg_Position, 0.0, 1.0);
        o.uv    = cg_TexCoord0;
        o.color = cg_Color;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (i.uv - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        float coverage = sdf_coverage(dist);

#ifdef WITH_TEXTURE_FILL
        vec4 fillColor = texture(_MainTex, i.uv);
#else
        vec4 fillColor = _FillColor;
#endif

#ifdef WITH_BORDER
        float innerCoverage = sdf_coverage(dist + _BorderWidth);
        vec4 color = mix(_BorderColor, fillColor, innerCoverage);
#else
        vec4 color = fillColor;
#endif

        color *= i.color;
        color.a *= coverage * _LayerOpacity;
        fragColor = color;
    }
}
