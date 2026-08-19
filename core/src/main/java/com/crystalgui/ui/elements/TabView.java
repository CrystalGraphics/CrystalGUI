package com.crystalgui.ui.elements;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A tabbed container: a strip of {@link Tab} headers plus a stack of content panes, one visible.
 *
 * <pre>
 * TabView
 * ├── strip : ScrollerView   .__strip__     Tabs are DIRECT children — no viewport wrapper
 * │   ├── Tab
 * │   └── Tab
 * └── panes : UIElement      .__panes__
 *     ├── pane               .__pane__      display: none unless its tab is selected
 *     └── pane
 * </pre>
 *
 * <p>Content goes in through {@link Tab#content()}:</p>
 * <pre>
 * TabView tabs = new TabView();
 * tabs.addTab("Settings").content().addChild(mySettingsPanel);
 * </pre>
 *
 * <h3>Why it is this flat</h3>
 * <p>LDLib2's equivalent is eight wrapper elements deep before a single tab exists, because its strip
 * is a ScrollerView with a nested viewport and an anonymous throwaway wrapper inside it. Ours is two,
 * because {@link ScrollerView} here puts user content as <em>direct</em> children — scrolling is an
 * element capability, not a box you nest things in. The strip is still a real ScrollerView, so a
 * strip too long for the widget pans on the wheel.</p>
 *
 * <h3>Serialization</h3>
 * <p>The root round-trips through {@code UIDescriptionCodec} — internals are excluded and rebuilt by
 * this constructor — but <b>its tabs and their content do not</b>. A TabView's tabs live in the rail
 * and its panes in the panes container, both of which are internal, so a decoded TabView comes back
 * empty. Restoring them needs a TabView-specific {@code writeState}/{@code readState} pair that
 * records the tab list; deliberately not in the first serialization milestone.</p>
 */
public class TabView extends UIElement {

    /** Which edge the header strip sits on. */
    public enum TabSide {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT;

        /** True when the strip runs down a side, so tabs stack vertically and arrows are up/down. */
        public boolean isVertical() {
            return this == LEFT || this == RIGHT;
        }
    }

    public static final String STRIP_CLASS = "__strip__";
    /**
     * On the strip while this view holds no tabs, so a sheet can take the row out of the layout.
     *
     * <p>The strip has a {@code min-height} of its own — one chrome row, so a panel's tabs line up with
     * every other panel's title — and it keeps that height with nothing in it. The result is a blank band
     * between the panel's header hairline and its content's, reading as a second separator with a gap
     * where a control should be. Invisible in dark, where the band is the same colour as everything
     * around it; obvious the moment panels went white.</p>
     *
     * <p>A CLASS from here rather than a rule, because CSS cannot ask "does this element have children" —
     * there is no {@code :empty} in this engine and a count is not something a selector can see.</p>
     */
    public static final String EMPTY_CLASS = "__empty__";
    /** The scrolling rail inside the strip that actually holds the tabs. */
    public static final String RAIL_CLASS = "__rail__";
    /** The strip's scrollbar — a normal flex item, not an overlay. */
    public static final String STRIP_BAR_CLASS = "__strip-bar__";
    public static final String PANES_CLASS = "__panes__";

    /** One of these is present on the root at all times, so CSS can write
     * {@code tabview.__left__ .__strip__}. */
    public static final String TOP_CLASS = "__top__";
    public static final String BOTTOM_CLASS = "__bottom__";
    public static final String LEFT_CLASS = "__left__";
    public static final String RIGHT_CLASS = "__right__";

    /** Fires whenever the selected tab changes. Carries {@code null} when the last tab is removed. */
    public final Signal.Value<Tab> onTabSelected = new Signal.Value<>();

    private final UIElement strip;
    private final ScrollerView rail;
    private final Scroller bar;
    private final UIElement panes;
    private final List<Tab> tabs = new ArrayList<>();
    /**
     * A tab that has been selected and not yet scrolled to — see {@link #revealPendingTab}.
     *
     * <p>Held rather than acted on, because selection routinely happens to a tab that has no geometry
     * yet.</p>
     */
    @Nullable
    private Tab pendingReveal;

    /** Guards the two-way sync between {@link #bar} and {@link #rail} from feeding back on itself. */
    private boolean syncingBar = false;

    private Tab selectedTab;
    private TabSide tabSide = TabSide.TOP;

    public TabView() {
        // The strip is a plain box, NOT the scroll view. It stacks the tab rail and a real scrollbar
        // as ordinary flex items, because ScrollerView's own bars are absolutely positioned and would
        // float on top of the tabs — on a strip barely taller than one tab that eats their bottom
        // edge, and reserving padding to dodge it pushes every tab off the pane and kills the
        // selected tab's seam. A laid-out bar simply takes its own few pixels.
        this.strip = new UIElement();
        this.strip.addClass(STRIP_CLASS);
        // Starts empty, and a TabView built and never filled must not reserve the row either.
        this.strip.addClass(EMPTY_CLASS);
        addInternalChild(this.strip);

        // Scrolls, but shows nothing: the visible bar is the sibling below.
        this.rail = new ScrollerView() {
            @Override
            public UIElement setScroll(float left, float top) {
                super.setScroll(left, top);
                refreshStripBar();
                return this;
            }

            /**
             * The bar has to appear the moment the tabs stop fitting, not on the first scroll.
             * Hooking TabView's own onLayoutChanged is not enough: overflow inside the rail doesn't
             * change the TabView's geometry, so that hook never fires and the bar stays hidden until
             * something else moves. This is the same reason ScrollerView refreshes its own bars here.
             */
            @Override
            protected void onLayoutChanged() {
                super.onLayoutChanged();
                refreshStripBar();
                // HERE, for the same reason the bar refreshes here: this is the moment the rail knows how
                // wide its content is, and revealing a tab is a question about exactly that.
                revealPendingTab();
            }
        };
        this.rail.addClass(RAIL_CLASS);
        this.rail.setScrollbarsVisible(false);
        StyleGroup.defaultPipeline(rail.getStyle().getLayoutGroup(), l -> l.flexGrow(1));
        this.strip.addChild(this.rail);
        this.rail.markAsInternal();

        this.bar = new Scroller();
        this.bar.addClass(STRIP_BAR_CLASS);
        this.bar.attachListener(v -> {
            if (syncingBar) return;
            if (tabSide.isVertical()) rail.setScrollTop(v * rail.getMaxScrollTop());
            else rail.setScrollLeft(v * rail.getMaxScrollLeft());
        });
        this.bar.onScrollIntent.connect(f -> {
            if (tabSide.isVertical()) rail.setScrollTop(rail.getTargetScrollTop() + f * rail.getScrollHeight());
            else rail.setScrollLeft(rail.getTargetScrollLeft() + f * rail.getScrollWidth());
        });
        this.strip.addChild(this.bar);
        this.bar.markAsInternal();

        this.panes = new UIElement();
        this.panes.addClass(PANES_CLASS);
        // flex-grow is not optional here: this engine's FLEX_SHRINK defaults to 0 and MIN_WIDTH to
        // ZERO (both diverge from CSS), so without an explicit grow the panes container would sit at
        // its content size and leave the rest of the TabView empty. The matching zero basis — the other
        // half of the same problem, and the half that was missing — is set per-axis in setTabSide.
        StyleGroup.defaultPipeline(panes.getStyle().getLayoutGroup(), l -> l.flexGrow(1));
        StyleGroup.defaultPipeline(panes.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));
        addInternalChild(this.panes);

        setTabSide(TabSide.TOP);

        // BUBBLE, not target-phase: the focused element is a Tab *child*, so a (false, false)
        // listener on this root would never fire. Shape otherwise copied from SplitView.
        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || tabs.isEmpty()) return;
            boolean vertical = tabSide.isVertical();
            int step;
            switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_LEFT -> step = vertical ? 0 : -1;
                case CgKeyCodes.KEY_RIGHT -> step = vertical ? 0 : 1;
                case CgKeyCodes.KEY_UP -> step = vertical ? -1 : 0;
                case CgKeyCodes.KEY_DOWN -> step = vertical ? 1 : 0;
                case CgKeyCodes.KEY_HOME -> {
                    focusAndSelect(tabs.get(0));
                    event.stopPropagation();
                    return;
                }
                case CgKeyCodes.KEY_END -> {
                    focusAndSelect(tabs.get(tabs.size() - 1));
                    event.stopPropagation();
                    return;
                }
                default -> {
                    return;
                }
            }
            if (step == 0) return;   // the strip's cross axis — leave the key for someone else
            int from = selectedTab == null ? -1 : tabs.indexOf(selectedTab);
            int next = Math.max(0, Math.min(tabs.size() - 1, from + step));
            focusAndSelect(tabs.get(next));
            // Consume, so Tab-traversal/activation doesn't also act on a key we handled.
            event.stopPropagation();
        }, false, true);
    }

    /** Structure is fixed; content goes into {@link Tab#content()}. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Tabs ────────────────────────────────────────────────────────────────

    /** Adds a tab at the end. The first tab added becomes the selected one. */
    public Tab addTab(String label) {
        return addTabAt(label, tabs.size());
    }

    public Tab addTabAt(String label, int index) {
        int at = Math.max(0, Math.min(tabs.size(), index));
        Tab tab = new Tab(label);

        tabs.add(at, tab);
        rail.addChildAt(tab, at);
        panes.addChildAt(tab.content(), at);

        // Button already emits onPressed only when the release lands on the press target, and gets
        // Space/Enter for free from UIInputHandler — so this is the whole activation story.
        tab.attachListener(() -> selectTab(tab));

        if (selectedTab == null) selectTab(tab);
        else updateTabStops(); // a fresh Tab arrives tabbable (Button's default) — demote it
        syncStripEmptiness();
        return tab;
    }

    /**
     * Removes a tab and its pane.
     *
     * <p>If it was the selected one, selection moves to the tab that took its place, else to the last
     * tab; removing the only tab leaves nothing selected and emits {@code null}.</p>
     *
     * @return whether the tab belonged to this view
     */
    public boolean removeTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return false;

        tabs.remove(index);
        rail.removeChild(tab);
        panes.removeChild(tab.content());
        // Hands it back as an ordinary tabbable control. It is no longer part of any composite, so
        // leaving it at tabindex="-1" would make a re-used tab silently unreachable by keyboard.
        tab.setTabStop(true);

        if (selectedTab == tab) {
            tab.setSelected(false);
            selectedTab = null;
            if (tabs.isEmpty()) {
                onTabSelected.emit(null);
            } else {
                selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
            }
        } else {
            updateTabStops(); // it may have been holding the no-selection fallback stop
        }
        syncStripEmptiness();
        return true;
    }

    /** Keeps {@link #EMPTY_CLASS} in step with the tab count. Called from both mutators, never inferred. */
    private void syncStripEmptiness() {
        if (tabs.isEmpty()) strip.addClass(EMPTY_CLASS);
        else strip.removeClass(EMPTY_CLASS);
    }

    public void clearTabs() {
        for (Tab tab : new ArrayList<>(tabs)) removeTab(tab);
    }

    /** The tabs in strip order. Unmodifiable — use {@link #addTab}/{@link #removeTab}. */
    public List<Tab> getTabs() {
        return Collections.unmodifiableList(tabs);
    }

    public Tab getTab(int index) {
        return tabs.get(index);
    }

    public int getTabCount() {
        return tabs.size();
    }

    // ── Selection ───────────────────────────────────────────────────────────

    public Tab getSelectedTab() {
        return selectedTab;
    }

    public int getSelectedIndex() {
        return selectedTab == null ? -1 : tabs.indexOf(selectedTab);
    }

    /** Selects {@code tab}. Ignored if it doesn't belong to this view. Signals only on a real change. */
    public TabView selectTab(Tab tab) {
        if (tab != null && !tabs.contains(tab)) return this;
        if (selectedTab == tab) return this;

        if (selectedTab != null) selectedTab.setSelected(false);
        selectedTab = tab;
        if (selectedTab != null) selectedTab.setSelected(true);
        updateTabStops();
        // SCROLLED TO, not just selected. A strip with more tabs than fit gains a rail, and opening a
        // file then selected a tab nobody could see -- the editor changed and the strip did not move, so
        // it read as the wrong file having opened.
        //
        // Recorded AND attempted: see revealPendingTab.
        //
        // Attempted now because `onLayoutChanged` is not enough on its own -- it fires when the layout
        // CHANGES, and selecting a tab changes colours rather than geometry, so a tab that was already on
        // screen would sit pending forever with no pass coming to spend it. The deferral is for the other
        // case, where the tab was added this instant and a layout pass is already on its way.
        pendingReveal = selectedTab;
        revealPendingTab();

        onTabSelected.emit(selectedTab);
        return this;
    }

    /**
     * Scrolls the selected tab into view, once it has a size to scroll to.
     *
     * <p><b>Deferred, and it has to be.</b> A tab is usually selected the instant it is added — that is
     * what opening a file does — and at that moment it has never been laid out: its width is zero, the
     * rail's content width does not include it, and {@code scrollIntoView} would compute a distance
     * against numbers that are about to change. So the request is recorded and spent on the first layout
     * pass that gives the tab a width.</p>
     *
     * <p>Waiting for a <b>non-zero width</b> rather than for one pass is the difference between working
     * and nearly working. A tab's label is a {@code UIText}, which settles its own size over two or three
     * passes; taking the first pass would scroll to where the tab was before its text was measured, which
     * is short by however much the filename is wide. Leaving the request pending until there is something
     * to measure is self-correcting and costs one field read per layout.</p>
     *
     * <p>{@code scrollIntoView} moves the minimum distance and does nothing to an element already in
     * view, so this is also correct for the ordinary case of clicking a tab that is already on screen.</p>
     */
    private void revealPendingTab() {
        Tab wanted = pendingReveal;
        if (wanted == null) return;
        if (wanted.getParent() == null) {
            pendingReveal = null;
            return;
        }
        if (!(wanted.getRuntimeCache().getWidth() > 0f)) return;
        pendingReveal = null;
        wanted.scrollIntoView();
    }

    public TabView selectIndex(int index) {
        if (index < 0 || index >= tabs.size()) return this;
        return selectTab(tabs.get(index));
    }

    public TabView attachListener(Signal.Value.Listener<Tab> action) {
        onTabSelected.connect(action);
        return this;
    }

    /**
     * Keeps <b>exactly one</b> tab in the Tab sequence — the ARIA APG's roving tabindex, whose point is
     * that a ten-tab strip costs one Tab press to skip rather than ten. The arrow keys
     * ({@link #focusAndSelect}) move within it; Tab enters once and leaves once.
     *
     * <p>The stop follows selection, and falls back to the <b>first</b> tab when nothing is selected —
     * which {@link #selectTab(Tab) selectTab(null)} makes reachable. Without that fallback a deselected
     * strip would have zero tab stops and become entirely unreachable by keyboard, since every tab would
     * be {@code tabindex="-1"}. APG has the same rule for the same reason.</p>
     *
     * <p>Called from every path that changes selection or strip membership. Assigning all N each time is
     * deliberate over tracking the previous holder: {@code setFocusPolicy} already no-ops on an unchanged
     * value, tab counts are single digits, and a missed clear is the one failure mode that produces two
     * stops and silently defeats the whole pattern.</p>
     */
    private void updateTabStops() {
        Tab stop = selectedTab != null ? selectedTab : (tabs.isEmpty() ? null : tabs.get(0));
        for (Tab t : tabs) t.setTabStop(t == stop);
    }

    /** Arrow-key navigation moves focus with the selection, so the next arrow continues from there. */
    private void focusAndSelect(Tab tab) {
        selectTab(tab);
        var window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(tab);
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    public TabSide getTabSide() {
        return tabSide;
    }

    /**
     * Moves the header strip to an edge.
     *
     * <p>Two style writes, exactly as {@code SplitView.setOrientation} does. DOM order stays
     * strip-then-panes; the {@code _REVERSE} directions are what put the strip visually last for
     * {@code BOTTOM}/{@code RIGHT}, so tab order and paint order never diverge from the tree.</p>
     */
    public TabView setTabSide(TabSide side) {
        this.tabSide = side == null ? TabSide.TOP : side;

        FlexDirection rootDirection = switch (this.tabSide) {
            case TOP -> FlexDirection.COLUMN;
            case BOTTOM -> FlexDirection.COLUMN_REVERSE;
            case LEFT -> FlexDirection.ROW;
            case RIGHT -> FlexDirection.ROW_REVERSE;
        };
        boolean vertical = this.tabSide.isVertical();
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(rootDirection));
        // The strip stacks rail-then-bar ACROSS the tabs' own axis: a row of tabs gets the bar
        // beneath it, a column of tabs gets it alongside. So the strip's direction is the opposite
        // of the rail's.
        StyleGroup.defaultPipeline(strip.getStyle().getLayoutGroup(),
                l -> l.flexDirection(vertical ? FlexDirection.ROW : FlexDirection.COLUMN));
        StyleGroup.defaultPipeline(rail.getStyle().getLayoutGroup(),
                l -> l.flexDirection(vertical ? FlexDirection.COLUMN : FlexDirection.ROW));
        // A ZERO flex basis on the panes' MAIN axis, and it is not cosmetic.
        //
        // flex-grow alone is not enough, because flex-shrink is 0 here: with an `auto` basis the panes
        // container's basis IS its content size, so it can grow but never shrink, and a TabView narrower
        // (or shorter) than its widest page silently overflows its own parent. The panes background
        // stretches with it, so the overhang is visible as a pane spilling past the frame around it —
        // and only at small window sizes, because at large ones there is room to spare.
        //
        // Zero basis plus grow is the flexbox idiom: take no space of your own, then fill exactly what is
        // left. Both axes are written every time rather than only the main one, because switching sides
        // has to CLEAR the basis the previous side set — a leftover `width: 0` on a TOP strip would
        // collapse the panes to nothing.
        StyleGroup.defaultPipeline(panes.getStyle().getLayoutGroup(), l -> {
            if (vertical) l.width(0).heightAuto();
            else l.widthAuto().height(0);
        });
        bar.setOrientation(vertical ? Scroller.Orientation.VERTICAL : Scroller.Orientation.HORIZONTAL);
        refreshStripBar();

        removeClass(TOP_CLASS);
        removeClass(BOTTOM_CLASS);
        removeClass(LEFT_CLASS);
        removeClass(RIGHT_CLASS);
        addClass(switch (this.tabSide) {
            case TOP -> TOP_CLASS;
            case BOTTOM -> BOTTOM_CLASS;
            case LEFT -> LEFT_CLASS;
            case RIGHT -> RIGHT_CLASS;
        });
        return this;
    }

    /** The header strip — the box holding {@link #rail()} and {@link #bar()}. */
    public UIElement strip() {
        return strip;
    }

    /** The scrolling rail that actually holds the tabs. */
    public ScrollerView rail() {
        return rail;
    }

    /** The strip's scrollbar. Laid out as a normal flex item, and hidden when nothing overflows. */
    public Scroller bar() {
        return bar;
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        refreshStripBar();
    }

    /**
     * Points the bar at the rail's current scroll state, and hides it when there is nothing to
     * scroll — {@code overflow: auto} semantics, done by hand because the bar is ours rather than
     * ScrollerView's.
     */
    private void refreshStripBar() {
        if (syncingBar) return;
        syncingBar = true;
        try {
            boolean vertical = tabSide.isVertical();
            float max = vertical ? rail.getMaxScrollTop() : rail.getMaxScrollLeft();
            float content = vertical ? rail.getScrollHeight() : rail.getScrollWidth();
            float client = vertical ? rail.getClientHeight() : rail.getClientWidth();

            StyleGroup.importantPipeline(bar.getStyle().getLayoutGroup(),
                    l -> l.display(max > 0f ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
            if (max <= 0f) return;

            bar.setVisibleRatio(content <= 0f ? 1f : client / content);
            bar.setStepFraction(content <= 0f ? 0f : LINE_PX / content);
            bar.setValue((vertical ? rail.getScrollTop() : rail.getScrollLeft()) / max);
        } finally {
            syncingBar = false;
        }
    }

    /** One "line" of travel for a bar step — matches ScrollerView's own constant. */
    private static final float LINE_PX = 40f;

    /** The container holding every pane. Panes themselves come from {@link Tab#content()}. */
    public UIElement panes() {
        return panes;
    }
}
