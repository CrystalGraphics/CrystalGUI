package com.crystalgui.workbench.dock;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.dom.UIElement;

import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * Split, close, maximize and move between groups — as commands, so a keystroke, a menu item and a
 * palette entry all point at one thing.
 *
 * <h3>Everything resolves against the ACTIVE group, not the focused element</h3>
 *
 * <p>Or against the tab a command's menu was opened on: the tab commands take their subject from {@link DockTab},
 * so one command serves a tab's right-click menu, the keyboard and the main menu.</p>
 *
 * <p>The nearest enclosing {@link DockArea} comes from the focused element, but which group inside it a
 * command acts on is {@link DockArea#activeGroup()} — tracked explicitly. Walking up from focus would
 * only be right for groups whose content happens not to be focusable, and every interesting panel's
 * content is: clicking inside a graph canvas focuses the <em>canvas</em>.</p>
 *
 * <h3>Installed explicitly</h3>
 *
 * <p>As with {@code GraphCommands} and {@code UndoCommands}: this engine does not inject its own
 * defaults, and a registry that quietly acquired commands nobody registered surprises anything that
 * enumerates it.</p>
 */
public final class DockCommands {

    public static final String SPLIT_RIGHT = "dock.splitRight";
    public static final String SPLIT_DOWN = "dock.splitDown";
    public static final String CLOSE_PANEL = "dock.closePanel";
    public static final String TOGGLE_MAXIMIZE = "dock.toggleMaximize";
    public static final String FOCUS_NEXT_GROUP = "dock.focusNextGroup";
    public static final String FOCUS_PREVIOUS_GROUP = "dock.focusPreviousGroup";
    public static final String NEXT_TAB = "dock.nextTab";
    public static final String PREVIOUS_TAB = "dock.previousTab";
    public static final String CLOSE_ALL_IN_GROUP = "dock.closeAllInGroup";
    public static final String CLOSE_OTHERS = "dock.closeOthers";
    public static final String CLOSE_TO_THE_RIGHT = "dock.closeToTheRight";
    public static final String SPLIT_AND_MOVE_RIGHT = "dock.splitAndMoveRight";
    public static final String SPLIT_AND_MOVE_DOWN = "dock.splitAndMoveDown";
    public static final String MOVE_TO_OPPOSITE = "dock.moveToOppositeGroup";
    public static final String OPEN_IN_OPPOSITE = "dock.openInOppositeGroup";
    public static final String CHANGE_ORIENTATION = "dock.changeSplitterOrientation";
    public static final String UNSPLIT = "dock.unsplit";
    public static final String OPEN_IN_NEW_WINDOW = "dock.openInNewWindow";

    private DockCommands() {
    }

    /**
     * Registers into {@link CommandRegistry#global()}.
     *
     * <p>Commands are global; a command is a fact about the application, and what varies per window is
     * what is <em>focused</em> — which is {@code DataContext}'s job. Registering per window meant every
     * window re-registered everything, and a widget had to find "its" window before it could contribute.</p>
     *
     * <p>Still <b>explicit</b>: a host calls this. Nothing self-registers, because a registry that
     * quietly acquired commands nobody asked for surprises anything that enumerates it — which the
     * command palette does.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(DockCommands.class, DockCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(SPLIT_RIGHT, "Split Right")
                .binding("Mod+Backslash")
                .menu(MenuId.MAIN_WINDOW, "1_panes", 10)
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 10)
                .run(context -> split(context, DockDropZone.SPLIT_RIGHT, false))
                .enabledWhen(DockCommands::hasTab));

        registry.register(Command.of(SPLIT_AND_MOVE_RIGHT, "Split and Move Right")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 20)
                .run(context -> split(context, DockDropZone.SPLIT_RIGHT, true))
                .enabledWhen(DockCommands::hasCompany));

        registry.register(Command.of(SPLIT_DOWN, "Split Down")
                .binding("Mod+Shift+Backslash")
                .menu(MenuId.MAIN_WINDOW, "1_panes", 20)
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 30)
                .run(context -> split(context, DockDropZone.SPLIT_DOWN, false))
                .enabledWhen(DockCommands::hasTab));

        registry.register(Command.of(SPLIT_AND_MOVE_DOWN, "Split and Move Down")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 40)
                .run(context -> split(context, DockDropZone.SPLIT_DOWN, true))
                .enabledWhen(DockCommands::hasCompany));

        registry.register(Command.of(MOVE_TO_OPPOSITE, "Move to Opposite Group")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 50)
                .run(context -> toOpposite(context, true))
                .enabledWhen(context -> oppositeOf(DockTab.of(context)) != null));

        registry.register(Command.of(OPEN_IN_OPPOSITE, "Open in Opposite Group")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 60)
                .run(context -> toOpposite(context, false))
                .enabledWhen(context -> oppositeOf(DockTab.of(context)) != null));

        registry.register(Command.of(CHANGE_ORIENTATION, "Change Splitter Orientation")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 70)
                .run(context -> {
                    DockTab tab = DockTab.of(context);
                    if (tab == null) return;
                    DockLayout layout = tab.area().layout();
                    layout.rootOrientation(layout.rootOrientation().orthogonal());
                    tab.area().requestRebuild();
                })
                // THE OUTERMOST SPLIT ONLY. A split's axis is derived from its depth (@see DockNode), so flipping one
                // nested split alone cannot be expressed; flipping the root flips every level, which is only what was
                // asked for when the tab's group is directly in it.
                .enabledWhen(context -> {
                    DockTab tab = DockTab.of(context);
                    return tab != null && tab.group().leaf().parent() == tab.area().layout().root()
                            && tab.area().layout().root().childCount() > 1;
                }));

        registry.register(Command.of(UNSPLIT, "Unsplit")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "3_split", 80)
                .run(context -> {
                    DockTab tab = DockTab.of(context);
                    if (tab == null) return;
                    tab.area().layout().unsplit(tab.group().leaf());
                    tab.area().requestRebuild();
                })
                .enabledWhen(context -> {
                    DockTab tab = DockTab.of(context);
                    return tab != null && tab.group().leaf().parent() != null;
                }));

        registry.register(Command.of(OPEN_IN_NEW_WINDOW, "Open Tab in New Window")
                .binding("Shift+F4")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "4_window", 10)
                .run(context -> {
                    DockTab tab = DockTab.of(context);
                    if (tab != null) tab.area().openInNewWindow(tab.panel());
                })
                .enabledWhen(DockCommands::hasTab));

        registry.register(Command.of(CLOSE_PANEL, "Close")
                .binding("Mod+W")
                // FILE, not Window: closing the thing you are looking at is a file action in both
                // references, and a command may declare as many placements as it has meanings.
                .menu(MenuId.MAIN_FILE, "4_close", 10)
                .menu(MenuId.EDITOR_TAB_CONTEXT, "1_close", 10)
                .run(context -> {
                    DockTab tab = DockTab.of(context);
                    if (tab != null) tab.area().closePanel(tab.panel());
                })
                .enabledWhen(DockCommands::hasTab));

        registry.register(Command.of(CLOSE_OTHERS, "Close Other Tabs")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "1_close", 20)
                .run(context -> closeIn(context, (tab, panel) -> !panel.equals(tab.panel())))
                .enabledWhen(DockCommands::hasCompany));

        registry.register(Command.of(TOGGLE_MAXIMIZE, "Toggle Maximize Group")
                .binding("Mod+M")
                .menu(MenuId.MAIN_WINDOW, "1_panes", 30)
                .run(context -> withArea(context, area -> {
                    DockGroup group = area.activeGroup();
                    if (group != null) area.toggleMaximize(group.leaf());
                }))
                .enabledWhen(context -> {
                    DockArea area = areaFor(context);
                    // Pointless with one pane, and a command that does nothing visible is worse than one
                    // that is greyed out — the user cannot tell the first from a bug.
                    return area != null && area.layout().leaves().size() > 1;
                }));

        registry.register(Command.of(FOCUS_NEXT_GROUP, "Focus Next Group")
                .binding("Mod+K")
                .menu(MenuId.MAIN_WINDOW, "2_editors", 30)
                .run(context -> cycleGroup(context, 1))
                .enabledWhen(context -> {
                    DockArea area = areaFor(context);
                    return area != null && area.layout().leaves().size() > 1;
                }));

        registry.register(Command.of(FOCUS_PREVIOUS_GROUP, "Focus Previous Group")
                .binding("Mod+Shift+K")
                .menu(MenuId.MAIN_WINDOW, "2_editors", 40)
                .run(context -> cycleGroup(context, -1))
                .enabledWhen(context -> {
                    DockArea area = areaFor(context);
                    return area != null && area.layout().leaves().size() > 1;
                }));

        registry.register(Command.of(CLOSE_ALL_IN_GROUP, "Close All Tabs in Group")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "1_close", 30)
                .run(context -> closeIn(context, (tab, panel) -> true))
                .enabledWhen(DockCommands::hasTab));

        registry.register(Command.of(CLOSE_TO_THE_RIGHT, "Close Tabs to the Right")
                .menu(MenuId.EDITOR_TAB_CONTEXT, "1_close", 50)
                .run(context -> closeIn(context, (tab, panel) -> tab.group().leaf().indexOf(panel) > tab.index()))
                .enabledWhen(context -> {
                    DockTab tab = DockTab.of(context);
                    return tab != null && tab.index() < tab.group().leaf().panelCount() - 1;
                }));

        registry.register(Command.of(NEXT_TAB, "Next Tab")
                .binding("Mod+PageDown")
                .menu(MenuId.MAIN_WINDOW, "2_editors", 10)
                .run(context -> cycleTab(context, 1))
                .enabledWhen(DockCommands::hasSeveralTabs));

        registry.register(Command.of(PREVIOUS_TAB, "Previous Tab")
                .binding("Mod+PageUp")
                .menu(MenuId.MAIN_WINDOW, "2_editors", 20)
                .run(context -> cycleTab(context, -1))
                .enabledWhen(DockCommands::hasSeveralTabs));
    }

    // The chords are VS Code's, and they are declared on the commands above rather than bound onto a
    // root keymap here.
    //
    // That is not a style choice: a dock wraps EVERYTHING, so a command scoped to "is there a dock
    // anywhere above me" is scoped to the whole application — which is exactly what a declared binding
    // is. Binding them on a root element instead made the whole set a HOST OBLIGATION, and the harness
    // never took it: no scene called DockCommands.install, so every dock in the gallery had eight
    // commands and not one key. There is nothing left to forget.
    //
    // No bare letters, unlike GraphCommands — application scope and a single letter cannot coexist.

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the tab in a new pane beside its group. A plain split DUPLICATES the reference, which is what VS Code's
     * "Split Editor" does: you wanted two views of it, not to move the one you had. {@code move} is IntelliJ's
     * "Split and Move", which takes it out of the group it was in.
     */
    private static void split(CommandContext context, DockDropZone zone, boolean move) {
        DockTab tab = DockTab.of(context);
        if (tab == null) return;
        DockLeaf leaf = tab.group().leaf();
        if (move) leaf.remove(tab.panel());
        tab.area().layout().drop(leaf, zone, new DockLeaf(tab.panel()));
        tab.area().requestRebuild();
    }

    /** Moves or copies the tab into the group across its split. @see DockLayout#opposite */
    private static void toOpposite(CommandContext context, boolean move) {
        DockTab tab = DockTab.of(context);
        DockLeaf opposite = oppositeOf(tab);
        if (opposite == null) return;
        if (move) {
            tab.area().layout().movePanel(tab.panel(), opposite, opposite.panelCount());
        } else if (opposite.indexOf(tab.panel()) < 0) {
            opposite.add(tab.panel());
        } else {
            opposite.activate(tab.panel());
        }
        tab.area().requestRebuild();
    }

    @Nullable
    private static DockLeaf oppositeOf(@Nullable DockTab tab) {
        return tab == null ? null : tab.area().layout().opposite(tab.group().leaf());
    }

    /** Closes every panel in the tab's group that {@code which} takes -- each through the guard, so an edit still asks. */
    private static void closeIn(CommandContext context, BiPredicate<DockTab, DockPanelRef> which) {
        DockTab tab = DockTab.of(context);
        if (tab == null) return;
        for (DockPanelRef panel : new ArrayList<>(tab.group().leaf().panels())) {
            if (which.test(tab, panel)) tab.area().closePanel(panel);
        }
    }

    private static boolean hasTab(CommandContext context) {
        return DockTab.of(context) != null;
    }

    /** Whether the tab's group holds another tab besides it. */
    private static boolean hasCompany(CommandContext context) {
        DockTab tab = DockTab.of(context);
        return tab != null && tab.group().leaf().panelCount() > 1;
    }

    private static void cycleGroup(CommandContext context, int delta) {
        withArea(context, area -> {
            List<DockLeaf> leaves = area.layout().leaves();
            if (leaves.size() < 2) return;
            DockGroup active = area.activeGroup();
            int index = active == null ? 0 : leaves.indexOf(active.leaf());
            int next = Math.floorMod(index + delta, leaves.size());
            area.setActiveGroup(area.groupFor(leaves.get(next)));
        });
    }

    private static void cycleTab(CommandContext context, int delta) {
        withArea(context, area -> {
            DockGroup group = area.activeGroup();
            if (group == null) return;
            DockLeaf leaf = group.leaf();
            if (leaf.panelCount() < 2) return;
            leaf.activate(Math.floorMod(leaf.activeIndex() + delta, leaf.panelCount()));
            group.sync();
        });
    }

    private static boolean hasSeveralTabs(CommandContext context) {
        DockArea area = areaFor(context);
        return area != null && area.activeGroup() != null
                && area.activeGroup().leaf().panelCount() > 1;
    }

    private static void withArea(CommandContext context, Consumer<DockArea> action) {
        DockArea area = areaFor(context);
        if (area != null) action.accept(area);
    }

    @Nullable
    private static DockArea areaFor(CommandContext context) {
        for (UIElement element = UIElement.sourceOf(context); element != null; element = element.parentElement()) {
            if (element instanceof DockArea area) return area;
        }
        return null;
    }


}
