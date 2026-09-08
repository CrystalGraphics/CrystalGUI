package com.crystalgui.render.texture;

import java.util.List;

import com.crystalgui.render.CgUiPaintContext;

/**
 * Several drawables in one property — <b>CSS's comma-separated background layers</b>.
 *
 * <pre>{@code
 * background: grid(16, #6EDCD024), shape("checkmark"), linear-gradient(to bottom, #3574F0FF, #2E436EFF);
 * }</pre>
 *
 * <p>Nothing constructs one directly: {@code TextureValue} builds it when a declaration holds more than
 * one layer, so any property taking a drawable takes a stack for free.</p>
 *
 * <h3>The first layer is the TOP one</h3>
 *
 * <p>CSS's order, and it reads backwards until you know it: {@code background: a, b} puts {@code a} in
 * front of {@code b}. So {@link #draw} walks the list from the end. Writing it the other way round is
 * the one mistake here that still renders — every layer is present and the picture is inside out, which
 * looks like a z-order bug in whatever supplied the drawables.</p>
 *
 * <h3>Corner radii reach the layers that can use them</h3>
 *
 * <p>A stack clips itself, because it has to: the painter's alternative is to wrap the whole background
 * in a {@link CgUiRoundedRect}, and a wrap can only round ONE fill. So the radii are forwarded to every
 * layer that is {@link CornerRadiusAware} — a gradient masks itself, glass measures its bezel — and a
 * layer that is not simply paints unclipped, exactly as it would today wherever the wrap does not apply.
 * Per-layer clipping for the rest is stage 2, along with per-layer {@code background-size} and
 * {@code -position}.</p>
 *
 * <h3>No equality</h3>
 *
 * <p>Deliberately identity, like every drawable but the two that can spell themselves. {@code TextureValue}
 * remembers the CSS a drawable was parsed from in a map keyed by equality, so a value-equal drawable that
 * cannot write itself loses its source when an equal temporary is collected — the defect that made a mask
 * disappear from Copy Attributes. A stack is written back from the text it was parsed from, so it must
 * stay identity-compared. @see com.crystalgui.style.property.visual.texture.TextureProperty#write</p>
 */
public final class CgUiLayers implements CgUiDrawable, CornerRadiusAware {

    /** In CSS order: index 0 is drawn last and therefore appears on top. */
    private final List<CgUiDrawable> layers;

    public CgUiLayers(List<CgUiDrawable> layers) {
        if (layers.size() < 2) {
            throw new IllegalArgumentException("A layer stack of " + layers.size()
                    + " is just that drawable — TextureValue returns the single layer itself.");
        }
        this.layers = List.copyOf(layers);
    }

    /** The layers, top first. */
    public List<CgUiDrawable> layers() {
        return layers;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        // BACKWARDS, because the first layer is the top one. @see the class note
        for (int i = layers.size() - 1; i >= 0; i--) {
            layers.get(i).draw(ctx, mouseX, mouseY, x, y, width, height);
        }
    }

    @Override
    public void setCornerRadii(float rxTL, float ryTL, float rxTR, float ryTR,
                               float rxBR, float ryBR, float rxBL, float ryBL) {
        for (CgUiDrawable layer : layers) {
            if (layer instanceof CornerRadiusAware aware) {
                aware.setCornerRadii(rxTL, ryTL, rxTR, ryTR, rxBR, ryBR, rxBL, ryBL);
            }
        }
    }

    /**
     * {@code -1}: a stack has no natural size of its own.
     *
     * <p>Each layer has one and CSS sizes each against its own, which is what the comma-separated
     * {@code background-size} is for. Answering the first layer's would size the whole stack by whichever
     * drawable happened to be on top.</p>
     */
    @Override
    public float intrinsicWidth() {
        return -1f;
    }

    @Override
    public float intrinsicHeight() {
        return -1f;
    }

    @Override
    public String toString() {
        return "CgUiLayers" + layers;
    }
}
