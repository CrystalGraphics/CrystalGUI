// crystalgui:shaders/gui_rounded_rect.shader
//
// SDF-based rounded-rectangle "canvas": interior filled by _FillColor, a sampled _MainTex
// (WITH_TEXTURE_FILL, single stretched sample), or a 9-slice sprite (WITH_9SLICE_FILL, per-pixel
// equivalent of CgUiSprite's 9-quad slicing — see the fragment's own comment), with an optional
// _BorderColor stroke band (WITH_BORDER) along the outer edge. All fill modes and the border share
// one rounded-box SDF, so corners clip everything consistently. _CornerRadiusX/_CornerRadiusY each
// hold four independent per-corner radii (TL,TR,BR,BL, CSS order) — elliptical corners (rx != ry).
// UIElement's rounded-corner hit-test uses the same per-corner (rx,ry) values and the same
// approximate elliptical SDF technique, so rendering and hit-testing stay consistent.
//
// Not part of CgUiPaintContext's shared box-model batch (see gui_quad.shader) — drawn via
// CgUiPaintContext.withMaterial(...) since it needs its own per-instance uniforms (corner
// radius, border, box size) that don't fit the shared quad batch's per-vertex-only tint.

#type pos2_uv2_col4ub

#pragma cg_feature WITH_BORDER
#pragma cg_feature WITH_TEXTURE_FILL
#pragma cg_feature WITH_9SLICE_FILL

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
    // WITH_9SLICE_FILL only — mirrors CgUiSprite's own border/UV-breakpoint fields exactly, so the
    // fragment reproduces the same 9-region remap CgUiSprite does with 9 separate quads, in one pass.
    _NineSliceBorder  ("9-Slice Border L,T,R,B (px)",       vec4) = (0.0, 0.0, 0.0, 0.0)
    _NineSliceOuterUV ("9-Slice Outer UV u0,v0,u3,v3",      vec4) = (0.0, 0.0, 1.0, 1.0)
    _NineSliceInnerUV ("9-Slice Inner UV u1,v1,u2,v2",      vec4) = (0.0, 0.0, 1.0, 1.0)
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

#ifdef WITH_9SLICE_FILL
        // Continuous-per-pixel equivalent of CgUiSprite's 9-quad slicing: remap this pixel's
        // box-local position into the correct one of 9 atlas regions, using the same
        // border-overlap clamp (scaleX/scaleY) CgUiSprite.draw() applies.
        vec2 p = i.uv * _BoxSize;
        vec4 border = _NineSliceBorder;
        float scaleX = min(1.0, _BoxSize.x / max(1.0, border.x + border.z));
        float scaleY = min(1.0, _BoxSize.y / max(1.0, border.y + border.w));
        float drawL = border.x * scaleX;
        float drawR = border.z * scaleX;
        float drawT = border.y * scaleY;
        float drawB = border.w * scaleY;
        float x1 = drawL, x2 = _BoxSize.x - drawR;
        float y1 = drawT, y2 = _BoxSize.y - drawB;

        float u;
        if (p.x < x1) {
            u = mix(_NineSliceOuterUV.x, _NineSliceInnerUV.x, x1 > 0.0 ? clamp(p.x / x1, 0.0, 1.0) : 0.0);
        } else if (p.x > x2) {
            float denom = _BoxSize.x - x2;
            u = mix(_NineSliceInnerUV.z, _NineSliceOuterUV.z, denom > 0.0 ? clamp((p.x - x2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float denom = x2 - x1;
            u = mix(_NineSliceInnerUV.x, _NineSliceInnerUV.z, denom > 0.0 ? clamp((p.x - x1) / denom, 0.0, 1.0) : 0.0);
        }

        float v;
        if (p.y < y1) {
            v = mix(_NineSliceOuterUV.y, _NineSliceInnerUV.y, y1 > 0.0 ? clamp(p.y / y1, 0.0, 1.0) : 0.0);
        } else if (p.y > y2) {
            float denom = _BoxSize.y - y2;
            v = mix(_NineSliceInnerUV.w, _NineSliceOuterUV.w, denom > 0.0 ? clamp((p.y - y2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float denom = y2 - y1;
            v = mix(_NineSliceInnerUV.y, _NineSliceInnerUV.w, denom > 0.0 ? clamp((p.y - y1) / denom, 0.0, 1.0) : 0.0);
        }

        vec4 fillColor = texture(_MainTex, vec2(u, v));
#elif defined(WITH_TEXTURE_FILL)
        vec4 fillColor = texture(_MainTex, i.uv);
#else
        vec4 fillColor = _FillColor;
#endif

        // Ambient background-color tint — scoped to the fill/background region only, so it never
        // bleeds into _BorderColor's own independently-resolved alpha below.
        fillColor *= i.color;

#ifdef WITH_BORDER
        float innerCoverage = sdf_coverage(dist + _BorderWidth);
        vec4 color = mix(_BorderColor, fillColor, innerCoverage);
#else
        vec4 color = fillColor;
#endif

        color.a *= coverage * _LayerOpacity;
        fragColor = color;
    }
}
