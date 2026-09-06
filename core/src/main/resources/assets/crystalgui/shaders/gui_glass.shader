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
    // How much of the backdrop's BRIGHTNESS is replaced by the tint's (its hue and saturation stay).
    // 0 leaves the alpha tint alone; WinUI's acrylic runs this at 0.96 and Mica at 1.0.
    _Luminosity ("Luminosity blend toward the tint", float) = 0.0
    _Bezel      ("Bezel width (px)",       float) = 8.0
    _Ior        ("Index of refraction",    float) = 1.5
    _Specular   ("Specular strength",      float) = 0.0
    _Glow       ("Broad glow along the light axis", float) = 0.10
    _EdgeHighlight ("Thin rim band strength",       float) = 0.25
    _EdgeWidth  ("Rim band width (px)",             float) = 3.0
    _RimAmbient ("Rim ambient (0 directional, 1 even)", float) = 0.5
    _Chromatic  ("Chromatic aberration",            float) = 0.20
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

    // W3C compositing's non-separable LUMINOSITY blend -- the layer Windows' acrylic and Mica are
    // mostly made of: the backdrop keeps its hue and saturation and takes the tint's brightness, so
    // what a colourful scene contributes is a temperature and never a colour. Lum and ClipColor are the
    // spec's own, with its 0.3 / 0.59 / 0.11 weights. @see docs/CGUI_MODERN_UI_RENDERING_RESEARCH.md section 1.2
    //
    // ABOVE `void vertex`, with every other helper, because the compiler hoists this region into BOTH
    // generated stages -- and safe there because none of it names a fragment-only builtin.
    float cg_lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
    vec3 cg_clipColor(vec3 c) {
        float l = cg_lum(c);
        float n = min(c.r, min(c.g, c.b));
        float x = max(c.r, max(c.g, c.b));
        if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-5);
        if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-5);
        return c;
    }
    vec3 cg_setLum(vec3 c, float l) { return cg_clipColor(c + vec3(l - cg_lum(c))); }

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

    /**
     * One lens tap: the backdrop at {@code uv}, sharp at the bezel and blurred in the interior.
     *
     * <p>Real glass is thickest and least diffuse at its edge, and the edge is also where the
     * refraction is -- blurring the very thing being bent throws the lens away.</p>
     */
    vec4 cg_lensTap(vec2 uv, float edge) {
        vec2 b = cg_backdropUv(uv);
        return mix(cg_backdrop(_SharpTex, b), cg_backdrop(_MainTex, b), smoothstep(0.0, 1.0, edge));
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
        // PIXEL-EXACT COVERAGE, not the shared sdf_coverage ramp. That one is a smoothstep two
        // pixels wide centred on the boundary, so the last pixel row INSIDE an axis-aligned edge is only
        // ~84% covered -- which for an opaque fill over its own background is invisible anti-aliasing,
        // and for a material that REPLACES what is behind it is a 16% bleed of the unblurred backdrop
        // along every straight edge. On the taskbar the top edge hides under the hairline and the sides
        // are the screen's; the bottom lip showed a row of sharp pixels under the bar. `dist / fwidth`
        // is the distance in SURFACE pixels along the edge's own gradient, so the ramp is exactly one
        // pixel wide whatever uiScale is: a straight edge that lies on a pixel boundary covers its last
        // row fully, and a rounded corner still gets one pixel of anti-aliasing.
        float coverage = clamp(0.5 - dist / max(fwidth(dist), 1e-4), 0.0, 1.0);

        // How far inside the bezel this pixel is: 0 at the boundary, 1 where the glass goes flat.
        float bezel = max(1.0, _Bezel);
        float edge  = clamp(-dist / bezel, 0.0, 1.0);

        vec2 disp = vec2(0.0, 0.0);
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
        disp = grad * shift / max(vec2(1.0), _BoxSize);
#endif

#ifdef WITH_CHROMATIC
        // CHROMATIC ABERRATION IS THREE TAPS AT THREE DISPLACEMENT SCALES, one per channel -- not one
        // tap tinted three ways. A prism separates colours because each wavelength REFRACTS BY A
        // DIFFERENT ANGLE, so the only faithful model is to run the displacement three times and keep
        // one channel from each. The staggered factors are the reference implementation's:
        // red bends most, blue least, green between.
        //
        // The first version scaled the red and blue taps by 0.985 and 1.015 -- a 3% spread, where the
        // reference's default is 20%. At that size the fringe is invisible at any radius anybody would
        // use, which reads as the feature not being wired up rather than as being too subtle.
        vec4 cR = cg_lensTap(i.uv + disp * (1.0 + 0.20 * _Chromatic), edge);
        vec4 cG = cg_lensTap(i.uv + disp * (1.0 + 0.10 * _Chromatic), edge);
        vec4 cB = cg_lensTap(i.uv + disp, edge);
        vec4 c = vec4(cR.r, cG.g, cB.b, 1.0);
#else
        vec4 c = cg_lensTap(i.uv + disp, edge);
#endif

        // THE BACKDROP IS OPAQUE ONCE UN-PREMULTIPLIED. A capture is transparent wherever nothing
        // has been drawn yet -- the scene's own clear, the gap between one layer's content and the next
        // -- and carrying that alpha through made the glass transparent in exactly those places. On
        // screen that is a panel with a hard-edged hole in it showing the raw backdrop, which reads as a
        // sampling bug rather than as an alpha one, because the hole is full of the very thing the panel
        // is supposed to be showing a treated version of.
        //
        // A pane of glass covers what is behind it. Its own alpha is its SHAPE, applied at the end.
        c.a = 1.0;

        // Saturation lift. A heavy blur averages a scene toward grey, so without this the backdrop's
        // colour -- the only reason to sample it at all -- is the first thing the blur destroys.
        float lum = luminance(c.rgb);
        c.rgb = mix(vec3(lum), c.rgb, _Saturation);

        // LUMINOSITY, then TINT -- WinUI's acrylic effect graph in that order (blur, saturation,
        // luminosity layer, tint layer, noise). The luminosity layer replaces the backdrop's brightness
        // with the tint's and keeps its hue, which is why a Windows surface over a red wallpaper is
        // WARM rather than red; the tint layer then mixes the tint's colour in by its alpha. With
        // _Luminosity at 0 this is the plain alpha tint it always was.
        c.rgb = mix(c.rgb, cg_setLum(c.rgb, cg_lum(_Tint.rgb)), _Luminosity);
        c.rgb = mix(c.rgb, _Tint.rgb, _Tint.a);

#ifdef WITH_SPECULAR
        // THE HIGHLIGHT IS SYMMETRIC ABOUT THE LIGHT AXIS, and getting that wrong is what made the
        // first version read as an EMBOSSED BUTTON rather than as glass.
        //
        // A lambertian surface is bright where it faces the light and dark where it faces away, so
        // `max(0, dot(-n, L))` lights one side and leaves the other flat -- which is a bevel. A lens is
        // not a lambertian surface: light entering one edge leaves through the opposite one, so BOTH
        // ends of the light axis catch it. Both production references model exactly this, each by
        // taking the ABSOLUTE projection onto that axis: the reference map writes the same highlight
        // into the top-left and bottom-right quadrants, and the CSS recreations spell it as a pair of
        // opposed inset shadows (`inset 3px 3px ... rgba(255,255,255,.45)` AND `inset -3px -3px ...`).
        float proj = abs(dot(grad, normalize(_LightDir + vec2(1e-6, 0.0))));

        // TWO TERMS, and the thin one dominates. A broad falloff alone is a glow, which reads as
        // bloom rather than as a surface; the thin band at the boundary is what actually says "this
        // has an edge". The reference's defaults weight them 0.25 rim against 0.10 glow -- the
        // opposite of the first version here, which had the broad term nearly three times the rim.
        float glow = _Glow * pow(proj, 1.5) * (1.0 - edge);

        // `dist` is negative inside, so this is 1 at the boundary and 0 one band-width in. In PIXELS,
        // not in bezel fractions: a rim is a fixed hairline whatever the bezel is doing behind it.
        float band = clamp(1.0 + dist / max(0.5, _EdgeWidth), 0.0, 1.0);

        // THE RIM IS AN EDGE, AND AN EDGE IS LIT FROM EVERYWHERE.
        //
        // Driving the hairline by `pow(proj, 1.5)` alone makes it purely directional, and proj is
        // exactly ZERO at the two corners perpendicular to the light axis -- so on a rounded rect the
        // outline did not merely dim at the top-right and bottom-left, it VANISHED, while all four
        // straight edges (every one of them at proj = 0.707) stayed lit. Only the corners diverge,
        // which is why it reads as two bad corners rather than as a lighting model. A stroke that
        // disappears at opposite corners looks like broken artwork, not like a lit surface, and that
        // is how it was reported.
        //
        // The directional pair is right for the GLOW -- that IS the reference's opposed inset shadows,
        // a broad quadrant highlight. But in every recreation those shadows sit OVER a uniform border,
        // and that border is the term missing here. A real edge picks up the whole environment
        // (Fresnel at a grazing angle is near-total whichever way the light happens to be), so a rim is
        // an ambient floor PLUS a directional pair, never the pair alone.
        //
        // _RimAmbient is that split, and it is a parameter rather than a constant because it is the
        // knob between "lit glass edge" and "even hairline": 0 restores the old fully-directional
        // behaviour exactly, 1 is a perfectly even rim.
        float rimLight = mix(pow(proj, 1.5), 1.0, clamp(_RimAmbient, 0.0, 1.0));
        float rim  = _EdgeHighlight * band * rimLight;

        c.rgb += vec3(glow + rim) * _Specular;
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
