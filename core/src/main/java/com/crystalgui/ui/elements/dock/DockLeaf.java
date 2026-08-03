package com.crystalgui.ui.elements.dock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A tab strip: one or more panels, one of them active.
 *
 * <h3>The central leaf</h3>
 *
 * <p>Exactly one leaf in the main tree may be {@link #isCentral() central}, and it is the one thing this
 * design takes from Dear ImGui rather than VS Code. VS Code and IntelliJ both hardcode "the editor area"
 * as a separate system with everything else arranged around it; that asymmetry is twenty years of product
 * opinion about where things belong, but it <em>is</em> protecting something real — the main work area
 * must always exist and must not be closable into nothing. ImGui buys that guarantee for one flag.</p>
 *
 * <p>A central leaf cannot be closed, floated, or absorbed by a collapse. It may be empty; the other kind
 * cannot, because a leaf with no panels has nothing to show and no way to be reached.</p>
 */
public final class DockLeaf extends DockNode {

    private final List<DockPanelRef> panels = new ArrayList<>();
    private int active;
    private boolean central;
    private boolean maximized;

    public DockLeaf(DockPanelRef... initial) {
        super(0f);
        Collections.addAll(panels, initial);
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    public List<DockPanelRef> panels() {
        return Collections.unmodifiableList(panels);
    }

    public int panelCount() {
        return panels.size();
    }

    public DockPanelRef panel(int index) {
        return panels.get(index);
    }

    public boolean isEmpty() {
        return panels.isEmpty();
    }

    public int indexOf(DockPanelRef panel) {
        return panels.indexOf(panel);
    }

    /** The selected tab, or {@code null} when the leaf is empty. */
    public DockPanelRef activePanel() {
        return panels.isEmpty() ? null : panels.get(clampActive(active));
    }

    public int activeIndex() {
        return panels.isEmpty() ? -1 : clampActive(active);
    }

    public DockLeaf activate(int index) {
        this.active = clampActive(index);
        return this;
    }

    public DockLeaf activate(DockPanelRef panel) {
        int index = panels.indexOf(panel);
        if (index >= 0) this.active = index;
        return this;
    }

    public DockLeaf add(DockPanelRef panel) {
        return add(panel, panels.size());
    }

    /**
     * Inserts at {@code index} and makes it active — a panel dropped onto a strip is the one you want to
     * see, which is what every IDE does and the reason this is not a separate call.
     */
    public DockLeaf add(DockPanelRef panel, int index) {
        int at = Math.max(0, Math.min(panels.size(), index));
        panels.add(at, panel);
        this.active = at;
        return this;
    }

    /**
     * Removes a panel, keeping the selection somewhere sensible.
     *
     * <p>Closing the active tab selects its <b>predecessor</b>, not the next one along. That is what
     * VS Code, IntelliJ and every browser do, and the reason is that closing several in a row should walk
     * back through what you already had open rather than march forward through tabs you have not looked
     * at.</p>
     */
    public boolean remove(DockPanelRef panel) {
        int index = panels.indexOf(panel);
        if (index < 0) return false;
        panels.remove(index);
        if (panels.isEmpty()) {
            active = 0;
        } else if (index < active || (index == active && active == panels.size())) {
            active--;
        } else if (index == active) {
            active = Math.max(0, index - 1);
        }
        active = clampActive(active);
        return true;
    }

    /** Moves a panel within this strip. Used by tab reordering, which must not rebuild the strip. */
    public boolean move(int from, int to) {
        if (from < 0 || from >= panels.size()) return false;
        int target = Math.max(0, Math.min(panels.size() - 1, to));
        if (from == target) return false;
        DockPanelRef moved = panels.remove(from);
        panels.add(target, moved);
        this.active = target;
        return true;
    }

    private int clampActive(int index) {
        if (panels.isEmpty()) return 0;
        return Math.max(0, Math.min(panels.size() - 1, index));
    }

    // ── Flags ───────────────────────────────────────────────────────────────────────────────────

    public boolean isCentral() {
        return central;
    }

    public DockLeaf setCentral(boolean central) {
        this.central = central;
        return this;
    }

    public boolean isMaximized() {
        return maximized;
    }

    public DockLeaf setMaximized(boolean maximized) {
        this.maximized = maximized;
        return this;
    }

    @Override
    void collectLeaves(List<DockLeaf> out) {
        out.add(this);
    }

    @Override
    public String toString() {
        return (central ? "central-leaf" : "leaf") + panels;
    }
}
