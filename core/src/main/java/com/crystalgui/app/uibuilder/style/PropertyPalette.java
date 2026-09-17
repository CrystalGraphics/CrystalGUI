package com.crystalgui.app.uibuilder.style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.dimension.DimensionProperty;
import com.crystalgui.style.property.layout.grid.GridAutoProperty;
import com.crystalgui.style.property.layout.grid.GridProperty;
import com.crystalgui.style.property.layout.grid.GridTemplateAreasProperty;
import com.crystalgui.style.property.layout.grid.GridTemplateProperty;
import com.crystalgui.style.property.layout.length.LPAProperty;
import com.crystalgui.style.property.layout.length.LPARectProperty;
import com.crystalgui.style.property.layout.length.LPSizeProperty;
import com.crystalgui.style.property.visual.border.LengthPercentProperty;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.style.property.visual.texture.TextureProperty;
import com.crystalgui.style.property.visual.transform.TransformProperty;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.CreateMenu;
import com.crystalgui.widget.composite.SearchTree;
import com.crystalgui.widget.surface.insert.InsertMenu;
import com.crystalgui.widget.text.UIText;

/**
 * What <i>Add property</i> opens: every registered property, filed by the Styles tab's own families, searchable —
 * the Insert menu's look and keys.
 *
 * <pre>{@code
 * PropertyPalette.open(addRow,
 *         name -> fields.declared(name) != null,           // already declared here, drawn dimmed
 *         name -> fields.add(name, value));                // picked
 * }</pre>
 *
 * <ul>
 *   <li>Browsing lists <b>Recent</b>, open, then <b>Common</b> and one folder per {@link StyleFamilies.Family},
 *       closed. Recent is this process's last {@value #RECENT_LIMIT} picks, newest first.</li>
 *   <li>Typing flattens to one list, each result trailed by its family; {@code bg}, {@code radius}, a family's
 *       own name and the other spellings {@link StyleFamilies#search} knows find their properties.</li>
 *   <li>Every row ends in the kind of value it takes — {@code color}, {@code length}, {@code keyword}.</li>
 *   <li>What is offered is what a sheet can WRITE: a longhand spelled only through a shorthand is offered as the
 *       shorthand — {@code text-stroke}, never {@code text-stroke-width} — so a pick is always a name a rule
 *       accepts.</li>
 *   <li>The footer says what the highlighted property is: its family, its initial value, and whether it is
 *       already declared here.</li>
 * </ul>
 */
public final class PropertyPalette extends CreateMenu<PropertyPalette.Row, String> {

    public static final Name NAME = Name.of("propertypalette");

    /** On the row of a property this target already declares. */
    public static final String DECLARED_CLASS = "__declared__";

    static final String RECENT_FOLDER = "Recent";
    static final String COMMON_FOLDER = "Common";

    /** How many picks Recent keeps. */
    private static final int RECENT_LIMIT = 6;

    /** Newest first, by name. Per process: a pick is a habit of this session, not something worth a file. */
    private static final Deque<String> RECENT = new ArrayDeque<>();

    static final String HINTS = "↑↓ to choose · Enter to add · Esc to close";

    /** The Insert menu's row height, so the two read as one family. */
    private static final float ROW_HEIGHT = 20f;

    /** The declarations written by hand most often, in the order a form asks for them. */
    private static final List<String> COMMON = List.of(
            "display", "flex-direction", "align-items", "justify-content", "gap", "width", "height",
            "padding-top", "margin-top", "color", "font-size", "font-weight", "background-color", "opacity",
            "border-radius", "border-color", "outline", "transform", "transition");

    /** A folder, or one declaration by the name a sheet writes. */
    public record Row(String label, @Nullable String name, List<Row> children) {
    }

    private final Predicate<String> declared;
    private final UIText footer = new UIText(HINTS);

    private PropertyPalette(Predicate<String> declared) {
        super(NAME, "Add property");
        this.declared = declared;
        addClass(QUICK_INPUT_CLASS);
        removeWhenHidden();
        footer.addClass(InsertMenu.FOOTER_CLASS);
        footer.setHitTest(false);
        append(footer);

        setRows(new SearchTree.Rows<>() {
            @Override
            public List<Row> roots(String query) {
                return query == null || query.isBlank() ? browsing() : found(query);
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
                return node.name() == null;
            }

            @Override
            @Nullable
            public String payload(Row node) {
                return node.name();
            }

            @Override
            public List<String> categorySegments(Row node) {
                return node.name() == null ? List.of() : List.of(StyleFamilies.of(node.name()).label());
            }

            @Override
            @Nullable
            public String hint(Row node) {
                return node.name() == null ? null : kind(node.name());
            }

            @Override
            @Nullable
            public String rowClass(Row node) {
                return node.name() != null && declared.test(node.name()) ? DECLARED_CLASS : null;
            }
        });
        searchBox().setPlaceholder("Search properties");
        treeView().setItemHeight(ROW_HEIGHT);
        treeView().onSelectionChanged.connect(indices -> describeHighlighted());
    }

    /**
     * Opens under {@code anchor} with Recent open, everything else folded, and the caret in the search box.
     *
     * @param declared whether a property is already declared here — still listed, and dimmed, since a person
     *                 looking for it wants to be told it is on rather than shown nothing
     * @param pick     what a chosen declaration name does
     */
    public static PropertyPalette open(UIElement anchor, Predicate<String> declared, Consumer<String> pick) {
        PropertyPalette palette = new PropertyPalette(declared);
        palette.onChosen.connect(name -> {
            remember(name);
            pick.accept(name);
        });
        // AWAY FROM THE ROW THAT OPENED IT: a popover attaches to the nearest ancestor that takes children, and a
        // press inside it would then bubble back to that row. @see StyleLab#open
        UIDocument window = anchor.document();
        if (window != null) window.topLayerNode().append(palette);
        palette.body().reset();
        for (Row folder : palette.browsing()) {
            if (folder.label().equals(RECENT_FOLDER)) {
                palette.treeView().setExpanded(folder, true);
            }
        }
        palette.showFor(anchor, anchor);
        palette.body().focusSearch();
        palette.describeHighlighted();
        return palette;
    }

    /** Recent and Common first, then every family that has something a sheet can write in it. */
    private List<Row> browsing() {
        Map<StyleFamilies.Family, List<Row>> byFamily = new EnumMap<>(StyleFamilies.Family.class);
        for (String name : offered()) {
            byFamily.computeIfAbsent(StyleFamilies.of(name), ignored -> new ArrayList<>()).add(leaf(name));
        }
        List<Row> rows = new ArrayList<>();
        List<Row> recent = new ArrayList<>();
        synchronized (RECENT) {
            for (String name : RECENT) recent.add(leaf(name));
        }
        if (!recent.isEmpty()) rows.add(new Row(RECENT_FOLDER, null, recent));
        List<Row> common = new ArrayList<>();
        for (String name : COMMON) {
            if (StylePropertyRegistry.byName(name) != null) common.add(leaf(name));
        }
        rows.add(new Row(COMMON_FOLDER, null, common));
        for (StyleFamilies.Family family : StyleFamilies.inOrder()) {
            List<Row> members = byFamily.get(family);
            if (members != null) rows.add(new Row(family.label(), null, members));
        }
        return rows;
    }

    private static List<Row> found(String query) {
        Set<String> names = new LinkedHashSet<>();
        for (StyleProperty<?> property : StyleFamilies.search(query)) names.add(written(property));
        List<Row> rows = new ArrayList<>(names.size());
        for (String name : names) rows.add(leaf(name));
        return rows;
    }

    /** Every name a sheet can write, alphabetically: each writable property, and each shorthand a longhand names. */
    private static List<String> offered() {
        Set<String> names = new TreeSet<>();
        for (StyleProperty<?> property : StylePropertyRegistry.all()) names.add(written(property));
        return List.copyOf(names);
    }

    /** The name a sheet writes {@code property} under: its own, or the shorthand it may only be written through. */
    static String written(StyleProperty<?> property) {
        // THE CORNERS AS THE SHORTHAND, as the Styles tab shows them: a sheet may write either, and eight entries for
        // one shape buried the one a person means.
        if (StyleFields.RADIUS_LONGHANDS.contains(property.name)) return StyleFields.BORDER_RADIUS;
        return property.getAuthoredThrough() == null ? property.name : property.getAuthoredThrough();
    }

    /** The longhands a shorthand sets, in registry order; empty for a name the registry holds itself. */
    static List<StyleProperty<?>> longhandsOf(String shorthand) {
        List<StyleProperty<?>> out = new ArrayList<>();
        if (StyleFields.BORDER_RADIUS.equals(shorthand)) {
            for (String longhand : StyleFields.RADIUS_LONGHANDS) out.add(StyleFields.propertyOf(longhand));
            return out;
        }
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            if (shorthand.equals(property.getAuthoredThrough())) out.add(property);
        }
        return out;
    }

    private static void remember(String name) {
        synchronized (RECENT) {
            RECENT.remove(name);
            RECENT.addFirst(name);
            while (RECENT.size() > RECENT_LIMIT) RECENT.removeLast();
        }
    }

    /** The kind of value a declaration of {@code name} takes; a shorthand's is its longhands', in order. */
    static String kind(String name) {
        StyleProperty<?> property = StylePropertyRegistry.byName(name);
        if (property != null) return kind(property);
        Set<String> kinds = new LinkedHashSet<>();
        for (StyleProperty<?> longhand : longhandsOf(name)) kinds.add(kind(longhand));
        return kinds.isEmpty() ? "value" : String.join(" ", kinds);
    }

    /**
     * The kind of value {@code property} takes, as a person names it — what a row ends in.
     *
     * <p>From the property's own type, so a property registered tomorrow is described without an entry here; the
     * few whose type says nothing a person would recognise are named outright.</p>
     */
    static String kind(StyleProperty<?> property) {
        switch (property.name) {
            case "font-family": return "font";
            case "transition": return "transition";
            case "text-shadow": return "shadow";
            case "backdrop-filter": return "filter";
            case "text-decoration-line": return "keywords";
            // PIXELS, which the type cannot say: the value is a float either way.
            case "font-size", "caret-width": return "length";
            case "tooltip-delay", "scroll-duration": return "time";
            default: break;
        }
        if (property instanceof ColorProperty) return "color";
        Class<?> type = property.type;
        if (type == Boolean.class) return "on/off";
        if (type.isEnum()) return "keyword";
        if (type == Float.class || type == Integer.class || type == Double.class) return "number";
        if (property instanceof DimensionProperty || property instanceof LPAProperty || property instanceof LPSizeProperty
                || property instanceof LPARectProperty || property instanceof LengthPercentProperty) {
            return "length";
        }
        if (property instanceof TextureProperty) return "paint";
        if (property instanceof TransformProperty) return "transform";
        if (property instanceof GridProperty || property instanceof GridTemplateProperty
                || property instanceof GridAutoProperty || property instanceof GridTemplateAreasProperty) {
            return "grid";
        }
        return "value";
    }

    private static Row leaf(String name) {
        return new Row(name, name, List.of());
    }

    private void describeHighlighted() {
        String name = null;
        for (Integer index : treeView().getSelectedIndices()) {
            TreeRow<Row> row = treeView().rowAt(index);
            if (row != null) name = row.item().name();
            break;
        }
        footer.setText(name == null ? HINTS : describe(name, declared.test(name)));
    }

    /** {@code Layout · initial flex · declared here}, or for a shorthand the longhands it sets. */
    static String describe(String name, boolean declaredHere) {
        StringBuilder out = new StringBuilder(StyleFamilies.of(name).label());
        StyleProperty<?> property = StylePropertyRegistry.byName(name);
        if (property == null) {
            List<String> sets = new ArrayList<>();
            for (StyleProperty<?> longhand : longhandsOf(name)) sets.add(longhand.name);
            if (!sets.isEmpty()) out.append(" · sets ").append(String.join(", ", sets));
        } else {
            String initial = property.initialValue == null ? null : StyleFields.cast(property).write(property.initialValue);
            if (initial != null && !initial.isBlank()) out.append(" · initial ").append(initial);
            if (property.isInheritable()) out.append(" · inherited");
        }
        if (declaredHere) out.append(" · declared here");
        return out.toString();
    }
}
