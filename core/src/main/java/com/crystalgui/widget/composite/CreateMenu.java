package com.crystalgui.widget.composite;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import lombok.Getter;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.TextRange;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/**
 * <b>Search a categorised library and pick one thing</b> — Unity's Create Node window, VS Code's command
 * palette, Blender's Add menu.
 *
 * <p>A popover with a search box over a virtualised tree: typing narrows and ranks, browsing shows
 * folders, the arrows and Enter work while the caret stays in the box, and the whole menu drags by its
 * title bar. What it lists is yours.</p>
 *
 * <pre>{@code
 * final class ThingMenu extends CreateMenu<MyNode, Thing> {
 *     ThingMenu() {
 *         super(NAME, "Add Thing");
 *         setRows(new Rows<MyNode, Thing>() {
 *             public List<MyNode> roots(String query) { return library.tree(query); }
 *             public List<MyNode> children(MyNode node) { return node.children(); }
 *             public String label(MyNode node)          { return node.label(); }
 *             public boolean isCategory(MyNode node)    { return node.isFolder(); }
 *             public Thing payload(MyNode node)         { return node.thing(); }
 *         });
 *     }
 * }
 *
 * menu.onChosen.connect(thing -> place(thing));
 * menu.openAt(x, y, invoker);
 * }</pre>
 *
 * <p>Two parameters: {@code N} is whatever your tree is made of and {@code T} is what choosing a leaf
 * produces. Set the rows before opening — a menu with none lists nothing and says nothing about it.</p>
 *
 * <h3>Why the menu has a definite height</h3>
 *
 * <p>A {@link TreeView} cannot live in a content-sized box: it is virtualised, its rows are absolutely
 * positioned and its scroll range comes from the <em>model</em>, so Taffy measures it as empty and it
 * realises exactly one row. The sheet gives this a height, which is what gives the list a viewport to
 * fill — Unity's Create Node window is a fixed-size panel for the same reason.</p>
 */
public class CreateMenu<N, T> extends Popover {

    /** This widget's kind. A subclass with its own look declares its own and passes it up. */
    public static final Name NAME = Name.of("createmenu");

    public static final String SEARCH_CLASS = "__search__";
    public static final String LIST_CLASS = "__items__";
    public static final String ENTRY_CLASS = "__entry__";
    public static final String EMPTY_CLASS = "__empty__";
    public static final String TITLE_BAR_CLASS = "__title-bar__";
    public static final String TITLE_CLASS = "__title__";
    public static final String TWISTY_CLASS = "__twisty__";
    public static final String LABEL_CLASS = "__label__";
    public static final String CATEGORY_CLASS = "__category__";

    /** The dimmed {@code Math ▸ Vector} suffix on a search result — what makes a category-only match
     * legible instead of arbitrary. */
    public static final String ENTRY_CATEGORY_CLASS = "__entry-category__";

    /** One segment of that suffix — {@code Math}, then {@code Vector}. */
    public static final String CATEGORY_SEGMENT_CLASS = "__category-segment__";

    /** The mark between two segments. A DRAWN shape, never a character — a font with no glyph for it
     * draws a blank advance, which reads as a missing separator rather than as a missing glyph. */
    public static final String CATEGORY_SEPARATOR_CLASS = "__category-separator__";

    /** The {@code ::highlight(...)} name the matched characters are registered under, so the tint is a
     * theme's business. */
    public static final String MATCH_HIGHLIGHT = "search-match";

    /**
     * At or below this many entries, every folder starts open.
     *
     * <p>Chosen so the shapes people actually hit — a small library, a filtered handful — never make you
     * click a folder to see three things. Above it, folders are the point.</p>
     */
    public static final int DEFAULT_AUTO_EXPAND_THRESHOLD = 12;

    /**
     * What the menu lists, and what choosing one produces.
     *
     * <p>Five questions. {@link #roots} is asked on every keystroke and is where a consumer decides
     * whether a query <em>flattens</em> a ranked list or keeps the folders — the menu only draws what it
     * is given.</p>
     */
    public interface Rows<N, T> {

        /** The tree for a query. Empty query means browsing, which usually means categories. */
        List<N> roots(String query);

        List<N> children(N node);

        String label(N node);

        /** Whether this row is a folder rather than something choosable. */
        boolean isCategory(N node);

        /** What choosing this row produces, or null for a folder. */
        @Nullable
        T payload(N node);

        /** The dimmed trail drawn after a search result — {@code Math}, {@code Vector}. */
        default List<String> categorySegments(N node) {
            return List.of();
        }
    }

    private final UIElement titleBar = new UIElement();
    private final UIText titleLabel;
    private final SearchField search = new SearchField();
    private final TreeView<N> tree;
    private final UIText emptyLabel = new UIText("nothing matches");

    @Nullable
    private Rows<N, T> rows;

    /** The current roots — rebuilt on every keystroke, read by the tree's data source. */
    private List<N> roots = new ArrayList<>();

    /** No row is highlighted — the browsing state, and what the menu opens in. */
    private static final int NONE_HIGHLIGHTED = -1;

    /** The row the arrows act on. Kept here rather than read from the tree's focused index, because the
     * tree never takes focus — the search box holds it for the menu's whole life. */
    private int highlighted = NONE_HIGHLIGHTED;

    @Getter
    private int autoExpandThreshold = DEFAULT_AUTO_EXPAND_THRESHOLD;

    /** Fires with what was chosen. A folder emits nothing; it opens. */
    public final Signal.Value<T> onChosen = new Signal.Value<>();

    public CreateMenu(String title) {
        this(NAME, title);
    }

    protected CreateMenu(Name name, String title) {
        super(name);
        this.titleLabel = new UIText(title);

        titleBar.addClass(TITLE_BAR_CLASS);
        titleLabel.addClass(TITLE_CLASS);
        titleLabel.setHitTest(false);
        titleBar.append(titleLabel);
        titleBar.onMouseDown.attachListener((element, event) -> {
            beginMove(event.getPosition().x(), event.getPosition().y());
            // Or the surface underneath takes it as a press on empty background and starts a marquee —
            // the menu is a promoted child, so its input still travels through whatever opened it.
            event.stopPropagation();
        }, false, true);

        search.addClass(SEARCH_CLASS);
        search.setPlaceholder("search");
        search.onQueryChanged.connect(this::rebuild);

        tree = new TreeView<>(new TreeDataSource<N>() {
            @Override
            public List<N> roots() {
                return roots;
            }

            @Override
            public List<N> children(N parent) {
                return rows == null ? List.of() : rows.children(parent);
            }

            @Override
            public boolean hasChildren(N item) {
                return rows != null && !rows.children(item).isEmpty();
            }
        });
        tree.addClass(LIST_CLASS);
        tree.setRenderer(new EntryRenderer());
        tree.onRowActivated.connect(this::activateRow);

        emptyLabel.addClass(EMPTY_CLASS);

        append(titleBar);
        append(search);
        append(tree);
        append(emptyLabel);

        // Arrows and Enter drive the list while focus STAYS in the search box.
        //
        // Moving focus into the tree would be the obvious implementation and is wrong twice over: it
        // would stop you typing, and it would silently do nothing anyway — a list takes no focus policy
        // of its own, and requestFocus refuses a FocusPolicy.NONE element without reporting it.
        search.field().onKeyDown.attachListener((element, event) -> {
            if (!handleListKey(event.getKeyCode())) return;
            event.stopPropagation();
            // Stops the field's own caret handling for these keys; Left/Right are deliberately NOT taken,
            // because they belong to the text you are editing.
            event.preventDefault();
        }, false, true);
    }

    /** Says what this menu lists. Set once, before opening. */
    public CreateMenu<N, T> setRows(Rows<N, T> rows) {
        this.rows = rows;
        return this;
    }

    /** What the title bar says. */
    public CreateMenu<N, T> setTitle(String title) {
        titleLabel.setText(title);
        return this;
    }

    /** @see #DEFAULT_AUTO_EXPAND_THRESHOLD */
    public CreateMenu<N, T> setAutoExpandThreshold(int entries) {
        this.autoExpandThreshold = Math.max(0, entries);
        return this;
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /**
     * Opens at a point, anchored to whatever invoked it.
     *
     * <p>Resets the query, the folders and the scroll: the menu is one element reused, so without this a
     * reopen shows the last visit's state — the list already a row or two down, with the first category
     * sliced off above the viewport, which reads as a rendering glitch rather than leftover state.</p>
     */
    public CreateMenu<N, T> openAt(float rootX, float rootY, @Nullable UIElement invoker) {
        search.setText("");
        tree.collapseAll();
        // IMMEDIATE, not the animated setter: a reset, not a movement. And before rebuild(), so the
        // offset is already 0 when the new model is measured rather than being clamped against the old.
        // Nullable on the ordinary path: a menu opened on the frame it was built in has a tree that has
        // never been laid out, which is exactly the state this reset leaves it in.
        Box treeBox = tree.box();
        if (treeBox != null) treeBox.setScroll(0f, 0f);
        rebuild();
        showAt(rootX, rootY, invoker);
        // Focus the box, not the first row: the menu exists to be typed into, and a user who wanted the
        // first row would have clicked it.
        if (document() != null) focusSearch();
        return this;
    }

    /**
     * Drags the whole menu by its title bar.
     *
     * <p><b>The drag source is the window, not the title bar</b>, and that is the trick: every drag
     * coordinate is converted through its source's own transform, so sourcing a move from the thing being
     * moved measures the delta in a frame that is itself moving.</p>
     */
    private void beginMove(float pointerX, float pointerY) {
        UIDocument window = document();
        if (window == null) return;
        Box placed = box();
        final float startLeft = placed == null ? 0f : placed.x();
        final float startTop = placed == null ? 0f : placed.y();
        // No payload, no drop targets, no activation threshold: a move must track the first pixel.
        Drag.start(window, pointerX, pointerY,
                (mx, my, sx, sy, dx, dy) -> moveTo(startLeft + dx, startTop + dy));
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** Rebuilt wholesale on every keystroke, which is safe here: this list is not under the pointer while
     * it changes — the search box is. */
    protected void rebuild() {
        String query = search.getText();
        roots = rows == null ? List.of() : rows.roots(query);

        tree.refresh();
        if (leafCount(roots) <= autoExpandThreshold) {
            for (N category : categoriesIn(roots)) tree.setExpanded(category, true);
        }

        // A query highlights its top match; browsing highlights nothing.
        //
        // The command-palette rule, and the asymmetry is the point. With a query, Enter should take the
        // best match. Without one, the first row is whatever sorted first (usually a CATEGORY), so
        // highlighting it offers "Enter collapses this folder" as the default action, which is not
        // something anyone opened the menu to do.
        boolean searching = !query.trim().isEmpty();
        highlighted = searching ? 0 : NONE_HIGHLIGHTED;
        if (searching && !tree.getModel().isEmpty()) tree.select(0);
        else tree.clearSelection();

        boolean empty = roots.isEmpty();
        // display rather than opacity: an empty-state label that keeps its box would push the tree down
        // by its own height on every keystroke that matched nothing.
        StyleGroup.inlinePipeline(emptyLabel.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.inlinePipeline(tree.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
    }

    /**
     * Up/Down/Enter, forwarded from the search box.
     *
     * @return whether the key was ours, so the caller knows whether to consume it
     */
    private boolean handleListKey(int keyCode) {
        int count = tree.getModel().size();
        if (count == 0) return false;

        switch (keyCode) {
            case CgKeyCodes.KEY_DOWN:
                // From nothing, the first arrow lands on the FIRST row rather than the second.
                highlight(highlighted == NONE_HIGHLIGHTED ? 0 : Math.min(count - 1, highlighted + 1));
                return true;
            case CgKeyCodes.KEY_UP:
                // Clamped rather than wrapped. A search list is a ranked column with a top, and wrapping
                // from the first row to the last is disorienting when what you want is usually first.
                highlight(highlighted == NONE_HIGHLIGHTED ? 0 : Math.max(0, highlighted - 1));
                return true;
            case CgKeyCodes.KEY_RETURN:
            case CgKeyCodes.KEY_NUMPADENTER:
                // Nothing highlighted: not ours. Left unconsumed so the field still sees its own Enter.
                if (highlighted == NONE_HIGHLIGHTED) return false;
                activateRow(highlighted);
                return true;
            default:
                return false;
        }
    }

    /** Moves the highlight and keeps it on screen — the list is virtualised, so a row that is scrolled
     * out does not merely look unselected, it does not exist as an element at all. */
    private void highlight(int index) {
        highlighted = Math.max(0, Math.min(tree.getModel().size() - 1, index));
        tree.select(highlighted);
        tree.scrollToIndex(highlighted);
    }

    /**
     * Puts the caret back in the search box — the standing invariant, since every key the menu responds
     * to is handled on that field.
     *
     * <p>The FIELD, not the {@link SearchField} wrapper: only a text field can hold a caret. And
     * {@code requestPointerFocus}, because the programmatic call is the one that rings
     * {@code :focus-visible} — a ring around the search box on every click of a folder is exactly the
     * noise that pseudo-class exists to avoid.</p>
     */
    private void focusSearch() {
        UIDocument window = document();
        if (window != null) window.focus().requestPointerFocus(search.field());
    }

    /** Enter on a highlighted row, and what a row's own press delegates to — one path, so a folder
     * behaves identically however it was reached. */
    private void activateRow(int index) {
        TreeRow<N> row = tree.rowAt(index);
        if (row == null || rows == null) return;
        if (rows.isCategory(row.item())) {
            tree.toggleExpanded(row.item());
            // Re-assert the highlight. Expanding rebuilds the flattened model wholesale and the list
            // drops any selection a model change invalidated, so without this, opening a folder
            // unhighlights the very row you are standing on.
            highlight(index);
            return;
        }
        T chosen = rows.payload(row.item());
        if (chosen == null) return;
        onChosen.emit(chosen);
        hide();
    }

    // ── Walking the tree ────────────────────────────────────────────────────

    private int leafCount(List<N> nodes) {
        if (rows == null) return 0;
        int count = 0;
        for (N node : nodes) {
            if (rows.isCategory(node)) count += leafCount(rows.children(node));
            else count++;
        }
        return count;
    }

    private List<N> categoriesIn(List<N> nodes) {
        List<N> found = new ArrayList<>();
        collectCategories(nodes, found);
        return found;
    }

    private void collectCategories(List<N> nodes, List<N> out) {
        if (rows == null) return;
        for (N node : nodes) {
            if (!rows.isCategory(node)) continue;
            out.add(node);
            collectCategories(rows.children(node), out);
        }
    }

    private void collectLeaves(List<N> nodes, List<N> out) {
        if (rows == null) return;
        for (N node : nodes) {
            if (rows.isCategory(node)) collectLeaves(rows.children(node), out);
            else out.add(node);
        }
    }

    // ── The row ─────────────────────────────────────────────────────────────

    /**
     * Row template and binding.
     *
     * <p>The twisty is an element rather than a CSS marker because there are no pseudo-elements in this
     * engine; {@link TreeView} still supplies the {@code __expanded__}/{@code __collapsed__}/{@code
     * __leaf__} classes on the row, so a theme decides what it looks like and this only decides that it
     * exists.</p>
     */
    private final class EntryRenderer implements TreeRenderer<N> {

        @Override
        public UIElement createTemplate() {
            EntryRow row = new EntryRow();
            // Listeners belong in the template, never in bind — an element is recycled across rows, so a
            // listener attached per bind would accumulate one per row it ever displayed. It therefore
            // cannot capture an index and has to ask the tree which row it is currently showing.
            row.onMouseDown.attachListener((element, event) -> {
                int index = tree.indexOfRowElement(element);
                if (index >= 0) activateRow(index);
                // Put focus back in the search box. A press blurs whatever was focused and re-focuses
                // only if what it hit is click-focusable — a row is a plain element, so clicking one
                // left the menu with NO focused element at all, and every key it answers hangs off the
                // search field. The menu looked alive and had gone completely deaf.
                if (isOpen()) focusSearch();
                event.stopPropagation();
            }, false, true);
            return row;
        }

        @Override
        public void bind(N item, TreeRow<N> row, int index, UIElement template) {
            if (rows == null) return;
            EntryRow entry = (EntryRow) template;
            boolean category = rows.isCategory(item);
            template.removeClass(CATEGORY_CLASS);
            if (category) template.addClass(CATEGORY_CLASS);
            entry.label.setText(rows.label(item));

            // The category suffix and the match tint are BOTH recomputed on every bind rather than
            // stored on the row, because rows are recycled: anything captured once would be correct
            // until the first scroll and then wear another result's ranges.
            //
            // Matching against the string being DRAWN (rather than a range computed upstream) is what
            // keeps the tint aligned: the trail is displayed with different separators, so offsets taken
            // from the raw path would creep sideways.
            SearchQuery query = SearchQuery.of(search.getText());
            List<String> segments = category || query.isEmpty()
                    ? List.of() : rows.categorySegments(item);
            entry.setCategory(segments);

            highlight(entry.label, query, rows.label(item), SearchMatch.FIELD_PRIMARY);
            for (int i = 0; i < EntryRow.MAX_CATEGORY_SEGMENTS; i++) {
                highlight(entry.categorySegments[i], query,
                        i < segments.size() ? segments.get(i) : "", SearchMatch.FIELD_CONTEXT);
            }
        }

        /**
         * Registers the matched ranges under {@link #MATCH_HIGHLIGHT}, or clears them.
         *
         * <p><b>Always calls {@code set}, including with nothing.</b> Clearing only when a match exists
         * would leave the previous occupant's ranges on a recycled row — a tint that drifts to unrelated
         * rows as you scroll, which reads as a rendering bug rather than a stale-data one.</p>
         */
        private void highlight(UIText text, SearchQuery query, String drawn, int fieldWeight) {
            SearchMatch match = query.isEmpty() || drawn.isEmpty()
                    ? null : SearchMatcher.match(query, drawn, fieldWeight);
            if (match == null) {
                text.highlights().set(MATCH_HIGHLIGHT, List.of());
                return;
            }
            List<TextRange> ranges = new ArrayList<>(match.ranges().size());
            for (SearchMatch.Range range : match.ranges()) {
                ranges.add(TextRange.of(range.start(), range.end()));
            }
            text.highlights().set(MATCH_HIGHLIGHT, ranges);
        }
    }

    /** A typed template, so {@link EntryRenderer#bind} reaches its parts by name. Indexing into the
     * children would work today and break silently the first time a theme inserted anything. */
    private static final class EntryRow extends UIElement {

        /** Deepest trail this can draw. Three covers every shipped path; anything deeper collapses into
         * the last segment rather than being dropped. */
        static final int MAX_CATEGORY_SEGMENTS = 3;

        private final UIElement twisty = new UIElement();
        private final UIText label = new UIText("");
        private final UIElement category = new UIElement();
        private final UIText[] categorySegments = new UIText[MAX_CATEGORY_SEGMENTS];
        private final UIElement[] categorySeparators = new UIElement[MAX_CATEGORY_SEGMENTS - 1];

        EntryRow() {
            addClass(ENTRY_CLASS);
            twisty.addClass(TWISTY_CLASS);
            twisty.setHitTest(false);
            label.addClass(LABEL_CLASS);
            label.setHitTest(false);

            // Built ONCE and shown/hidden per bind, never rebuilt: rows are recycled, so creating
            // elements in bind would register and drop Taffy nodes on every keystroke.
            category.addClass(ENTRY_CATEGORY_CLASS);
            category.setHitTest(false);
            for (int i = 0; i < MAX_CATEGORY_SEGMENTS; i++) {
                if (i > 0) {
                    UIElement separator = new UIElement();
                    separator.addClass(CATEGORY_SEPARATOR_CLASS);
                    separator.setHitTest(false);
                    categorySeparators[i - 1] = separator;
                    category.append(separator);
                }
                UIText segment = new UIText("");
                segment.addClass(CATEGORY_SEGMENT_CLASS);
                segment.setHitTest(false);
                categorySegments[i] = segment;
                category.append(segment);
            }

            append(twisty);
            append(label);
            append(category);
        }

        /** Shows exactly {@code segments.size()} labels and the marks between them; hides the rest. */
        void setCategory(List<String> segments) {
            int shown = Math.min(segments.size(), MAX_CATEGORY_SEGMENTS);
            for (int i = 0; i < MAX_CATEGORY_SEGMENTS; i++) {
                boolean visible = i < shown;
                // The last slot absorbs anything deeper, so an unexpectedly nested trail loses its
                // separators rather than its tail.
                String text = !visible ? ""
                        : i == MAX_CATEGORY_SEGMENTS - 1 && segments.size() > MAX_CATEGORY_SEGMENTS
                        ? String.join(" ", segments.subList(i, segments.size()))
                        : segments.get(i);
                categorySegments[i].setText(text);
                show(categorySegments[i], visible);
                if (i > 0) show(categorySeparators[i - 1], visible);
            }
            show(category, shown > 0);
        }

        private static void show(UIElement element, boolean visible) {
            StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                    l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        }
    }

    /**
     * None: the box, the list and the header ARE this widget, and the constructor rebuilds them.
     *
     * <p>They are light children because a sheet reaches through them ({@code .__search__},
     * {@code .__items__}), so the shadow tree that hides most composites' parts is not available here —
     * and without this a described menu arrives with a second copy of every part appended to the first.</p>
     */
    @Override
    public List<UIElement> describedChildren() {
        return List.of();
    }

    // ── Accessors, for a theme or a test ────────────────────────────────────

    public TextField searchField() {
        return search.field();
    }

    /** The whole search box — icon, field and clear. */
    public SearchField searchBox() {
        return search;
    }

    public TreeView<N> treeView() {
        return tree;
    }

    /** The draggable header. */
    public UIElement titleBar() {
        return titleBar;
    }

    /**
     * The rows currently <b>on screen</b>, in order — the flattened tree, which is what a user can see
     * and arrow through.
     *
     * <p>Deliberately the model rather than the realised elements: the tree is virtualised, so the
     * elements are a window over this and asserting on them measures the viewport rather than the menu.</p>
     */
    public List<N> visibleEntries() {
        List<N> visible = new ArrayList<>();
        for (TreeRow<N> row : tree.visibleRows()) visible.add(row.item());
        return visible;
    }

    /** Just the choosable rows currently on screen — folders dropped. */
    public List<N> visibleOffers() {
        List<N> offers = new ArrayList<>();
        if (rows == null) return offers;
        for (N node : visibleEntries()) {
            if (!rows.isCategory(node)) offers.add(node);
        }
        return offers;
    }

    /** Everything the current query admits, folders or not — what the menu would show fully expanded. */
    public List<N> allOffers() {
        List<N> leaves = new ArrayList<>();
        collectLeaves(roots, leaves);
        return leaves;
    }

    /** The realised row elements, top to bottom. Only meaningful once the menu has been laid out. */
    public List<UIElement> entries() {
        List<UIElement> realised = new ArrayList<>();
        for (int index = 0; index < tree.getModel().size(); index++) {
            UIElement row = tree.realisedRows().get(index);
            if (row != null) realised.add(row);
        }
        return realised;
    }
}
