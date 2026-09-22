package com.crystalgui.widget.collection.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.input.keymap.Keymap;

/**
 * The six commands every {@link TreeEditing} answers: Cut, Copy, Paste, Duplicate, Rename, Delete.
 *
 * <p>Global and captureless — each resolves {@link TreeEditing#KEY} from the invoking element, so one
 * registration serves every edited tree. The keys go on each tree's own keymap, so Delete and F2 never fire
 * while typing elsewhere.</p>
 *
 * <pre>{@code
 * TreeEditCommands.register();                          // TreeEditing does this
 * TreeEditCommands.bindKeys(tree.keymap());             // and this
 * TreeEditing.contributeMenu(CommandRegistry.global(), NODE_MENU);   // a consumer does this, once
 * }</pre>
 */
public final class TreeEditCommands {

    public static final String CUT = "tree.cut";
    public static final String COPY = "tree.copy";
    public static final String PASTE = "tree.paste";
    public static final String DUPLICATE = "tree.duplicate";
    public static final String RENAME = "tree.rename";
    public static final String DELETE = "tree.delete";

    /** The clipboard group, and the modifying one — the explorer's own section names. */
    public static final String CLIPBOARD_GROUP = "2_clipboard";
    public static final String MODIFY_GROUP = "4_modify";

    private TreeEditCommands() {
    }

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(TreeEditCommands.class, TreeEditCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        // Five of the six carry marks. Rename is the one without: the set has no rename glyph, and
        // `edit`'s pencil already means "open this for editing" on Jump to Source.
        //
        // These six reach EVERY tree's menu through TreeEditing.contributeMenu -- the explorer, the
        // Hierarchy, the Library -- so this is one edit for every panel rather than one per panel.
        registry.register(command(CUT, "Cut", TreeEditing::canCut, TreeEditing::cut)
                .icon(ActionIcons.CUT));
        registry.register(command(COPY, "Copy", TreeEditing::canCopy, TreeEditing::copy)
                .icon(ActionIcons.COPY));
        registry.register(command(PASTE, "Paste", TreeEditing::canPaste, TreeEditing::paste)
                .icon(ActionIcons.PASTE));
        registry.register(command(DUPLICATE, "Duplicate", TreeEditing::canDuplicate, TreeEditing::duplicate)
                .icon(ActionIcons.DUPLICATE));
        registry.register(command(RENAME, "Rename…", TreeEditing::canRename, TreeEditing::renameSelected)
                .icon(ActionIcons.RENAME));
        registry.register(command(DELETE, "Delete", TreeEditing::canDelete, TreeEditing::delete)
                .icon(ActionIcons.DELETE));
    }

    @SuppressWarnings("rawtypes")
    private static Command command(String id, String label, Predicate<TreeEditing> enabled,
                                   Consumer<TreeEditing> verb) {
        return Command.of(id, label)
                .enabledWhereData(data -> editingIn(data) != null && enabled.test(editingIn(data)))
                .runWithData(data -> {
                    TreeEditing editing = editingIn(data);
                    // RE-ASKED: a menu may have stayed open while the selection changed under it.
                    if (editing != null && enabled.test(editing)) verb.accept(editing);
                });
    }

    @SuppressWarnings("rawtypes")
    private static TreeEditing editingIn(DataContext data) {
        return data.get(TreeEditing.KEY);
    }

    /** Mod+X, Mod+C, Mod+V, Mod+D, F2 and Delete, on one tree's keymap. */
    public static void bindKeys(Keymap keymap) {
        keymap.bind("Mod+X", CUT);
        keymap.bind("Mod+C", COPY);
        keymap.bind("Mod+V", PASTE);
        keymap.bind("Mod+D", DUPLICATE);
        keymap.bind("F2", RENAME);
        keymap.bind("Delete", DELETE);
    }

    /** Puts the six rows into {@code menu}: Cut, Copy, Paste, Duplicate, then Rename and Delete. */
    public static Disposable contributeTo(CommandRegistry registry, MenuId menu) {
        register();
        return registry.contributeMenu(menu, (at, context) -> {
            List<MenuEntry> rows = new ArrayList<>(6);
            row(rows, registry, CUT, CLIPBOARD_GROUP, 10, context);
            row(rows, registry, COPY, CLIPBOARD_GROUP, 20, context);
            row(rows, registry, PASTE, CLIPBOARD_GROUP, 30, context);
            // DUPLICATE ONLY WHERE IT MEANS SOMETHING: a sorted file tree has no "just after it".
            TreeEditing<?> editing = editingIn(context.data());
            if (editing != null && editing.model() != null && editing.model().isOrdered()) {
                row(rows, registry, DUPLICATE, CLIPBOARD_GROUP, 40, context);
            }
            row(rows, registry, RENAME, MODIFY_GROUP, 10, context);
            row(rows, registry, DELETE, MODIFY_GROUP, 20, context);
            return rows;
        });
    }

    private static void row(List<MenuEntry> rows, CommandRegistry registry, String id, String group, int order,
                            CommandContext context) {
        Command command = registry.get(id);
        if (command != null) rows.add(new MenuEntry.Item(command, group, order, command.isEnabled(context), false, false));
    }
}
