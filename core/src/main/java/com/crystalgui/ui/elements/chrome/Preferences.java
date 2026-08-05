package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.SettingsConfigurator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The preferences window — every user-editable declaration, grouped by section.
 *
 * <h3>It builds nothing of its own</h3>
 *
 * <p>{@link SettingsConfigurator} already turns a {@link Setting} into a bound, two-way row, and
 * {@link ConfiguratorPanel} already does collapsible groups. So this class chooses <em>which</em>
 * declarations to show and in what grouping, and that is the whole of it. Anything more would be a second
 * idea of what a settings row looks like, differing from the shader graph's inspector in ways nobody
 * chose.</p>
 *
 * <h3>Only what is writable at {@link SettingsLayer#USER}</h3>
 *
 * <p>The filter is the point, not a detail. {@code ShaderGraphSettings} declares its render queue
 * {@code writableAt(DOCUMENT, MEMORY)} precisely so it cannot become a global preference — and a window
 * that listed every registered declaration would put it there, which is the exact failure
 * {@code Setting.writableAt}'s own documentation describes: <i>"a user sets it once and every graph they
 * open silently inherits it"</i>.</p>
 *
 * <p><b>Nothing else enforces this today.</b> {@code Settings.setRaw} takes a key rather than a
 * declaration and so cannot check, which means {@code writableAt} is a rule this window keeps and the
 * store does not. Worth knowing before adding a second way to write settings.</p>
 *
 * <h3>Changes apply immediately, and are not undoable</h3>
 *
 * <p>VS Code's model rather than IntelliJ's OK/Apply/Cancel: a buffered dialog needs a second copy of
 * every value plus a revert path, and settings here are already observable, so a checkbox takes effect as
 * you watch. No {@code UndoStack} is passed, which is not an omission — {@link SettingsLayer#USER} is not
 * an undoable layer, and Ctrl+Z changing your font size instead of undoing your work is the failure that
 * boundary exists to prevent.</p>
 */
public final class Preferences extends Dialog {

    public static final String TITLE = "Preferences";

    /** On the panel, so the sheet can size it without reaching through the dialog's own content box. */
    public static final String PANEL_CLASS = "__preferences-panel__";

    private final ConfiguratorPanel panel = new ConfiguratorPanel();
    private final Settings settings;
    private final List<Setting<?>> shown = new ArrayList<>();

    public Preferences(Settings settings) {
        super(TITLE);
        this.settings = settings;

        panel.addClass(PANEL_CLASS);
        getContent().addChild(panel);

        for (Map.Entry<String, List<Setting<?>>> section : sections().entrySet()) {
            ConfiguratorGroup group = panel.group(labelOf(section.getKey()));
            for (Setting<?> setting : section.getValue()) {
                SettingsConfigurator.addRow(panel, group.content(), settings, SettingsLayer.USER,
                        setting, null);
                shown.add(setting);
            }
        }
    }

    /** Opens it centred and modal, as every editor's settings window is. */
    public static Preferences open(UIWindow window, Settings settings) {
        Preferences preferences = new Preferences(settings);
        window.addOverlay(preferences, null);
        preferences.showModal();
        return preferences;
    }

    public Settings settings() {
        return settings;
    }

    public ConfiguratorPanel panel() {
        return panel;
    }

    /** Every declaration this window put a row in for, in the order the rows appear. */
    public List<Setting<?>> shownSettings() {
        return new ArrayList<>(shown);
    }

    /**
     * The user-editable declarations, grouped by the first segment of their id and in declaration order.
     *
     * <p>Grouping by id prefix rather than by a category field on {@link Setting}: the prefix is already
     * the convention every declaration follows ({@code editor.}, {@code explorer.}), it is what
     * {@link SettingsRegistry#section} already keys on, and a separate category would be a second name
     * for the same grouping that could disagree with it.</p>
     */
    public static Map<String, List<Setting<?>>> sections() {
        Map<String, List<Setting<?>>> grouped = new LinkedHashMap<>();
        for (Setting<?> setting : SettingsRegistry.get().all()) {
            if (!setting.isWritableAt(SettingsLayer.USER)) continue;
            grouped.computeIfAbsent(sectionOf(setting.getId()), key -> new ArrayList<>()).add(setting);
        }
        return grouped;
    }

    /** Distinct section names, for a test that wants them without the settings. */
    public static Set<String> sectionNames() {
        return new LinkedHashSet<>(sections().keySet());
    }

    private static String sectionOf(String id) {
        int dot = id.indexOf('.');
        return dot <= 0 ? id : id.substring(0, dot);
    }

    /** {@code explorer} reads as "Explorer" — an id segment is a key, not a heading. */
    public static String labelOf(String section) {
        if (section.isEmpty()) return section;
        return Character.toUpperCase(section.charAt(0)) + section.substring(1).toLowerCase(Locale.ROOT);
    }
}
