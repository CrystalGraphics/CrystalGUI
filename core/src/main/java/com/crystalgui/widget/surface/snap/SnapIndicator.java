package com.crystalgui.widget.surface.snap;

/**
 * <b>What a snap draws</b> — the two kinds tldraw and Excalidraw both draw, and nothing else.
 *
 * <p>Coordinates are in the solver's space; {@link SnapIndicators} puts them on the screen.</p>
 */
public sealed interface SnapIndicator permits SnapIndicator.Points, SnapIndicator.Gap, SnapIndicator.Owner {

    /**
     * <b>The outline of something a guide passes through</b>, drawn faintly so each mark says whose it
     * is — a corner of a box that reads as nothing on its own, the middle of a container with nothing
     * drawn there.
     *
     * @param xs its corners in order round it; a fifth entry, the centre, is ignored
     */
    record Owner(float[] xs, float[] ys) implements SnapIndicator {
    }

    /**
     * <b>One line through every point that shares a coordinate</b>, with a mark at each.
     *
     * <p>{@code axis} is the axis that was SOLVED: a horizontal solve pins an x, so the line is vertical,
     * at {@code at}, from the first of {@code crosses} to the last. The marks say which features lined
     * up — a corner or a middle — so a centre needs no second line style.</p>
     *
     * @param crosses sorted, distinct, and including the moving box's own points on the line
     */
    record Points(SnapAxis axis, float at, float[] crosses) implements SnapIndicator {
    }

    /**
     * <b>One measured gap</b>: a bar along {@code axis} from {@code from} to {@code to}, at {@code cross}
     * on the other axis. Always between two boxes, never across the canvas.
     */
    record Gap(SnapAxis axis, float from, float to, float cross) implements SnapIndicator {

        public float length() {
            return to - from;
        }
    }
}
