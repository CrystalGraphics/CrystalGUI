package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
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
 * <h3>A plain {@link Dialog} with a class, not a subclass of one</h3>
 *
 * <p>It was a subclass, briefly, and every {@code dialog …} rule in the sheet stopped applying to it: a
 * widget's cascade identity is its <b>tag</b>, and {@code tagName()} reports the registered name for its
 * own class, so {@code Preferences extends Dialog} is a {@code preferences}, not a {@code dialog}. The
 * symptom was a title bar with no height and a close button stretched across it — the same failure
 * {@code AGENTS.md} records for {@code Dropdown extends Button}.</p>
 *
 * <p>So this is a controller holding a dialog, and the variant is spelled with a class exactly as
 * {@code dialog.__picker__} already is. A dialog variant should not cost a tag.</p>
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
public final class Preferences {

    public static final String TITLE = "Preferences";

    /** The variant class on the dialog. @see Preferences the class note on why this is not a tag */
    public static final String DIALOG_CLASS = "__preferences__";

    private final Dialog dialog = new Dialog(TITLE);
    private final ConfiguratorPanel panel = new ConfiguratorPanel();
    private final Settings settings;
    private final List<Setting<?>> shown = new ArrayList<>();

    public Preferences(Settings settings) {
        this.settings = settings;
        dialog.addClass(DIALOG_CLASS);
        dialog.getContent().addChild(panel);

        for (Map.Entry<String, List<Setting<?>>> section : sections().entrySet()) {
            ConfiguratorGroup group = panel.group(labelOf(section.getKey()));
            // ADDED, not merely built. `group()` deliberately does not attach -- a group may belong inside
            // another group, and only the caller knows -- and forgetting it leaves every row parented to a
            // detached element. It looks fine from `panel.controls()`, which is keyed by id and populated
            // whether or not anything is on screen, so the window comes up entirely empty.
            panel.addChild(group);
            for (Setting<?> setting : section.getValue()) {
                if (SettingsConfigurator.addRow(panel, group.content(), settings, SettingsLayer.USER,
                        setting, null) != null) {
                    shown.add(setting);
                }
            }
        }
    }

    /**
     * Opens it centred, and <b>not</b> modal.
     *
     * <p>IntelliJ's settings dialog is modal; VS Code's is a tab you can leave open while you work, and
     * that is the better model for a window whose whole point is watching a change take effect. Modality
     * here would make the tree it re-sorts <em>inert</em> — you could see the sort order change and not
     * touch the result until you closed the window that changed it.</p>
     *
     * <p>Escape still closes it: a close watcher is what Escape consults, and that is a separate thing
     * from modality — see {@code UIWindow}'s two stacks.</p>
     */
    public static Preferences open(UIWindow window, Settings settings) {
        Preferences preferences = new Preferences(settings);
        window.addOverlay(preferences.dialog, null);
        preferences.dialog.show();
        centre(window, preferences.dialog);
        return preferences;
    }

    /**
     * Puts the dialog in the middle of the window, once it has a size to be the middle of.
     *
     * <p>It cannot be done at open time: nothing has laid out yet, so the width and height are both zero
     * and the "centre" is the top-left corner — which is where it opened. So the same ticker idiom
     * {@code InputDialog} uses, held invisible for the frame in between rather than allowed to appear in
     * the corner and jump.</p>
     *
     * <p>Hidden with {@code opacity} at IMPORTANT and then <b>removed</b> rather than set back to 1:
     * dropping the candidate hands the property back to the stylesheet, so the sheet keeps ownership of
     * how a dialog appears.</p>
     */
    private static void centre(UIWindow window, Dialog dialog) {
        StyleGroup.importantPipeline(dialog.getStyle().getGeneralGroup(), g -> g.opacity(0f));
        window.registerTicker(delta -> {
            if (dialog.getAttachedWindow() == null) return false;
            float width = dialog.getRuntimeCache().getWidth();
            float height = dialog.getRuntimeCache().getHeight();
            if (width <= 0f || height <= 0f) return true;   // not laid out yet; look again next frame
            dialog.moveTo(Math.max(0f, (window.getScreenWidth() - width) / 2f),
                    Math.max(0f, (window.getScreenHeight() - height) / 2f));
            dialog.getStyle().removeCandidates(StylePropertyRegistry.OPACITY,
                    slot -> slot.origin() == StyleOrigin.IMPORTANT);
            return false;
        });
    }

    public Dialog dialog() {
        return dialog;
    }

    public Settings settings() {
        return settings;
    }

    public ConfiguratorPanel panel() {
        return panel;
    }

    /**
     * Every declaration this window actually put a row in for.
     *
     * <p>A setting whose kind has no registered control is <b>not</b> in here — recording it anyway made
     * this list a restatement of the filter rather than evidence of what was built, which is precisely how
     * an entirely empty window passed its own test.</p>
     */
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

    /** Distinct section names, for a caller that wants them without the settings. */
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
