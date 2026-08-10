package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.SearchField;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;

import dev.vfyjxf.taffy.style.TaffyDisplay;

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
        this.editor = editor;
        addClass(BAR_CLASS);

        rows.addClass(ROWS_CLASS);
        findRow.addClass(ROW_CLASS);
        replaceRow.addClass(ROW_CLASS);

        buildFindRow();
        buildReplaceRow();

        rows.addChild(findRow);
        rows.addChild(replaceRow);
        addInternalChild(chevron);
        addInternalChild(rows);
        addInternalChild(close);

        chevron.addClass(CHEVRON_CLASS);
        Tooltip.attach(chevron, "Replace  Ctrl+R");
        chevron.onPressed.connect(() -> setReplaceShown(!replaceShown));

        close.addClass(CLOSE_CLASS);
        Tooltip.attach(close, "Close  Escape");
        close.onPressed.connect(this::close);

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

        option(findBox, "__option-match-case__", "Match Case", "Alt+C", CgKeyCodes.KEY_C,
                () -> options.matchCase(), on -> options = options.withMatchCase(on));
        option(findBox, "__option-words__", "Words", "Alt+W", CgKeyCodes.KEY_W,
                () -> options.wholeWords(), on -> options = options.withWholeWords(on));
        option(findBox, "__option-regex__", "Regex", "Alt+X", CgKeyCodes.KEY_X,
                () -> options.regex(), on -> options = options.withRegex(on));

        count.addClass(COUNT_CLASS);
        prev.addClass(PREV_CLASS);
        Tooltip.attach(prev, "Previous Match  Shift+Enter");
        prev.onPressed.connect(() -> step(-1));
        next.addClass(NEXT_CLASS);
        Tooltip.attach(next, "Next Match  Enter");
        next.onPressed.connect(() -> step(1));

        findRow.addChild(findBox);
        // THE TRAILING GROUP IS A FIXED WIDTH, and the same one on both rows. Without it the two boxes are
        // sized by whatever happens to sit beside them -- three text buttons on one row and a count plus
        // two glyphs on the other -- so the replace box wrapped a good 200px earlier than the search box
        // and the two never lined up. IntelliJ lays these out as a grid for the same reason.
        findTrailing.addClass(TRAILING_CLASS);
        findTrailing.addChild(count);
        findTrailing.addChild(prev);
        findTrailing.addChild(next);
        findRow.addChild(findTrailing);
    }

    private void buildReplaceRow() {
        replaceBox.addClass(INPUT_CLASS);
        replaceBox.setPlaceholder("Replace");
        option(replaceBox, "__option-preserve-case__", "Preserve case", "Alt+E", CgKeyCodes.KEY_E,
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
        Tooltip.attach(exclude, "Exclude this match from Replace All");
        exclude.onPressed.connect(() -> {
            editor.toggleExcludeCurrentMatch();
            refresh();
        });

        replaceTrailing.addClass(TRAILING_CLASS);
        replaceTrailing.addChild(replaceOne);
        replaceTrailing.addChild(replaceAll);
        replaceTrailing.addChild(exclude);
        replaceRow.addChild(replaceBox);
        replaceRow.addChild(replaceTrailing);
    }

    /**
     * One option toggle: the glyph, its tooltip, its accelerator and its on-state.
     *
     * <p>The button comes from {@link SearchField#optionToggle} so this bar and the tree's build them the
     * same way. The accelerator binds on the box rather than in the keymap — Alt+W must not be taken from
     * the rest of the application, the same reasoning that puts Ctrl+F on the widget.</p>
     */
    private void option(SearchField box, String styleClass, String title, String accelerator, int key,
                        BooleanSupplier get, Consumer<Boolean> set) {
        Button toggle = SearchField.optionToggle(styleClass, title, accelerator);
        SearchField.setOptionOn(toggle, get.getAsBoolean());
        toggle.onPressed.connect(() -> {
            boolean on = !get.getAsBoolean();
            set.accept(on);
            SearchField.setOptionOn(toggle, on);
            runSearch();
        });
        box.field().onKeyDown.attachListener((element, event) -> {
            if (!CgModifiers.hasAlt(event.getModifiers()) || event.getKeyCode() != key) return;
            toggle.onPressed.emit();
            event.stopPropagation();
        }, false, true);
        box.addOption(toggle);
    }

    // ── Driving the editor ──────────────────────────────────────────────────────────────────────

    private void runSearch() {
        editor.find(SearchQuery.of(findBox.getText(), options));
        editor.findNext();
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
        count.setText(matches == 0 ? "0" : editor.currentMatchNumber() + "/" + matches);
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
        focus(findBox.field());
    }

    /** Shows the bar with the replace row expanded — Ctrl+R. */
    public void openReplace() {
        open = true;
        setDisplayed(true);
        setReplaceShown(true);
        focus(findBox.field());
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
        StyleGroup.importantPipeline(replaceRow.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        // The glyph is the sheet's business; this only says which state it is in, as a class rather than a
        // pseudo-class -- the engine does not re-evaluate one flipped from a listener.
        if (shown) chevron.addClass("__expanded__");
        else chevron.removeClass("__expanded__");
    }

    /**
     * Keeps the editor's text clear of the bar.
     *
     * <p>The bar floats — that is what keeps it out of the editor's layout sums — so nothing would
     * otherwise stop it covering the first lines. IntelliJ's editor starts below its bar, and the cheapest
     * honest way to get that from a floating widget is to inset the thing underneath by exactly what the
     * widget measured. Run from a ticker because the height changes when the replace row appears.</p>
     */
    private void syncEditorInset() {
        float inset = isOpen() ? getRuntimeCache().getHeight() : 0f;
        if (Math.abs(inset - appliedInset) < 0.5f) return;
        appliedInset = inset;
        StyleGroup.importantPipeline(editor.getStyle().getLayoutGroup(), l -> l.paddingTop(inset));
    }

    private float appliedInset = -1f;

    /**
     * Registers the inset ticker once the bar is in a window.
     *
     * <p>From a TICKER and not from here: {@link #syncEditorInset} writes a style, and a structural write
     * inside the layout pass is the one thing {@code onLayoutChanged} must not do. The navigator's sidebar
     * fitting carries the same note.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking || getAttachedWindow() == null) return;
        ticking = true;
        getAttachedWindow().registerTicker(delta -> {
            syncEditorInset();
            return true;
        });
    }

    private boolean ticking;

    private void focus(TextField field) {
        UIWindow window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestPointerFocus(field);
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────

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
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
