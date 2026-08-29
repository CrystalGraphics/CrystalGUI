package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.core.signal.Signal;
import javax.annotation.Nullable;
import java.util.List;
import java.util.ArrayList;
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

    public static final State<Tab, String> TEXT =
            State.<Tab, String>of("text", StateTypes.STRING, Tab::getText, Tab::setText, "")
                    .omittedWhen("");

    /**
     * The close button was pressed.
     *
     * <p>{@code plan_ui_rewrite.md} M1 asked for {@code Tab.SELECTED} here. A tab has no selection
     * signal of its own -- selection belongs to the strip, and {@code TabView.SELECT} reports it with
     * the index, which is what a server can act on. What a tab genuinely owns is its close REQUEST,
     * and that is the veto path M4 needs, so it is the one declared.</p>
     */
    public static final Event<Tab, Void> CLOSE_REQUESTED =
            Event.signal("closeRequested", (tab, sink) -> tab.onCloseRequested.connect(sink));

    public static final WidgetContract<Tab> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Tab.class, "tab")
                    .state(TEXT)
                    .event(CLOSE_REQUESTED)
                    .primary(TEXT)
                    .build());

    /** On the content pane, not on the tab itself. */
    public static final String PANE_CLASS = "__pane__";

    /** The close affordance — see {@link #setClosable}. */
    public static final String CLOSE_CLASS = "__close__";

    private final UIElement pane;
    private boolean selected = false;

    /** The close button, or null when this tab is not closable. @see #setClosable */
    @Nullable
    private Button close;

    /**
     * Someone pressed the close affordance.
     *
     * <p>A <b>request</b>, not a closure: a tab does not know what it is a tab for, and whichever thing
     * owns the document behind it may want to ask before discarding unsaved work. {@code DockGroup} routes
     * this to {@code DockArea.closePanel}, which is the same path the Close Panel command takes — so a tab
     * closed with the mouse and one closed with Ctrl+W cannot diverge.</p>
     */
    public final Signal.Action onCloseRequested = new Signal.Action();

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
     * A tab's description carries what is <em>in</em> it.
     *
     * <p>The pane is an internal child, so the default would describe a tab as empty — which is how a
     * TabView used to round-trip into a set of labelled, blank pages. The pane itself is not described:
     * it is rebuilt by the constructor, and only its contents are content.</p>
     */
    @Override
    protected List<UIElement> describedChildren() {
        return new ArrayList<>(pane.getChildren());
    }

    @Override
    protected void addDescribedChild(UIElement child) {
        pane.addChild(child);
    }

    @Override
    protected boolean acceptsDescribedChildren() {
        return true;
    }

    /** Empties the pane, not the tab. */
    @Override
    protected void clearDescribedChildren() {
        pane.clearAllChildren();
    }

    /**
     * Gives this tab a close button.
     *
     * <h3>The space is reserved and the glyph is not</h3>
     *
     * <p>The button exists from the moment a tab is closable and is <b>faded rather than hidden</b> until
     * the pointer is over the tab or the tab is the selected one — which is what IntelliJ, VS Code and
     * every browser do, and the reason is layout rather than taste. A tab that grows a button on hover
     * changes width, so the tabs to its right move; do that on a strip and the tab under the pointer
     * shifts out from under it, and pressing where a label was closes the neighbour instead. The width has
     * to be paid for whether or not the glyph is drawn.</p>
     *
     * <p>Which is why the sheet does this with {@code opacity} and never {@code display}. Being invisible
     * and still hit-testable costs nothing: reaching it requires hovering the tab, and hovering is what
     * makes it appear — so there is no state in which a user can press a button they cannot see.</p>
     *
     * <h3>Pressing it does not select the tab</h3>
     *
     * <p>And that needs no {@code stopPropagation}, which is worth stating because reaching for one is the
     * obvious move. A {@code Button}'s activation comes from its {@code defaultEvents}, which fire only in
     * the TARGET phase — so the press whose target is the close button reaches the tab in the BUBBLE
     * phase, where the tab's own activation does not run. Stopping propagation here would additionally
     * pre-empt anything a caller had attached to the tab, since a stop halts the remaining listeners on
     * that element and phase rather than only the walk.</p>
     */
    public Tab setClosable(boolean closable) {
        if (closable == (close != null)) return this;
        if (!closable) {
            removeInternalChild(close);
            close = null;
            return this;
        }
        // A Button rather than a bare element: it is a control, so it wants the hover, press and focus
        // behaviour every other control has, and the sheet styles it as one.
        Button button = new Button("");
        button.addClass(CLOSE_CLASS);
        button.markAsInternal();
        button.attachListener(onCloseRequested::emit);
        close = button;
        addInternalChild(button);
        return this;
    }

    /** @see #setClosable */
    public boolean isClosable() {
        return close != null;
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
