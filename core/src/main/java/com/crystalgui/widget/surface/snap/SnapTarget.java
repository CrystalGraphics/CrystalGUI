package com.crystalgui.widget.surface.snap;

import javax.annotation.Nullable;

/**
 * <b>One point the moving box may land on</b>, seen from the axis being solved — a corner or the centre
 * of something already there.
 *
 * <pre>{@code
 * // A box's top-left corner, offered to the horizontal solve.
 * sink.accept(new SnapTarget(box.x(), box.y(), SnapTarget.Kind.EDGE, outline));
 * // And to the vertical one: the axes swap, the point does not.
 * sink.accept(new SnapTarget(box.y(), box.x(), SnapTarget.Kind.EDGE, outline));
 * }</pre>
 *
 * <p>A point rather than a line, as tldraw and Excalidraw both snap: the line is drawn once the solve is
 * over, through every point sharing the coordinate the box landed on, with a mark at each.</p>
 *
 * @param at    the point's coordinate on the axis being solved
 * @param cross its coordinate on the OTHER axis — where the guide passes it and marks it
 * @param owner the shape the point belongs to, which a guide outlines so its mark says whose it is; null
 *              for a point that belongs to nothing drawn, such as a grid line
 */
public record SnapTarget(float at, float cross, Kind kind, @Nullable SnapScene.Outline owner) {

    /** A point that belongs to nothing drawn. */
    public SnapTarget(float at, float cross, Kind kind) {
        this(at, cross, kind, null);
    }

    /**
     * What the point belongs to. <b>Declaration order is priority order</b> among points that are
     * equally close and land the box in different places: flush with the container is what somebody
     * meant when a sibling happens to be the same distance away. A FRAME is the page the whole tree is
     * laid out on — an artboard.
     */
    public enum Kind {
        PARENT_EDGE,
        PARENT_CENTRE,
        FRAME_EDGE,
        FRAME_CENTRE,
        EDGE,
        CENTRE,
        GRID
    }
}
