package com.crystalgui.widget.texteditor.find;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Collection;
import java.util.ArrayList;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The editor's find and replace bar — IntelliJ's {@code SearchReplaceComponent}, Monaco's
 * {@code FindWidget}.
 *
 * <h3>A view, and only a view</h3>
 *
 * <p>It holds no matches and does no searching. The scan is {@link com.crystalgui.text.search.TextSearch}'s,
 * the occurrences and exclusions are {@link com.crystalgui.text.search.SearchResults}', and this reads the
 * numbers back out to draw them. That is the split both references make (§31), and it is why this file has
 * no {@code indexOf} in it.</p>
 *
 * <h3>The same bar as the tree's</h3>
 *
 * <p>Deliberately the same chrome as {@code TreeSearch}'s: {@code __find-bar__}, a {@link SearchField} with
 * the {@code Cc} / {@code W} / {@code .*} toggles inside its border, the same count, arrows and close. The
 * toggles are built by {@link SearchField#optionToggle} so the two bars cannot drift — six things have to
 * be right about one and none of them is visible in a screenshot.</p>
 *
 * <p>New here: a <b>chevron</b> that expands the replace row, a replace box with <b>Preserve case</b>, and
 * <b>Replace</b> / <b>Replace All</b> / <b>Exclude</b>.</p>
 */
public class SearchReplaceBar extends UIElement {
    /**
     * Its own kind. Every concrete node needs one, and a subclass that declares none
     * INHERITS its supertype's — so this would have reported {@code element} and matched
     * every rule written for one. The ToolWindowFrame trap, which cost a whole unstyled
     * widget; {@code NodeKindsCoverageTest} is what makes it a compile-time question.
     */
    public static final Name NAME = Name.of("searchreplacebar");


    public static final String BAR_CLASS = "__find-bar__";
    public static final String ROWS_CLASS = "__find-rows__";
    public static final String ROW_CLASS = "__find-row__";
    public static final String CHEVRON_CLASS = "__find-chevron__";
    public static final String INPUT_CLASS = "__find-input__";
    public static final String COUNT_CLASS = "__find-count__";
    public static final String PREV_CLASS = "__find-prev__";
    public static final String NEXT_CLASS = "__find-next__";
    public static final String CLOSE_CLASS = "__find-close__";
    public static final String OFF_CLASS = "__off__";

    /** The fixed-width group after each box, which is what keeps the two boxes the same width. */
    public static final String TRAILING_CLASS = "__find-trailing__";

    /** The three text buttons on the replace row. */
    public static final String ACTION_CLASS = "__find-action__";

    /** Fires when the bar is dismissed, so a host can take focus back. */
    public final Signal.Action onClosed = new Signal.Action();

    private final TextEditor editor;

    private final UIElement rows = new UIElement();
    private final UIElement findRow = new UIElement();
    private final UIElement replaceRow = new UIElement();
    private final UIElement findTrailing = new UIElement();
    private final UIElement replaceTrailing = new UIElement();

    private final Button chevron = new Button("");
    private final SearchField findBox = new SearchField();
    private final SearchField replaceBox = new SearchField();
    private final UIText count = new UIText("");
    private final Button prev = new Button("");
    private final Button next = new Button("");
    private final Button close = new Button("");
    private final Button replaceOne = new Button("Replace");
    private final Button replaceAll = new Button("Replace All");
    private final Button exclude = new Button("Exclude");

    private SearchQuery.Options options = SearchQuery.Options.DEFAULT;
    private boolean replaceShown;
    private boolean writingBack;

    public SearchReplaceBar(TextEditor editor) {
        super(NAME);
        this.editor = editor;
        addClass(BAR_CLASS);

        rows.addClass(ROWS_CLASS);
        findRow.addClass(ROW_CLASS);
        replaceRow.addClass(ROW_CLASS);

        buildFindRow();
        buildReplaceRow();

        rows.append(findRow);
        rows.append(replaceRow);
        append(chevron);
        append(rows);
        append(close);

        chevron.addClass(CHEVRON_CLASS);
        // ON THE MOUSE-DOWN, which is the frame the blur happens in. `emitMouseDown` blurs before it
        // dispatches, and a Button's onPressed fires on the mouse-UP -- so restoring focus there left the
        // field drawn unfocused for every frame in between, which is the flicker. Same frame, no gap.
        chevron.onMouseDown.attachListener((element, event) -> focus(findBox.field()), false, true);
        chevron.onPressed.connect(() -> {
            setReplaceShown(!replaceShown);
            // Focus was restored on the down; opening the row must not move it. Opening replace is not the
            // same as wanting to type in it, and the query you were in the middle of is still the query.
            focus(findBox.field());
        });

        close.addClass(CLOSE_CLASS);
        close.onPressed.connect(this::close);

        // THE BAR FOLLOWS THE DOCUMENT, not only its own buttons. Everything it says -- the count, the dead
        // arrows, the red query -- is read from the editor, and it was only ever re-read when the bar
        // itself had just done something. An edit from outside (undo, redo, a paste, a server push) moved
        // the matches and left the bar reporting the numbers from before it.
        //
        // The editor re-runs the search from the buffer's change signal BEFORE emitting this, so the
        // numbers are already current by the time this reads them.
        tooltips.put(prev, new String[]{"Previous Match", "editor.findPrevious"});
        tooltips.put(next, new String[]{"Next Match", "editor.findNext"});
        tooltips.put(chevron, new String[]{"Replace", "editor.replace"});
        tooltips.put(close, new String[]{"Close", "editor.find.close"});
        tooltips.put(replaceOne, new String[]{"Replace", "editor.replaceCurrent"});
        tooltips.put(replaceAll, new String[]{"Replace All", "editor.replaceAll"});
        tooltips.put(exclude, new String[]{"Exclude", "editor.excludeMatch"});

        editor.onChanged.connect(text -> refresh());

        for (UIElement element : new UIElement[]{findBox.field(), replaceBox.field(),
                replaceOne, replaceAll, exclude}) {
            bindTab(element);
        }

        setReplaceShown(false);
        refresh();
    }

    private void buildFindRow() {
        findBox.addClass(INPUT_CLASS);
        findBox.setPlaceholder("Search");
        findBox.field().attachListener(text -> {
            if (!writingBack) runSearch();
        });
        findBox.field().onKeyDown.attachListener((element, event) -> {
            boolean handled = switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_RETURN -> CgModifiers.hasShift(event.getModifiers())
                        ? step(-1) : step(1);
                case CgKeyCodes.KEY_DOWN -> step(1);
                case CgKeyCodes.KEY_UP -> step(-1);
                case CgKeyCodes.KEY_ESCAPE -> {
                    // ESCAPE IS A CASCADE, and this bar is TWO of its steps: clear the query, then close.
                    // Only once both are done does it belong to whatever is outside. Written as one step it
                    // cleared the query and then sat there — which is the opposite of what Escape means in
                    // a find bar, where closing is the whole point of the key.
                    if (!findBox.getText().isEmpty()) {
                        findBox.setText("");
                        runSearch();
                        yield true;
                    }
                    if (isOpen()) {
                        close();
                        yield true;
                    }
                    yield false;
                }
                default -> false;
            };
            if (handled) event.stopPropagation();
        }, false, true);

        matchCaseToggle = option(findBox, "__option-match-case__", "Match Case", "editor.toggleMatchCase",
                () -> options.matchCase(), on -> options = options.withMatchCase(on));
        wordsToggle = option(findBox, "__option-words__", "Words", "editor.toggleWholeWords",
                () -> options.wholeWords(), on -> options = options.withWholeWords(on));
        regexToggle = option(findBox, "__option-regex__", "Regex", "editor.toggleRegex",
                () -> options.regex(), on -> options = options.withRegex(on));

        count.addClass(COUNT_CLASS);
        prev.addClass(PREV_CLASS);
        prev.onPressed.connect(() -> step(-1));
        next.addClass(NEXT_CLASS);
        next.onPressed.connect(() -> step(1));

        findRow.append(findBox);
        // THE TRAILING GROUP IS A FIXED WIDTH, and the same one on both rows. Without it the two boxes are
        // sized by whatever happens to sit beside them -- three text buttons on one row and a count plus
        // two glyphs on the other -- so the replace box wrapped a good 200px earlier than the search box
        // and the two never lined up. IntelliJ lays these out as a grid for the same reason.
        findTrailing.addClass(TRAILING_CLASS);
        findTrailing.append(count);
        findTrailing.append(prev);
        findTrailing.append(next);
        findRow.append(findTrailing);
    }

    private void buildReplaceRow() {
        replaceBox.addClass(INPUT_CLASS);
        replaceBox.setPlaceholder("Replace");
        preserveCaseToggle = option(replaceBox, "__option-preserve-case__", "Preserve case",
                "editor.togglePreserveCase",
                editor::preserveCase, editor::setPreserveCase);

        replaceOne.addClass(ACTION_CLASS);
        replaceOne.onPressed.connect(() -> {
            editor.replaceCurrent(replaceBox.getText());
            refresh();
        });
        replaceAll.addClass(ACTION_CLASS);
        replaceAll.onPressed.connect(() -> {
            editor.replaceAll(replaceBox.getText());
            refresh();
        });
        exclude.addClass(ACTION_CLASS);
        exclude.onPressed.connect(() -> {
            editor.toggleExcludeCurrentMatch();
            refresh();
        });

        replaceTrailing.addClass(TRAILING_CLASS);
        replaceTrailing.append(replaceOne);
        replaceTrailing.append(replaceAll);
        replaceTrailing.append(exclude);
        replaceRow.append(replaceBox);
        replaceRow.append(replaceTrailing);
    }

    /**
     * One option toggle: the glyph, its tooltip, its accelerator and its on-state.
     *
     * <p>The button comes from {@link SearchField#optionToggle} so this bar and the tree's build them the
     * same way. The accelerator binds on the box rather than in the keymap — Alt+W must not be taken from
     * the rest of the application, the same reasoning that puts Ctrl+F on the widget.</p>
     */
    private Button option(SearchField box, String styleClass, String title, String commandId,
                          BooleanSupplier get, Consumer<Boolean> set) {
        Button toggle = SearchField.optionToggle(styleClass, title, "");
        tooltips.put(toggle, new String[]{title, commandId});
        SearchField.setOptionOn(toggle, get.getAsBoolean());
        toggle.onPressed.connect(() -> {
            boolean on = !get.getAsBoolean();
            set.accept(on);
            SearchField.setOptionOn(toggle, on);
            runSearch();
        });
        (box == findBox ? findOptions : replaceOptions).add(toggle);
        bindTab(toggle);
        box.addOption(toggle);
        return toggle;
    }

    // ── Driving the editor ──────────────────────────────────────────────────────────────────────

    /**
     * FROM WHAT IS ON SCREEN, not from the caret. {@code findNext()} is a STEPPING command — "somewhere I
     * am not" — and a freshly typed query has nowhere to step from, so running it here anchored the search
     * on the caret. Scrolling is view state and never moves the caret, so after reading your way down a
     * file the caret is still wherever you last clicked, and typing a query scrolled the document back to
     * it.
     */
    private void runSearch() {
        runSearch(editor.firstVisibleOffset());
    }

    /** As above, from an explicit anchor — a seeded query starts at the selection it was seeded from. */
    private void runSearch(int from) {
        editor.find(SearchQuery.of(findBox.getText(), options));
        editor.findFrom(from);
        refresh();
    }

    private boolean step(int delta) {
        boolean moved = delta < 0 ? editor.findPrevious() : editor.findNext();
        refresh();
        return moved;
    }

    /** Writes what the bar says: the count, the dead arrows, and the not-found state. */
    private void refresh() {
        int matches = editor.matchCount();
        boolean nothing = !findBox.getText().isEmpty() && matches == 0;
        findBox.setNotFound(nothing);
        // "3", not "0/3". A re-find leaves nothing selected until something steps to a match, and a
        // leading zero reads as a failure rather than as a total -- which is exactly how it looked after
        // an undo restored three matches.
        int at = editor.currentMatchNumber();
        count.setText(matches == 0 ? "0" : at == 0 ? String.valueOf(matches) : at + "/" + matches);
        if (nothing) count.addClass(SearchField.NOT_FOUND_CLASS);
        else count.removeClass(SearchField.NOT_FOUND_CLASS);

        for (Button arrow : new Button[]{prev, next}) {
            arrow.setEnabled(matches > 0);
            // Out of hit testing as well: `:disabled` and `:hover` tie on specificity, so a disabled glyph
            // lights up under the pointer and shows its tooltip. See TreeSearch.
            arrow.setHitTest(matches > 0);
            if (matches > 0) arrow.removeClass(OFF_CLASS);
            else arrow.addClass(OFF_CLASS);
        }
        boolean excluded = editor.searchResults().isCurrentExcluded();
        exclude.setText(excluded ? "Include" : "Exclude");
        for (Button action : new Button[]{replaceOne, replaceAll, exclude}) {
            action.setEnabled(matches > 0);
        }
    }

    // ── Opening and closing ─────────────────────────────────────────────────────────────────────

    /** Shows the bar and puts the caret in the search box — Ctrl+F. */
    public void open() {
        open = true;
        setDisplayed(true);
        // FIND MEANS FIND. Reopening with the replace row still expanded gives back the state you left,
        // which is not what the key asked for.
        setReplaceShown(false);
        // READ BEFORE FOCUS MOVES, applied after. Whether focusing the box disturbs either selection is
        // not something this needs an answer to -- taking the reading first makes the order irrelevant.
        String seed = seedFromSelection();
        int seedAt = seed == null ? -1 : editor.getSelectionStart();
        focus(findBox.field());
        applySeed(seed, seedAt);
    }

    /** Shows the bar with the replace row expanded — Ctrl+R. */
    public void openReplace() {
        open = true;
        setDisplayed(true);
        setReplaceShown(true);
        String seed = seedFromSelection();
        int seedAt = seed == null ? -1 : editor.getSelectionStart();
        focus(findBox.field());
        applySeed(seed, seedAt);
    }

    /**
     * The editor's selection, when it is usable as a query — otherwise null.
     *
     * <p>Selecting a word and pressing Ctrl+F is how you search for that word in both references, which is
     * the whole reason the selection is made first rather than after.</p>
     *
     * <p><b>A multi-line selection is a scope, not a query.</b> Both references read one as "search inside
     * this" rather than as a literal to look for, and nothing here implements that scope yet — so the
     * honest answer is to leave the box alone. Seeding it anyway would paste a block of text that matches
     * nothing and would bury whatever query was there, which is worse than not seeding at all.</p>
     */
    private String seedFromSelection() {
        if (!editor.hasSelection()) return null;
        String selected = editor.getSelectedText();
        if (selected.isEmpty() || selected.indexOf('\n') >= 0 || selected.indexOf('\r') >= 0) {
            return null;
        }
        return selected;
    }

    /** Writes the seed, runs the search once, and leaves the box selected so typing replaces it. */
    private void applySeed(String seed, int seedAt) {
        if (seed != null) {
            // SUPPRESSED, then run explicitly. The field's listener fires only on a CHANGED value, so
            // re-opening on the same word the bar was last closed with would put the word in the box with
            // no matches behind it -- close() clears the editor's query, and nothing would have re-run it.
            writingBack = true;
            try {
                findBox.setText(seed);
            } finally {
                writingBack = false;
            }
            // ANCHORED ON THE SELECTION, not on the viewport: the occurrence you highlighted IS the one
            // you asked about, and it is already the current selection -- so selectMatch re-selects the
            // same range and ensureCaretVisible has nothing to scroll. Anchored on the viewport instead,
            // a second occurrence higher up the screen would win and the highlight would jump off the
            // word you picked, which is the same complaint the viewport anchor was introduced to fix.
            runSearch(seedAt);
        }
        // SELECTED EITHER WAY, so the next keystroke replaces what is there. A seed is a guess at what you
        // meant and typing over it must not require clearing it first; with no seed this is what makes a
        // second Ctrl+F offer the previous query for replacement rather than for editing.
        findBox.field().selectAll();
    }

    public void close() {
        open = false;
        setDisplayed(false);
        editor.find((SearchQuery) null);
        onClosed.emit();
    }

    public boolean isOpen() {
        return open;
    }

    private boolean open = true;

    public boolean isReplaceShown() {
        return replaceShown;
    }

    /** Expands or folds the replace row, and turns the chevron. */
    public void setReplaceShown(boolean shown) {
        replaceShown = shown;
        // `setDisplayed`, the engine's own attribute, where the old engine wrote `display` at
        // IMPORTANT. This selector engine has no attribute selectors, so HTML's `[hidden]` rule
        // cannot be written -- the box tree gives a hidden node no box instead, which is exactly
        // what the IMPORTANT write effectively did. @see plan_m6.md M6.0
        replaceRow.setDisplayed(shown);
        // The glyph is the sheet's business; this only says which state it is in, as a class rather than a
        // pseudo-class -- the engine does not re-evaluate one flipped from a listener.
        if (shown) chevron.addClass("__expanded__");
        else chevron.removeClass("__expanded__");
    }

    /**
     * Tab order: the query, then the replacement, then the options.
     *
     * <p>DOM order cannot express it. The toggles live <em>inside</em> the find box — that is what makes the
     * box one control — so they precede the replace row in the tree, and Tab walked
     * {@code query → Cc → W → .* → replacement}. What is wanted is the two text fields first: they are what
     * the bar is for, and the options are a refinement of a query you have already typed. Integer
     * {@code tabindex} was deliberately never ported (see {@code FocusPolicy}), so an explicit ring is the
     * only lever, and it is the honest one — the order is a decision, not a side effect of nesting.</p>
     *
     * <p>Hidden rows are skipped rather than focused invisibly, so a folded replace row is not three dead
     * Tab stops.</p>
     */
    private List<UIElement> tabRing() {
        List<UIElement> ring = new ArrayList<>();
        ring.add(findBox.field());
        if (replaceShown) ring.add(replaceBox.field());
        ring.addAll(findOptions);
        if (replaceShown) {
            ring.addAll(replaceOptions);
            ring.add(replaceOne);
            ring.add(replaceAll);
            ring.add(exclude);
        }
        return ring;
    }

    /** Moves along the ring, wrapping. Returns false when focus is not on it at all. */
    private boolean moveTab(int delta) {
        List<UIElement> ring = tabRing();
        UIDocument window = document();
        if (window == null || ring.isEmpty()) return false;
        UIElement focused = window.focus().focused();
        int at = ring.indexOf(focused);
        if (at < 0) return false;
        int next = Math.floorMod(at + delta, ring.size());
        window.focus().requestFocus(ring.get(next));
        return true;
    }

    /** Tab and Shift+Tab, on everything the ring contains. */
    private void bindTab(UIElement element) {
        element.onKeyDown.attachListener((el, event) -> {
            if (event.getKeyCode() != CgKeyCodes.KEY_TAB) return;
            // THE RING IS Tab AND Shift+Tab, so a Ctrl-held Tab belongs to somebody else -- the desktop's
            // window switcher, today. The keymap resolves only on an UNCONSUMED event, so stopping
            // propagation here would deny the chord to every binding above with nothing to report it.
            if (CgModifiers.hasCtrl(event.getModifiers())
                    || CgModifiers.hasSuper(event.getModifiers())
                    || CgModifiers.hasAlt(event.getModifiers())) {
                return;
            }
            if (moveTab(CgModifiers.hasShift(event.getModifiers()) ? -1 : 1)) event.stopPropagation();
        }, false, true);
    }

    /**
     * What each control is called and which command it runs, so its tooltip can name the <b>live</b> chord.
     *
     * <p>The accelerators were spelled into the tooltips as literals — "Alt+X" — which is a promise the
     * widget cannot keep the moment anything rebinds the command. {@code Keymap.acceleratorFor} is what the
     * menus already read, and it resolves outward from this element, so a keymap installed anywhere above
     * answers.</p>
     */
    private final Map<Button, String[]> tooltips = new LinkedHashMap<>();

    private void refreshTooltips() {
        for (Map.Entry<Button, String[]> entry : tooltips.entrySet()) {
            String title = entry.getValue()[0];
            KeyChord chord =
                    Keymap.acceleratorFor(this, entry.getValue()[1]);
            String text = chord == null ? title : title + "  " + chord;
            if (text.equals(shownTooltips.get(entry.getKey()))) continue;
            shownTooltips.put(entry.getKey(), text);
            // ONE Tooltip, RETAINED. `attach` adds a hover listener pair every time it is called and does
            // not replace what is already there -- so calling it again left the first tooltip in place and
            // the accelerator never appeared, however correct the lookup was. StatusBarView's own note says
            // the same thing.
            Tooltip existing = attached.get(entry.getKey());
            if (existing == null) attached.put(entry.getKey(), Tooltip.attach(entry.getKey(), text));
            else existing.setText(text);
        }
    }

    /**
     * What every tooltip currently says — the only observable of it.
     *
     * <p>Worth having: a tooltip's text is not in the layout, not in the computed style and not in any
     * capture unless the pointer happens to be over it, so "does it name its accelerator" has nowhere else
     * to be asserted. It was wrong twice — once because {@code Tooltip.attach} adds rather than replaces,
     * and once because the refresh was written and never called.</p>
     */
    public Collection<String> tooltipTexts() {
        return shownTooltips.values();
    }

    /** What each tooltip currently says, so the lookup is not repeated every frame. */
    private final Map<Button, String> shownTooltips = new HashMap<>();

    /** The one Tooltip per control. @see #refreshTooltips */
    private final Map<Button, Tooltip> attached = new HashMap<>();

    private final List<UIElement> findOptions = new ArrayList<>();
    private final List<UIElement> replaceOptions = new ArrayList<>();

    /**
     * Keeps the editor's text clear of the bar.
     *
     * <p>The bar floats — that is what keeps it out of the editor's layout sums — so nothing would
     * otherwise stop it covering the first lines. IntelliJ's editor starts below its bar, and the cheapest
     * honest way to get that from a floating widget is to inset the thing underneath by exactly what the
     * widget measured. Run from a ticker because the height changes when the replace row appears.</p>
     */
    private void syncEditorInset() {
        float inset = isOpen() ? boxHeight(this) : 0f;
        if (Math.abs(inset - appliedInset) < 0.5f) return;
        appliedInset = inset;
        StyleGroup.inlinePipeline(editor.getStyle().getLayoutGroup(), l -> l.paddingTop(inset));
        // AND THE SCROLLBAR, which padding alone does not move. The bar is pinned to the editor's padding
        // box with `top: 0`, so growing the padding pushes the text down and leaves the scrollbar starting
        // where it always did -- underneath this widget, with its top section unreachable. Both halves of
        // "the editor starts below the bar" belong to the same measurement, so they are written together;
        // splitting them is how one of the two silently stops being updated.
        editor.setTopChromeInset(inset);
    }

    private float appliedInset = -1f;

    /**
     * Lines the two boxes up by matching the find row's trailing group to the replace row's.
     *
     * <p>The two rows carry different things after the box — a count and two arrows on one, three text
     * buttons on the other — so left alone the boxes are sized by whatever happens to sit beside them and
     * never line up. A fixed width in the stylesheet was the first answer and cannot work: the right
     * number is whatever <em>Replace / Replace All / Exclude</em> happen to measure, which depends on the
     * font, the theme's padding and the labels themselves.</p>
     *
     * <h3>One direction only, which is what makes it terminate</h3>
     *
     * <p>The replace group is never written to — it stays content-sized and therefore truthful — and the
     * find group's minimum follows it. Measuring both and applying the max to both would feed each
     * group's own written width back into the next frame's measurement, which is the monotonic ratchet
     * {@code NavigatorView} records: it can only ever grow, so one long label pins the floor for the
     * session. Here the input is a measurement of something this method never touches.</p>
     *
     * <p>When the replace row is collapsed its group measures zero, so the find box takes the whole bar —
     * which is what it should do when there is nothing to line up with.</p>
     */
    private void syncTrailingWidths() {
        float target = replaceShown
                ? boxWidth(replaceTrailing) : 0f;
        if (Math.abs(target - appliedTrailing) < 0.5f) return;
        appliedTrailing = target;
        StyleGroup.inlinePipeline(findTrailing.getStyle().getLayoutGroup(), l -> l.minWidth(target));
    }

    private float appliedTrailing = -1f;

    /**
     * Registers the inset ticker once the bar is in a window.
     *
     * <p>From a TICKER and not from here: {@link #syncEditorInset} writes a style, and a structural write
     * inside the layout pass is the one thing {@code onLayoutChanged} must not do. The navigator's sidebar
     * fitting carries the same note.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        if (ticking || document() == null) return;
        ticking = true;
        // AN AFTER-LAYOUT HOOK, standing, where the old engine armed a one-shot ticker from
        // `onLayoutChanged`. The note below is why it was a ticker at all -- a structural write inside
        // the layout pass is the one thing that hook must not do -- and `afterLayout` answers it
        // directly: it runs once layout has settled, so the write lands on the next pass rather than
        // re-entering this one. Owned by the bar, so closing it drops the hook.
        document().animation().afterLayout(this, delta -> {
            syncEditorInset();
            syncTrailingWidths();
            // FROM HERE TOO, and not from the constructor: `Keymap.acceleratorFor` walks up from this
            // element, so it can only answer once the bar is in a tree whose editor has installed its
            // keymap. Cheap to re-ask -- `refreshTooltips` compares the text it last wrote and does
            // nothing when it has not moved.
            refreshTooltips();
            return true;
        });
    }

    private boolean ticking;

    private void focus(TextField field) {
        UIDocument window = document();
        if (window != null) window.focus().requestPointerFocus(field);
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────

    /** What the count says — the only observable of it, for a test that must not assert on pixels. */
    /**
     * The operations a command can invoke.
     *
     * <p>The chords used to be listeners on this widget's own fields — Alt+C, Alt+W, Alt+X, Ctrl+F, Ctrl+R
     * — which put six shortcuts somewhere no keymap could see and nobody could rebind. This engine has a
     * command layer and an element-scoped resolver for exactly this; a bar in an application belongs in it.
     * (A generic widget like {@code TreeSearch} is the case that does not: making it depend on a
     * {@code CommandRegistry} to answer one keystroke would be the wrong direction.)</p>
     */
    public void toggleMatchCase() {
        matchCaseToggle.onPressed.emit();
    }

    public void toggleWholeWords() {
        wordsToggle.onPressed.emit();
    }

    public void toggleRegex() {
        regexToggle.onPressed.emit();
    }

    public void togglePreserveCase() {
        preserveCaseToggle.onPressed.emit();
    }

    /** Replaces the selected match. */
    public void replaceCurrent() {
        replaceOne.onPressed.emit();
    }

    /** Replaces every match the user has not excluded. */
    public void replaceEvery() {
        replaceAll.onPressed.emit();
    }

    /** Takes the selected match out of Replace All, or puts it back. */
    public void toggleExclude() {
        exclude.onPressed.emit();
    }

    private Button matchCaseToggle;
    private Button wordsToggle;
    private Button regexToggle;
    private Button preserveCaseToggle;

    public String countText() {
        return count.getText();
    }

    public SearchField findField() {
        return findBox;
    }

    public SearchField replaceField() {
        return replaceBox;
    }

    public SearchQuery.Options searchOptions() {
        return options;
    }

    @Nullable
    public UIElement replaceRow() {
        return replaceRow;
    }

    /** The parts are this widget's own structure. */
    /** A node's border-box width, or zero before it has a box. @see com.crystalgui.ui.box.Box */
    private static float boxHeight(UIElement node) {
        Box box = node.box();
        return box == null ? 0f : box.height();
    }

    /** @see #boxHeight */
    private static float boxWidth(UIElement node) {
        Box box = node.box();
        return box == null ? 0f : box.width();
    }

}
