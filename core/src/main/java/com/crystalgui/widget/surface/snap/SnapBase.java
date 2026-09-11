package com.crystalgui.widget.surface.snap;

/**
 * <b>Which feature of the moving box is doing the landing</b> — Blender's "Snap With".
 *
 * <p>Kept apart from {@link SnapTarget}, which says where it may land, because the two are independent
 * questions and pairing them by hand is how you end up offering some of the combinations. Three bases
 * against three kinds of target line is nine alignments, all of which a designer means at one time or
 * another; the implementation this replaced wrote out three.</p>
 */
public enum SnapBase {

    /** The box's left or top edge. */
    LEADING,

    /** Its right or bottom edge. */
    TRAILING,

    /** Its middle. */
    CENTRE;

    /**
     * Where the box's origin has to be for this feature to land on {@code target}.
     *
     * @param extent the box's width or height on the axis being solved
     */
    public float originFor(float target, float extent) {
        return switch (this) {
            case LEADING -> target;
            case TRAILING -> target - extent;
            case CENTRE -> target - extent * 0.5f;
        };
    }
}
