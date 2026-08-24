// crystalgui:shaders/gui_glass.shader
//
// LIQUID GLASS: the composite pass. Takes a blurred backdrop and a sharp one -- both already cropped
// to this element's rect by CgUiPaintContext.backdropFor -- and turns them into a pane of glass.
//
// Layer order, which is Windows Acrylic's recipe with refraction inserted where a real lens puts it:
//
//     refract -> pick blurred/sharp -> saturate -> tint -> specular -> noise -> SDF mask
//
// EVERY OPTIONAL LAYER IS A KEYWORD, so a theme that wants plain frosted glass gets a shader with
// none of this code compiled into it. `ior 1`, `specular 0`, `noise 0` each switch one off.
//
// THE REFRACTION IS NOT DERIVED FROM THE SDF DISTANCE. That is the mistake to avoid and it looks
// plausible: a bevel shaded from distance gives a bevel, not a lens. The displacement comes from a
// SURFACE HEIGHT PROFILE across the bezel -- normalised edge distance -> height h(x) -> its
// derivative -> the surface normal -> Snell's law -> a planar offset. Apple's surface matches a
// convex squircle, h(x) = (1 - (1-x)^4)^(1/4), which is the default here.
//
// The browser reimplementations bake that into an offscreen displacement map because SVG's
// feDisplacementMap needs a bitmap. We are already in a fragment shader with an analytic SDF, so
// the map, its 127-sample radius and its R/G channel encoding are all artefacts of that pipeline
// rather than of the technique.

#type pos2_uv2_col4ub
#pragma cg_use quad

#pragma cg_feature WITH_REFRACTION
#pragma cg_feature WITH_CHROMATIC
#pragma cg_feature WITH_SPECULAR
#pragma cg_feature WITH_NOISE

#include "crystalgraphics:shaders/lib/sdf.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex   ("Blurred backdrop", sampler2D) = "white"
    _SharpTex  ("Sharp backdrop",   sampler2D) = "white"
    // WHERE THIS ELEMENT SITS IN THEM. Both backdrops are the whole surface, captured and blurred ONCE
    // for every consumer on the frame, so each one crops itself rather than owning a texture.
    // (u0, vBottom, u1, vTop) -- v is inverted because GL framebuffers are bottom-left origin.
    _BackdropRect ("u0,v0,u1,v1", vec4) = (0.0, 0.0, 1.0, 1.0)

    _CornerRadiusX ("Corner Radii X (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner Radii Y (TL,TR,BR,BL)", vec4) = (0.0, 0.0, 0.0, 0.0)
    _BoxSize       ("Box Size (px)",                vec2) = (0.0, 0.0)

    _Tint       ("Tint (over the blur)", color) = (0.0, 0.0, 0.0, 0.0)
    _Saturation ("Saturation of backdrop", float) = 1.0
    _Bezel      ("Bezel width (px)",       float) = 8.0
    _Ior        ("Index of refraction",    float) = 1.5
    _Specular   ("Specular strength",      float) = 0.0
    _Noise      ("Grain amount",           float) = 0.0
    _LightDir   ("Light direction",        vec2)  = (-0.55, -0.83)
    _LayerOpacity ("Layer Opacity",        float) = 1.0
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

    // The glass's height across the bezel, as a function of normalised distance inward from the
    // edge. x = 0 at the outer boundary, x = 1 where the surface becomes flat.
    //
    // Convex squircle -- the profile Apple's own surface matches, and the reason its bezel reads as
    // "poured" rather than chamfered: it leaves the flat interior smoothly rather than at an angle.
    float cg_glassHeight(float x) {
        float t = 1.0 - clamp(x, 0.0, 1.0);
        return pow(max(0.0, 1.0 - t * t * t * t), 0.25);
    }

    // The outward unit normal of the SDF in the plane, by central difference on the field itself.
    //
    // NOT dFdx/dFdy. Those are fragment-only builtins, and while this IS a fragment, sdf.glsl already
    // pays for one AMD-only launch failure caused by a derivative builtin reaching a vertex stage --
    // sampling the field costs four more evaluations and cannot regress that way.
    vec2 cg_sdfGradient(vec2 p, vec2 halfSize) {
        const float e = 1.0;
        float dx = sdf_rounded_box(p + vec2(e, 0.0), halfSize, _CornerRadiusX, _CornerRadiusY)
                 - sdf_rounded_box(p - vec2(e, 0.0), halfSize, _CornerRadiusX, _CornerRadiusY);
        float dy = sdf_rounded_box(p + vec2(0.0, e), halfSize, _CornerRadiusX, _CornerRadiusY)
                 - sdf_rounded_box(p - vec2(0.0, e), halfSize, _CornerRadiusX, _CornerRadiusY);
        return normalize(vec2(dx, dy) + vec2(1e-6, 0.0));
    }

    /**
     * A backdrop sample, UN-PREMULTIPLIED.
     *
     * <p>Every UI target holds premultiplied colour (gui_quad.shader blends `SRC_ALPHA ...` for rgb and
     * `ONE ...` for alpha, so a draw into a transparent target leaves `rgb * a`). Reading one straight
     * and treating it as opaque darkens every partly-covered pixel toward black -- invisible on a fully
     * covered backdrop, and the whole of the "why does the blur grey everything out" bug once a blur
     * started averaging covered and uncovered pixels together.</p>
     *
     * <p>The floor is one 8-bit step: below that there is genuinely nothing there, and dividing by a
     * smaller number amplifies whatever rounding is left rather than recovering a colour.</p>
     */
    vec4 cg_backdrop(sampler2D tex, vec2 uv) {
        vec4 c = texture(tex, uv);
        c.rgb /= max(c.a, 1.0 / 255.0);
        return c;
    }

    /** Element uv -> backdrop uv. The y flip lives here, so callers work in element space. */
    vec2 cg_backdropUv(vec2 uv) {
        return vec2(mix(_BackdropRect.x, _BackdropRect.z, clamp(uv.x, 0.0, 1.0)),
                    mix(_BackdropRect.w, _BackdropRect.y, clamp(uv.y, 0.0, 1.0)));
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

        // How far inside the bezel this pixel is: 0 at the boundary, 1 where the glass goes flat.
        float bezel = max(1.0, _Bezel);
        float edge  = clamp(-dist / bezel, 0.0, 1.0);

        vec2 uv = i.uv;
        vec2 grad = vec2(0.0, 0.0);
        float slope = 0.0;

#if defined(WITH_REFRACTION) || defined(WITH_SPECULAR)
        grad = cg_sdfGradient(localPos, halfSize);
        // The surface normal's tilt, by central difference on the height profile. Analytically this
        // derivative is unbounded at x = 0 (a lens edge really is vertical), so it is sampled and
        // clamped rather than solved -- an infinity here would take the whole rim with it.
        const float d = 0.02;
        slope = (cg_glassHeight(edge + d) - cg_glassHeight(edge - d)) / (2.0 * d);
        slope = min(slope, 8.0);
#endif

#ifdef WITH_REFRACTION
        // Snell's law for a ray arriving perpendicular to the backdrop. theta1 is the angle between
        // that ray and the tilted surface normal, so it IS atan(slope); theta2 is where the glass
        // sends it. The planar displacement is the difference, over the thickness the bezel stands for.
        //
        // At the flat interior slope is 0, so theta1 == theta2 and the offset vanishes -- which is
        // why this needs no separate "only near the edge" mask.
        float theta1 = atan(slope);
        float theta2 = asin(clamp(sin(theta1) / max(1.0, _Ior), -1.0, 1.0));
        float shift  = tan(theta1 - theta2) * bezel;
        uv = i.uv + grad * shift / max(vec2(1.0), _BoxSize);
#endif

        // The bezel shows the SHARP backdrop and the interior the blurred one. Real glass is
        // thickest and least diffuse at its edge, and it is also where the refraction is -- blurring
        // the very thing being bent throws the lens away.
        vec2 buv = cg_backdropUv(uv);
        vec4 blurred = cg_backdrop(_MainTex, buv);
        vec4 sharp   = cg_backdrop(_SharpTex, buv);
        vec4 c = mix(sharp, blurred, smoothstep(0.0, 1.0, edge));

        // THE BACKDROP IS OPAQUE ONCE UN-PREMULTIPLIED. A capture is transparent wherever nothing
        // has been drawn yet -- the scene's own clear, the gap between one layer's content and the next
        // -- and carrying that alpha through made the glass transparent in exactly those places. On
        // screen that is a panel with a hard-edged hole in it showing the raw backdrop, which reads as a
        // sampling bug rather than as an alpha one, because the hole is full of the very thing the panel
        // is supposed to be showing a treated version of.
        //
        // A pane of glass covers what is behind it. Its own alpha is its SHAPE, applied at the end.
        c.a = 1.0;

#ifdef WITH_CHROMATIC
        // Red and blue refract by slightly different amounts, so a lens fringes at its rim. Scaled by
        // (1 - edge) so it vanishes where the glass is flat: fringing across a whole surface does not
        // read as optics, it reads as a broken sampler.
        float fringe = (1.0 - edge) * 0.6;
        vec2 dR = grad * (shift * 0.985) / max(vec2(1.0), _BoxSize);
        vec2 dB = grad * (shift * 1.015) / max(vec2(1.0), _BoxSize);
        c.r = mix(c.r, cg_backdrop(_SharpTex, cg_backdropUv(i.uv + dR)).r, fringe);
        c.b = mix(c.b, cg_backdrop(_SharpTex, cg_backdropUv(i.uv + dB)).b, fringe);
#endif

        // Saturation lift. A heavy blur averages a scene toward grey, so without this the backdrop's
        // colour -- the only reason to sample it at all -- is the first thing the blur destroys.
        float lum = luminance(c.rgb);
        c.rgb = mix(vec3(lum), c.rgb, _Saturation);

        // Tint over the backdrop, straight alpha.
        c.rgb = mix(c.rgb, _Tint.rgb, _Tint.a);

#ifdef WITH_SPECULAR
        // Three separate things, not one gloss:
        //   fresnel  -- brightens toward the boundary from any angle
        //   lambert  -- the edge FACING the light is brighter than the one away from it
        //   rim      -- a thin bright line right at the boundary, which is what actually reads as
        //               "this has an edge" rather than "this fades out"
        float fresnel = pow(1.0 - edge, 3.0);
        float lambert = max(0.0, dot(-grad, normalize(_LightDir + vec2(1e-6, 0.0))));
        float rim     = 1.0 - smoothstep(0.0, 0.18, edge);
        c.rgb += vec3(fresnel * (0.35 + 0.65 * lambert) + rim * 0.35) * _Specular;
#endif

#ifdef WITH_NOISE
        // Grain, and it is not decoration: over a flat backdrop -- an empty sky, the harness's ground
        // -- a blur has nothing to work with and the surface reads as a slightly-wrong rectangle.
        // Texture is what makes it read as a material. It also breaks up banding on a gradient.
        c.rgb += (hash12(gl_FragCoord.xy) - 0.5) * _Noise;
#endif

        // Tint by background-color on RGB only: its alpha is an ambient multiplier for the element, not
        // a second opinion about the backdrop's opacity.
        c.rgb *= i.color.rgb;
        c.a = coverage * _LayerOpacity * i.color.a;
        fragColor = c;
    }
}
