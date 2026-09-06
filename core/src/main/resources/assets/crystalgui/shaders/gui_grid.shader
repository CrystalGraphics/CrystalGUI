// crystalgui:shaders/gui_grid.shader
//
// A RULED GRID IN ONE DRAW, analytically antialiased, correct at any scale and at any cell size --
// including cells smaller than a pixel, which is where every naive grid falls apart.
//
// PORTED from the "Pristine Grid" technique: Ben Golus, "The Best Darn Grid Shader (Yet)"
// (https://bgolus.medium.com/the-best-darn-grid-shader-yet-727f9278b9d8, 2023). The author's own
// gist carries NO licence statement, so nothing here is copied from it; the code below follows the
// CC0 Godot port (godotshaders.com/shader/the-best-darn-grid-shader-yet-for-godot) and the MIT
// WebGPU one (github.com/toji/pristine-grid-webgpu), both of which implement the same published
// technique. Credit for the technique is Golus's. See THIRD-PARTY.md.
//
// WHY NOT smoothstep(fwidth(fract(uv))). That is the grid everybody writes first and it is wrong in
// three ways at once, all of which show on a UI that can be scaled: the line THICKENS as the cell
// shrinks (the smoothstep band is a fraction of a cell, not a count of pixels), it MOIRES once a cell
// is near a pixel, and it CUTS OUT entirely once a cell is under a pixel, so a zoomed-out grid goes
// from a haze to nothing with a visible boundary in between. The three corrections below are the
// whole of the technique.
//
//   1. DERIVATIVES PER AXIS, by length rather than by component. uvDeriv takes the length of each
//      axis's (ddx, ddy) pair, so a rotated or sheared grid measures its own footprint correctly
//      instead of assuming the axes are still aligned to the screen. Our UI is usually axis-aligned
//      and this costs nothing there; it is what keeps a grid inside a `transform: rotate()` right.
//
//   2. A LINE NEVER DRAWN THINNER THAN A PIXEL, faded instead. drawWidth clamps the target width up
//      to one pixel's footprint, and then `targetWidth / drawWidth` scales the coverage back down --
//      so a half-pixel line is drawn one pixel wide at half strength. That preserves the grid's
//      average brightness where sampling a thinner line would alias it into a sparkling mess. It is
//      the same reasoning behind a mip chain, done analytically.
//
//   3. A CLEAN LIMIT. Once a cell is under about a pixel (uvDeriv > 0.5) the fragment fades to the
//      grid's own average density (targetWidth) rather than to an arbitrary sample of it, so the
//      grid dissolves into a flat wash instead of into moire.
//
// invertLine is the fourth piece and only matters for wide lines: past half a cell it is cheaper and
// more accurate to draw the GAPS and invert, because the smoothstep band would otherwise overlap
// itself. A UI grid never gets there, and it is kept because removing it would leave a shader that
// is silently wrong for a value a caller may legitimately pass.
//
// PREMULTIPLIED out, under ONE / ONE_MINUS_SRC_ALPHA -- the layer blit's blend and gui_gradient's,
// not gui_quad's. A grid is nearly always drawn translucent over something.

#type pos2_uv2_col4ub
#pragma cg_use quad
#pragma cg_feature WITH_MASK

#include "crystalgraphics:shaders/lib/sdf.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    // The line colour, PREMULTIPLIED (rgb already scaled by a), matching gui_gradient's stops.
    _Color         ("Line colour (premultiplied)",  vec4)  = (1.0, 1.0, 1.0, 1.0)
    // Cell size in the same pixels _BoxSize is in. Separate axes, so a grid may be non-square.
    _Cell          ("Cell size (px)",               vec2)  = (16.0, 16.0)
    // Line thickness in those same pixels. The shader converts to cell fractions itself, which is
    // what keeps a 1px line ONE pixel at any cell size and any uiScale.
    _LineWidth     ("Line width (px)",              vec2)  = (1.0, 1.0)
    _BoxSize       ("Box size (px)",                vec2)  = (0.0, 0.0)
    // WITH_MASK only: the element's resolved radii, CSS order, one vec4 per axis.
    _CornerRadiusX ("Corner radii X (TL,TR,BR,BL)", vec4)  = (0.0, 0.0, 0.0, 0.0)
    _CornerRadiusY ("Corner radii Y (TL,TR,BR,BL)", vec4)  = (0.0, 0.0, 0.0, 0.0)
    _LayerOpacity  ("Layer opacity",                float) = 1.0
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

    // NO HELPER FUNCTION, and that is not a style choice. A fragment-only helper cannot be declared
    // in a Pass at all: the region above `void vertex` is hoisted into BOTH generated stages, and
    // `partitionGlobalDecls` hoists every #-line out of a Pass body to the TOP of the generated
    // source -- above the `#define CG_VERTEX_STAGE` the compiler emits -- so an `#ifndef` guard
    // written here is evaluated before the define exists and lets the body through into the vertex
    // stage regardless. ShippedShaderStagePurityTest caught exactly that, naming dFdx.
    //
    // A guard only works from an INCLUDED lib, which is read after the define (that is why sdf.glsl's
    // own `#ifndef` works). The grid is one shader's business, so it is inlined into fragment()
    // instead of inventing a lib for it -- which is also what gui_gradient does with its ramp.

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv    = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 cell = max(_Cell, vec2(0.5));
        // uv is 0..1 over the box, so this is the position in CELLS -- the space `fract` and the
        // derivatives below both want. lineWidth likewise becomes a fraction of a cell, which is what
        // keeps a 1px line ONE pixel at any cell size and any uiScale.
        vec2 uv = i.uv * _BoxSize / cell;
        vec2 lineWidth = clamp(_LineWidth / cell, vec2(0.0), vec2(1.0));

        vec2 ddxUv = dFdx(uv);
        vec2 ddyUv = dFdy(uv);
        // PER AXIS, BY LENGTH: how far this axis's coordinate travels across one pixel, whichever way
        // that pixel is oriented. Component-wise derivatives under-report a rotated grid.
        vec2 uvDeriv = vec2(length(vec2(ddxUv.x, ddyUv.x)), length(vec2(ddxUv.y, ddyUv.y)));

        bvec2 invertLine = bvec2(lineWidth.x > 0.5, lineWidth.y > 0.5);
        vec2 targetWidth = vec2(
                invertLine.x ? 1.0 - lineWidth.x : lineWidth.x,
                invertLine.y ? 1.0 - lineWidth.y : lineWidth.y);

        // Never thinner than the pixel footprint, never wider than half a cell.
        vec2 drawWidth = clamp(targetWidth, uvDeriv, vec2(0.5));
        // 1.5 pixels of gradient either side: enough to cover the sample's footprint without the
        // smear a wider band gives a line that is only a pixel or two across to begin with.
        vec2 lineAA = uvDeriv * 1.5;

        vec2 gridUv = abs(fract(uv) * 2.0 - 1.0);
        gridUv.x = invertLine.x ? gridUv.x : 1.0 - gridUv.x;
        gridUv.y = invertLine.y ? gridUv.y : 1.0 - gridUv.y;

        vec2 grid = smoothstep(drawWidth + lineAA, drawWidth - lineAA, gridUv);

        // THE STEP THE NAIVE SHADER HAS NO ANSWER FOR: a line forced wider than it should be is faded
        // by exactly the factor it was widened, so its total energy is unchanged and a sub-pixel grid
        // reads as a fainter grid rather than as a sparkling one.
        grid *= clamp(targetWidth / drawWidth, vec2(0.0), vec2(1.0));
        // Under about a cell per pixel, dissolve to the grid's own average density rather than to an
        // arbitrary sample of it -- the difference between fading out and moire.
        grid = mix(grid, targetWidth, clamp(uvDeriv * 2.0 - 1.0, vec2(0.0), vec2(1.0)));
        grid.x = invertLine.x ? 1.0 - grid.x : grid.x;
        grid.y = invertLine.y ? 1.0 - grid.y : grid.y;

        // Union of the two axes, without double-darkening where they cross.
        float coverage = mix(grid.x, 1.0, grid.y);

        vec4 c = _Color;
        // The ambient tint (background-color, a cross-fade's opacity) is STRAIGHT alpha over a
        // premultiplied colour: rgb scales by the tint's rgb and its alpha, alpha by alpha alone.
        c *= vec4(i.color.rgb * i.color.a, i.color.a);

        float shape = coverage;

#ifdef WITH_MASK
        vec2 halfSize = _BoxSize * 0.5;
        vec2 localPos = (i.uv - 0.5) * _BoxSize;
        float dist = sdf_rounded_box(localPos, halfSize, _CornerRadiusX, _CornerRadiusY);
        shape *= sdf_coverage(dist);
#endif

        fragColor = c * shape * _LayerOpacity;
    }
}
