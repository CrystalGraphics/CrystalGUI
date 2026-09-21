package com.crystalgui.workbench.view;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.command.ActionIcons;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.widget.composite.ActionButton;

/**
 * A view that puts action buttons on its container's title line, before the options menu and Hide — IntelliJ's
 * {@code ToolWindowEx.setTitleActions}.
 *
 * <pre>{@code
 * public List<ActionButton> titleActions() {
 *     return List.of(
 *             ActionButton.menu("New…", NEW_MENU).icon(ActionIcons.ADD).context(tree),
 *             ActionButton.command(TreeViewCommands.EXPAND_SELECTED)
 *                     .icon(ActionIcons.EXPAND_ALL).context(tree),
 *             ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
 *                     .icon(ActionIcons.COLLAPSE_ALL).context(tree));
 * }
 *
 * public MenuId optionsMenu() {
 *     return MY_OPTIONS;   // above View Mode in the ⋮ menu
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Asked once, when the view is mounted alone in its container; the buttons are kept, so build them once
 *       and hand back the same ones.</li>
 *   <li>Name each button's {@code context}: the header is outside the view, and in a floating tool window it
 *       is in a window's caption.</li>
 *   <li>Beside {@link HeaderContributor}, not instead: that one places content after the title, this one
 *       right-aligns actions.</li>
 * </ul>
 */
public interface TitleActionsContributor {

    /** The buttons, left to right. */
    List<ActionButton> titleActions();

    /** Rows for the top of the ⋮ menu, above the tool window's own View Mode; null for none. */
    @Nullable
    default MenuId optionsMenu() {
        return null;
    }
}
