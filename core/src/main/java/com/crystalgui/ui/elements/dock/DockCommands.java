package com.crystalgui.ui.elements.dock;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

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

    public static void register(CommandRegistry registry) {
        if (registry.contains(CLOSE_PANEL)) return;

        registry.register(Command.of(SPLIT_RIGHT, "Split Right")
                .run(context -> splitActive(context, DockDropZone.SPLIT_RIGHT))
                .enabledWhen(DockCommands::hasActivePanel));

        registry.register(Command.of(SPLIT_DOWN, "Split Down")
                .run(context -> splitActive(context, DockDropZone.SPLIT_DOWN))
                .enabledWhen(DockCommands::hasActivePanel));

        registry.register(Command.of(CLOSE_PANEL, "Close Panel")
                .run(context -> withArea(context, area -> {
                    DockGroup group = area.activeGroup();
                    if (group == null) return;
                    DockPanelRef panel = group.leaf().activePanel();
                    if (panel != null) area.closePanel(panel);
                }))
                .enabledWhen(DockCommands::hasActivePanel));

        registry.register(Command.of(TOGGLE_MAXIMIZE, "Toggle Maximize Group")
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
                .run(context -> cycleGroup(context, 1))
                .enabledWhen(context -> {
                    DockArea area = areaFor(context);
                    return area != null && area.layout().leaves().size() > 1;
                }));

        registry.register(Command.of(FOCUS_PREVIOUS_GROUP, "Focus Previous Group")
                .run(context -> cycleGroup(context, -1))
                .enabledWhen(context -> {
                    DockArea area = areaFor(context);
                    return area != null && area.layout().leaves().size() > 1;
                }));

        registry.register(Command.of(NEXT_TAB, "Next Tab")
                .run(context -> cycleTab(context, 1))
                .enabledWhen(DockCommands::hasSeveralTabs));

        registry.register(Command.of(PREVIOUS_TAB, "Previous Tab")
                .run(context -> cycleTab(context, -1))
                .enabledWhen(DockCommands::hasSeveralTabs));
    }

    /**
     * VS Code's set, because it is the one most people already have in their hands.
     *
     * <p>No bare-letter bindings here, unlike {@code GraphCommands}: a dock wraps <em>everything</em>, so
     * a command scoped to "is there a dock anywhere above me" is scoped to the whole application, and a
     * single letter would be intolerable.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Mod+Backslash", SPLIT_RIGHT);
        keymap.bind("Mod+Shift+Backslash", SPLIT_DOWN);
        keymap.bind("Mod+W", CLOSE_PANEL);
        keymap.bind("Mod+M", TOGGLE_MAXIMIZE);
        keymap.bind("Mod+K", FOCUS_NEXT_GROUP);
        keymap.bind("Mod+Shift+K", FOCUS_PREVIOUS_GROUP);
        keymap.bind("Mod+PageDown", NEXT_TAB);
        keymap.bind("Mod+PageUp", PREVIOUS_TAB);
    }

    public static void install(CommandRegistry registry, UIElement root) {
        register(registry);
        bindDefaults(root.keymap());
    }

    public static void install(UIWindow window) {
        install(window.getCommands(), window.ui.rootElement);
    }

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
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof DockArea area) return area;
        }
        return null;
    }
}
