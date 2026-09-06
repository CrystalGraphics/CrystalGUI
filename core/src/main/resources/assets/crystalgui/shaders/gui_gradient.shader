// crystalgui:shaders/gui_gradient.shader
//
// A LINEAR GRADIENT IN ONE DRAW: up to eight stops evaluated per fragment along CSS's gradient line,
// interpolated in PREMULTIPLIED alpha, masked by the element's own corner radii, and dithered before
// the target quantises. What every production gradient does, and each of the four is a correction of
// something this shader first got wrong -- see docs/CGUI_MODERN_UI_RENDERING_RESEARCH.md section 8.
//
// ONE DRAW, NOT ONE PER SEGMENT. The first version drew a quad per pair of stops and set two colours
// on each; Skia evaluates a whole gradient of up to eight stops in a single unrolled shader
// (SkGradientShader's "unrolled binary" path) and falls back to a texture beyond that. Per-segment
// draws are more material switches for the same pixels AND they were wrong here: the paint context
// uploads a material's properties on the bind AFTER the draw body, so a flush inside the body drew
// every segment with the previous segment's colours. The ramp below is the unrolled form: each
// interval mixes the running colour toward its end stop by a clamped fraction, so intervals before
// the fragment contribute 1, intervals after it contribute 0, and the one containing it lerps. A
// gradient with more than eight stops is drawn as several of these, each owning a window of t.
//
// CSS'S GRADIENT LINE, for any angle. 0deg points up and the angle turns clockwise, so the direction
// is (sin a, -cos a) in this Y-down projection; the line runs through the box's centre with the
// length |W sin a| + |H cos a|, which is what puts 0% and 100% exactly at the corners at every angle
// (CSS Images 3 section 3.4.1). _Axis is that direction scaled by the box and divided by the length, so t
// is one dot product and the axis-aligned cases fall out (90deg: t = u; 180deg: t = v).
//
// PREMULTIPLIED, because `transparent` is transparent BLACK. A straight-alpha lerp from transparent
// to #3574F033 passes through (26, 58, 120, 25): a dark, half-desaturated blue, which is the muddy
// shoulder every naive gradient has on its way to a fade. CSS specifies premultiplied interpolation
// for exactly this (CSS Images 3 section 3.4.3) -- the colour holds its hue and only the alpha ramps. So the
// stops arrive premultiplied, the ramp mixes them premultiplied, and the result is written
// premultiplied under a ONE / ONE_MINUS_SRC_ALPHA blend rather than unpremultiplied first.
//
// DITHER, last. An 8-bit target quantises any shallow ramp into flat bands, and per-fragment mixing
// removes the STRIP edges the old draw had but not the LEVEL edges: a wash climbing fifty alpha
// levels across a third of a 1920px bar steps under two levels every twenty pixels, and on a dark
// surface that is a visible line. Half a level of hash noise in every channel, added after every
// other multiply so it is the last thing before quantisation, dissolves each band's edge into a
// stipple the eye averages back to the ramp (Skia dithers its gradients by default; Chrome banded for
// years because it compiled that out). Alpha too: the bands in a translucent wash are alpha bands.
//
// MASKED by the same rounded-box SDF gui_rounded_rect uses, under WITH_MASK, so a gradient on an
// element with a border-radius clips to it instead of squaring the corners; off for square boxes,
// which is the taskbar's glow and costs the fragment nothing.

#type pos2_uv2_col4ub
#pragma cg_use quad
#pragma cg_feature WITH_MASK

#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    // Stops, PREMULTIPLIED (rgb already scaled by a). Eight is Skia's unrolled limit; CgUiGradient
    // draws a longer gradient as several windows of eight.
    _Color0       ("Stop 0 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color1       ("Stop 1 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color2       ("Stop 2 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color3       ("Stop 3 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color4       ("Stop 4 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color5       ("Stop 5 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color6       ("Stop 6 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Color7       ("Stop 7 (premultiplied)",       vec4)  = (0.0, 0.0, 0.0, 0.0)
    _Positions0   ("Positions of stops 0-3",       vec4)  = (0.0, 1.0, 1.0, 1.0)
    _Positions1   ("Positions of stops 4-7",       vec4)  = (1.0, 1.0, 1.0, 1.0)
    _Count        ("Stops in this draw (2-8)",     float) = 2.0
    // The gradient line: t = 0.5 + dot(uv - 0.5, _Axis). (1, 0) is `to right`, (0, 1) is `to bottom`.
    _Axis         ("Gradient line",                vec2)  = (1.0, 0.0)
    // The half-open range of t this draw owns, [x, y). A single draw owns everything.
    _Window       ("Owned range of t",             vec2)  = (-1.0, 2.0)
    // WITH_MASK only: the element's resolved radii, CSS order, one vec4 per axis (elliptical corners).
    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize      ("Box Size (px)",                vec2)  = (0.0, 0.0)
    _LayerOpacity ("Layer Opacity",                float) = 1.0
}

struct v2f {
    vec2 uv;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        // PREMULTIPLIED source over a premultiplied target -- the layer blit's blend, not gui_quad's.
        // Everything above the blend is premultiplied, so the source factor is ONE for both.
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
        float t = 0.5 + dot(i.uv - 0.5, _Axis);

        // The unrolled ramp. Before the first stop every fraction is 0 and the colour is stop 0;
        // after the last every fraction is 1 and it is the last stop -- CSS pads with the end colours.
        // Two stops at one position are a hard stop: the max() keeps the division finite and the
        // fraction steps from 0 to 1 across that position.
        vec4 stops[8] = vec4[8](_Color0, _Color1, _Color2, _Color3, _Color4, _Color5, _Color6, _Color7);
        float positions[8] = float[8](_Positions0.x, _Positions0.y, _Positions0.z, _Positions0.w,
                                      _Positions1.x, _Positions1.y, _Positions1.z, _Positions1.w);
        int count = int(_Count + 0.5);
        vec4 c = stops[0];
        for (int k = 1; k < 8; k++) {
            if (k >= count) break;
            float f = clamp((t - positions[k - 1]) / max(positions[k] - positions[k - 1], 1e-5), 0.0, 1.0);
            c = mix(c, stops[k], f);
        }

        // The ambient tint (background-color, a cross-fade's opacity) is a straight colour; a straight
        // tint on a premultiplied colour scales rgb by the tint's rgb AND alpha, and alpha by alpha.
        c *= vec4(i.color.rgb * i.color.a, i.color.a);

        // WHAT THIS DRAW COVERS, collected before it is applied, because the dither below needs it too.
        // Outside the window this draw owns, contribute nothing: a premultiplied zero is a no-op blend,
        // so the other windows' draws are undisturbed and no fragment is ever written twice.
        float shape = step(_Window.x, t) * (1.0 - step(_Window.y, t));

#ifdef WITH_MASK
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (i.uv - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        // All four channels: premultiplied coverage is a multiply of the whole colour.
        shape *= sdf_coverage(dist);
#endif

        c *= shape * _LayerOpacity;

        // Last, so nothing rescales the noise before the target quantises it. hash12 is the engine's
        // sin-free hash, keyed on the fragment's position so the stipple is stable frame to frame.
        //
        // SCALED BY `shape`, or the dither is deposited where this draw covers nothing: half a level of
        // noise outside a rounded corner is still a level once the target quantises, so a masked
        // gradient would stipple its own corners back in. Inside the shape it is unscaled and last,
        // which is the property the paragraph above is about; a fully transparent STOP still dithers,
        // and must, because a shallow alpha ramp is exactly what banded.
        c += vec4((hash12(gl_FragCoord.xy) - 0.5) / 255.0) * shape;
        fragColor = c;
    }
}
