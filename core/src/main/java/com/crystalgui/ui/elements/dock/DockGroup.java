package com.crystalgui.ui.elements.dock;

import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
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

    /**
     * On a group holding no panels — which only the {@linkplain DockLeaf#isCentral() central} one can be.
     *
     * <p>An empty central pane is the guarantee that the work area still exists, so it is deliberate and
     * it has to look deliberate. VS Code draws a watermark of keyboard shortcuts in exactly this state;
     * without something, an empty grey box is indistinguishable from a pane that failed to collapse, and
     * it gets reported as one.</p>
     */
    public static final String EMPTY_CLASS = "__empty__";

    /** The caret between two tabs showing where a reorder would land. */
    public static final String INSERTION_CLASS = "__insertion__";

    /** How wide the insertion caret is drawn, in logical pixels. */
    private static final float INSERTION_WIDTH = 2f;

    private final DockArea area;
    private final DockLeaf leaf;
    private final TabView tabs = new TabView();
    private final UIElement overlay = new UIElement();
    private final UIElement insertion = new UIElement();

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

        insertion.addClass(INSERTION_CLASS);
        insertion.setHitTest(false);
        hideInsertionMarker();
        addInternalChild(insertion);

        // A group is the unit commands resolve against, so it has to be able to hold focus. A container
        // that never sets a policy takes none, and every command that asks "which group is active" goes
        // silently inert while the widget still looks alive — the way GraphView shipped.
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);

        // A PRESS ANYWHERE IN THIS GROUP MAKES IT THE ACTIVE ONE, content included.
        //
        // Every dock command acts on DockArea.activeGroup(), and until now the only thing that set it was
        // the tab listener below -- so focus could sit in an editor while the "active" group was whichever
        // HEADER was last clicked. Ctrl+W then closed a panel in another pane, or nothing at all, and the
        // workaround looked like "click the tab first".
        //
        // CAPTURE phase, so it lands before whatever was clicked can stopPropagation -- a TextEditor
        // consumes its own presses, and that is exactly the case this exists for. It only reads, never
        // consumes: activating a group must not cost the click that caused it.
        this.events.getGroup(MouseEvent.Down.class).attachListener(
                (element, event) -> area.setActiveGroup(this), true, false);

        // AND ON FOCUS, which is not the same question and does not subsume it either way. A press
        // activates a group even where nothing is focusable -- blank space in a panel, a read-only label
        // -- and focus activates it when nobody pressed anything, which is a real path here: the Problems
        // panel calls requestFocus on an editor to jump to a diagnostic, and the pane holding that editor
        // has to become the one Ctrl+W acts on. FocusEvent bubbles, so this sees focus anywhere inside.
        this.events.getGroup(FocusEvent.Focus.class).attachListener(
                (element, event) -> area.setActiveGroup(this), false, true);

        tabs.attachListener(tab -> {
            // ONLY a user's selection writes back. See the field's note: the strip emits selections while
            // it is being rebuilt FROM the model, and taking those as input overwrites the very value the
            // rebuild is trying to display.
            if (syncing) return;
            DockPanelRef panel = panelOf(tab);
            if (panel != null) {
                leaf.activate(panel);
                area.setActiveGroup(this);
                // Explicitly, because setActiveGroup above early-returns when this group was ALREADY
                // active -- which is the ordinary case for switching tabs within one pane, and would
                // otherwise be the one active-panel change that announces nothing. The announce is
                // idempotent, so the two paths overlapping costs nothing.
                area.announceActivePanel();
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
    /**
     * True while the strip is being brought into line with the leaf — the model→view direction.
     *
     * <p><b>The write-back listener must not fire during this.</b> {@code TabView} emits
     * {@code onTabSelected} whenever its selection changes, and a rebuild changes it repeatedly for
     * reasons that have nothing to do with the user: {@code clearTabs()} removes tabs one at a time and
     * each removal promotes a survivor, then the first {@code addTab} selects itself because nothing is
     * selected yet. Every one of those was being written straight back into {@code leaf.activate(...)}.</p>
     *
     * <p>So the model's selection was destroyed by the act of displaying it, and {@code sync()} then
     * faithfully showed the corrupted value. The symptom was a <b>one-step lag</b>: open a file and the
     * <em>previously</em> opened tab is the one that looks selected. Every part in isolation was correct —
     * {@code DockLeaf.add} activates what it inserts, {@code TabView.selectTab} is exclusive, and the
     * computed styles tracked the model exactly — which is why this survived the suite: nothing tested the
     * two directions running at once.</p>
     */
    private boolean syncing;

    void sync() {
        boolean wasSyncing = syncing;
        syncing = true;
        try {
            List<DockPanelRef> wanted = leaf.panels();
            if (wanted.isEmpty() != hasClass(EMPTY_CLASS)) {
                if (wanted.isEmpty()) {
                    addClass(EMPTY_CLASS);
                } else {
                    removeClass(EMPTY_CLASS);
                }
            }
            if (!wanted.equals(new ArrayList<>(tabByPanel.keySet()))) {
                rebuildStrip(wanted);
            }
            Tab active = tabByPanel.get(leaf.activePanel());
            if (active != null && tabs.getSelectedTab() != active) tabs.selectTab(active);
        } finally {
            // Restored rather than cleared, so a nested sync cannot re-open the door on the way out.
            syncing = wasSyncing;
        }
    }

    private void rebuildStrip(List<DockPanelRef> wanted) {
        // Detach content before clearing, or clearAllChildren disposes of elements this group is still
        // caching and the next sync re-parents an element that thinks it is still attached elsewhere.
        for (Tab tab : tabs.getTabs()) tab.content().clearAllChildren();
        tabs.clearTabs();
        tabByPanel.clear();

        for (DockPanelRef panel : wanted) {
            Tab tab = tabs.addTab(area.registry().titleOf(panel));
            applyIcon(tab, area.registry().iconOf(panel));
            tab.content().addChild(contentFor(panel));
            tabByPanel.put(panel, tab);
            area.installTabDrag(this, panel, tab);
        }
    }

    /**
     * Re-reads one panel's title onto its tab, in place.
     *
     * <p>In place, never through {@link #rebuildStrip}: a rebuild detaches and recreates every tab
     * element, and a tab is a drag source — so rebuilding one to change its label tears down the element
     * the pointer is holding. That is the same rule the file tree and the table header are both written
     * to, and the reason the presentation seam exists at all.</p>
     *
     * <p>The icon is deliberately <b>not</b> re-read. It is a function of the ref, the ref is immutable,
     * and so the icon cannot have changed without this being a different panel entirely — at which point
     * {@link #sync} rebuilds the strip anyway. Re-reading it here would be work that provably cannot find
     * anything, on a path called from a frame tick.</p>
     */
    void refreshPresentation(DockPanelRef panel) {
        Tab tab = tabByPanel.get(panel);
        // setText suppresses an equal write, so a caller that cannot cheaply tell whether anything moved
        // may call this freely.
        if (tab != null) tab.setText(area.registry().titleOf(panel));
    }

    /**
     * Gives a tab its file-type icon, or leaves it without one.
     *
     * <p>Resolved through {@code FileIconTheme.toResourcePath}, which is the single definition of where an
     * icon lives — shared with CSS {@code icon()} so a stylesheet and a tab can never disagree about the
     * path a name maps to. The dock learns a name and resolves it; it never learns what a {@code .java}
     * is.</p>
     */
    private static void applyIcon(Tab tab, @Nullable String iconName) {
        if (iconName == null) return;
        CgUiSvg glyph = CgUiSvg.of(FileIconTheme.toResourcePath(FileIconTheme.withVariant(iconName)));
        if (glyph == null) return;
        UIElement slot = new UIElement();
        // Unhittable, like every other composite part: click-focus targets the exact element hit rather
        // than the nearest focusable ancestor, so a hittable icon would swallow the press meant to select
        // the tab -- and the drag that starts from it.
        slot.setHitTest(false);
        StyleGroup.defaultPipeline(slot.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
        tab.setPreIcon(slot);
    }

    // The pane cache belongs here and is NOT wired yet -- deliberately, and the reason is worth keeping.
    //
    // A pane is one instance per (group, TYPE), retargeted by setInput. This group's `content` map is
    // keyed per PANEL. So two tabs of one type would both resolve to the same view element, and
    // rebuildStrip would parent one element into two tabs -- which is not a subtle failure, it is the
    // "cannot add the same child twice" class of bug this package has already paid for twice.
    //
    // The fix is not a bigger map. It is that with panes, only the ACTIVE tab has a body at all (VS Code
    // builds no DOM for an inactive editor), so the view has to be re-parented into the active tab on
    // every activation -- and sync() runs during a tab click, which is exactly when this codebase's rule
    // says a widget must not re-parent what is being clicked. Getting that ordering right needs the
    // harness, not a unit test.
    //
    // DockInput, DockPane, DockPaneProvider and the registry half are shipped and tested; this is the
    // remaining wiring. See plan.md §18.4.

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

    // ── Tab strip geometry ──────────────────────────────────────────────────────────────────────

    /** Whether {@code (screenX, screenY)} is over this group's tab strip rather than its content. */
    public boolean isOverStrip(float screenX, float screenY) {
        return tabs.getTabs().stream().anyMatch(tab -> tab.containsScreenPoint(screenX, screenY))
                || stripBandContains(screenX, screenY);
    }

    /**
     * The strip's own band, so the gap after the last tab still counts as "the strip".
     *
     * <p>Without it, dropping just past the last tab — which is exactly where you aim to append — falls
     * through to the content area and becomes a split.</p>
     */
    private boolean stripBandContains(float screenX, float screenY) {
        if (tabs.getTabs().isEmpty()) return false;
        var strip = tabs.getTabs().get(0).getRuntimeCache();
        var local = screenToLocal(screenX, screenY);
        var self = getRuntimeCache();
        float y = local.y();
        return local.x() >= self.getX() && local.x() <= self.getX() + self.getWidth()
                && y >= strip.getY() && y <= strip.getY() + strip.getHeight();
    }

    /**
     * Where in the strip a drop at {@code screenX} would land.
     *
     * <p>The first tab whose midpoint is past the pointer — the rule every tab strip uses, and the reason
     * a tab dropped on the left half of its neighbour goes before it rather than after.</p>
     */
    public int insertionIndexAt(float screenX) {
        List<Tab> strip = tabs.getTabs();
        for (int i = 0; i < strip.size(); i++) {
            var cache = strip.get(i).getRuntimeCache();
            var local = screenToLocal(screenX, cache.getY());
            if (local.x() < cache.getX() + cache.getWidth() / 2f) return i;
        }
        return strip.size();
    }

    /** Draws the caret at the boundary {@code index} would insert at. */
    void showInsertionMarker(int index) {
        List<Tab> strip = tabs.getTabs();
        if (strip.isEmpty()) {
            hideInsertionMarker();
            return;
        }
        float x;
        float top;
        float height;
        if (index >= strip.size()) {
            var last = strip.get(strip.size() - 1).getRuntimeCache();
            x = last.getX() + last.getWidth();
            top = last.getY();
            height = last.getHeight();
        } else {
            var cache = strip.get(index).getRuntimeCache();
            x = cache.getX();
            top = cache.getY();
            height = cache.getHeight();
        }
        var self = getRuntimeCache();
        float left = x - self.getX() - INSERTION_WIDTH / 2f;
        float y = top - self.getY();
        StyleGroup.importantPipeline(insertion.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(left).top(y).width(INSERTION_WIDTH).height(height));
        StyleGroup.importantPipeline(insertion.getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    void hideInsertionMarker() {
        StyleGroup.importantPipeline(insertion.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.importantPipeline(insertion.getStyle().getGeneralGroup(), g -> g.opacity(0f));
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
