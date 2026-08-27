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
        ctx.nativeContent(current.profile(), anchorFor(fillDirection), box[0], box[1], box[2], box[3],
                surface -> service.draw(surface, current));
    }

    /**
     * The corner a tiling renderer should pin its pattern to, for a box this class has already narrowed.
     *
     * <p><b>The anchor is the edge that MOVES.</b> {@link #fillBox} keeps three edges of the content box
     * and slides the fourth, so pinning the tile grid there makes that edge always a whole tile's edge —
     * identical at every fill level — and pushes the remainder against a static edge, where the slot's
     * own border already sits. Pinned to a static edge instead, the moving one cuts through a tile and
     * the fluid's surface shows a different slice of the sprite at every level.</p>
     *
     * <p>The axis that is not being filled takes the near end, which is what it answered before
     * {@link NativeAnchor} existed. Package-private and static so the mapping is testable without a
     * paint — it is four one-line answers and every one of them is invisible in a screenshot until the
     * tank is at a level that is not a whole number of tiles.</p>
     */
    static NativeAnchor anchorFor(FillDirection direction) {
        switch (direction) {
            // The bottom edge slides down as it drains, so the grid hangs from it.
            case TOP_DOWN:
                return NativeAnchor.BOTTOM_LEFT;
            // The right edge sweeps rightwards as it fills.
            case LEFT_RIGHT:
                return NativeAnchor.TOP_RIGHT;
            // RIGHT_LEFT's moving edge is the left one, and BOTTOM_UP's is the top — both the near end
            // of their axis, so both are the default corner.
            case RIGHT_LEFT:
            case BOTTOM_UP:
            default:
                return NativeAnchor.TOP_LEFT;
        }
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
                return new float[] { x, y, width, filled(height, fill) };
            case LEFT_RIGHT:
                return new float[] { x, y, filled(width, fill), height };
            case RIGHT_LEFT: {
                float w = filled(width, fill);
                return new float[] { x + (width - w), y, w, height };
            }
            case BOTTOM_UP:
            default: {
                float h = filled(height, fill);
                return new float[] { x, y + (height - h), width, h };
            }
        }
    }

    /**
     * The filled length along the narrowed axis — <b>never rounded away to nothing while there is
     * anything in the tank</b>.
     *
     * <p>Tinkers' Construct clamps the same way ({@code Math.max(Math.min(height, amount * height /
     * capacity), 1)}), and the reason is that the two states a reader most needs to tell apart are
     * "empty" and "nearly empty". A bucket in a 32-bucket tank is 3% of an 18px slot, which is half a
     * pixel, which is no pixels — so a tank that is genuinely holding something reads as one that is
     * not, and the only way to find out is to try to draw from it.</p>
     *
     * <p>The floor is one <em>logical</em> pixel, so it stays one pixel at any {@code uiScale} rather
     * than becoming a hairline at 2x. It cannot overflow a box smaller than itself — a slot under 1px
     * is already invisible and clamping up would draw outside it. Genuine emptiness is filtered out
     * before this: {@link NativeContentSlot#paintSelf} returns on {@link NativeContent#isEmpty()}, so
     * {@code fill <= 0} here means a rounding artefact rather than an empty tank.</p>
     */
    private static float filled(float extent, float fill) {
        if (extent <= 0f || fill <= 0f) return 0f;
        return Math.min(extent, Math.max(1f, extent * fill));
    }
}
