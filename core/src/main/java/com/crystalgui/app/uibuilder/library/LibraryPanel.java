package com.crystalgui.app.uibuilder.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.document.NewNode;
import com.crystalgui.app.uibuilder.glyph.GlyphView;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.core.collection.list.ItemSizeStrategy;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.Input;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.composite.SearchTree;
import com.crystalgui.widget.text.UIText;

/**
 * Every placeable kind as a card, filed in a searchable tree of categories — the builder's palette.
 *
 * <pre>{@code
 * LibraryPanel library = new LibraryPanel(LibraryCatalog.current());
 * library.onPlace.connect(entry -> builder.insert(entry.build()));   // a double-click
 * library.setRows(true);                                             // compact rows instead of cards
 * }</pre>
 *
 * <p>A category's kinds flow as strips of cards sized from the panel's width; a search flattens to a ranked run
 * of strips. Cards draw their kind live, wearing the window's sheets and no panel rule — see {@link PreviewCard}.</p>
 */
public final class LibraryPanel extends UIElement implements DataProvider {

    public static final Name NAME = Name.of("librarypanel");

    public static final String CONTENT_CLASS = "__library-content__";
    public static final String ROW_CLASS = "__library-row__";
    public static final String STRIP_CLASS = "__card-strip__";
    public static final String FOLDER_CLASS = "__library-folder__";
    public static final String ITEM_CLASS = "__library-item__";
    public static final String TWISTY_CLASS = "__twisty__";
    public static final String LABEL_CLASS = "__label__";

    /** This panel, for a command that acts on one. */
    public static final DataKey<LibraryPanel> LIBRARY = DataKey.create("uibuilder.library", LibraryPanel.class);

    /** A folder or compact row's height. The view positions rows, so the heights are the view's to state. */
    static final float ROW_HEIGHT = 20f;

    /** A strip's height before a card has been measured. */
    private static final float DEFAULT_STRIP_HEIGHT = 64f;

    /** Space a strip keeps below its cards. */
    private static final float STRIP_SLACK = 4f;

    /** A row of the tree: a folder, a strip of cards, or one kind in compact mode. Equal by key, so folds survive a refresh. */
    public sealed interface Row permits Folder, Strip, Item {
        String key();
    }

    public record Folder(String key, String label, List<Row> children) implements Row {
        @Override
        public boolean equals(Object other) {
            return other instanceof Folder folder && folder.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    public record Strip(String key, List<LibraryCatalog.Entry> entries) implements Row {
        @Override
        public boolean equals(Object other) {
            return other instanceof Strip strip && strip.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    public record Item(String key, LibraryCatalog.Entry entry) implements Row {
        @Override
        public boolean equals(Object other) {
            return other instanceof Item item && item.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /** A card was double-clicked. */
    public final Signal.Value<LibraryCatalog.Entry> onPlace = new Signal.Value<>();

    /** The selected card changed; null when none is. */
    public final Signal.Value<LibraryCatalog.Entry> onSelect = new Signal.Value<>();

    private LibraryCatalog catalog;
    private final SearchTree<Row, LibraryCatalog.Entry> search = new SearchTree<>();
    private final UIElement content = new UIElement();
    private final LibraryDetail detail = new LibraryDetail();

    /** The label a dragged card leaves behind the pointer: a tree row's, since it lands as one in the Hierarchy. */
    private final DragGhost ghost = new DragGhost();
    private final Map<UIElement, RowView> views = new HashMap<>();

    private boolean rows;
    private int perStrip = 1;
    private float stripHeight = DEFAULT_STRIP_HEIGHT;
    private boolean opened;

    @Nullable
    private LibraryCatalog.Entry selected;

    public LibraryPanel(LibraryCatalog catalog) {
        super(NAME);
        this.catalog = catalog;
        content.addClass(CONTENT_CLASS);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f).flexDirection(FlexDirection.COLUMN));
        StyleGroup.defaultPipeline(content.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexBasis(0f).flexGrow(1f).flexDirection(FlexDirection.COLUMN));

        search.setRows(new SearchTree.Rows<>() {
            @Override
            public List<Row> roots(String query) {
                return rowsFor(LibraryPanel.this.catalog.roots(query), "");
            }

            @Override
            public List<Row> children(Row node) {
                return node instanceof Folder folder ? folder.children() : List.of();
            }

            @Override
            public String label(Row node) {
                if (node instanceof Folder folder) return folder.label();
                if (node instanceof Item item) return item.entry().label();
                return "";
            }

            @Override
            public boolean isCategory(Row node) {
                return node instanceof Folder;
            }

            @Override
            @Nullable
            public LibraryCatalog.Entry payload(Row node) {
                if (node instanceof Item item) return item.entry();
                return node instanceof Strip strip && strip.entries().size() == 1 ? strip.entries().get(0) : null;
            }
        });
        // FOLDS ARE THE PANEL'S: it opens Common once, and a refresh at a new width must not re-open what was closed.
        search.setAutoExpandThreshold(0);
        search.onChosen.connect(onPlace::emit);
        search.searchBox().setPlaceholder("Search elements");
        tree().setRenderer(new Renderer());
        tree().setSizeStrategy(new Heights());

        content.append(search);
        append(content);
        append(detail);
        onSelect.connect(detail::show);
        ghost.addClass("__row-ghost__");
        // IN THE TREE BEFORE A DRAG CAN PROMOTE IT. @see DragGhost
        ghost.parkIn(this);

        // THE LIST'S SELECTION IS THE COMPACT ROWS' SELECTION, so the arrows move the detail strip as a click does.
        whileConnected(() -> tree().onSelectionChanged.connect(indices -> {
            if (indices == null || indices.size() != 1) return;
            TreeRow<Row> at = tree().rowAt(indices.iterator().next());
            if (at != null && at.item() instanceof Item item) select(item.entry());
        }));
        onConnected(() -> {
            UIDocument window = document();
            if (window == null) return;
            if (!opened) {
                opened = true;
                search.refresh();
                openCommon();
            }
            window.animation().every(this, delta -> {
                PreviewStyles.of(window).sync();
                return true;
            });
            window.animation().afterLayout(this, delta -> {
                measure();
                return true;
            });
        });
    }

    public SearchTree<Row, LibraryCatalog.Entry> search() {
        return search;
    }

    public TreeView<Row> tree() {
        return search.treeView();
    }

    /** The selected kind, described at the panel's foot. */
    public LibraryDetail detail() {
        return detail;
    }

    /** Shows compact rows instead of cards. */
    public void setRows(boolean compact) {
        if (compact == rows) return;
        rows = compact;
        if (compact) addClass("__rows__");
        else removeClass("__rows__");
        search.refresh();
    }

    public boolean isRows() {
        return rows;
    }

    /** How many cards a strip holds at the current width. */
    public int cardsPerStrip() {
        return perStrip;
    }

    /** Re-reads the catalog — after a mod registers a kind at runtime. */
    public void setCatalog(LibraryCatalog catalog) {
        this.catalog = catalog;
        search.refresh();
    }

    @Nullable
    public LibraryCatalog.Entry selected() {
        return selected;
    }

    /** Selects the card for {@code entry}, or clears the selection. */
    public void select(@Nullable LibraryCatalog.Entry entry) {
        if (Objects.equals(entry == null ? null : entry.kind(), selected == null ? null : selected.kind())) return;
        selected = entry;
        for (RowView view : views.values()) view.markSelection();
        onSelect.emit(entry);
    }

    /** The cards realised on screen, in no particular order. For a test. */
    public List<PreviewCard> realisedCards() {
        List<PreviewCard> out = new ArrayList<>();
        for (Map.Entry<Integer, UIElement> realised : tree().realisedRows().entrySet()) {
            RowView view = views.get(realised.getValue());
            if (view == null) continue;
            out.addAll(view.cards.subList(0, view.used));
        }
        return out;
    }

    @Override
    public Object getData(DataKey<?> key) {
        return key == LIBRARY ? this : null;
    }

    private void openCommon() {
        for (Row row : search.treeView().roots()) {
            if (row instanceof Folder folder && folder.label().equals(LibraryGroups.COMMON.label())) {
                tree().setExpanded(folder, true);
            }
        }
    }

    // ── Building rows ───────────────────────────────────────────────────────

    private List<Row> rowsFor(List<LibraryCatalog.Node> nodes, String path) {
        List<Row> out = new ArrayList<>();
        List<LibraryCatalog.Entry> run = new ArrayList<>();
        for (LibraryCatalog.Node node : nodes) {
            if (node.isCategory()) {
                flush(run, path, out);
                String key = path + "/" + node.label();
                out.add(new Folder(key, node.label(), rowsFor(node.children(), key)));
            } else {
                run.add(node.entry());
            }
        }
        flush(run, path, out);
        return out;
    }

    private void flush(List<LibraryCatalog.Entry> run, String path, List<Row> out) {
        if (run.isEmpty()) return;
        if (rows) {
            for (LibraryCatalog.Entry entry : run) out.add(new Item(path + "|" + entry.kind(), entry));
        } else {
            for (int start = 0; start < run.size(); start += perStrip) {
                List<LibraryCatalog.Entry> strip = List.copyOf(run.subList(start, Math.min(run.size(), start + perStrip)));
                out.add(new Strip(path + "#" + start / perStrip, strip));
            }
        }
        run.clear();
    }

    /**
     * Re-flows the strips when a card's pitch or the panel's width changed, and re-sizes them to a card's height.
     *
     * <p>Measured rather than declared: a card's width is the sheet's, and so is the gap between cards.</p>
     */
    private void measure() {
        if (rows) return;
        for (Map.Entry<Integer, UIElement> realised : tree().realisedRows().entrySet()) {
            RowView view = views.get(realised.getValue());
            Box stripBox = realised.getValue().box();
            if (view == null || stripBox == null || !view.isStrip()) continue;
            Box first = view.cards.isEmpty() ? null : view.cards.get(0).box();
            if (first == null || first.width() < 1f) continue;
            Box second = view.used > 1 ? view.cards.get(1).box() : null;
            float gap = second == null ? 0f : second.x() - first.x() - first.width();
            float pitch = first.width() + Math.max(0f, gap);
            // THE ROW, not the strip inside it: the strip is as wide as the cards it holds, so it would only ever fit them.
            float available = stripBox.contentBoxWidth();
            int fits = Math.max(1, (int) Math.floor((available + Math.max(0f, gap)) / pitch));
            float height = first.height() + STRIP_SLACK;
            boolean reflow = fits != perStrip;
            boolean resize = Math.abs(height - stripHeight) > 0.5f;
            if (reflow) perStrip = fits;
            if (resize) stripHeight = height;
            if (reflow) search.refresh();
            else if (resize) tree().setSizeStrategy(new Heights());
            return;
        }
    }

    /** A folder or a row is {@link #ROW_HEIGHT}; a strip is a card's height. */
    private final class Heights implements ItemSizeStrategy {

        @Override
        public float sizeOf(int index) {
            TreeRow<Row> row = tree().rowAt(index);
            return row != null && row.item() instanceof Strip ? stripHeight : ROW_HEIGHT;
        }

        @Override
        public float totalSize(int count) {
            return offsetOf(count);
        }

        @Override
        public float offsetOf(int index) {
            float offset = 0f;
            for (int i = 0; i < index; i++) offset += sizeOf(i);
            return offset;
        }

        @Override
        public int indexAt(float offset, int count) {
            float bottom = 0f;
            for (int i = 0; i < count; i++) {
                bottom += sizeOf(i);
                if (bottom > offset) return i;
            }
            return count;
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    /** One recycled row element and its parts; which parts show depends on what the row stands for. */
    private final class RowView {

        final UIElement row = new UIElement();
        final UIElement twisty = new UIElement();
        final GlyphView glyph = new GlyphView(null);
        final UIText label = new UIText("");
        final UIElement strip = new UIElement();
        final List<PreviewCard> cards = new ArrayList<>();

        /** How many of {@link #cards} the bound strip shows; the rest are hidden spares. */
        int used;

        @Nullable
        Row bound;

        RowView() {
            row.addClass(ROW_CLASS);
            twisty.addClass(TWISTY_CLASS);
            label.addClass(LABEL_CLASS);
            label.setHitTest(false);
            strip.addClass(STRIP_CLASS);
            // ITS OWN HIT TARGET, as the Hierarchy's: a single click folds, and the press never reaches the list.
            twisty.onMouseDown.attachListener((element, event) -> {
                int index = tree().indexOfRowElement(row);
                TreeRow<Row> at = index < 0 ? null : tree().rowAt(index);
                if (at == null || !at.expandable()) return;
                event.stopPropagation();
                event.preventDefault();
                tree().requestToggleAt(index);
            }, false, false);
            row.onMouseDown.attachListener((element, event) -> {
                if (bound instanceof Item item) press(item.entry(), row, event);
            }, false, true);
            row.append(twisty, glyph.element(), label, strip);
        }

        boolean isStrip() {
            return bound instanceof Strip;
        }

        void bind(Row item) {
            bound = item;
            boolean folder = item instanceof Folder;
            boolean compact = item instanceof Item;
            toggle(row, FOLDER_CLASS, folder);
            toggle(row, ITEM_CLASS, compact);
            show(twisty, !(item instanceof Strip));
            show(label, !(item instanceof Strip));
            show(glyph.element(), compact);
            show(strip, item instanceof Strip);
            if (folder) label.setText(((Folder) item).label());
            if (compact) {
                LibraryCatalog.Entry entry = ((Item) item).entry();
                label.setText(entry.label());
                glyph.showKind(entry.kind());
            }
            if (item instanceof Strip run) bindStrip(run);
            else used = 0;
            markSelection();
        }

        private void bindStrip(Strip run) {
            UIDocument window = document();
            while (cards.size() < run.entries().size() && window != null) {
                PreviewCard card = new PreviewCard(PreviewStyles.of(window).group());
                card.onMouseDown.attachListener((element, event) -> {
                    LibraryCatalog.Entry entry = ((PreviewCard) element).entry();
                    if (entry != null) press(entry, element, event);
                }, false, true);
                cards.add(card);
                strip.append(card);
            }
            used = Math.min(cards.size(), run.entries().size());
            for (int i = 0; i < cards.size(); i++) {
                boolean shown = i < used;
                if (shown) cards.get(i).show(run.entries().get(i));
                show(cards.get(i), shown);
            }
        }

        /** A card marks itself; a compact row is marked by the list, whose selection drives {@link #select}. */
        void markSelection() {
            for (PreviewCard card : cards) {
                toggle(card, PreviewCard.SELECTED_CLASS, card.entry() != null && sameKind(card.entry(), selected));
            }
        }
    }

    /** A click selects, a double-click places, and a press that travels drags a {@link NewNode} to a drop target. */
    private void press(LibraryCatalog.Entry entry, UIElement source, MouseEvent.Down event) {
        // NEVER FROM THE KEYBOARD: a drag armed by a synthesized press can never be released.
        if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || event.getDetail() == Input.KEYBOARD_DETAIL) return;
        select(entry);
        if (event.getDetail() >= 2) {
            onPlace.emit(entry);
            return;
        }
        UIDocument window = document();
        if (window == null) return;
        ghost.follow(window, KindGlyphs.ofKind(entry.kind()).icon(), entry.label());
        Drag.start(source, event.getPosition().x(), event.getPosition().y(), CgMouseCodes.LEFT_BUTTON,
                new NewNode(entry.label(), entry::build), Drag.DEFAULT_THRESHOLD_PX, (x, y, sx, sy, dx, dy) -> { });
    }

    private final class Renderer implements TreeRenderer<Row> {

        @Override
        public UIElement createTemplate() {
            RowView view = new RowView();
            views.put(view.row, view);
            return view.row;
        }

        @Override
        public void bind(Row item, TreeRow<Row> row, int index, UIElement template) {
            RowView view = views.get(template);
            if (view != null) view.bind(item);
        }
    }

    private static boolean sameKind(@Nullable LibraryCatalog.Entry a, @Nullable LibraryCatalog.Entry b) {
        return a != null && b != null && a.kind().equals(b.kind());
    }

    private static void toggle(UIElement element, String className, boolean on) {
        if (on == element.hasClass(className)) return;
        if (on) element.addClass(className);
        else element.removeClass(className);
    }

    private static void show(UIElement element, boolean visible) {
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** None: the search tree and its rows are rebuilt by the constructor. */
    @Override
    public List<UIElement> describedChildren() {
        return List.of();
    }
}
