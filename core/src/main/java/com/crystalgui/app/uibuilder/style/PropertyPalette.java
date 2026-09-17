package com.crystalgui.app.uibuilder.style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 *         property -> fields.add(property.name, value));   // picked
 * }</pre>
 *
 * <ul>
 *   <li>Browsing lists <b>Recent</b>, open, then <b>Common</b> and one folder per {@link StyleFamilies.Family},
 *       closed. Recent is this process's last {@value #RECENT_LIMIT} picks, newest first.</li>
 *   <li>Typing flattens to one list, each result trailed by its family; {@code bg}, {@code radius}, a family's
 *       own name and the other spellings {@link StyleFamilies#search} knows find their properties.</li>
 *   <li>Every row ends in the kind of value it takes — {@code colour}, {@code length}, {@code keyword}.</li>
 *   <li>The footer says what the highlighted property is: its family, its initial value, and whether it is
 *       already declared here.</li>
 * </ul>
 */
public final class PropertyPalette extends CreateMenu<PropertyPalette.Row, StyleProperty<?>> {

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
            "border-top-left-radius-x", "border-color", "outline", "transform", "transition");

    /** A folder, or one property. */
    public record Row(String label, @Nullable StyleProperty<?> property, List<Row> children) {
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
                return node.property() == null;
            }

            @Override
            @Nullable
            public StyleProperty<?> payload(Row node) {
                return node.property();
            }

            @Override
            public List<String> categorySegments(Row node) {
                return node.property() == null ? List.of() : List.of(StyleFamilies.of(node.property()).label());
            }

            @Override
            @Nullable
            public String hint(Row node) {
                return node.property() == null ? null : kind(node.property());
            }

            @Override
            @Nullable
            public String rowClass(Row node) {
                return node.property() != null && declared.test(node.property().name) ? DECLARED_CLASS : null;
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
     * @param pick     what a chosen property does
     */
    public static PropertyPalette open(UIElement anchor, Predicate<String> declared, Consumer<StyleProperty<?>> pick) {
        PropertyPalette palette = new PropertyPalette(declared);
        palette.onChosen.connect(property -> {
            remember(property);
            pick.accept(property);
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

    /** Common first, then every family that has something registered in it. */
    private List<Row> browsing() {
        Map<StyleFamilies.Family, List<Row>> byFamily = new EnumMap<>(StyleFamilies.Family.class);
        List<StyleProperty<?>> all = new ArrayList<>(StylePropertyRegistry.all());
        all.sort(Comparator.comparing(property -> property.name));
        for (StyleProperty<?> property : all) {
            if (!writable(property)) continue;
            byFamily.computeIfAbsent(StyleFamilies.of(property), ignored -> new ArrayList<>()).add(leaf(property));
        }
        List<Row> rows = new ArrayList<>();
        List<Row> recent = new ArrayList<>();
        synchronized (RECENT) {
            for (String name : RECENT) {
                StyleProperty<?> property = StylePropertyRegistry.byName(name);
                if (property != null) recent.add(leaf(property));
            }
        }
        if (!recent.isEmpty()) rows.add(new Row(RECENT_FOLDER, null, recent));
        List<Row> common = new ArrayList<>();
        for (String name : COMMON) {
            StyleProperty<?> property = StylePropertyRegistry.byName(name);
            if (property != null) common.add(leaf(property));
        }
        rows.add(new Row(COMMON_FOLDER, null, common));
        for (StyleFamilies.Family family : StyleFamilies.inOrder()) {
            List<Row> members = byFamily.get(family);
            if (members != null) rows.add(new Row(family.label(), null, members));
        }
        return rows;
    }

    private static List<Row> found(String query) {
        List<Row> rows = new ArrayList<>();
        for (StyleProperty<?> property : StyleFamilies.search(query)) {
            if (writable(property)) rows.add(leaf(property));
        }
        return rows;
    }

    private static void remember(StyleProperty<?> property) {
        synchronized (RECENT) {
            RECENT.remove(property.name);
            RECENT.addFirst(property.name);
            while (RECENT.size() > RECENT_LIMIT) RECENT.removeLast();
        }
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
        if (property instanceof ColorProperty) return "colour";
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

    /** A longhand a sheet may not write — {@code text-stroke-width} is spelled {@code text-stroke} — is not offered. */
    private static boolean writable(StyleProperty<?> property) {
        return property.getAuthoredThrough() == null;
    }

    private static Row leaf(StyleProperty<?> property) {
        return new Row(property.name, property, List.of());
    }

    private void describeHighlighted() {
        StyleProperty<?> property = null;
        for (Integer index : treeView().getSelectedIndices()) {
            TreeRow<Row> row = treeView().rowAt(index);
            if (row != null) property = row.item().property();
            break;
        }
        footer.setText(property == null ? HINTS : describe(property, declared.test(property.name)));
    }

    /** {@code Layout · initial flex · declared here}. */
    static String describe(StyleProperty<?> property, boolean declaredHere) {
        StringBuilder out = new StringBuilder(StyleFamilies.of(property).label());
        String initial = property.initialValue == null ? null : StyleFields.cast(property).write(property.initialValue);
        if (initial != null && !initial.isBlank()) out.append(" · initial ").append(initial);
        if (property.isInheritable()) out.append(" · inherited");
        if (declaredHere) out.append(" · declared here");
        return out.toString();
    }
}
