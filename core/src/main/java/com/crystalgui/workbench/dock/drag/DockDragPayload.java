package com.crystalgui.workbench.dock.drag;

import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import java.util.List;
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
 *
 * <p>A drag the dock did not start — files from the Project panel — arrives as {@link #ofPanels}: panels with no
 * source, each lifted from wherever it already is when the drop lands. @see DockForeignDrop</p>
 */
public final class DockDragPayload {

    @Nullable
    private final DockArea sourceArea;
    @Nullable
    private final DockLeaf sourceLeaf;
    private final List<DockPanelRef> panels;

    private DockDragPayload(@Nullable DockArea sourceArea, @Nullable DockLeaf sourceLeaf, List<DockPanelRef> panels) {
        this.sourceArea = sourceArea;
        this.sourceLeaf = sourceLeaf;
        this.panels = List.copyOf(panels);
    }

    public static DockDragPayload ofPanel(DockArea area, DockLeaf leaf, DockPanelRef panel) {
        return new DockDragPayload(area, leaf, List.of(panel));
    }

    public static DockDragPayload ofGroup(DockArea area, DockLeaf leaf) {
        return new DockDragPayload(area, leaf, List.of());
    }

    /** Panels from outside the dock's own drag, opened where they drop — or moved there, if already open. */
    public static DockDragPayload ofPanels(List<DockPanelRef> panels) {
        if (panels.isEmpty()) throw new IllegalArgumentException("no panels");
        return new DockDragPayload(null, null, panels);
    }

    /** The dock the drag started in, or null for {@link #ofPanels}. */
    @Nullable
    public DockArea sourceArea() {
        return sourceArea;
    }

    /** The leaf the drag started in, or null for {@link #ofPanels}. */
    @Nullable
    public DockLeaf sourceLeaf() {
        return sourceLeaf;
    }

    /** The panel in flight — the first, of several — or {@code null} when a whole group is. */
    @Nullable
    public DockPanelRef panel() {
        return panels.isEmpty() ? null : panels.get(0);
    }

    /** Every panel in flight, in order; empty for a whole group. */
    public List<DockPanelRef> panels() {
        return panels;
    }

    public boolean isWholeGroup() {
        return panels.isEmpty();
    }

    /** Whether this came from outside the dock's own drag. @see #ofPanels */
    public boolean isForeign() {
        return sourceLeaf == null;
    }
}
