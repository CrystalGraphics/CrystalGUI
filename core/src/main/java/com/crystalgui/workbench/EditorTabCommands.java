package com.crystalgui.workbench;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.EditorInput;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.InputDialog;
import com.crystalgui.workbench.dock.DockTab;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.explorer.ExplorerCommands;
import com.crystalgui.workbench.explorer.ProjectFileTree;

/**
 * What an editor tab's menu offers about the DOCUMENT it shows — the half of IntelliJ's tab menu the dock cannot answer,
 * since it knows panels and not files. The dock's own half is {@code DockCommands}; both take their tab from
 * {@link DockTab}.
 *
 * <pre>{@code
 * EditorTabCommands.register();   // global; the workbench is resolved from the data context
 * }</pre>
 */
public final class EditorTabCommands {

    public static final String CLOSE_UNMODIFIED = "editor.tab.closeUnmodified";
    public static final String COPY_PATH = "editor.tab.copyPath";
    public static final String COPY_RELATIVE_PATH = "editor.tab.copyRelativePath";
    public static final String RENAME_FILE = "editor.tab.renameFile";

    private EditorTabCommands() {
    }

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(EditorTabCommands.class, EditorTabCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(CLOSE_UNMODIFIED, "Close Unmodified Tabs in Group")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "1_close", 40)
                .run(context -> {
                    DockTab tab = DockTab.of(context);
                    Workbench workbench = context.data().get(Workbench.WORKBENCH);
                    if (tab == null || workbench == null) return;
                    for (DockPanelRef panel : tab.group().leaf().panels().toArray(new DockPanelRef[0])) {
                        if (!isModified(workbench, panel)) tab.area().closePanel(tab.group().leaf(), panel);
                    }
                })
                .enabledWhen(context -> DockTab.of(context) != null));

        registry.register(Command.of(COPY_PATH, "Copy Path")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "2_copy", 10)
                .run(context -> ExplorerCommands.copyPath(pathOf(context), false))
                .enabledWhen(context -> pathOf(context) != null));

        registry.register(Command.of(COPY_RELATIVE_PATH, "Copy Relative Path")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "2_copy", 20)
                .run(context -> ExplorerCommands.copyPath(pathOf(context), true))
                .enabledWhen(context -> pathOf(context) != null));

        registry.register(Command.of(RENAME_FILE, "Rename File…")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "5_file", 10)
                .run(context -> {
                    CgPath path = pathOf(context);
                    Workbench workbench = context.data().get(Workbench.WORKBENCH);
                    if (path == null || workbench == null) return;
                    // THE TAB FOLLOWS: a renamed document moves its tab in place. @see DocumentTabs#followDocuments
                    InputDialog.ask(UIElement.sourceOf(context), "Rename File", "Name", path.name(), name -> {
                        if (!name.equals(path.name()) && ProjectFileTree.isWellFormedName(name)) {
                            workbench.files().rename(Resource.of(path), Resource.of(path.parent().resolve(name)), false);
                        }
                    });
                })
                .enabledWhen(context -> pathOf(context) != null));
    }

    /** The project file the tab shows, or null for anything that is not one. */
    @Nullable
    private static CgPath pathOf(CommandContext context) {
        DockTab tab = DockTab.of(context);
        Resource resource = tab == null ? null : DocumentTabs.viewedResource(tab.panel());
        return resource == null ? null : resource.asPath();
    }

    /** Whether the document behind {@code panel} holds edits not yet written. */
    private static boolean isModified(Workbench workbench, DockPanelRef panel) {
        Resource resource = DocumentTabs.viewedResource(panel);
        EditorService.Tab tab = resource == null ? null : workbench.editors().tabFor(EditorInput.of(resource));
        return tab != null && tab.state() == DocumentState.DIRTY;
    }
}
