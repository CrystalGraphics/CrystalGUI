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
import com.crystalgui.core.collection.list.ItemSizeStrategy;
import com.crystalgui.core.collection.list.VariableHeightStrategy;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.Input;
import com.crystalgui.widget.dnd.DragGhost;
import com.crystalgui.widget.overlay.ContextMenu;
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

    /** The kind or starter a card or a compact row shows, for a command run from its menu. */
    public static final DataKey<LibraryCatalog.Entry> ENTRY =
            DataKey.create("uibuilder.library.entry", LibraryCatalog.Entry.class);

    /** The group a row lists or sits in, for a command run from its menu. */
    public static final DataKey<LibraryCatalog.Group> GROUP =
            DataKey.create("uibuilder.library.group", LibraryCatalog.Group.class);

    /** A card's or a compact row's menu. */
    public static final MenuId CARD_MENU = MenuId.of("uibuilder/library/card");

    /** A user's group's menu. */
    public static final MenuId GROUP_MENU = MenuId.of("uibuilder/library/group");

    /** The menu anywhere else in the panel. */
    public static final MenuId PANEL_MENU = MenuId.of("uibuilder/library/panel");

    /** On a user's group's row while a card dragged over it would land there. */
    public static final String DROP_CLASS = "__drop-target__";

    /** A folder or compact row's height. The view positions rows, so the heights are the view's to state. */
    static final float ROW_HEIGHT = 20f;

    /** A strip's height before a card has been measured. */
    private static final float DEFAULT_STRIP_HEIGHT = 64f;

    /** Space a strip keeps below its cards. */
    private static final float STRIP_SLACK = 4f;

    /** Rows kept realised beyond the viewport each side: every row of the shipped catalog, at any width. */
    private static final int REALISED_ROWS = 64;

    /** A row of the tree: a folder, a strip of cards, or one kind in compact mode. Equal by key, so folds survive a refresh. */
    public sealed interface Row permits Folder, Strip, Item {
        String key();
    }

    /** @param group the group this folder lists, or null for a category */
    public record Folder(String key, String label, List<Row> children, @Nullable LibraryCatalog.Group group)
            implements Row {
        @Override
        public boolean equals(Object other) {
            return other instanceof Folder folder && folder.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /**
     * @param folder the category it lists, its cards' home: a kind filed under two categories has a card in each
     * @param group the group it lists, or null
     */
    public record Strip(String key, String folder, List<LibraryCatalog.Entry> entries,
                        @Nullable LibraryCatalog.Group group) implements Row {
        @Override
        public boolean equals(Object other) {
            return other instanceof Strip strip && strip.key.equals(key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }

    /** @param group the group it is listed in, or null */
    public record Item(String key, LibraryCatalog.Entry entry, @Nullable LibraryCatalog.Group group) implements Row {
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

    /** What the user made of the Library; a session's own until a tool window hands over the stored one. */
    private UserLibrary user = UserLibrary.in(null);

    @Nullable
    private Connection userConnection;

    /** The user's groups as last listed, so a change to the view alone does not re-read the registry. */
    private List<LibraryCatalog.Group> listedGroups = List.of();
    private final SearchTree<Row, LibraryCatalog.Entry> search = new SearchTree<>();
    private final UIElement content = new UIElement();
    private final LibraryDetail detail = new LibraryDetail();

    /** The label a dragged card leaves behind the pointer: a tree row's, since it lands as one in the Hierarchy. */
    private final DragGhost ghost = new DragGhost();
    private final Map<UIElement, RowView> views = new HashMap<>();

    /**
     * ONE CARD PER KIND PER CATEGORY for the panel's life, moved into whichever of that category's strips shows it.
     * A card bound to a strip SLOT rebuilt its sample whenever a re-flow or a keystroke shifted another kind into
     * the slot, and a move within the window is not re-matched at all. Per category, because Common lists kinds
     * that are filed elsewhere too, and one element can only be in one strip.
     */
    private final Map<String, PreviewCard> cards = new HashMap<>();

    /** Where a card no strip shows waits, hidden and still in the window: taking it out would re-match it on return. */
    private final UIElement parked = new UIElement();

    private final Heights heights = new Heights();

    private boolean rows;
    private int perStrip = 1;
    private float stripHeight = DEFAULT_STRIP_HEIGHT;
    private boolean opened;

    /** Set after layout when the strips no longer fit, or a card's height changed; acted on before the next frame's style. @see #measure */
    private boolean reflowPending;
    private boolean resizePending;

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
                return rowsFor(LibraryPanel.this.catalog.roots(query), "", null);
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
        tree().setSizeStrategy(heights);
        tree().getModel().onChange(change -> heights.invalidate());
        // KEPT, NOT RECYCLED: a strip scrolling back in re-creates its cards' boxes and lays their samples out again.
        // Measured against the default two: a scroll's p90 7.5ms against 8.6, for about 2ms more on a re-flow frame.
        // Bounded all the same, for a catalog an addon has made long.
        tree().setOverscan(REALISED_ROWS);

        content.append(search);
        append(content);
        append(detail);
        StyleGroup.defaultPipeline(parked.getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));
        append(parked);
        onSelect.connect(detail::show);
        ghost.addClass("__row-ghost__");
        // IN THE TREE BEFORE A DRAG CAN PROMOTE IT. @see DragGhost
        ghost.parkIn(this);

        // A MENU BY WHAT WAS PRESSED: a card's, a user's group's, or the panel's.
        tree().suppressDefaultContextMenu();
        ContextMenu.attach(tree(), CommandRegistry.global(), this::menuFor);

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
                if (!foldsRestored) openCommon();
            }
            window.animation().every(this, delta -> {
                PreviewStyles.of(window).sync();
                if (reflowPending) {
                    reflowPending = false;
                    resizePending = false;
                    search.refresh();
                } else if (resizePending) {
                    resizePending = false;
                    heights.invalidate();
                    tree().setSizeStrategy(heights);
                }
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

    /**
     * Keeps {@code library}'s groups and view: lists its groups after the shipped ones, shows cards or rows as it
     * says, and writes the rows toggle back to it.
     */
    public LibraryPanel useLibrary(UserLibrary library) {
        if (userConnection != null) userConnection.disconnect();
        user = library;
        userConnection = library.onChanged.connect(this::userChanged);
        listedGroups = null;
        userChanged();
        return this;
    }

    /** What the user made of the Library. */
    public UserLibrary userLibrary() {
        return user;
    }

    private void userChanged() {
        applyRows(user.isRows());
        if (!user.groups().equals(listedGroups)) {
            listedGroups = user.groups();
            setCatalog(LibraryCatalog.current(listedGroups));
        }
    }

    /** Shows compact rows instead of cards, and remembers it for the user. */
    public void setRows(boolean compact) {
        applyRows(compact);
        user.setRows(compact);
    }

    private void applyRows(boolean compact) {
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

    /** The group the selected card was picked in, or null: a kind in several groups has a card in each. */
    @Nullable
    public LibraryCatalog.Group selectedGroup() {
        return selectedGroup;
    }

    @Nullable
    private LibraryCatalog.Group selectedGroup;

    /** Remembers the group {@code picked} — a card or a compact row — sits in. */
    private void pickedIn(UIElement picked) {
        for (UIElement at = picked; at != null && at != tree(); at = at.parentElement()) {
            RowView view = views.get(at);
            if (view == null) continue;
            selectedGroup = (LibraryCatalog.Group) view.dataFor(GROUP);
            return;
        }
        selectedGroup = null;
    }

    /** Delete takes the selected card out of the user's group it was picked in. */
    @Override
    protected void bindKeys() {
        super.bindKeys();
        keymap().bind("Delete", LibraryActions.REMOVE_FROM_GROUP);
        keymap().bind("Backspace", LibraryActions.REMOVE_FROM_GROUP);
    }

    /** Selects the card for {@code entry}, or clears the selection. */
    public void select(@Nullable LibraryCatalog.Entry entry) {
        if (Objects.equals(entry == null ? null : entry.id(), selected == null ? null : selected.id())) return;
        selected = entry;
        for (PreviewCard card : cards.values()) {
            toggle(card, PreviewCard.SELECTED_CLASS, same(card.entry(), selected));
        }
        onSelect.emit(entry);
    }

    /** The cards realised on screen, in no particular order. For a test. */
    public List<PreviewCard> realisedCards() {
        List<PreviewCard> out = new ArrayList<>();
        for (Map.Entry<Integer, UIElement> realised : tree().realisedRows().entrySet()) {
            RowView view = views.get(realised.getValue());
            if (view != null && view.isStrip()) out.addAll(view.cards());
        }
        return out;
    }

    @Override
    public Object getData(DataKey<?> key) {
        return key == LIBRARY ? this : null;
    }

    /** Whether folds were restored, which replaces opening Common on a first run. */
    private boolean foldsRestored;

    /**
     * The folders open now, by their keys — the same across a refresh and a relaunch, for a host to keep.
     *
     * <pre>{@code
     * List<String> open = panel.expandedFolders();   // kept by whoever owns this Library
     * other.restoreExpanded(open);                    // a later panel, over the same catalog
     * }</pre>
     */
    public List<String> expandedFolders() {
        List<String> out = new ArrayList<>();
        for (Row row : tree().expandedItems()) {
            if (row instanceof Folder folder) out.add(folder.key());
        }
        return out;
    }

    /**
     * Opens exactly the folders {@code keys} name, and no others. A folder not listed yet — a user's group read in
     * later — opens when it is, since a folder is its key. Replaces opening Common on a first run.
     */
    public void restoreExpanded(List<String> keys) {
        foldsRestored = true;
        List<Row> folders = new ArrayList<>(keys.size());
        for (String key : keys) folders.add(new Folder(key, "", List.of(), null));
        tree().setExpandedItems(folders);
    }

    private void openCommon() {
        for (Row row : search.treeView().roots()) {
            if (row instanceof Folder folder && folder.label().equals(LibraryGroups.COMMON.label())) {
                tree().setExpanded(folder, true);
            }
        }
    }

    // ── Building rows ───────────────────────────────────────────────────────

    private List<Row> rowsFor(List<LibraryCatalog.Node> nodes, String path, @Nullable LibraryCatalog.Group group) {
        List<Row> out = new ArrayList<>();
        List<LibraryCatalog.Entry> run = new ArrayList<>();
        for (LibraryCatalog.Node node : nodes) {
            if (node.isCategory()) {
                flush(run, path, group, out);
                // A GROUP'S KEY IS ITS OWN, so a user's group named after a category is still a folder of its own.
                String key = node.group() != null ? "group:" + node.group().label() : path + "/" + node.label();
                out.add(new Folder(key, node.label(), rowsFor(node.children(), key, node.group()), node.group()));
            } else {
                run.add(node.entry());
            }
        }
        flush(run, path, group, out);
        return out;
    }

    private void flush(List<LibraryCatalog.Entry> run, String path, @Nullable LibraryCatalog.Group group,
                       List<Row> out) {
        if (run.isEmpty()) return;
        if (rows) {
            for (LibraryCatalog.Entry entry : run) out.add(new Item(path + "|" + entry.id(), entry, group));
        } else {
            for (int start = 0; start < run.size(); start += perStrip) {
                List<LibraryCatalog.Entry> strip = List.copyOf(run.subList(start, Math.min(run.size(), start + perStrip)));
                out.add(new Strip(path + "#" + start / perStrip, path, strip, group));
            }
        }
        run.clear();
    }

    /**
     * Re-flows the strips when a card's pitch or the panel's width changed, and re-sizes them to a card's height.
     *
     * <p>Measured rather than declared: a card's width is the sheet's, and so is the gap between cards.</p>
     *
     * <p>Both only flag themselves: rebuilding the rows here, after layout, moved every card and made the frame lay
     * itself out twice. The next frame's hook acts on them before its style and layout run.</p>
     */
    private void measure() {
        if (rows) return;
        for (Map.Entry<Integer, UIElement> realised : tree().realisedRows().entrySet()) {
            RowView view = views.get(realised.getValue());
            Box stripBox = realised.getValue().box();
            if (view == null || stripBox == null || !view.isStrip()) continue;
            List<PreviewCard> strip = view.cards();
            Box first = strip.isEmpty() ? null : strip.get(0).box();
            if (first == null || first.width() < 1f) continue;
            Box second = strip.size() > 1 ? strip.get(1).box() : null;
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
            if (reflow) reflowPending = true;
            else if (resize) resizePending = true;
            return;
        }
    }

    /**
     * A folder or a row is {@link #ROW_HEIGHT}; a strip is a card's height. Prefix sums over the visible rows,
     * re-read from the tree on the first query after its rows or the strip height changed.
     */
    private final class Heights implements ItemSizeStrategy {

        private final VariableHeightStrategy sizes = new VariableHeightStrategy(ROW_HEIGHT);
        private boolean stale = true;

        void invalidate() {
            stale = true;
        }

        private VariableHeightStrategy sizes() {
            if (!stale) return sizes;
            stale = false;
            int count = tree().getModel().size();
            sizes.setCount(count);
            for (int i = 0; i < count; i++) {
                TreeRow<Row> row = tree().rowAt(i);
                sizes.setSize(i, row != null && row.item() instanceof Strip ? stripHeight : ROW_HEIGHT);
            }
            return sizes;
        }

        @Override
        public float sizeOf(int index) {
            return sizes().sizeOf(index);
        }

        @Override
        public float totalSize(int count) {
            return sizes().totalSize(count);
        }

        @Override
        public float offsetOf(int index) {
            return sizes().offsetOf(index);
        }

        @Override
        public int indexAt(float offset, int count) {
            return sizes().indexAt(offset, count);
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    /** One recycled row element and its parts; which parts show depends on what the row stands for. */
    private final class RowView {

        final UIElement row = new RowElement(this);
        final UIElement twisty = new UIElement();
        final GlyphView glyph = new GlyphView(null);
        final UIText label = new UIText("");
        final UIElement strip = new UIElement();

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

            // A CARD DRAGGED ONTO A USER'S GROUP joins it. The same payload a canvas places, read for its kind.
            row.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
                boolean lands = landsHere(event.getPayload());
                toggle(row, DROP_CLASS, lands);
                if (lands) event.preventDefault();
            }, false, true);
            row.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> {
                if (event.getTarget() == row) toggle(row, DROP_CLASS, false);
            }, false, false);
            row.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
                toggle(row, DROP_CLASS, false);
                if (landsHere(event.getPayload()) && bound instanceof Folder folder && folder.group() != null) {
                    user.addToGroup(folder.group().label(), ((NewNode) event.getPayload()).kind());
                }
            }, false, true);
        }

        /** Whether {@code payload} is a kind this row's user group does not hold yet. */
        private boolean landsHere(Object payload) {
            return bound instanceof Folder folder && folder.group() != null && folder.group().user()
                    && payload instanceof NewNode created && created.kind() != null
                    && !folder.group().kinds().contains(created.kind());
        }

        /** The group this row lists or sits in; the compact row's kind. @see #GROUP @see #ENTRY */
        @Nullable
        Object dataFor(DataKey<?> key) {
            if (key == GROUP) {
                if (bound instanceof Folder folder) return folder.group();
                if (bound instanceof Strip run) return run.group();
                if (bound instanceof Item item) return item.group();
            }
            if (key == ENTRY && bound instanceof Item item) return item.entry();
            return null;
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
                glyph.showGlyph(entry.glyph());
            }
            if (item instanceof Strip run) bindStrip(run);
            else park(0);
        }

        /** The cards this strip holds, in order. */
        List<PreviewCard> cards() {
            List<PreviewCard> out = new ArrayList<>();
            for (UIElement child : strip.children()) {
                if (child instanceof PreviewCard card) out.add(card);
            }
            return out;
        }

        /** Moves each entry's card into its place here; whatever this strip held past the run is parked. */
        private void bindStrip(Strip run) {
            UIDocument window = document();
            if (window == null) return;
            List<LibraryCatalog.Entry> entries = run.entries();
            for (int i = 0; i < entries.size(); i++) {
                PreviewCard card = cardFor(run.folder(), entries.get(i), window);
                List<UIElement> held = strip.children();
                if (i >= held.size() || held.get(i) != card) card.moveTo(strip, i);
            }
            park(entries.size());
        }

        private void park(int from) {
            while (strip.children().size() > from) {
                strip.children().get(from).moveTo(parked, parked.children().size());
            }
        }
    }

    /** The entry's card in {@code folder}, made on first sight. @see #cards */
    private PreviewCard cardFor(String folder, LibraryCatalog.Entry entry, UIDocument window) {
        String key = folder + "|" + entry.id();
        PreviewCard card = cards.get(key);
        if (card != null) return card;
        card = new PreviewCard(PreviewStyles.of(window).group());
        card.onMouseDown.attachListener((element, event) -> {
            LibraryCatalog.Entry shown = ((PreviewCard) element).entry();
            if (shown != null) press(shown, element, event);
        }, false, true);
        card.show(entry);
        toggle(card, PreviewCard.SELECTED_CLASS, same(entry, selected));
        cards.put(key, card);
        return card;
    }

    /** A row element that answers for what its view is bound to. */
    private static final class RowElement extends UIElement implements DataProvider {

        private final RowView view;

        RowElement(RowView view) {
            this.view = view;
        }

        @Override
        @Nullable
        public Object getData(DataKey<?> key) {
            return view.dataFor(key);
        }
    }

    /** The card's menu for a card or compact row, the group's for a user's group, else the panel's. */
    private ContextMenu menuFor(UIElement pressed) {
        for (UIElement at = pressed; at != null && at != tree(); at = at.parent() instanceof UIElement up ? up : null) {
            // A STARTER JOINS NO GROUP, so its card offers what the panel does.
            if (at instanceof PreviewCard card && card.entry() != null) {
                select(card.entry());
                pickedIn(card);
                return ContextMenu.of(card.entry().isStarter() ? PANEL_MENU : CARD_MENU);
            }
            RowView view = views.get(at);
            if (view == null) continue;
            if (view.bound instanceof Item item) return ContextMenu.of(item.entry().isStarter() ? PANEL_MENU : CARD_MENU);
            if (view.bound instanceof Folder folder && folder.group() != null && folder.group().user()) {
                return ContextMenu.of(GROUP_MENU);
            }
            break;
        }
        return ContextMenu.of(PANEL_MENU);
    }

    /** A click selects, a double-click places, and a press that travels drags a {@link NewNode} to a drop target. */
    private void press(LibraryCatalog.Entry entry, UIElement source, MouseEvent.Down event) {
        // NEVER FROM THE KEYBOARD: a drag armed by a synthesized press can never be released.
        if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON || event.getDetail() == Input.KEYBOARD_DETAIL) return;
        select(entry);
        pickedIn(source);
        if (event.getDetail() >= 2) {
            onPlace.emit(entry);
            return;
        }
        UIDocument window = document();
        if (window == null) return;
        ghost.follow(window, entry.glyph().icon(), entry.label());
        Drag.start(source, event.getPosition().x(), event.getPosition().y(), CgMouseCodes.LEFT_BUTTON,
                new NewNode(entry.label(), entry.kind(), entry::build), Drag.DEFAULT_THRESHOLD_PX, (x, y, sx, sy, dx, dy) -> { });
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

    private static boolean same(@Nullable LibraryCatalog.Entry a, @Nullable LibraryCatalog.Entry b) {
        return a != null && b != null && a.id().equals(b.id());
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
