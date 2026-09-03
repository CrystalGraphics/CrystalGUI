package com.crystalgui.workbench.dock;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.dom.UIElement;

import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * Split, close, maximize and move between groups — as commands, so a keystroke, a menu item and a
 * palette entry all point at one thing.
 *
 * <h3>Everything resolves against the ACTIVE group, not the focused element</h3>
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
                .run(context -> splitActive(context, DockDropZone.SPLIT_RIGHT))
                .enabledWhen(DockCommands::hasActivePanel));

        registry.register(Command.of(SPLIT_DOWN, "Split Down")
                .binding("Mod+Shift+Backslash")
                .menu(MenuId.MAIN_WINDOW, "1_panes", 20)
                .run(context -> splitActive(context, DockDropZone.SPLIT_DOWN))
                .enabledWhen(DockCommands::hasActivePanel));

        registry.register(Command.of(CLOSE_PANEL, "Close Panel")
                .binding("Mod+W")
                // FILE, not Window: closing the thing you are looking at is a file action in both
                // references, and a command may declare as many placements as it has meanings.
                .menu(MenuId.MAIN_FILE, "4_close", 10)
                .run(context -> withArea(context, area -> {
                    DockGroup group = area.activeGroup();
                    if (group == null) return;
                    DockPanelRef panel = group.leaf().activePanel();
                    if (panel != null) area.closePanel(panel);
                }))
                .enabledWhen(DockCommands::hasActivePanel));

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

    private static void splitActive(CommandContext context, DockDropZone zone) {
        withArea(context, area -> {
            DockGroup group = area.activeGroup();
            if (group == null) return;
            DockPanelRef panel = group.leaf().activePanel();
            if (panel == null) return;
            // A split from the keyboard duplicates the panel reference into a new pane rather than moving
            // it, which is what VS Code's "Split Editor" does: you wanted two views of it, not to move the
            // one you had.
            area.layout().drop(group.leaf(), zone, new DockLeaf(panel));
            area.requestRebuild();
        });
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

    private static boolean hasActivePanel(CommandContext context) {
        DockArea area = areaFor(context);
        return area != null && area.activeGroup() != null
                && area.activeGroup().leaf().activePanel() != null;
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
