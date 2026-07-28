package com.crystalgui.ui.elements;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.UIElement;
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
        // Re-matches descendants too, which is what makes `tab:checked .__label__` work — the same
        // pair Checkbox.setChecked uses.
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
}
