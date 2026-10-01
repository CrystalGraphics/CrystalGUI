// crystalgui:shaders/gui_box.shader
//
// Every box the UI draws, in one material: a fill, a texture, a rounded or bordered rect, a nine-slice sprite, a cached
// icon and a composited layer are each a quad whose custom2 names its shape in the recording's CgShapeTable. So they
// batch on one pipeline and part only where their textures differ. Premultiplied out: a straight colour is multiplied
// by its alpha here, a layer's texture already is.
//
// Shapes share one rounded-box SDF for the outline, the border's inner edge and every fill, so corners clip all of them
// alike; radii are per corner and elliptical (TL, TR, BR, BL, CSS order), as UIElement's rounded hit-test reads them.
// Off-axis, the textured fills take the texel filter (lib/texel.glsl) and a nine-slice supersamples its seams.
//
// _LayerOpacity fades a whole draw (CgUiPaintContext.withLayerOpacity); an effect node fades it from the compositor.

#type pos2_uv2_col4ub
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_use shape

#include "crystalgraphics:shaders/lib/texel.glsl"

#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex ("Main Texture", sampler2D) = "white"
    _LayerOpacity ("Layer Opacity", float) = 1.0
}

struct v2f {
    // The quad's parameter, grown by half a pixel when rotated so its edge, and a shape's SDF ramp, are not cut off by
    // the quad's own edge -- CG_QUAD_EDGE_* in env/buffer/quad.glsl. uv is derived from it per fragment.
    vec2 param;
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

    // Parameter within a tiled nine-slice interval, plus a 0/1 mask SPACE uses to blank its gaps; mirrors CgUiRepeat:
    //   STRETCH      -> one tile across the whole span
    //   REPEAT/ROUND -> fract(local * n / span); they differ only in n, computed CPU-side
    //   SPACE        -> n whole tiles of srcSize separated by n+1 equal gaps
    float cg_sliceParam(float local, float span, float mode, float n, float srcSize, out float keep) {
        keep = 1.0;
        if (span <= 0.0) return 0.0;
        if (mode < 0.5 || n <= 0.0) return clamp(local / span, 0.0, 1.0); // STRETCH
        if (mode < 2.5) { // REPEAT (1) or ROUND (2)
            return clamp(fract(local * n / span), 0.0, 1.0);
        }
        // SPACE
        float gap = (span - n * srcSize) / (n + 1.0);
        float cycle = srcSize + gap;
        float offset = local - gap;
        if (offset < 0.0) { keep = 0.0; return 0.0; }
        float within = offset - floor(offset / cycle) * cycle;
        if (within > srcSize) { keep = 0.0; return 0.0; }
        return srcSize > 0.0 ? within / srcSize : 0.0;
    }

    /** Where the nine regions meet, in box-local pixels: (x1, y1, x2, y2). */
    vec4 cg_sliceBounds(int shape, vec2 boxSize) {
        vec4 border = SHAPE_DATA(shape).sliceBorder;
        float scaleX = min(1.0, boxSize.x / max(1.0, border.x + border.z));
        float scaleY = min(1.0, boxSize.y / max(1.0, border.y + border.w));
        return vec4(border.x * scaleX, border.y * scaleY,
                    boxSize.x - border.z * scaleX, boxSize.y - border.w * scaleY);
    }

    // The nine-region remap: box-local pixels in, atlas UV in .xy and the keep/centre mask in .z. Samples nothing,
    // since it is compiled into the vertex stage as well and the fragment evaluates it at more than one position.
    vec3 cg_slice9Uv(int shape, vec2 boxSize, vec2 p) {
        vec4 b = cg_sliceBounds(shape, boxSize);
        vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
        vec4 innerUv = SHAPE_DATA(shape).sliceInnerUv;
        vec4 tiles = SHAPE_DATA(shape).sliceTiles;
        vec4 mode = SHAPE_DATA(shape).sliceMode;
        float x1 = b.x, y1 = b.y, x2 = b.z, y2 = b.w;
        float keepX = 1.0;
        float keepY = 1.0;
        float centerMask = 1.0;

        float u;
        if (p.x < x1) {
            u = mix(outerUv.x, innerUv.x, x1 > 0.0 ? clamp(p.x / x1, 0.0, 1.0) : 0.0);
        } else if (p.x > x2) {
            float denom = boxSize.x - x2;
            u = mix(innerUv.z, outerUv.z, denom > 0.0 ? clamp((p.x - x2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float t = cg_sliceParam(p.x - x1, x2 - x1, mode.x, tiles.x, tiles.z, keepX);
            u = mix(innerUv.x, innerUv.z, t);
        }

        float v;
        if (p.y < y1) {
            v = mix(outerUv.y, innerUv.y, y1 > 0.0 ? clamp(p.y / y1, 0.0, 1.0) : 0.0);
        } else if (p.y > y2) {
            float denom = boxSize.y - y2;
            v = mix(innerUv.w, outerUv.w, denom > 0.0 ? clamp((p.y - y2) / denom, 0.0, 1.0) : 0.0);
        } else {
            float t = cg_sliceParam(p.y - y1, y2 - y1, mode.y, tiles.y, tiles.w, keepY);
            v = mix(innerUv.y, innerUv.w, t);
        }

        // An unfilled centre blanks the centre region only (both axes inside), leaving the edges.
        if (mode.z < 0.5 && p.x >= x1 && p.x <= x2 && p.y >= y1 && p.y <= y2) {
            centerMask = 0.0;
        }
        return vec3(u, v, keepX * keepY * centerMask);
    }

    // Straight colour to premultiplied, clamped first as a blend unit clamps its inputs: an icon raster's summed coverage
    // is a float texture and runs past 1.
    vec4 cg_premultiply(vec3 rgb, float a) {
        float alpha = clamp(a, 0.0, 1.0);
        return vec4(clamp(rgb, 0.0, 1.0) * alpha, alpha);
    }

    void vertex(out v2f o) {
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        // The rounded clip this draw is inside (CgClipTable); read before anything branches, since it takes derivatives.
        float boxClip = CG_CLIP_QUAD_COVERAGE;
        // A shape is one per instance, so every branch below is uniform across a primitive and its derivatives defined.
        int shape = int(CG_QUAD_CUSTOM2 + 0.5);
        vec4 meta = SHAPE_DATA(shape).meta;
        int kind = int(meta.x + 0.5);
        vec2 uv = CG_QUAD_EDGE_UV(i.param);

        if (kind == CG_SHAPE_PLAIN) {
            // Rotated, the texels get the same treatment as the outline -- lib/texel.glsl.
            vec4 texel = CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, uv);
            vec4 plain = texel * i.color;
            float a = plain.a * (_LayerOpacity * CG_QUAD_EDGE_COVERAGE(i.param) * boxClip) * CG_QUAD_OPACITY;
            fragColor = cg_premultiply(plain.rgb, a);
            return;
        }
        if (kind == CG_SHAPE_PREMULTIPLIED) {
            // A layer is a screen-resolution picture, so no texel filter -- only the edge, for a snapshot drawn rotated.
            fragColor = texture(_MainTex, uv) * i.color;
            fragColor *= CG_QUAD_EDGE_COVERAGE(i.param);
            fragColor *= _LayerOpacity * CG_QUAD_OPACITY * boxClip;
            return;
        }

        // Unclamped for the SDF, since past the box is exactly what it is there to cut; clamped for the texture fills,
        // so the pad never samples outside the rect.
        vec2 boxSize = meta.zw;
        vec2 uvUnclamped = mix(QUAD_DATA(CG_INSTANCE_ID).uv0, QUAD_DATA(CG_INSTANCE_ID).uv1, i.param);
        vec2 halfSize = boxSize * 0.5;
        vec2 localPos = (uvUnclamped - 0.5) * boxSize;
        vec4 radiiX = SHAPE_DATA(shape).radiiX;
        vec4 radiiY = SHAPE_DATA(shape).radiiY;
        float dist = sdf_rounded_box(localPos, halfSize, radiiX, radiiY);
        // A rotated box takes the wider reconstruction filter its edges and texels get; at rest, one pixel.
        float ramp = CG_QUAD_EDGE_ROTATED ? CG_QUAD_EDGE_FILTER : 1.0;
        float coverage = sdf_coverage(dist, ramp);

        // Derivatives outside any branch: a seam is exactly where neighbouring fragments disagree about one.
        vec2 p = uv * boxSize;
        vec2 ddx = dFdx(p), ddy = dFdy(p);

        vec4 fillColor;
        if (kind == CG_SHAPE_NINE_SLICE) {
            // Continuous-per-pixel equivalent of nine quads: remap this pixel's box-local position into one of the nine
            // atlas regions -- see cg_slice9Uv.
            vec4 outerUv = SHAPE_DATA(shape).sliceOuterUv;
            vec4 spriteRect = vec4(min(outerUv.xy, outerUv.zw), max(outerUv.xy, outerUv.zw));
            vec3 slice = cg_slice9Uv(shape, boxSize, p);
            // All four channels, never alpha alone: the border's mix() below interpolates toward fillColor on straight
            // alpha, so a colour left in an alpha-zeroed fill would drag the border's inner edge into a fringe.
            fillColor = (CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, slice.xy, spriteRect, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, slice.xy)) * slice.z;

            // A SEAM BETWEEN TWO OF THE NINE REGIONS IS GEOMETRY, NOT A TEXEL EDGE: the texel filter reconstructs from
            // fwidth in texel space, and at a seam the two sides' gradients differ by the centre's stretch. The seam is
            // a straight line in box-local pixels, which are smooth, so supersample the remap over this pixel's own
            // footprint on the one-pixel line where regions meet: 8x8, since a box filter quantises coverage to 1/N^2.
            vec4 sb = cg_sliceBounds(shape, boxSize);
            vec2 fw = abs(ddx) + abs(ddy);
            bool onSeam = min(min(abs(p.x - sb.x), abs(p.x - sb.z)) - fw.x,
                              min(abs(p.y - sb.y), abs(p.y - sb.w)) - fw.y) < 0.0;
            if (CG_QUAD_EDGE_ROTATED && onSeam) {
                vec4 acc = vec4(0.0);
                for (int sy = 0; sy < 8; ++sy) {
                    for (int sx = 0; sx < 8; ++sx) {
                        vec2 o = (vec2(float(sx), float(sy)) - 3.5) * 0.125;
                        vec3 sub = cg_slice9Uv(shape, boxSize, p + o.x * ddx + o.y * ddy);
                        acc += texture(_MainTex, sub.xy) * sub.z;
                    }
                }
                fillColor = acc * (1.0 / 64.0);
            }
        } else if (kind == CG_SHAPE_TEXTURE) {
            fillColor = CG_QUAD_EDGE_ROTATED
                    ? cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                    : texture(_MainTex, uv);
        } else {
            fillColor = SHAPE_DATA(shape).fillColor;
        }

        // The quad's colour tints the fill only, so it never bleeds into the border's own colour below.
        fillColor *= i.color;

        vec4 color = fillColor;
        int flags = int(meta.y + 0.5);
        if ((flags & CG_SHAPE_BORDER) != 0) {
            // THE INNER EDGE IS ITS OWN ROUNDED BOX, as CSS Backgrounds 3 section 5.3 draws it: the outer box inset by each
            // side's width, each corner's inner radius its outer one less the two sides meeting there.
            vec4 w = SHAPE_DATA(shape).borderWidths;
            vec2 innerHalf = max(halfSize - vec2(w.x + w.z, w.y + w.w) * 0.5, vec2(0.0));
            vec2 innerShift = vec2(w.x - w.z, w.y - w.w) * 0.5;
            vec4 innerRx = max(radiiX - vec4(w.x, w.z, w.z, w.x), vec4(0.0));
            vec4 innerRy = max(radiiY - vec4(w.y, w.y, w.w, w.w), vec4(0.0));
            float innerDist = sdf_rounded_box(localPos - innerShift, innerHalf, innerRx, innerRy);
            float innerCoverage = sdf_coverage(innerDist, ramp);
            vec4 edgeColor = SHAPE_DATA(shape).borderColor;
            if ((flags & CG_SHAPE_SPLIT_BORDER) != 0) {
                // Which of the FOUR edges this pixel belongs to, not which half of the box: a pixel nearer the top or
                // bottom boundary than the sides takes that edge's colour (the UI's projection is Y-down), and the sides
                // keep the uniform colour -- Unity's inset bevel.
                float dx = halfSize.x - abs(localPos.x);
                float dy = halfSize.y - abs(localPos.y);
                if (dy < dx) edgeColor = localPos.y < 0.0 ? SHAPE_DATA(shape).borderTop : SHAPE_DATA(shape).borderBottom;
            }
            color = mix(edgeColor, fillColor, innerCoverage);
        }

        color.a *= coverage * _LayerOpacity;
        fragColor = cg_premultiply(color.rgb, color.a * boxClip * CG_QUAD_OPACITY);
    }
}
