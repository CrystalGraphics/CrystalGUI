package com.crystalgui.ui.elements.dock;

import javax.annotation.Nullable;

/**
 * What is in flight during a dock drag.
 *
 * <p>A single panel or a whole group, and the distinction is only which field is set — because at the
 * layout level they are the same operation. {@link DockLayout#drop} takes any {@link DockNode}, so
 * "tear a tab out" and "move a whole pane" go through one code path, and a system where they are two is a
 * system where one of them forgets to collapse the tree behind it.</p>
 *
 * <p>Carries the {@link DockArea} it came from so a cross-area drop — out of the main dock and into a
 * float, or back — knows which tree to detach from. Without it the source area has to be inferred from
 * the dragged element's ancestors, which stops working the moment a drag outlives the element's
 * attachment.</p>
 */
public final class DockDragPayload {

    private final DockArea sourceArea;
    private final DockLeaf sourceLeaf;
    @Nullable
    private final DockPanelRef panel;

    private DockDragPayload(DockArea sourceArea, DockLeaf sourceLeaf, @Nullable DockPanelRef panel) {
        this.sourceArea = sourceArea;
        this.sourceLeaf = sourceLeaf;
        this.panel = panel;
    }

    public static DockDragPayload ofPanel(DockArea area, DockLeaf leaf, DockPanelRef panel) {
        return new DockDragPayload(area, leaf, panel);
    }

    public static DockDragPayload ofGroup(DockArea area, DockLeaf leaf) {
        return new DockDragPayload(area, leaf, null);
    }

    public DockArea sourceArea() {
        return sourceArea;
    }

    public DockLeaf sourceLeaf() {
        return sourceLeaf;
    }

    /** The single panel in flight, or {@code null} when the whole group is. */
    @Nullable
    public DockPanelRef panel() {
        return panel;
    }

    public boolean isWholeGroup() {
        return panel == null;
    }

    /** A group drag gets a larger edge target — see {@link DockDropZones#GROUP_EDGE_THRESHOLD}. */
    public boolean isGroupDrag() {
        return panel == null;
    }
}
