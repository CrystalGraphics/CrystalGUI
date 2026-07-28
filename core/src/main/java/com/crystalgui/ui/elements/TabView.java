package com.crystalgui.ui.elements;

import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
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
        // its content size and leave the rest of the TabView empty.
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
                case CgUiKeyCodes.KEY_LEFT -> step = vertical ? 0 : -1;
                case CgUiKeyCodes.KEY_RIGHT -> step = vertical ? 0 : 1;
                case CgUiKeyCodes.KEY_UP -> step = vertical ? -1 : 0;
                case CgUiKeyCodes.KEY_DOWN -> step = vertical ? 1 : 0;
                case CgUiKeyCodes.KEY_HOME -> {
                    focusAndSelect(tabs.get(0));
                    event.stopPropagation();
                    return;
                }
                case CgUiKeyCodes.KEY_END -> {
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

        if (selectedTab == tab) {
            tab.setSelected(false);
            selectedTab = null;
            if (tabs.isEmpty()) {
                onTabSelected.emit(null);
            } else {
                selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
            }
        }
        return true;
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

        onTabSelected.emit(selectedTab);
        return this;
    }

    public TabView selectIndex(int index) {
        if (index < 0 || index >= tabs.size()) return this;
        return selectTab(tabs.get(index));
    }

    public TabView attachListener(Signal.Value.Listener<Tab> action) {
        onTabSelected.connect(action);
        return this;
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
