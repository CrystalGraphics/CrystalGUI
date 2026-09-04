package com.crystalgui.workbench;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.texteditor.TextEditor;

import com.crystalgui.workbench.explorer.WorkspaceTreeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * The workbench's own declarations, and the one place they are applied.
 *
 * <h3>Ids are VS Code's wherever VS Code has one</h3>
 *
 * <p>Not deference — reuse of an argument already had. {@code explorer.confirmDelete} exists because
 * deleting is destructive and people asked for the confirmation back; {@code explorer.autoReveal} exists
 * because revealing the active file is right until the tree yanks itself around while you are reading it.
 * Inventing a new name for the same tradeoff loses the ability to look up what the tradeoff was.</p>
 *
 * <h3>Everything declared here reaches a real call</h3>
 *
 * <p>A setting that changes nothing is worse than a missing one: it appears in the preferences window,
 * invites being set, and does nothing — with no way to tell that from a bug. {@link #install} is the whole
 * of the wiring, and it is deliberately in the same file as the declarations so the two cannot drift.</p>
 *
 * <h3>Values are read through the scope chain, not from a store somebody has to hand around</h3>
 *
 * <p>{@code UIElement} is a {@code SettingsScope}, so {@code workbench.resolve(SORT_ORDER)} already walks
 * outward to whichever ancestor holds the value. Nothing here needs a {@code Settings} reference, and a
 * panel that wants to override a setting for itself alone can, by holding its own.</p>
 */
public final class WorkbenchSettings {

    private WorkbenchSettings() {
    }

    // ── Explorer ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Off by default</b>, which is IntelliJ's posture rather than VS Code's.
     *
     * <p>VS Code ships {@code explorer.autoReveal} on and IntelliJ ships "Always Select Opened File" off,
     * so the default is a choice rather than a copy. Off, because on it <b>couples the tree to the tab
     * strip</b>: every switch between editors moves the tree's selection and scrolls it, so a tree you
     * were reading loses your place for a reason you did not ask for.</p>
     *
     * <p>It also had a second-order cost that reads as a different bug entirely. Revealing refreshes the
     * tree, and a {@code ListView} refresh reattaches focus to its focused row — so closing a tab, which
     * detaches the focused editor and leaves the focus owner null, ended with the PROJECT TREE focused. It
     * was reported as "Ctrl+W focuses the explorer", and nothing about closing a tab is what did it.</p>
     */
    public static final Setting<Boolean> AUTO_REVEAL =
            Setting.bool("explorer.autoReveal", "Reveal the active file", false)
                    .description("Select the file being edited in the tree as you move between tabs.");

    public static final Setting<Boolean> CONFIRM_DELETE =
            Setting.bool("explorer.confirmDelete", "Confirm before deleting", true)
                    .description("Ask before deleting a file. There is no version control underneath, so "
                            + "a delete is final.");

    /**
     * Spelled with the enum's own constant names, so the two cannot disagree.
     *
     * <p>{@code Setting.select} takes strings rather than a Java enum on purpose — an option list that
     * has to be a compile-time enum cannot come from a registry — so the bridge back is
     * {@link #sortOrder()}, which is the only place the name is turned into the constant.</p>
     */
    public static final Setting<String> SORT_ORDER =
            Setting.select("explorer.sortOrder", "Sort order", names(), "DEFAULT")
                    .description("How entries are ordered inside a folder.");

    private static List<String> names() {
        List<String> options = new java.util.ArrayList<>();
        for (WorkspaceTreeSource.SortOrder order : WorkspaceTreeSource.SortOrder.values()) {
            options.add(order.name());
        }
        return options;
    }

    // ── Editor ──────────────────────────────────────────────────────────────────────────────────

    public static final Setting<Boolean> FOLDING =
            Setting.bool("editor.folding", "Code folding", true)
                    .description("Show fold arrows in the gutter and allow regions to be collapsed.");

    public static final Setting<Boolean> SCROLL_BEYOND_LAST_LINE =
            Setting.bool("editor.scrollBeyondLastLine", "Scroll beyond the last line", true)
                    .description("Allow scrolling past the end of the file, so the last line need not sit "
                            + "pinned to the bottom of the viewport.");

    public static final Setting<Integer> TAB_SIZE =
            Setting.integer("editor.tabSize", "Tab size", 4)
                    .description("How many columns a tab character occupies.");

    public static final Setting<Double> CARET_BLINK =
            Setting.number("editor.caretBlinkSeconds", "Caret blink interval", 0.5)
                    .description("Seconds between caret blinks. Zero holds the caret steady.");

    // ── Appearance ──────────────────────────────────────────────────────────────────────────────

    /**
     * The two axes of {@code plan_styling.md} §3.7, side by side on one page exactly as IntelliJ
     * shows them: the UI theme restyles the chrome, the editor colour scheme the document — and
     * Crystal Dark plus any scheme is a legal pair.
     *
     * <p><b>Options are display names, values are display names.</b> The dropdown control shows the
     * setting's value verbatim, so the human-readable name is what is stored; {@link #themeId} maps
     * it back to the registry id at apply time (the same bridge {@link #sortOrder()} builds for its
     * enum). A stored name whose theme has gone is clamped by {@code Setting.select}'s own parse to
     * the default — the §4.4 fallback, for free.</p>
     *
     * <p>The option list snapshots the registry at declaration, and the field initializer registers
     * the built-ins first so the snapshot is never empty. A mod registering themes later than class
     * load will not appear until the declaration is re-run — a real limitation, lifted when the
     * select control learns dynamic options, and preferred to a dropdown whose contents change
     * while it is open.</p>
     */
    public static final Setting<String> UI_THEME = themeSetting();

    public static final Setting<String> EDITOR_SCHEME = schemeSetting();

    private static Setting<String> themeSetting() {
        ThemeRegistry.registerBuiltins();
        return Setting.select("appearance.theme", "Theme",
                        displayNames(ThemeRegistry.themes()), "Crystal Dark")
                .description("The UI theme — panels, buttons, trees, every surface but the editor's text.");
    }

    private static Setting<String> schemeSetting() {
        ThemeRegistry.registerBuiltins();
        // ISLANDS DARK, matching the frame. The chrome draws IntelliJ's window and the document was
        // painted in VS Code's palette -- both good, and recognisably from different products, with the
        // seam most visible exactly where the eye spends its time. Dark+ stays in the list because a VS
        // Code user should be able to have it back in one click; that IS the second axis working.
        return Setting.select("appearance.editorScheme", "Editor color scheme",
                        displayNames(ThemeRegistry.schemes()), "Islands Dark")
                .description("Colours inside the editor: syntax, selection, gutter, guides. "
                        + "Independent of the UI theme.");
    }

    private static List<String> displayNames(List<UiTheme> registered) {
        List<String> names = new ArrayList<>();
        for (UiTheme theme : registered) names.add(theme.displayName());
        return names;
    }

    /** The registry id for a stored display name, or {@code null} when the theme has gone —
     * which {@code setTheme(null)} degrades to "unthemed" rather than to anything surprising. */
    private static String themeId(List<UiTheme> pool, String displayName) {
        for (UiTheme theme : pool) {
            if (theme.displayName().equals(displayName)) return theme.id();
        }
        return null;
    }

    // ── Workbench ───────────────────────────────────────────────────────────────────────────────

    public static final Setting<Boolean> RESTORE_SESSION =
            Setting.bool("workbench.restoreSession", "Reopen the last session", true)
                    .description("Restore the pane arrangement and the files that were open when a "
                            + "project is reopened.");

    public static final Setting<Boolean> RESTORE_VIEW_STATE =
            Setting.bool("workbench.restoreViewState", "Restore cursor and folds", true)
                    .description("Put the caret, scroll position and collapsed regions back where they "
                            + "were when a file is reopened.");

    /**
     * Declares the set. Idempotent — {@link SettingsRegistry} replaces a same-id declaration, and these
     * are the same instances every time, so re-registering is a no-op rather than a log line.
     */
    public static void declare() {
        // The NAVIGATION, declared rather than derived from the ids: adding a setting must never grow a
        // node in somebody menu by accident. @see SettingsCategory
        SettingsCategory.page("explorer", "Explorer");
        SettingsCategory.page("editor", "Editor");
        SettingsCategory.page("workbench", "Workbench");
        SettingsCategory.page("appearance", "Appearance & Behavior");

        declareDemo();

        SettingsRegistry registry = SettingsRegistry.get();
        registry.register(AUTO_REVEAL);
        registry.register(CONFIRM_DELETE);
        registry.register(SORT_ORDER);
        registry.register(FOLDING);
        registry.register(SCROLL_BEYOND_LAST_LINE);
        registry.register(TAB_SIZE);
        registry.register(CARET_BLINK);
        registry.register(RESTORE_SESSION);
        registry.register(RESTORE_VIEW_STATE);
        registry.register(UI_THEME);
        registry.register(EDITOR_SCHEME);
    }

    /**
     * SCAFFOLDING — a spread of categories, sub-pages, sections and control kinds, so the window can be
     * exercised at a size it will actually see.
     *
     * <p>Every one of these is real: declared, rendered and written to the {@code USER} layer like any
     * other. None is wired to anything, which is exactly the thing this file otherwise forbids — so this
     * is temporary, and the point of keeping it in one method is that deleting it is one call.</p>
     */
    private static void declareDemo() {
        SettingsCategory.page("editor.general", "General");
        SettingsCategory.page("editor.appearance", "Appearance");
        SettingsCategory.section("editor.appearance.a11y", "Accessibility");
        SettingsCategory.page("editor.codeStyle", "Code Style");
        SettingsCategory.page("editor.codeStyle.java", "Java But This One Is Really Look like goddamn");
        SettingsCategory.page("editor.codeStyle.glsl", "GLSL");
        SettingsCategory.page("appearance.notifications", "Notifications");
        SettingsCategory.page("build", "Build & Deployment");
        SettingsCategory.page("build.shaders", "Shaders");
        SettingsCategory.page("tools", "Tools");

        SettingsRegistry registry = SettingsRegistry.get();
        for (Setting<?> setting : new Setting<?>[]{
                Setting.bool("editor.general.smartHome", "Smart Home key", true)
                        .description("Home moves to the first non-whitespace character first."),
                Setting.bool("editor.general.stripTrailing", "Strip trailing whitespace", false),
                Setting.integer("editor.general.undoLimit", "Undo history size", 100),
                Setting.select("editor.general.lineEndings", "Line endings",
                        java.util.List.of("LF", "CRLF", "System"), "LF"),
                Setting.bool("editor.appearance.showIndentGuides", "Show indent guides", true),
                Setting.bool("editor.appearance.showWhitespace", "Show whitespace", false),
                Setting.number("editor.appearance.lineHeight", "Line height", 1.2),
                Setting.integer("editor.appearance.a11y.zoom", "Zoom", 100)
                        .description("Percentage. Alt+Shift+= and Alt+Shift+- change it."),
                Setting.bool("editor.appearance.a11y.highContrastCaret", "High contrast caret", false),
                Setting.integer("editor.codeStyle.java.indent", "Indent", 4),
                Setting.integer("editor.codeStyle.java.continuationIndent", "Continuation indent", 8),
                Setting.bool("editor.codeStyle.java.braceOnNewLine", "Brace on new line", false),
                Setting.integer("editor.codeStyle.glsl.indent", "Indent", 4),
                Setting.select("editor.codeStyle.glsl.precision", "Default precision",
                        java.util.List.of("lowp", "mediump", "highp"), "highp"),
                Setting.bool("appearance.notifications.onBuildFinished", "Build finished", true),
                Setting.bool("appearance.notifications.onError", "Error", true),
                Setting.integer("appearance.notifications.dismissAfter", "Dismiss after (seconds)", 8),
                Setting.bool("build.shaders.compileOnSave", "Compile on save", true),
                Setting.bool("build.shaders.warningsAsErrors", "Warnings as errors", false),
                Setting.select("build.shaders.target", "Target profile",
                        java.util.List.of("GL 3.3", "GL 4.3", "GL 4.6"), "GL 3.3"),
                Setting.string("build.outputDirectory", "Output directory", "build/shaders"),
                Setting.bool("tools.autoSave", "Auto-save", true),
                Setting.integer("tools.autoSaveDelay", "Auto-save delay (seconds)", 15),
                Setting.bool("workbench.confirmExit", "Confirm before closing", false)}) {
            registry.register(setting);
        }
    }

    /** The declared sort order as the constant it names, falling back when a record names a dead one. */
    public static WorkspaceTreeSource.SortOrder sortOrder(Workbench workbench) {
        try {
            return WorkspaceTreeSource.SortOrder.valueOf(workbench.resolve(SORT_ORDER));
        } catch (IllegalArgumentException removedConstant) {
            return WorkspaceTreeSource.SortOrder.DEFAULT;
        }
    }

    /**
     * Applies every declaration to {@code workbench}, now and whenever one changes.
     *
     * <p>Listens on the scope that <em>holds</em> the values rather than on the workbench's own store:
     * settings resolve outward, so a change made at the window (which is where the preferences window
     * writes) would never be heard by a listener attached to the workbench itself.</p>
     */
    public static void install(Workbench workbench, Settings settings) {
        declare();
        apply(workbench);
        settings.onChanged.connect(change -> apply(workbench));
    }

    /** Pushes the values in force into the widgets that read them. Safe to call as often as you like. */
    public static void apply(Workbench workbench) {
        // The two appearance axes. installInto is idempotent, and the manager's own same-id guard
        // makes the setTheme/setScheme pair a no-op on every apply() that didn't change them — so
        // an unrelated toggle does not re-substitute every stylesheet.
        UiThemeManager themes = UiThemeManager.getInstance();
        UIDocument window = workbench.document();
        if (window != null) themes.installInto(window.styles());
        themes.setTheme(themeId(ThemeRegistry.themes(), workbench.resolve(UI_THEME)));
        themes.setScheme(themeId(ThemeRegistry.schemes(), workbench.resolve(EDITOR_SCHEME)));

        workbench.explorerBinding.setAutoReveal(workbench.resolve(AUTO_REVEAL));
        workbench.fileTree().source().setSortOrder(sortOrder(workbench));
        workbench.fileTree().treeView().refresh();

        for (CgPath path : workbench.openPaths()) {
            TextEditor editor = workbench.editorFor(path);
            if (editor != null) applyTo(workbench, editor);
        }
    }

    /** The editor half, also called for a document that opens after the settings were installed. */
    public static void applyTo(Workbench workbench, TextEditor editor) {
        editor.setFoldingEnabled(workbench.resolve(FOLDING));
        editor.setScrollBeyondLastLine(workbench.resolve(SCROLL_BEYOND_LAST_LINE));
        editor.setTabSize(workbench.resolve(TAB_SIZE));
        editor.setCaretBlinkSeconds(workbench.resolve(CARET_BLINK).floatValue());
    }

    /** Where the preferences window writes. Not the document layer, which is undoable and per-graph. */
    public static final SettingsLayer EDITED_AT = SettingsLayer.USER;
}
