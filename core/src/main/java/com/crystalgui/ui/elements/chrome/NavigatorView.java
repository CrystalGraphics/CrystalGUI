package com.crystalgui.ui.elements.chrome;

import com.crystalgui.ui.elements.tree.TreeSearch;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.nav.NavigationHistory;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.tree.*;
import com.crystalgui.ui.event.KeyboardEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Search and a tree on the left, a breadcrumb and a page on the right — the master/detail window.
 *
 * <p>IntelliJ uses this same shell for Settings, Keymap and Inspections; VS Code for its settings editor.
 * It is a shape, not a feature, which is why it is a widget rather than something Preferences owns: a
 * node library, an inspection browser and a keymap editor all want it, and none of them should have to
 * reimplement the tree-filtering or the history to get it.</p>
 *
 * <h3>It composes a {@link SplitView}; it does not extend one</h3>
 *
 * <p>A widget's cascade identity is its <b>tag</b>, so subclassing {@code SplitView} would report
 * {@code navigatorview} and silently lose every {@code splitview} rule in the sheet. That has cost this
 * codebase real time twice already ({@code Preferences extends Dialog},
 * {@code ConfiguratorPanel extends ScrollerView}), so composition here is a rule rather than a
 * preference.</p>
 *
 * <h3>Focus stays in the search field; the arrows are forwarded</h3>
 *
 * <p>The ARIA combobox pattern, and the same arrangement {@code QuickPick} uses: you type into the field
 * and steer the tree with the arrow keys without ever leaving it. That works only because {@code ListView}
 * restores focus rather than taking it — a tree that grabbed focus on selection would unfocus the field on
 * every keystroke, which is exactly the bug the palette had.</p>
 *
 * @param <T> the item type of the tree — a path, an id, a node
 */
public class NavigatorView<T> extends UIElement {

    /** On the search BAR — the box, its count and its mode button. @see TreeSearch */
    public static final String SEARCH_CLASS = "__nav-search__";
    public static final String SIDEBAR_CLASS = "__nav-sidebar__";
    public static final String DETAIL_CLASS = "__nav-detail__";
    public static final String HEADER_CLASS = "__nav-header__";
    public static final String BACK_CLASS = "__nav-back__";
    public static final String FORWARD_CLASS = "__nav-forward__";

    /** On a tree node drawn by the default renderer. */
    public static final String NODE_CLASS = "__nav-node__";

    /** The expander on a node that has children. Empty on a leaf — the sheet draws the glyph. */
    public static final String ARROW_CLASS = "__nav-arrow__";

    /** The node's text. */
    public static final String LABEL_CLASS = "__nav-label__";

    /** Emits whenever the shown item changes, however it was reached. */
    public final Signal.Value<T> onNavigated = new Signal.Value<>();

    private final SplitView split = new SplitView();
    private final UIElement sidebar = new UIElement();
    private final UIElement detail = new UIElement();
    private final UIElement header = new UIElement();
    /**
     * The search, and every part of it — the box, the count, the mode button, the marking and the filter.
     *
     * <p>Built in {@link #setSource} rather than here, because it installs onto a tree and there is none
     * until then. Permanent: a settings window's search is the first thing in the sidebar and dismissing
     * it would leave the panel with no visible way back.</p>
     */
    @Nullable
    private TreeSearch<T> search;
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();
    private final Button back = new Button("<");
    private final Button forward = new Button(">");
    private final PageStack<T> pages = new PageStack<>();
    private final NavigationHistory<T> history = new NavigationHistory<>();

    private TreeView<T> tree;

    @Nullable
    private FilteredTreeSource<T> filtered;

    private Function<T, String> titleOf = String::valueOf;
    private Function<T, List<String>> trailOf = item -> List.of(String.valueOf(item));

    /** True while a history move is driving the selection, so it is not recorded as a new visit. */
    private boolean navigatingHistory;

    public NavigatorView() {
        markAsInternal();

        sidebar.addClass(SIDEBAR_CLASS);

        header.addClass(HEADER_CLASS);
        back.addClass(BACK_CLASS);
        forward.addClass(FORWARD_CLASS);
        back.attachListener(this::goBack);
        forward.attachListener(this::goForward);
        header.addChild(back);
        header.addChild(forward);
        header.addChild(breadcrumbs);
        breadcrumbs.onSegmentChosen.connect(this::onBreadcrumb);

        detail.addClass(DETAIL_CLASS);
        detail.addChild(header);
        detail.addChild(pages);

        split.first().addChild(sidebar);
        split.second().addChild(detail);
        // Narrow, like every settings window: the tree holds short names and the page holds the work, so
        // an even split gives half the dialog to a column of one-word labels. A share rather than a fixed
        // width because the dialog is resizable, and it is the DEFAULT -- the divider is still draggable.
        // A drag is the user taking ownership of the width. After one, the sidebar stops following its
        // content: somebody who widened it to read a long name must not have it snapped back the next
        // time a branch folds.
        // OWNERSHIP IS TAKEN BY MOVING THE DIVIDER, not by touching it. This listened on the divider's
        // mouse-DOWN, so a click that moved nothing -- landing on the handle, a press-and-release, the
        // start of a drag that went back where it began -- permanently switched off the auto-sizing. The
        // symptom is silent and does not look like a click: unfolding a long page name simply stops
        // widening the sidebar, for the rest of the session, with the label clipped at the pane edge.
        //
        // Reading the percentage instead also covers the keyboard resize (Home/End on the divider), which
        // the mouse-down never did. `writingSplit` is what keeps our own auto-size from reading as the
        // user having done it.
        split.onPercentageChanged.connect(value -> {
            if (!writingSplit) userSizedSidebar = true;
        });
        addInternalChild(split);

        updateHistoryButtons();
    }

    /**
     * Grows the sidebar's <b>minimum</b> width to fit the widest row on screen.
     *
     * <p>A share alone cannot do this: unfolding a branch reveals deeper, longer labels, and a pane sized
     * to a fraction of the dialog clips them — which is what a tree is least able to survive, since a
     * truncated label is often the only thing distinguishing two siblings.</p>
     *
     * <p>A MINIMUM rather than a width, so the divider stays draggable and a user who wants it wider keeps
     * that. It only ever grows within a session: shrinking it as a branch collapses would make the whole
     * page jump sideways every time somebody folded something.</p>
     *
     * <p>Measured from the realised rows rather than computed from the text, because only layout knows
     * what a string is worth in the current font. It settles for the reason every measure-and-push-back
     * loop here settles — {@code replaceOrPutCandidate} no-ops when the value has not moved.</p>
     */
    private boolean fitSidebarToRows() {
        if (tree == null || getAttachedWindow() == null) return true;
        float widest = 0f;
        for (UIElement label : tree.querySelectorAll("." + LABEL_CLASS)) {
            // The label's own X relative to the sidebar already carries the indent and the arrow, so
            // there is nothing to add back -- and no need to reach for the Taffy box to find it.
            float left = label.getRuntimeCache().getX() - sidebar.getRuntimeCache().getX();
            // Its own box is the right measure now that the label self-sizes -- see the renderer.
            widest = Math.max(widest, left + label.getRuntimeCache().getWidth());
        }
        if (widest <= 0f) return true;   // nothing laid out yet
        float wanted = Math.min(MAX_SIDEBAR_WIDTH, widest + SIDEBAR_PADDING);
        if (wanted == sidebarMinimum) return true;
        // GROWS FREELY, SHRINKS ONLY WHEN THE CONTENT CHANGED.
        //
        // Monotonic growth was the original rule, and its reason was sound: this measures the REALISED
        // rows, so a plain scroll changes the answer, and letting it fall on every frame would make the
        // pane breathe as you scrolled. But it is the pane's MINIMUM, so a label seen once pinned the
        // floor for the rest of the session -- unfold a long name, and the split could never be dragged
        // narrow again even after folding it away. Only the shrink is gated: growth still lands the frame
        // a long row appears.
        //
        // `contentChanged` is set by the two things that alter what the widest row COULD be: a fold, and a
        // change of filter. NOT by scrolling, which is what the monotonic rule originally existed to guard
        // against -- a plain scroll changes which rows are realised and must not move the floor.
        //
        // The filter half was missed at first because `onExpandChanged` looked like the whole story. It is
        // not: filtering replaces the row set outright, and the bulk `setExpandedItems` that reveal and
        // restore go through does not emit that signal at all.
        if (wanted < sidebarMinimum && !contentChanged) return true;
        contentChanged = false;
        sidebarMinimum = wanted;
        // THROUGH SplitView'S OWN API, not a CSS min-width on the pane. SplitView already clamps a drag
        // against a per-pane minimum (see boundsFor), and it has no idea about a `min-width` written
        // behind its back -- so with one the WEIGHT walks below what the pane actually renders at, Taffy
        // clamps the drawn width back up, and the two silently disagree. Dragging away from the minimum
        // then moves nothing until the weight climbs back to it, which is a dead zone exactly as wide as
        // the gap. Telling the split is what keeps them the same number.
        writingSplit = true;
        try {
            split.setPaneSizeLimits(0, sidebarMinimum, Float.MAX_VALUE);

        // OPENS AT ITS CONTENT rather than at a fraction somebody picked: a settings tree holds short
        // names, so any hardcoded share is wasteful on a narrow one and clipping on a wide one -- and the
        // width that fits is already being computed right here.
            float total = split.getRuntimeCache().getWidth();
            if (total > 0f && !userSizedSidebar) {
                split.setPercentage(Math.min(90f, sidebarMinimum / total * 100f));
            }
        } finally {
            writingSplit = false;
        }
        return true;
    }

    /** The width the sidebar refuses to be dragged below — the widest row it is currently showing. */
    public float sidebarMinimumWidth() {
        return sidebarMinimum;
    }

    /** Room for the arrow, the gap and the pane's own padding, plus a little air. */
    private static final float SIDEBAR_PADDING = 28f;

    /** However long a label gets, the tree must not take the whole window. */
    private static final float MAX_SIDEBAR_WIDTH = 320f;

    private float sidebarMinimum;

    private boolean userSizedSidebar;

    /** Set when a fold or unfold changes what the widest row could be. @see #fitSidebarToRows */
    private boolean contentChanged;

    /** True while this widget is writing the split itself, so it is not mistaken for the user. */
    private boolean writingSplit;

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (fitting || getAttachedWindow() == null) return;
        fitting = true;
        // From a TICKER, not from here: this writes a style, and a structural write inside the layout pass
        // is the one thing onLayoutChanged must not do.
        getAttachedWindow().registerTicker(delta -> fitSidebarToRows());
    }

    private boolean fitting;

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Wiring ──────────────────────────────────────────────────────────────────────────────────

    /** Installs the tree. The source is wrapped so searching can filter it without the caller helping. */
    public NavigatorView<T> setSource(TreeDataSource<T> source) {
        filtered = new FilteredTreeSource<>(source);
        tree = new TreeView<>(filtered);
        // A DEFAULT RENDERER, because a TreeView without one draws nothing at all -- an empty sidebar
        // beside a working page, which reads as the tree having no items rather than no way to paint
        // them. A caller wanting icons or badges replaces it; a caller wanting a list of names should not
        // have to write one to see anything.
        tree.setRenderer(new TitleRenderer());
        tree.onSelectionChanged.connect(selected -> onTreeSelection());
        // The one event that changes what the widest row could be. A fold must be able to give the width
        // back; a scroll must not.
        tree.onExpandChanged.connect((item, open) -> contentChanged = true);
        sidebar.addChild(tree);

        // THE SHARED COMPONENT, at the top of the sidebar, filtering by default.
        //
        // What used to be here was a TextField, a FilteredTreeSource, a query field and an applyFilter --
        // which is TreeSearch's whole job, minus the marking, the count and the type-ahead it never had.
        // Filtering is FILTER mode; the matcher below is the one thing that stayed, because what a query
        // means for a settings tree involves labels and descriptions this widget has never heard of.
        search = TreeSearch.installOn(tree, sidebar, 0, new TreeSearch.Model<T>() {
            @Override
            public void setQuery(SearchQuery next, boolean filtering) {
                parsedQuery = next == null || next.isEmpty() ? null : next;
                query = parsedQuery == null ? "" : parsedQuery.text();
                // A FILTER CHANGES WHICH ROWS EXIST, which is the same thing a fold does and the same
                // reason the minimum has to be allowed to fall. Without this the sidebar ratcheted open on
                // any query that revealed a long name and never gave the width back when the query was
                // cleared -- the identical dead end 29.15 fixed for folding, reached the other way.
                contentChanged = true;
                if (filtered == null) return;
                filtered.setFilter(query.isEmpty() || !filtering ? null : matcher);
            }

            @Override
            public boolean isMatch(T item) {
                return !query.isEmpty() && matcher.test(item);
            }

            @Override
            public List<SearchMatch.Range> matchRanges(T item) {
                // OVER THE TITLE, which is the only string on screen. A caller's matcher may well have
                // matched a description instead, and then there is nothing here to mark -- correctly: a
                // band over an unrelated word would be a worse answer than no band at all.
                if (query.isEmpty()) return List.of();
                SearchMatch match = parsedQuery == null ? null
                        : SearchMatcher.match(parsedQuery, titleOf.apply(item), 0);
                return match == null ? List.of() : match.ranges();
            }

            @Override
            public int descendantMatches(T item) {
                // COUNTED, not answered 0. `beneath` is what keeps an ancestor from being dimmed, and a
                // filtered settings tree is mostly ancestors -- every category kept only because a setting
                // under it matched. Answering 0 greys out exactly the rows the filter went to the trouble
                // of keeping.
                //
                // Over the FILTERED source, which has already done the work: while filtering it yields
                // only surviving children, so this is a walk over what is on screen rather than over the
                // whole tree.
                if (filtered == null || query.isEmpty()) return 0;
                int total = 0;
                for (T child : filtered.children(item)) {
                    if (matcher.test(child)) total++;
                    total += descendantMatches(child);
                }
                return total;
            }
        }, this::navigateTo);
        // ON THE BAR, not on the box. The class names the search AREA of a navigator, and that is now a
        // row with a count and a mode button in it rather than a lone field -- pointing it at the input
        // left a `width: 100%` rule fighting the bar's own flex layout from one level down.
        search.bar().addClass(SEARCH_CLASS);
        search.input().setPlaceholder("Search");
        search.setPresentation(TreeSearch.Presentation.PERMANENT);
        // A BARE BOX. The tree below IS the answer here -- an empty sidebar says "no matches" more directly
        // than a count does, there is nothing to step through once filtering has narrowed it, and a
        // permanent bar has nothing to dismiss. The matching options are precision tools for prose; a
        // settings page is found by its name.
        search.setControls();
        search.setMode(TreeSearch.Mode.FILTER);
        // ARROWS STAY OURS. They walk the visible tree and OPEN the page for whatever they land on, with
        // or without a query -- so match-stepping would both fight that and go dead the moment the box was
        // empty. Filtering already narrows the rows, which makes "arrow through what is left" the same
        // gesture with a better answer.
        search.setArrowNavigation(false);
        search.input().events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> {
            if (tree == null) return;
            int key = event.getKeyCode();
            if (key != CgKeyCodes.KEY_UP && key != CgKeyCodes.KEY_DOWN) return;
            event.stopPropagation();
            step(key == CgKeyCodes.KEY_DOWN ? 1 : -1);
        }, true, false);
        return this;
    }

    /** How a page is built for an item. Returning null gives the placeholder — see {@link PageStack}. */
    public NavigatorView<T> setPageFactory(Function<T, UIElement> factory) {
        pages.setPageFactory(factory);
        return this;
    }

    /** What an item is called, for the breadcrumb's last segment and for a default trail. */
    public NavigatorView<T> setTitleFunction(Function<T, String> titleOf) {
        this.titleOf = titleOf == null ? String::valueOf : titleOf;
        return this;
    }

    /** The full trail to an item, outermost first. Defaults to just the item's own title. */
    public NavigatorView<T> setTrailFunction(Function<T, List<String>> trailOf) {
        this.trailOf = trailOf;
        return this;
    }

    /**
     * What a search query means for one item.
     *
     * <p>Domain knowledge, so it belongs to the caller: for a settings tree it means "does any setting at
     * or under this path match", which involves labels and descriptions this widget has never heard of.
     * A filter that only matched the tree's own titles would be decorative — typing a setting's name
     * would find nothing.</p>
     */
    public NavigatorView<T> setMatcher(Predicate<T> matcher) {
        this.matcher = matcher;
        return this;
    }

    private Predicate<T> matcher = item -> true;

    private String query = "";

    /** The query with its options, so {@code matches} honours Match Case / Words / Regex. */
    @Nullable
    private SearchQuery parsedQuery;

    /** Shown on a node with no page of its own. @see PageStack#setPlaceholder */
    public NavigatorView<T> setPlaceholder(@Nullable UIElement placeholder) {
        pages.setPlaceholder(placeholder);
        return this;
    }

    // ── Navigating ──────────────────────────────────────────────────────────────────────────────

    /** Selects an item, shows its page and records the visit. */
    public NavigatorView<T> navigateTo(@Nullable T item) {
        if (item == null) return this;
        selectInTree(item);
        apply(item);
        return this;
    }

    /** The tree selects by ROW INDEX, so an item has to be found among the rows currently shown. */
    private void selectInTree(T item) {
        int index = indexOf(item);
        if (index >= 0 && tree != null) tree.select(index);
    }

    private int indexOf(T item) {
        if (tree == null) return -1;
        List<TreeRow<T>> rows = tree.visibleRows();
        for (int i = 0; i < rows.size(); i++) {
            if (item.equals(rows.get(i).item())) return i;
        }
        return -1;
    }

    private void onTreeSelection() {
        if (tree == null) return;
        List<TreeRow<T>> rows = tree.visibleRows();
        int index = tree.getFocusedIndex();
        if (index < 0 || index >= rows.size()) return;
        T item = rows.get(index).item();
        if (!item.equals(pages.current())) apply(item);
    }

    private void apply(T item) {
        pages.show(item);
        breadcrumbs.setTrail(trailFor(item));
        if (!navigatingHistory) history.visit(item);
        updateHistoryButtons();
        onNavigated.emit(item);
    }

    private List<String> trailFor(T item) {
        if (trailOf == null) return List.of(titleOf.apply(item));
        List<String> trail = trailOf.apply(item);
        return trail == null || trail.isEmpty() ? List.of(titleOf.apply(item)) : trail;
    }

    private void goBack() {
        move(history.back());
    }

    private void goForward() {
        move(history.forward());
    }

    /**
     * Shows a place the history moved to, <b>without</b> recording it.
     *
     * <p>Re-recording would truncate the forward tail the move just created, so Forward would never be
     * available and a second Back press would appear to do nothing.</p>
     */
    private void move(@Nullable T item) {
        if (item == null) return;
        navigatingHistory = true;
        try {
            selectInTree(item);
            apply(item);
        } finally {
            navigatingHistory = false;
        }
    }

    private void onBreadcrumb(int index) {
        T current = pages.current();
        if (current == null || tree == null) return;
        List<T> path = ancestorsOf(current);
        if (index >= 0 && index < path.size()) navigateTo(path.get(index));
    }

    /**
     * Outermost first, ending at {@code item} — the same order the breadcrumb draws.
     *
     * <p>Walks {@code TreeRow.parentIndex()} rather than re-searching the source, so it costs a step per
     * level and uses the structure the view already computed. It follows that a breadcrumb segment for a
     * collapsed ancestor is still reachable: the rows are the tree's own flattening, and an ancestor of a
     * visible row is visible by construction.</p>
     */
    private List<T> ancestorsOf(T item) {
        List<T> path = new ArrayList<>();
        List<TreeRow<T>> rows = tree.visibleRows();
        int at = indexOf(item);
        while (at >= 0 && at < rows.size()) {
            path.add(0, rows.get(at).item());
            at = rows.get(at).parentIndex();
        }
        return path;
    }

    private void step(int delta) {
        if (tree == null) return;
        List<TreeRow<T>> rows = tree.visibleRows();
        if (rows.isEmpty()) return;
        T current = pages.current();
        int at = current == null ? -1 : indexOf(current);
        int next = Math.max(0, Math.min(rows.size() - 1, at + delta));
        navigateTo(rows.get(next).item());
    }

    private void updateHistoryButtons() {
        back.setEnabled(history.canGoBack());
        forward.setEnabled(history.canGoForward());
    }

    /** Focus belongs in the search field, which is where the keys are steered from. */
    public void giveFocus() {
        UIWindow window = getAttachedWindow();
        if (window != null && search != null) window.getInputHandler().requestFocus(search.input());
    }

    /**
     * An expander and a title.
     *
     * <p>Indentation is {@code TreeView}'s own — it writes {@code padding-left} from {@code TreeRow.depth}
     * at DEFAULT origin, so <b>a sheet rule setting padding on this element silently flattens the whole
     * tree</b>. It also adds {@code __expanded__} / {@code __collapsed__} / {@code __leaf__} to the
     * template, which is what lets the arrow be a stylesheet's business rather than a string chosen
     * here.</p>
     *
     * <p>The arrow's row index is read <b>per event</b> from the element the press landed on. Rows are
     * pooled and recycled as the tree scrolls, so a listener that captured its index would keep toggling
     * whatever row its slot was first used for — which works right up until somebody scrolls.</p>
     */
    private final class TitleRenderer implements TreeRenderer<T> {
        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(NODE_CLASS);

            UIElement arrow = new UIElement();
            arrow.addClass(ARROW_CLASS);
            arrow.onMouseDown.attachListener((element, event) -> {
                if (tree == null) return;
                int at = tree.indexOfRowElement(element.getParent());
                if (at < 0) return;
                event.stopPropagation();
                tree.toggleExpandedAt(at);
            }, false, true);
            row.addChild(arrow);

            UIText label = new UIText("");
            label.addClass(LABEL_CLASS);
            label.setHitTest(false);
            // FORCED, not left to the auto-detect. UIText decides whether it sizes itself ONCE, from
            // whether its content box is empty on the first post-attachment recompute -- and a tree row
            // already has a width by then, so the label latches "take what I am given" and truncates for
            // the rest of its life. Its own javadoc names this: the only recovery was destroying and
            // rebuilding the element, which is why nudging the divider (re-realising the rows) appeared to
            // fix it while unfolding a long name never did. This label must drive the sidebar's width by
            // construction, so it should not gamble on the race at all.
            label.forceSelfSizeWidth();
            row.addChild(label);
            return row;
        }

        @Override
        public void bind(T item, TreeRow<T> row, int index, UIElement template) {
            for (UIElement child : template.getChildren()) {
                if (child instanceof UIText label) label.setText(titleOf.apply(item));
            }
        }
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────

    public SplitView split() {
        return split;
    }

    /** The search box itself. Null until {@link #setSource} has run — it installs onto the tree. */
    @Nullable
    public TextField search() {
        return search == null ? null : search.input();
    }

    /** The whole search component, for a caller that wants the mode, the count or the marking. */
    @Nullable
    public TreeSearch<T> treeSearch() {
        return search;
    }

    @Nullable
    public TreeView<T> tree() {
        return tree;
    }

    public Breadcrumbs breadcrumbs() {
        return breadcrumbs;
    }

    public PageStack<T> pages() {
        return pages;
    }

    public NavigationHistory<T> history() {
        return history;
    }

    public String query() {
        return query;
    }

    /**
     * The query <b>with its options</b> — null when nothing is being searched for.
     *
     * <p>What a matcher should be given. {@link #query()} is the bare text, and anything rebuilding a
     * {@code SearchQuery} from it drops Match Case, Words and Regex on the floor.</p>
     */
    @Nullable
    public SearchQuery parsedQuery() {
        return parsedQuery;
    }
}
