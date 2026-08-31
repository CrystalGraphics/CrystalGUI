package com.crystalgui.widget.collection.list;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.ClipboardCommands;
import com.crystalgui.widget.overlay.ContextMenu;

/**
 * Installs the default right-click menu every {@link ListView} gets.
 *
 * <h3>Why it is here and not on {@code ContextMenu}</h3>
 *
 * <p>It was a static on {@code ContextMenu}, and {@code LayeringTest} refused it: {@code
 * widget.overlay} is tier 3 and {@code widget.collection} is tier 6, so a menu naming a list is a
 * layer reaching upward. That is the check working — a {@code ContextMenu} is general-purpose and
 * must not know that lists exist, while a list may perfectly well know about menus.</p>
 *
 * <p>So the direction inverts and nothing else changes: the same installer, registered from the
 * side that is allowed to name both.</p>
 */
public final class ListContextMenus {

    private ListContextMenus() {
    }

    /**
     * Gives <b>every</b> list and tree a right-click <b>Copy</b>, installed once.
     *
     * <p><b>A hook rather than a call per widget</b>, because "all of them" is the requirement: a helper
     * hosts opt into is a list of call sites that is wrong the moment somebody adds a list and forgets.
     * It cannot live in the list either — a menu is chrome, {@code ui.elements.list} cannot see this
     * package, and inverting that would make the two mutually dependent for one item. So the list
     * supplies the seam and this supplies the behaviour. The list's own part is answering <em>what Copy
     * means here</em> ({@code UiDataKeys.CLIPBOARD}); naming the verb is chrome's.</p>
     *
     * <p><b>It names the row without selecting it.</b> A context menu resolves against what was CLICKED
     * while a menu bar resolves against what has FOCUS — opposite rules, both right, because a
     * right-click says which row it is about. Selecting instead would destroy the selection the menu was
     * opened over, which for a multi-selection cannot be undone. The explorer's own menu does select, and
     * that is not a contradiction: its verbs act on the selection, so the selection has to become the
     * subject. Copy has a subject of its own.</p>
     *
     * <p>A widget with a menu of its own declines this — see {@code ListView.suppressDefaultContextMenu}.
     * {@code attach} keeps one live menu per attachment site, but two attachments on one element are two
     * listeners and both would open.</p>
     */
    public static void installDefault(CommandRegistry registry) {
        ListView.setDefaultContextMenuInstaller(list -> ContextMenu.attach(list, registry, element -> {
            if (list.isDefaultContextMenuSuppressed()) return null;
            int index = list.indexOfRowElement(element);
            if (index < 0) return null;
            list.setContextRow(index);
            return ContextMenu.builder().item(ClipboardCommands.COPY);
        }));
    }

}
