package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.settings.SetSettingEdit;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.core.undo.UndoStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * Builds fields from {@link Setting} declarations, each bound to the setting's value.
 *
 * <pre>{@code
 * SettingsConfigurator.build(panel.form(), settings, SettingsLayer.USER, SettingsRegistry.get().section("editor"), null);
 * }</pre>
 *
 * <h3>This is the payoff for declarations being data</h3>
 * <p>A settings panel is <b>generated</b> here, the same way the command palette is generated from
 * {@code CommandRegistry}: nothing about which settings exist is written twice, so adding one is a single
 * line in one place and it appears with a label, a control of the right kind and its value already in
 * it.</p>
 *
 * <p>It is also why {@code core/} declares settings and {@code widget/} renders them, rather than a
 * {@code Setting} carrying its own widget. This class is the whole bridge.</p>
 *
 * <h3>A field follows the store, not only drives it</h3>
 * <p>A setting's value is a {@link Property} over the store ({@link #property}). An {@code Edit} mutates
 * the store directly, so without following it undo would change the value while the field showing it
 * stayed behind — which reads as "Ctrl+Z did nothing".</p>
 */
public final class SettingsConfigurator {

    private SettingsConfigurator() {
    }

    /**
     * Writes a field per declaration.
     *
     * @param layer where writes land. {@link SettingsLayer#DOCUMENT} is the undoable one
     * @param undo  where changes are recorded; null still edits, just not undoably
     */
    public static void build(ConfigForm form, Settings settings, SettingsLayer layer,
                             Collection<? extends Setting<?>> declarations, @Nullable UndoStack undo) {
        for (Setting<?> setting : declarations) addRow(form, settings, layer, setting, undo);
    }

    /** As {@link #build}, taking every declaration registered under {@code section}. */
    public static void buildSection(ConfigForm form, Settings settings, SettingsLayer layer, String section,
                                    @Nullable UndoStack undo) {
        build(form, settings, layer, SettingsRegistry.get().section(section), undo);
    }

    /** One bound field. */
    public static Configurator addRow(ConfigForm form, Settings settings, SettingsLayer layer,
                                      Setting<?> setting, @Nullable UndoStack undo) {
        return form.prop(describe(setting), property(settings, layer, setting, undo));
    }

    /**
     * A setting's value in {@code settings}, as the property a field binds to.
     *
     * <pre>{@code
     * Property<Object> fontSize = SettingsConfigurator.property(settings, SettingsLayer.USER, FONT_SIZE, null);
     * }</pre>
     *
     * <p>Reads the resolved value, in the shape its control expects — an integer setting as a
     * {@code Double}, since a number field is typed on one. Writes a {@link SetSettingEdit} at
     * {@code layer}, recorded in {@code undo} only when that layer is undoable: a preference change is not
     * an edit to anything the user is working on, and Ctrl+Z changing your font size instead of undoing
     * your work is the failure {@link SettingsLayer#isUndoable} exists to prevent.</p>
     */
    public static Property<Object> property(Settings settings, SettingsLayer layer, Setting<?> setting,
                                            @Nullable UndoStack undo) {
        UndoStack history = undo != null && layer.isUndoable() ? undo : null;
        return Property.derived(() -> currentValue(settings, setting),
                        value -> write(settings, layer, setting, history, value))
                .announcedBy(refresh -> settings.onChanged.connect(change -> {
                    if (change.affects(setting)) refresh.run();
                }))
                .editedIn(history);
    }

    /**
     * A declaration as something {@link ConfigControls} can build.
     *
     * <p>By the declared value type, which is all a {@link Setting} carries — it has no widget kind of its
     * own, deliberately, so that {@code core/} never names a control.</p>
     */
    public static ConfigDescriptor describe(Setting<?> setting) {
        ConfigDescriptor descriptor;
        if (setting.isEnumerated()) {
            descriptor = ConfigDescriptor.select(setting.getId(), setting.getLabel(), setting.getOptions());
        } else {
            Object fallback = setting.getDefaultValue();
            if (fallback instanceof Boolean) {
                descriptor = ConfigDescriptor.bool(setting.getId(), setting.getLabel());
            } else if (fallback instanceof Integer) {
                descriptor = ConfigDescriptor.number(setting.getId(), setting.getLabel()).integral(true);
            } else if (fallback instanceof Number) {
                descriptor = ConfigDescriptor.number(setting.getId(), setting.getLabel());
            } else {
                descriptor = ConfigDescriptor.text(setting.getId(), setting.getLabel());
            }
        }
        // The description becomes the tooltip: it is the one piece of a declaration that exists purely to
        // be shown to somebody, and dropping it would make it dead weight on every declaration.
        return setting.getDescription() == null ? descriptor : descriptor.tooltip(setting.getDescription());
    }

    /** Every declaration in a section, for a caller that wants to arrange them itself. */
    public static List<Setting<?>> section(String section) {
        return SettingsRegistry.get().section(section);
    }

    /** The resolved value, in the shape the control expects. */
    @Nullable
    private static Object currentValue(Settings settings, Setting<?> setting) {
        Object value = settings.get(setting);
        // A NumberControl is typed on Double regardless of how the setting spells its number, so an
        // Integer setting has to widen here or the control refuses its own value.
        return value instanceof Integer whole ? Double.valueOf(whole) : value;
    }

    private static void write(Settings settings, SettingsLayer layer, Setting<?> setting,
                              @Nullable UndoStack history, @Nullable Object value) {
        String text = value == null ? null : String.valueOf(value);
        // A NumberControl reports 3.0 for an integral setting, which would store "3.0" and then fail to
        // compare equal to the "3" a codec wrote. Normalising here keeps one spelling in storage.
        if (setting.getDefaultValue() instanceof Integer && value instanceof Number number) {
            text = String.valueOf(number.intValue());
        }
        SetSettingEdit edit = SetSettingEdit.of(settings, layer, setting.getId(), text);
        if (!edit.changesAnything()) return;
        if (history != null) history.execute(edit);
        else edit.apply();
    }
}
