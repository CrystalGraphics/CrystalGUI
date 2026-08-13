package com.crystalgui.ui.elements.dock;

import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.core.notify.Notification;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.InsertionMarker;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import com.crystalgui.core.dispose.Disposer;
import java.util.LinkedHashSet;
import java.util.Set;
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

    /** The stable container a pane's view is moved into when its panel is the active one. */
    public static final String PANE_HOST_CLASS = "__pane-host__";

    /**
     * The caret between two tabs showing where a reorder would land.
     *
     * <p>An alias for {@link InsertionMarker#MARKER_CLASS} now — the class moved with the widget, and the
     * thickness with it. Kept because this is the name every dock theme already selects on.</p>
     */
    public static final String INSERTION_CLASS = InsertionMarker.MARKER_CLASS;

    private final DockArea area;
    private final DockLeaf leaf;
    private final TabView tabs = new TabView();
    private final UIElement overlay = new UIElement();
    private final InsertionMarker insertion = new InsertionMarker(InsertionMarker.Axis.HORIZONTAL);

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


        insertion.parkIn(this);

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

    /** The dock this group belongs to. Lets a walk tell one dock's group from another's. */
    public DockArea dockArea() {
        return area;
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
            prunePanes(wanted);
            retargetPane(leaf.activePanel());
            // AND ANNOUNCE. A sync is how a panel ADDED to an already-active group becomes the front one,
            // and that path announced nothing: setActiveGroup early-returns because the group did not
            // change, and rebuild() does not run because opening into an existing group is a selection
            // change rather than a structural one. So "open a file into the pane you are already in" --
            // which is what launching with a document open IS -- moved the active panel silently, and
            // anything following it kept showing what was there before.
            //
            // Idempotent, so overlapping with the other announce sites costs nothing.
            area.announceActivePanel();
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
            // AT BUILD TIME, which is the whole reason the decoration is pulled from a provider rather
            // than pushed onto the tab: the strip is rebuilt on every rearrangement, and anything pushed
            // would have to be pushed again by somebody who noticed.
            applyDecoration(tab, panel);
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
        if (tab == null) return;
        // setText suppresses an equal write, so a caller that cannot cheaply tell whether anything moved
        // may call this freely.
        tab.setText(area.registry().titleOf(panel));
        applyDecoration(tab, panel);
    }

    /**
     * Puts the panel's {@code decoration-*} class on its tab, replacing whichever one it had.
     *
     * <p><b>Swapped, never added.</b> A tab outlives every state its file passes through — broken, fixed,
     * broken again — so adding {@code decoration-error} without removing it leaves a tab permanently red
     * once its file has been wrong once. The same rule the project tree's rows follow for the identical
     * reason, and {@code swapPrefixedClass} is the one definition of it.</p>
     */
    private void applyDecoration(Tab tab, DockPanelRef panel) {
        String decoration = area.registry().decorationOf(panel);
        tab.swapPrefixedClass("decoration-", decoration == null ? "" : decoration);
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
        CgUiSvg glyph = CgUiSvg.ofIcon(iconName);
        if (glyph == null) return;
        UIElement slot = new UIElement();
        // Unhittable, like every other composite part: click-focus targets the exact element hit rather
        // than the nearest focusable ancestor, so a hittable icon would swallow the press meant to select
        // the tab -- and the drag that starts from it.
        slot.setHitTest(false);
        StyleGroup.defaultPipeline(slot.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
        tab.setPreIcon(slot);
    }

    /**
     * One pane per panel <b>type</b> in this group — the retargeting cache.
     *
     * <p>Keyed by type rather than by panel, which is the point: two {@code file} tabs in one group share
     * a pane and switching between them is a {@code setInput}, not a rebuild.</p>
     */
    private final Map<String, DockPane> panes = new LinkedHashMap<>();

    /** What each pane is currently pointed at, so a re-activation is not mistaken for a retarget. */
    private final Map<String, DockInput> paneInputs = new LinkedHashMap<>();

    /** Per-input view state, keyed by the FRAMEWORK. A pane keying its own is the stacked-inspector bug. */
    private final Map<DockPanelRef, StateMap<?>> viewStates = new LinkedHashMap<>();

    /** Which input's pane is currently on screen here, so {@code onHidden}/{@code onVisible} fire once. */
    @Nullable
    private DockInput visibleInput;

    /**
     * Points the pane for the active panel's type at it, and puts its view in that panel's host.
     *
     * <h3>How this avoids putting one element in two tabs</h3>
     *
     * <p>A pane is one instance per <b>type</b> while {@link #content} is keyed per <b>panel</b>, so
     * returning the pane's view from {@code contentFor} would hand the same element to two tabs and
     * {@link #rebuildStrip} would parent it twice — the "cannot add the same child twice" bug this
     * package has paid for before.</p>
     *
     * <p>So every pane-backed panel keeps its own stable <b>host</b>, and only the host of the
     * <em>active</em> panel holds the view. {@code rebuildStrip} is untouched, nothing is ever shared,
     * and moving the view is one {@code setOnlyChild} — which re-parents correctly, including out of an
     * internal parent.</p>
     *
     * <h3>Why this is safe during a tab click</h3>
     *
     * <p>The rule is that a widget must not rebuild the elements it is being <em>clicked on</em>. The
     * click target is the {@link Tab} in the strip; what moves here is the pane's view, which lives in
     * the tab's content. The clicked element is never touched — which is exactly why this shape was
     * chosen over re-parenting a shared view between tabs.</p>
     *
     * <p>Ordering is the contract: the outgoing input's view state is written and {@code onHidden} fires
     * <em>before</em> {@link DockPane#setInput}, and the incoming input's state is read after it.</p>
     */
    private void retargetPane(@Nullable DockPanelRef active) {
        DockInput incoming = active == null ? null : DockInput.of(active);

        if (visibleInput != null && !visibleInput.matches(incoming)) {
            DockPane leaving = panes.get(visibleInput.typeId());
            if (leaving != null) {
                StateMap<?> outgoing = new StateMap<>(PlainOps.INSTANCE, new LinkedHashMap<>());
                leaving.writeViewState(outgoing);
                viewStates.put(visibleInput.ref(), outgoing);
                leaving.onHidden();
            }
            visibleInput = null;
        }
        if (incoming == null) return;

        DockPaneProvider provider = area.registry().paneProviderFor(incoming);
        if (provider == null) return;
        String typeId = incoming.typeId();
        DockPane pane = panes.computeIfAbsent(typeId, key -> provider.create());

        if (!incoming.matches(paneInputs.get(typeId))) {
            pane.setInput(incoming);
            paneInputs.put(typeId, incoming);
            StateMap<?> stored = viewStates.get(active);
            if (stored != null) pane.readViewState(stored);
        }
        if (visibleInput == null) {
            pane.onVisible();
            visibleInput = incoming;
        }

        UIElement host = content.get(active);
        if (host != null) host.setOnlyChild(pane.view());
    }

    /**
     * Releases panes whose type no longer has a panel here.
     *
     * <p>{@code clearInput} then dispose, and only when the pane genuinely leaves the group — a pane
     * whose tab merely stopped being active is still this group's and is reused the moment it comes
     * forward again.</p>
     */
    /**
     * Releases every pane here — this whole group is going away.
     *
     * <p>Closing the <b>last</b> panel of a leaf removes the leaf, so this group is discarded before any
     * {@code sync} could prune its panes one at a time. Without this, the one case that most needs the
     * release — the last tab closing — is the one that never gets it.</p>
     */
    void releaseAllPanes() {
        prunePanes(List.of());
    }

    private void prunePanes(List<DockPanelRef> wanted) {
        if (panes.isEmpty()) return;
        Set<String> live = new LinkedHashSet<>();
        for (DockPanelRef panel : wanted) live.add(panel.typeId());
        panes.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey())) return false;
            DockPane pane = entry.getValue();
            pane.clearInput();
            Disposer.dispose(pane);
            paneInputs.remove(entry.getKey());
            if (visibleInput != null && visibleInput.typeId().equals(entry.getKey())) visibleInput = null;
            return true;
        });
    }

    private UIElement contentFor(DockPanelRef panel) {
        return content.computeIfAbsent(panel, ref -> withBanners(ref, buildContent(ref)));
    }

    /**
     * Puts whatever {@link DockBanners} had to say above {@code built}.
     *
     * <h3>Here, because this is the only place every panel passes through</h3>
     *
     * <p>A banner has to work for a tab that is <b>not</b> a document — the generated shader is a panel
     * type, not a {@code FileDocument} — so hanging it off the document layer would have missed the one
     * case that asked for it. {@code contentFor} sees every kind: document tabs, pane-backed panels and
     * plain registry-built ones alike.</p>
     *
     * <p><b>Nothing is wrapped when nothing answered</b>, which is nearly always. A wrapper column per
     * panel would add a layout level to every tab in the engine to serve the rare one, and the extra box
     * is not free — it is another flex context between a pane and its content.</p>
     *
     * <p>The content keeps growing into what is left: the grow is written at {@code DEFAULT} origin, so a
     * panel that states its own layout still wins, exactly as the pane host above does.</p>
     */
    private UIElement withBanners(DockPanelRef ref, UIElement built) {
        List<Notification> banners = DockBanners.bannersFor(ref);
        if (banners.isEmpty()) return built;

        UIElement column = new UIElement();
        column.addClass(BANNERED_CLASS);
        StyleGroup.defaultPipeline(column.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1f).flexBasis(0));
        for (Notification banner : banners) column.addChild(new DockBannerBar(banner));
        StyleGroup.defaultPipeline(built.getStyle().getLayoutGroup(),
                l -> l.flexGrow(1f).flexBasis(0));
        column.addChild(built);
        return column;
    }

    /** The banner column wrapping a panel's own content. Only present when something answered. */
    public static final String BANNERED_CLASS = "__dock-bannered__";

    private UIElement buildContent(DockPanelRef ref) {
        // A pane-backed panel gets a stable EMPTY host of its own. The pane's view moves into
        // whichever host is active -- see retargetPane -- so no element is ever in two tabs.
        if (area.registry().paneProviderFor(DockInput.of(ref)) != null) {
            UIElement host = new UIElement();
            host.addClass(PANE_HOST_CLASS);
            StyleGroup.defaultPipeline(host.getStyle().getLayoutGroup(),
                    l -> l.flexGrow(1f).flexBasis(0));
            return host;
        }
        UIElement built = area.registry().create(ref);
        if (built != null) return built;
        // An unbuildable panel is shown as an empty box rather than skipped: a tab with nothing
        // behind it is visible and reportable, while a silently absent tab looks like the layout
        // failed to restore.
        UIElement placeholder = new UIElement();
        placeholder.addClass("__missing__");
        return placeholder;
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
     * <p>Delegated to {@link InsertionMarker}, which is where the two rules now live: the first item whose
     * midpoint is past the pointer, and an index range of {@code [0, size]} so the far end stays reachable.
     * This was the original statement of both, and the stripes needing the same thing rotated ninety
     * degrees is what made it worth having once.</p>
     */
    public int insertionIndexAt(float screenX) {
        List<Tab> strip = tabs.getTabs();
        float y = strip.isEmpty() ? 0f : strip.get(0).getRuntimeCache().getY();
        return insertion.indexFor(this, strip, screenX, y);
    }

    /** Draws the caret at the boundary {@code index} would insert at. */
    void showInsertionMarker(int index) {
        insertion.showAt(this, tabs.getTabs(), index);
    }

    void hideInsertionMarker() {
        insertion.hide();
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
