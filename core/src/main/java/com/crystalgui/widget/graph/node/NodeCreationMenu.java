package com.crystalgui.widget.graph.node;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.NodeMenuTree;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.widget.form.SearchField;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.ui.text.TextRange;

import java.util.ArrayList;
import java.util.List;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * The create-node menu: a search box over a node library, optionally filtered by a wire you are holding.
 *
 * <pre>{@code
 * menu.openForOutput("vec3", compatibility);   // dropped a wire from an output
 * menu.openAll();                              // Space, or a right-click on empty canvas
 * }</pre>
 *
 * <h3>Entries are ports, not nodes</h3>
 * <p>When the menu is opened by dropping a wire, each row is a <b>(type, port)</b> pair — Unity's menu
 * *"lists every available Port on nodes that match"* the dragged type. Choosing one therefore creates
 * the node <em>and</em> lands the wire, which is a materially better interaction than creating a node
 * and leaving the user to connect the thing they just asked for. Opened without a wire, the rows are
 * plain types.</p>
 *
 * <h3>Browsing is a tree; searching is a flat list</h3>
 * <p>{@link NodeMenuTree} files offers under their categories, so a real library is navigable rather than
 * a thousand-row scroll. Typing flattens it, because a result set is ranked rather than filed — burying
 * three matches under two levels of collapsed folder is exactly what the user typed to avoid.</p>
 *
 * <p><b>A small tree opens itself.</b> Below {@link #setAutoExpandThreshold(int) the threshold} every
 * folder starts expanded, which keeps a six-type library (and a wire drop that yields four ports) behaving
 * as a plain list — folders only appear once there are enough entries for them to be worth the click.</p>
 *
 * <h3>Search matches synonyms, because the library declares them</h3>
 * <p>Typing {@code plus} finds {@code Add}. That cannot be a property of the matcher — no amount of
 * fuzziness knows that a multiply is a "product" — so it is a field on {@link NodeType} and this only
 * has to ask.</p>
 *
 * <h3>A {@code Popover}, so dismissal is already solved</h3>
 * <p>Light dismiss, Escape, focus restore and the top layer all come from {@link Popover}, which is the
 * reason this is list-building rather than a dismissal state machine. Placement comes from
 * {@code showAt} — and nothing here writes {@code left}/{@code top}, because {@code AnchoredPlacement}
 * owns that and a second writer fights it every frame.</p>
 *
 * <h3>Why the menu has a definite height</h3>
 * <p>It used to be content-sized. A {@link TreeView} cannot live in a content-sized box: it is
 * virtualised, its rows are absolutely positioned, and its scroll range comes from the <em>model</em> —
 * so Taffy measures it as empty, {@code getClientHeight()} is 0, and it realises exactly one row. The
 * height here is what gives it a viewport to fill, and it is also what makes {@code height: 0} +
 * {@code flex-grow: 1} the correct idiom for the list again. Unity's Create Node window is likewise a
 * fixed-size resizable panel rather than one that hugs its contents.</p>
 */
public class NodeCreationMenu extends Popover {

    /**
     * This widget's kind.
     *
     * <p>Declared here rather than in a vocabulary class, and declared AT ALL because a subclass
     * inherits its parent's kind unless it is given its own: without this, NodeCreationMenu reports
     * {@code crystalgui:element} (or its supertype's) and every rule the sheets write for
     * {@code nodecreationmenu} matches nothing at all — no background, no border, an unstyled widget that
     * reads as one that was never built.</p>
     */
    public static final Name NAME = Name.of("nodecreationmenu");

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
    /** The mark between two segments. A DRAWN shape, never a character — see
     * {@code NodeMenuTree.categorySegments} for the blank-advance trap that forced it. */
    public static final String CATEGORY_SEPARATOR_CLASS = "__category-separator__";
    /** The {@code ::highlight(...)} name the matched characters are registered under. Styled in CSS, so
     * the tint is a theme's business — see this class's own note on the renderer. */
    public static final String MATCH_HIGHLIGHT = "search-match";

    /**
     * At or below this many entries, every folder starts open.
     *
     * <p>Chosen so that the shapes people actually hit — a small library, and a wire drop that offers a
     * handful of ports — never make you click a folder to see three things. Above it, folders are the
     * point.</p>
     */
    public static final int DEFAULT_AUTO_EXPAND_THRESHOLD = 12;

    private final NodeTypeRegistry library;
    private final UIElement titleBar = new UIElement();
    private final UIText titleLabel = new UIText("Create Node");
    private final SearchField search = new SearchField();
    private final TreeView<NodeMenuTree.Node> tree;
    private final UIText emptyLabel = new UIText("no matching nodes");

    /** The current roots — rebuilt on every keystroke, read by the tree's data source. */
    private List<NodeMenuTree.Node> roots = new ArrayList<>();

    /** No row is highlighted — the browsing state, and what the menu opens in. */
    private static final int NONE_HIGHLIGHTED = -1;

    /** The row the arrows act on. Kept here rather than read from the tree's focused index, because the
     * tree never takes focus — the search box holds it for the menu's whole life. */
    private int highlighted = NONE_HIGHLIGHTED;

    @Getter
    private int autoExpandThreshold = DEFAULT_AUTO_EXPAND_THRESHOLD;

    /** What the menu is currently offering against, or null when it was opened without a wire. */
    @Nullable
    private String filterTypeId;
    private boolean filterFromOutput;
    private TypeCompatibility compatibility = TypeCompatibility.EXACT;

    /**
     * Fires with the chosen entry. A {@link NodeTypeRegistry.Offer} whose {@code port} is null when the
     * menu was opened without a wire — one signal rather than two, because every consumer does the same
     * thing with both: create the node, then connect if there is something to connect.
     */
    public final Signal.Value<NodeTypeRegistry.Offer> onChosen = new Signal.Value<>();

    public NodeCreationMenu(NodeTypeRegistry library) {
        super(NAME);
        this.library = library;
        addClass("nodecreationmenu");

        titleBar.addClass(TITLE_BAR_CLASS);
        titleLabel.addClass(TITLE_CLASS);
        titleLabel.setHitTest(false);
        titleBar.append(titleLabel);
        titleBar.onMouseDown.attachListener((element, event) -> {
            beginMove(event.getPosition().x(), event.getPosition().y());
            // Or the canvas underneath takes it as a press on empty background and starts a marquee —
            // the menu is the graph's own promoted child, so its input still travels through the graph.
            event.stopPropagation();
        }, false, true);

        search.addClass(SEARCH_CLASS);
        search.setPlaceholder("search");
        // IMMEDIATE, not the default ON_COMMIT: the list has to narrow as you type. On commit-only the
        // menu would sit showing everything until Enter, which is not a search box, it is a filter you
        // have to submit.
        // IMMEDIATE update mode now lives inside SearchField — a box that only filters on Enter is
        // not a search box, and that reasoning belongs with the widget rather than with each user of it.
        search.onQueryChanged.connect(this::rebuild);

        tree = new TreeView<>(new TreeDataSource<NodeMenuTree.Node>() {
            @Override
            public List<NodeMenuTree.Node> roots() {
                return roots;
            }

            @Override
            public List<NodeMenuTree.Node> children(NodeMenuTree.Node parent) {
                return parent.children();
            }

            @Override
            public boolean hasChildren(NodeMenuTree.Node item) {
                return !item.children().isEmpty();
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
        // Moving focus into the tree would be the obvious implementation and is the wrong one twice over.
        // It would stop you typing — the whole point of a search menu is narrowing and picking without a
        // round trip — and it would have silently done nothing anyway: a ListView takes no focus policy of
        // its own, and requestFocus refuses a FocusPolicy.NONE element without reporting it. Forwarding
        // keeps one focus owner and one obvious place for the caret.
        search.field().onKeyDown.attachListener((element, event) -> {
            if (!handleListKey(event.getKeyCode())) return;
            event.stopPropagation();
            // Stops TextField's own caret handling for these keys; Left/Right are deliberately NOT taken,
            // because they belong to the text you are editing.
            event.preventDefault();
        }, false, true);
    }

    /**
     * Drags the whole menu by its title bar, Unity's "Create Node" header.
     *
     * <p><b>The drag source is the ROOT, not the title bar</b>, and that is the whole trick. Every
     * {@code DragListener} coordinate is converted through its source's own transform, so sourcing a
     * move-drag from the thing being moved measures the delta in a frame that is itself moving — the
     * engine's pan drag documents exactly this, where it made the view accelerate away from the cursor.
     * The root does not move, so its deltas are the pointer's real travel.</p>
     *
     * <p>The origin comes from {@code resizeOriginLeft()/Top()}, which read the <em>live Taffy inset</em>
     * rather than a remembered field. A field would only know positions this code wrote, so the very
     * first drag of a menu placed by {@code AnchoredPlacement} would teleport it to the corner.</p>
     */
    private void beginMove(float pointerX, float pointerY) {
        var window = document();
        if (window == null) return;
        Box placed = box();
        final float startLeft = placed == null ? 0f : placed.x();
        final float startTop = placed == null ? 0f : placed.y();
        // No payload, no drop targets, no activation threshold: a move must track the first pixel.
        Drag.start(
                window.root(), pointerX, pointerY,
                (mx, my, sx, sy, dx, dy) -> moveTo(startLeft + dx, startTop + dy));
    }

    /** The draggable header, for a theme or a test. */
    public UIElement titleBar() {
        return titleBar;
    }

    /** @see #DEFAULT_AUTO_EXPAND_THRESHOLD */
    public NodeCreationMenu setAutoExpandThreshold(int entries) {
        this.autoExpandThreshold = Math.max(0, entries);
        return this;
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /** Everything in the library — Space, or a right-click on empty canvas. */
    public NodeCreationMenu openAll(float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = null;
        return open(rootX, rootY, invoker);
    }

    /** Filtered to what could <b>receive</b> a wire dragged from an output of {@code sourceTypeId}. */
    public NodeCreationMenu openForOutput(String sourceTypeId, TypeCompatibility rule,
                                          float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = sourceTypeId;
        this.filterFromOutput = true;
        this.compatibility = rule;
        return open(rootX, rootY, invoker);
    }

    /** Filtered to what could <b>feed</b> a wire dragged from an input of {@code targetTypeId}. */
    public NodeCreationMenu openForInput(String targetTypeId, TypeCompatibility rule,
                                         float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = targetTypeId;
        this.filterFromOutput = false;
        this.compatibility = rule;
        return open(rootX, rootY, invoker);
    }

    private NodeCreationMenu open(float rootX, float rootY, @Nullable UIElement invoker) {
        search.setText("");
        tree.collapseAll();
        // Back to the top. The menu is a Popover, so it is the SAME element every time — hiding it does
        // not reset anything, and the scroll offset from the last visit survived: reopening showed the
        // list already a row or two down, with the first category sliced off above the viewport. Read as
        // a rendering glitch rather than as leftover state, because nothing about opening a fresh menu
        // suggests it is a continuation.
        //
        // IMMEDIATE, not the animated setter: this is a reset, not a movement, and smooth-scrolling from
        // wherever the user left off would be a visible slide on open. Before rebuild(), so the offset is
        // already 0 when the new model is measured rather than being clamped against the old content.
        // NULLABLE, and reached on the ordinary path: a menu opened on the frame it was built in has
        // a tree that has never been laid out, so there is no box and no scroll offset to reset --
        // which is exactly the state this reset exists to leave it in.
        Box treeBox = tree.box();
        if (treeBox != null) treeBox.setScroll(0f, 0f);
        rebuild();
        showAt(rootX, rootY, invoker);
        // Focus the box, not the first row: the menu exists to be typed into, and a user who wanted the
        // first row would have clicked it.
        var window = document();
        if (window != null) focusSearch();
        return this;
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** Rebuilt wholesale on every keystroke, and that is safe here in a way it is not in the graph: this
     * list is not under the pointer while it changes — the search box is. */
    private void rebuild() {
        String query = search.getText();
        List<NodeTypeRegistry.Offer> offers = currentOffers(query);

        // A query flattens. See the class note: a result set is ranked, not filed.
        // ranked, NOT flat: `offers` already arrives best-first from the registry, and flat() re-sorted
        // alphabetically — which threw the ranking away and is what made `vec` + Enter create Cross
        // Product. See NodeMenuTree.ranked.
        roots = query.trim().isEmpty()
                ? NodeMenuTree.categorised(offers)
                : NodeMenuTree.ranked(offers);

        tree.refresh();
        if (NodeMenuTree.leafCount(roots) <= autoExpandThreshold) {
            for (NodeMenuTree.Node category : NodeMenuTree.categoriesIn(roots)) {
                tree.setExpanded(category, true);
            }
        }

        // A query highlights its top match; browsing highlights nothing.
        //
        // This is the command-palette rule, and the asymmetry is the point. With a query, Enter should
        // take the best match -- that is what a search box is for, and pre-selecting it saves the arrow
        // press. Without one, the first row is whatever sorted first (usually a CATEGORY), so highlighting
        // it offers "Enter collapses this folder" as the default action, which is not something anyone
        // opened the menu to do.
        boolean searching = !query.trim().isEmpty();
        highlighted = searching ? 0 : NONE_HIGHLIGHTED;
        if (searching && !tree.getModel().isEmpty()) tree.select(0);
        else tree.clearSelection();

        boolean empty = offers.isEmpty();
        // display rather than opacity: an empty-state label that keeps its box would push the tree down by
        // its own height on every keystroke that matched nothing.
        StyleGroup.inlinePipeline(emptyLabel.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        StyleGroup.inlinePipeline(tree.getStyle().getLayoutGroup(),
                l -> l.display(empty ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
    }

    private List<NodeTypeRegistry.Offer> currentOffers(String query) {
        if (filterTypeId == null) {
            List<NodeTypeRegistry.Offer> plain = new ArrayList<>();
            for (NodeType type : library.search(query)) plain.add(new NodeTypeRegistry.Offer(type, null));
            return plain;
        }
        return filterFromOutput
                ? library.offersForOutput(filterTypeId, compatibility, query)
                : library.offersForInput(filterTypeId, compatibility, query);
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
                // from the first row to the last is disorienting when the thing you want is usually first.
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

    /** Enter on a focused row, and what a row's own press delegates to — one path, so a category behaves
     * identically however it was reached. */
    /**
     * Puts the caret back in the search box — the menu's standing invariant, since every key it responds
     * to is handled on that field.
     *
     * <p>The FIELD, not the {@link SearchField} wrapper: that is a row of parts and only the text field
     * can hold a caret. And {@code requestPointerFocus}, not {@code requestFocus}: this is always
     * pointer-driven, and the programmatic call is the one that rings {@code :focus-visible} — a focus
     * ring drawn around the search box on every click of a folder is exactly the noise that
     * pseudo-class exists to avoid.</p>
     */
    private void focusSearch() {
        UIDocument window = document();
        if (window != null) window.focus().requestPointerFocus(search.field());
    }

    private void activateRow(int index) {
        TreeRow<NodeMenuTree.Node> row = tree.rowAt(index);
        if (row == null) return;
        if (row.item().isCategory()) {
            tree.toggleExpanded(row.item());
            // Re-assert the highlight. Expanding rebuilds the flattened model wholesale, and ListView
            // drops any selection a model change invalidated -- refresh() clears before it re-adds, so
            // EVERY index goes with it. Without this, opening a folder unhighlights the very row you are
            // standing on: it reads as the row losing focus, and the next arrow press starts from the top.
            //
            // The folder keeps its own index across the toggle (only rows BELOW it shift), so this lands
            // back on the same row whether it just opened or closed.
            highlight(index);
            return;
        }
        NodeTypeRegistry.Offer offer = row.item().offer();
        if (offer == null) return;
        onChosen.emit(offer);
        hide();
    }

    /**
     * Row template and binding.
     *
     * <p>The twisty is an element rather than a CSS marker because there are no pseudo-elements in this
     * engine; {@code TreeView} still supplies the {@code __expanded__}/{@code __collapsed__}/{@code __leaf__}
     * classes on the row, so a theme decides what it looks like and this only decides that it exists.</p>
     */
    private final class EntryRenderer implements TreeRenderer<NodeMenuTree.Node> {

        @Override
        public UIElement createTemplate() {
            EntryRow row = new EntryRow();
            // Listeners belong in the template, never in bind — an element is recycled across rows, so a
            // listener attached per bind would accumulate one per row it ever displayed. It therefore
            // cannot capture an index and has to ask the tree which row it is currently showing.
            row.onMouseDown.attachListener((element, event) -> {
                int index = tree.indexOfRowElement(element);
                if (index >= 0) activateRow(index);
                // Put focus back in the search box. A press BLURS whatever was focused and re-focuses
                // only if what it hit is click-focusable — a row is a plain element, so clicking one
                // (opening a folder, most obviously) left the menu with NO focused element at all, and
                // the arrow/Enter handling hangs off the search field's own key events. The menu looked
                // alive and had gone completely deaf.
                //
                // Safe to do during dispatch: UIInputHandler.emitMouseDown settles focus BEFORE it sends
                // the event, so nothing undoes this afterwards. Skipped when the press chose a node,
                // because activateRow has already hidden the menu and restoring focus is the Popover's
                // job from there.
                if (isOpen()) focusSearch();
                event.stopPropagation();
            }, false, true);
            return row;
        }

        @Override
        public void bind(NodeMenuTree.Node item, TreeRow<NodeMenuTree.Node> row, int index, UIElement template) {
            EntryRow entry = (EntryRow) template;
            template.removeClass(CATEGORY_CLASS);
            if (item.isCategory()) template.addClass(CATEGORY_CLASS);
            // No Java decision needed here any more: the twisty's appearance (chevron-right, rotated
            // to chevron-down when expanded, or blank for a leaf) is driven entirely by the
            // __expanded__/__collapsed__/__leaf__ classes TreeView already applies to `template` — see
            // default.css's nodecreationmenu .__twisty__ rules.
            entry.label.setText(item.label());

            // The category suffix, and the match tint. BOTH are recomputed here on EVERY bind rather
            // than stored on the row, because TreeView recycles row elements — its own note says a
            // realised row "represents a different row every time it is recycled". Anything captured
            // once would be correct until the first scroll and then wear another result's ranges, the
            // same trap the editor's pooled gutter arrows already document.
            //
            // Matching against the string being DRAWN (rather than reusing a range computed upstream)
            // is what keeps the tint aligned: the category is displayed title-cased with a different
            // separator, so offsets taken from the raw `math/vector` would creep sideways.
            SearchQuery query = SearchQuery.of(search.getText());
            List<String> segments = item.isCategory() || item.offer() == null || query.isEmpty()
                    ? List.of() : NodeMenuTree.categorySegments(item.offer().type().category());
            entry.setCategory(segments);

            highlight(entry.label, query, item.label(), SearchMatch.FIELD_PRIMARY);
            // Each segment matched on its own, so the tint needs no offset arithmetic — see
            // NodeMenuTree.categorySegments.
            for (int i = 0; i < EntryRow.MAX_CATEGORY_SEGMENTS; i++) {
                UIText segment = entry.categorySegments[i];
                highlight(segment, query, i < segments.size() ? segments.get(i) : "",
                        SearchMatch.FIELD_CONTEXT);
            }
        }

        /**
         * Registers the matched ranges under {@link #MATCH_HIGHLIGHT}, or clears them.
         *
         * <p><b>Always calls {@code set}, including with nothing.</b> Clearing only when a match exists
         * would leave the previous occupant's ranges on a recycled row — visible as a tint that drifts to
         * unrelated rows as you scroll, which reads as a rendering bug rather than a stale-data one.</p>
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

    /** A typed template, so {@link EntryRenderer#bind} reaches its parts by name. Indexing into
     * {@code getChildren()} would work today and break silently the first time a theme or a later feature
     * inserted anything. */
    private static final class EntryRow extends UIElement {

        /** Deepest category this can draw. Three covers every shipped path ({@code procedural/shape/…});
         * anything deeper collapses into the last segment rather than being dropped. */
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

            // Built ONCE and shown/hidden per bind, never rebuilt. TreeView recycles rows, so creating
            // elements in bind would register and drop Taffy nodes on every keystroke — churn, for a
            // structure that is at most three labels and two marks.
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
                // The last slot absorbs anything deeper, so an unexpectedly nested category loses its
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

    // ── Accessors, for a theme or a test ────────────────────────────────────

    public TextField searchField() {
        return search.field();
    }

    /** The whole search box — icon, field and clear — for a theme or a test that needs the wrapper
     * rather than the text field inside it. */
    public SearchField searchBox() {
        return search;
    }

    public TreeView<NodeMenuTree.Node> treeView() {
        return tree;
    }

    /**
     * The rows currently <b>on screen</b>, in order — the flattened tree, which is what a user can see
     * and arrow through.
     *
     * <p>Deliberately the model rather than the realised elements: the tree is virtualised, so the
     * elements are a window over this and asserting on them measures the viewport rather than the menu.
     * A test that wants "what could I click" wants this.</p>
     */
    public List<NodeMenuTree.Node> visibleEntries() {
        List<NodeMenuTree.Node> visible = new ArrayList<>();
        for (TreeRow<NodeMenuTree.Node> row : tree.visibleRows()) visible.add(row.item());
        return visible;
    }

    /** Just the choosable rows currently on screen — categories dropped. */
    public List<NodeMenuTree.Node> visibleOffers() {
        List<NodeMenuTree.Node> offers = new ArrayList<>();
        for (NodeMenuTree.Node node : visibleEntries()) {
            if (!node.isCategory()) offers.add(node);
        }
        return offers;
    }

    /** Every offer the current query admits, folders or not — what the menu would show fully expanded. */
    public List<NodeMenuTree.Node> allOffers() {
        return NodeMenuTree.leavesIn(roots);
    }

    /** The realised row elements, top to bottom. A window over {@link #visibleEntries()}, and only
     * meaningful once the menu has been laid out. */
    public List<UIElement> entries() {
        List<UIElement> rows = new ArrayList<>();
        for (int index = 0; index < tree.getModel().size(); index++) {
            UIElement row = tree.realisedRows().get(index);
            if (row != null) rows.add(row);
        }
        return rows;
    }
}
