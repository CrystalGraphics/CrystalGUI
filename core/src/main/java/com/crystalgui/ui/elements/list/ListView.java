package com.crystalgui.ui.elements.list;

import com.crystalgui.core.property.ObservableList;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A windowed view over an {@link ObservableList} — only the visible rows exist as elements, and they are
 * recycled as it scrolls.
 *
 * <h3>Why this is a widget and not an optimisation</h3>
 * <p>Everything else in this engine puts real elements in the tree, and that is the right default: the
 * cascade works, {@code querySelector} works, focus works, serialization works. It stops being right at
 * scale — a 200k-line file is 200k Taffy nodes, in memory and in every tree walk, however cheap each one
 * is. The web's own answer to the milder version of this is {@code content-visibility: auto}, which keeps
 * the elements and merely skips their layout and paint; that is a better answer where it fits, and it
 * does not fit here.</p>
 *
 * <p>So a {@code ListView} answers a different question — <em>should this element exist at all?</em> — and
 * pays for it in the ways listed under "What this genuinely breaks" below. Do not reach for it because a
 * list feels big. Reach for it when the element count is unbounded.</p>
 *
 * <h3>It is a {@link ScrollerView}, and that is not laziness</h3>
 * <p>Setting {@code overflow} is <b>not</b> enough to make something scrollable by the wheel here: a bare
 * element is programmatic-scroll only however its overflow is set, and opting into the wheel is precisely
 * what makes something a scroll <em>view</em>. A hundred thousand rows that could only be scrolled from
 * code is not a widget. Extending also brings real scrollbars, whose thumb is sized from
 * {@link #getMaxScrollTop()} — which reads through the {@link #getScrollHeight()} override below, so it
 * reflects the model rather than the dozen realised rows, with no further work.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ListView<String> list = new ListView<>(model);          // an ObservableList<String>
 * list.setItemHeight(14f);
 * list.setRenderer(new ListRenderer<String>() {
 *     public UIElement createTemplate() { ... }           // once — structure and listeners
 *     public void bind(String item, int i, UIElement t) { ... }   // per row — data only
 * });
 * }</pre>
 *
 * <h3>How the window is realised</h3>
 * <p>Rows are <b>absolutely positioned internal children</b> at {@code top = strategy.offsetOf(index)}.
 * That is what lets scrolling cost nothing: this engine applies a scroll offset as a pose translate when
 * painting descendants, so a row already sitting at its true offset moves for free and only the
 * <em>set</em> of realised rows has to change. It also means {@link #getScrollHeight()} cannot be derived
 * from the children — they are a window, not the content — so it is overridden to come from the model.</p>
 *
 * <h3>What this genuinely breaks</h3>
 * <ul>
 *   <li><b>{@code querySelector} cannot find an unrealised row.</b> Inherent, not a defect: the element
 *       does not exist. Ask the view by index.</li>
 *   <li><b>Focus.</b> A focused row scrolled out of the window is recycled out from under the focus. The
 *       view therefore tracks the focused <em>index</em> and restores focus when that index is realised
 *       again — without which a keyboard user loses their place on every scroll.</li>
 *   <li><b>Serialization.</b> A {@code ListView} has no business writing out its realised window; fifteen
 *       of ten thousand rows is worse than nothing. It is the model that is state.</li>
 * </ul>
 */
public class ListView<T> extends ScrollerView {

    /** Realised rows carry this, so a theme can style them without knowing they are recycled. */
    public static final String ROW_CLASS = "__row__";

    /**
     * Rows realised beyond the viewport on each side.
     *
     * <p>Fixed rather than derived from scroll velocity, deliberately: a fixed count is predictable and
     * testable, and velocity-based overscan is an optimisation that wants a measurement before it is worth
     * the non-determinism it introduces.</p>
     */
    private static final int OVERSCAN = 2;

    @Getter
    private final ObservableList<T> model;

    @Nullable
    private ListRenderer<T> renderer;

    @Getter
    private ItemSizeStrategy sizeStrategy = new FixedHeightStrategy(16f);

    /** Realised rows by model index. Not a list: the window is a moving range, and keying by index is
     * what makes "is this row already realised?" a lookup rather than a scan. */
    private final Map<Integer, UIElement> realised = new HashMap<>();

    /** Recycled elements waiting to be re-bound. Templates, in the renderer's sense — structure intact,
     * data stale. */
    private final Deque<UIElement> pool = new ArrayDeque<>();

    private int firstRealised = -1, lastRealised = -1;

    /** The focused row's model index, or -1. Tracked because the element cannot be: it is recycled the
     * moment it leaves the window, and the index is the only stable identity a row has. */
    private int focusedIndex = -1;

    private final Connection modelConnection;

    // ── Selection ───────────────────────────────────────────────────────────

    /** Applied to selected rows. A class rather than the {@code :checked} pseudo-class, because rows are
     * arbitrary elements the renderer built — {@code :checked} reads {@code isChecked()}, which would
     * need a row subclass, and wrapping every template in one is the "eight wrappers deep" structure
     * {@code TabView} exists to avoid. */
    public static final String SELECTED_CLASS = "__selected__";

    @Getter
    private SelectionMode selectionMode = SelectionMode.SINGLE;

    /** Sorted, so range operations and {@code getSelectedIndices()} are both ordered — a caller acting on
     * a multi-selection almost always wants it in list order rather than click order. */
    private final NavigableSet<Integer> selected = new TreeSet<>();

    /** Where a Shift-range starts. Held separately from the focused index because Shift+Down repeatedly
     * must keep extending from the original row, not from wherever focus has since reached. */
    private int selectionAnchor = -1;

    /** Fires whenever the selected set changes, with the new selection. */
    public final Signal.Value<java.util.Set<Integer>> onSelectionChanged = new Signal.Value<>();

    /**
     * Fires when a row is <b>activated</b> — Enter on the focused row.
     *
     * <p>Distinct from {@link #onSelectionChanged} on purpose, and every list eventually needs both:
     * arrowing through a file list changes the selection constantly, and none of those are "open this
     * file". Selection is where you are; activation is what you decided. A double-click belongs here too,
     * and a renderer can raise it from its own template listener.</p>
     */
    public final Signal.Value<Integer> onRowActivated = new Signal.Value<>();

    public ListView(ObservableList<T> model) {
        this.model = model;
        // NOT markAsInternal() — that marks THIS element internal, which would hide the whole list from
        // public traversal and from UIDescriptionCodec. Rows are made internal individually by
        // addInternalChild, which is the correct half of that pair.
        modelConnection = model.onChange(change -> {
            // A model that shrank can leave selections pointing past the end. Clamping here rather than
            // at read time means every consumer sees a valid set, instead of each having to defend.
            selected.removeIf(index -> index >= model.size());
            invalidateWindow();
        });

        // Arrow keys on the view itself, matching TabView's idiom rather than the keymap: a keymap
        // binding names a command id, and command ids are global to the window — two lists on one screen
        // would need two sets of ids for identical behaviour. Widget-local keys belong on the widget.
        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled() || model.isEmpty()) return;
            if (handleNavigationKey(event)) event.stopPropagation();
        }, false, true);

    }

    /** Rows are structure, not content — {@code addChild} would put a caller's element among the recycled
     * window, where it would be positioned by index and destroyed on the next scroll. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public ListView<T> setRenderer(@Nullable ListRenderer<T> renderer) {
        this.renderer = renderer;
        recycleAll();
        invalidateWindow();
        return this;
    }

    public ListView<T> setSizeStrategy(ItemSizeStrategy strategy) {
        this.sizeStrategy = strategy == null ? new FixedHeightStrategy(16f) : strategy;
        recycleAll();
        invalidateWindow();
        return this;
    }

    /** Convenience for the common case. */
    public ListView<T> setItemHeight(float height) {
        return setSizeStrategy(new FixedHeightStrategy(height));
    }

    // ── Scrolling ───────────────────────────────────────────────────────────

    /**
     * From the <b>model</b>, not from the children — the children are a window, and deriving the scroll
     * range from them would let the list scroll exactly as far as the handful of rows currently realised.
     * Everything else about scrolling (max scroll, clamping, smooth scroll, the scrollbar thumb) reads
     * through here, so this one override is what makes the rest work unchanged.
     */
    @Override
    public float getScrollHeight() {
        return sizeStrategy.totalSize(model.size());
    }

    /** Scrolls so the row at {@code index} is visible. The element-based {@code scrollIntoView} cannot
     * serve here — the row may not exist yet, which is the whole point. */
    public ListView<T> scrollToIndex(int index) {
        if (model.isEmpty()) return this;
        int clamped = Math.max(0, Math.min(model.size() - 1, index));
        float top = sizeStrategy.offsetOf(clamped);
        float bottom = top + sizeStrategy.sizeOf(clamped);
        float viewTop = getScrollTop();
        float viewBottom = viewTop + getClientHeight();

        if (top < viewTop) setScrollTop(top);
        else if (bottom > viewBottom) setScrollTop(bottom - getClientHeight());
        return this;
    }

    // ── The window ──────────────────────────────────────────────────────────

    /**
     * Rebuilds on the next settled layout, <b>re-binding every realised row</b>.
     *
     * <p>Resetting the range markers alone is not enough, and the failure is nasty: {@link #updateWindow()}
     * only calls {@code realise} for an index it is not already holding, so rows already in the map keep
     * whatever they were last bound to. The model changes underneath and the screen does not move.</p>
     *
     * <p>That is exactly how a tree came out looking broken — expanding a node re-flattened the model
     * correctly, the row count changed, keyboard navigation acted on the new rows, and the display kept
     * showing the old ones. The elements are recycled rather than destroyed, so the cost is a re-bind of a
     * dozen rows, which is what a model change means anyway.</p>
     */
    protected void invalidateWindow() {
        recycleAll();
        markTreeDirty();
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        ensureTicking();
        updateWindow();
    }

    /**
     * The window is re-derived every frame, not on layout — because <b>scrolling does not lay out</b>.
     *
     * <p>A scroll offset is state on the element, applied as a pose translate when painting descendants;
     * nothing in the layout tree changes when it moves. So an {@code onLayoutChanged} hook alone realises
     * the window once and then never again, and the list appears to work until the moment somebody
     * scrolls it. A ticker is also what makes smooth scrolling work, since that eases the offset over
     * many frames with no layout at any of them.</p>
     *
     * <p>Cost when nothing has moved is one comparison — {@link #updateWindow()} early-returns on an
     * unchanged range — which is the same bargain {@code Dialog}'s clamp ticker already makes.</p>
     */
    @Override
    public boolean tickFrame(float deltaSeconds) {
        // ScrollerView's own tick refreshes the bars and then asks to be DROPPED once nothing is
        // animating. This one must never be dropped — the window has to be re-derived on every frame a
        // scroll offset might have moved — so its result is deliberately discarded and true returned.
        super.tickFrame(deltaSeconds);
        if (getAttachedWindow() == null) {
            // Left the tree. Detach from the model here rather than on a removal event, because an
            // ObservableList outlives the views onto it — a file list survives the panel showing it — so
            // a listener held by a discarded view keeps that view, its pooled elements and every item
            // they reference alive for as long as the model does. dispose() stays public for callers who
            // want it immediate; relying on them to remember is how this leaks in practice.
            dispose();
            return false;
        }
        // BEFORE updateWindow, so it acts on the layout that settled last frame. A ticker runs ahead of
        // layout, so this is the earliest point at which a row realised on the previous frame is sitting
        // at its true position.
        if (focusRestoreWanted) {
            focusRestoreWanted = false;
            restoreFocusIfRealised();
        }
        updateWindow();
        return true;
    }

    private boolean ticking;

    private void ensureTicking() {
        if (ticking) return;
        var window = getAttachedWindow();
        if (window == null) return;
        window.registerTicker(this);
        ticking = true;
    }

    /**
     * Realises exactly the rows the viewport can see, plus {@link #OVERSCAN} either side, recycling
     * everything else.
     *
     * <p>Early-returns when the range is unchanged, which is what makes this safe to call from every
     * settled layout and every scroll step — the common case is that nothing moved.</p>
     */
    public void updateWindow() {
        if (renderer == null || getAttachedWindow() == null) return;

        int count = model.size();
        if (count == 0) {
            recycleAll();
            return;
        }

        float viewportHeight = getClientHeight();
        // Before the first real layout the box is zero-sized; realising one row rather than none keeps
        // scrollToIndex and focus restoration from having to special-case an empty window.
        int first = Math.max(0, sizeStrategy.indexAt(getScrollTop(), count) - OVERSCAN);
        int last = viewportHeight <= 0f
                ? first
                : Math.min(count - 1, sizeStrategy.indexAt(getScrollTop() + viewportHeight, count) + OVERSCAN);

        if (first == firstRealised && last == lastRealised) return;

        for (var iterator = realised.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getKey() < first || entry.getKey() > last) {
                recycle(entry.getValue());
                iterator.remove();
            }
        }
        for (int index = first; index <= last; index++) {
            if (!realised.containsKey(index)) realised.put(index, realise(index));
        }

        firstRealised = first;
        lastRealised = last;
        // Deferred by a frame, deliberately — see restoreFocusIfRealised.
        focusRestoreWanted = focusedIndex >= 0;
    }

    /**
     * How far down the scrollport the first row starts. Zero for a plain list.
     *
     * <p>{@code TableView} returns its header height: the header lives inside the scrollport (so it can
     * be scroll-exempt and stay pinned) rather than above it, which means the rows have to begin below it
     * or the first one is painted underneath. Reducing {@link #getClientHeight()} alone is not enough —
     * that fixes how many rows fit, not where they sit.</p>
     */
    protected float rowOffset() {
        return 0f;
    }

    private UIElement realise(int index) {
        UIElement row = pool.pollFirst();
        if (row == null) {
            row = renderer.createTemplate();
            row.addClass(ROW_CLASS);
            // The view learns the focused INDEX from the row itself, so focus arriving any way at all —
            // a click, Tab, a renderer calling requestFocus — is tracked. Requiring the renderer to
            // report it would be a rule to remember, and the failure when forgotten is silent.
            // FOCUSABLE ON CLICK, or the listener below can never fire for a pointer.
            //
            // Selection here is driven entirely by focus -- deliberately, so that a click, Tab and a
            // renderer's own requestFocus all take one path. But FocusPolicy defaults to NONE, so a row
            // that nobody made focusable is a row a click cannot focus, and therefore cannot select. The
            // list still worked by keyboard, because moveFocusTo focuses the row itself, which made the
            // gap look like a styling problem: rows highlighted on hover and never on click.
            //
            // CLICK_NOT_TABBABLE is the ARIA roving-tabindex pattern this engine already uses for
            // composites: the LIST is the tab stop, the arrows move inside it, and a fifty-row list is
            // one Tab press to skip rather than fifty.
            row.setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);

            final UIElement tracked = row;
            tracked.onFocus.attachListener((el, event) -> {
                int index2 = indexOf(tracked);
                if (index2 < 0) return;
                focusedIndex = index2;
                // Focus and selection are separate concepts, and a genuine focus gesture sets both — the
                // row was aimed at. But focus the VIEW ITSELF moved must not come back through here, or
                // Ctrl+arrow (move without selecting) and Shift+arrow (extend a range) would both be
                // overwritten by a plain select, and restoring focus to a recycled row would silently
                // re-select it.
                if (suppressFocusSelection || selectionMode == SelectionMode.NONE) return;
                select(index2);
            }, false, true);
            // Absolute, so a row sits at its true content offset and the scroll translate moves it for
            // free — the realised set changes, the positions never do.
            StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            addInternalChild(row);
        }
        final float top = rowOffset() + sizeStrategy.offsetOf(index);
        final float height = sizeStrategy.sizeOf(index);
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.top(top).left(0).widthPercent(100f).height(height).display(dev.vfyjxf.taffy.style.TaffyDisplay.FLEX));
        renderer.bind(model.get(index), index, row);
        applySelectionClass(row, index);
        return row;
    }

    // ── Selection API ───────────────────────────────────────────────────────

    public ListView<T> setSelectionMode(SelectionMode mode) {
        this.selectionMode = mode == null ? SelectionMode.SINGLE : mode;
        if (this.selectionMode == SelectionMode.NONE) clearSelection();
        else if (this.selectionMode == SelectionMode.SINGLE && selected.size() > 1) {
            // Keep the last, which is the one the user most recently expressed an interest in.
            int keep = selected.last();
            selected.clear();
            selected.add(keep);
            selectionChanged();
        }
        return this;
    }

    /** In list order, not selection order — a caller acting on a multi-selection almost always wants it
     * top-to-bottom. */
    public java.util.Set<Integer> getSelectedIndices() {
        return Collections.unmodifiableSet(selected);
    }

    public boolean isSelected(int index) {
        return selected.contains(index);
    }

    /** Replaces the selection with {@code index} alone, and makes it the anchor for a later Shift-range. */
    public ListView<T> select(int index) {
        if (selectionMode == SelectionMode.NONE || !isValid(index)) return this;
        selectionAnchor = index;
        if (selected.size() == 1 && selected.contains(index)) return this;
        selected.clear();
        selected.add(index);
        selectionChanged();
        return this;
    }

    /** Adds or removes {@code index}, leaving the rest alone — Ctrl-click. Single-select falls back to a
     * plain replace, so the same gesture does the sensible thing in either mode. */
    public ListView<T> toggle(int index) {
        if (selectionMode == SelectionMode.NONE || !isValid(index)) return this;
        if (selectionMode == SelectionMode.SINGLE) return select(index);
        if (!selected.remove(index)) selected.add(index);
        selectionAnchor = index;
        selectionChanged();
        return this;
    }

    /** Selects everything between the anchor and {@code index} inclusive — Shift-click. */
    public ListView<T> selectRangeTo(int index) {
        if (selectionMode != SelectionMode.MULTIPLE || !isValid(index)) return select(index);
        if (selectionAnchor < 0) return select(index);
        selected.clear();
        for (int i = Math.min(selectionAnchor, index); i <= Math.max(selectionAnchor, index); i++) {
            selected.add(i);
        }
        selectionChanged();
        return this;
    }

    public ListView<T> selectAll() {
        if (selectionMode != SelectionMode.MULTIPLE || model.isEmpty()) return this;
        selected.clear();
        for (int i = 0; i < model.size(); i++) selected.add(i);
        selectionChanged();
        return this;
    }

    public ListView<T> clearSelection() {
        if (selected.isEmpty()) return this;
        selected.clear();
        selectionChanged();
        return this;
    }

    /**
     * Replaces the selection wholesale with a set of indices.
     *
     * <p>For a subclass that keys selection on something more stable than an index and has to re-derive
     * it — {@code TableView} does, because sorting moves every row and index-based selection would leave
     * a user who selected three files owning three different ones after one header click.</p>
     *
     * <p>Emits once, not once per index, so a listener sees the new selection rather than each step of
     * assembling it.</p>
     */
    protected void setSelectedIndices(Collection<Integer> indices) {
        TreeSet<Integer> next = new TreeSet<>();
        for (int index : indices) {
            if (isValid(index)) next.add(index);
        }
        if (next.equals(selected)) return;
        selected.clear();
        selected.addAll(next);
        selectionChanged();
    }

    private boolean isValid(int index) {
        return index >= 0 && index < model.size();
    }

    private void selectionChanged() {
        // Realised rows carry the class; unrealised ones get it when they are next bound, which is why
        // applySelectionClass is also called from realise(). Two call sites for one rule, because the
        // window and the selection change independently.
        realised.forEach((index, row) -> applySelectionClass(row, index));
        onSelectionChanged.emit(Collections.unmodifiableSet(new TreeSet<>(selected)));
    }

    private void applySelectionClass(UIElement row, int index) {
        if (selected.contains(index)) row.addClass(SELECTED_CLASS);
        else row.removeClass(SELECTED_CLASS);
    }

    // ── Keyboard, per the ARIA listbox pattern ──────────────────────────────

    /**
     * Arrow keys, Home/End, PageUp/PageDown, Space and Ctrl+A.
     *
     * <p><b>Selection follows focus</b> in {@link SelectionMode#SINGLE}, which is what a file list, a
     * palette and a properties table all do — arrowing through a list you then have to press Space in is
     * a keyboard experience nobody wants. Holding Ctrl moves focus <em>without</em> selecting, which is
     * the APG's escape hatch for reaching a row you want to Ctrl+Space into a multi-selection.</p>
     *
     * @return true if the key was ours, so the caller can stop it propagating
     */
    protected boolean handleNavigationKey(KeyboardEvent.Down event) {
        int count = model.size();
        int current = focusedIndex < 0 ? 0 : focusedIndex;
        int page = Math.max(1, (int) (getClientHeight() / Math.max(1f, sizeStrategy.sizeOf(current))) - 1);
        int modifiers = event.getModifiers();
        boolean shift = CgModifiers.hasShift(modifiers);
        boolean ctrl = CgModifiers.hasCtrl(modifiers) || CgModifiers.hasSuper(modifiers);

        int target;
        switch (event.getKeyCode()) {
            case CgKeyCodes.KEY_DOWN -> target = current + 1;
            case CgKeyCodes.KEY_UP -> target = current - 1;
            case CgKeyCodes.KEY_HOME -> target = 0;
            case CgKeyCodes.KEY_END -> target = count - 1;
            case CgKeyCodes.KEY_NEXT -> target = current + page;   // Page Down
            case CgKeyCodes.KEY_PRIOR -> target = current - page;  // Page Up
            case CgKeyCodes.KEY_SPACE -> {
                // Space TOGGLES in a multi-selection — it is how you add a row you reached with
                // Ctrl+arrow without disturbing the rest.
                if (selectionMode == SelectionMode.MULTIPLE) toggle(current);
                else select(current);
                return true;
            }
            case CgKeyCodes.KEY_RETURN, CgKeyCodes.KEY_NUMPADENTER -> {
                // Enter REPLACES the selection with the focused row and activates it — the complement to
                // Space rather than a duplicate of it. Needed because focus and selection are separate
                // here: after Ctrl+arrow the row is focused and unselected, and without this there is no
                // key that simply says "this one".
                //
                // It has to be handled here rather than left to UIInputHandler's Space/Enter activation,
                // which synthesizes a mouse click on the focused element — and since selection is driven
                // by the FOCUS event, clicking a row that already has focus changes nothing at all. That
                // is exactly why Enter did nothing before.
                select(current);
                onRowActivated.emit(current);
                return true;
            }
            case CgKeyCodes.KEY_A -> {
                if (!ctrl) return false;
                selectAll();
                return true;
            }
            default -> {
                return false;
            }
        }

        target = Math.max(0, Math.min(count - 1, target));
        moveFocusTo(target, shift, ctrl);
        return true;
    }

    /** Set while this view is moving focus itself, so the row's focus listener knows the gesture was not
     * the user's and leaves the selection alone. */
    private boolean suppressFocusSelection;

    /** Moves the focused index, scrolls it into view, and updates the selection unless Ctrl says not to.
     *
     * <p>Protected because {@code TreeView} moves focus for Left/Right too — to a parent or a first child
     * — and must do it through the same path, or those two keys would skip the scroll and the
     * selection-follows-focus rule that every other key obeys.</p> */
    protected void moveFocusTo(int index, boolean extend, boolean focusOnly) {
        suppressFocusSelection = true;
        try {
            setFocusedIndex(index);
        } finally {
            suppressFocusSelection = false;
        }
        scrollToIndex(index);
        if (selectionMode == SelectionMode.NONE || focusOnly) return;
        if (extend && selectionMode == SelectionMode.MULTIPLE) selectRangeTo(index);
        else select(index);
    }

    /** The model index a realised row currently represents, or -1. Linear over the window, which is a
     * dozen entries — a second map would have to be kept in step for no measurable gain. */
    private int indexOf(UIElement row) {
        for (Map.Entry<Integer, UIElement> entry : realised.entrySet()) {
            if (entry.getValue() == row) return entry.getKey();
        }
        return -1;
    }

    private void recycle(UIElement row) {
        // Blur BEFORE pooling, and this is the fix for the worst symptom this widget had: focus rode the
        // recycled element into the pool and out again, so scrolling away from a focused row left the
        // focus ring jumping onto whatever unrelated item inherited that element next. The element is not
        // the identity — the index is — so the element must give focus up the moment it stops
        // representing anything, and focusedIndex is what remembers where to put it back.
        var window = getAttachedWindow();
        if (window != null) window.getInputHandler().blurIfFocused(row);
        renderer.unbind(row);
        // display: none rather than removal. Removing would destroy the Taffy node and every style
        // candidate on it, so the next scroll step would pay to rebuild what it just threw away — which
        // is precisely the cost a pool exists to avoid.
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.display(dev.vfyjxf.taffy.style.TaffyDisplay.NONE));
        pool.addLast(row);
    }

    private void recycleAll() {
        for (UIElement row : realised.values()) recycle(row);
        realised.clear();
        firstRealised = -1;
        lastRealised = -1;
    }

    /** Realised rows, by model index — for tests and for anything that needs "the element for item N, if
     * it exists right now". */
    public Map<Integer, UIElement> realisedRows() {
        return java.util.Collections.unmodifiableMap(realised);
    }

    public int realisedCount() {
        return realised.size();
    }

    /** Pooled-but-unused elements. Exposed because "does scrolling allocate?" is otherwise unobservable,
     * and it is the property that makes this widget worth having. */
    public int pooledCount() {
        return pool.size();
    }

    // ── Focus survives recycling ────────────────────────────────────────────

    /**
     * Remembers which <b>index</b> holds focus.
     *
     * <p>The element cannot be remembered: it is recycled the moment it leaves the window, and would then
     * be re-bound to some other item. The index is a row's only stable identity, which is why VS Code
     * tracks the same thing. Without this a keyboard user loses their place on every scroll — and worse,
     * focus lands on whatever item happens to inherit the recycled element.</p>
     */
    public ListView<T> setFocusedIndex(int index) {
        focusedIndex = index < 0 || index >= model.size() ? -1 : index;
        restoreFocusIfRealised();
        return this;
    }

    /** Set when the realised window changed and a focused index may need re-attaching. */
    private boolean focusRestoreWanted;

    public int getFocusedIndex() {
        return focusedIndex;
    }

    /**
     * Re-attaches focus to whichever element now represents {@link #focusedIndex}.
     *
     * <p><b>Deferred by one frame after a window change, and that is not tidiness.</b>
     * {@code requestFocus} scrolls its target into view — correctly, since focus the user cannot see is
     * useless. But called from inside {@code updateWindow}, the row it is given has only just been pulled
     * from the pool and still carries the <em>previous</em> occupant's laid-out position, because layout
     * has not run since. So it scrolled to where that stale row used to be, which moved the window, which
     * realised a different set, which restored focus again — a loop that settled thousands of pixels from
     * where the caller asked to be. Observed as {@code setScrollTop(0)} coming to rest at 7940.</p>
     *
     * <p>Run a frame later the row is where it belongs, it is inside the viewport by construction, and
     * {@code scrollIntoView} is correctly a no-op.</p>
     */
    private void restoreFocusIfRealised() {
        if (focusedIndex < 0) return;
        UIElement row = realised.get(focusedIndex);
        var window = getAttachedWindow();
        if (row == null || window == null) return;
        if (window.getInputHandler().getFocusedElement() != row && row.focusable()) {
            // Restoring focus to a row that scrolled back into view is the view's own doing, not the
            // user's — without the guard it would re-select that row and quietly discard whatever
            // multi-selection had been built since.
            suppressFocusSelection = true;
            try {
                window.getInputHandler().requestFocus(row);
            } finally {
                suppressFocusSelection = false;
            }
        }
    }

    /** Detaching from the model matters: an {@code ObservableList} outlives the views onto it, and a
     * listener held by a discarded view keeps the whole view alive with it. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        modelConnection.disconnect();
        ticking = false;
    }

    /** Whether this view is still listening to its model.
     *
     * <p>Tracked in a field rather than asked of the {@link Connection}, whose {@code isConnected()}
     * defaults to {@code true} unless the concrete signal overrides it — so reading it would have
     * reported "still connected" forever and made this leak unobservable. */
    public boolean isListeningToModel() {
        return !disposed;
    }

    private boolean disposed;
}
