package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.dock.DockRegion;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * One {@link DockRegion}, as an element — VS Code's `SIDEBAR_PART` / `PANEL_PART` / `AUXILIARYBAR_PART`.
 *
 * <h3>Why a region is not a dock leaf</h3>
 *
 * <p>A leaf's identity is its position in a tree, and closing one collapses the branch that held it —
 * which is the whole reason {@code ToolWindowManager} needed a four-tier heuristic to put a hidden tool
 * window back. A region is a <b>fixed slot</b>: hiding it does not destroy anything, because "the sidebar"
 * is not a position and cannot be collapsed away.</p>
 *
 * <p>So a region is not something the layout can lose, and that is the point. You cannot drag the sidebar
 * into the panel; you drag a <em>container</em> between them, which is a one-field write.</p>
 *
 * <h3>Empty is a real state, not an absent one</h3>
 *
 * <p>A region with nothing showing still exists — it is simply not in the split. That is the same rule the
 * uncloseable central leaf already states: a region that vanished when empty could never be reopened,
 * because there would be nothing left for {@code Ctrl+B} to toggle.</p>
 */
public class RegionHost extends UIElement {

    /** Every region host, for a theme that frames them alike. */
    public static final String HOST_CLASS = "__region-host__";

    private final DockRegion region;

    /** What is showing, by panel type id — {@code null} when the region is empty. */
    @Nullable
    private String showing;

    public RegionHost(DockRegion region) {
        this.region = region;
        addClass(HOST_CLASS);
        // Per-region class so the sheet can size the sidebar differently from the panel without the
        // Java side naming a pixel -- the widget rule.
        addClass("__region-" + region.name().toLowerCase(Locale.ROOT) + "__");
        // NOT markAsInternal(). SplitView.paneContent clears a pane with clearAllChildren(), which
        // deliberately skips internal children -- so an internal host would never leave the pane it was
        // first placed in, and every later sync would stack another one behind it.
    }

    public DockRegion region() {
        return region;
    }

    /** What is showing here, or null. */
    @Nullable
    public String showing() {
        return showing;
    }

    public boolean isEmpty() {
        return showing == null;
    }

    /**
     * Shows {@code content} here, replacing whatever was.
     *
     * <p>Through {@link UIElement#setOnlyChild}, which is the general form of the {@code assertOnlyChild}
     * that {@code CrystalEditor} used to hand-roll — it handles the internal-child case that
     * {@code clearAllChildren} silently skips.</p>
     */
    public void show(String typeId, UIElement content) {
        this.showing = typeId;
        setOnlyChild(content);
    }

    /** Empties the region. The host stays; see the class note on why. */
    public void clear() {
        this.showing = null;
        setOnlyChild(null);
    }

    // acceptsPublicChildren stays TRUE, unlike most composites here. A region host is a holder -- the
    // same shape as Tab.content() or a SplitView pane -- and setOnlyChild adds through the public API,
    // so refusing would make the one method this class exists for throw.
}
