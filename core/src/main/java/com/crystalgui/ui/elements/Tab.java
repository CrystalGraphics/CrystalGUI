package com.crystalgui.ui.elements;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyDisplay;

/**
 * One header in a {@link TabView}, together with the content pane it shows.
 *
 * <p>Extends {@link Button}, so it inherits the label, the {@code __pre-icon__}/{@code __post-icon__}
 * slots, and Space/Enter activation — a tab is a button that happens to latch.</p>
 *
 * <h3>Selection is a pseudo-class, not a marker class</h3>
 * <p>{@link #isChecked()} is overridden, and {@code UIElement.isChecked} is already bound to
 * {@code PseudoClasses.CHECKED}, so {@code tab:checked { … }} works with no engine change and no
 * add/remove class bookkeeping. (LDLib2 hand-manages a {@code __tab_selected__} class *and*
 * hard-codes a texture swap in Java that bypasses its stylesheet entirely; its add/remove spellings
 * don't even match, so the class never clears.)</p>
 *
 * <h3>The pane lives elsewhere in the tree</h3>
 * <p>{@link #content()} is <b>not</b> a child of this tab — it sits in the TabView's panes container,
 * because the header strip and the content area are separate layout regions. The tab merely owns the
 * reference. A Tab built standalone (by {@code ElementRegistry}) has a pane that is simply not
 * attached to anything until a TabView adopts it.</p>
 *
 * <h3>A whole tablist is one tab stop</h3>
 * <p>One tab in a strip is {@code FocusPolicy.CLICK}; every other is {@link FocusPolicy#CLICK_NOT_TABBABLE}.
 * This is the ARIA APG's <b>roving tabindex</b>: Tab enters the strip once, arrow keys move within it,
 * and Tab again leaves for whatever follows. Ten tabs cost one press to skip, not ten — the entire
 * reason the pattern exists.</p>
 *
 * <p><b>{@link TabView} owns which tab that is</b>, via {@code updateTabStops()} — normally the selected
 * one, falling back to the first when nothing is selected. {@link #setTabStop} is package-private
 * precisely so a Tab cannot decide this for itself; see that method for why. Arrow-key navigation lives
 * in TabView too.</p>
 *
 * <p>Not overridable by CSS, deliberately: which control in a composite holds the tab stop is a
 * behavioural invariant of the composite, and the web doesn't expose {@code tabindex} to stylesheets
 * either.</p>
 */
public class Tab extends Button {

    /** On the content pane, not on the tab itself. */
    public static final String PANE_CLASS = "__pane__";

    private final UIElement pane;
    private boolean selected = false;

    public Tab(String label) {
        super(label);

        this.pane = new UIElement();
        this.pane.addClass(PANE_CLASS);
        // Clips, for the reason SplitView's panes do: a pane is a bounded region, and `overflow:
        // hidden` also feeds Taffy and zeroes the automatic min-size, so an oversized child can't
        // force the whole TabView wider than it asked to be. DEFAULT origin — a theme may override.
        StyleGroup.defaultPipeline(pane.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.HIDDEN));
        StyleGroup.defaultPipeline(pane.getStyle().getLayoutGroup(), l -> l.flexGrow(1));

        applyPaneVisibility();
    }

    /** The content pane. An ordinary element — it accepts children normally. */
    public UIElement content() {
        return pane;
    }

    /**
     * Only the label. Selection is deliberately absent: it is a TabView-wide invariant (exactly one
     * tab), so it belongs to the TabView's own state, and a Tab restoring {@code selected} on its own
     * could leave two tabs checked at once.
     */
    @Override
    protected <T> void writeState(StateMap<T> out) {
        out.putStringIfNot("text", getText(), "");
    }

    @Override
    protected <T> void readState(StateMap<T> in) {
        setText(in.getString("text", ""));
    }

    /** Drives {@code tab:checked}. */
    @Override
    public boolean isChecked() {
        return selected;
    }

    /**
     * Package-private on purpose: selection is a TabView-wide invariant (exactly one tab at a time),
     * so it is set through {@link TabView#selectTab}, never on a tab in isolation.
     */
    void setSelected(boolean selected) {
        if (this.selected == selected) return;
        this.selected = selected;
        applyPaneVisibility();
        onStyleChanged();
        // Re-matches descendants too, so a descendant rule like `tab:checked text` restyles with the
        // selection and not just the tab itself — the same pair Checkbox.setChecked uses.
        invalidateStyleMatch();
    }

    /**
     * Shows or hides the pane with {@code display}, rather than adding/removing it from the tree.
     *
     * <p>Element identity, listeners and scroll position all survive a tab switch this way, and the
     * engine already treats {@code display: none} as invisible everywhere that matters: hit-testing
     * skips it, and {@code LayoutProperties.init()} hooks {@code invalidateFocusableChain()} onto
     * every display change, so Tab-traversal cannot walk into a hidden pane.</p>
     *
     * <p>IMPORTANT origin because this is runtime state — a stylesheet has no business overriding
     * which pane is showing.</p>
     */
    private void applyPaneVisibility() {
        StyleGroup.importantPipeline(pane.getStyle().getLayoutGroup(),
                l -> l.display(selected ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /**
     * Whether Tab may land on this tab — the roving half of the roving-tabindex pattern.
     *
     * <p>Package-private for the same reason {@link #setSelected} is: "exactly one tab in the strip is
     * tabbable" is a TabView-wide invariant, so {@link TabView#updateTabStops()} is the only caller.
     * Setting it per-tab from outside could leave a strip with zero tab stops, which makes an entire
     * tablist unreachable by keyboard — a far worse failure than a stray focus ring.</p>
     *
     * <p>Either way the tab stays clickable and stays reachable by the arrow keys; only Tab's view of it
     * changes. {@code invalidateFocusableChain()} is deliberately not called — the
     * {@code hasFocusableDescendant} cache is keyed on {@code focusable()}, which is identical for the
     * two policies, so nothing it memoizes has changed.</p>
     */
    void setTabStop(boolean tabStop) {
        setFocusPolicy(tabStop ? FocusPolicy.CLICK : FocusPolicy.CLICK_NOT_TABBABLE);
    }
}
