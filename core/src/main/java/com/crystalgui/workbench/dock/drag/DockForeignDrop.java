package com.crystalgui.workbench.dock.drag;

import java.util.List;

import com.crystalgui.workbench.dock.layout.DockPanelRef;

/**
 * What a dock asks about a drag it did not start — a file dragged from the Project panel — so it can open the drop
 * where it lands, with the same zones a tab gets: into a group, beside it, or along the dock's outer edge.
 *
 * <pre>{@code
 * dock.setForeignDrop(new DockForeignDrop() {
 *     public List<DockPanelRef> panelsFor(Object payload) {
 *         DraggedResources files = DragData.find(payload, DraggedResources.class);
 *         return files == null ? List.of() : refsFor(files);
 *     }
 *     public void opened(List<DockPanelRef> panels) { recordRecent(panels); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Set on the home: every window torn out of it asks the same one. @see com.crystalgui.workbench.dock.DockArea#home</li>
 *   <li>{@link #panelsFor} is asked on every move of the drag, so it must be cheap and answer a payload the same way
 *       each time.</li>
 *   <li>A panel already open is MOVED to the drop, as dragging its tab would; the dock does that, not this.</li>
 * </ul>
 */
public interface DockForeignDrop {

    /** The panels a drop of {@code payload} would show, in order, or an empty list to refuse it. */
    List<DockPanelRef> panelsFor(Object payload);

    /** After a drop placed {@code panels} — what an open does besides placing a tab: recent files, companion panels. */
    default void opened(List<DockPanelRef> panels) {
    }
}
