package com.crystalgui.widget.collection.list;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.chrome.palette.QuickPick;
import com.crystalgui.core.collection.list.FixedHeightStrategy;
import com.crystalgui.core.collection.list.ItemSizeStrategy;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.ClipboardActions;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.scroll.ScrollerView;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;

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
 * {@link #getMaxScrollTop()} — which reads through the {@link #scrollHeight()} override below, so it
 * reflects the model rather than the dozen realised rows, with no further work.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ListView<String> list = new ListView<>(model);          // an ObservableList<String>
 * list.setItemHeight(14f);
 * list.setRenderer(new ListRenderer<String>() {
 *     public UINode createTemplate() { ... }           // once — structure and listeners
 *     public void bind(String item, int i, UINode t) { ... }   // per row — data only
 * });
 * }</pre>
 *
 * <h3>How the window is realised</h3>
 * <p>Rows are <b>absolutely positioned internal children</b> at {@code top = strategy.offsetOf(index)}.
 * That is what lets scrolling cost nothing: this engine applies a scroll offset as a pose translate when
 * painting descendants, so a row already sitting at its true offset moves for free and only the
 * <em>set</em> of realised rows has to change. It also means {@link #scrollHeight()} cannot be derived
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
public class ListView<T> extends ScrollerView implements ClipboardActions, DataProvider {

    public static final Name NAME = Name.of("listview");

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
    private final Map<Integer, UINode> realised = new HashMap<>();

    /** Recycled elements waiting to be re-bound. Templates, in the renderer's sense — structure intact,
     * data stale. */
    private final Deque<UINode> pool = new ArrayDeque<>();

    private int firstRealised = -1, lastRealised = -1;

    /** The focused row's model index, or -1. Tracked because the element cannot be: it is recycled the
     * moment it leaves the window, and the index is the only stable identity a row has. */
    private int focusedIndex = -1;

    /**
     * The model subscription — dropped while detached, re-made on re-attach.
     *
     * <p><b>Not final, and that is the whole fix.</b> It was, so releasing it was permanent — and a detach
     * released it automatically. A dock panel that is closed and reopened detaches and re-attaches, after
     * which the view was back on screen, ticking again, and no longer listening to its model: rows went
     * stale, folds stopped landing, and nothing said so. Every list and tree in the application had this.
     * It surfaced as a Problems tree whose chevrons worked until the panel was reopened from the activity
     * bar and then did nothing.</p>
     */
    @Nullable
    private Connection modelConnection;

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
    public final Signal.Value<Set<Integer>> onSelectionChanged = new Signal.Value<>();

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
        this(NAME, model);
    }

    /** The constructor a subclass hands its own kind to. @see Button#Button(Name, String) */
    protected ListView(Name name, ObservableList<T> model) {
        this.model = model;
        // NOT markAsInternal() — that marks THIS element internal, which would hide the whole list from
        // public traversal and from UIDescriptionCodec. Rows are made internal individually by
        // addInternalChild, which is the correct half of that pair.
        subscribeToModel();

        // THE LIST IS THE TAB STOP, and until now nothing had made it one. The roving-tabindex comment on
        // the row below has said so since rows became focusable, but the other half was never written:
        // rows are CLICK_NOT_TABBABLE and the list was FocusPolicy.NONE, so the composite had *zero* tab
        // stops and could hold focus only by way of a row.
        //
        // That is not a tab-order nicety. `UIInputHandler.consumeKeyboardEvent` dispatches nothing at all
        // when `focusedElement` is null, so a list nobody had clicked a row in heard no keys whatsoever —
        // not the arrows attached immediately below, not type-ahead, not Ctrl+F. Clicking the empty space
        // under the rows was actively worse than not clicking: `emitMouseDown` blurs before it dispatches,
        // so it took focus away and handed it to nothing. The Problems panel is where it showed, because
        // a fresh panel has no reason to have been clicked into; the explorer hid it by being a thing you
        // click a file in immediately.
        setFocusPolicy(FocusPolicy.CLICK);

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


    // ── Horizontal scrolling ────────────────────────────────────────────────

    /**
     * On the view whenever {@link #setHorizontalScrolling} is on.
     *
     * <p>It exists for the stylesheet, and the sheet's half is not optional: a row only overflows if its
     * label is allowed to keep its natural width, so the rule that makes labels ellipsize has to be turned
     * off by the same switch that turns scrolling on. The two are one decision and cannot be set
     * separately without one of them being silently pointless.</p>
     */
    public static final String HORIZONTAL_CLASS = "__h-scroll__";

    private boolean horizontalScrolling;

    /**
     * The widest content any realised row has reported, for this generation of the model.
     *
     * <p><b>A running maximum, deliberately, rather than the widest row on screen right now.</b> Only
     * realised rows can be measured — an unrealised one has no element and no width — so a live maximum
     * would fall the moment a long name scrolled out of view, taking the scroll range with it and yanking
     * the thumb and the content sideways under the pointer. Growing-only means the range settles as you
     * explore and never moves backwards under you. {@link #refresh} resets it, because a new model is a
     * new set of names and holding a width from the old one is just wrong.</p>
     *
     * <p>The cost is that the range can overstate: collapse the folder with the long name and the bar
     * stays until something reloads. VS Code's list behaves the same way and for the same reason.</p>
     */
    private float widestRealised;

    /**
     * Lets rows keep their natural width and scroll sideways, instead of ellipsizing — VS Code's
     * {@code workbench.list.horizontalScrolling}.
     *
     * <p>Off by default, which is VS Code's default too. It is not free: with it on, a long name makes
     * every row in the list wider, so a list that has no room to grow (a dropdown, a palette) is better
     * off truncating.</p>
     */
    public ListView<T> setHorizontalScrolling(boolean enabled) {
        if (horizontalScrolling == enabled) return this;
        horizontalScrolling = enabled;
        if (enabled) addClass(HORIZONTAL_CLASS);
        else removeClass(HORIZONTAL_CLASS);
        widestRealised = 0f;
        applyRowWidths();
        return this;
    }

    public boolean isHorizontalScrolling() {
        return horizontalScrolling;
    }

    /**
     * A <b>pure accessor</b>, exactly like {@code TextEditor.getScrollWidth} — the scan is
     * {@link #measureWidestRealisedRow()} and runs once a frame.
     *
     * <p>The split is not tidiness. {@code getMaxScrollLeft} reads this, the bar's visibility reads that,
     * the thumb's size reads that, and the wheel handler reads it again — so a dozen field-looking reads
     * fan back into one loop over every realised row. The editor measured <b>54 such entries per settled
     * frame</b> before the same split was made there.</p>
     *
     * <p>Floored at the client width so the rows always span the full scrollable area: a selection or
     * hover fill that stopped at the viewport edge would leave a bare strip once scrolled.</p>
     */
    /**
     * <b>{@code scrollExtent} is what {@code getScrollWidth}/{@code getScrollHeight} became</b>, and
     * a virtualised list is what it was written for.
     *
     * <p>Its javadoc names this case exactly: a list realises a dozen rows of ten thousand, so the
     * boxes under it describe the WINDOW rather than the content, and the children cannot be asked.
     * The node answers {@code -1} unless something overrides it — 6.2 mistook that for a content-size
     * accessor and got {@code -1} back — and this is the first override in the engine.</p>
     *
     * <p>{@code -1} for an axis this list does not virtualise, which is the contract's own way of
     * saying "ask the boxes": a list that does not scroll sideways has nothing the children do not
     * already say.</p>
     */
    @Override
    public float scrollExtent(boolean horizontal) {
        if (!horizontal) return sizeStrategy.totalSize(model.size());
        if (!horizontalScrolling) return -1f;
        Box box = box();
        return Math.max(box == null ? 0f : box.clientWidth(), widestRealised);
    }

    /** @see #widestRealised */
    private void measureWidestRealisedRow() {
        if (!horizontalScrolling) return;
        float widest = widestRealised;
        // A ROW'S OWN getScrollWidth, which is the furthest right edge of its children -- so it reports
        // the label's true extent even while the row itself is clamped narrower. Anything a row pins to
        // its trailing edge must be setScrollExempt, or it sits at the row's right edge by construction
        // and this measures the row instead of its content, forever.
        for (UINode row : realised.values()) {
            Box rowBox = row.box();
            if (rowBox != null) widest = Math.max(widest, rowBox.scrollWidth());
        }
        if (widest <= widestRealised) return;
        widestRealised = widest;
        applyRowWidths();
    }

    private void applyRowWidths() {
        for (UINode row : realised.values()) applyRowWidth(row);
    }

    /**
     * Writes one row's width — the full scrollable width when scrolling sideways, else the viewport's.
     *
     * <p>Every realised row is re-written when the maximum grows, not just newly realised ones:
     * {@code realise} only runs for a row the window did not already hold, so rows already on screen
     * would keep the width they were born with and the fills would end in a ragged edge.</p>
     */
    private void applyRowWidth(UINode row) {
        StyleGroup.inlinePipeline(row.getStyle().getLayoutGroup(), l -> {
            if (horizontalScrolling) l.width(scrollExtent(true));
            else l.widthPercent(100f);
        });
    }

    /** Scrolls so the row at {@code index} is visible. The element-based {@code scrollIntoView} cannot
     * serve here — the row may not exist yet, which is the whole point. */
    public ListView<T> scrollToIndex(int index) {
        if (model.isEmpty()) return this;
        int clamped = Math.max(0, Math.min(model.size() - 1, index));
        float top = sizeStrategy.offsetOf(clamped);
        float bottom = top + sizeStrategy.sizeOf(clamped);
        float viewTop = scrollTop();
        float viewBottom = viewTop + (box() == null ? 0f : box().clientHeight());

        if (top < viewTop) scrollTo(scrollLeft(), top);
        else if (bottom > viewBottom) scrollTo(scrollLeft(), bottom - (box() == null ? 0f : box().clientHeight()));
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
        // COALESCED, not done here. A model change arrives once per MUTATION, and a re-flatten is a clear
        // followed by one add per row -- so a tree with twenty children fired twenty-one of these, each
        // recycling every realised row and dirtying the tree again. updateWindow then re-realised from
        // inside the layout pass, dirtying it once more, and the settle loop spent its budget churning
        // elements. What that looks like on screen is the panel going blank for a few frames.
        //
        // Deferring to the next updateWindow makes it one recycle per frame however many mutations
        // arrived, which is what a re-flatten actually means.
        // THE ROWS LEAVE THE INDEX MAP, and this is the whole fix rather than an optimisation.
        //
        // `realised` maps INDEX to element, and a model change makes every one of those indices a lie:
        // row 4 is now a different item, or no item. Everything that reaches into the map by index was
        // therefore wrong for the frame or two before the rows rebind -- restoring a selection lit up an
        // unrelated file, restoring focus focused one, and each looked like its own bug in whatever
        // widget happened to show it.
        //
        // Guarding each of those call sites is a rule to remember at every future one. Moving the
        // elements to a list instead makes the index map genuinely empty, so every lookup answers "not
        // realised" on its own -- which is the truth. They stay children and stay displayed, so the frame
        // still paints what it painted before; they simply stop being addressable.
        for (UINode row : realised.values()) awaitingRecycle.add(row);
        realised.clear();
        firstRealised = -1;
        lastRealised = -1;
        // A NEW MODEL IS A NEW SET OF NAMES. The running maximum only grows within one generation, so
        // without this a collapsed folder's longest name would keep the horizontal range open forever.
        widestRealised = 0f;
        markTreeDirty();
    }

    /**
     * Rows still on screen whose index no longer means anything — recycled by the next
     * {@link #updateWindow}.
     *
     * <p>Deliberately a list rather than a map: the point is that they have no index.</p>
     */
    private final List<UINode> awaitingRecycle = new ArrayList<>();

    /**
     * A standing post-layout hook, which is what the {@code onLayoutChanged} override became.
     *
     * <p>Layout is one pass with no feedback into it here, so anything that READS a measured box goes
     * after it — and the realised window is derived from the viewport's height.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            ensureTicking();
            updateWindow();
            return true;
        });
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
    public boolean tickFrame(float deltaSeconds) {
        // ScrollerView's own tick refreshes the bars and then asks to be DROPPED once nothing is
        // animating. This one must never be dropped — the window has to be re-derived on every frame a
        // scroll offset might have moved — so its result is deliberately discarded and true returned.
                if (document() == null) {
            // Left the tree. Release the model subscription here rather than on a removal event, because
            // an ObservableList outlives the views onto it — a file list survives the panel showing it —
            // so a listener held by an off-screen view keeps that view, its pooled elements and every item
            // they reference alive for as long as the model does.
            //
            // RELEASED, NOT DISPOSED. This called dispose(), which is one-way, so a view could not survive
            // being detached: a dock panel closed and reopened came back on screen and ticking with no
            // model subscription at all. @see #modelConnection
            unsubscribeFromModel();
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
        // AFTER updateWindow, so a row realised this frame is counted. Once a frame and no more -- see
        // getScrollWidth on why the scan may not sit behind the query.
        measureWidestRealisedRow();
        syncFocusedClass();
        return true;
    }

    /**
     * On the list while the focus owner is inside it — what an <b>inactive selection</b> is styled from.
     *
     * <p>Every reference greys the selection of a list that does not have focus: the highlight answers
     * "which row will the keys act on", and when the keys are going somewhere else it is answering a
     * question nobody asked. Two panels both showing a saturated selection say the arrow keys will move
     * both.</p>
     *
     * <p>A CLASS rather than a pseudo-class, and not for want of trying: {@code :focus-within} is not in
     * this engine's supported set, and an unknown pseudo-class does not degrade — it POISONS the sheet it
     * appears in. {@code SearchField.FOCUSED_CLASS} is the same answer for the same reason.</p>
     */
    public static final String FOCUSED_CLASS = "__focused__";

    private boolean ticking;

    /** Starts the per-frame tick if it is not already running. Protected so a subclass with deferred
     * work of its own can drive it from the ticker this class already owns, rather than registering a
     * second one -- two tickers means two lifecycles, and the second is always the one that leaks. */
    protected void ensureTicking() {
        if (ticking) return;
        var window = document();
        if (window == null) return;
        // BACK ON SCREEN. A detach released the model subscription; this is the moment it comes back, and
        // the window is invalidated because the model may have moved on entirely while nobody was
        // listening. @see #modelConnection
        subscribeToModel();
        invalidateWindow();
        installDefaultContextMenu();
        document().animation().every(this, this::tickFrame);
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
        if (renderer == null || document() == null) return;

        // Held until now so the old rows stayed on screen -- recycling at invalidation time hid every row
        // the instant the model changed, and the frame that had already ticked painted empty.
        if (!awaitingRecycle.isEmpty()) {
            for (UINode row : awaitingRecycle) recycle(row);
            awaitingRecycle.clear();
        }

        int count = model.size();

        // A FOCUSED INDEX PAST THE MODEL IS A LIE, and it must die here rather than sit and wait.
        if (focusedIndex >= count) focusedIndex = -1;

        if (count == 0) {
            recycleAll();
            return;
        }

        float viewportHeight = viewportHeight();
        // Before the first real layout the box is zero-sized; realising one row rather than none keeps
        // scrollToIndex and focus restoration from having to special-case an empty window.
        int first = Math.max(0, sizeStrategy.indexAt(scrollTop(), count) - OVERSCAN);
        int last = viewportHeight <= 0f
                ? first
                : Math.min(count - 1, sizeStrategy.indexAt(scrollTop() + viewportHeight, count) + OVERSCAN);

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
        
        if (focusBlurredByRecycle) restoreFocusIfRealised();
    }

    /** Set when {@link #recycle} pooled the element that held focus — see {@link #updateWindow()}. */
    private boolean focusBlurredByRecycle;

    /**
     * How far down the scrollport the first row starts. Zero for a plain list.
     *
     * <p>{@code TableView} returns its header height: the header lives inside the scrollport (so it can
     * be scroll-exempt and stay pinned) rather than above it, which means the rows have to begin below it
     * or the first one is painted underneath. Reducing {@link #(box() == null ? 0f : box().clientHeight())} alone is not enough —
     * that fixes how many rows fit, not where they sit.</p>
     */
    /**
     * The height rows are virtualised against.
     *
     * <p>A hook because a {@code TableView}'s is not its client box: the header sits inside the
     * scrollport and out of the scroll, so counting it realises one row too few at the bottom on
     * every frame. It was inline here before the port and the table override needed something to
     * override.</p>
     */
    protected float viewportHeight() {
        Box box = box();
        return box == null ? 0f : box.clientHeight();
    }

    protected float rowOffset() {
        return 0f;
    }

    private UINode realise(int index) {
        UINode row = pool.pollFirst();
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

            final UINode tracked = row;
            tracked.onFocus.attachListener((el, event) -> {
                int index2 = indexOf(tracked);
                if (index2 < 0) return;
                focusedIndex = index2;
            }, false, true);
            // THE POINTER SELECTS HERE, not in the focus listener above, and the difference is the whole
            // of Ctrl-click, Shift-click and dragging a multi-selection.
            //
            // Selection used to be driven entirely by focus, which reads well until you notice that focus
            // has no modifiers and no press/release pair to hang them on: every click could only ever mean
            // "replace the selection with this one row". So Ctrl-click and Shift-click did nothing at all
            // in the mouse, while working perfectly from the keyboard -- moveFocusTo has always selected
            // explicitly -- which is why it looked like a broken modifier rather than a missing feature.
            //
            // The keyboard is unaffected: it never went through the focus listener either.
            //
            // POINTER PRESSES ONLY. Space and Enter on a focused element arrive here as a synthesized
            // MouseEvent.Down, which is what gives every widget keyboard activation for free — but these
            // two handlers are the POINTER half of selection, and the keyboard already has its own,
            // explicit half in handleNavigationKey. Letting a synthesized press through means Enter runs
            // the modifier logic against whatever keys happen to be held and re-decides a selection the
            // arrow keys just made.
            tracked.onMouseDown.attachListener((el, event) -> {
                if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
                // THE PRIMARY BUTTON CHOOSES; the secondary one only asks. A right-click opens a menu
                // ABOUT a row and must leave the selection alone — otherwise the menu destroys the very
                // selection it was opened over, which for a multi-selection cannot be undone. The menu
                // still knows its subject: it reads the row under the pointer directly.
                if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
                int index2 = indexOf(tracked);
                if (index2 >= 0) pressRow(index2);
            }, false, true);
            tracked.onMouseUp.attachListener((el, event) -> {
                if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
                if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
                int index2 = indexOf(tracked);
                if (index2 >= 0) releaseRow(index2);
            }, false, true);
            // Absolute, so a row sits at its true content offset and the scroll translate moves it for
            // free — the realised set changes, the positions never do.
            StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            append(row);
        }
        final float top = rowOffset() + sizeStrategy.offsetOf(index);
        final float height = sizeStrategy.sizeOf(index);
        // IMPORTANT, not DEFAULT: this is virtualisation state, not a suggestion.
        //
        // The strategy decides BOTH where a row sits and how tall it is, and the two have to agree or the
        // rows stop tiling. A stylesheet that set `height` on a row won that comparison at DEFAULT origin,
        // so rows were painted and hit-tested at the sheet's height inside slots spaced at the strategy's
        // -- leaving a dead band between every pair that swallowed clicks. Same reasoning as SplitView's
        // weights, which are IMPORTANT for exactly this reason.
        //
        // A caller that wants a different row height says so with setItemHeight, which the strategy reads,
        // rather than through CSS the layout cannot see.
        StyleGroup.inlinePipeline(row.getStyle().getLayoutGroup(),
                l -> l.top(top).left(0).height(height));
        // WIDTH IS ITS OWN WRITE, because it is the one part of a row's geometry that is not a function of
        // the index -- it depends on the widest row realised so far, which changes as you scroll.
        applyRowWidth(row);
        // DISPLAY IS DEFAULT ORIGIN, and must stay at whatever origin `recycle` uses.
        //
        // It is lifecycle state -- realised or pooled -- written from TWO places, and the pair only works
        // if neither can outrank the other. Raising this one to IMPORTANT alongside the geometry above
        // made recycle's `display: none` unable to win, so a pooled row stayed painted at its last
        // position forever: the list grew a tail of unclickable ghost rows showing whatever used to be
        // there. Geometry has one writer and can be authoritative; display has two and cannot.
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX));
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
    public Set<Integer> getSelectedIndices() {
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

    /**
     * What a press on a row means — ported from the behaviour every file manager shares, and the one
     * VS Code's {@code listWidget} implements.
     *
     * <p>Four cases, and the last is the one that is always missing when multi-select "randomly" breaks:</p>
     *
     * <ul>
     *   <li><b>Shift</b> — extend from the anchor. The anchor deliberately does not move, so
     *       Shift-clicking twice grows and shrinks one range instead of walking it.</li>
     *   <li><b>Ctrl</b> — toggle this row and leave the rest alone.</li>
     *   <li><b>Plain, on an unselected row</b> — replace the selection with it.</li>
     *   <li><b>Plain, on a row that is ALREADY selected</b> — do nothing yet, and decide on release.</li>
     * </ul>
     *
     * <p>That last case exists entirely so that dragging a multi-selection works. Selecting on press
     * collapses the selection to the row under the pointer <em>before</em> the drag begins, so picking up
     * five files and moving them moves one and silently deselects four — and deleting after a drag acts on
     * whatever survived. Deferring to release keeps the whole set intact for the drag, and still collapses
     * to the clicked row when the press turns out to be a plain click.</p>
     */
    private void pressRow(int index) {
        if (selectionMode == SelectionMode.NONE || !isValid(index)) return;
        pendingSelectOnRelease = -1;

        if (selectionMode == SelectionMode.MULTIPLE && isShiftDown()) {
            selectRangeTo(index);
            return;
        }
        if (selectionMode == SelectionMode.MULTIPLE && isMultiSelectModifierDown()) {
            toggle(index);
            return;
        }
        if (isSelected(index)) {
            pendingSelectOnRelease = index;
            return;
        }
        select(index);
    }

    /** @see #pressRow(int) */
    private void releaseRow(int index) {
        int pending = pendingSelectOnRelease;
        pendingSelectOnRelease = -1;
        if (pending != index) return;
        // NOT after a drag. The press was the start of moving this selection somewhere, and collapsing it
        // now would land the drop and then throw away the very set that was dropped -- which reads as the
        // selection changing on its own once the mouse comes up.
        //
        // isActivated, NOT isDragging. A drag is ARMED on mouse-down -- ProjectFileTree calls startDrag
        // straight out of its press handler -- so isDragging is true for every ordinary click, and using it
        // suppressed the collapse always: a plain click on one of five selected files left all five
        // selected, which is the same "randomly multi-selects" complaint from the other end. isActivated
        // only becomes true once the pointer has passed the threshold, which is exactly "this turned out to
        // be a drag".
        var window = document();
        // A LIVE DRAG is an InputMode on this engine, not a controller to ask -- pushed while one
        // is running and gone the moment it ends, so "is a drag active" is "is one on the stack".
        Drag drag = window == null ? null : window.input().mode(Drag.class);
        if (drag != null && drag.isActivated()) return;
        select(index);
    }

    /** A plain press on an already-selected row, waiting to see whether it becomes a drag. */
    private int pendingSelectOnRelease = -1;

    private static boolean isShiftDown() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasShift(input.getCurrentModifiers());
    }

    /** Ctrl, or Command on a Mac — {@code CgModifiers} already resolves which one this platform means. */
    private static boolean isMultiSelectModifierDown() {
        var input = CgPlatform.input();
        return input != null && CgModifiers.hasCtrl(input.getCurrentModifiers());
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
        //
        // No guard needed for a stale window: invalidateWindow empties this map, so a selection restored
        // across a re-flatten finds nothing to stamp and realise() applies the class as it rebinds.
        realised.forEach((index, row) -> applySelectionClass(row, index));
        onSelectionChanged.emit(Collections.unmodifiableSet(new TreeSet<>(selected)));
    }

    /**
     * Which row {@code element} is, or sits inside — {@code -1} for anything that is not a row.
     *
     * <p><b>Walks up</b>, because the thing under the pointer is almost never the row: it is the label,
     * the icon or a badge the renderer put there. This lived on {@code TreeView} and matched the row
     * element exactly, which worked only because its one caller had already resolved the row by hand.</p>
     */
    public int indexOfRowElement(@Nullable UINode element) {
        for (UINode scope = element; scope != null; scope = scope.parent()) {
            for (var entry : realised.entrySet()) {
                if (entry.getValue() == scope) return entry.getKey();
            }
        }
        return -1;
    }

    // ── The default row context menu ────────────────────────────────────────

    /**
     * Gives <b>every</b> list a right-click menu, installed once by whoever owns menus.
     *
     * <p><b>A hook rather than a call in each widget</b>, because "all of them" is the requirement: a
     * helper hosts opt into is a list of call sites that is wrong the moment somebody adds a list and
     * forgets. And it cannot simply be done here — a menu is chrome, {@code ui.elements.list} cannot see
     * that package, and inverting the dependency would make the two mutually dependent for one item. So
     * chrome installs the behaviour and the list supplies the seam. {@code ElementRegistry.bootstrapBuiltins}
     * is the same shape.</p>
     *
     * @see ContextMenu#installDefaultForLists
     */
    public static void setDefaultContextMenuInstaller(@Nullable Consumer<ListView<?>> installer) {
        defaultContextMenuInstaller = installer;
    }

    @Nullable
    private static Consumer<ListView<?>> defaultContextMenuInstaller;

    private boolean defaultContextMenuInstalled;

    /**
     * -- GETTER --
     *  Whether this list declines the default menu because it has one of its own.
     *  <p>Read at <b>click</b> time, not at install time — see the installer. Two menus attached to one
     *  element are two listeners and both would open.</p>
     */
    @Getter
    private boolean defaultContextMenuSuppressed;

    /** For a widget that attaches its own menu — {@code ProjectFileTree} does. */
    public ListView<T> suppressDefaultContextMenu() {
        this.defaultContextMenuSuppressed = true;
        return this;
    }

    /**
     * Installed on first attach rather than in the constructor.
     *
     * <p>Construction order would otherwise decide it: a list built before chrome registers its hook
     * would silently never get a menu, and that is exactly the kind of miss that shows up as "this one
     * panel is different" months later. Every list reaches a window before it can be right-clicked.</p>
     */
    private void installDefaultContextMenu() {
        if (defaultContextMenuInstalled || defaultContextMenuInstaller == null) return;
        defaultContextMenuInstalled = true;
        defaultContextMenuInstaller.accept(this);
    }

    // ── Copy ────────────────────────────────────────────────────────────────

    /**
     * A host's own cut/copy/paste, replacing the row-text default. @see #setClipboardActions
     */
    @Nullable
    private ClipboardActions clipboardDelegate;

    /**
     * Hands this list's clipboard behaviour to whoever owns it.
     *
     * <p><b>This exists because implementing {@link ClipboardActions} here SHADOWS the host's.</b>
     * {@code UiDataKeys.CLIPBOARD} resolves by walking outward from the focused element and taking the
     * first thing of that type, so a list that answers for itself is found before the panel around it —
     * and {@code ProjectFileTree} <em>contains</em> its tree rather than extending it. Giving every list a
     * default Copy would therefore have silently replaced the explorer's whole Cut/Copy/Paste with a
     * row-text copier: the menu would still have opened, the items would still have been enabled, and
     * they would have done the wrong thing.</p>
     *
     * <p>So a host that already implements the interface says so, once, and this list becomes a
     * pass-through. Every other list keeps the default and gains Copy for free.</p>
     */
    public ListView<T> setClipboardActions(@Nullable ClipboardActions delegate) {
        this.clipboardDelegate = delegate == this ? null : delegate;
        return this;
    }

    /**
     * Answers {@link UiDataKeys#CLIPBOARD} with this list.
     *
     * <p>Implementing the interface is <b>not</b> what makes a widget the clipboard target — the walk asks
     * {@code getData} and takes the first non-null answer, never {@code instanceof}. So this override is
     * the whole mechanism, and it is also the shadowing hazard: it answers before any ancestor gets to,
     * which is why {@link #setClipboardActions} exists.</p>
     */
    /**
     * {@inheritDoc}
     *
     * <p>No {@code super} call: {@code DataProvider} is an interface the node does not implement, so
     * an unanswered key is {@code null} — which is the walk's own signal to try the next step out.
     * The old engine's {@code UIElement} implemented it and answered null itself.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == UiDataKeys.CLIPBOARD) return clipboardDelegate != null ? clipboardDelegate : this;
        return null;
    }

    @Override
    public boolean canCut() {
        return clipboardDelegate != null && clipboardDelegate.canCut();
    }

    @Override
    public void cut() {
        if (clipboardDelegate != null) clipboardDelegate.cut();
    }

    /**
     * The row a context menu was opened over — <b>its subject, which is not the selection</b>.
     *
     * <p>A right-click names the row it is about, so the menu acts on that row and leaves the selection
     * alone. Cleared once used, and by the next ordinary press, so a later Ctrl+C means the selection
     * again.</p>
     */
    public ListView<T> setContextRow(int index) {
        this.contextRow = index >= 0 && index < model.size() ? index : -1;
        return this;
    }

    private int contextRow = -1;

    /** What Copy would act on: the right-clicked row if there is one, otherwise the selection. */
    private Collection<Integer> copyTargets() {
        if (contextRow >= 0 && contextRow < model.size() && !selected.contains(contextRow)) {
            return List.of(contextRow);
        }
        return selected;
    }

    /** Something to copy — a list cannot copy "where the cursor is", only a row that was named or chosen. */
    @Override
    public boolean canCopy() {
        return clipboardDelegate != null ? clipboardDelegate.canCopy() : !copyTargets().isEmpty();
    }

    @Override
    public void copy() {
        if (clipboardDelegate != null) {
            clipboardDelegate.copy();
            return;
        }
        Collection<Integer> targets = copyTargets();
        contextRow = -1;
        if (renderer == null || targets.isEmpty()) return;
        StringBuilder out = new StringBuilder();
        // IN LIST ORDER, which is what `selected` being a NavigableSet already guarantees -- copying a
        // multi-selection in click order would produce text nobody could match against the screen.
        for (int index : targets) {
            if (index < 0 || index >= model.size()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(renderer.copyTextFor(model.get(index)));
        }
        CgPlatform.input().setClipboard(out.toString());
    }

    @Override
    public boolean canPaste() {
        return clipboardDelegate != null && clipboardDelegate.canPaste();
    }

    @Override
    public void paste() {
        if (clipboardDelegate != null) clipboardDelegate.paste();
    }

    private void applySelectionClass(UINode row, int index) {
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
        int page = Math.max(1, (int) ((box() == null ? 0f : box().clientHeight()) / Math.max(1f, sizeStrategy.sizeOf(current))) - 1);
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
    private int rowIndexOf(UINode row) {
        for (Map.Entry<Integer, UINode> entry : realised.entrySet()) {
            if (entry.getValue() == row) return entry.getKey();
        }
        return -1;
    }

    /**
     * Whether the blur being dispatched right now came from pooling a row rather than from the user.
     *
     * <h3>Why this has to be asked here and not guessed at the listener</h3>
     *
     * <p>{@link #recycle} blurs a row before pooling it, deliberately and for a defect of its own — focus
     * used to ride a recycled element into the pool and back out onto an unrelated item. That blur is
     * indistinguishable, at the listener, from the user clicking away.</p>
     *
     * <p>It matters to anything that puts a <b>focusable control in a row</b> and treats losing focus as a
     * decision. The explorer's inline rename does: every refresh while an edit was open raised a blur,
     * committed the name and closed the field, so F2 opened an input and shut it in the same frame. A flag
     * on the widget that owns the input cannot be right, because a refresh can be started by anyone —
     * five call sites reached {@code treeView().refresh()} directly. The list is the only thing that knows.</p>
     */
    public boolean isRecyclingRow() {
        return recycling;
    }

    /** @see #isRecyclingRow */
    private boolean recycling;

    private void recycle(UINode row) {
        // Blur BEFORE pooling, and this is the fix for the worst symptom this widget had: focus rode the
        // recycled element into the pool and out again, so scrolling away from a focused row left the
        // focus ring jumping onto whatever unrelated item inherited that element next. The element is not
        // the identity — the index is — so the element must give focus up the moment it stops
        // representing anything, and focusedIndex is what remembers where to put it back.
        var window = document();
        recycling = true;
        try {
            // NOTED, because this blur leaves the WHOLE WINDOW with no focus owner until the restore runs
            // -- see updateWindow, which closes that gap in the same frame rather than a frame later.
            if (window != null && window.focus().focused() == row) {
                focusBlurredByRecycle = true;
            }
            if (window != null) window.focus().blurIfFocused(row);
            // AND HOVER, for the identical reason focus is given up above: the element stops representing
            // anything, so it cannot still be the thing the pointer is over. Missing, the flag rides the
            // element through the pool and an untouched row comes back wearing :hover.
            if (window != null) window.input().invalidateHover();
            renderer.unbind(row);
        } finally {
            recycling = false;
        }
        // display: none rather than removal. Removing would destroy the Taffy node and every style
        // candidate on it, so the next scroll step would pay to rebuild what it just threw away — which
        // is precisely the cost a pool exists to avoid.
        StyleGroup.defaultPipeline(row.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.NONE));
        pool.addLast(row);
    }

    private void recycleAll() {
        for (UINode row : realised.values()) recycle(row);
        realised.clear();
        firstRealised = -1;
        lastRealised = -1;
    }

    /** Realised rows, by model index — for tests and for anything that needs "the element for item N, if
     * it exists right now". */
    public Map<Integer, UINode> realisedRows() {
        return Collections.unmodifiableMap(realised);
    }

    public int realisedCount() {
        return realised.size();
    }

    /**
     * Row elements currently on screen — realised, plus any still awaiting recycle after a model change.
     *
     * <p>The honest answer to "does this frame have anything to paint", which {@link #realisedCount()} is
     * not during the window between a model change and the next {@link #updateWindow}.</p>
     */
    public int shownRowCount() {
        return realised.size() + awaitingRecycle.size();
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
        // ASKED FOR, so a vacancy is this call's to fill -- see restoreFocusIfRealised for the three
        // paths and why only this one and the recycle may take a null owner.
        restoreFocusIfRealised(true);
        return this;
    }

    /**
     * Puts {@link #FOCUSED_CLASS} on iff the focus owner is inside this list.
     *
     * <p>Recomputed once a frame rather than driven from focus and blur EVENTS, and that is the whole
     * reason it is reliable. Moving focus from one row to another fires a blur and a focus that both
     * bubble here, so a listener pair has to be correct under either order — and it is not: blur-then-focus
     * removes and re-adds, focus-then-blur removes and stays removed. Asking who holds focus has no order
     * to get wrong. It costs one null check and a walk up the ancestor chain.</p>
     */
    private void syncFocusedClass() {
        var window = document();
        UINode focused = window == null ? null : window.focus().focused();
        boolean inside = focused != null && containsInSubtree(focused);
        // addClass/removeClass no-op on an unchanged set, so a settled frame writes nothing.
        if (inside) addClass(FOCUSED_CLASS);
        else removeClass(FOCUSED_CLASS);
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
     * where the caller asked to be. Observed as {@code scrollTo(scrollLeft(), 0)} coming to rest at 7940.</p>
     *
     * <p>Run a frame later the row is where it belongs, it is inside the viewport by construction, and
     * {@code scrollIntoView} is correctly a no-op.</p>
     */
    private void restoreFocusIfRealised() {
        // The two REFRESH paths -- a window change and the list's own recycle. Neither is a request for
        // focus, so the only vacancy either may fill is the one this list made itself.
        restoreFocusIfRealised(focusBlurredByRecycle);
    }

    /**
     * @param mayFillAVacancy whether a focus owner of {@code null} is this call's to take. True for
     *                        {@link #setFocusedIndex}, which is a caller ASKING, and for the list's own
     *                        {@link #recycle}, whose blur made the vacancy. False for an ordinary refresh:
     *                        nobody holding focus is not this list holding it.
     */
    private void restoreFocusIfRealised(boolean mayFillAVacancy) {
        if (focusedIndex < 0) return;
        UINode row = realised.get(focusedIndex);
        var window = document();
        if (row == null || window == null) return;

        // RESTORE, never TAKE. This exists to reattach focus to a row that scrolled back into view, which
        // presupposes focus was in this list already -- so when it is somewhere else, leaving it there is
        // the whole contract, not a special case.
        //
        // Without the guard a list steals focus from whatever is driving it. QuickPick is exactly that
        // shape: the search field owns the caret and the arrow keys, and the list is a VIEW of the
        // selection -- the ARIA combobox pattern, where focus stays in the textbox and the listbox is
        // pointed at by aria-activedescendant. Every keystroke re-queried the source, which called
        // setFocusedIndex, which landed focus on a row: the palette opened unfocused and then unfocused
        // itself again on every letter typed.
        UINode focused = window.focus().focused();
        // AND NOBODY HOLDING FOCUS IS NOT THIS LIST HOLDING IT.
        //
        // The guard below reads "focus is elsewhere, leave it there", and it was one case short in exactly
        // the way it had already been short once before: `null` is not elsewhere, it is NOWHERE, and the
        // test let it through — so a list with a focused index claimed any vacancy that appeared while it
        // happened to refresh.
        //
        // Which is how closing an editor tab focused the PROJECT TREE. Ctrl+W detaches the focused editor,
        // UIInputHandler correctly forgets it and the owner becomes null; any refresh of the tree then
        // landed here and took the opening. Every part was doing its job, which is why it read as a
        // close-tab bug and survived the reveal being switched off — the reveal was one trigger of a
        // refresh, not the only one.
        //
        // THE ONE NULL THAT IS THIS LIST'S is the one it made: `recycle` blurs a row before pooling it, so
        // restoring there is putting back what this list took. That is what `focusBlurredByRecycle`
        // records, and it is spent below rather than at the call site — an attempt that finds no realised
        // row must leave it set for the deferred attempt a frame later, or a focused row that scrolled out
        // and back loses focus for good.
        if (focused == null ? !mayFillAVacancy : !containsInSubtree(focused)) return;

        // AND NEVER OUT OF A CONTROL INSIDE A ROW. The guard above says "restore, never take" and stops
        // one step short: it asks whether focus is in this LIST, and a list is not only its rows.
        //
        // This method exists to reattach focus to a row whose ELEMENT went away and came back. Focus
        // sitting on something a row CONTAINS was never lost, so there is nothing to restore and taking
        // it is pure theft. Asked as "is the focused element itself a row" rather than "is it inside the
        // row I am restoring to", because the two rows need not be the same one -- the editor may be
        // several rows below whichever index last had focus, and stealing across rows is the same bug.
        //
        // The explorer's inline rename is exactly this shape. F2 put an input in a row and focused it;
        // the next frame's restore pulled focus onto the row at `focusedIndex`, the editor read the blur
        // as the user leaving, committed, and closed -- so the field appeared for one frame and vanished.
        // Reported twice as "the rename flickers", and it is this method rather than anything in the
        // explorer.
        if (focused != null && !realised.containsValue(focused)) return;

        focusBlurredByRecycle = false;
        if (focused != row && window.focus().focusable(row)) {
            // Restoring focus to a row that scrolled back into view is the view's own doing, not the
            // user's — without the guard it would re-select that row and quietly discard whatever
            // multi-selection had been built since.
            suppressFocusSelection = true;
            try {
                // requestPointerFocus, NOT requestFocus. The latter is PROGRAMMATIC and therefore rings,
                // and :focus-visible exists precisely to ring keyboard focus and not clicks -- so a row
                // that merely scrolled back into view drew a focus outline the user never asked for. It
                // showed up as folder rows wearing a blue box while file rows wore a blue fill: two
                // different states that looked like two different styles.
                //
                // The comment above already says this restore is the view's own doing rather than the
                // user's; the ring is the other half of that same statement.
                window.focus().requestPointerFocus(row);
            } finally {
                suppressFocusSelection = false;
            }
        }
    }

    /** Whether {@code element} is this list or sits underneath it. */
    private boolean containsInSubtree(UINode element) {
        for (UINode scope = element; scope != null; scope = scope.parent()) {
            if (scope == this) return true;
        }
        return false;
    }

    /**
     * Subscribes to the model, unless this view has been explicitly disposed.
     *
     * <p>Idempotent, so re-attaching twice costs one comparison.</p>
     */
    private void subscribeToModel() {
        if (disposed || modelConnection != null) return;
        modelConnection = model.onChange(change -> {
            // A model that shrank can leave selections pointing past the end. Clamping here rather than
            // at read time means every consumer sees a valid set, instead of each having to defend.
            selected.removeIf(index -> index >= model.size());
            invalidateWindow();
        });
    }

    /**
     * Drops the model subscription without ending this view.
     *
     * <p>What a <b>detach</b> does. An {@code ObservableList} outlives the views onto it, so a listener
     * held by an off-screen view keeps that view, its pooled elements and every item they reference alive
     * — which is why detaching has to release it. What it must <em>not</em> do is make that permanent:
     * a dock panel is detached every time it is closed and re-attached when it is reopened, and a view
     * that treated the first as death came back deaf. @see #modelConnection</p>
     */
    private void unsubscribeFromModel() {
        if (modelConnection != null) {
            modelConnection.disconnect();
            modelConnection = null;
        }
        ticking = false;
    }

    /**
     * Ends this view for good — for a caller discarding it outright rather than merely hiding it.
     *
     * <p>One-way, unlike a detach: {@link #isListeningToModel} stays false and re-attaching will not bring
     * it back. That distinction is the point — automatic release on detach has to be reversible, and an
     * explicit {@code dispose()} has to not be, or "I am finished with this" would be undone by the next
     * thing that reparented it.</p>
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        unsubscribeFromModel();
    }

    /** Whether this view is still listening to its model.
     *
     * <p>Tracked in a field rather than asked of the {@link Connection}, whose {@code isConnected()}
     * defaults to {@code true} unless the concrete signal overrides it — so reading it would have
     * reported "still connected" forever and made this leak unobservable. */
    public boolean isListeningToModel() {
        // THE CONNECTION, not the disposed flag. Once a detach became reversible the two stopped agreeing:
        // a detached view is not disposed and is not listening either, and reading the flag would have
        // reported it as still subscribed — which is exactly the leak this method exists to make visible.
        // Caught by removalFromTheTreeDetachesFromTheModel, which is the assertion that already said so.
        return modelConnection != null;
    }

    private boolean disposed;
}
