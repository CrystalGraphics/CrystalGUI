package com.crystalgui.ui.elements.config;

import com.crystalgui.core.settings.SetSettingEdit;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.core.undo.UndoStack;

import com.crystalgui.core.config.ConfigDescriptor;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

/**
 * Builds inspector rows from {@link Setting} declarations, and writes changes back.
 *
 * <h3>This is the payoff for declarations being data</h3>
 * <p>A settings panel is <b>generated</b> here, the same way the command palette is generated from
 * {@code CommandRegistry}: hand a section name or a list of declarations and get a bound panel. Nothing
 * about which settings exist is written twice, so adding one is a single line in one place and it appears
 * in the UI with a label, a control of the right kind and its default already in it.</p>
 *
 * <p>It is also why {@code core/} declares settings and {@code ui/} renders them, rather than a
 * {@code Setting} carrying its own widget. This class is the entire bridge, and it is the identical seam
 * {@code NodeFieldWidgets} already is for {@code NodeField} — one adapter, in the layer that is allowed
 * to know about both.</p>
 *
 * <h3>Two-way, and echo-suppressed</h3>
 * <p>A row writes to the store <em>and</em> follows it. Following matters for the same reason it did for
 * the node field editors: an {@code Edit} mutates the store directly, so without it undo changes the
 * value and the widget showing it stays behind — which reads as "Ctrl+Z did nothing" and sends everyone
 * looking at the undo stack. Its own writes are ignored by comparing against what it last wrote, and it
 * skips entirely while a gesture is live so a drag is not interrupted by its own per-frame writes.</p>
 */
public final class SettingsConfigurator {

    private SettingsConfigurator() {
    }

    /**
     * Appends a bound row per declaration.
     *
     * @param layer where writes land. {@link SettingsLayer#DOCUMENT} is the undoable one
     * @param undo  where changes are recorded; null still edits, just not undoably
     */
    public static ConfiguratorPanel build(ConfiguratorPanel panel, Settings settings,
                                          SettingsLayer layer, Collection<? extends Setting<?>> declarations,
                                          @Nullable UndoStack undo) {
        for (Setting<?> setting : declarations) {
            addRow(panel, panel, settings, layer, setting, undo);
        }
        return panel;
    }

    /** As {@link #build}, taking every declaration registered under {@code section}. */
    public static ConfiguratorPanel buildSection(ConfiguratorPanel panel, Settings settings,
                                                 SettingsLayer layer, String section,
                                                 @Nullable UndoStack undo) {
        return build(panel, settings, layer, SettingsRegistry.get().section(section), undo);
    }

    /**
     * One bound row, into {@code parent} — which may be a group's content rather than the panel root.
     *
     * @return the row, or null when the setting's kind has no registered control
     */
    @Nullable
    public static Configurator addRow(ConfiguratorPanel panel, com.crystalgui.ui.UIElement parent,
                                      Settings settings, SettingsLayer layer, Setting<?> setting,
                                      @Nullable UndoStack undo) {
        ConfigDescriptor descriptor = describe(setting);
        Configurator row = panel.addTo(parent, descriptor, currentValue(settings, setting));
        if (row == null) return null;
        bind(row.control(), settings, layer, setting, undo);
        return row;
    }

    /**
     * A declaration as something {@link ConfigControls} can build.
     *
     * <p>The mapping is by the declared value type, which is all a {@link Setting} carries — it has no
     * widget kind of its own, deliberately, so that {@code core/} never names a control.</p>
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

    /** The resolved value, in the shape the control expects. */
    @Nullable
    private static Object currentValue(Settings settings, Setting<?> setting) {
        Object value = settings.get(setting);
        // A NumberControl is typed on Double regardless of how the setting spells its number, so an
        // Integer setting has to widen here or the control refuses its own initial value.
        return value instanceof Integer whole ? Double.valueOf(whole) : value;
    }

    /**
     * Wires one control both ways.
     *
     * @see SettingsConfigurator the class note, on why following the store is not optional
     */
    private static void bind(ConfigControl control, Settings settings, SettingsLayer layer,
                             Setting<?> setting, @Nullable UndoStack undo) {
        String[] lastWritten = { settings.layer(layer).get(setting.getId()) };

        control.changed.connect(value -> {
            String text = value == null ? null : String.valueOf(value);
            // A NumberControl reports 3.0 for an integral setting, which would store "3.0" and then fail
            // to compare equal to the "3" a codec wrote. Normalising here keeps one spelling in storage.
            if (setting.getDefaultValue() instanceof Integer && value instanceof Number number) {
                text = String.valueOf(number.intValue());
            }
            lastWritten[0] = text;

            SetSettingEdit edit = SetSettingEdit.of(settings, layer, setting.getId(), text);
            if (!edit.changesAnything()) return;
            // Only the document layer is undoable — a preference change is not an edit to anything the
            // user is working on, and putting one on the stack means Ctrl+Z changes your font size
            // instead of undoing your work. See SettingsLayer.
            if (undo != null && layer.isUndoable()) undo.execute(edit);
            else edit.apply();
        });

        // A gesture is one undo step, not one per frame. Same bracket NodeFieldBinder puts round a scrub.
        if (undo != null && layer.isUndoable()) {
            control.interacting.connect(active -> {
                if (Boolean.TRUE.equals(active)) undo.beginMergeRun();
                else undo.endMergeRun();
            });
        }

        // DECLARED, not subscribed. `settings` outlives this control by the life of the application, so
        // the control owns when this is live -- connected while it is in a tree, dropped when it leaves,
        // re-established if it comes back. See ConfigControl.follows.
        control.follows(() -> {
            // Re-read FIRST. The store may have moved while this control was out of the tree, and a
            // control that comes back stale is worse than one that never followed at all.
            lastWritten[0] = settings.layer(layer).get(setting.getId());
            control.setValueObject(currentValue(settings, setting));
            return settings.onChanged.connect(change -> {
                if (!change.affects(setting)) return;
                if (control.isInteracting()) return;
                String live = settings.layer(layer).get(setting.getId());
                if (java.util.Objects.equals(live, lastWritten[0])) return;
                lastWritten[0] = live;
                control.setValueObject(currentValue(settings, setting));
            });
        });
    }

    /** Every declaration in a section, for a caller that wants to arrange them itself. */
    public static List<Setting<?>> section(String section) {
        return SettingsRegistry.get().section(section);
    }
}
