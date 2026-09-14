package com.crystalgui.widget.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

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
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.TextRange;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

/**
 * A search box over a categorised tree: typing narrows and ranks, browsing shows folders, and the arrows and
 * Enter work while the caret stays in the box. The body of {@link CreateMenu}, and dockable on its own.
 *
 * <pre>{@code
 * SearchTree<MyNode, Thing> library = new SearchTree<MyNode, Thing>().setRows(rows);
 * library.onChosen.connect(thing -> place(thing));
 * panel.append(library);
 * library.refresh();
 * }</pre>
 *
 * <p>A panel that draws its rows differently gives the tree its own renderer — the Library's rows are strips
 * of cards:</p>
 *
 * <pre>{@code
 * library.treeView().setRenderer(new CardStripRenderer());
 * }</pre>
 *
 * <ul>
 *   <li>Set the rows before {@link #refresh}; with none it lists nothing.</li>
 *   <li>It needs a definite height: a virtualised tree has no intrinsic one. The sheet gives {@code searchtree}
 *       {@code height: 0; flex-grow: 1}, so its parent must be sized.</li>
 * </ul>
 */
public class SearchTree<N, T> extends UIElement {

    public static final Name NAME = Name.of("searchtree");

    public static final String SEARCH_CLASS = "__search__";
    public static final String LIST_CLASS = "__items__";
    public static final String ENTRY_CLASS = "__entry__";
    public static final String EMPTY_CLASS = "__empty__";
    public static final String TWISTY_CLASS = "__twisty__";
    public static final String LABEL_CLASS = "__label__";
    public static final String CATEGORY_CLASS = "__category__";

    /** A row's icon, drawn when {@link Rows#icon} names one; tinted by whatever class {@link Rows#iconClass} adds. */
    public static final String ENTRY_ICON_CLASS = "__entry-icon__";

    /** The dimmed {@code Math ▸ Vector} suffix on a search result — what makes a category-only match legible. */
    public static final String ENTRY_CATEGORY_CLASS = "__entry-category__";

    /** One segment of that suffix — {@code Math}, then {@code Vector}. */
    public static final String CATEGORY_SEGMENT_CLASS = "__category-segment__";

    /** The mark between two segments — a drawn shape, since a font with no glyph for it draws a blank. */
    public static final String CATEGORY_SEPARATOR_CLASS = "__category-separator__";

    /** The {@code ::highlight(...)} name the matched characters are registered under, so the tint is a theme's. */
    public static final String MATCH_HIGHLIGHT = "search-match";

    /**
     * At or below this many entries, every folder starts open.
     *
     * <p>Chosen so the shapes people actually hit — a small library, a filtered handful — never make you
     * click a folder to see three things. Above it, folders are the point.</p>
     */
    public static final int DEFAULT_AUTO_EXPAND_THRESHOLD = 12;

    /**
     * What the tree lists, and what choosing one produces.
     *
     * <p>Five questions. {@link #roots} is asked on every keystroke and is where a consumer decides
     * whether a query <em>flattens</em> a ranked list or keeps the folders — the tree only draws what it
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

        /** An icon drawn before the label — {@code "crystalgui:nodes/ui/button"} — or null for none. */
        @Nullable
        default String icon(N node) {
            return null;
        }

        /** One class the icon wears, so a theme tints it — a kind's role — or null. */
        @Nullable
        default String iconClass(N node) {
            return null;
        }
    }

    private final SearchField search = new SearchField();
    private final TreeView<N> tree;
    private final UIText emptyLabel = new UIText("nothing matches");

    @Nullable
    private Rows<N, T> rows;

    /** The current roots — rebuilt on every keystroke, read by the tree's data source. */
    private List<N> roots = new ArrayList<>();

    /** No row is highlighted — the browsing state. */
    private static final int NONE_HIGHLIGHTED = -1;

    /** The row the arrows act on. Kept here rather than read from the tree's focused index, because the
     * tree never takes focus — the search box holds it. */
    private int highlighted = NONE_HIGHLIGHTED;

    @Getter
    private int autoExpandThreshold = DEFAULT_AUTO_EXPAND_THRESHOLD;

    private BooleanSupplier refocusAfterPress = () -> true;

    /** Fires with what was chosen. A folder emits nothing; it opens. */
    public final Signal.Value<T> onChosen = new Signal.Value<>();

    public SearchTree() {
        this(NAME);
    }

    protected SearchTree(Name name) {
        super(name);
        search.addClass(SEARCH_CLASS);
        search.setPlaceholder("search");
        search.onQueryChanged.connect(this::refresh);

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

    /** Says what this lists. */
    public SearchTree<N, T> setRows(Rows<N, T> rows) {
        this.rows = rows;
        return this;
    }

    @Nullable
    public Rows<N, T> rows() {
        return rows;
    }

    /**
     * Whether a press on a row puts the caret back in the search box. A menu answers {@code isOpen}, so a
     * choice that closes it does not take focus back from whatever it restored.
     */
    public SearchTree<N, T> setRefocusAfterPress(BooleanSupplier refocus) {
        this.refocusAfterPress = refocus;
        return this;
    }

    /** @see #DEFAULT_AUTO_EXPAND_THRESHOLD */
    public SearchTree<N, T> setAutoExpandThreshold(int entries) {
        this.autoExpandThreshold = Math.max(0, entries);
        return this;
    }

    /**
     * Clears the query, the folders and the scroll, then re-lists — what a menu reused across openings needs,
     * or it shows the last visit's state.
     */
    public void reset() {
        search.setText("");
        tree.collapseAll();
        // IMMEDIATE, not the animated setter, and before refresh() so the new model is measured at 0.
        Box treeBox = tree.box();
        if (treeBox != null) treeBox.setScroll(0f, 0f);
        refresh();
    }

    /** The query as typed. */
    public String query() {
        return search.getText();
    }

    /** Re-lists for the current query. Rebuilt wholesale, which is safe: the search box is under the pointer, not the list. */
    public void refresh() {
        String query = search.getText();
        roots = rows == null ? List.of() : rows.roots(query);

        tree.refresh();
        if (leafCount(roots) <= autoExpandThreshold) {
            for (N category : categoriesIn(roots)) tree.setExpanded(category, true);
        }

        // A query highlights its top match; browsing highlights nothing. With a query Enter takes the best
        // match; without one the first row is usually a CATEGORY, and "Enter folds it" is not a default.
        boolean searching = !query.trim().isEmpty();
        highlighted = searching ? 0 : NONE_HIGHLIGHTED;
        if (searching && !tree.getModel().isEmpty()) tree.select(0);
        else tree.clearSelection();

        boolean empty = roots.isEmpty();
        // display rather than opacity: a label that keeps its box pushes the tree down on every miss.
        StyleGroup.inlinePipeline(emptyLabel.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.inlinePipeline(tree.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
    }

    /**
     * Puts the caret in the search box — the FIELD, since only a text field holds a caret, and by pointer so
     * no {@code :focus-visible} ring appears on every click of a folder.
     */
    public void focusSearch() {
        UIDocument window = document();
        if (window != null) window.focus().requestPointerFocus(search.field());
    }

    /** Enter on a row, and what a row's press delegates to — one path, so a folder behaves identically however reached. */
    public void activateRow(int index) {
        TreeRow<N> row = tree.rowAt(index);
        if (row == null || rows == null) return;
        if (rows.isCategory(row.item())) {
            highlight(index);
            return;
        }
        T chosen = rows.payload(row.item());
        if (chosen != null) onChosen.emit(chosen);
    }

    /** @return whether the key was ours, so the caller knows whether to consume it */
    private boolean handleListKey(int keyCode) {
        int count = tree.getModel().size();
        if (count == 0) return false;

        switch (keyCode) {
            case CgKeyCodes.KEY_DOWN:
                // From nothing, the first arrow lands on the FIRST row rather than the second.
                highlight(highlighted == NONE_HIGHLIGHTED ? 0 : Math.min(count - 1, highlighted + 1));
                return true;
            case CgKeyCodes.KEY_UP:
                // Clamped rather than wrapped: a ranked column has a top, and what you want is usually first.
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

    /** Moves the highlight and keeps it on screen — a scrolled-out row of a virtualised list does not exist. */
    private void highlight(int index) {
        highlighted = Math.max(0, Math.min(tree.getModel().size() - 1, index));
        tree.select(highlighted);
        tree.scrollToIndex(highlighted);
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
     * <p>The twisty is an element because there are no pseudo-elements here; {@link TreeView} supplies the
     * {@code __expanded__}/{@code __collapsed__}/{@code __leaf__} classes, so a theme decides its look.</p>
     */
    private final class EntryRenderer implements TreeRenderer<N> {

        @Override
        public UIElement createTemplate() {
            EntryRow row = new EntryRow();
            // Listeners belong in the template, never in bind — an element is recycled across rows.
            // THE TWISTY FOLDS on a single press, as the Library's and the Hierarchy's do, and the press stops
            // there: the row would otherwise select a category it was only asked to open.
            row.twisty.onMouseDown.attachListener((element, event) -> {
                int index = tree.indexOfRowElement(row);
                TreeRow<N> at = index < 0 ? null : tree.rowAt(index);
                if (at == null || !at.expandable()) return;
                event.stopPropagation();
                event.preventDefault();
                tree.requestToggleAt(index);
                if (refocusAfterPress.getAsBoolean()) focusSearch();
            }, false, false);
            row.onMouseDown.attachListener((element, event) -> {
                int index = tree.indexOfRowElement(element);
                if (index >= 0) activateRow(index);
                // A press blurs whatever was focused and a row is not click-focusable, which would leave
                // nothing focused and every key this answers hangs off the search field.
                if (refocusAfterPress.getAsBoolean()) focusSearch();
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
            entry.setIcon(category ? null : rows.icon(item), category ? null : rows.iconClass(item));

            // Suffix and match tint are recomputed per bind, because rows are recycled; matched against the
            // DRAWN string so the tint stays aligned with what is on screen.
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

        /** Always sets, including nothing — or a recycled row keeps the previous occupant's ranges. */
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

    /** A typed template, so bind reaches its parts by name rather than by child index. */
    private static final class EntryRow extends UIElement {

        /** Deepest trail this can draw; anything deeper collapses into the last segment. */
        static final int MAX_CATEGORY_SEGMENTS = 3;

        private final UIElement twisty = new UIElement();
        private final UIElement icon = new UIElement();
        private final UIText label = new UIText("");

        /** What the icon holds, so a rebind to the same one redraws nothing. */
        @Nullable
        private String iconName;

        @Nullable
        private String iconTint;
        private final UIElement category = new UIElement();
        private final UIText[] categorySegments = new UIText[MAX_CATEGORY_SEGMENTS];
        private final UIElement[] categorySeparators = new UIElement[MAX_CATEGORY_SEGMENTS - 1];

        EntryRow() {
            addClass(ENTRY_CLASS);
            twisty.addClass(TWISTY_CLASS);
            label.addClass(LABEL_CLASS);
            label.setHitTest(false);
            icon.addClass(ENTRY_ICON_CLASS);
            icon.setHitTest(false);
            show(icon, false);

            // Built ONCE and shown/hidden per bind: creating elements in bind churns Taffy nodes per keystroke.
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
            append(icon);
            append(label);
            append(category);
        }

        /** Draws {@code name} tinted by {@code tint}, or hides the icon for null. */
        void setIcon(@Nullable String name, @Nullable String tint) {
            if (!Objects.equals(tint, iconTint)) {
                if (iconTint != null) icon.removeClass(iconTint);
                if (tint != null) icon.addClass(tint);
                iconTint = tint;
            }
            if (Objects.equals(name, iconName)) return;
            iconName = name;
            show(icon, name != null);
            if (name == null) return;
            CgUiSvg svg = CgUiSvg.ofIcon(name);
            CgUiDrawable drawn = svg == null ? CgUiDrawable.EMPTY : svg;
            StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(), g -> g.overlay(drawn));
        }

        /** Shows exactly {@code segments.size()} labels and the marks between them; hides the rest. */
        void setCategory(List<String> segments) {
            int shown = Math.min(segments.size(), MAX_CATEGORY_SEGMENTS);
            for (int i = 0; i < MAX_CATEGORY_SEGMENTS; i++) {
                boolean visible = i < shown;
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

    /** None: the box, the list and the empty label ARE this widget, and the constructor rebuilds them. */
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

    /** The rows on screen, in order — the flattened model, since the realised elements are only a viewport over it. */
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

    /** Everything the current query admits, folders or not. */
    public List<N> allOffers() {
        List<N> leaves = new ArrayList<>();
        collectLeaves(roots, leaves);
        return leaves;
    }

    /** The realised row elements, top to bottom. Only meaningful once laid out. */
    public List<UIElement> entries() {
        List<UIElement> realised = new ArrayList<>();
        for (int index = 0; index < tree.getModel().size(); index++) {
            UIElement row = tree.realisedRows().get(index);
            if (row != null) realised.add(row);
        }
        return realised;
    }
}
