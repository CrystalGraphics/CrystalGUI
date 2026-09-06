package com.crystalgui.workbench.dock;

import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.dock.banner.DockBannerBar;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.drag.DockDropZones;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockPane;
import com.crystalgui.workbench.dock.panel.DockPaneProvider;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.dnd.InsertionMarker;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import com.crystalgui.core.dispose.Disposer;
import java.util.HashMap;
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
 *
 * <p>It is also built <b>lazily</b> — a tab restored from a session is a title until something activates
 * it. See {@link #showContent}.</p>
 */
public class DockGroup extends UIElement {
    /** One leaf, drawn: a tab strip plus whatever the active panel is. */
    public static final Name NAME = Name.of("dockgroup");


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
    /**
     * The gap a drag opens where the tab would land.
     *
     * <p>{@link InsertionMarker.Mode#IN_FLOW}, so the strip rearranges under the pointer and you are
     * looking at the arrangement you are about to get rather than at a caret promising one. Both
     * references do it this way for the lists you reorder by hand, and it is what the stripe rail beside
     * this already does.</p>
     */
    private final InsertionMarker insertion =
            new InsertionMarker(InsertionMarker.Axis.HORIZONTAL).mode(InsertionMarker.Mode.IN_FLOW);

    /** Panel → its built content. Survives every rebuild of the tree above. */
    private final Map<DockPanelRef, UIElement> content = new LinkedHashMap<>();

    /** Panel → its tab, so a reconcile can tell "already there" from "new". */
    private final Map<DockPanelRef, Tab> tabByPanel = new LinkedHashMap<>();

    public DockGroup(DockArea area, DockLeaf leaf) {
        super(NAME);
        this.area = area;
        this.leaf = leaf;

        append(tabs);

        // The overlay must NOT take the pointer: an overlay that is hittable ends the drag on top of
        // itself, and the drop reads as having done nothing. setHitTest(false) covers the whole subtree,
        // which is what is wanted here — nothing inside a preview is interactive.
        overlay.addClass(OVERLAY_CLASS);
        overlay.setHitTest(false);
        hideDropPreview();
        append(overlay);


        // PARKED IN THE RAIL, not in the group: an in-flow gap is a SIBLING of the things it makes room
        // between, and the tabs live two levels down in the strip's scrolling rail. The group stays the
        // coordinate frame every query is asked in -- see InsertionMarker.flowParent.
        insertion.parkIn(tabs.rail());

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
                // AND BRING THE VIEW WITH IT. Every OTHER route to a selection change -- the file tree,
                // Ctrl+Tab, activatePanel, openFile -- calls syncGroups itself; a tab click was the one
                // that changed the model and left the view to TabView, which only knows how to swap
                // which content box is visible. That was survivable while every box was already filled.
                // It is not now: content is built on activation, and a click is an activation.
                //
                // sync() rather than a private half of it, because "make the view match the leaf" has
                // one definition and this is it. rebuildStrip does not run -- the panel LIST is
                // unchanged, only the selection -- so the tab being clicked is never rebuilt under the
                // click, and `syncing` stops the write-back re-entering through this same listener.
                sync();
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
            List<DockPanelRef> current = new ArrayList<>(tabByPanel.keySet());
            if (!wanted.equals(current) && !reconcileStrip(wanted, current)) {
                rebuildStrip(wanted);
            }
            Tab active = tabByPanel.get(leaf.activePanel());
            if (active != null && tabs.getSelectedTab() != active) tabs.selectTab(active);
            prunePanes(wanted);
            // BEFORE retargetPane, which moves a pane's view into the active panel's HOST -- and with
            // content built lazily that host does not exist until this line has run. Reversed, the first
            // activation of a pane-backed panel finds no host and silently moves nothing.
            showContent(leaf.activePanel());
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
        for (Tab tab : tabs.getTabs()) tab.content().removeAll();
        tabs.clearTabs();
        tabByPanel.clear();
        // WITH THEM, because `addTab` below builds NEW Tab objects every time -- and this runs on every
        // split, drag and reorder. Keyed by Tab, so without this the map keeps one dead tab and its
        // tooltip per rebuild, forever.
        tabTooltips.clear();

        for (int i = 0; i < wanted.size(); i++) buildTabAt(wanted.get(i), i);
    }

    /**
     * Builds one tab for {@code panel} and files it — the body {@link #rebuildStrip} used to inline.
     *
     * <p>Extracted so {@link #reconcileStrip} can add a single tab without rebuilding the strip around
     * it. Everything here was already per-tab; nothing about it depended on being inside that loop.</p>
     */
    private Tab buildTabAt(DockPanelRef panel, int index) {
        {
            Tab tab = tabs.addTabAt(area.registry().titleOf(panel), index);
            // AN ELEMENT FIRST, because a name cannot carry a declaration's static and final marks --
            // those are stacked layers rather than a picture. @see DockPanelRegistry#iconElementOf
            applyIconTo(tab, panel);
            // AT BUILD TIME, which is the whole reason the decoration is pulled from a provider rather
            // than pushed onto the tab: the strip is rebuilt on every rearrangement, and anything pushed
            // would have to be pushed again by somebody who noticed.
            applyDecoration(tab, panel);
            // AFTER the icon, because the icon is what the tooltip's second answer is anchored to.
            applyTooltip(tab, panel);
            // CLOSABILITY IS THE PANEL TYPE'S, and it is already recorded there -- a console or an editor
            // may be closed, a region's permanent host may not. Routed to the same `closePanel` the Close
            // Panel command uses, so the mouse and the keyboard cannot come to mean different things.
            //
            // THIS WAS LOST IN A MERGE and nothing noticed for two days. `1f9b5b3` added it directly
            // above the `contentFor` line; `d397b9d` resolved that hunk in favour of the other side and
            // took both. `TabCloseAndRevealTest` stayed green throughout because it drives `Tab` on its
            // own -- the WIDGET never broke, only the wiring to it, which is the half no test reached.
            // `aDockTabCarriesACloseButton` is that half now.
            if (area.registry().isClosable(panel)) {
                tab.setClosable(true);
                tab.onCloseRequested.connect(() -> area.closePanel(panel));
            }
            // AND NO CONTENT. See showContent: a tab is a title until it is looked at.
            tabByPanel.put(panel, tab);
            area.installTabDrag(this, panel, tab);
            return tab;
        }
    }

    /**
     * Brings the strip into line by adding and removing tabs — <b>never by rebuilding it</b>.
     *
     * <h3>What a rebuild costs, and why it is paid by panels that did not change</h3>
     *
     * <p>{@link #rebuildStrip} opens with {@code tab.content().removeAll()} over every tab, then
     * destroys and recreates all of them. The detached content is the part that matters: a panel whose
     * pane is emptied loses its place in the tree, and {@link #showContent} only ever re-attaches the
     * ACTIVE one — so opening a second file into a group detaches the first one's editor, and it stays
     * detached until something activates it again.</p>
     *
     * <p>The bill then arrives at the wrong moment. Closing a tab activates the survivor, whose editor is
     * attached from scratch: ~200 elements registered, {@code style:drainDirtyMatch 6,607us},
     * {@code layout 7,282us} — reported as the cost of CLOSING a file, when the close itself is under
     * 4ms and the work was caused by an OPEN that happened much earlier.</p>
     *
     * <p><b>Adds and removes only.</b> A reorder — the same panels in a different order — still falls
     * through to the full rebuild, so this narrows what the fast path claims rather than widening what
     * the slow one must handle. Returns false for "not my case", never for "I did half of it": every
     * check that can refuse runs before anything is mutated.</p>
     */
    private boolean reconcileStrip(List<DockPanelRef> wanted, List<DockPanelRef> current) {
        // THE SURVIVORS, IN EACH LIST'S OWN ORDER. If those disagree the panels were REORDERED, and
        // reordering tabs in place is a different operation from adding and removing them.
        List<DockPanelRef> keptFromCurrent = new ArrayList<>();
        for (DockPanelRef panel : current) {
            if (wanted.contains(panel)) keptFromCurrent.add(panel);
        }
        List<DockPanelRef> keptFromWanted = new ArrayList<>();
        for (DockPanelRef panel : wanted) {
            if (current.contains(panel)) keptFromWanted.add(panel);
        }
        if (!keptFromCurrent.equals(keptFromWanted)) return false;
        // Every survivor still has a tab to keep. Checked before mutating, so a refusal costs nothing.
        for (DockPanelRef panel : keptFromCurrent) {
            if (!tabByPanel.containsKey(panel)) return false;
        }

        for (DockPanelRef panel : current) {
            if (wanted.contains(panel)) continue;
            Tab tab = tabByPanel.remove(panel);
            // ITS content, detached before the tab goes, for the reason rebuildStrip records: removing a
            // tab takes its subtree with it, and this group may still be caching that element.
            tab.content().removeAll();
            tabTooltips.remove(tab);
            tabs.removeTab(tab);
        }
        // AFTER the removals, so an arrival's index is against the strip it is actually joining.
        for (int i = 0; i < wanted.size(); i++) {
            if (!tabByPanel.containsKey(wanted.get(i))) buildTabAt(wanted.get(i), i);
        }
        // AND EVERY SURVIVOR RE-READS ITS PRESENTATION, in place.
        //
        // A rebuild pulled the title, icon and decoration afresh for every tab, and that was load-bearing
        // rather than incidental: a panel that went dirty while the strip was untouched relied on the next
        // structural change to show it, which `aRebuiltStripStillHasIconsAndDecoratedTitles` pins. Keeping
        // the ELEMENT is the point of this method; keeping a stale title with it is not, and re-reading is
        // a text and an icon rather than a tree.
        for (DockPanelRef panel : keptFromCurrent) refreshPresentation(panel);
        // AND THE MAP RE-ORDERED. `sync` compares this map's KEY ORDER against the leaf's panel list, and
        // a tab inserted in the middle lands at the end of a LinkedHashMap's iteration — so without this
        // the very next sync would see a mismatch it cannot explain and rebuild the strip anyway.
        Map<DockPanelRef, Tab> ordered = new LinkedHashMap<>();
        for (DockPanelRef panel : wanted) ordered.put(panel, tabByPanel.get(panel));
        tabByPanel.clear();
        tabByPanel.putAll(ordered);
        return true;
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
        applyIconTo(tab, panel);
    }

    /**
     * Re-reads the tab's icon — <b>which used to be pulled once and kept.</b>
     *
     * <p>That was right while an icon was a function of the file NAME: the name is in the ref, the ref is
     * immutable, and a panel whose ref changed is a different panel. It stopped being right when a tab
     * started showing what its file DECLARES. That answer is read through {@code ProjectSources}, which
     * does not have it until the file has been read — so the tab is built before the answer exists, and
     * an icon pulled once is the file-type glyph forever.</p>
     *
     * <p>The tooltip's second region is re-anchored with it, because {@link #applyIconElement} REPLACES
     * the slot: a region registered against the old element points at something no longer in the tree,
     * so the icon would keep its picture and lose its words. The tooltip itself is retained rather than
     * re-attached, since {@code Tooltip.attach} adds a listener pair rather than replacing one.</p>
     */
    private void applyIconTo(Tab tab, DockPanelRef panel) {
        // AN ELEMENT FIRST, for the reason the build path gives: a name cannot carry a declaration's
        // static and final marks.
        UIElement glyph = area.registry().iconElementOf(panel);
        if (glyph != null) applyIconElement(tab, glyph);
        else applyIcon(tab, area.registry().iconOf(panel));

        Tooltip tooltip = tabTooltips.get(tab);
        String iconText = area.registry().iconTooltipOf(panel);
        UIElement icon = tab.getPreIcon();
        if (tooltip != null && iconText != null && icon != null) tooltip.addRegion(icon, iconText);
    }

    /** The tooltip attached to each tab, so its icon region can be re-anchored. @see #applyIconTo */
    private final Map<Tab, Tooltip> tabTooltips = new HashMap<>();

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
    /**
     * Puts a caller's own element in the tab's icon slot.
     *
     * <p>{@code setOnlyChild} rather than an add: a strip is rebuilt on every rearrangement and a tab is
     * pooled, so adding would stack a new glyph on the last one every time the panel list moved.</p>
     */
    private static void applyIconElement(Tab tab, UIElement glyph) {
        // THE ELEMENT IS THE SLOT, set the way applyIcon sets its own. This read `getPreIcon()` and
        // filled it, which answers NULL on a tab that has never had one -- so a viewer tab lost its icon
        // entirely while every project tab kept theirs, because those go through the NAME path and its
        // `setPreIcon` is what creates the slot in the first place.
        //
        // `setPreIcon` also adds PRE_ICON_CLASS, which is what the sheet sizes and spaces the icon by, so
        // a child stuffed into an existing slot would have inherited none of that even where one existed.
        tab.setPreIcon(glyph);
    }

    /**
     * Gives a tab its hover text — and its icon a second answer, where the icon means something of its
     * own.
     *
     * <h3>One tooltip with two regions, not two tooltips</h3>
     *
     * <p>The icon is unhittable, as every composite part is: click-focus targets the exact element hit
     * rather than the nearest focusable ancestor, so a hittable icon swallows the press that selects the
     * tab and the drag that starts from it. An unhittable element never receives {@code mouseenter}, so a
     * tooltip attached to it could not fire at all — and even if it could, {@code Enter} is dispatched to
     * every element in the entered chain, so the tab's tooltip and the icon's would both show, stacked.
     * {@link Tooltip#addRegion} is the one mechanism that answers both.</p>
     *
     * <p><b>Attached once per tab, and never a second time</b> — {@code Tooltip.attach} adds a listener
     * pair rather than replacing one, so calling it twice leaves two tooltips showing. The instance is
     * retained instead, and {@link #applyIconTo} re-anchors the ICON region when the icon is re-read.
     * (The icon itself used to share this paragraph's "never refreshed" reasoning. It no longer can: a
     * tab now shows what its file declares, and that answer arrives after the tab does.)</p>
     *
     * <p>An icon answer without a tab answer is not wired: the base text is what the tooltip says
     * everywhere else on the tab, and an empty one draws a bare rounded box over most of the control.</p>
     */
    private void applyTooltip(Tab tab, DockPanelRef panel) {
        String text = area.registry().tooltipOf(panel);
        if (text == null) return;

        Tooltip tooltip = Tooltip.attach(tab, text);
        // RETAINED, so a later icon can re-anchor its region rather than attaching a second tooltip.
        tabTooltips.put(tab, tooltip);
        String iconText = area.registry().iconTooltipOf(panel);
        UIElement icon = tab.getPreIcon();
        if (iconText != null && icon != null) tooltip.addRegion(icon, iconText);
    }

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

    /**
     * Builds {@code panel}'s content if this is the first time it has been looked at, and puts it in its
     * tab.
     *
     * <h3>A restored tab is a title until it is activated</h3>
     *
     * <p>{@link #rebuildStrip} used to call {@link #contentFor} for every panel in the leaf, so restoring a
     * session with five tabs open built five editors on the frame the workbench appeared — four of them
     * behind a {@code display: none} nobody was going to look at. For a document that is not a cheap
     * element: a document kind builds a {@link com.crystalgui.ui.elements.editor
     * .TextEditor}, a fresh tokenizer with its own parse tree, a fresh {@code LanguageServices} that
     * starts a compile, and reads the file off disk. <b>Measured at ~480 ms for two tabs</b> in a real
     * client, which was the largest single item left in a first editor open.</p>
     *
     * <p>So the cost moves to the activation that asks for it, and a session's tab count stops being
     * something the first frame pays for: twenty restored tabs cost what one does. This is what VS Code
     * does — a background editor is restored as a placeholder and materialised when it is selected — and
     * the reason it is safe here is that the dock already keeps {@link #content} keyed per panel, so
     * building late is building <em>once</em>, not building repeatedly.</p>
     *
     * <h3>What does not change</h3>
     *
     * <p>The tab itself is complete from the start — title, icon and {@code decoration-*} class all come
     * from the registry rather than from the content, so an unmaterialised tab is fully drawn, fully
     * draggable and fully closable. Nothing observable is deferred except the widget behind it.</p>
     *
     * <p>{@code setOnlyChild} rather than {@code addChild}: it early-returns when the content is already
     * the only child there, so every {@link #sync} may call this and only the first does any work.</p>
     */
    private void showContent(@Nullable DockPanelRef panel) {
        if (panel == null) return;
        Tab tab = tabByPanel.get(panel);
        if (tab == null) return;
        tab.content().setOnlyChild(contentFor(panel));
    }

    private UIElement contentFor(DockPanelRef panel) {
        return content.computeIfAbsent(panel, ref -> withBanners(ref, buildContent(ref)));
    }

    /** What has been built for {@code panel}, or null. What is actually on screen for it. */
    @Nullable
    public UIElement builtContentFor(DockPanelRef panel) {
        return content.get(panel);
    }
    
    /**
     * Drops the built widget for a panel that has been closed, so a reopen builds a fresh one.
     *
     * <p>The memo is keyed by {@link DockPanelRef}, which is a <b>value</b> — reopening the same file
     * produces an equal ref and would otherwise be handed the widget built for the document that was just
     * disposed. Nothing about that looks wrong until something touches it, and then it is an
     * {@code IllegalStateException: Parser is closed} out of a frame tick.</p>
     *
     * <p>Detached as well as forgotten. The element is still a child of the tab it was shown in, and the
     * strip that owned it is about to be rebuilt around a panel that no longer exists — so leaving it
     * attached keeps a whole editor, its buffer and its language services alive behind a tab nobody can
     * see. @see DockArea#closePanelDiscarding</p>
     */
    void forgetContent(DockPanelRef panel) {
        UIElement built = content.remove(panel);
        if (built != null) built.removeSelf();
    }

    /**
     * Puts whatever this dock's banner providers had to say above {@code built}.
     *
     * <p>Asked of the panel {@link com.crystalgui.workbench.dock.panel.DockPanelRegistry registry},
     * which is the workbench's — a provider is a closure over the workbench it answers for, so a
     * process-wide list of them held every workbench that ever contributed one.</p>
     *
     * <h3>Here, because this is the only place every panel passes through</h3>
     *
     * <p>A banner has to work for a tab that is <b>not</b> a document — the generated shader is a panel
     * type, not a document — so hanging it off the document layer would have missed the one
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
        List<Notification> banners = area.registry().bannersFor(ref);
        if (banners.isEmpty()) return built;

        UIElement column = new UIElement();
        column.addClass(BANNERED_CLASS);
        StyleGroup.defaultPipeline(column.getStyle().getLayoutGroup(),
                l -> l.flexDirection(FlexDirection.COLUMN).flexGrow(1f).flexBasis(0));
        for (Notification banner : banners) column.append(new DockBannerBar(banner));
        StyleGroup.defaultPipeline(built.getStyle().getLayoutGroup(),
                l -> l.flexGrow(1f).flexBasis(0));
        column.append(built);
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
        return tabs.getTabs().stream().anyMatch(tab -> tab.containsSurfacePoint(screenX, screenY))
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
        // THE STRIP'S OWN BOX, never the first tab's. The first tab is hidden for the whole of a drag
        // that started on it -- see beginTabDrag -- so it reports a zero box, and a band derived from one
        // matches nothing: dragging the only tab in a group fell straight through the strip and offered
        // to SPLIT the pane instead of reordering. It is also the more honest band, since it includes the
        // scrollbar row the strip reserves under the tabs.
        Box self = box();
        Box strip = tabs.strip().box();
        if (self == null || strip == null) return false;
        // `toLocal` puts THIS group's origin at zero, so the horizontal band is [0, width) with no
        // origin term -- subtracting `self.x()` as the old engine did would count it twice. The
        // strip is a DESCENDANT, and `Box.x()` is parent-relative here, so its offset in this
        // group's space is `originIn` rather than a subtraction of two boxes' x.
        var local = toLocal(screenX, screenY);
        float stripY = Box.originIn(strip, self).y();
        float y = local.y();
        return local.x() >= 0f && local.x() <= self.width()
                && y >= stripY && y <= stripY + strip.height();
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
        // The empty case already answers 0; a tab that has not been laid out answers the same. The
        // strip's own band is what `stripBandContains` reads for exactly this reason.
        Box firstTab = strip.isEmpty() ? null : strip.get(0).box();
        float y = firstTab == null ? 0f : firstTab.y();
        return insertion.indexFor(this, strip, screenX, y);
    }

    /** Draws the caret at the boundary {@code index} would insert at. */
    void showInsertionMarker(int index) {
        insertion.showAt(this, tabs.getTabs(), index);
    }

    void hideInsertionMarker() {
        insertion.hide();
    }

    /**
     * Takes the dragged tab out of the strip and opens the gap where it stood. Idempotent.
     *
     * <p>Called from the drag's first TICK rather than from the press — see
     * {@link InsertionMarker#withdraw}, which carries the whole argument and the two rules that go
     * with it.</p>
     */
    void beginTabDrag(Tab tab) {
        insertion.withdraw(this, tabs.getTabs(), tab);
    }

    /** Puts it back and closes the gap. Idempotent, and safe when this group started no drag. */
    void endTabDrag() {
        insertion.restore();
    }

    // ── Drop preview ────────────────────────────────────────────────────────────────────────────

    /**
     * Covers the part of this group a drop would take.
     *
     * <p><b>INLINE origin, where the old engine wrote at IMPORTANT.</b> The new engine may not write
     * at the origin an author's {@code !important} lives at — {@code EngineBoundaryTest} scans the
     * constant pool for it — and this overlay is the same thing the desktop's own snap preview is: a
     * transient rect nothing in a sheet has an opinion about. {@code Desktop.snapPreview} is the
     * reference spelling.</p>
     */
    void showDropPreview(DockDropZone zone) {
        Box box = box();
        if (box == null) return;
        float[] rect = DockDropZones.previewRect(zone, box.width(), box.height());
        StyleGroup.inlinePipeline(overlay.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(rect[0]).top(rect[1])
                .width(rect[2]).height(rect[3]));
        StyleGroup.inlinePipeline(overlay.getStyle().getGeneralGroup(), g -> g.opacity(1f));
    }

    /**
     * Hides the preview.
     *
     * <p>Kept in the tree at zero opacity rather than removed: taking it out and putting it back is a
     * structural change to a subtree a drag is live over, and it is the cheaper of the two anyway.</p>
     */
    void hideDropPreview() {
        StyleGroup.inlinePipeline(overlay.getStyle().getLayoutGroup(), l -> l
                .positionType(TaffyPosition.ABSOLUTE)
                .left(0f).top(0f).width(0f).height(0f));
        StyleGroup.inlinePipeline(overlay.getStyle().getGeneralGroup(), g -> g.opacity(0f));
    }
}
