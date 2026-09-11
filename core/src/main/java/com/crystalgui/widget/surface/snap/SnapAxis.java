package com.crystalgui.widget.surface.snap;

/**
 * One of the two axes a 2D snap is solved on, <b>independently of the other</b>.
 *
 * <p>That independence is the whole shape of this engine and is what lets an axis be withheld: a
 * {@link AxisLock locked} axis is simply not solved, rather than solved and then overwritten by whoever
 * remembers to. It is also why a third axis does not belong here — a 3D face snap projects onto a plane
 * and couples all three at once, which is a different solver over the same vocabulary rather than this
 * one made wider. See {@code plan/crystalgui/shell-ui-builder/snapping-rewrite.md} §7.</p>
 */
public enum SnapAxis {
    HORIZONTAL,
    VERTICAL
}
