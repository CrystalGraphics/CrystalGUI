package com.crystalgui.workbench;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.fs.CgPath;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelKind;
import com.crystalgui.workbench.dock.layout.DockPanelRef;

import java.util.ArrayList;
import java.util.List;

/**
 * The two main-menu sections that cannot be registered ahead of time — {@code View ▸ Tool Windows} and
 * {@code Window}'s list of open editors.
 *
 * <h3>Why these are computed and the rest are not</h3>
 *
 * <p>Everything else in the menu bar is a {@code .menu(...)} on a command that exists at startup. These
 * two are lists whose <b>length is not known until the menu opens</b>: a tool window is registered by
 * whoever contributes it, an open editor exists because somebody opened a file, and neither has an id to
 * register a command against. IntelliJ generates one action per registered tool window for exactly this
 * reason, and its Window menu's editor list is a computed {@code ActionGroup}.</p>
 *
 * <p>The alternative — a command per tool window, kept in step with the registry — goes stale exactly
 * once and then looks like a bug in the menu. It also puts one palette entry per open file in the command
 * palette, which is not what a palette is for.</p>
 *
 * @see com.crystalgui.core.command.MenuContributor
 */
public final class WorkbenchMenus {

    private WorkbenchMenus() {
    }

    /**
     * Wires both contributors onto {@code registry}.
     *
     * <p>Idempotent through {@code contribute}, which matters because every {@link Workbench} calls this:
     * two workbenches must not produce the tool-window list twice.</p>
     */
    public static void register(CommandRegistry registry) {
        registry.contribute(WorkbenchMenus.class, target -> {
            target.contributeMenu(MenuId.MAIN_VIEW_TOOLWINDOWS, WorkbenchMenus::toolWindows);
            target.contributeMenu(MenuId.MAIN_WINDOW, WorkbenchMenus::openEditors);
            target.contributeMenu(MenuId.MAIN_FILE_RECENT, WorkbenchMenus::recentFiles);
        });
    }

    /**
     * One checkable row per registered tool window, ticked when it is open.
     *
     * <p><b>Toggles, and the checkmark is the reason it must.</b> A row that shows a tick and only ever
     * turns the thing on is a lie about what pressing it does — so this uses {@code togglePanel} rather
     * than the reveal that {@link Workbench#SHOW_PROBLEMS} performs for the status bar. The two
     * genuinely differ: clicking an error count means "show me", and a ticked menu row means "it is on".
     * IntelliJ draws the same distinction between its status widgets and {@code View ▸ Tool Windows}.</p>
     *
     * <p>Ordered by the descriptor registration order, which is the order the rails already show them in
     * — so the menu and the activity bar agree without either consulting the other.</p>
     */
    private static List<MenuEntry> toolWindows(MenuId menu, CommandContext context) {
        Workbench workbench = context.data().get(Workbench.WORKBENCH);
        if (workbench == null) return List.of();

        List<MenuEntry> rows = new ArrayList<>();
        int order = 0;
        for (DockPanelDescriptor descriptor : workbench.panels().descriptors()) {
            // DOCUMENTS ARE NOT TOOL WINDOWS. A row per open file belongs in the Window menu below, and a
            // rail listing them would grow without bound -- the same reason DockPanelKind gives.
            if (descriptor.kind() == DockPanelKind.DOCUMENT) continue;
            String typeId = descriptor.typeId();
            rows.add(new MenuEntry.Item(
                    // NOT REGISTERED, deliberately: this command exists for as long as the menu does. It
                    // is run directly by MenuBuilder when the registry does not know the id, which is the
                    // branch that makes a computed row work at all.
                    Command.of("workbench.toolWindow." + typeId, descriptor.title())
                            .run(() -> workbench.togglePanel(typeId)),
                    "1_windows", order += 10,
                    true, true, workbench.isPanelOpen(typeId)));
        }
        return rows;
    }

    /**
     * {@code File ▸ Open Recent} — newest first.
     *
     * <p>Labelled by file name, not by path: a submenu whose every row begins with the same twelve
     * folders is unreadable, and the name is what the user is looking for. The full path is what the row
     * <em>does</em>, so nothing is lost.</p>
     *
     * <p><b>Not checkable</b>, unlike the two lists above. Those answer "which of these is current"; this
     * one is a history, and ticking the file that happens to be open would suggest the others could be
     * ticked too.</p>
     */
    private static List<MenuEntry> recentFiles(MenuId menu, CommandContext context) {
        Workbench workbench = context.data().get(Workbench.WORKBENCH);
        if (workbench == null) return List.of();

        List<MenuEntry> rows = new ArrayList<>();
        int order = 0;
        for (CgPath path : workbench.recentFiles().paths()) {
            rows.add(MenuEntry.Item.of(
                    Command.of("workbench.recent." + path, path.name())
                            .run(() -> workbench.openFile(path)),
                    "1_recent", order += 10));
        }
        return rows;
    }

    /**
     * The open editors, as a checkable list with the active one ticked.
     *
     * <p>Both references end the Window menu this way, and it is the one place the full set of open
     * documents is visible when the tab strip has run out of room.</p>
     */
    private static List<MenuEntry> openEditors(MenuId menu, CommandContext context) {
        Workbench workbench = context.data().get(Workbench.WORKBENCH);
        if (workbench == null) return List.of();

        DockPanelRef active = workbench.dock().activePanel();
        List<MenuEntry> rows = new ArrayList<>();
        int order = 0;
        for (DockPanelRef panel : workbench.dock().allPanels()) {
            rows.add(new MenuEntry.Item(
                    Command.of("workbench.editor." + System.identityHashCode(panel),
                                    workbench.panels().titleOf(panel))
                            .run(() -> workbench.dock().activatePanel(panel)),
                    "9_editors", order += 10,
                    true, true, panel == active));
        }
        return rows;
    }
}
