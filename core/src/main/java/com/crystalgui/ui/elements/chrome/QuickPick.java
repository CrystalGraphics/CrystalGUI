package com.crystalgui.ui.elements.chrome;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.SearchField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.TextRange;

import java.util.List;

import javax.annotation.Nullable;

/**
 * A filtered chooser over a {@link QuickPickSource} — the widget behind the command palette.
 *
 * <p>VS Code's Quick Pick, with IntelliJ's density. One input, a ranked list beneath it, type to filter,
 * arrows to move, Enter to accept, Escape or a click outside to dismiss.</p>
 *
 * <h3>Why this is a {@link Popover} and not a {@link com.crystalgui.ui.elements.Dialog}</h3>
 *
 * <p>A palette wants light dismiss, Escape, top-layer promotion and focus restore — which is the whole of
 * what {@code Mode.AUTO} already is. {@code Dialog} would supply a title bar to hide and drag-to-move
 * nobody wants, and its {@code showModal()} would make the rest of the window <b>inert</b>. That last one
 * is the real argument: a palette is not modal anywhere. In a browser and in VS Code, a click outside
 * dismisses it <em>and lands on what you clicked</em>, which is exactly what light dismiss does here
 * (it runs after the mouse-down is dispatched, deliberately).</p>
 *
 * <h3>The source filters — see {@link QuickPickSource}</h3>
 *
 * <p>This widget never inspects a query. It hands it over and renders what comes back, in the order it
 * comes back, with the first row pre-selected. That is what lets a future file picker answer over RPC
 * without this class growing an async path.</p>
 *
 * <h3>Arrow keys are intercepted in the CAPTURE phase</h3>
 *
 * <p>Focus lives in the search field, and a {@link com.crystalgui.ui.elements.TextField} treats arrows as
 * caret movement. Listening on the bubble phase would mean the field had already consumed and stopped them.
 * Capturing on the way down gets them first. Note that {@code attachListener}'s two booleans are
 * <b>additive</b> — passing {@code capture} still subscribes the target phase — which is harmless here and
 * is the documented behaviour rather than an accident.</p>
 */
public class QuickPick extends Popover {

    public static final String CONTENT_CLASS = "__content__";
    public static final String SEARCH_CLASS = "__search__";
    public static final String RESULTS_CLASS = "__results__";
    public static final String CATEGORY_CLASS = "__qp-category__";
    public static final String LABEL_CLASS = "__qp-label__";
    public static final String SPACER_CLASS = "__qp-spacer__";
    public static final String ACCELERATOR_CLASS = "__qp-accelerator__";

    /** One key of a chord — {@code Ctrl}, {@code K} — each in its own box, as VS Code draws them. */
    public static final String KEY_CLASS = "__qp-key__";

    /** The {@code +} between two keys. Not a key, so it gets no box. */
    public static final String KEY_SEPARATOR_CLASS = "__qp-key-sep__";
    public static final String EMPTY_CLASS = "__qp-empty__";

    /** A row that is listed but cannot be chosen — see {@link QuickPickItem#enabled()}. */
    public static final String DISABLED_CLASS = "__qp-disabled__";

    /** The highlight name a stylesheet targets with {@code ::highlight(quickpick-match)}. */
    public static final String MATCH_HIGHLIGHT = "quickpick-match";

    /** Logical px. Wide enough for a long command with its accelerator, narrow enough to read as an
     * overlay rather than a panel. VS Code uses 600 CSS px; this is smaller because the host is a game
     * window at {@code uiScale} 2, where 600 logical px is most of the screen. */
    private static final float PREFERRED_WIDTH = 420f;

    /** Kept clear of the window edges when the window is narrower than {@link #PREFERRED_WIDTH}. */
    private static final float MIN_MARGIN = 16f;

    /** How far down the window the palette sits. Near the top, out of the way of what you are looking at —
     * VS Code's placement rather than IntelliJ's centred one, because this floats over a game. */
    private static final float TOP_OFFSET = 48f;

    /**
     * Row height, in logical px. <b>Must match {@code quickpick .__row__ { height }} in {@code default.css}</b>
     * — the two are a pair, and the duplication is forced rather than sloppy.
     *
     * <p>A virtualised list already needs its row height in Java: {@code FixedHeightStrategy} is what turns
     * a model index into a scroll offset, and it cannot read the cascade. Given that the number has to exist
     * here, deriving the list's own height from it is consistent rather than a second violation of
     * "no pixel values in Java".</p>
     */
    private static final float ROW_HEIGHT = 22f;   // authoritative: the sheet cannot override it

    /** How many rows are shown before the list scrolls. VS Code shows roughly this many. */
    private static final int MAX_VISIBLE_ROWS = 12;

    /** Reports the {@link QuickPickItem#id()} of the accepted row. Fires <b>after</b> the palette has
     * hidden and focus has been restored — see {@link #accept()}. */
    public final Signal.Value<String> onAccepted = new Signal.Value<>();

    private final UIElement content = new UIElement();
    private final SearchField search = new SearchField();
    private final ObservableList<QuickPickEntry> results = new ObservableList<>();
    private final ListView<QuickPickEntry> list = new ListView<>(results);

    private QuickPickSource source = query -> List.of();

    public QuickPick() {
        setMode(Mode.AUTO);
        // NEVER focusable itself. The search field is the only thing in here that should hold focus, and a
        // focusable container would put a stop between the palette opening and the caret being live.
        setFocusPolicy(FocusPolicy.NONE);

        content.addClass(CONTENT_CLASS);
        // Marked internal exactly ONCE, while empty. markAsInternal() RECURSES, so stamping a populated
        // subtree marks every descendant internal too -- and removeChild silently refuses internal
        // children. That is the bug that put duplicate, unclickable tabs in the dock; the wrapper is how
        // DockArea fixed it and the reason is identical here, since ListView adds and recycles rows.
        addInternalChild(content);

        search.addClass(SEARCH_CLASS);
        content.addChild(search);

        list.addClass(RESULTS_CLASS);
        list.setSelectionMode(SelectionMode.SINGLE);
        list.setItemHeight(ROW_HEIGHT);
        list.setRenderer(new RowRenderer());
        content.addChild(list);

        search.onQueryChanged.connect(this::refresh);

        // Enter on the list itself, for the case where focus somehow reached it (a click, a future
        // mouse-driven flow). The capture handler below covers the normal path.
        list.onRowActivated.connect(index -> accept());

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (handleKey(event.getKeyCode())) event.stopPropagation();
        }, true, false);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public QuickPick setSource(QuickPickSource source) {
        this.source = source == null ? query -> List.of() : source;
        return this;
    }

    public QuickPick setPlaceholder(String placeholder) {
        search.setPlaceholder(placeholder);
        return this;
    }

    public SearchField searchField() {
        return search;
    }

    public ListView<QuickPickEntry> resultList() {
        return list;
    }

    /** The rows currently shown, in rank order. The observable surface a test asserts on. */
    public List<QuickPickEntry> visibleEntries() {
        return results.asUnmodifiableList();
    }

    // ── Opening ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Attaches to the window's root if it is not already there, clears the query, and shows.
     *
     * <p>The query is reset on every open rather than remembered. VS Code remembers it and pre-selects
     * the text; that is a preference-store decision, and a palette that opens showing last time's filter
     * with no visible indication it is filtered is worse than one that opens blank.</p>
     */
    public QuickPick open(UIWindow window) {
        // hostFor, not the root: a root that refuses public children -- any composite, CrystalEditor
        // included -- would throw here. Null `near` means "window level", which the palette is.
        window.addOverlay(this, null);
        search.setText("");
        // Point-anchored with a null invoker: reposition() below overrides placement entirely, and a null
        // invoker is correct because a palette is not a toggle -- naming a trigger surface as the invoker
        // would exempt that whole surface from light dismiss.
        showAt(0f, 0f, null);
        // AFTER showing, and that ordering is the point. A closed popover is display:none, so its whole
        // subtree is skipped by Taffy -- populating the list first meant the row height, the selection and
        // the scroll offset were all computed against a box that did not exist yet. Everything downstream
        // of that (which rows are realised, where they sit) is derived from a viewport, so none of it can
        // be settled while there is no viewport to settle against.
        refresh();
        return this;
    }

    /** Focus goes to the search field the moment it opens — the caret is the point of the whole widget. */
    @Override
    protected void onOpened() {
        UIWindow window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(search.field());
    }

    /**
     * Centred horizontally, pinned near the top, re-run every frame by {@code Popover}'s placement ticker.
     *
     * <p>Overriding rather than calling {@code moveTo} is deliberate: {@code moveTo} sets
     * {@code freelyPositioned}, which silences placement for good, so the palette would keep its original
     * spot after a window resize. Overriding keeps it the single writer of {@code left}/{@code top} — the
     * invariant {@code AnchoredPlacement} exists to hold — while staying live.</p>
     */
    @Override
    public void reposition() {
        if (!isOpen()) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        float available = window.getScreenWidth();
        float width = Math.max(0f, Math.min(PREFERRED_WIDTH, available - 2f * MIN_MARGIN));
        float left = Math.max(MIN_MARGIN, (available - width) / 2f);
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.width(width).left(left).top(TOP_OFFSET));
    }

    // ── Query and selection ─────────────────────────────────────────────────────────────────────

    /** Re-asks the source and rebuilds the row model. */
    public void refresh() {
        List<QuickPickEntry> entries = source.query(SearchQuery.of(search.getText()));
        results.clear();
        for (QuickPickEntry entry : entries) results.add(entry);
        sizeListToContent(entries.size());
        // Land on the best row that can actually be chosen, so Enter on an untouched query does the obvious
        // thing. This is why the source's ORDER is a contract rather than a suggestion -- and why the search
        // is for the first ENABLED row rather than simply index 0, which may be dimmed.
        int first = nextEnabledFrom(0, 1);
        if (first >= 0) {
            focusRow(first);
            // A new query always shows its best results, so the list starts at the top -- and this must run
            // AFTER the selection, not before. setFocusedIndex defers a focus restore that scrolls the row
            // into view, and on the frame a palette opens there is no laid-out viewport to compute that
            // against: it came to rest one whole row down, hiding the best match behind the search box.
            // Measured at scrollTop=22 with a 198px list in a 198px viewport, where the only valid scroll
            // offset is 0.
            if (first < MAX_VISIBLE_ROWS) list.setScrollImmediate(0f, 0f);
        } else {
            // Nothing here can run, so nothing is selected. Leaving the previous index in place would paint
            // a dimmed row with the selection highlight -- an unrunnable row that looks like the one Enter
            // will take, which is the precise confusion dimming exists to prevent.
            list.setFocusedIndex(-1);
            list.clearSelection();
        }
    }

    /**
     * The first selectable row at or after {@code from}, stepping by {@code delta}, or {@code -1} when
     * every row is disabled.
     *
     * <p>The bound is the row count rather than a {@code while (true)}: with nothing focused every command
     * in the palette can legitimately be disabled at once, and a wrap-around search for something that is
     * not there is an infinite loop. That is not a hypothetical — it is the exact state the dock harness
     * opens in.</p>
     */
    private int nextEnabledFrom(int from, int delta) {
        int count = results.size();
        if (count == 0) return -1;
        for (int step = 0; step < count; step++) {
            int index = Math.floorMod(from + step * delta, count);
            if (results.get(index).item().enabled()) return index;
        }
        return -1;
    }

    private void focusRow(int index) {
        list.setFocusedIndex(index);
        list.select(index);
        list.scrollToIndex(index);
    }

    /**
     * Gives the list an explicit height of {@code min(rows, MAX_VISIBLE_ROWS) * ROW_HEIGHT}.
     *
     * <p><b>Not {@code flex-grow: 1; flex-basis: 0}</b>, which is the obvious thing to write and is silently
     * wrong here. Growing distributes <em>free space</em>, and a popover's height is derived from its own
     * content — so there is no free space to distribute, the list resolves to zero, and the palette renders
     * as a search box with nothing under it. A container has to be told its height before a child can grow
     * inside it, and nothing tells this one.</p>
     *
     * <p>Sizing to content is also what makes the palette shrink as a query narrows, which is VS Code's
     * behaviour and the reason a two-result palette is not a mostly-empty box.</p>
     *
     * <p>IMPORTANT origin, like {@link #reposition}, because it is computed rather than authored — a
     * stylesheet height would be overwritten every keystroke anyway, so losing to this is honest.</p>
     */
    private void sizeListToContent(int rowCount) {
        float height = Math.min(rowCount, MAX_VISIBLE_ROWS) * ROW_HEIGHT;
        StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(), l -> l.height(height));
    }

    private boolean handleKey(int keyCode) {
        if (keyCode == CgKeyCodes.KEY_DOWN) return moveFocus(1);
        if (keyCode == CgKeyCodes.KEY_UP) return moveFocus(-1);
        if (keyCode == CgKeyCodes.KEY_RETURN) {
            accept();
            return true;
        }
        // Escape is deliberately NOT handled here. Popover is a close watcher in AUTO mode, and the window
        // routes Escape to the topmost one -- which is what makes a palette opened over a modal close first
        // and leave the modal alone.
        return false;
    }

    /** Steps to the next selectable row, skipping dimmed ones entirely — arrowing onto a row that Enter
     * would refuse is the same dead end as clicking one. */
    private boolean moveFocus(int delta) {
        if (results.isEmpty()) return true;
        int next = nextEnabledFrom(list.getFocusedIndex() + delta, delta);
        if (next >= 0) focusRow(next);
        return true;
    }

    /**
     * Accepts the focused row.
     *
     * <p><b>Hides before emitting.</b> {@code Popover.hide()} restores the focus the palette took, so a
     * handler that runs after it sees the element the user was actually on — which matters for anything
     * resolving a target from focus. Emitting first would run every handler with focus still parked in a
     * search field that is about to disappear.</p>
     */
    public void accept() {
        int index = list.getFocusedIndex();
        if (index < 0 || index >= results.size()) return;
        QuickPickItem item = results.get(index).item();
        // A dimmed row is listed so you can learn the command exists and what it is bound to -- never so it
        // can be run. Refusing here rather than relying on CommandRegistry.run's own disabled check keeps
        // the palette honest for every source, not only the command one.
        if (!item.enabled()) return;
        hide();
        onAccepted.emit(item.id());
    }

    // ── Row rendering ───────────────────────────────────────────────────────────────────────────

    /** A row's parts, plus the index it is currently showing. */
    private static final class Row extends UIElement {
        final UIText category = new UIText("");
        final UIText label = new UIText("");
        final UIElement spacer = new UIElement();
        /**
         * A ROW of key boxes, not one string.
         *
         * <p>{@code "Ctrl+Shift+P"} as a single text can only ever be plain text, and every editor draws
         * each key as its own bordered box. Splitting it here is what lets a sheet style a key at all.</p>
         */
        final UIElement accelerator = new UIElement();

        /**
         * <b>Read per event, never captured.</b> Rows are pooled and recycled as the list scrolls, so a
         * listener that closed over the index would keep acting on whatever row its slot first showed —
         * which keeps working for exactly as long as nobody scrolls. Same trap the editor's pooled gutter
         * arrows document.
         */
        int index = -1;
    }

    private final class RowRenderer implements ListRenderer<QuickPickEntry> {

        @Override
        public UIElement createTemplate() {
            Row row = new Row();
            row.category.addClass(CATEGORY_CLASS);
            row.label.addClass(LABEL_CLASS);
            row.spacer.addClass(SPACER_CLASS);
            row.accelerator.addClass(ACCELERATOR_CLASS);
            row.addChild(row.category);
            row.addChild(row.label);
            row.addChild(row.spacer);
            row.addChild(row.accelerator);

            // A single click accepts. VS Code's palette does the same -- there is nothing else a click on
            // a palette row could mean, and requiring a double-click would be a second gesture for the
            // only gesture available.
            row.onMouseDown.attachListener((el, event) -> {
                int clicked = ((Row) el).index;
                if (clicked < 0 || clicked >= results.size()) return;
                // Consumed either way, including for a dimmed row: letting the press through would reach
                // light dismiss and close the palette, so clicking an unavailable command would look like
                // it ran something. accept() refuses it; this stops it doing anything else instead.
                event.stopPropagation();
                if (!results.get(clicked).item().enabled()) return;
                list.setFocusedIndex(clicked);
                accept();
            }, false, true);
            return row;
        }

        @Override
        public void bind(QuickPickEntry entry, int index, UIElement template) {
            Row row = (Row) template;
            row.index = index;

            QuickPickItem item = entry.item();
            String category = item.category();
            row.category.setText(category == null || category.isEmpty() ? "" : category + ": ");
            row.label.setText(item.label());
            setAccelerator(row.accelerator, item.accelerator());

            // Set AND cleared, because rows are recycled -- a row that showed a disabled command and is
            // reused for an enabled one would otherwise stay dimmed and unclickable-looking forever.
            if (item.enabled()) row.removeClass(DISABLED_CLASS);
            else row.addClass(DISABLED_CLASS);

            apply(row.label, entry.labelRanges());
            apply(row.category, entry.categoryRanges());
        }

        /**
         * Rebuilds the key boxes for one row.
         *
         * <p>Rebuilt rather than pooled because a chord has one to four keys and rows are already
         * recycled by the list — a pool inside a pool for four tiny elements earns nothing. Every box is
         * {@code setHitTest(false)}, so the row stays the hit target for the click that accepts it and
         * nothing here can be detached out from under a live event.</p>
         */
        private void setAccelerator(UIElement host, @Nullable String chord) {
            host.clearAllChildren();
            if (chord == null || chord.isEmpty()) return;
            for (String raw : chord.split("[+]")) {
                String key = raw.trim();
                if (key.isEmpty()) continue;
                if (!host.getChildren().isEmpty()) {
                    UIText plus = new UIText("+");
                    plus.addClass(KEY_SEPARATOR_CLASS);
                    plus.setHitTest(false);
                    host.addChild(plus);
                }
                UIText box = new UIText(key);
                box.addClass(KEY_CLASS);
                box.setHitTest(false);
                host.addChild(box);
            }
        }

        /** Replaces the highlight set every bind — including with nothing, which is the half a recycled
         * row would otherwise inherit from whatever it showed before. */
        private void apply(UIText text, List<TextRange> ranges) {
            if (ranges.isEmpty()) {
                text.highlights().remove(MATCH_HIGHLIGHT);
            } else {
                text.highlights().set(MATCH_HIGHLIGHT, ranges);
            }
        }

        @Override
        public void unbind(UIElement template) {
            Row row = (Row) template;
            row.index = -1;
            row.label.highlights().remove(MATCH_HIGHLIGHT);
            row.category.highlights().remove(MATCH_HIGHLIGHT);
        }
    }

    /** Present so a caller can style "no results" without reaching for the list internals. */
    @Nullable
    public UIElement contentElement() {
        return content;
    }
}
