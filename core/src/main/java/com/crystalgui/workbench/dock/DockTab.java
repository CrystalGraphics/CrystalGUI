package com.crystalgui.workbench.dock;

import javax.annotation.Nullable;

import com.crystalgui.core.command.CommandContext;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.workbench.dock.layout.DockPanelRef;

/**
 * The tab a tab command acts on: the one right-clicked when it runs from a tab's menu, and otherwise the front tab of
 * the dock's active group — VS Code's rule for editor commands, so one command serves the tab menu, the
 * keyboard and the main menu alike.
 *
 * <pre>{@code
 * .run(context -> {
 *     DockTab tab = DockTab.of(context);
 *     if (tab != null) tab.area().closePanel(tab.panel());
 * })
 * }</pre>
 *
 * @param area  the dock holding the tab, which may be a torn-out window's
 * @param group the group holding the tab
 * @param panel the tab's panel
 */
public record DockTab(DockArea area, DockGroup group, DockPanelRef panel) {

    /** The tab {@code context} is about, or null when it is in no dock or its group holds nothing. */
    @Nullable
    public static DockTab of(CommandContext context) {
        Tab pressed = null;
        DockGroup pressedIn = null;
        for (UIElement at = UIElement.sourceOf(context); at != null; at = at.parentElement()) {
            if (pressed == null && pressedIn == null && at instanceof Tab tab) pressed = tab;
            if (pressed != null && pressedIn == null && at instanceof DockGroup group) pressedIn = group;
            if (at instanceof DockArea area) {
                // A PRESSED TAB, or the ACTIVE group's front tab -- never the group focus happens to be in, for the
                // reason DockCommands gives.
                DockPanelRef panel = pressedIn == null ? null : pressedIn.panelOf(pressed);
                if (panel != null) return new DockTab(area, pressedIn, panel);
                DockGroup active = area.activeGroup();
                DockPanelRef front = active == null ? null : active.leaf().activePanel();
                return front == null ? null : new DockTab(area, active, front);
            }
        }
        return null;
    }

    /** Where the tab sits in its group's strip. */
    public int index() {
        return group.leaf().indexOf(panel);
    }
}
