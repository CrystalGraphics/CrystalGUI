// One axis of a separable Gaussian, its kernel from sigma: taps one source texel apart, ceil(3 sigma) either side.
// The caller scales the source down first so sigma stays small (CgUiBackdrop, Skia's rule). Weights are incremental
// (GPU Gems 3, ch. 40) and renormalised over the taps used, or a truncated kernel tints. Premultiplied throughout.

#type none
#pragma cg_use quad
// A constant bound the radius breaks out of: some drivers unroll nothing they cannot see the end of. Keep in step with
// CgUiBackdrop.MAX_KERNEL_RADIUS.
#define CG_BLUR_MAX_RADIUS 16
// Skia's linear-sampled kernel: each pair of taps is one bilinear fetch, offset and weighted on the CPU
// (LinearBlurKernel, from SkShaderBlurAlgorithm::Compute1DBlurLinearKernel). Without it, the incremental kernel.
#pragma cg_feature LINEAR_KERNEL
#pragma cg_permutation LINEAR_KERNEL
#define CG_BLUR_MAX_PAIRS 9

Tags { "RenderType" = "Transparent" }
Queue = "Overlay"

Properties {
    _MainTex   ("Source",                    sampler2D) = "white"
    // ONE source texel along the axis of this pass, in UV; zero on the other axis.
    _Step      ("Per-tap UV step",           vec2)      = (0.0, 0.0)
    // Sigma in source texels. The caller keeps it small by scaling the source down first.
    _Sigma     ("Sigma (texels)",            float)     = 2.0
    // Taps either side of the centre: ceil(3 * sigma), at most CG_BLUR_MAX_RADIUS.
    _Radius    ("Taps per side",             float)     = 6.0
    // The region of the source holding real content, as (u0, v0, u1, v1). Taps clamp into it.
    _Bounds    ("Valid source rect",         vec4)      = (0.0, 0.0, 1.0, 1.0)
    // LINEAR_KERNEL: (offset, weight, offset, weight) per pair, offsets in texels; _Pairs of them live.
    _Pairs     ("Live kernel pairs",         float)     = 0.0
    _Kernel0   ("Kernel pair 0",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel1   ("Kernel pair 1",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel2   ("Kernel pair 2",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel3   ("Kernel pair 3",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel4   ("Kernel pair 4",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel5   ("Kernel pair 5",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel6   ("Kernel pair 6",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel7   ("Kernel pair 7",             vec4)      = (0.0, 0.0, 0.0, 0.0)
    _Kernel8   ("Kernel pair 8",             vec4)      = (0.0, 0.0, 0.0, 0.0)
}

struct v2f {
    vec2 uv;
};

Pass {
    Tags { "LightMode" = "Forward" }

    RenderState {
        // A filter, not a composite: every pass overwrites its target.
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // Helpers go above `void vertex`: what sits between vertex() and fragment() reaches the vertex stage alone, and
    // the fragment compile fails on it. A tap past _Bounds is mirrored back in, as Chromium does: clamping streaked
    // the edge row along every tap beyond it.
    vec4 cg_tap(vec2 uv) {
        vec2 span = max(_Bounds.zw - _Bounds.xy, vec2(1e-6));
        vec2 folded = abs(mod(uv - _Bounds.xy, 2.0 * span) - span);
        return texture(_MainTex, _Bounds.zw - folded);
    }

    void vertex(out v2f o) {
        gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
        o.uv = CG_QUAD_UV;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef LINEAR_KERNEL
        // Skia's 1D blur effect: sum += s.y * child(coord + s.x * dir) + s.w * child(coord + s.z * dir).
        vec4 pairs[CG_BLUR_MAX_PAIRS] = vec4[CG_BLUR_MAX_PAIRS](
                _Kernel0, _Kernel1, _Kernel2, _Kernel3, _Kernel4, _Kernel5, _Kernel6, _Kernel7, _Kernel8);
        vec4 linearSum = vec4(0.0);
        for (int k = 0; k < CG_BLUR_MAX_PAIRS; k++) {
            if (float(k) >= _Pairs) break;
            vec4 s = pairs[k];
            linearSum += s.y * cg_tap(i.uv + s.x * _Step);
            linearSum += s.w * cg_tap(i.uv + s.z * _Step);
        }
        // Premultiplied, as below: the weights already sum to one.
        fragColor = linearSum;
#else
        float sigma = max(_Sigma, 0.25);
        // Incremental Gaussian: g.x the weight at this tap, g.y the ratio to the next, g.z the ratio's growth.
        vec3 g;
        g.x = 1.0 / (sqrt(2.0 * 3.14159265) * sigma);
        g.y = exp(-0.5 / (sigma * sigma));
        g.z = g.y * g.y;

        vec4 sum = cg_tap(i.uv) * g.x;
        float weight = g.x;
        for (int k = 1; k <= CG_BLUR_MAX_RADIUS; k++) {
            if (float(k) > _Radius) break;
            g.xy *= g.yz;
            vec2 d = _Step * float(k);
            sum += (cg_tap(i.uv + d) + cg_tap(i.uv - d)) * g.x;
            weight += 2.0 * g.x;
        }

        // Alpha is averaged with the colour: UI targets hold premultiplied colour, which is linear under filtering.
        // Writing a = 1 mixed black in from every partly covered pixel. The consumer un-premultiplies
        // (gui_backdrop_filter.shader's cg_backdrop).
        fragColor = sum / weight;
#endif
    }
}
