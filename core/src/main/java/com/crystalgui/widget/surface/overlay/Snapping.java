package com.crystalgui.widget.surface.overlay;

/**
 * Where a dragged value settles: a grid, and a tolerance around it.
 *
 * <pre>{@code
 * float x = ctx.snapping().snap(worldX);
 * ctx.snapping().setStep(8f).setEnabled(true);
 * }</pre>
 *
 * <p>Off by default and a pure function when on, so a consumer that wants its own rule — guides, other
 * items' edges — reads the step and does its own arithmetic rather than fighting this.</p>
 */
public final class Snapping {

    private boolean enabled;
    private float step = 8f;

    public boolean isEnabled() {
        return enabled;
    }

    public Snapping setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** World units between grid lines. Must be positive; a step of zero would snap everything to zero. */
    public float step() {
        return step;
    }

    public Snapping setStep(float step) {
        if (step > 0f) this.step = step;
        return this;
    }

    /** {@code value} on the grid, or unchanged while snapping is off. */
    public float snap(float value) {
        return enabled ? Math.round(value / step) * step : value;
    }
}
