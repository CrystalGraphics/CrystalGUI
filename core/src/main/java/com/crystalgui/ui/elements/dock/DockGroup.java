package com.crystalgui.ui.elements.dock;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * One {@link DockLeaf}, drawn: a tab strip plus whatever the active panel is.
 *
 * <p>A {@link TabView} does the strip and the pane stack, so this is reconciliation rather than
 * construction — the tabs are brought into line with the leaf's panel list, and everything else is
 * already a shipped widget.</p>
 *
 * <h3>Content is cached per panel, not per rebuild</h3>
 *
 * <p>A split rebuilds the element tree above this group. If the content were rebuilt with it, every split
 * would throw away the editor's scroll position, its undo stack and its selection — which is the kind of
 * bug that gets reported as "the layout is fine but everything resets".</p>
 */
public class DockGroup extends UIElement {

    /** The whole group, so a theme can frame the active one. */
    public static final String ACTIVE_CLASS = "__active__";

    /** The drop preview. Covers the pane it would take. */
    public static final String OVERLAY_CLASS = "__drop-overlay__";

    private final DockArea area;
    private final DockLeaf leaf;
    private final TabView tabs = new TabView();
    private final UIElement overlay = new UIElement();

    /** Panel → its built content. Survives every rebuild of the tree above. */
    private final Map<DockPanelRef, UIElement> content = new LinkedHashMap<>();

    /** Panel → its tab, so a reconcile can tell "already there" from "new". */
    private final Map<DockPanelRef, Tab> tabByPanel = new LinkedHashMap<>();

    DockGroup(DockArea area, DockLeaf leaf) {
        this.area = area;
        this.leaf = leaf;

        addInternalChild(tabs);

        // The overlay must NOT take the pointer: an overlay that is hittable ends the drag on top of
        // itself, and the drop reads as having done nothing. setHitTest(false) covers the whole subtree,
        // which is what is wanted here — nothing inside a preview is interactive.
        overlay.addClass(OVERLAY_CLASS);
        overlay.setHitTest(false);
        hideDropPreview();
        addInternalChild(overlay);

        // A group is the unit commands resolve against, so it has to be able to hold focus. A container
        // that never sets a policy takes none, and every command that asks "which group is active" goes
        // silently inert while the widget still looks alive — the way GraphView shipped.
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);

        tabs.attachListener(tab -> {
            DockPanelRef panel = panelOf(tab);
            if (panel != null) {
                leaf.activate(panel);
                area.setActiveGroup(this);
            }
        });

        sync();
    }

    /** The root owns a tab view and an overlay; content comes from the panel registry. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public DockLeaf leaf() {
        return leaf;
    }

    public TabView tabView() {
        return tabs;
    }

    /** The tab showing {@code panel}, or {@code null} if it is not in this group. */
    @Nullable
    public Tab tabFor(DockPanelRef panel) {
        return tabByPanel.get(panel);
    }

    @Nullable
    private DockPanelRef panelOf(Tab tab) {
        for (Map.Entry<DockPanelRef, Tab> entry : tabByPanel.entrySet()) {
            if (entry.getValue() == tab) return entry.getKey();
        }
        return null;
    }

    /** Every panel currently shown here, in strip order. */
    public List<DockPanelRef> panels() {
        return new ArrayList<>(tabByPanel.keySet());
    }

    // ── Reconcile ───────────────────────────────────────────────────────────────────────────────

    /**
     * Brings the strip into line with the leaf.
     *
     * <p>Rebuilds the strip when the panel <em>list</em> changed and does nothing when only the selection
     * did. That distinction is the whole reason this is not simply "clear and re-add": a widget must never
     * rebuild the elements it is being clicked or dragged on, and selecting a tab is a click on a tab.</p>
     */
    void sync() {
        List<DockPanelRef> wanted = leaf.panels();
        if (!wanted.equals(new ArrayList<>(tabByPanel.keySet()))) {
            rebuildStrip(wanted);
        }
        Tab active = tabByPanel.get(leaf.activePanel());
        if (active != null && tabs.getSelectedTab() != active) tabs.selectTab(active);
    }

    private void rebuildStrip(List<DockPanelRef> wanted) {
        // Detach content before clearing, or clearAllChildren disposes of elements this group is still
        // caching and the next sync re-parents an element that thinks it is still attached elsewhere.
        for (Tab tab : tabs.getTabs()) tab.content().clearAllChildren();
        tabs.clearTabs();
        tabByPanel.clear();

        for (DockPanelRef panel : wanted) {
            Tab tab = tabs.addTab(area.registry().titleOf(panel));
            tab.content().addChild(contentFor(panel));
            tabByPanel.put(panel, tab);
            area.installTabDrag(this, panel, tab);
        }
    }

    private UIElement contentFor(DockPanelRef panel) {
        return content.computeIfAbsent(panel, ref -> {
            UIElement built = area.registry().create(ref);
            if (built != null) return built;
            // An unbuildable panel is shown as an empty box rather than skipped: a tab with nothing
            // behind it is visible and reportable, while a silently absent tab looks like the layout
            // failed to restore.
            UIElement placeholder = new UIElement();
            placeholder.addClass("__missing__");
            return placeholder;
        });
    }

    void setActive(boolean active) {
        if (active == hasClass(ACTIVE_CLASS)) return;
        if (active) {
            addClass(ACTIVE_CLASS);
        } else {
            removeClass(ACTIVE_CLASS);
        }
    }

    // ── Drop preview ────────────────────────────────────────────────────────────────────────────

    /** Covers the part of this group a drop would take. */
    void showDropPreview(DockDropZone zone) {
        var cache = getRuntimeCache();
        float[] rect = DockDropZones.previewRect(zone, cache.getWidth(), cache.getHeight());
        StyleGroup.importantPipeline(overlay.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(rect[0]).top(rect[1])
                .width(rect[2]).height(rect[3]));
        StyleGroup.importantPipeline(overlay.getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * Hides the preview.
     *
     * <p>Kept in the tree at zero opacity rather than removed: taking it out and putting it back is a
     * structural change to a subtree a drag is live over, and it is the cheaper of the two anyway.</p>
     */
    void hideDropPreview() {
        StyleGroup.importantPipeline(overlay.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.importantPipeline(overlay.getStyle().getGeneralGroup(), g -> g.opacity(0f));
    }
}
