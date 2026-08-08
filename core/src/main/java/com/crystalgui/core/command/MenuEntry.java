package com.crystalgui.core.command;

/**
 * One row of a menu, resolved against a context — VS Code's {@code MenuItemAction} /
 * {@code SubmenuItemAction} pair.
 *
 * <h3>Why the resolved row is a type and not just a {@link Command}</h3>
 *
 * <p>A menu needs three things a command cannot answer on its own: <b>where</b> it sits (the placement is
 * per-menu, and one command may appear in several), <b>whether it applies right now</b>, and <b>whether it
 * is a checkmark and which way it points</b>. Returning bare commands meant every renderer re-derived all
 * three, and two renderers derived them differently — which is exactly how a menu bar and a context menu
 * end up disagreeing about separators and greying.</p>
 *
 * <p>So the registry answers the question once, and a renderer decides only what to <em>draw</em>.
 * {@code enabled} in particular is carried rather than applied: a context menu dims a disabled row and a
 * palette hides one, and neither is the registry's call.</p>
 */
public sealed interface MenuEntry permits MenuEntry.Item, MenuEntry.Submenu {

    /** The section this row belongs to — the sort key AND the separator boundary. @see MenuSection */
    String group();

    /** Position within {@link #group()}. */
    int order();

    /**
     * A command row.
     *
     * @param command   the command itself. Held rather than only its id because a
     *                  {@link MenuContributor} may produce a command that is not registered anywhere —
     *                  a Recent Files entry exists for as long as the menu is open and no longer.
     * @param enabled   {@link Command#isEnabled} against the context this was resolved for
     * @param checkable whether to draw a checkmark column at all — {@link Command#isCheckable()}
     * @param checked   which way the checkmark points, meaningless unless {@code checkable}
     */
    record Item(Command command, String group, int order,
                boolean enabled, boolean checkable, boolean checked) implements MenuEntry {

        /** A plain, enabled, uncheckable row — what most callers building an entry by hand want. */
        public static Item of(Command command, String group, int order) {
            return new Item(command, group, order, true, false, false);
        }
    }

    /**
     * A nested menu, to be expanded by whoever builds the rows.
     *
     * <p>Deliberately NOT expanded here: resolving a submenu means resolving every command in it, and a
     * menu bar with six top-level menus would resolve the whole application's command set to draw one
     * row. The builder expands a submenu when it is opened, which is also when its enablement is
     * actually true.</p>
     */
    record Submenu(MenuId menu, String title, String group, int order) implements MenuEntry {
    }
}
