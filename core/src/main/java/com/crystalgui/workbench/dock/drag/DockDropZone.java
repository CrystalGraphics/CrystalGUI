package com.crystalgui.workbench.dock.drag;

import com.crystalgui.workbench.dock.layout.DockOrientation;

/**
 * What a drop over a pane would do.
 *
 * <p>Five outcomes, which is what every docking system offers whatever its guide looks like — Visual
 * Studio draws them as a compass diamond, ImGui as a cross, VS Code as an invisible hit map. The shape of
 * the affordance differs; the set does not.</p>
 */
public enum DockDropZone {

    /**
     * Append to the hovered leaf's tab strip.
     *
     * <p><b>The most-used drop in the whole system</b>, and the one an edge-zones-only implementation
     * forgets — leaving a dock where every drop splits and two panels can never share a strip.</p>
     */
    MERGE(null, false),

    SPLIT_LEFT(DockOrientation.HORIZONTAL, false),
    SPLIT_RIGHT(DockOrientation.HORIZONTAL, true),
    SPLIT_UP(DockOrientation.VERTICAL, false),
    SPLIT_DOWN(DockOrientation.VERTICAL, true);

    private final DockOrientation axis;
    private final boolean after;

    DockDropZone(DockOrientation axis, boolean after) {
        this.axis = axis;
        this.after = after;
    }

    /** The axis the split divides, or {@code null} for {@link #MERGE}. */
    public DockOrientation axis() {
        return axis;
    }

    /** Whether the dropped node lands after the target along {@link #axis()} (right/down) or before. */
    public boolean after() {
        return after;
    }

    public boolean isSplit() {
        return axis != null;
    }
}
