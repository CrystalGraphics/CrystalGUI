// crystalgui:shaders/gui_quad.shader
//
// Single shared material for all CgGui 2D box-model quads (Track A immediate mode).
// Every draw call — solid-color rects and textured sprites alike — goes through this
// material. Solid rects bind CgUiPaintContext's 1x1 white texture; textured draws bind
// whatever CgTexture2D the CguiTexture strategy supplies. Tint/opacity are carried per
// vertex via a_color (see CgVertexFormat.POS2_UV2_COL4UB), NOT as a material property —
// this means adjacent quads with different tints still hit the state-elision fast path
// in CgUiPaintContext, since only the bound texture (or material, for non-box-model
// elements) needs to change, never a property re-apply.
//
// _LayerOpacity IS a material property (deliberately, unlike per-vertex tint above) —
// it represents a whole draw's compositing opacity (e.g. one side of a CgUiCrossFade),
// not a per-pixel/per-vertex color channel. See CgUiPaintContext.withLayerOpacity().
//
// Pure screen-space 2D: intentionally does NOT reference CG_OBJECT_TO_WORLD /
// CG_MATRIX_MVP, so no per-instance object-buffer record is required before drawing —
// gl_Position comes straight from cg_ProjMatrix (set to an ortho matrix once per frame
// by CgUiPaintContext.beginFrame()) applied to the raw a_pos attribute.

#type pos2_uv2_col4ub

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex ("Main Texture", sampler2D) = "white"
    _LayerOpacity ("Layer Opacity", float) = 1.0
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
        fragColor = texture(_MainTex, i.uv) * i.color;
        fragColor.a *= _LayerOpacity;
    }
}
