package com.crystalgui.ui.elements.slot;

import com.crystalgui.render.CgUiPaintContext;

/**
 * <b>A tank, drawn by the host and filled proportionally.</b>
 *
 * <p>The sibling of {@link ItemSlot}, and deliberately not the same thing wearing a different class. A
 * fluid is rendered completely differently from an item — flat quads tiled out of the block atlas with
 * depth testing explicitly <em>off</em>, against an item's depth-tested, lit model — which is why
 * {@link NativeProfile} exists and why a fluid handle reports {@link NativeProfile#FLAT}.</p>
 *
 * <p>It stays a native draw rather than something this engine renders itself, even though a fluid is
 * only a tinted sprite and we could composite one. Mods render their fluids in their own ways, and the
 * host's renderer is the only thing that knows how — a sprite plus a colour is the common case, not the
 * contract.</p>
 *
 * <h3>Fill is geometry, so it is the element's</h3>
 *
 * <p>{@link NativeContent#fillFraction()} says how full the tank is; this class turns that into a box and
 * asks the service to fill it. Drawing a full tank at reduced alpha instead would be the obvious
 * shortcut and is wrong twice — a half-full tank would read as a full tank of something translucent, and
 * a fluid that genuinely is translucent would have nothing left to say.</p>
 */
public class FluidSlot extends NativeContentSlot {

    /** Styling hook, alongside the shared {@link NativeContentSlot#SLOT_CLASS}. */
    public static final String FLUID_SLOT_CLASS = "__fluid-slot__";

    /**
     * Which way the contents rise.
     *
     * <p>{@link #BOTTOM_UP} is the default because it is what a tank does. The rest exist because a
     * horizontal gauge and a draining meter are both ordinary, and deriving them from the fill box is one
     * expression each — far less than a consumer would need to re-implement by nesting a clipped child.</p>
     */
    public enum FillDirection {
        /** Rises from the bottom edge. A tank. */
        BOTTOM_UP,
        /** Falls from the top edge. A depleting reservoir. */
        TOP_DOWN,
        /** Grows from the left edge. A horizontal gauge. */
        LEFT_RIGHT,
        /** Grows from the right edge. */
        RIGHT_LEFT
    }

    private FillDirection fillDirection = FillDirection.BOTTOM_UP;

    public FluidSlot() {
        addClass(FLUID_SLOT_CLASS);
    }

    public FillDirection getFillDirection() {
        return fillDirection;
    }

    public FluidSlot setFillDirection(FillDirection direction) {
        this.fillDirection = direction == null ? FillDirection.BOTTOM_UP : direction;
        return this;
    }

    @Override
    public FluidSlot bind(NativeContent content) {
        super.bind(content);
        return this;
    }

    /**
     * Narrows the content box to the filled portion before handing it over.
     *
     * <p>The service fills whatever box it is given, so the whole of "how full is this and which way does
     * it fill" stays here and none of it reaches the renderer. That is what lets one
     * {@link NativeContentService#draw} serve items and fluids without a branch.</p>
     */
    @Override
    protected void applyFill(CgUiPaintContext ctx, NativeContentService service, NativeContent current,
                             float x, float y, float width, float height, float fill) {
        float[] box = fillBox(fillDirection, x, y, width, height, fill);
        if (box[2] <= 0f || box[3] <= 0f) return;
        ctx.nativeContent(current.profile(), box[0], box[1], box[2], box[3],
                surface -> service.draw(surface, current));
    }

    /**
     * The filled sub-box, as {@code {x, y, width, height}}. Pure, so the arithmetic is testable with no
     * GL context — which is the only part of this class that can be wrong in a way a screenshot would not
     * immediately show.
     *
     * <p>Static and parameterised rather than reading the field, so a test can drive all four directions
     * without four instances. The covering test pairs it with a real paint in the harness scene: this
     * verifies the maths, and only the scene verifies that the caller hands it the right box — the
     * distinction that let a snap-zone bug survive a green arithmetic test.</p>
     */
    static float[] fillBox(FillDirection direction, float x, float y, float width, float height, float fill) {
        switch (direction) {
            case TOP_DOWN:
                return new float[] { x, y, width, height * fill };
            case LEFT_RIGHT:
                return new float[] { x, y, width * fill, height };
            case RIGHT_LEFT: {
                float w = width * fill;
                return new float[] { x + (width - w), y, w, height };
            }
            case BOTTOM_UP:
            default: {
                float h = height * fill;
                return new float[] { x, y + (height - h), width, h };
            }
        }
    }
}
