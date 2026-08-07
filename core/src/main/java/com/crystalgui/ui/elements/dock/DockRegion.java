package com.crystalgui.ui.elements.dock;

/**
 * Where a panel <b>belongs</b> — VS Code's `Part`, IntelliJ's tool-window anchor.
 *
 * <h3>A region is not a position</h3>
 *
 * <p>This is the distinction the whole Parts model rests on. A {@link DockDropZone} says *"split that
 * leaf downwards"* — it describes an operation on a tree, and its answer stops being meaningful the
 * moment the tree changes. A region says *"the sidebar"*, which is still true after every split, drag and
 * collapse.</p>
 *
 * <p>That is why hiding a tool window currently needs a four-tier restoration heuristic: placement was a
 * tree position, closing collapsed the branch holding it, and the position had to be reconstructed from
 * whatever survived. With a region there is nothing to reconstruct — a lookup answers it.</p>
 *
 * <h3>Only the regions that hold content</h3>
 *
 * <p>VS Code has eight parts; four of them ({@code TITLEBAR}, {@code BANNER}, {@code ACTIVITYBAR},
 * {@code STATUSBAR}) are chrome that holds no movable panel, so they are not spellable here. A panel
 * cannot be put in the status bar, and an enum that let you ask is an enum that needs an answer.</p>
 */
public enum DockRegion {

    /**
     * The work area — documents, arbitrarily split.
     *
     * <p>The one region that keeps a splittable tree, and both references agree: VS Code nests its editor
     * grid inside {@code EDITOR_PART}, IntelliJ's is {@code EditorsSplitters}. Everything good about
     * {@code DockLayout} stays here; what changes is that it stops being asked to hold tool windows too.</p>
     */
    EDITOR(DockDropZone.MERGE),

    /** The left rail's panel — VS Code's `SIDEBAR`, IntelliJ's `LEFT` anchor. Project, Search. */
    SIDEBAR(DockDropZone.SPLIT_LEFT),

    /** The bottom strip — VS Code's `PANEL`, IntelliJ's `BOTTOM`. Problems, Terminal, Output. */
    PANEL(DockDropZone.SPLIT_DOWN),

    /** The right strip — VS Code's `AUXILIARYBAR`, IntelliJ's `RIGHT`. Inspector, Outline. */
    AUXILIARY(DockDropZone.SPLIT_RIGHT);

    private final DockDropZone wall;

    DockRegion(DockDropZone wall) {
        this.wall = wall;
    }

    /**
     * The outer edge this region currently stands in for.
     *
     * <p><b>Transitional.</b> Regions do not physically exist yet — a tool window is still a leaf in the
     * one dock tree — so "the sidebar" is expressed as "against the left wall". This is the bridge that
     * lets placement be <em>stated</em> as a region before there is a region to put it in, which is what
     * makes the change land in pieces rather than all at once.</p>
     *
     * <p>Deleted when the regions become real elements (plan.md §23 step 7). Nothing new should read it.</p>
     */
    public DockDropZone wall() {
        return wall;
    }

    /** The region a legacy {@link DockDropZone} anchor meant, for reading records written before regions. */
    public static DockRegion ofWall(DockDropZone zone) {
        if (zone == null) return SIDEBAR;
        switch (zone) {
            case SPLIT_RIGHT: return AUXILIARY;
            case SPLIT_DOWN:
            case SPLIT_UP: return PANEL;
            case MERGE: return EDITOR;
            default: return SIDEBAR;
        }
    }
}
