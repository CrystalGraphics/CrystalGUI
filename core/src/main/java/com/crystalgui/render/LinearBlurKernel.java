package com.crystalgui.render;

import java.util.Arrays;

/**
 * One axis of a Gaussian as linearly-sampled tap pairs: each bilinear fetch between two texels reads both
 * at once, so a kernel of {@code 2r + 1} taps costs {@code r + 1} fetches.
 *
 * <p>Ported from Skia's {@code SkShaderBlurAlgorithm::Compute1DBlurLinearKernel} and the 1D case of
 * {@code Compute2DBlurKernel} ({@code src/core/SkBlurEngine.cpp}, BSD-3-Clause, see THIRD-PARTY.md), packed
 * as its 1D blur effect reads them: {@code offsetsAndKernel[i] = (offset, weight, offset, weight)}.</p>
 *
 * <pre>{@code
 * float[] packed = new float[4 * LinearBlurKernel.MAX_PAIRS];
 * int pairs = LinearBlurKernel.compute(sigma, radius, packed);
 * // gui_blur.shader (LINEAR_KERNEL): sum += w0 * tap(uv + o0 * step) + w1 * tap(uv + o1 * step), per pair
 * }</pre>
 *
 * <p>The weights sum to one, so the shader divides by nothing. Offsets are in texels along the axis.</p>
 */
final class LinearBlurKernel {

    /** Pairs for the largest radius the backdrop asks for, {@link CgUiBackdrop#MAX_KERNEL_RADIUS}. */
    static final int MAX_PAIRS = (CgUiBackdrop.MAX_KERNEL_RADIUS + 2) / 2;

    private LinearBlurKernel() {
    }

    /**
     * Fills {@code offsetsAndKernel} (at least {@code 4 * MAX_PAIRS} long) and answers how many pairs are
     * live; the rest are zero weights.
     */
    static int compute(float sigma, int radius, float[] offsetsAndKernel) {
        if (radius < 0 || radius > CgUiBackdrop.MAX_KERNEL_RADIUS) {
            throw new IllegalArgumentException("radius out of range: " + radius);
        }
        Arrays.fill(offsetsAndKernel, 0, 4 * MAX_PAIRS, 0f);

        // Compute2DBlurKernel with a zero second axis: exp(-x^2 / (2 sigma^2)) at integer x, normalised.
        int width = 2 * radius + 1;
        float[] fullKernel = new float[width + 1];
        if (radius == 0 || sigma <= 0f) {
            fullKernel[radius] = 1f;
        } else {
            float twoSigmaSqrd = 2f * sigma * sigma;
            float sum = 0f;
            for (int x = -radius; x <= radius; x++) {
                float value = (float) Math.exp(-(x * x) / twoSigmaSqrd);
                fullKernel[x + radius] = value;
                sum += value;
            }
            float invSum = 1f / sum;
            for (int i = 0; i < width; i++) fullKernel[i] *= invSum;
        }

        // Compute1DBlurLinearKernel.
        int halfSize = radius + 1;   // LinearKernelWidth
        float[] kernel = new float[halfSize];
        float[] offsets = new float[halfSize];
        int halfRadius = halfSize / 2;
        int lowIndex = halfRadius - 1;
        int index = radius;
        if ((radius & 1) == 1) {
            // The centre texel gets sampled twice, so halve its influence for each sample.
            newWeight(kernel, offsets, halfRadius, fullKernel[index] * 0.5f, fullKernel[index + 1]);
            kernel[lowIndex] = kernel[halfRadius];
            offsets[lowIndex] = -offsets[halfRadius];
            index++;
            lowIndex--;
        } else {
            kernel[halfRadius] = fullKernel[index];
            offsets[halfRadius] = 0f;
        }
        index++;
        for (int i = halfRadius + 1; i < halfSize; index += 2, i++, lowIndex--) {
            newWeight(kernel, offsets, i, fullKernel[index], fullKernel[index + 1]);
            offsets[i] += (float) (index - radius);
            kernel[lowIndex] = kernel[i];
            offsets[lowIndex] = -offsets[i];
        }

        int pairs = (halfSize + 1) / 2;
        for (int i = 0; i < halfSize; i++) {
            int slot = (i / 2) * 4 + ((i & 1) == 0 ? 0 : 2);
            offsetsAndKernel[slot] = offsets[i];
            offsetsAndKernel[slot + 1] = kernel[i];
        }
        return pairs;
    }

    /** {@code get_new_weight}: W' = Wi + Wj, x = Wj / (Wi + Wj). */
    private static void newWeight(float[] kernel, float[] offsets, int at, float wi, float wj) {
        kernel[at] = wi + wj;
        offsets[at] = wi + wj == 0f ? 0f : wj / (wi + wj);
    }
}
