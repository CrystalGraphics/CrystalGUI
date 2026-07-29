// crystalgui:shaders/gui_layer_blit.shader
//
// Composites a finished CgUiPaintContext "visual layer" FBO (from beginLayerFbo/endLayerFbo)
// back onto whatever's currently bound (the real screen, or an enclosing layer) — used by
// CgUiPaintContext.blitLayer(). NOT the same material as gui_quad.shader, and deliberately so:
// a layer FBO is always cleared fully transparent (clearColor(0,0,0,0)) before anything is
// painted into it, so at every partially-covered pixel (AA edges, translucent content) its
// stored color is PREMULTIPLIED by its own alpha (color = trueColor * coverage, alpha = coverage)
// — that's just what "straight-alpha compositing onto a transparent destination" produces.
// Blitting that back with gui_quad.shader's straight-alpha blend (srcRGB=SRC_ALPHA) would
// multiply the already-premultiplied color by coverage a SECOND time (coverage², not coverage),
// visibly darkening/fringing every AA edge and every translucent pixel compared to the same
// content drawn without a layer. Premultiplied blend (srcRGB=ONE) is the correct way to
// composite a premultiplied-alpha source.
//
// _LayerOpacity fades the WHOLE composited layer (e.g. CSS opacity < 1 on the owning element).
// Because the source is premultiplied, fading it needs BOTH rgb and alpha scaled by the same
// factor — scaling alpha alone (as gui_quad.shader does) would keep the premultiplied rgb at
// full strength while only lightening the blend's alpha weighting, which is wrong once the
// blend itself no longer re-multiplies rgb by alpha.

#type pos2_uv2_col4ub
#pragma cg_use quad

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex ("Layer Texture", sampler2D) = "white"
    _LayerOpacity ("Layer Opacity", float) = 1.0
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv    = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = texture(_MainTex, i.uv) * i.color;
        fragColor *= _LayerOpacity;
    }
}
