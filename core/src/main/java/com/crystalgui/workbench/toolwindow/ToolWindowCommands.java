package com.crystalgui.workbench.toolwindow;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.view.ViewContainer;

/**
 * What every tool window's ⋮ menu offers: View Mode ▸ Dock Pinned, Float, Window — IntelliJ's gear.
 *
 * <p>Resolved from the container a menu was opened in ({@link ViewContainer#KEY}) and the workbench around
 * it, so one registration serves every tool window in every workbench.</p>
 */
public final class ToolWindowCommands {

    public static final String DOCK = "toolwindow.viewMode.docked";
    public static final String FLOAT = "toolwindow.viewMode.floating";
    public static final String WINDOW = "toolwindow.viewMode.windowed";

    private ToolWindowCommands() {
    }

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(ToolWindowCommands.class, ToolWindowCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(mode(DOCK, "Dock Pinned", ToolWindowType.DOCKED, 10));
        registry.register(mode(FLOAT, "Float", ToolWindowType.FLOATING, 20));
        registry.register(mode(WINDOW, "Window", ToolWindowType.WINDOWED, 30));
    }

    private static Command mode(String id, String label, ToolWindowType type, int order) {
        return Command.of(id, label)
                .menu(MenuId.TOOL_WINDOW_VIEW_MODE, "1_mode", order)
                .enabledWhereData(data -> managerIn(data) != null && containerIn(data) != null)
                .toggledWhereData(data -> managerIn(data) != null && containerIn(data) != null
                        && managerIn(data).typeOf(containerIn(data).containerId()) == type)
                .runWithData(data -> {
                    ToolWindowManager manager = managerIn(data);
                    ViewContainer container = containerIn(data);
                    if (manager == null || container == null) return;
                    String typeId = container.containerId();
                    if (type == ToolWindowType.DOCKED) {
                        manager.dockPanel(typeId);
                        return;
                    }
                    // WHERE IT IS NOW, so the float opens over the panel it came from.
                    ToolWindowState.Bounds remembered = manager.floatingGeometryOf(typeId);
                    float left = remembered != null ? remembered.left() : 0f;
                    float top = remembered != null ? remembered.top() : 0f;
                    if (remembered == null && container.box() != null) {
                        left = container.box().worldX();
                        top = container.box().worldY();
                    }
                    manager.floatPanel(typeId, left, top, type);
                });
    }

    @Nullable
    private static ToolWindowManager managerIn(DataContext data) {
        Workbench workbench = data.get(Workbench.WORKBENCH);
        return workbench == null ? null : workbench.toolWindowManager();
    }

    @Nullable
    private static ViewContainer containerIn(DataContext data) {
        return data.get(ViewContainer.KEY);
    }
}
