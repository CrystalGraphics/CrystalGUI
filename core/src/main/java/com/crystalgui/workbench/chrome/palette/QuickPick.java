package com.crystalgui.workbench.chrome.palette;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.form.SearchField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.control.SymbolIcon;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import java.util.ArrayList;
import java.util.Collections;
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

    public static final Name NAME = Name.of("quickpick");

    /**
     * Whether the user has resized this picker, so auto-sizing stops.
     *
     * <p>{@code isUserSizedHeight()} came from {@code UIResizer}, which has no counterpart on this
     * engine — D6 chose a resize MODE over an edge band and 6.0 did not build it. A field is what
     * the resizer set anyway, and it is the honest placeholder: nothing sets it yet, so the picker
     * auto-sizes always, which is what it did before anyone dragged it.</p>
     */
    private boolean userSizedHeight;

    public static final String CONTENT_CLASS = "__content__";

    /** The bar above the search field. A title, and the surface the whole popup is dragged by. */
    public static final String HEADER_CLASS = "__qp-header__";

    /** The text in that bar. @see #setTitle */
    public static final String TITLE_CLASS = "__qp-title__";

    /** Pushes the truncation hint to the right-hand end of the header. */
    public static final String HEADER_SPACER_CLASS = "__qp-header-spacer__";

    /** "100+ matches", shown only when the list was cut short. @see #isTruncated */
    public static final String TRUNCATION_CLASS = "__qp-truncated__";

    public static final String SEARCH_CLASS = "__search__";
    public static final String RESULTS_CLASS = "__results__";
    public static final String CATEGORY_CLASS = "__qp-category__";
    public static final String LABEL_CLASS = "__qp-label__";

    /** The dim text after the label — a symbol's package. @see QuickPickItem#description */
    public static final String DESCRIPTION_CLASS = "__qp-description__";

    /** The kind glyph before the label. Present on every row, hidden when the item is not a symbol. */
    public static final String ICON_CLASS = "__qp-icon__";

    /** The file glyph before the label, for a row that is not a symbol. @see QuickPickItem#iconName */
    public static final String FILE_ICON_CLASS = "__qp-file-icon__";
    public static final String SPACER_CLASS = "__qp-spacer__";
    public static final String ACCELERATOR_CLASS = "__qp-accelerator__";

    /** One key of a chord — {@code Ctrl}, {@code K} — each in its own box, as VS Code draws them. */
    public static final String KEY_CLASS = "__qp-key__";

    /** The {@code +} between two keys. Not a key, so it gets no box. */
    public static final String KEY_SEPARATOR_CLASS = "__qp-key-sep__";

    /** Slots per row. Four covers every chord the engine can express; a longer one is truncated. */
    private static final int MAX_KEYS_PER_CHORD = 4;
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
    private final UIElement header = new UIElement();
    private final UIElement headerSpacer = new UIElement();
    private final UIText title = new UIText("");
    private final UIText truncation = new UIText("");

    /** Whether the last query had more answers than were listed. @see #isTruncated */
    private boolean truncated;

    /** Position at the moment a move began, so the drag accumulates from there and not from itself. */
    private float dragStartLeft, dragStartTop;

    /** @see #setRetainQuery */
    private boolean retainQuery;
    private final SearchField search = new SearchField();
    private final ObservableList<QuickPickEntry> results = new ObservableList<>();
    private final ListView<QuickPickEntry> list = new ListView<>(results);

    private QuickPickSource source = (query, sink) -> { };

    public QuickPick() {
        super(NAME);
        setMode(Mode.AUTO);
        // NEVER focusable itself. The search field is the only thing in here that should hold focus, and a
        // focusable container would put a stop between the palette opening and the caret being live.
        setFocusPolicy(FocusPolicy.NONE);

        content.addClass(CONTENT_CLASS);

        // A HEADER, and its job is to be draggable as much as to say anything.
        //
        // A popup with no chrome has nowhere to grab: every pixel of it is either the field (which owns
        // the caret) or a row (which accepts on press). IntelliJ's own Search Everywhere puts its tab
        // strip here and that strip is the drag handle -- the bar earns its height twice.
        header.addClass(HEADER_CLASS);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);
        header.append(title);
        headerSpacer.addClass(HEADER_SPACER_CLASS);
        headerSpacer.setHitTest(false);
        header.append(headerSpacer);
        truncation.addClass(TRUNCATION_CLASS);
        truncation.setHitTest(false);
        header.append(truncation);
        header.onMouseDown.attachListener((el, event) -> beginMove(event), false, true);
        content.append(header);
        // Marked internal exactly ONCE, while empty. markAsInternal() RECURSES, so stamping a populated
        // subtree marks every descendant internal too -- and removeChild silently refuses internal
        // children. That is the bug that put duplicate, unclickable tabs in the dock; the wrapper is how
        // DockArea fixed it and the reason is identical here, since ListView adds and recycles rows.
        append(content);

        search.addClass(SEARCH_CLASS);
        content.append(search);

        list.addClass(RESULTS_CLASS);
        list.setSelectionMode(SelectionMode.SINGLE);
        list.setItemHeight(ROW_HEIGHT);
        list.setRenderer(new RowRenderer());
        content.append(list);

        search.onQueryChanged.connect(this::refresh);

        // Enter on the list itself, for the case where focus somehow reached it (a click, a future
        // mouse-driven flow). The capture handler below covers the normal path.
        list.onRowActivated.connect(index -> accept());

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (handleKey(event.getKeyCode())) event.stopPropagation();
        }, true, false);
    }

    public QuickPick setSource(QuickPickSource source) {
        this.source = source == null ? (query, sink) -> { } : source;
        return this;
    }

    /**
     * Whether a close keeps what was typed, for the next {@link #open}.
     *
     * <p>Only useful to a caller that <b>reuses the instance</b> — a picker rebuilt per invocation has
     * nothing to retain, and this quietly does nothing. That is the arrangement {@code GoToFile} has and
     * the command palette does not: repeating a search is ordinary, and re-running the command you just
     * ran is not.</p>
     */
    public QuickPick setRetainQuery(boolean retain) {
        this.retainQuery = retain;
        return this;
    }

    public boolean isRetainQuery() {
        return retainQuery;
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
     * Attaches to the window's root if it is not already there, resets or restores the query, and shows.
     *
     * <h3>Whether the query survives a close is the CALLER's to decide</h3>
     *
     * <p>This used to always clear, on the ground that "a palette that opens showing last time's filter
     * with no visible indication it is filtered is worse than one that opens blank". That objection is
     * real and it is answered by <b>selecting</b> the restored text rather than by throwing it away —
     * which is exactly what both references do. The first keystroke replaces it, so nothing is stickier
     * than before; Enter or an arrow reuses it, which is the whole point.</p>
     *
     * <p>Default is still to clear, so nothing changes for a caller that has not asked. @see #setRetainQuery</p>
     */
    public QuickPick open(UIDocument window) {
        // hostFor, not the root: a root that refuses public children -- any composite, CrystalEditor
        // included -- would throw here. Null `near` means "window level", which the palette is.
        window.addOverlay(this, null);
        // SELECTED, not merely present -- but not here. An unselected restored query is the "filtered
        // with no visible indication" complaint this used to answer by clearing; selected, the first
        // character replaces the lot and the box reads as a fresh one that happens to be pre-filled.
        //
        // The selection is made in `onOpened` instead, because `showAt` below is what opens the popover
        // and opening is what takes focus -- and `requestFocus` puts a caret in the field, which collapses
        // any selection made before it. Selecting here therefore ran, and was undone one call later: the
        // query came back, the caret sat at the end of it, and typing APPENDED. @see #onOpened
        if (!retainQuery || search.getText().isEmpty()) search.setText("");
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

    /** Puts the "more than this" hint in the header, or takes it away. */
    private void setTruncated(boolean cut) {
        this.truncated = cut;
        // The COUNT plus a plus, rather than a sentence: the bar is 22px beside a title, and "100+
        // matches" says both what you are looking at and that it is not everything. A bare "more results"
        // says the second half and hides the first.
        truncation.setText(cut ? MAX_RESULTS + "+ matches" : "");
        StyleGroup.inlinePipeline(truncation.getStyle().getLayoutGroup(),
                l -> l.display(cut ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** What the header says. Empty hides the bar, so a picker that wants no chrome keeps none. */
    public QuickPick setTitle(@Nullable String text) {
        title.setText(text == null ? "" : text);
        boolean shown = text != null && !text.isEmpty();
        // `display`, not `opacity` or a detach: a hidden header must take no height, and nothing may enter
        // or leave the tree here -- this runs while the popup is being built and again whenever a caller
        // renames it.
        StyleGroup.inlinePipeline(header.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        return this;
    }

    public String getTitle() {
        return title.getText();
    }

    /** The header bar, so a caller can put its own controls in it. @see #setTitle */
    public UIElement headerBar() {
        return header;
    }

    /**
     * Starts a positional drag of the whole popup from a press on the header.
     *
     * <p>Through {@link #moveTo}, which is the one legal way off an anchor: it hands placement over
     * rather than fighting it, so {@link #reposition} goes quiet and there is still exactly one writer of
     * {@code left}/{@code top} at any moment. Writing the position directly would have the placement
     * ticker overwrite the drag on the very next frame.</p>
     *
     * <p>Accumulated from a snapshot rather than from the live box, the same as {@code Dialog}'s title
     * bar: reading the current position each tick compounds the drag's own deltas.</p>
     */
    private void beginMove(MouseEvent.Down event) {
        UIDocument window = document();
        if (window == null) return;
        Box box = box();
        if (box == null) return;
        dragStartLeft = box.x();
        dragStartTop = box.y();
        // Zero threshold: a window must track the very first pixel, and a header has no competing click
        // interpretation to protect.
        Drag.start(header,
                event.getPosition().x(), event.getPosition().y(),
                (mx, my, sx, sy, dx, dy) -> moveClamped(dragStartLeft + dx, dragStartTop + dy));
    }

    /** Writes the dragged position, clamped into the window so it cannot be lost off an edge. */
    private void moveClamped(float left, float top) {
        // A promoted node's containing block is whatever HOSTS it, which is the document
        // while there is no desktop and the WindowFrame once 6.6 lands one.
        UIElement container = document();
        float maxLeft = Float.MAX_VALUE, maxTop = Float.MAX_VALUE;
        Box containerBox = container == null ? null : container.box();
        Box box = box();
        if (containerBox != null && box != null) {
            maxLeft = Math.max(0f, containerBox.width() - box.width());
            maxTop = Math.max(0f, containerBox.height() - box.height());
        }
        moveTo(Math.min(Math.max(0f, left), maxLeft), Math.min(Math.max(0f, top), maxTop));
    }

    /** Focus goes to the search field the moment it opens — the caret is the point of the whole widget. */
    @Override
    protected void onOpened() {
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(search.field());
        // AFTER the focus, which is the whole reason this is not in `open`. @see #open
        if (retainQuery && !search.getText().isEmpty()) search.field().selectAll();
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
        // BEFORE the freely-positioned check, because this is not about position. A resize does not set
        // that flag -- only a drag does -- but a popup that was dragged AND resized would otherwise never
        // re-evaluate how its list is sized, and `refresh` alone only runs on a keystroke. So the switch
        // between measuring and filling is re-decided every frame, which is cheap: both writes no-op on an
        // unchanged value.
        sizeListToContent(results.size());
        // ONCE DRAGGED, THIS GOES QUIET. The base implementation returns on the same flag; an override
        // that forgot to would overwrite the drag on the very next tick, so the popup would follow the
        // pointer for one frame and snap back -- which reads as the drag not being implemented.
        if (isFreelyPositioned()) return;
        UIDocument window = document();
        if (window == null) return;
        float available = (window.box() == null ? 0f : window.box().width());
        float width = Math.max(0f, Math.min(PREFERRED_WIDTH, available - 2f * MIN_MARGIN));
        float left = Math.max(MIN_MARGIN, (available - width) / 2f);
        // WIDTH AT DEFAULT, position at IMPORTANT, and the split is what makes `resize` work at all.
        //
        // `UIResizer` writes at INLINE, per the CSS spec's rule for a user resize. A widget that writes
        // its own measurement at IMPORTANT therefore beats the handle -- so the grabber could change the
        // height and never the width, and half a resize working reads as a broken widget rather than an
        // unsupported gesture. `UIElement.markUserSized` states the remedy outright: "a widget whose size
        // the user may take writes that size at a LOWER origin". The position is not the user's until
        // they drag it, at which point this method stops running.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.width(width));
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.left(left).top(TOP_OFFSET));
    }

    // ── Query and selection ─────────────────────────────────────────────────────────────────────

    /**
     * The most rows any one query may produce.
     *
     * <p>Not a display bound — the list virtualises, so a longer one costs nothing to draw. It is a bound
     * on how much a <em>source</em> may be asked to produce per keystroke, which for the classpath index
     * is the difference between a list and a stall. Reaching it is reported rather than obeyed silently;
     * see {@link #isTruncated}.</p>
     */
    private static final int MAX_RESULTS = 100;

    /**
     * Whether the last query had more answers than are listed.
     *
     * <p>Surfaced in the header, because a list that silently stops is the worst answer a search can
     * give: a row that exists but fell past the cap looks exactly like a row that does not exist, and
     * the user stops looking. Both the source and the cap can cause it — see
     * {@link QuickPickSource.ResultSink#markTruncated}.</p>
     */
    public boolean isTruncated() {
        return truncated;
    }

    /** Re-asks the source and rebuilds the row model. */
    public void refresh() {
        // ONCE PER KEYSTROKE. Typing "Minecraft" runs this nine times, and every run re-asks the source.
        long profiled = FrameProfile.enter("QuickPick.refresh '" + search.getText() + "'");
        long timed = FrameProfile.begin();
        QuickPickSource.Batch batch =
                QuickPickSource.drain(source, SearchQuery.of(search.getText()), MAX_RESULTS);
        FrameProfile.step(timed, "source.drain");
        List<QuickPickEntry> entries = batch.entries();
        timed = FrameProfile.begin();
        results.clear();
        for (QuickPickEntry entry : entries) results.add(entry);
        FrameProfile.step(timed, "results.replace " + entries.size() + " rows");
        setTruncated(batch.truncated());
        timed = FrameProfile.begin();
        sizeListToContent(entries.size());
        FrameProfile.step(timed, "sizeListToContent");
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
            // AND box() IS NULLABLE, which is the same sentence one step further: on the frame a
            // palette opens the list may not have been laid out AT ALL, so there is no box to reset
            // -- and a list with no box is already at the offset this is trying to put it at.
            Box listBox = list.box();
            if (first < MAX_VISIBLE_ROWS && listBox != null) listBox.setScroll(0f, 0f);
        } else {
            // Nothing here can run, so nothing is selected. Leaving the previous index in place would paint
            // a dimmed row with the selection highlight -- an unrunnable row that looks like the one Enter
            // will take, which is the precise confusion dimming exists to prevent.
            list.setFocusedIndex(-1);
            list.clearSelection();
        }
        FrameProfile.leave(profiled, "QuickPick.refresh");
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
        // ONCE THE USER HAS SET A HEIGHT, THE LIST FILLS RATHER THAN MEASURES.
        //
        // Sizing to content inside a box the user has fixed is what broke a resized popup: this engine
        // defaults `flex-shrink` to **0**, so a parent with a fixed height does NOT compress its children
        // -- they keep their content size and spill straight out of it. Resize the popup tall while it is
        // empty, then type, and the rows ran on past the bottom edge and painted over the editor, because
        // nothing was ever asked to fit.
        //
        // `height: 0; flex-grow: 1` is this codebase's fill idiom, and it deliberately does not touch
        // `flex-shrink` -- the 0 default is what stops content being compressed below its own size, and
        // overriding it to 1 collapses the box instead. @see AGENTS.md
        if (userSizedHeight) {
            StyleGroup.inlinePipeline(list.getStyle().getLayoutGroup(),
                    l -> l.height(0f).flexGrow(1f));
            return;
        }
        float height = Math.min(rowCount, MAX_VISIBLE_ROWS) * ROW_HEIGHT;
        // flexGrow BACK TO 0, not merely a height: the two are written by the same method and a popup that
        // was user-sized and then reopened would otherwise keep growing into whatever space it is given.
        StyleGroup.inlinePipeline(list.getStyle().getLayoutGroup(),
                l -> l.height(height).flexGrow(0f));
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
        /**
         * The kind glyph, built with the template and hidden when unused.
         *
         * <p>{@link SymbolIcon} rather than a name resolved to a drawable, because that widget is where
         * the {@code completion-kind-*} vocabulary lives and a second table saying the same thing drifts
         * invisibly — a class glyph on an interface reads as a row with an icon, not a row with the wrong
         * one. Built here and not in {@code bind} for the reason {@link #keyBoxes} records at length.</p>
         */
        final SymbolIcon icon = new SymbolIcon();
        /**
         * The other glyph — a file's, resolved from a name rather than a kind.
         *
         * <p>A sibling rather than a mode of {@link #icon}, because the two are drawn by different
         * machinery: a {@link SymbolIcon} stacks modifier layers as internal children, a file icon is one
         * overlay drawable. Exactly one is ever shown.</p>
         */
        final UIElement fileIcon = new UIElement();
        final UIText category = new UIText("");
        final UIText label = new UIText("");
        /** Dim, trailing, never highlighted. @see QuickPickItem#description */
        final UIText description = new UIText("");
        final UIElement spacer = new UIElement();
        /**
         * A ROW of key boxes, not one string.
         *
         * <p>{@code "Ctrl+Shift+P"} as a single text can only ever be plain text, and every editor draws
         * each key as its own bordered box. Splitting it here is what lets a sheet style a key at all.</p>
         */
        final UIElement accelerator = new UIElement();

        /**
         * A FIXED set of key boxes, built once with the template and hidden when unused.
         *
         * <p>Built during {@code createTemplate} rather than per bind, which is the pooling idiom the
         * editor's gutter decorations already use. Creating them in {@code bind} put brand-new elements
         * into the tree after the layout pass that would have measured them, so on the frame a row first
         * appeared every box collapsed to its minimum and its label hung out — and came right only once
         * the row was recycled and bound a second time. That is exactly the "scroll it away and back and
         * it fixes itself" report.</p>
         */
        final List<UIElement> keyBoxes = new ArrayList<>();
        final List<UIText> keyLabels = new ArrayList<>();
        final List<UIText> keySeparators = new ArrayList<>();

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
            row.icon.addClass(ICON_CLASS);
            row.fileIcon.addClass(FILE_ICON_CLASS);
            row.fileIcon.setHitTest(false);
            row.category.addClass(CATEGORY_CLASS);
            row.label.addClass(LABEL_CLASS);
            row.description.addClass(DESCRIPTION_CLASS);
            row.spacer.addClass(SPACER_CLASS);
            row.accelerator.addClass(ACCELERATOR_CLASS);
            // Unhittable, like every composite part: the row itself takes the press, and an icon that
            // took it instead would swallow the click that accepts.
            row.icon.setHitTest(false);
            row.description.setHitTest(false);
            row.append(row.icon);
            row.append(row.fileIcon);
            row.append(row.category);
            row.append(row.label);
            row.append(row.description);
            row.append(row.spacer);
            row.append(row.accelerator);
            for (int i = 0; i < MAX_KEYS_PER_CHORD; i++) {
                if (i > 0) {
                    UIText plus = new UIText("+");
                    plus.addClass(KEY_SEPARATOR_CLASS);
                    plus.setHitTest(false);
                    row.keySeparators.add(plus);
                    row.accelerator.append(plus);
                }
                UIElement box = new UIElement();
                box.addClass(KEY_CLASS);
                box.setHitTest(false);
                UIText label = new UIText("");
                label.setHitTest(false);
                box.append(label);
                row.keyBoxes.add(box);
                row.keyLabels.add(label);
                row.accelerator.append(box);
            }

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
            setAccelerator(row, item.accelerator());

            // BOTH SET AND CLEARED on every bind, because rows are pooled. A row that once showed a class
            // and is reused for a command keeps the glyph otherwise -- the same swap-never-add rule the
            // file tree's `filetype-*` classes and the dock's `decoration-*` ones both follow.
            String description = item.description();
            boolean described = description != null && !description.isEmpty();
            row.description.setText(described ? description : "");
            show(row.description, described);

            boolean hasKind = item.kind() != null;
            show(row.icon, hasKind);
            row.icon.show(item.kind(), item.isAbstract()
                    ? Collections.singleton(SymbolModifier.ABSTRACT) : Collections.emptySet());

            // KIND WINS. Both being set is a caller's mistake rather than a state to render, and drawing
            // both would be two glyphs where the sheet has spaced one.
            CgUiSvg glyph = hasKind || item.iconName() == null || item.iconName().isEmpty()
                    ? null : CgUiSvg.ofIcon(item.iconName());
            show(row.fileIcon, glyph != null);
            // Cleared as well as set: rows are pooled, so a row that showed a `.java` and is reused for a
            // command would otherwise keep the drawable behind a hidden box -- and reappear with it the
            // next time anything unhid it.
            final CgUiDrawable drawable = glyph == null ? CgUiDrawable.EMPTY : glyph;
            StyleGroup.defaultPipeline(row.fileIcon.getStyle().getGeneralGroup(),
                    g -> g.overlay(drawable));

            // Set AND cleared, because rows are recycled -- a row that showed a disabled command and is
            // reused for an enabled one would otherwise stay dimmed and unclickable-looking forever.
            if (item.enabled()) row.removeClass(DISABLED_CLASS);
            else row.addClass(DISABLED_CLASS);

            apply(row.label, entry.labelRanges());
            apply(row.category, entry.categoryRanges());
        }

        /**
         * Fills the fixed key slots for one row, hiding the rest. @see Row#keyBoxes
         *
         * <p>{@code display} rather than detaching, so nothing enters or leaves the tree during a bind —
         * the same reason {@code Tab} hides its panes instead of removing them.</p>
         */
        private void setAccelerator(Row row, @Nullable String chord) {
            String[] keys = chord == null || chord.isEmpty() ? new String[0] : chord.split("[+]");
            int shown = 0;
            for (String raw : keys) {
                if (shown >= MAX_KEYS_PER_CHORD) break;
                String key = raw.trim();
                if (key.isEmpty()) continue;
                row.keyLabels.get(shown).setText(key);
                shown++;
            }
            for (int i = 0; i < MAX_KEYS_PER_CHORD; i++) show(row.keyBoxes.get(i), i < shown);
            for (int i = 0; i < row.keySeparators.size(); i++) show(row.keySeparators.get(i), i + 1 < shown);
        }

        private void show(UIElement element, boolean visible) {
            StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                    l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
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
