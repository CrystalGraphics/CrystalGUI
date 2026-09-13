package com.crystalgui.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The linear-sampled pairs must reproduce the per-texel Gaussian exactly: a bilinear fetch at
 * {@code i + f} with weight {@code w} is {@code w * (1 - f)} of texel {@code i} and {@code w * f} of texel
 * {@code i + 1}, so spreading every pair back onto texels has to give the kernel it was built from.
 */
public class LinearBlurKernelTest {

    @Test
    public void pairsSpreadBackOntoTheDiscreteGaussian() {
        float[] packed = new float[4 * LinearBlurKernel.MAX_PAIRS];
        for (int radius = 0; radius <= CgUiBackdrop.MAX_KERNEL_RADIUS; radius++) {
            float sigma = Math.max(0.25f, radius / 3f);
            int pairs = LinearBlurKernel.compute(sigma, radius, packed);
            assertEquals("pair count for radius " + radius, (radius + 2) / 2, pairs);

            double[] texels = new double[2 * radius + 2];
            double sum = 0.0;
            for (int p = 0; p < LinearBlurKernel.MAX_PAIRS; p++) {
                for (int half = 0; half < 2; half++) {
                    float offset = packed[4 * p + 2 * half];
                    float weight = packed[4 * p + 2 * half + 1];
                    if (weight == 0f) continue;
                    double position = offset + radius;
                    int base = (int) Math.floor(position);
                    double frac = position - base;
                    texels[base] += weight * (1.0 - frac);
                    if (frac > 0.0) texels[base + 1] += weight * frac;
                    sum += weight;
                }
            }
            assertEquals("weights sum to one for radius " + radius, 1.0, sum, 1e-5);

            double norm = 0.0;
            for (int x = -radius; x <= radius; x++) norm += Math.exp(-(x * x) / (2.0 * sigma * sigma));
            for (int x = -radius; x <= radius; x++) {
                double expected = radius == 0 ? 1.0 : Math.exp(-(x * x) / (2.0 * sigma * sigma)) / norm;
                assertEquals("texel " + x + " at radius " + radius, expected, texels[x + radius], 1e-5);
            }
        }
    }
}
