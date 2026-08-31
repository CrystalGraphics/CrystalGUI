package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.WorkspaceFileService;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.chrome.ContextMenu;
import com.crystalgui.ui.elements.InputDialog;
import com.crystalgui.ui.input.keymap.Keymap;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.ui.elements.chrome.Preferences;

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

    /** Open a file by name — VS Code's Ctrl+P, IntelliJ's Go to File. */
    public static final String GO_TO_FILE = "explorer.goToFile";



    /** Opens the tree's search box. Ctrl+F, which is what everybody presses. */
    public static final String FIND_IN_TREE = "explorer.find";

    /** The preferences window. VS Code's Ctrl+, — IntelliJ uses Ctrl+Alt+S, which is less universal. */
    public static final String PREFERENCES = "workbench.preferences";
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

    /**
     * Registers the explorer's commands. Global — nothing is captured.
     *
     * <p>Every one of these used to close over a {@code Workbench}, which is why this set was the last
     * holdout after commands went global: a captured owner makes registration un-repeatable, so the
     * second workbench would have driven the first. They now resolve it from
     * {@link Workbench#WORKBENCH} in the data context, which answers with the workbench the
     * <em>focused</em> element is in — the same question, asked at the right time.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(ExplorerCommands.class, ExplorerCommands::declare);
    }

    /**
     * The workbench this command is acting on, or null when the focus is not in one.
     *
     * <p>Null is ordinary: a command asks in {@code enabledWhen} and disables itself, and because
     * {@link Command#execute} refuses to run a disabled command, a {@code run} body may assume whatever
     * its own enablement asserted.</p>
     */
    @Nullable
    private static Workbench workbenchFor(CommandContext context) {
        return context.data().get(Workbench.WORKBENCH);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(NEW_FILE, "New File…")
                .menu(MenuId.EXPLORER_NEW, "1_new", 10)
                // TWO PLACEMENTS, ONE COMMAND. The explorer's New acts on the right-clicked folder and
                // the main menu's on the project root, but that difference is already inside
                // destinationFor -- so the same command legitimately appears in both, which is exactly
                // what a list of placements is for.
                .menu(MenuId.MAIN_FILE_NEW, "1_new", 10)
                .binding("Mod+N")
                .run(context -> promptNew(workbenchFor(context), context, false))
                .enabledWhen(context -> workbenchFor(context) != null
                        && destinationFor(workbenchFor(context), context) != null
                        && mayWrite(workbenchFor(context),
                                destinationFor(workbenchFor(context), context))));

        registry.register(Command.of(NEW_FOLDER, "New Folder…")
                .menu(MenuId.EXPLORER_NEW, "1_new", 20)
                .menu(MenuId.MAIN_FILE_NEW, "1_new", 20)
                .run(context -> promptNew(workbenchFor(context), context, true))
                .enabledWhen(context -> workbenchFor(context) != null
                        && destinationFor(workbenchFor(context), context) != null
                        && mayWrite(workbenchFor(context),
                                destinationFor(workbenchFor(context), context))));

        registry.register(Command.of(RENAME, "Rename…")
                .menu(MenuId.EXPLORER_CONTEXT, "4_modify", 10)
                // F2 EVERYWHERE -- Windows Explorer, VS Code, IntelliJ. Declared on the command rather
                // than bound onto the tree, so the palette advertises it and a user can remap it.
                .binding("F2")
                .run(context -> promptRename(workbenchFor(context), context))
                // The project root is not a file and has no parent to rename within.
                .enabledWhen(context -> workbenchFor(context) != null && isRenameable(target(context))
                        && mayWrite(workbenchFor(context), target(context))));

        registry.register(Command.of(DELETE, "Delete")
                .menu(MenuId.EXPLORER_CONTEXT, "4_modify", 20)
                .run(context -> confirmDelete(workbenchFor(context), context))
                .enabledWhen(context -> workbenchFor(context) != null && isRenameable(target(context))
                        && mayWrite(workbenchFor(context), target(context))));

        registry.register(Command.of(COPY_PATH, "Copy Path")
                .menu(MenuId.EXPLORER_CONTEXT, "3_paths", 10)
                .run(context -> copy(workbenchFor(context), target(context), false))
                .enabledWhen(context -> workbenchFor(context) != null && target(context) != null));

        registry.register(Command.of(COPY_RELATIVE_PATH, "Copy Relative Path")
                .menu(MenuId.EXPLORER_CONTEXT, "3_paths", 20)
                .run(context -> copy(workbenchFor(context), target(context), true))
                .enabledWhen(context -> workbenchFor(context) != null && target(context) != null));

        registry.register(Command.of(CUT, "Cut")
                .menu(MenuId.EXPLORER_CONTEXT, "2_clipboard", 10)
                .run(context -> CLIPBOARD.cut(targets(context)))
                .enabledWhen(context -> !targets(context).isEmpty()
                        && targets(context).stream().noneMatch(CgPath::isProjectRoot)));

        registry.register(Command.of(COPY, "Copy")
                .menu(MenuId.EXPLORER_CONTEXT, "2_clipboard", 20)
                .run(context -> CLIPBOARD.copy(targets(context)))
                .enabledWhen(context -> !targets(context).isEmpty()
                        && targets(context).stream().noneMatch(CgPath::isProjectRoot)));

        registry.register(Command.of(PASTE, "Paste")
                .menu(MenuId.EXPLORER_CONTEXT, "2_clipboard", 30)
                .run(context -> paste(workbenchFor(context), context))
                // Pasting into the empty space below the files means pasting into the project root,
                // which is both useful and what every file manager does.
                .enabledWhen(context -> !CLIPBOARD.isEmpty()
                        && workbenchFor(context) != null
                        && destinationFor(workbenchFor(context), context) != null));

        // EVERYTHING LISTED, not the selected row's folder.
        //
        // Scoping it to the selection is what a file operation does, because an operation knows which
        // folder it touched. A user pressing F5 knows the opposite: they are asking precisely because
        // something changed that the tree cannot know about, and they have no way to tell it where. So a
        // per-folder reload did nothing whenever the change was anywhere else -- which is most of the
        // time -- and read first as "F5 does not work" and then as "F5 needs two presses", since a second
        // press after clicking elsewhere would sometimes land on the right folder.
        //
        // Cost is bounded by what is already on screen: only directories that have been listed are
        // re-listed, so a collapsed tree is one call.
        // Registered here rather than in a chrome-only place because the settings it shows are the
        // workbench's, and because this is where the global keymap is already being written.
        registry.register(Command.of(PREFERENCES, "Preferences…")
                .binding("Alt+Shift+S")
                .run(context -> {
                    UIWindow window = context.data().get(UiDataKeys.WINDOW);
                    if (window == null) return;
                    // THE STORE THE APPLICATION SAYS IT LISTENS ON -- asked, not derived.
                    //
                    // This used to be `window.ui.rootElement.settings()`, on the reasoning that settings
                    // resolve outward so writing at the root is what makes a preference reach every panel
                    // rather than one subtree. The reasoning is right and the expression stopped matching
                    // it: with a window compositor the editor opens as a WINDOW, so the root element is
                    // the desktop's and the editor's own store is several levels below it.
                    //
                    // Both halves then still looked correct. The value was written, and it RESOLVED
                    // correctly too -- the walk goes outward, so a value at the root is visible from
                    // inside. What never happened is the notification: `WorkbenchSettings.install`
                    // subscribes to the editor's store, which nothing had written to, so `apply` never
                    // ran. Picking a theme stored the choice, changed nothing on screen, and lost it on
                    // restart, because `savePreferences` writes the editor store's user layer.
                    //
                    // Invisible in the harness, whose scene is `new UIWindow(Ui.of(editor))` -- there the
                    // editor IS the root element and the two expressions are the same object.
                    Settings host = context.data().get(UiDataKeys.SETTINGS_HOST);
                    Preferences.open(window,
                            host != null ? host : window.ui.rootElement.settings());
                }));

        registry.register(Command.of(FIND_IN_TREE, "Find in Project View")
                // ELEMENT-SCOPED, bound on the tree rather than declared globally: Ctrl+F means Find in
                // an editor and this must not take it away from one. The resolver walks the focused
                // element's chain first, so the tree's own binding wins only while the tree has focus.
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    if (workbench != null && workbench.fileTree() != null) {
                        workbench.fileTree().openFind();
                    }
                })
                .enabledWhen(context -> workbenchFor(context) != null));

        registry.register(Command.of(GO_TO_FILE, "Go to File…")
                // THREE CHORDS, ONE LIST, which is the reference behaviour rather than a convenience:
                // IntelliJ's Ctrl+N and Ctrl+Shift+N are two doors into one window. Mod+P is VS Code's,
                // Mod+T is its Go to Symbol in Workspace, Mod+Shift+T is Eclipse's Open Type -- and every
                // one of them opens the same picker, because "open the thing called this" does not become
                // a different gesture when the thing lives in a jar. IntelliJ's own Mod+N is New File here.
                .binding("Mod+P", "Mod+T", "Mod+Shift+T")
                // FILE ▸ OPEN, and this is the honest version of it. There is no native file dialog to
                // reach -- that is a platform service this engine deliberately does not have -- and a
                // workspace-scoped quick-open is what both references put on Ctrl+P anyway. Naming it
                // "Open" in the menu and "Go to File…" in the palette is the label-override case
                // ContextMenu.item(id, label) already exists for.
                .menu(MenuId.MAIN_FILE, "2_open", 10)
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    UIWindow window = workbench == null ? null : workbench.getAttachedWindow();
                    if (window == null) return;
                    long profiled = FrameProfile.enter("Ctrl+P explorer.goToFile");
                    GoToFile.open(window, workbench);
                    FrameProfile.leave(profiled, "Ctrl+P explorer.goToFile");
                })
                // Enabled whenever there is a project, not whenever something is selected: it is how you
                // reach a file you have NOT got selected, which is the whole point of it.
                .enabledWhen(context -> hasProject(workbenchFor(context))));

        registry.register(Command.of(REFRESH, "Reload from Disk")
                .menu(MenuId.EXPLORER_CONTEXT, "5_refresh", 10)
                .binding("F5")
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    workbench.fileTree().source().invalidateAll();
                    workbench.fileTree().treeView().refresh();
                })
                // No target needed any more -- it reloads the whole tree, so the only thing that could make
                // it meaningless is having no project open at all.
                .enabledWhen(context -> hasProject(workbenchFor(context))));
    }

    private static boolean hasProject(@Nullable Workbench workbench) {
        return workbench != null && !workbench.fileTree().source().roots().isEmpty();
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
        // Mod+F is NOT bound here any more -- TreeSearch binds it on the tree it is installed on, so
        // every tree gets it rather than only this one. The command survives for the palette.
        keymap.bind("Mod+X", CUT);
        keymap.bind("Mod+C", COPY);
        keymap.bind("Mod+V", PASTE);
        keymap.bind("F2", RENAME);
        keymap.bind("Delete", DELETE);
    }

    // The application-wide chords -- Mod+N, Alt+Shift+S, Mod+P, F5 -- are DECLARED on the commands
    // above rather than bound onto a root keymap here, which is what a declared binding means.
    //
    // The reasoning that put them at the root is unchanged and worth keeping: a keymap resolves outward
    // from the FOCUSED element, so a binding on the tree is unreachable while nothing in the tree has
    // focus -- which is how the panel looks the moment it opens, and is why F5 "needed a click first".
    // Reload and Go to File are exactly the verbs you reach for before touching anything. All four are
    // chords or function keys, so unlike Delete and F2 they cannot fire while typing.
    //
    // Alt+Shift+S, NOT VS Code's Ctrl+comma, and that is a deliberate retreat rather than a preference.
    // Ctrl+comma is bound correctly and fires in every test -- including one built in the application's
    // real shape, and one carrying the printable character a real keyboard sends with it -- and it does
    // nothing in the running harness. The obvious explanation was wrong: on all four of this machine's
    // keyboard layouts `,` maps to scancode 0x33, exactly CgKeyCodes.KEY_COMMA, so the right code is
    // arriving. Whatever eats it lives somewhere no test has reproduced, and a shortcut that works on the
    // bench and not in the product is worse than one spelled differently.
    // -Dcrystalgui.keymap.trace=true is what will name the cause if anyone wants Ctrl+comma back.

    /**
     * The menu the Project panel opens on a right-click — <b>queried, not written</b>.
     *
     * <p>This was a literal builder listing thirteen items, which meant nothing could add a fourteenth
     * without editing this method. Every command above now declares where it sits with
     * {@link Command#menu}, and this asks. A feature that wants "New ▸ Shader Graph" declares it on its
     * own command and appears here, knowing nothing about the explorer.</p>
     *
     * <p>Order comes from the group names ({@code 1_new}, {@code 2_clipboard}, …), VS Code's convention,
     * and separators fall out of the group boundaries.</p>
     */
    public static ContextMenu menu() {
        return ContextMenu.of(MenuId.EXPLORER_CONTEXT);
    }

    // ── Target resolution ───────────────────────────────────────────────────────────────────────

    /** Everything selected — what the commands that act on several things ask for. */
    private static List<CgPath> targets(CommandContext context) {
        for (UIElement element = UIElement.sourceOf(context); element != null; element = element.getParent()) {
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
        CgPath destination = destinationFor(workbench, context);
        if (destination == null || CLIPBOARD.isEmpty()) return;
        boolean moving = CLIPBOARD.mode() == ExplorerClipboard.Mode.CUT;

        // ONE UNDO STEP FOR THE WHOLE PASTE, the same rule the drop follows -- see
        // WorkspaceFileService.batch for why the group cannot be closed at the end of this loop.
        WorkspaceFileService.Batch batch = workbench.files().batch(moving ? "move files" : "paste files");
        for (CgPath source : CLIPBOARD.consumeIfCut()) {
            // CLAIMED FIRST, so every path out of this iteration -- including the two refusals below --
            // settles its share of the batch. A `continue` that skipped it would leave the group open
            // forever and the whole paste would never become one undo step.
            Runnable done = batch.track();
            // A folder pasted into itself, or into its own descendant, would move a directory under
            // itself -- which the filesystem refuses with a message about paths rather than about the
            // gesture. Refusing here says the useful thing.
            if (source.equals(destination) || source.contains(destination)) {
                Notifications.show(Notification.error("Cannot paste").withDetail(source.name() + " into itself"));
                done.run();
                continue;
            }
            CgPath into = destination.resolve(source.name());
            List<String> taken = namesIn(workbench, destination);
            if (source.equals(into) || (!moving && taken.contains(into.name()))) {
                // A COPY NEVER OVERWRITES. VS Code's findValidPasteFileTarget does the same, and the two
                // cases are one rule rather than two: pasting back where it came from and pasting onto a
                // namesake in another folder are both "this name is taken", and both mean the user wants
                // a second copy -- they asked for a copy.
                //
                // This used to fire ONLY for paste-in-place, so a copy into a folder that already had the
                // name went through as a plain write. That is the data-loss half of the same line.
                into = destination.resolve(WorkspaceFileService.incrementalName(
                        source.name(), taken));
            } else if (moving && taken.contains(into.name())) {
                // A MOVE ONTO A NAMESAKE IS DESTRUCTIVE AND IS REFUSED, not silently renamed.
                //
                // Renaming would be wrong in a way a copy's rename is not: the user asked to move this
                // file HERE, and quietly landing it beside the one already there leaves two files where
                // they asked for one. VS Code prompts to replace; there is no undo for a clobbered file
                // here and no OS trash under it, so refusing is the honest version of that prompt until
                // one exists -- and it says which name, which is what makes it actionable.
                Notifications.show(Notification.error("Cannot move " + source.name())
                        .withDetail(destination.name() + " already has a " + into.name()));
                done.run();
                continue;
            }
            CgPath finalTarget = into;
            if (moving) {
                // ONE completion hook, not two copies of it -- the batch has to be told either way.
                workbench.files().move(source, finalTarget, false, done);
            } else {
                workbench.files().copyFile(source, finalTarget, done);
            }
        }
        batch.sealed();
    }

    /** The names already in a folder, as far as the tree has listed it — for incremental naming. */
    private static List<String> namesIn(Workbench workbench, CgPath directory) {
        List<String> names = new ArrayList<>();
        for (CgPath child : workbench.fileTree().source().children(directory)) names.add(child.name());
        return names;
    }

    @Nullable
    private static CgPath target(CommandContext context) {
        for (UIElement element = UIElement.sourceOf(context); element != null; element = element.getParent()) {
            if (element instanceof ProjectFileTree tree) return tree.selectedPath();
        }
        return null;
    }

    /** A path that can be renamed or deleted — anything but a project root, which is not a file. */
    private static boolean isRenameable(@Nullable CgPath path) {
        return path != null && !path.isProjectRoot();
    }

    /**
     * Whether a write here is worth offering — 5.4.
     *
     * <p>{@code enabledWhen} runs on the client, so it cannot ask the server <i>may I?</i>. Before this,
     * Delete looked perfectly available to a non-operator and the refusal arrived as a
     * {@code NO_PERMISSIONS} failure after a round trip. The answer is now cached and pushed, which is
     * VS Code's context-key model.</p>
     *
     * <p><b>Unknown is yes</b>, and deliberately: the cached answer is per project while the real check
     * is per path, and it can be stale or not yet arrived. A wrongly-greyed command is a thing the user
     * cannot do and cannot explain; a wrongly-live one fails with a reason the server wrote. @see
     * WorkspaceClient#mayWrite</p>
     */
    private static boolean mayWrite(@Nullable Workbench workbench, @Nullable CgPath path) {
        return workbench == null || workbench.files().mayWrite(path);
    }

    /** Where a New lands: inside the selection when it is a folder, beside it when it is a file. */
    private static CgPath newParentFor(Workbench workbench, CgPath selected) {
        return workbench.fileTree().isDirectory(selected) ? selected : selected.parent();
    }

    /**
     * Where a New or a Paste goes when nothing is selected — the first project's root.
     *
     * <p><b>Nothing selected is the normal state, not an edge case.</b> Right-clicking the empty space
     * below the files is how you make a file at the top level, and it is what the panel looks like the
     * moment it opens. Requiring a selection made New File unavailable in exactly the situation it is most
     * wanted, and made a global Ctrl+N do nothing anywhere.</p>
     *
     * <p>IntelliJ resolves the same way: with no selection its New acts on the project root.</p>
     */
    @Nullable
    private static CgPath destinationFor(Workbench workbench, CommandContext context) {
        CgPath selected = target(context);
        if (selected != null) return newParentFor(workbench, selected);
        List<CgPath> roots = workbench.fileTree().source().roots();
        return roots.isEmpty() ? null : roots.get(0);
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates an entry, named in the tree rather than in a dialog.
     *
     * <h3>Inline, and the dialog is the fallback rather than the other way round</h3>
     *
     * <p>VS Code and IntelliJ both put a real input <em>in the row</em>: you see the folder it will land
     * in, its siblings, and the icon its extension gives it, all while typing. A modal hides every one of
     * those behind itself, and it hides them at the exact moment they are the question being asked.</p>
     *
     * <p>The dialog remains for the case with no tree to put a row in -- New File invoked from the
     * palette while the explorer is closed. That is a real path, not a hedge: {@code destinationFor}
     * answers with the project root there, and there is no row for it.</p>
     */
    private static void promptNew(Workbench workbench, CommandContext context, boolean folder) {
        CgPath parent = destinationFor(workbench, context);
        if (parent == null) return;

        ProjectFileTree tree = workbench.fileTree();
        if (tree != null && tree.getAttachedWindow() != null) {
            tree.beginNew(parent, folder, name -> createEntry(workbench, parent.resolve(name), folder));
            return;
        }
        InputDialog.ask(UIElement.sourceOf(context), folder ? "New Folder" : "New File", "Name", "", name ->
                createEntry(workbench, parent.resolve(name), folder));
    }

    private static void createEntry(Workbench workbench, CgPath path, boolean folder) {
        {
            if (folder) {
                workbench.files().createFolder(path);
            } else {
                // OPENED, not merely created. Making a file is a statement of intent to edit it, and every
                // editor that has a New File treats it that way -- VS Code, IntelliJ and Visual Studio all
                // leave you in the new file with the caret in it. Creating one and leaving the user to find
                // it in the tree makes New File feel like it did nothing at all, which is how it was
                // reported.
                //
                // A FOLDER is deliberately not opened: there is nothing to edit, and revealing it would
                // fight the selection the user is about to make inside it.
                workbench.files().create(path, "", () -> workbench.openFile(path), null);
            }
        }
    }

    /**
     * Renames in place. @see #promptNew for why the dialog is the fallback.
     *
     * <p>The row has to be <b>revealed</b> first: Rename reached from the palette or the menu bar acts on
     * the selected path, which may be scrolled out of view or inside a folded folder -- and an editor
     * opened on a row nobody can see is an edit with no visible subject.</p>
     */
    private static void promptRename(Workbench workbench, CommandContext context) {
        CgPath path = target(context);
        if (!isRenameable(path)) return;
        CgPath parent = path.parent();

        ProjectFileTree tree = workbench.fileTree();
        if (tree != null && tree.getAttachedWindow() != null) {
            tree.reveal(path);
            tree.beginRename(path, name -> workbench.files().move(path, parent.resolve(name), false));
            return;
        }
        InputDialog.ask(UIElement.sourceOf(context), "Rename", "New name", path.name(), name -> {
            if (name.equals(path.name())) return;
            workbench.files().move(path, parent.resolve(name), false);
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

        if (!workbench.resolve(WorkbenchSettings.CONFIRM_DELETE)) {
            // Turned off deliberately, so it deletes. Still routed through the same file service, so undo
            // and the trash behave identically -- the setting removes the question, not the safety net.
            workbench.files().delete(path, directory);
            return;
        }

        InputDialog.confirm(UIElement.sourceOf(context), "Delete",
                directory ? "Delete '" + path.name() + "' and everything in it?"
                        : "Delete '" + path.name() + "'?",
                () -> workbench.files().delete(path, directory));
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
        Notifications.show(Notification.info("Copied").withDetail(text));
    }

    /** Every command id this set owns, for a host building its own menus. */
    public static List<String> ids() {
        return List.of(NEW_FILE, NEW_FOLDER, RENAME, DELETE, COPY_PATH, COPY_RELATIVE_PATH, REFRESH,
                GO_TO_FILE,
                CUT, COPY, PASTE);
    }
}
