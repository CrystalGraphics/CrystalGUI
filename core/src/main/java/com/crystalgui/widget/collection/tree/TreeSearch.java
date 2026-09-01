package com.crystalgui.widget.collection.tree;

import com.crystalgui.workbench.chrome.palette.QuickPick;
import com.crystalgui.workbench.chrome.preferences.NavigatorView;
import com.crystalgui.workbench.chrome.preferences.Preferences;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;
import com.crystalgui.core.collection.tree.FilteredTreeSource;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.text.diagnostic.ProblemNode;
import com.crystalgui.widget.overlay.ContextMenu;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Collections;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.widget.form.SearchField;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Search over any {@link TreeView} — the bar, the two modes, match navigation and the per-row marking.
 *
 * <h3>Generic because both references made it generic</h3>
 *
 * <p>VS Code puts {@code FindWidget}, {@code FindController} and {@code TreeFindMode} in
 * {@code abstractTree.ts}: the tree owns "all UI rendering, mode management, and keyboard bindings for
 * search interaction itself", and a client supplies one thing that matters —
 * {@code IKeyboardNavigationLabelProvider.getKeyboardNavigationLabel(element)}, how to get searchable
 * text out of a node. IntelliJ says the same with a different verb: {@code SpeedSearchBase} is
 * <em>installed onto</em> a {@code JTree}/{@code JList}/{@code JTable} and parameterised by a converter
 * from element to text.</p>
 *
 * <p>It was written inside the file explorer first, which was the right order — the shape was not obvious
 * until it existed — and it is here now because there are four {@link TreeView} consumers and it was
 * serving one.</p>
 *
 * <h3>Installed, not inherited</h3>
 *
 * <p>{@link #installOn} rather than fields on {@code TreeView}, which is IntelliJ's boundary and matches
 * {@code ContextMenu.attach} — this codebase's existing shape for a behaviour a widget opts into. A node
 * creation menu should not carry a bar element and six fields for a feature it never shows. VS Code
 * reaches the same place through options: find is opt-in there too, and without a label provider it does
 * not initialise at all.</p>
 *
 * <h3>What the client still owns</h3>
 *
 * <p>{@link Model} — because "does this match" is domain knowledge, which is the same argument
 * {@link FilteredTreeSource} already makes about its predicate. {@link #byText} builds the common case
 * from one lambda; a source that already knows how to narrow itself supplies its own.</p>
 *
 * @param <T> the tree's item type
 */
public final class TreeSearch<T> {

    /** On the bar. */
    public static final String BAR_CLASS = "__find-bar__";

    /** On the search box itself — a real input, so it can be clicked into and take Ctrl+A. */
    public static final String INPUT_CLASS = "__find-input__";

    /** On the positional readout. */
    public static final String COUNT_CLASS = "__find-count__";

    /** On the Filter/Highlight button. */
    public static final String MODE_CLASS = "__find-mode__";

    /** The dismiss button at the far right — {@code .__find-close__}. */
    public static final String CLOSE_CLASS = "__find-close__";

    /** On each option toggle inside the box — Cc, W, .* */
    public static final String OPTION_CLASS = "__search-option__";

    /** On a toggle that is on. @see #addOption */
    public static final String OPTION_ON_CLASS = "__on__";

    /** On the count while a non-empty query matches nothing. @see SearchField#NOT_FOUND_CLASS */
    public static final String NOT_FOUND_CLASS = SearchField.NOT_FOUND_CLASS;

    /** On a match arrow with nothing to step through. @see #apply */
    public static final String OFF_CLASS = "__off__";

    /** The previous / next match buttons, beside the count. */
    public static final String PREV_CLASS = "__find-prev__";
    public static final String NEXT_CLASS = "__find-next__";

    /**
     * On the HOST while the bar is showing.
     *
     * <p>So a panel can style around a bar it does not own. The Problems panel's empty-state label is
     * {@code position: absolute; top: 0} — correct on its own, and drawn straight over the bar the moment
     * one appeared. Nothing in CSS can ask "is there a visible sibling above me", so the component says
     * so instead.</p>
     */
    public static final String SEARCHING_CLASS = "__searching__";

    /** On a row whose own name matches, in Highlight mode. */
    public static final String MATCH_CLASS = "__match__";

    /** On a row that matches nothing and contains nothing that does. */
    public static final String DIMMED_CLASS = "__dimmed__";

    /**
     * The {@code ::highlight()} name the matched characters carry.
     *
     * <p>A highlight rather than a class, because what is styled is a <b>range inside a string</b> and not
     * an element — the whole reason the CSS Custom Highlight API exists. Wrapping the matched letters in
     * spans would put a real Taffy node around three characters of every row.</p>
     */
    public static final String HIGHLIGHT = "find-match";

    /** Filter removes non-matching rows; Highlight keeps them and marks the matches. */
    public enum Mode {
        HIGHLIGHT,
        FILTER
    }

    /**
     * What a search needs to know about the items it is searching.
     *
     * <p>VS Code's {@code IKeyboardNavigationLabelProvider} plus the half its {@code IAsyncFindProvider}
     * adds. Kept small on purpose: everything above it — the bar, the modes, the arrows, the counter — is
     * the same for every tree, and only these four answers are not.</p>
     */
    public interface Model<T> {

        /**
         * Narrows the tree, or stops narrowing it.
         *
         * <p>Called on every keystroke and on every mode change. A model that only highlights ignores
         * {@code filtering} and does nothing here beyond remembering the query.</p>
         */
        /**
         * The query, <b>with its options</b>, and whether the tree should be narrowed to it.
         *
         * <p>A {@link SearchQuery} rather than a {@code String}, because the options are the whole point:
         * handed the raw text, a model builds its own query and silently drops Match Case, Words and Regex
         * -- which is exactly what happened, and why `GRAPH` with both toggles lit still matched
         * `shadergraph`. The type that carries them is the type the matcher takes.</p>
         */
        void setQuery(SearchQuery query, boolean filtering);

        /** Whether {@code item}'s own text matches — not whether something beneath it does. */
        boolean isMatch(T item);

        /** Where in {@code item}'s text it matched, for the band. Empty when it did not. */
        List<SearchMatch.Range> matchRanges(T item);

        /**
         * How many matches sit beneath {@code item}, for a badge on a collapsed branch — {@code 0} for a
         * tree that does not want one.
         *
         * <p>Not derived generically, and deliberately: walking {@code TreeDataSource.children} to count
         * them would <b>request every unlisted directory</b> on a lazily-loaded tree, turning a keystroke
         * into a listing storm. Only the model knows what it can answer for free.</p>
         */
        int descendantMatches(T item);
    }

    /**
     * The common case: match an item by one string, filter with {@link FilteredTreeSource}.
     *
     * <p>{@code TreeSearch.byText(ProblemNode::message)} is the whole of what a tree needs to gain
     * search. Matching is {@link SearchMatcher}'s, already ported from VS Code's {@code filters.ts}, so
     * every tree ranks the way the command palette does rather than inventing a second idea of what
     * "matches" means.</p>
     */
    /**
     * Highlight only — IntelliJ's speed search exactly, which navigates and marks and never filters.
     *
     * <p>For a tree whose source narrows itself (or does not narrow at all). Filter mode still toggles;
     * it simply has nothing to narrow, so the two modes look the same. A tree that wants filtering passes
     * its {@link FilteredTreeSource} to the other overload.</p>
     */
    public static <T> Model<T> byText(Function<T, String> textOf) {
        return byText(textOf, null);
    }

    public static <T> Model<T> byText(Function<T, String> textOf,
                                      @Nullable FilteredTreeSource<T> filtered) {
        return new Model<>() {
            @Nullable
            private SearchQuery query;

            @Override
            public void setQuery(SearchQuery next, boolean filtering) {
                query = next == null || next.isEmpty() ? null : next;
                // The predicate is set even in Highlight mode, and then not used: FilteredTreeSource
                // delegates entirely with no filter, so clearing it is what turns filtering off.
                if (filtered == null) return;
                filtered.setFilter(query == null || !filtering ? null
                        : item -> SearchMatcher.match(query, textOf.apply(item), 0) != null);
            }

            @Override
            public boolean isMatch(T item) {
                return query != null && SearchMatcher.match(query, textOf.apply(item), 0) != null;
            }

            @Override
            public List<SearchMatch.Range> matchRanges(T item) {
                if (query == null) return List.of();
                SearchMatch match = SearchMatcher.match(query, textOf.apply(item), 0);
                return match == null ? List.of() : match.ranges();
            }

            @Override
            public int descendantMatches(T item) {
                return 0;
            }
        };
    }

    // ── Installation ────────────────────────────────────────────────────────────────────────────

    private final TreeView<T> tree;
    private final Model<T> model;
    private final Consumer<T> onActivate;

    private final UINode bar = new UINode();

    /** Where the bar was added, so {@link #apply} can mark it. */
    @Nullable
    private UINode host;
    /**
     * The box, with the toggles inside its border.
     *
     * <p>A {@link SearchField} rather than a bare {@code TextField}, which is what this used to be. The
     * distinction is not decoration: IntelliJ's focus ring encloses the magnifier, the text, the clear
     * button and the option toggles as ONE control, with the text occupying only what is left between
     * them. A strip of buttons mounted beside a plain field looks the same until something resizes.</p>
     */
    private final SearchField box = new SearchField();

    private final TextField input = box.field();

    /** Cc / W / .* — the state the matcher reads. @see SearchQuery.Options */
    private SearchQuery.Options searchOptions = SearchQuery.Options.DEFAULT;

    /** Fires when a toggle changes, so a host can re-run whatever it matched with. */
    public final Signal.Value<SearchQuery.Options> onOptionsChanged = new Signal.Value<>();
    private final UIText count = new UIText("");
    /**
     * Highlight vs Filter, as a glyph.
     *
     * <p>It read "Highlight" / "Filter" in words, which is clearer in isolation and cost about fifty pixels
     * of a bar that is as narrow as its panel — so in the explorer the box sat pinned at its minimum with
     * the count truncated to an ellipsis, and no width was left for the thing everything else exists to
     * serve. IntelliJ spends that space on the funnel glyph for the same reason.</p>
     */
    private final Button modeButton = new Button("");

    /** Previous / next match — the ↑ ↓ pair, beside the count that says which one you are on. */
    private final Button prevButton = new Button("");
    private final Button nextButton = new Button("");

    /**
     * DISMISS, and the only affordance for it that does not require knowing Escape.
     *
     * <p>The bar closes when dismissed and never because its query happens to be empty, so without a
     * visible way out the only exit is a key nobody is told about. IntelliJ's find toolbar and VS Code's
     * find widget both put an X at the far right; it is the one control in the bar that belongs there,
     * which is what freed the count and the mode button to sit beside the field where they are read.</p>
     */
    private final Button closeButton = new Button("");

    /**
     * Whether the bar comes and goes, or is simply part of the panel.
     *
     * <p>Both references ship both, and the difference is not cosmetic. VS Code's tree find widget and
     * IntelliJ's find toolbar are <b>transient</b> — summoned with Ctrl+F, dismissed with the X or Escape,
     * and gone. A settings window's search box is <b>permanent</b>: it is the first thing in the sidebar,
     * it is how you are expected to start, and dismissing it would leave a panel whose main affordance had
     * vanished with no visible way back.</p>
     *
     * <p>So a permanent bar has no close button, cannot be dismissed, and reads Escape as "clear what I
     * typed" rather than "put this away" — which is what IntelliJ's settings search does with it.</p>
     */
    public enum Presentation {
        /** Summoned and dismissed. The default, and what a tree in a panel wants. */
        TRANSIENT,
        /** Always there. What a search-first panel — a settings window, a picker — wants. */
        PERMANENT
    }

    /**
     * A control the bar may show beside the box.
     *
     * <p>Declared rather than hidden in CSS, which is where these started. A sheet can only say "not here",
     * and it says it from a selector that has to know the host's tag — so `projectfiletree .__find-prev__`
     * and `navigatorview .__nav-search__ .__find-mode__` were two hosts reaching into a component's parts
     * by name. This is the same decision made where it belongs: the host owns which controls its search
     * has, the sheet owns what they look like.</p>
     *
     * <p>The box's own contents are not here. The magnifier, the clear button and the Cc/W/.* toggles are
     * inside the field's border and are what the search <em>is</em>; these four are furniture around it.</p>
     */
    public enum Control {
        /** The {@code Cc} toggle. */
        MATCH_CASE,
        /** The {@code W} toggle. */
        WORDS,
        /** The {@code .*} toggle. */
        REGEX,
        /** The previous / next match arrows. */
        ARROWS,
        /** The match count — {@code 1/4}. */
        COUNT,
        /** The dismiss button. Never shown on a {@linkplain Presentation#PERMANENT permanent} bar. */
        CLOSE
    }

    /**
     * The Highlight/Filter toggle is deliberately NOT a {@link Control}.
     *
     * <p>It is the one control here that changes what the search <em>does</em> rather than how precisely it
     * matches, so a host that keeps a search at all generally wants it. Where it is not wanted — the
     * settings sidebar, which always filters — the sheet already declines it, and that is the right level
     * for a decision about a control the widget still fully supports.</p>
     */
    private static final String MODE_IS_NOT_A_CONTROL = "";

    private final EnumSet<Control> controls = EnumSet.allOf(Control.class);

    /** The three option toggles, so {@link #setControls} can reach the ones inside the box. */
    private final EnumMap<Control, Button> optionButtons = new EnumMap<>(Control.class);

    private Presentation presentation = Presentation.TRANSIENT;

    /**
     * Whether Up/Down in the box step between matches.
     *
     * <p>On by default, and off for a host that navigates its own rows. {@link NavigatorView} is the case:
     * its arrows walk the visible tree and <em>open the page</em> for whatever they land on, with or
     * without a query, so match-stepping would both fight it and go dead the moment the box was empty.
     * Filtering already narrows the rows there, which makes "arrow through what is left" the same gesture
     * with a better answer.</p>
     */
    private boolean arrowNavigation = true;

    private Mode mode = Mode.HIGHLIGHT;
    private String query = "";
    private boolean open;

    /** True while {@link #apply} writes the field, so its own listener is ignored. */
    private boolean writingBack;

    /**
     * Which match is current, as an index into {@link #matchRows()} — or {@code -1}.
     *
     * <p>A position in the match list rather than a row index, because rows come and go: a listing
     * arriving or a fold renumbers everything under it, and "the third match" survives that where
     * "row 17" does not.</p>
     */
    private int currentMatch = -1;

    private TreeSearch(TreeView<T> tree, Model<T> model, Consumer<T> onActivate) {
        this.tree = tree;
        this.model = model;
        this.onActivate = onActivate;
    }

    /**
     * Adds the bar to {@code host} and wires the keys.
     *
     * @param host       where the bar goes — <b>added first</b>, so it sits above the rows. Appending put
     *                   it below the list and overlapping its last row; VS Code's find widget is at the
     *                   top of the view and has nothing to cover there
     * @param onActivate what Enter does with the current match
     */
    public static <T> TreeSearch<T> installOn(TreeView<T> tree, UINode host, Model<T> model,
                                              Consumer<T> onActivate) {
        return installOn(tree, host, 0, model, onActivate);
    }

    /**
     * As above, with the bar inserted at {@code index} of {@code host}.
     *
     * <p>Because "first child" is not always "above the rows". The Problems panel stacks its table and its
     * empty state as <b>out-of-flow</b> elements in one box — deliberately, so neither one's arrival
     * resizes the panel — and an in-flow bar dropped in beside them shares their y and is drawn straight
     * through by the first row. Its bar goes in the panel's own column instead, which is a decision only
     * the host can make.</p>
     */
    public static <T> TreeSearch<T> installOn(TreeView<T> tree, UINode host, int index, Model<T> model,
                                              Consumer<T> onActivate) {
        TreeSearch<T> search = new TreeSearch<>(tree, model, onActivate);
        search.build(host, index);
        return search;
    }

    private void build(UINode host, int index) {
        // WRAPPED AT INSTALL, so a renderer set afterwards would lose the marking. Both consumers build
        // their renderer with the tree and install onto it, which is the natural order -- a component
        // cannot decorate something that does not exist yet.
        TreeRenderer<T> existing = tree.getTreeRenderer();
        if (existing != null && !(existing instanceof TreeSearch<?>.MarkingRenderer)) {
            tree.setRenderer(new MarkingRenderer(existing));
        }

        bar.addClass(BAR_CLASS);
        // ON THE BOX, not on the TextField inside it. The class names "the search input of a find bar",
        // and that is now a bordered control containing the text rather than the text itself -- left on the
        // inner field its `min-width: 60px` and `max-width: 320px` fought the box's own layout from one
        // level down and squeezed the text to nothing. Same shape as the navigator's `__nav-search__`.
        box.addClass(INPUT_CLASS);
        input.setPlaceholder("Search");
        // IMMEDIATE, because a search that waits for Enter is a filter you cannot feel. ON_COMMIT is the
        // right default for a form field and the wrong one for this.
        input.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        input.onKeyDown.attachListener((element, event) -> {
            // THE FIELD OWNS EVERY CARET KEY. Left, Right, Home and End all move a caret in a real text
            // field, so navigation takes only the pair that means nothing in a single line -- Up and
            // Down -- plus Enter.
            //
            // Ctrl+Home/Ctrl+End were tried for first/last match and removed: TextField consumes Home and
            // End for the caret whether or not Ctrl is held, so the binding was dead on arrival.
            boolean handled = switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_DOWN -> arrowNavigation && moveToMatch(1);
                case CgKeyCodes.KEY_UP -> arrowNavigation && moveToMatch(-1);
                case CgKeyCodes.KEY_RETURN -> {
                    activateCurrent();
                    yield true;
                }
                case CgKeyCodes.KEY_ESCAPE -> {
                    // ESCAPE IS A CASCADE, and this box is only its first step. It closes a transient bar
                    // and clears a permanent one -- but once there is nothing left for it to do here, it
                    // must NOT be consumed, or whatever is outside can never be closed. The settings dialog
                    // is the case: its search is permanent and always focused, so it swallowed every
                    // Escape and the dialog could not be dismissed from the keyboard at all.
                    //
                    // The same rule the engine already applies at the top: a drag eats Escape before a
                    // close watcher, and a nested popover before the modal behind it.
                    boolean anythingToDo = !query.isEmpty()
                            || (open && presentation != Presentation.PERMANENT);
                    if (anythingToDo) close();
                    yield anythingToDo;
                }
                default -> false;
            };
            if (handled) event.stopPropagation();
        }, false, true);
        input.attachListener(typed -> {
            // GUARDED, because apply() writes the field back: without this, setQuery -> apply -> setText
            // -> this listener -> setQuery is a loop that only stops because Property.set drops a
            // re-entrant set, which is a mechanism to depend on rather than a design.
            if (writingBack) return;
            setQuery(typed);
        });
        count.addClass(COUNT_CLASS);
        count.setHitTest(false);
        modeButton.addClass(MODE_CLASS);
        modeButton.onPressed.connect(this::toggleMode);
        // THE TOGGLES GO IN THE BOX, not in the bar -- see the field's note. Mounted here rather than by
        // each host, because they are the component's own state: a host that wants fewer declines them in
        // CSS, the way the navigator already declines the mode button and the count.
        addOption(Control.MATCH_CASE, "__option-match-case__", "Match Case", "Alt+C", CgKeyCodes.KEY_C,
                () -> searchOptions.matchCase(), v -> searchOptions = searchOptions.withMatchCase(v));
        addOption(Control.WORDS, "__option-words__", "Words", "Alt+W", CgKeyCodes.KEY_W,
                () -> searchOptions.wholeWords(), v -> searchOptions = searchOptions.withWholeWords(v));
        addOption(Control.REGEX, "__option-regex__", "Regex", "Alt+X", CgKeyCodes.KEY_X,
                () -> searchOptions.regex(), v -> searchOptions = searchOptions.withRegex(v));
        bar.append(box);
        bar.append(count);
        closeButton.addClass(CLOSE_CLASS);
        closeButton.onPressed.connect(this::close);
        Tooltip.attach(prevButton, "Previous Match  Shift+Enter");
        Tooltip.attach(nextButton, "Next Match  Enter");
        Tooltip.attach(modeButton, "Filter Search Results");
        Tooltip.attach(closeButton, "Close  Escape");
        prevButton.addClass(PREV_CLASS);
        prevButton.onPressed.connect(() -> moveToMatch(-1));
        nextButton.addClass(NEXT_CLASS);
        nextButton.onPressed.connect(() -> moveToMatch(1));
        bar.append(prevButton);
        bar.append(nextButton);
        bar.append(modeButton);
        bar.append(closeButton);
        refreshControls();
        this.host = host;
        // INTERNAL, because the bar IS chrome of the host rather than content somebody put there: it is
        // skipped by public traversal and by UIDescriptionCodec, which is what a widget's own parts are.
        // It is also the only call that works on a composite -- ProblemsPanel refuses public children,
        // as every composite here does, so addChildAt threw the moment the second consumer existed.
        host.insertAt(Math.max(0, Math.min(index, host.children().size())), bar);
        installTypeAhead();
        apply();
    }

    // ── The query ───────────────────────────────────────────────────────────────────────────────

    /**
     * Sets what is being searched for, by whatever route.
     *
     * <p><b>One entry point</b>, because there are three ways in — the box, type-ahead in the tree, and a
     * caller setting it outright — and jumping to the first match from only one of them made the same
     * keystroke behave differently depending on where the caret was.</p>
     */
    public void setQuery(String text) {
        this.query = text == null ? "" : text;
        model.setQuery(SearchQuery.of(query, searchOptions), mode == Mode.FILTER);
        tree.refresh();
        revealForFilter();
        apply();
        moveToFirstMatch();
    }

    /**
     * Makes the bar permanent — always open, no close button, Escape clears instead of dismissing.
     *
     * <p>Call before anything shows it. It opens the bar immediately, since a permanent bar that had to be
     * opened once would be transient with extra steps.</p>
     */
    public TreeSearch<T> setPresentation(Presentation next) {
        if (next == null || next == presentation) return this;
        presentation = next;
        refreshControls();
        if (next == Presentation.PERMANENT) {
            open = true;
            apply();
        }
        return this;
    }

    /**
     * Which controls the bar shows. Everything, unless a host says otherwise.
     *
     * <p>Call with no arguments for a bare box — the settings sidebar wants exactly that, since its tree is
     * the answer and there is nothing to step through or dismiss.</p>
     */
    public TreeSearch<T> setControls(Control... shown) {
        controls.clear();
        if (shown != null) Collections.addAll(controls, shown);
        refreshControls();
        return this;
    }

    public Set<Control> controls() {
        return Collections.unmodifiableSet(controls);
    }

    private void refreshControls() {
        show(count, Control.COUNT);
        show(prevButton, Control.ARROWS);
        show(nextButton, Control.ARROWS);
        optionButtons.forEach((control, button) -> show(button, control));
        // A permanent bar cannot be dismissed, so its close button is never shown whatever is asked for --
        // one refusal, in the same place the hide is written, rather than a rule every caller must know.
        boolean closeable = controls.contains(Control.CLOSE)
                && presentation != Presentation.PERMANENT;
        StyleGroup.inlinePipeline(closeButton.getStyle().getLayoutGroup(),
                l -> l.display(closeable ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    private void show(UINode element, Control control) {
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.display(controls.contains(control) ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    public Presentation presentation() {
        return presentation;
    }

    /** @see #arrowNavigation */
    public TreeSearch<T> setArrowNavigation(boolean enabled) {
        this.arrowNavigation = enabled;
        return this;
    }

    public String query() {
        return query;
    }

    public Mode mode() {
        return mode;
    }

    /** Filter ⇄ Highlight. */
    public void toggleMode() {
        setMode(mode == Mode.HIGHLIGHT ? Mode.FILTER : Mode.HIGHLIGHT);
    }

    public void setMode(Mode next) {
        if (next == null || next == mode) return;
        mode = next;
        model.setQuery(SearchQuery.of(query, searchOptions), mode == Mode.FILTER);
        tree.refresh();
        revealForFilter();
        apply();
    }

    /**
     * Shows the bar and puts the caret in it — Ctrl+F.
     *
     * <p>Opening with an empty query is the whole point of the flag: a box you can only reach by first
     * typing a character into the tree is not one you can click into.</p>
     */
    public void open() {
        open = true;
        apply();
        focusInput();
    }

    /**
     * Hides it and clears the query — or, when {@linkplain Presentation#PERMANENT permanent}, only clears.
     *
     * <p>Every dismissal route runs through here: the X, Escape, and a host calling it. Refusing the hide
     * in one place is what keeps a permanent bar permanent no matter which one fired.</p>
     */
    public void close() {
        if (presentation == Presentation.PERMANENT) {
            setQuery("");
            focusInput();
            return;
        }
        open = false;
        currentMatch = -1;
        setQuery("");
        apply();
        UIDocument window = tree.document();
        if (window != null && window.focus().focusable(tree)) window.focus().requestPointerFocus(tree);
    }

    public boolean isOpen() {
        return open;
    }

    /** The tree this is installed on. */
    public TreeView<T> tree() {
        return tree;
    }

    /** The box — the bordered control holding the text, the clear button and the toggles. */
    public SearchField box() {
        return box;
    }

    /** The bar itself, for a host that wants to name or place it. */
    public UINode bar() {
        return bar;
    }

    /**
     * Sets the options a host wants, and re-runs the query against them.
     *
     * <p>The toggles are the user's way in; this is the programmatic one, and the only way a test can
     * assert that an option reaches the matching.</p>
     */
    public TreeSearch<T> setSearchOptions(SearchQuery.Options next) {
        searchOptions = next == null ? SearchQuery.Options.DEFAULT : next;
        onOptionsChanged.emit(searchOptions);
        setQuery(query);
        apply();
        return this;
    }

    /** The options the matcher should honour — Cc / W / .* as the user has them. */
    public SearchQuery.Options searchOptions() {
        return searchOptions;
    }

    /**
     * One toggle in the box.
     *
     * <p>A {@code Button} overriding {@code isChecked()}, which is how every stateful control here says it
     * is on: the pseudo-class comes for free and the sheet draws the state. @see PseudoClasses</p>
     */
    private void addOption(Control control, String styleClass, String title, String accelerator, int key,
                           BooleanSupplier get,
                           Consumer<Boolean> set) {
        Button option = new Button("");
        // NAMED, because three glyphs reading Cc / W / .* are only obvious to somebody who already knows
        // them. IntelliJ's own tooltip is the title, the accelerator, and one line of instruction -- and
        // the accelerator is BOUND below rather than merely advertised: a tooltip naming a shortcut that
        // does nothing is worse than no tooltip.
        Tooltip.attach(option, title + "  " + accelerator);
        option.addClass(OPTION_CLASS);
        option.addClass(styleClass);
        option.onPressed.connect(() -> {
            boolean next = !get.getAsBoolean();
            set.accept(next);
            // AN EXPLICIT CLASS, not an isChecked() override. `:checked` is re-evaluated from the dirty-match
            // set, and invalidateStyleMatch is protected -- so a toggle flipped from a listener would be
            // drawn in the state it had just left until something else happened to dirty it. A class change
            // is what the engine is guaranteed to notice.
            if (next) option.addClass(OPTION_ON_CLASS);
            else option.removeClass(OPTION_ON_CLASS);
            onOptionsChanged.emit(searchOptions);
            setQuery(query);
            apply();
        });
        optionButtons.put(control, option);
        box.addOption(option);

        // ALT+C / ALT+W / ALT+X, on the box rather than in the keymap. The same reasoning that put Ctrl+F
        // on the tree: these must not be taken from the rest of the application, and they only mean
        // anything while there is a query to apply them to.
        input.onKeyDown.attachListener((element, event) -> {
            if (!CgModifiers.hasAlt(event.getModifiers()) || event.getKeyCode() != key) return;
            option.onPressed.emit();
            event.stopPropagation();
        }, false, true);
    }

    /** The search box, for a host that wants to style or focus it. */
    public TextField input() {
        return input;
    }

    private void focusInput() {
        UIDocument window = input.document();
        if (window == null) return;
        window.focus().requestPointerFocus(input);
        input.setSelection(input.getText().length(), input.getText().length());
    }

    /**
     * Shows or hides the bar, and writes what it says.
     *
     * <p><b>Open until dismissed</b>, not until the query is empty. Backspacing the last character used to
     * hide the box out from under the caret, so a query could never be cleared and retyped.</p>
     */
    public void apply() {
        boolean searching = open || !query.isEmpty();
        StyleGroup.inlinePipeline(bar.getStyle().getLayoutGroup(),
                l -> l.display(searching ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        if (host != null) {
            if (searching) host.addClass(SEARCHING_CLASS);
            else host.removeClass(SEARCHING_CLASS);
        }
        if (!searching) return;
        writingBack = true;
        // Only when it differs: assigning the same string still moves the caret to the end, so typing in
        // the middle of a query would jump to the end on every keystroke.
        if (!input.getText().equals(query)) input.setText(query);
        writingBack = false;
        // A GLYPH WITH A STATE, not a label that says which state it is in. Same idiom as the option
        // toggles beside it, and the same reason: `:checked` is not re-evaluated from a listener.
        if (mode == Mode.FILTER) modeButton.addClass(OPTION_ON_CLASS);
        else modeButton.removeClass(OPTION_ON_CLASS);

        int matches = matchRows().size();
        // POSITIONAL, not merely a count. Navigation you cannot see the position of is navigation you
        // cannot tell you are doing.
        //
        // WHAT IS ON SCREEN, not what the model holds: a lazily-listed tree would otherwise report a
        // number about the parts that happen to have been opened, which reads as a search result and is
        // not one.
        // TERSE, because this bar lives in a sidebar. "no matches here" and "1 of 4" are better English
        // and they are eleven and six characters of a row that is already overfull at 187px -- the box
        // was shrinking to its floor to pay for them, which trades the control everything else exists to
        // serve for a sentence. IntelliJ can afford "0 results" because its bar spans an editor.
        if (matches == 0) count.setText("0");
        else if (currentMatch < 0) count.setText(String.valueOf(matches));
        else count.setText((currentMatch + 1) + "/" + matches);

        // NOTHING FOUND, said three ways at once because they are one fact: the query reds, the count reds,
        // and the two arrows go dead. `SearchField.setNotFound` has existed since the box was built and
        // nothing had ever called it -- a state the widget could render and no one ever put it in.
        //
        // An UNCOMPILABLE PATTERN counts as nothing found, deliberately: to somebody typing `(unclosed` the
        // answer is the same, and a second colour for it would be a distinction only the implementer cares
        // about. SearchQuery.isInvalidPattern is why the box can say so at all.
        boolean nothing = !query.isEmpty() && matches == 0;
        box.setNotFound(nothing);
        if (nothing) count.addClass(NOT_FOUND_CLASS);
        else count.removeClass(NOT_FOUND_CLASS);

        // DISABLED, not hidden. An arrow that vanishes when a query stops matching moves everything beside
        // it on every keystroke; greyed, it stays where the hand expects it and says why it does nothing.
        boolean canStep = matches > 0;
        prevButton.setEnabled(canStep);
        nextButton.setEnabled(canStep);
        // AND UNHITTABLE, which is what actually makes it look dead. `:disabled` and `:hover` tie on
        // specificity and a `:disabled:hover` compound does not match here, so a disabled arrow kept
        // lighting up under the pointer -- and kept showing its tooltip, which is worse: a dead control
        // explaining what it would have done. Taking it out of hit testing means `:hover` can never
        // match it and the tooltip never opens, which is what a disabled control should do anyway.
        prevButton.setHitTest(canStep);
        nextButton.setHitTest(canStep);
        // AND AN EXPLICIT CLASS for the colour, rather than leaning on `:disabled`. Same answer as the
        // option toggles and for the same reason: a pseudo-class here is re-evaluated on the engine's
        // terms and a class is re-evaluated on ours. `:disabled` was styled correctly and still lost.
        for (Button arrow : new Button[]{prevButton, nextButton}) {
            if (canStep) arrow.removeClass(OFF_CLASS);
            else arrow.addClass(OFF_CLASS);
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /**
     * The visible rows whose own text matches, in tree order.
     *
     * <p><b>Own text</b>, so a branch that merely contains a match is not a stop. You can already see it
     * — its badge says how many are inside — and stopping there would put a deep hit several presses
     * away. The same rule in both modes: filtering keeps such branches too, so "every visible row is a
     * match" was never true even there.</p>
     */
    private List<Integer> matchRows() {
        List<Integer> rows = new ArrayList<>();
        if (query.isEmpty()) return rows;
        List<TreeRow<T>> visible = tree.visibleRows();
        for (int i = 0; i < visible.size(); i++) {
            if (model.isMatch(visible.get(i).item())) rows.add(i);
        }
        return rows;
    }

    /**
     * Steps to the next or previous match, wrapping.
     *
     * <p><b>Selects and scrolls; never focuses.</b> Focus stays in the search box for the whole gesture —
     * the ARIA combobox pattern, which {@code ListView.restoreFocusIfRealised} names and
     * {@code QuickPick} implements: the field owns the caret and the arrows, the list is a <em>view</em>
     * of the selection. Focusing the row would take the caret out of the box on the first press.</p>
     */
    public boolean moveToMatch(int delta) {
        List<Integer> rows = matchRows();
        if (rows.isEmpty()) {
            currentMatch = -1;
            apply();
            return false;
        }
        // Wraps at both ends, like Menu's Up/Down and like Tab: a search that dead-ends at the bottom
        // makes you retype to get back to the top.
        currentMatch = currentMatch < 0
                ? (delta > 0 ? 0 : rows.size() - 1)
                : Math.floorMod(currentMatch + delta, rows.size());
        selectCurrent(rows);
        return true;
    }

    /** Jumps to the first match — what typing does, so a query answers itself before you stop typing. */
    public void moveToFirstMatch() {
        List<Integer> rows = matchRows();
        currentMatch = rows.isEmpty() ? -1 : 0;
        if (!rows.isEmpty()) selectCurrent(rows);
        apply();
    }

    private void selectCurrent(List<Integer> rows) {
        int row = rows.get(currentMatch);
        tree.select(row);
        tree.scrollToIndex(row);
        apply();
    }

    private void activateCurrent() {
        T item = currentMatchItem();
        if (item != null) onActivate.accept(item);
    }

    /** The match the arrows are on, or null. */
    @Nullable
    public T currentMatchItem() {
        List<Integer> rows = matchRows();
        if (currentMatch < 0 || currentMatch >= rows.size()) return null;
        List<TreeRow<T>> visible = tree.visibleRows();
        int row = rows.get(currentMatch);
        return row < visible.size() ? visible.get(row).item() : null;
    }

    public int matchCount() {
        return matchRows().size();
    }

    public int currentMatchIndex() {
        return currentMatch;
    }

    // ── Per-row marking ─────────────────────────────────────────────────────────────────────────

    /**
     * Marks one row — called from the client's renderer during {@code bind}.
     *
     * <p>The client calls in rather than this reaching for the row's parts, because only the renderer
     * knows which element carries the label. VS Code's does the same: its renderer asks the filter for
     * the match data and applies it to the label it built.</p>
     *
     * <p>Three states rather than two, and the third is what makes Highlight usable: a row that matches
     * is <b>marked</b>, a row on the way to one is left alone so the path stays readable, and a row that
     * is neither is <b>dimmed</b>.</p>
     *
     * <p>Everything is written <b>unconditionally</b>, including to nothing. A template is a different
     * item every time the view recycles it, so leaving the previous occupant's ranges would band whatever
     * letters happen to sit at those offsets in text that never matched.</p>
     *
     * @param badge optional element carrying the descendant count; null for a tree without one
     */
    public void markRow(UINode row, UIText label, @Nullable UIText badge, T item, boolean expandable) {
        markedThisBind = true;
        // MARKED IN BOTH MODES. Filtering narrows the list; it does not answer "where in this row".
        // VS Code highlights matched characters in its filter mode too, and leaving them unmarked here
        // meant the amber band vanished the moment the mode button was pressed -- which reads as the
        // marking being broken rather than as a mode difference.
        boolean searching = !query.isEmpty();
        boolean match = searching && model.isMatch(item);
        int beneath = searching && expandable ? model.descendantMatches(item) : 0;

        // MATCH AND DIMMED ARE BOTH HIGHLIGHT-ONLY, and they are one rule rather than two: Highlight mode
        // paints a THREE-STATE answer over a complete tree -- match white, ordinary grey, irrelevant dimmed
        // -- because nothing has been taken away and colour is the only thing that can say where to look.
        //
        // Filter mode has already said it by narrowing. Brightening what survived is as redundant as
        // dimming it, and it does not read as redundancy: the sidebar was #CCCCCC with no query and white
        // with one, so typing appeared to change the font of the whole panel. Worse in the navigator than
        // anywhere else, because its matcher answers "does anything at or under this path match", which
        // makes nearly every surviving row a match.
        //
        // The amber band stays in both modes. It says WHERE, which narrowing cannot.
        if (mode == Mode.HIGHLIGHT && match) row.addClass(MATCH_CLASS);
        else row.removeClass(MATCH_CLASS);

        List<TextRange> spans = new ArrayList<>();
        if (match) {
            for (SearchMatch.Range range : model.matchRanges(item)) {
                spans.add(TextRange.of(range.start(), range.end()));
            }
        }
        if (spans.isEmpty()) label.highlights().remove(HIGHLIGHT);
        else label.highlights().set(HIGHLIGHT, spans);

        // DIMMED IN HIGHLIGHT MODE ONLY -- the mirror of the reveal rule, and for the same reason.
        // Filtering REMOVES what does not belong; saying it again in grey about what is left is a second,
        // weaker statement that mostly reads as "disabled". It is not even reliably true: a matching node
        // keeps its whole subtree unfiltered (FilteredTreeSource says so outright), so a filtered
        // Preferences tree greyed out `Code Style` purely for sitting under a category that matched --
        // which looked like the font colour changing at random rather than like an answer.
        //
        // In Highlight mode the tree is complete and untouched, and "this row is irrelevant" is the only
        // thing dimming can mean.
        if (mode == Mode.HIGHLIGHT && searching && !match && beneath == 0) row.addClass(DIMMED_CLASS);
        else row.removeClass(DIMMED_CLASS);

        if (badge == null) return;
        if (beneath > 0) badge.setText(String.valueOf(beneath));
        else if (searching) badge.setText("");
    }

    // ── Revealing ───────────────────────────────────────────────────────────────────────────────

    /** The expansion state before filtering opened everything, or null when not filtering. */
    @Nullable
    private List<T> expansionBeforeFilter;

    /** How deep the reveal walk will go, in case a source's children() is cyclic or unbounded. */
    private static final int MAX_REVEAL_DEPTH = 32;

    /**
     * Opens the filtered tree so its matches are on screen, and puts the expansion back afterwards.
     *
     * <h3>Filtering reveals; highlighting does not</h3>
     *
     * <p>Which of the two a tree should do is a question about the <b>mode</b> and not about the panel, and
     * both references draw it here.</p>
     *
     * <p>In <b>Filter</b> mode the tree <em>is</em> the result set: everything left is there because it
     * matched or because it contains something that did. A match sitting inside a collapsed branch is
     * therefore not "hidden", it is <em>missing</em> — the filter did its work and then showed you nothing.
     * That is exactly what Preferences did with {@code gene}: it kept {@code Editor}, because
     * {@code Editor ▸ General} matched, and drew one collapsed row and "no matches here". The count agreed,
     * because the count can only see visible rows. VS Code's tree filter auto-expands for this reason and
     * IntelliJ's settings search does the same.</p>
     *
     * <p>In <b>Highlight</b> mode the tree is untouched by construction — that is the whole distinction —
     * and marking is a statement about what is on screen. Expanding would move rows under the cursor on
     * every keystroke, which is precisely what somebody chose Highlight to avoid. IntelliJ's speed search
     * behaves this way too, and it is why a folder carries a count badge: the badge is what tells you
     * something is inside without opening it.</p>
     *
     * <h3>It is put back</h3>
     *
     * <p>Snapshotted before the first reveal and restored when the query empties or the mode leaves Filter.
     * Without that, one search leaves the tree sprawled open for the rest of the session and the user has
     * to re-fold by hand what they never unfolded. VS Code restores expansion when its filter clears.</p>
     *
     * <p>The walk goes over the <b>filtered</b> source, so it only ever visits surviving branches — and in
     * a lazily-listed tree it only visits what has already been listed, since {@code children()} answers
     * from the listings that have arrived. Neither costs a fetch.</p>
     */
    private void revealForFilter() {
        boolean filtering = mode == Mode.FILTER && !query.isEmpty();
        if (filtering) {
            if (expansionBeforeFilter == null) expansionBeforeFilter = tree.expandedItems();
            List<T> open = new ArrayList<>();
            collectExpandable(tree.getSource().roots(), open, 0);
            tree.setExpandedItems(open);
        } else if (expansionBeforeFilter != null) {
            tree.setExpandedItems(expansionBeforeFilter);
            expansionBeforeFilter = null;
        }
    }

    private void collectExpandable(List<T> items, List<T> into, int depth) {
        if (depth >= MAX_REVEAL_DEPTH) return;
        for (T item : items) {
            if (!tree.getSource().hasChildren(item)) continue;
            into.add(item);
            collectExpandable(tree.getSource().children(item), into, depth + 1);
        }
    }

    // ── Marking ─────────────────────────────────────────────────────────────────────────────────

    /** Set by {@link #markRow}, read by {@link MarkingRenderer} — "the host has this covered". */
    private boolean markedThisBind;

    /**
     * Marks every row the tree binds, unless its renderer already did.
     *
     * <p><b>Because "install it and it works" was not true.</b> Marking lived in the host's renderer —
     * which is where VS Code and IntelliJ both put it, and it is defensible there: a renderer knows which
     * of its elements is the label and which is a badge, and this cannot. But it makes the marking an
     * unwritten second step, and the Problems panel duly installed the component, got a working bar, a
     * working counter and a truthful "1 of 1", and highlighted nothing at all. Nothing failed; the
     * component simply had no way to reach the rows.</p>
     *
     * <p>So the default is automatic and the override is explicit. A renderer that calls
     * {@link #markRow} itself wins for that row — the explorer does, because it has a folder badge to
     * write and a label this could only guess at. One that does not gets the first {@link UIText} in the
     * row, which is the label in every row shape here.</p>
     */
    private final class MarkingRenderer implements TreeRenderer<T> {

        private final TreeRenderer<T> inner;

        MarkingRenderer(TreeRenderer<T> inner) {
            this.inner = inner;
        }

        @Override
        public UINode createTemplate() {
            return inner.createTemplate();
        }

        @Override
        public void bind(T item, TreeRow<T> row, int index, UINode template) {
            markedThisBind = false;
            inner.bind(item, row, index, template);
            if (markedThisBind) return;
            UIText label = firstText(template);
            if (label != null) markRow(template, label, null, item, row.expandable());
        }

        /**
         * Forwarded, like every other method here — a decorator that answers for itself is a decorator
         * that silently replaces the thing it wraps.
         *
         * <p>Missing, the panel's own {@code copyTextFor} existed, was correct, and was never called:
         * Copy fell through to {@code TreeRenderer}'s {@code String.valueOf} default and put a record's
         * generated {@code toString} on the clipboard. Nothing failed anywhere, because installing a
         * search component is not something a Copy has any reason to be aware of.</p>
         */
        @Override
        public String copyTextFor(T item) {
            return inner.copyTextFor(item);
        }

        @Override
        public void unbind(UINode template) {
            inner.unbind(template);
        }
    }

    /** Depth-first, because a label is usually inside a content box rather than a direct child. */
    @Nullable
    private static UIText firstText(UINode element) {
        for (UINode child : element.children()) {
            if (child instanceof UIText text) return text;
            UIText found = firstText(child);
            if (found != null) return found;
        }
        return null;
    }

    // ── Type-ahead ──────────────────────────────────────────────────────────────────────────────

    /**
     * Typing in the tree starts a search — IntelliJ's speed search.
     *
     * <p>Bound directly rather than as commands, and that is the exception rather than a lapse: this is
     * not <em>an</em> action, it is every printable character meaning "narrow to this". A command per
     * letter is not a thing, and a keymap that owned the alphabet would collide with everything else in
     * the panel.</p>
     */
    private void installTypeAhead() {
        tree.onKeyDown.attachListener((element, event) -> {
            // CTRL+F BELONGS TO THE COMPONENT, not to each host. It was bound once, on the explorer's own
            // command, so every other tree that installed this had a search box reachable only by typing
            // into it -- which is exactly the "you can only get there by already being there" problem the
            // bar was added to fix.
            //
            // A direct listener rather than a command, because this is a widget: requiring a
            // CommandRegistry would make a generic tree component depend on the application's command
            // layer to answer one keystroke. Bound on the TREE's element, so it never takes Ctrl+F from
            // an editor elsewhere in the window.
            if (CgModifiers.hasCtrl(event.getModifiers()) && event.getKeyCode() == CgKeyCodes.KEY_F) {
                open();
                event.stopPropagation();
                return;
            }
            if (event.getModifiers() != 0) return;     // Ctrl+C is a command, not a letter
            int key = event.getKeyCode();
            if (key == CgKeyCodes.KEY_ESCAPE) {
                if (!open && query.isEmpty()) return;
                close();
                event.stopPropagation();
                return;
            }
            if (key == CgKeyCodes.KEY_BACK) {
                if (query.isEmpty()) return;
                setQuery(query.substring(0, query.length() - 1));
                event.stopPropagation();
                return;
            }
            char typed = event.getCharacter();
            // Printable only. A tree that searched on Delete would eat the delete key, and the arrows have
            // to keep moving the selection.
            if (typed >= ' ' && typed != 127) {
                open = true;
                setQuery(query + typed);
                // FOCUS FOLLOWS THE FIRST KEYSTROKE, into the field the query lives in. Leaving it on the
                // tree would send the second character through this handler too while a real input sat
                // beside it holding the first -- two places to type one query.
                focusInput();
                event.stopPropagation();
            }
        }, false, true);
    }
}
