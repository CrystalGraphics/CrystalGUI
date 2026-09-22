package com.crystalgui.widget.surface.insert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.CreateMenu;
import com.crystalgui.widget.composite.SearchTree;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * The engine's Add menu: everything the registered {@link InsertSource}s offer, searchable, opened at a point on the
 * plane — Blender's Shift+A, Unity's Create Node.
 *
 * <pre>{@code
 * InsertMenu menu = surface.openInsertMenu(worldX, worldY);
 * menu.header().append(placementLine);                  // a consumer's own line above the search
 * menu.onCycle.connect(step -> placement.cycle(step));  // Tab and Shift+Tab, while a consumer listens
 * }</pre>
 *
 * <ul>
 *   <li>Browsing files every source's offers into nested folders by their {@code path()}, in the order the
 *       sources gave them; a query flattens them to one list ranked by label, synonym and path.</li>
 *   <li>Choosing a row calls its {@code insert} with the point the menu was opened at. What a surface offers is
 *       entirely its sources' — the graph offers node types, a UI builder widget kinds and starters.</li>
 *   <li>The footer describes the highlighted offer, or says which keys do what.</li>
 *   <li>Tab stays in the search box and emits {@link #onCycle} — only while something is connected to it, so a
 *       menu nobody cycles keeps Tab's ordinary meaning.</li>
 * </ul>
 */
public final class InsertMenu extends CreateMenu<InsertMenu.Row, Insertable> {

    public static final Name NAME = Name.of("insertmenu");

    public static final String HEADER_CLASS = "__insert-header__";

    /** A row: either a folder with children, or one offer. */
    public record Row(String label, @Nullable Insertable offer, List<Row> children) {
    }

    /** Tab ({@code +1}) or Shift+Tab ({@code -1}) in the search box. */
    public final Signal.Value<Integer> onCycle = new Signal.Value<>();

    private final SurfaceContext ctx;
    private final UIElement header = new UIElement();

    private float worldX;
    private float worldY;

    /** Every source's offers, gathered once per opening: {@link InsertSource#offers} is not asked per keystroke. */
    private List<Insertable> offers = List.of();

    public InsertMenu(SurfaceContext ctx) {
        super(NAME, "Insert Element");
        this.ctx = ctx;
        header.addClass(HEADER_CLASS);
        // THE HEADER UNDER THE TITLE and above the search: where Unity's Create Node puts its breadcrumb.
        insertAt(indexOf(body()), header);
        showHeader(false);

        setRows(new SearchTree.Rows<Row, Insertable>() {
            @Override
            public List<Row> roots(String query) {
                return build(query);
            }

            @Override
            public List<Row> children(Row node) {
                return node.children();
            }

            @Override
            public String label(Row node) {
                return node.label();
            }

            @Override
            public boolean isCategory(Row node) {
                return node.offer() == null;
            }

            @Override
            @Nullable
            public Insertable payload(Row node) {
                return node.offer();
            }

            @Override
            public List<String> categorySegments(Row node) {
                return node.offer() == null ? List.of() : node.offer().path();
            }

            @Override
            @Nullable
            public String icon(Row node) {
                return node.offer() == null ? null : node.offer().icon();
            }

            @Override
            @Nullable
            public String iconClass(Row node) {
                return node.offer() == null ? null : node.offer().iconClass();
            }
        });
        onChosen.connect(offer -> offer.insert(worldX, worldY));

        searchField().onKeyDown.attachListener((element, event) -> {
            if (event.getKeyCode() != CgKeyCodes.KEY_TAB || !onCycle.hasListeners()) return;
            // A HELD Ctrl, Alt or Super makes the chord somebody else's -- the window switcher's, today.
            int modifiers = event.getModifiers();
            if (CgModifiers.hasCtrl(modifiers) || CgModifiers.hasAlt(modifiers) || CgModifiers.hasSuper(modifiers)) return;
            onCycle.emit(CgModifiers.hasShift(modifiers) ? -1 : 1);
            event.stopPropagation();
            event.preventDefault();
        }, false, true);
        treeView().onSelectionChanged.connect(indices -> describeHighlighted());
        searchBox().setPlaceholder("Search to insert");
    }

    /** Opens at a world point, remembering it — what a chosen row is inserted at. */
    public InsertMenu openAtWorld(float worldX, float worldY) {
        this.worldX = worldX;
        this.worldY = worldY;
        List<Insertable> gathered = new ArrayList<>();
        for (InsertSource source : ctx.insertSources()) gathered.addAll(source.offers());
        offers = gathered;
        Vector2f at = ctx.surface().toViewport(worldX, worldY);
        // THE ROOT'S SPACE, which a promoted popover is placed in: the viewport's origin sits wherever the
        // surface does. And NO invoker: an invoker is exempt from light dismiss, so naming the surface left
        // a click anywhere on it unable to close the menu. @see GraphNodeLibrary#rootPositionOfWorld
        UIDocument window = ctx.surface().element().document();
        Box viewport = ctx.surface().element().box();
        if (window != null && viewport != null && window.box() != null) {
            at.add(Box.originIn(viewport, window.box()));
        }
        openAt(at.x(), at.y(), null);
        describeHighlighted();
        return this;
    }

    /** The line above the search box, empty and hidden until a consumer puts something in it. */
    public UIElement header() {
        return header;
    }

    /** Shows or hides {@link #header}. */
    public InsertMenu showHeader(boolean shown) {
        StyleGroup.inlinePipeline(header.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        return this;
    }

    private void describeHighlighted() {
        String description = null;
        List<Integer> selected = new ArrayList<>(treeView().getSelectedIndices());
        if (!selected.isEmpty()) {
            TreeRow<Row> row = treeView().rowAt(selected.get(0));
            if (row != null && row.item().offer() != null) description = row.item().offer().description();
        }
        setFooterText(description);
    }

    /**
     * Every source's offers, as one tree for browsing or one ranked list for a query.
     *
     * <p>A query <b>flattens</b>: a result set is ranked, not filed. Browsing keeps the folders, in the order the
     * sources first named them, which is what lets a source put its Recent row first.</p>
     */
    private List<Row> build(String query) {
        SearchQuery parsed = SearchQuery.of(query);
        if (!parsed.isEmpty()) {
            List<Ranked> ranked = new ArrayList<>();
            for (Insertable offer : offers) {
                if (offer.browsingOnly()) continue;
                SearchMatch match = SearchMatcher.match(parsed, offer.label(), SearchMatch.FIELD_PRIMARY);
                match = SearchMatch.best(match, SearchMatcher.matchAny(parsed, offer.synonyms(), SearchMatch.FIELD_ALIAS));
                match = SearchMatch.best(match, SearchMatcher.matchAny(parsed, offer.path(), SearchMatch.FIELD_CONTEXT));
                if (match != null) ranked.add(new Ranked(offer, match));
            }
            ranked.sort(Comparator.comparing(Ranked::match));
            List<Row> flat = new ArrayList<>(ranked.size());
            for (Ranked each : ranked) flat.add(new Row(each.offer().label(), each.offer(), List.of()));
            return flat;
        }

        // A FOLDER PER SEGMENT, so a path nests as the Library files it; an offer with no path sits at the top.
        Folder root = new Folder();
        for (Insertable offer : offers) {
            Folder folder = root;
            for (String segment : offer.path()) folder = folder.child(segment);
            folder.offers.add(offer);
        }
        return root.rows();
    }

    /** One level of the browsing tree, keeping the order its folders were first named. */
    private static final class Folder {
        final Map<String, Folder> children = new LinkedHashMap<>();
        final List<Insertable> offers = new ArrayList<>();

        Folder child(String name) {
            return children.computeIfAbsent(name, ignored -> new Folder());
        }

        /** Sub-folders first, then the offers filed here. */
        List<Row> rows() {
            List<Row> rows = new ArrayList<>(children.size() + offers.size());
            for (Map.Entry<String, Folder> child : children.entrySet()) {
                rows.add(new Row(child.getKey(), null, child.getValue().rows()));
            }
            for (Insertable offer : offers) rows.add(new Row(offer.label(), offer, List.of()));
            return rows;
        }
    }

    private record Ranked(Insertable offer, SearchMatch match) {
    }
}
