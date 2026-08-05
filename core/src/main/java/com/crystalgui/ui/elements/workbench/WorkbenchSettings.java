package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.document.TextFileDocument;

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

    public static final Setting<Boolean> AUTO_REVEAL =
            Setting.bool("explorer.autoReveal", "Reveal the active file", true)
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
    public static void install(Workbench workbench, com.crystalgui.core.settings.Settings settings) {
        declare();
        apply(workbench);
        settings.onChanged.connect(change -> apply(workbench));
    }

    /** Pushes the values in force into the widgets that read them. Safe to call as often as you like. */
    public static void apply(Workbench workbench) {
        workbench.setAutoReveal(workbench.resolve(AUTO_REVEAL));
        workbench.fileTree().source().setSortOrder(sortOrder(workbench));
        workbench.fileTree().treeView().refresh();

        for (CgPath path : workbench.openPaths()) {
            if (workbench.documentFor(path) instanceof TextFileDocument text) applyTo(workbench, text.editor());
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
