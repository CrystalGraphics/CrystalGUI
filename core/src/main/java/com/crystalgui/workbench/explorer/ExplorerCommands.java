package com.crystalgui.workbench.explorer;

import com.crystalgui.core.trace.FrameProfile;
import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.document.NewDocumentContext;
import com.crystalgui.document.NewDocumentKind;
import com.crystalgui.document.NewDocumentKinds;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.InputDialog;

import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.WorkbenchSettings;
import com.crystalgui.workbench.chrome.palette.CommandPalette;
import com.crystalgui.workbench.search.GoToFile;
import java.util.List;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.fs.project.SourceRoots;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.workbench.chrome.preferences.Preferences;

/**
 * What the Project panel can do beyond editing the selection — New, Copy Path, Reload, Restore Deleted.
 * Cut, Copy, Paste, Rename and Delete are {@code TreeEditing}'s, performed by {@link ExplorerEditModel}.
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
 */
public final class ExplorerCommands {

    /** What a seeded template writes between lines. Java source, so LF regardless of host. */
    private static final String NL = "\n";

    public static final String NEW_FILE = "explorer.newFile";
    public static final String NEW_FOLDER = "explorer.newFolder";

    /** The New ▸ presets, offered only where they make sense. @see #contributeNewMenu */
    public static final String NEW_PACKAGE = "explorer.newPackage";
    public static final String NEW_JAVA_CLASS = "explorer.newJavaClass";
    public static final String NEW_PACKAGE_INFO = "explorer.newPackageInfo";
    public static final String NEW_JS_FILE = "explorer.newJavaScriptFile";
    public static final String COPY_PATH = "explorer.copyPath";
    public static final String COPY_RELATIVE_PATH = "explorer.copyRelativePath";
    public static final String REFRESH = "explorer.refresh";

    /**
     * <b>Restore Deleted File…</b> — the trash, made reachable.
     *
     * <p>Every delete has been kept on the server since deletes were reversible; nothing could ask what
     * was in there, so the only recoverable deletion was one this session still held a receipt for.</p>
     */
    public static final String RESTORE_DELETED = "explorer.restoreDeleted";

    /** Reveal the active editor's file in the tree — IntelliJ's Select Opened File. */
    public static final String SELECT_OPENED_FILE = "explorer.selectOpenedFile";

    /** Open a file by name — VS Code's Ctrl+P, IntelliJ's Go to File. */
    public static final String GO_TO_FILE = "explorer.goToFile";



    /** Opens the tree's search box. Ctrl+F, which is what everybody presses. */
    public static final String FIND_IN_TREE = "explorer.find";

    /** The preferences window. VS Code's Ctrl+, — IntelliJ uses Ctrl+Alt+S, which is less universal. */
    public static final String PREFERENCES = "workbench.preferences";

    /** The preferences window, on its Editor Tabs page. */
    public static final String CONFIGURE_EDITOR_TABS = "workbench.configureEditorTabs";

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

    /**
     * The tree a command acts on.
     *
     * <p>Asked of the CONTEXT rather than of the workbench, which is what let the explorer become an
     * extension — the engine no longer holds a field for a panel an application may not enable. Null is
     * an ordinary answer and every caller already guarded for it, because {@code fileTree()} was
     * nullable too. @see ProjectFileTree#PROJECT_TREE
     */
    @Nullable
    private static ProjectFileTree treeFor(CommandContext context) {
        return context.data().get(ProjectFileTree.PROJECT_TREE);
    }

    private static void declare(CommandRegistry registry) {
        // FIRST, and it is load-bearing: `CommandRegistry.all()` is registration order and
        // `declaredBindings()` is built by walking it, so the two Mod+N presets have to reach the
        // resolver before `explorer.newFile`'s catch-all. @see #declareNewPresets
        declareNewPresets(registry);
        registry.register(Command.of(NEW_FILE, "New File…")
                .icon(ActionIcons.ADD_FILE)
                // NO DECLARED PLACEMENT AT ALL. Both New menus are contributed, because both depend on
                // where the new thing would land -- a placement is fixed at registration and cannot ask.
                // @see #contributeNewMenu
                .binding("Mod+N")
                .run(context -> promptNew(workbenchFor(context), context, false))
                .enabledWhen(context -> workbenchFor(context) != null
                        && destinationFor(workbenchFor(context), context) != null
                        && mayWrite(workbenchFor(context),
                                destinationFor(workbenchFor(context), context))));

        registry.register(Command.of(NEW_FOLDER, "New Folder…")
                .icon(ActionIcons.ADD_DIRECTORY)
                .run(context -> promptNew(workbenchFor(context), context, true))
                .enabledWhen(context -> workbenchFor(context) != null
                        && destinationFor(workbenchFor(context), context) != null
                        && mayWrite(workbenchFor(context),
                                destinationFor(workbenchFor(context), context))));

        // CUT, COPY, PASTE, RENAME AND DELETE are TreeEditing's, over the whole selection, and
        // ProjectExtension contributes their rows to this menu. @see ExplorerEditModel

        registry.register(Command.of(RESTORE_DELETED, "Restore Deleted File…")
                // BESIDE DELETE, in the section that modifies the tree, because that is where somebody
                // looks a minute after pressing Delete. No binding: it is rare and it is not a gesture.
                .menu(MenuId.EXPLORER_CONTEXT, "4_modify", 30)
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    UIDocument window = workbench == null ? null : workbench.document();
                    if (window == null) return;
                    TrashPicker.open(window, workbench);
                })
                // A PROJECT, not a selection: what you are restoring is by definition not in the tree.
                .enabledWhen(context -> hasProject(workbenchFor(context))));

        registry.register(Command.of(COPY_PATH, "Copy Path")
                .menu(MenuId.EXPLORER_CONTEXT, "3_paths", 10)
                .run(context -> copyPath(target(context), false))
                .enabledWhen(context -> workbenchFor(context) != null && target(context) != null));

        registry.register(Command.of(COPY_RELATIVE_PATH, "Copy Relative Path")
                .menu(MenuId.EXPLORER_CONTEXT, "3_paths", 20)
                .run(context -> copyPath(target(context), true))
                .enabledWhen(context -> workbenchFor(context) != null && target(context) != null));

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
                .icon(ActionIcons.SETTINGS)
                .binding("Alt+Shift+S")
                .run(context -> openPreferences(context, null)));

        registry.register(Command.of(CONFIGURE_EDITOR_TABS, "Configure Editor Tabs…")
                .menu(MenuId.EDITOR_GROUP_OPTIONS, "9_configure", 10)
                .menu(MenuId.EDITOR_TAB_CONTEXT, "4_window", 20)
                .run(context -> openPreferences(context, WorkbenchSettings.EDITOR_TABS_PAGE)));

        registry.register(Command.of(SELECT_OPENED_FILE, "Select Opened File")
                // IntelliJ's locate button: expands to the active file, selects it and scrolls it in.
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    ProjectFileTree tree = treeFor(context);
                    if (workbench != null && tree != null) tree.reveal(workbench.activeFilePath());
                })
                .enabledWhen(context -> workbenchFor(context) != null && treeFor(context) != null
                        && workbenchFor(context).activeFilePath() != null));

        registry.register(Command.of(FIND_IN_TREE, "Find in Project View")
                // ELEMENT-SCOPED, bound on the tree rather than declared globally: Ctrl+F means Find in
                // an editor and this must not take it away from one. The resolver walks the focused
                // element's chain first, so the tree's own binding wins only while the tree has focus.
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    if (workbench != null && treeFor(context) != null) {
                        treeFor(context).openFind();
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
                    UIDocument window = workbench == null ? null : workbench.document();
                    if (window == null) return;
                    long profiled = FrameProfile.enter("Ctrl+P explorer.goToFile");
                    GoToFile.open(window, workbench);
                    FrameProfile.leave(profiled, "Ctrl+P explorer.goToFile");
                })
                // Enabled whenever there is a project, not whenever something is selected: it is how you
                // reach a file you have NOT got selected, which is the whole point of it.
                .enabledWhen(context -> hasProject(workbenchFor(context))));

        registry.register(Command.of(REFRESH, "Reload from Disk")
                .icon(ActionIcons.REFRESH)
                .menu(MenuId.EXPLORER_CONTEXT, "5_refresh", 10)
                .binding("F5")
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    ProjectFileTree tree = treeFor(context);
                    if (tree == null) return;
                    tree.source().invalidateAll();
                    tree.treeView().refresh();
                })
                // No target needed any more -- it reloads the whole tree, so the only thing that could make
                // it meaningless is having no project open at all.
                .enabledWhen(context -> hasProject(workbenchFor(context))));

        contributeNewMenu(registry);
    }

    // ── New ▸, and the two shapes it takes ──────────────────────────────────────────────────────

    /**
     * The commands a CHORD can name, and nothing else.
     *
     * <p>Every New row is a {@link NewDocumentKind} now, and the menu builds a row for each without a
     * command having to exist — so the only reason to register one is that something outside the menu
     * has to name it. That is {@code Mod+N} and the palette. Each looks its kind up in the workbench's
     * registry rather than carrying a template of its own. @see BuiltInNewDocuments</p>
     */
    private static void declareNewPresets(CommandRegistry registry) {
        registry.register(chordCommand(NEW_JAVA_CLASS, "Java Class", ActionIcons.JAVA_CLASS)
                // MOD+N MAKES WHATEVER THE MENU'S FIRST ROW MAKES -- a Java class in a java source root,
                // a script in a js one, and a plain file everywhere else, where `explorer.newFile`'s own
                // declared binding takes over.
                //
                // THREE COMMANDS, ONE CHORD, AND THE RESOLVER SORTS IT: a binding whose command is
                // disabled does not consume the stroke, so at most one can answer -- and enablement is
                // `offeredHere`, which asks the very registry the menu draws from. Declared rather than
                // bound onto the tree, so the chord also works with focus in the EDITOR, where the
                // destination is the open file's own directory.
                .binding("Mod+N"));
        registry.register(chordCommand(NEW_JS_FILE, "JavaScript File", ActionIcons.JAVASCRIPT_FILE)
                .binding("Mod+N"));
    }

    /** A registered command for a built-in kind, resolved from the registry when it runs. */
    private static Command chordCommand(String id, String label, String icon) {
        return Command.of(id, label)
                .icon(icon)
                .run(context -> {
                    Workbench workbench = workbenchFor(context);
                    if (workbench != null) create(workbench, context, workbench.newDocuments().byId(id));
                })
                .enabledWhen(context -> offeredHere(context, id));
    }

    private static void contributeNewMenu(CommandRegistry registry) {
        // BOTH New MENUS, ONE CATALOGUE. `File > New` was two declared rows while the explorer's was
        // computed, so the same command offered a Java class from a right-click and a bare file from the
        // menu bar -- for the same directory. They ask the same question now, and `MenuBarView` already
        // resolves its source to the FOCUS OWNER, so the bar's answer follows whichever of the editor or
        // the project panel you were last working in.
        for (MenuId menu : List.of(MenuId.EXPLORER_NEW, MenuId.MAIN_FILE_NEW)) {
            contributeCatalogue(registry, menu);
        }
    }

    private static void contributeCatalogue(CommandRegistry registry, MenuId into) {
        registry.contributeMenu(into, (menu, context) -> {
            List<MenuEntry> rows = new ArrayList<>();
            for (NewDocumentKind kind : kindsAt(context)) {
                Command command = registry.get(kind.id());
                // A KIND WITH NO REGISTERED COMMAND STILL GETS A ROW. A contributor registers a kind, not
                // a command -- which is the point of the seam -- so the row is a Command built for the
                // life of this menu, exactly as a recent-file row is. @see MenuBuilder
                if (command == null) command = commandFor(kind);
                rows.add(new MenuEntry.Item(command, kind.group(), kind.order(),
                        command.isEnabled(context), false, false));
            }
            return rows;
        });
    }

    /**
     * Which kinds this click offers, asked of the registry.
     *
     * <p>The explorer decides nothing here any more: it resolves WHERE the new thing would land and
     * hands that to {@link NewDocumentKinds#offeredAt}, which asks every registered kind. What used to
     * be two hard-coded catalogues and a java/js branch is now each kind's own {@code where}.</p>
     */
    private static List<NewDocumentKind> kindsAt(CommandContext context) {
        Workbench workbench = workbenchFor(context);
        if (workbench == null) return List.of();
        return workbench.newDocuments().offeredAt(placeFor(workbench, context));
    }

    /** Where a new thing would land, as a kind is asked about it. */
    private static NewDocumentContext placeFor(Workbench workbench, CommandContext context) {
        CgPath at = destinationFor(workbench, context);
        if (at == null) return new NewDocumentContext(null, null);
        List<String> roots = workbench.projectListing().sourceRootsOf(at.project());
        return new NewDocumentContext(at, SourceRoots.rootOf(at, roots));
    }

    /**
     * Runs {@code kind} — the one path every New row takes, built in and contributed alike.
     *
     * <p>Three shapes, and which one is read off the declaration rather than branched on by name: a kind
     * that makes a DIRECTORY takes a name and makes directories; one with VARIANTS asks for a name and a
     * variant together; anything else takes a name and is seeded from its template.</p>
     */
    public static void create(Workbench workbench, CommandContext context, NewDocumentKind kind) {
        if (workbench == null || kind == null) return;
        CgPath parent = destinationFor(workbench, context);
        if (parent == null) return;

        if (kind.isDirectory()) {
            promptNewDirectories(workbench, context, parent);
            return;
        }
        if (!kind.variants().isEmpty()) {
            NewDocumentPrompt.ask(UIElement.sourceOf(context), kind,
                    (typed, variant) -> write(workbench, parent, kind, typed, variant));
            return;
        }
        // NO NAME TO ASK FOR when the suffix IS the whole name -- package-info.java is one per package
        // and already called that, so a prompt would be a question with one answer.
        if (kind.label().endsWith(kind.suffix()) && !kind.suffix().isEmpty()) {
            write(workbench, parent, kind, kind.label(), null);
            return;
        }
        promptFor(workbench, context, parent, typed -> write(workbench, parent, kind, typed, null));
    }

    /** Creates the file and opens it, which is what makes New File feel like it did something. */
    private static void write(Workbench workbench, CgPath parent, NewDocumentKind kind,
                              String typed, @Nullable NewDocumentKind.Variant variant) {
        String name = typed.endsWith(kind.suffix()) ? typed : typed + kind.suffix();
        CgPath file = parent.resolve(name);
        NewDocumentKind.Target target =
                new NewDocumentKind.Target(parent, name, packageOf(workbench, file));
        byte[] body = kind.contentFor(target, variant).getBytes(StandardCharsets.UTF_8);
        workbench.files().create(Resource.of(file), body).then(etag -> workbench.openFile(file));
    }

    /** The inline row edit where there is a tree, and a dialog where there is not. */
    private static void promptFor(Workbench workbench, CommandContext context, CgPath parent,
                                  Consumer<String> onName) {
        ProjectFileTree tree = treeFor(context);
        if (tree != null && tree.document() != null) {
            tree.beginNew(parent, false, onName);
            return;
        }
        InputDialog.ask(UIElement.sourceOf(context), "New File", "Name", "", onName);
    }

    /** A row for a kind nothing registered a command for. @see #contributeCatalogue */
    private static Command commandFor(NewDocumentKind kind) {
        return Command.of(kind.id(), kind.label())
                .icon(kind.icon())
                .run(context -> create(workbenchFor(context), context, kind))
                .enabledWhen(ExplorerCommands::canCreateHere);
    }

    private static boolean offeredHere(CommandContext context, String id) {
        if (!canCreateHere(context)) return false;
        for (NewDocumentKind kind : kindsAt(context)) {
            if (kind.id().equals(id)) return true;
        }
        return false;
    }

    private static boolean canCreateHere(CommandContext context) {
        Workbench workbench = workbenchFor(context);
        if (workbench == null) return false;
        CgPath at = destinationFor(workbench, context);
        return at != null && mayWrite(workbench, at);
    }

    private static void promptNewDirectories(@Nullable Workbench workbench, CommandContext context,
                                             @Nullable CgPath into) {
        if (workbench == null) return;
        CgPath parent = into != null ? into : destinationFor(workbench, context);
        if (parent == null) return;
        Consumer<String> create = typed -> makeDirectories(workbench, parent, typed.split("[.]"), 0);
        ProjectFileTree tree = treeFor(context);
        if (tree != null && tree.document() != null) {
            tree.beginNew(parent, true, create);
            return;
        }
        InputDialog.ask(UIElement.sourceOf(context), "New Package", "Name", "", create);
    }

    private static void makeDirectories(Workbench workbench, CgPath parent, String[] segments, int at) {
        if (at >= segments.length) return;
        String segment = segments[at].trim();
        if (segment.isEmpty()) {
            makeDirectories(workbench, parent, segments, at + 1);
            return;
        }
        CgPath directory = parent.resolve(segment);
        workbench.files().mkdir(Resource.of(directory))
                .then(etag -> makeDirectories(workbench, directory, segments, at + 1));
    }

    private static String packageOf(Workbench workbench, CgPath file) {
        SourceRoots.Located located =
                SourceRoots.locate(file, workbench.projectListing().sourceRootsOf(file.project()));
        return located == null ? "" : located.packageName();
    }

    private static boolean hasProject(@Nullable Workbench workbench) {
        return workbench != null && !workbench.projects().roots().isEmpty();
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

    @Nullable
    private static CgPath target(CommandContext context) {
        for (UIElement element = UIElement.sourceOf(context); element != null; element = element.parentElement()) {
            if (element instanceof ProjectFileTree tree) return tree.selectedPath();
        }
        return null;
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
     * cannot do and cannot explain; a wrongly-live one fails with a reason the server wrote.
     * @see com.crystalgui.fs.client.Workspace.Capabilities#mayWrite</p>
     */
    private static boolean mayWrite(@Nullable Workbench workbench, @Nullable CgPath path) {
        if (workbench == null || path == null) return true;
        return workbench.workspace().capabilities().mayWrite(Resource.of(path));
    }

    /** Where a New lands: inside the selection when it is a folder, beside it when it is a file. */
    private static CgPath newParentFor(Workbench workbench, CgPath selected) {
        return workbench.projects().isDirectory(selected) ? selected : selected.parent();
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
        // THEN THE EDITOR YOU ARE IN, which is what Mod+N means with focus in the code rather than in the
        // panel: the new type goes beside the one you are looking at. IntelliJ's behaviour, and the
        // reason it is worth having is the catalogue -- a class opened from `src/main/java` puts you in
        // that source root, so the chord offers a Java class without a trip to the tree to say so.
        //
        // AFTER the tree's selection and not before it. The selection is only in the data context when
        // focus is inside the explorer, so the two can never both answer -- but if they ever did, the
        // panel you are looking at is the one you meant.
        if (workbench != null) {
            CgPath open = workbench.activeFilePath();
            if (open != null) return newParentFor(workbench, open);
        }

        // NO TREE IS NO DESTINATION, and this must not throw: it is reached from `enabledWhen`, which the
        // command palette runs for EVERY command each time it opens. With focus anywhere but the explorer
        // -- a builder canvas, an editor -- nothing answers PROJECT_TREE, and the palette died on
        // Ctrl+Shift+P rather than merely listing this command as disabled.
        ProjectFileTree tree = treeFor(context);
        if (tree == null) return null;
        List<CgPath> roots = tree.source().roots();
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

        ProjectFileTree tree = treeFor(context);
        if (tree != null && tree.document() != null) {
            tree.beginNew(parent, folder, name -> createEntry(workbench, parent.resolve(name), folder));
            return;
        }
        InputDialog.ask(UIElement.sourceOf(context), folder ? "New Folder" : "New File", "Name", "", name ->
                createEntry(workbench, parent.resolve(name), folder));
    }

    private static void createEntry(Workbench workbench, CgPath path, boolean folder) {
        {
            if (folder) {
                workbench.files().mkdir(Resource.of(path));
            } else {
                // OPENED, not merely created. Making a file is a statement of intent to edit it, and every
                // editor that has a New File treats it that way -- VS Code, IntelliJ and Visual Studio all
                // leave you in the new file with the caret in it. Creating one and leaving the user to find
                // it in the tree makes New File feel like it did nothing at all, which is how it was
                // reported.
                //
                // A FOLDER is deliberately not opened: there is nothing to edit, and revealing it would
                // fight the selection the user is about to make inside it.
                workbench.files().create(Resource.of(path), new byte[0])
                        .then(etag -> workbench.openFile(path));
            }
        }
    }

    /**
     * Puts a path on the clipboard.
     *
     * <p>Two forms because they are pasted into different places: an absolute {@code project:dir/file} for
     * anything that resolves paths, and a project-relative one for a message to somebody else. VS Code
     * ships both, with a separate separator setting for each.</p>
     */
    public static void copyPath(@Nullable CgPath path, boolean relative) {
        if (path == null) return;
        String text = relative ? path.path() : path.toString();
        CgPlatform.input().setClipboard(text);
        Notifications.show(Notification.info("Copied").withDetail(text));
    }

    /** Every command id this set owns, for a host building its own menus. */
    public static List<String> ids() {
        return List.of(NEW_FILE, NEW_FOLDER, COPY_PATH, COPY_RELATIVE_PATH, REFRESH, GO_TO_FILE, SELECT_OPENED_FILE);
    }

    /** The preferences window, on {@code page} or the first. */
    private static void openPreferences(CommandContext context, @Nullable String page) {
        UIDocument window = context.data().get(CommandPalette.SURFACE);
        if (window == null) return;
        // THE STORE THE APPLICATION SAYS IT LISTENS ON -- asked, not derived.
        //
        // This used to be `window.settings()`, on the reasoning that settings
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
        // Invisible in the harness, whose scene is `new UIDocument(Ui.of(editor))` -- there the
        // editor IS the root element and the two expressions are the same object.
        Settings host = context.data().get(UiDataKeys.SETTINGS_HOST);
        Preferences.open(window, host != null ? host : window.settings(), page);
    }
}
