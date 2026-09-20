package com.crystalgui.workbench.stripe;

import java.util.List;
import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionDropZones;
import com.crystalgui.workbench.region.RegionSide;
import com.crystalgui.workbench.toolwindow.ToolWindowManager;

/**
 * What a right-click on an activity bar offers — IntelliJ's stripe context menu: <i>Hide</i>,
 * <i>Move to</i> ▸ and <i>Show Tool Window Names</i>.
 *
 * <p>Resolved from the button that was pressed ({@link StripeView#BUTTON_TYPE}) and the workbench around
 * it, so one registration serves both rails and every tool window in them — the same shape
 * {@code ToolWindowCommands} takes for the ⋮ menu.</p>
 *
 * <h3>Hide is about the RAIL, not the window</h3>
 *
 * <p>It clears {@code show_stripe_button} and touches nothing else, so the window keeps its region, its
 * side, its order and its size and comes back exactly where it was. The ⋯ menu is the way
 * back, which is what makes hiding a button safe rather than a way to lose a tool window.</p>
 */
public final class StripeCommands {

    public static final String HIDE = "toolwindow.stripe.hide";
    public static final String SHOW_NAMES = "toolwindow.stripe.showNames";

    /**
     * The regions a tool window can be moved to, in the order the submenu lists them.
     *
     * <p>A LIST, not an array: a {@code public static final} array is a constant nobody can rely on, since
     * every caller can write through it. {@code DockRegion.EDITOR} is deliberately absent — an editor area
     * is not somewhere a tool window goes.</p>
     */
    public static final List<DockRegion> REGIONS =
            List.of(DockRegion.SIDEBAR, DockRegion.AUXILIARY, DockRegion.PANEL);

    /** {@code toolwindow.stripe.moveTo.<REGION>.<SIDE>} — one per slot, which is what a menu item needs. */
    public static String moveTo(DockRegion region, RegionSide side) {
        return "toolwindow.stripe.moveTo." + region.name() + "." + side.name();
    }

    private StripeCommands() {
    }

    /** Registers into {@link CommandRegistry#global()}. Idempotent. */
    public static void register() {
        CommandRegistry.global().contribute(StripeCommands.class, StripeCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(HIDE, "Hide")
                .enabledWhereData(data -> managerIn(data) != null && typeIn(data) != null)
                .runWithData(data -> {
                    ToolWindowManager manager = managerIn(data);
                    String typeId = typeIn(data);
                    if (manager != null && typeId != null) manager.setStripeButtonShown(typeId, false);
                }));

        registry.register(Command.of(SHOW_NAMES, "Show Tool Window Names")
                // NO BUTTON NEEDED: this is the bar's own setting, and the menu it sits in opens on the
                // bar's blank space as well as on a button.
                .enabledWhereData(data -> managerIn(data) != null)
                .toggledWhereData(data -> managerIn(data) != null && managerIn(data).isShowingNames())
                .runWithData(data -> {
                    ToolWindowManager manager = managerIn(data);
                    if (manager != null) manager.setShowingNames(!manager.isShowingNames());
                }));

        for (DockRegion region : REGIONS) {
            for (RegionSide side : RegionSide.values()) {
                registry.register(move(region, side));
            }
        }
    }

    /**
     * One slot of the <i>Move to</i> submenu.
     *
     * <p>Labelled by {@link RegionDropZones#labelFor} — the same words the drag's ghost uses for the same
     * place, so "Right Bottom" means one thing whichever way you got there.</p>
     */
    private static Command move(DockRegion region, RegionSide side) {
        RegionDropZones.Target slot = new RegionDropZones.Target(region, side);
        return Command.of(moveTo(region, side), RegionDropZones.labelFor(slot))
                .enabledWhereData(data -> managerIn(data) != null && typeIn(data) != null)
                .toggledWhereData(data -> {
                    ToolWindowManager manager = managerIn(data);
                    String typeId = typeIn(data);
                    return manager != null && typeId != null
                            && manager.regionOf(typeId) == region && manager.sideOf(typeId) == side;
                })
                .runWithData(data -> {
                    ToolWindowManager manager = managerIn(data);
                    String typeId = typeIn(data);
                    if (manager != null && typeId != null) manager.moveTo(typeId, region, side);
                });
    }

    @Nullable
    private static ToolWindowManager managerIn(DataContext data) {
        Workbench workbench = data.get(Workbench.WORKBENCH);
        return workbench == null ? null : workbench.toolWindowManager();
    }

    @Nullable
    private static String typeIn(DataContext data) {
        return data.get(StripeView.BUTTON_TYPE);
    }
}
