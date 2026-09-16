package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.UIElement;
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
        DeclarationEditors.Field field = key.disabled()
                // A SWITCHED-OFF DECLARATION IS TEXT until it is on again: its value is not in the cascade.
                ? new DeclarationEditors.Field(ConfigDescriptor.info(id, key.name()),
                        Property.derived(() -> valueOf(key)))
                : DeclarationEditors.of(property, id, key.name(), fields.value(key.name()), fields, node);

        Configurator row = field.control() == null
                ? panel.row(field.descriptor(), cast(field.value()))
                : panel.row(key.name(), id, field.control());
        row.addClass(BuilderStyleSections.STYLE_ROW_CLASS);
        if (key.disabled()) {
            row.addClass(BuilderStyleSections.DISABLED_CLASS);
        } else {
            PropertyWatch.follow(row, Property.derived(() -> fields.wins(key.name())),
                    won -> row.toggleClass(BuilderStyleSections.OVERRIDDEN_CLASS, !won));
        }
        if (!fields.canWrite()) return row;
        if (!fields.target().isInline()) {
            row.append(action(key.disabled() ? "☐" : "☑", () -> fields.setEnabled(key.name(), key.disabled())));
        }
        row.append(action("×", () -> fields.remove(key.name())));
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
            PropertyPalette.open(row, name -> fields.declared(name) != null,
                    // AT ITS INITIAL VALUE, so the row appears holding something the sheet can parse.
                    property -> fields.add(property.name, initialOf(property)));
            event.preventDefault();
        }, false, true);
        return row;
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
