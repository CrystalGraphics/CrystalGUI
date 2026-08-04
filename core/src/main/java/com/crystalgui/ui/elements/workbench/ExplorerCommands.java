package com.crystalgui.ui.elements.workbench;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceFileService;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.ui.elements.chrome.InputDialog;
import com.crystalgui.ui.input.keymap.Keymap;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * What the Project panel can do to a file — New, Rename, Delete, Copy Path.
 *
 * <h3>Commands, so the menu is not a fourth place to keep in sync</h3>
 *
 * <p>Every one of these appears in the context menu, in the command palette and on a key, from one
 * registration. {@link ContextMenu} builds rows from ids alone, so nothing here is restated in a menu
 * definition — see its javadoc for why a lambda-built menu is the one that goes stale.</p>
 *
 * <h3>Everything acts on the tree's selection, resolved from the invoking element</h3>
 *
 * <p>Not on a remembered "current file". A command invoked from a right-click has to act on the row that
 * was clicked, and one invoked from the palette on whatever is selected — both of which are the same
 * question asked of the same tree, reached by walking outward from {@link CommandContext#source()}. A
 * field updated on selection would answer correctly for one of those two and silently wrongly for the
 * other.</p>
 *
 * <h3>Names come from a dialog, not an inline editor</h3>
 *
 * <p>IntelliJ's choice, and the ellipsis in its own menu says so: <i>Rename…</i>, <i>New ▸ File</i> both
 * open a prompt. VS Code edits in the row instead. The dialog is taken here because it is the smaller
 * build and because a virtualised tree recycles its rows — an editor mounted in one is an editor that
 * moves to a different file when the list scrolls. Inline rename is a real improvement and a separate
 * one; it is not a prerequisite.</p>
 */
public final class ExplorerCommands {

    public static final String NEW_FILE = "explorer.newFile";
    public static final String NEW_FOLDER = "explorer.newFolder";
    public static final String RENAME = "explorer.rename";
    public static final String DELETE = "explorer.delete";
    public static final String COPY_PATH = "explorer.copyPath";
    public static final String COPY_RELATIVE_PATH = "explorer.copyRelativePath";
    public static final String REFRESH = "explorer.refresh";
    public static final String CUT = "explorer.cut";
    public static final String COPY = "explorer.copy";
    public static final String PASTE = "explorer.paste";

    /** One clipboard per application, not per tree — cut in one panel, paste in another. */
    private static final ExplorerClipboard CLIPBOARD = new ExplorerClipboard();

    /** What Cut and Copy currently hold. Exposed so a test can assert on the intent, not just the effect. */
    public static ExplorerClipboard clipboard() {
        return CLIPBOARD;
    }

    private ExplorerCommands() {
    }

    public static void register(CommandRegistry registry, Workbench workbench) {
        if (registry.contains(RENAME)) return;

        registry.register(Command.of(NEW_FILE, "New File…")
                .run(context -> promptNew(workbench, context, false))
                .enabledWhen(context -> target(context) != null));

        registry.register(Command.of(NEW_FOLDER, "New Folder…")
                .run(context -> promptNew(workbench, context, true))
                .enabledWhen(context -> target(context) != null));

        registry.register(Command.of(RENAME, "Rename…")
                .run(context -> promptRename(workbench, context))
                // The project root is not a file and has no parent to rename within.
                .enabledWhen(context -> isRenameable(target(context))));

        registry.register(Command.of(DELETE, "Delete")
                .run(context -> confirmDelete(workbench, context))
                .enabledWhen(context -> isRenameable(target(context))));

        registry.register(Command.of(COPY_PATH, "Copy Path")
                .run(context -> copy(workbench, target(context), false))
                .enabledWhen(context -> target(context) != null));

        registry.register(Command.of(COPY_RELATIVE_PATH, "Copy Relative Path")
                .run(context -> copy(workbench, target(context), true))
                .enabledWhen(context -> target(context) != null));

        registry.register(Command.of(CUT, "Cut")
                .run(context -> CLIPBOARD.cut(targets(context)))
                .enabledWhen(context -> !targets(context).isEmpty()
                        && targets(context).stream().noneMatch(CgPath::isProjectRoot)));

        registry.register(Command.of(COPY, "Copy")
                .run(context -> CLIPBOARD.copy(targets(context)))
                .enabledWhen(context -> !targets(context).isEmpty()
                        && targets(context).stream().noneMatch(CgPath::isProjectRoot)));

        registry.register(Command.of(PASTE, "Paste")
                .run(context -> paste(workbench, context))
                .enabledWhen(context -> !CLIPBOARD.isEmpty() && target(context) != null));

        registry.register(Command.of(REFRESH, "Reload from Disk")
                .run(context -> {
                    CgPath path = target(context);
                    CgPath directory = path == null ? null
                            : workbench.fileTree().isDirectory(path) ? path : path.parent();
                    workbench.fileTree().source().invalidate(directory);
                    workbench.fileTree().treeView().refresh();
                })
                .enabledWhen(context -> target(context) != null));
    }

    /**
     * The defaults.
     *
     * <p>{@code F2} rather than IntelliJ's {@code Shift+F6}: F2 is the platform convention everywhere
     * outside a JetBrains IDE, and this panel is a file explorer before it is an IDE view. {@code Delete}
     * is bound on the tree rather than application-wide for the reason {@code GraphCommands} records —
     * a bare key at the root fires while typing into any editor sharing the window.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Mod+X", CUT);
        keymap.bind("Mod+C", COPY);
        keymap.bind("Mod+V", PASTE);
        keymap.bind("F2", RENAME);
        keymap.bind("Delete", DELETE);
        keymap.bind("Mod+N", NEW_FILE);
        keymap.bind("F5", REFRESH);
    }

    /** Registers on the window and binds on the file tree, which is what scopes the bare keys. */
    public static void install(UIWindow window, Workbench workbench) {
        register(window.getCommands(), workbench);
        bindDefaults(workbench.fileTree().keymap());
    }

    /** The menu the Project panel opens on a right-click. */
    public static ContextMenu menu() {
        return ContextMenu.builder()
                .submenu("New", sub -> sub.item(NEW_FILE, "File…").item(NEW_FOLDER, "Folder…"))
                .separator()
                .item(CUT)
                .item(COPY)
                .item(PASTE)
                .separator()
                .item(COPY_PATH)
                .item(COPY_RELATIVE_PATH)
                .separator()
                .item(RENAME)
                .item(DELETE)
                .separator()
                .item(REFRESH);
    }

    // ── Target resolution ───────────────────────────────────────────────────────────────────────

    /** Everything selected — what the commands that act on several things ask for. */
    private static List<CgPath> targets(CommandContext context) {
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof ProjectFileTree tree) return tree.selectedPaths();
        }
        return List.of();
    }

    /**
     * Pastes whatever is held into the selected folder.
     *
     * <p><b>Each item is issued independently rather than as one transaction.</b> Two files pasted into a
     * folder are two operations that can succeed or fail separately, and stopping the batch on the first
     * refusal would leave the user guessing which ones landed. Each reports its own status.</p>
     */
    private static void paste(Workbench workbench, CommandContext context) {
        CgPath selected = target(context);
        if (selected == null || CLIPBOARD.isEmpty()) return;
        CgPath destination = newParentFor(workbench, selected);
        boolean moving = CLIPBOARD.mode() == ExplorerClipboard.Mode.CUT;

        for (CgPath source : CLIPBOARD.consumeIfCut()) {
            // A folder pasted into itself, or into its own descendant, would move a directory under
            // itself -- which the filesystem refuses with a message about paths rather than about the
            // gesture. Refusing here says the useful thing.
            if (source.equals(destination) || source.contains(destination)) {
                workbench.onStatus.emit("cannot paste " + source.name() + " into itself");
                continue;
            }
            CgPath into = destination.resolve(source.name());
            if (source.equals(into)) {
                // Pasting a copy back where it came from: give it VS Code's incremental name rather than
                // refusing, which is what makes Copy then Paste a duplicate-in-place gesture.
                into = destination.resolve(WorkspaceFileService.incrementalName(
                        source.name(), namesIn(workbench, destination)));
            }
            CgPath finalTarget = into;
            if (moving) {
                workbench.files().move(source, finalTarget, false,
                        () -> workbench.onStatus.emit("moved " + finalTarget.name()),
                        failure -> workbench.onStatus.emit(
                                "move failed: " + source.name() + " -- " + failure.code()));
            } else {
                workbench.files().copyFile(source, finalTarget,
                        () -> workbench.onStatus.emit("copied " + finalTarget.name()),
                        failure -> workbench.onStatus.emit(
                                "copy failed: " + source.name() + " -- " + failure.code()));
            }
        }
    }

    /** The names already in a folder, as far as the tree has listed it — for incremental naming. */
    private static List<String> namesIn(Workbench workbench, CgPath directory) {
        List<String> names = new ArrayList<>();
        for (CgPath child : workbench.fileTree().source().children(directory)) names.add(child.name());
        return names;
    }

    @Nullable
    private static CgPath target(CommandContext context) {
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof ProjectFileTree tree) return tree.selectedPath();
        }
        return null;
    }

    /** A path that can be renamed or deleted — anything but a project root, which is not a file. */
    private static boolean isRenameable(@Nullable CgPath path) {
        return path != null && !path.isProjectRoot();
    }

    /** Where a New lands: inside the selection when it is a folder, beside it when it is a file. */
    private static CgPath newParentFor(Workbench workbench, CgPath selected) {
        return workbench.fileTree().isDirectory(selected) ? selected : selected.parent();
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────────────

    private static void promptNew(Workbench workbench, CommandContext context, boolean folder) {
        CgPath selected = target(context);
        if (selected == null) return;
        CgPath parent = newParentFor(workbench, selected);

        InputDialog.ask(context.source(), folder ? "New Folder" : "New File", "Name", "", name -> {
            CgPath path = parent.resolve(name);
            if (folder) {
                workbench.files().createFolder(path, () -> workbench.onStatus.emit("created " + name),
                        failure -> workbench.onStatus.emit("create failed: " + failure.code()));
            } else {
                workbench.files().create(path, "", () -> workbench.onStatus.emit("created " + name),
                        failure -> workbench.onStatus.emit("create failed: " + failure.code()));
            }
        });
    }

    private static void promptRename(Workbench workbench, CommandContext context) {
        CgPath path = target(context);
        if (!isRenameable(path)) return;
        CgPath parent = path.parent();

        InputDialog.ask(context.source(), "Rename", "New name", path.name(), name -> {
            if (name.equals(path.name())) return;
            workbench.files().move(path, parent.resolve(name), false,
                    () -> workbench.onStatus.emit("renamed to " + name),
                    failure -> workbench.onStatus.emit("rename failed: " + failure.code()));
        });
    }

    /**
     * Deletes, behind a confirmation.
     *
     * <p>{@code explorer.confirmDelete} defaults to {@code true} in VS Code, and the case for it is
     * stronger here: there is no version control underneath and no OS trash to fish it back out of, so a
     * delete is final until E5 lands a trash.</p>
     */
    private static void confirmDelete(Workbench workbench, CommandContext context) {
        CgPath path = target(context);
        if (!isRenameable(path)) return;
        boolean directory = workbench.fileTree().isDirectory(path);

        InputDialog.confirm(context.source(), "Delete",
                directory ? "Delete '" + path.name() + "' and everything in it?"
                        : "Delete '" + path.name() + "'?",
                () -> workbench.files().delete(path, directory,
                        () -> workbench.onStatus.emit("deleted " + path.name()),
                        failure -> workbench.onStatus.emit("delete failed: " + failure.code())));
    }

    /**
     * Puts a path on the clipboard.
     *
     * <p>Two forms because they are pasted into different places: an absolute {@code project:dir/file} for
     * anything that resolves paths, and a project-relative one for a message to somebody else. VS Code
     * ships both, with a separate separator setting for each.</p>
     */
    private static void copy(Workbench workbench, @Nullable CgPath path, boolean relative) {
        if (path == null) return;
        String text = relative ? path.path() : path.toString();
        CgPlatform.input().setClipboard(text);
        workbench.onStatus.emit("copied " + text);
    }

    /** Every command id this set owns, for a host building its own menus. */
    public static List<String> ids() {
        return List.of(NEW_FILE, NEW_FOLDER, RENAME, DELETE, COPY_PATH, COPY_RELATIVE_PATH, REFRESH,
                CUT, COPY, PASTE);
    }
}
