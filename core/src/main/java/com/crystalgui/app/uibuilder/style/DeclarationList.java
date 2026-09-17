package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.text.UIText;

/**
 * What a target declares, as rows grouped by family — the body of the Styles tab.
 *
 * <pre>{@code
 * form.custom(new DeclarationList(panel, StyleFields.on(document, target, node), node));
 * }</pre>
 *
 * <p><b>A row per declaration, kept for as long as the declaration is.</b> The list follows
 * {@link StyleFields#declarations()}, so a declaration added by a palette pick, a lab, an undo or a typed
 * edit in the sheet appears as one new row, and editing a value touches no row at all. Rows are kept by name
 * with {@link ChildList.Keyed}, never rebuilt, so the control under the pointer survives the edit it is
 * making — a box-model scrub creates a declaration mid-drag.</p>
 */
final class DeclarationList extends UIElement {

    /** A row's identity: what it edits, and whether it is a switched-off declaration drawn as text. */
    private record RowKey(String name, boolean disabled) {
    }

    private final ConfiguratorPanel panel;
    private final StyleFields fields;
    private final UIElement node;

    private final UIElement groups = new UIElement();
    private final ChildList.Keyed<StyleFamilies.Family, ConfiguratorGroup> families;
    private final Map<StyleFamilies.Family, ChildList.Keyed<RowKey, Configurator>> rows =
            new EnumMap<>(StyleFamilies.Family.class);

    DeclarationList(ConfiguratorPanel panel, StyleFields fields, UIElement node) {
        this.panel = panel;
        this.fields = fields;
        this.node = node;
        addClass(BuilderStyleSections.LIST_CLASS);
        append(groups);
        families = new ChildList.Keyed<>(groups, this::group).onRemoved((family, group) -> rows.remove(family));
        if (fields.canWrite()) append(addRow());
        PropertyWatch.follow(this, fields.declarations(), this::show);
    }

    private void show(List<StyleFields.Declared> declared) {
        Map<StyleFamilies.Family, List<RowKey>> byFamily = new EnumMap<>(StyleFamilies.Family.class);
        for (StyleFields.Declared declaration : declared) {
            RowKey key = new RowKey(declaration.name(), declaration.disabled());
            List<RowKey> keys = byFamily.computeIfAbsent(
                    StyleFamilies.of(declaration.property(), declaration.name()), family -> new ArrayList<>());
            // A NAME DECLARED TWICE is one row: the last one wins in a rule, and it is the one an edit means.
            if (!keys.contains(key)) keys.add(key);
        }
        // AN INLINE STYLE HAS NO SOURCE ORDER, so its rows take the family's reading order; a rule keeps the order
        // its file has, as DevTools shows a rule -- except TEXT, whose sample rows would otherwise be broken up by
        // whatever the file declared between them.
        for (Map.Entry<StyleFamilies.Family, List<RowKey>> family : byFamily.entrySet()) {
            if (!fields.target().isInline() && family.getKey() != StyleFamilies.Family.TEXT) continue;
            family.getValue().sort(Comparator.comparingInt((RowKey key) -> StyleFamilies.rank(key.name()))
                    .thenComparing(RowKey::name));
        }
        // ONLY THE FAMILIES THAT HOLD SOMETHING: a heading per empty family is scaffolding, not content.
        List<StyleFamilies.Family> shown = new ArrayList<>();
        for (StyleFamilies.Family family : StyleFamilies.inOrder()) {
            if (byFamily.containsKey(family)) shown.add(family);
        }
        families.show(shown);
        for (StyleFamilies.Family family : shown) rows.get(family).show(byFamily.get(family));
    }

    private ConfiguratorGroup group(StyleFamilies.Family family) {
        // THE PANEL'S OWN GROUP, so its fold state is remembered like every other heading in the inspector.
        ConfiguratorGroup group = panel.group(family.label());
        rows.put(family, new ChildList.Keyed<RowKey, Configurator>(group.content(), this::row)
                .onRemoved((key, row) -> panel.forget(row)));
        return group;
    }

    private Configurator row(RowKey key) {
        String id = "style." + key.name();
        StyleFields.Declared declared = fields.declared(key.name());
        StyleProperty<?> property = declared == null ? null : declared.property();
        // A SWITCHED-OFF DECLARATION KEEPS ITS EDITOR, greyed and inert: it reads as the row it will be again. Read
        // through its own key, so a live declaration of the same name does not show in its place.
        DeclarationEditors.Field field = key.disabled()
                ? DeclarationEditors.of(property, id, key.name(), Property.derived(() -> valueOf(key), ignored -> { }),
                        fields, node)
                : DeclarationEditors.of(property, id, key.name(), fields.value(key.name()), fields, node);

        Configurator row = field.control() == null
                ? panel.row(field.descriptor(), cast(field.value()))
                : panel.row(key.name(), id, field.control());
        row.addClass(BuilderStyleSections.STYLE_ROW_CLASS);
        // A VALUE SIZED BY WHAT IT SAYS: a chip's lines are its floor, where a field shrinks to its column.
        row.toggleClass(BuilderStyleSections.CHIP_ROW_CLASS, field.control() instanceof StyleChip);
        if (key.disabled()) {
            row.addClass(BuilderStyleSections.HIDDEN_CLASS);
            // THE WHOLE VALUE COLUMN, whatever the row built into it: a plain field took typing that went nowhere.
            for (UIElement part : row.children()) {
                if (part.hasClass(Configurator.INLINE_CLASS)) part.setInert(true);
            }
        } else {
            PropertyWatch.follow(row, Property.derived(() -> fields.wins(key.name())),
                    won -> row.toggleClass(BuilderStyleSections.OVERRIDDEN_CLASS, !won));
        }
        if (!fields.canWrite()) return row;
        // THE EYE FIRST, before the name, as DevTools' checkbox is: switched off it stays up, so a hidden declaration
        // is found down the row's left edge.
        UIElement eye = action("", () -> fields.setEnabled(key.name(), key.disabled()));
        eye.addClass(BuilderStyleSections.ROW_EYE_CLASS);
        eye.toggleClass(BuilderStyleSections.OFF_CLASS, key.disabled());
        row.insertAt(0, eye);
        row.addClass(BuilderStyleSections.EYED_ROW_CLASS);
        UIElement remove = action("×", () -> fields.remove(key.name()));
        row.append(remove);
        // THE ROW'S HINT OVER ITS NAME AND VALUE ONLY, and the buttons saying what they do: regions of the one tooltip,
        // with nothing to say anywhere else -- the strip under a button read as the row. A second tooltip on a
        // button would show stacked on the row's. @see Tooltip#addRegion
        String eyeText = key.disabled() ? "Show" : "Hide";
        Tooltip hint = row.hint();
        if (hint != null) {
            String text = hint.getBaseText();
            for (UIElement part : row.children()) {
                if (part.hasClass(Configurator.LABEL_CLASS) || part.hasClass(Configurator.INLINE_CLASS)) {
                    // ONE PILL for the two: placed against the row, so crossing from name to value does not move it.
                    hint.addRegion(part, text, row);
                }
            }
            hint.addRegion(eye, eyeText);
            hint.addRegion(remove, "Remove");
            hint.setText("");
        } else {
            Tooltip.attach(eye, eyeText);
            Tooltip.attach(remove, "Remove");
        }
        return row;
    }

    /** A switched-off declaration's value, which only the comment holds. */
    private String valueOf(RowKey key) {
        for (StyleFields.Declared declared : fields.declared()) {
            if (declared.name().equals(key.name()) && declared.disabled() == key.disabled()) return declared.value();
        }
        return "";
    }

    /**
     * A one-glyph affordance, drawn rather than themed: a {@code Button}'s own padding is uneven, and at 16
     * pixels that asymmetry is most of the box.
     */
    private static UIElement action(String glyph, Runnable done) {
        UIElement button = new UIElement();
        button.addClass(BuilderStyleSections.ROW_ACTION_CLASS);
        button.setHitTest(true);
        button.append(new UIText(glyph));
        button.onMouseDown.attachListener((element, event) -> {
            done.run();
            event.preventDefault();
        }, false, true);
        return button;
    }

    /** DevTools' blank line at the end of a rule, as a button that says what it does. */
    private UIElement addRow() {
        UIElement row = new UIElement();
        row.addClass(BuilderStyleSections.ADD_ROW_CLASS);
        row.setHitTest(true);
        UIText label = new UIText("+  Add property");
        label.addClass(BuilderStyleSections.ADD_CLASS);
        // NOT A HIT TARGET: a press on the glyphs selects them. The row takes the press instead.
        label.setHitTest(false);
        row.append(label);
        row.onMouseDown.attachListener((element, event) -> {
            PropertyPalette.open(row, name -> fields.declared(name) != null, this::pick);
            event.preventDefault();
        }, false, true);
        return row;
    }

    /** What a palette pick does: adds the declaration, or shows the row of one already declared. */
    void pick(String name) {
        // ALREADY DECLARED: shown, not added -- adding writes the initial value over the one set.
        if (fields.declared(name) != null) {
            reveal(name);
            return;
        }
        // A COLOR STARTS AT THE ELEMENT'S OWN: most color properties' initial is transparent, and declaring
        // `caret-color: #00000000` hides the caret and `selection-color` a selection.
        StyleProperty<?> property = StyleFields.propertyOf(name);
        if (property instanceof ColorProperty && node != null) {
            Integer color = node.getStyle().computed().get(StylePropertyRegistry.COLOR);
            if (color != null) {
                fields.add(name, CssValues.color(color));
                return;
            }
        }
        // AT ITS INITIAL VALUE, so the row appears holding something the sheet can parse.
        fields.add(name, initialOf(name));
    }

    /** Opens {@code name}'s section, scrolls its row into view and puts focus in its control. */
    private void reveal(String name) {
        StyleFields.Declared declared = fields.declared(name);
        if (declared == null) return;
        StyleFamilies.Family family = StyleFamilies.of(declared.property(), name);
        ChildList.Keyed<RowKey, Configurator> section = rows.get(family);
        Configurator row = section == null ? null : section.get(new RowKey(name, declared.disabled()));
        if (row == null) return;
        ConfiguratorGroup group = families.get(family);
        if (group != null) group.setCollapsed(false);
        if (row.box() != null) row.box().scrollIntoView();
        if (document() != null && row.control() != null) document().focus().requestFocus(row.control());
    }

    /**
     * What a shorthand starts at when its longhands' initials would change nothing: a stroke of no width in no color
     * is not an edit, so an inline pick of it recorded nothing and no row appeared.
     */
    private static final Map<String, String> STARTERS = Map.of(StyleFields.TEXT_STROKE, "1px #000000");

    /** A name's initial value as a sheet writes it; a shorthand's is its starter, else its longhands' initials. */
    private static String initialOf(String name) {
        StyleProperty<?> property = StyleFields.propertyOf(name);
        if (property != null) return initialOf(property);
        String starter = STARTERS.get(name);
        if (starter != null) return starter;
        List<String> parts = new ArrayList<>();
        for (StyleProperty<?> longhand : PropertyPalette.longhandsOf(name)) parts.add(initialOf(longhand));
        return parts.isEmpty() ? "initial" : String.join(" ", parts);
    }

    private static String initialOf(StyleProperty<?> property) {
        String written = property.initialValue == null ? null : StyleFields.cast(property).write(property.initialValue);
        return written == null || written.isBlank() ? "initial" : written;
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> cast(Property<?> property) {
        return (Property<Object>) property;
    }
}
