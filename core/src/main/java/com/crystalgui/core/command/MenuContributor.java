package com.crystalgui.core.command;

import java.util.List;

/**
 * Rows computed when a menu opens, rather than registered ahead of time — IntelliJ's
 * {@code ActionGroup.getChildren}, which is computed per invocation for exactly this reason.
 *
 * <h3>What a static placement cannot express</h3>
 *
 * <p>{@code Command.menu(id, group, order)} says where <em>one known command</em> goes. Recent Files is
 * not one known command: it is N rows whose count, labels and actions are only knowable at the moment the
 * menu opens, and the same is true of a Window menu listing the open editors. There is no id to register
 * against, because the thing being offered is the <em>list</em>.</p>
 *
 * <p>Both references solve it the same way — a group whose children are a function rather than a
 * collection — and neither tries to keep a registry in sync with the underlying list, because a registry
 * that has to be maintained to stay correct will go stale exactly once and then look like a bug in the
 * menu.</p>
 *
 * <h3>Returns entries, not commands</h3>
 *
 * <p>So a contributor can place its rows in a real section and mark them enabled or checked, on the same
 * footing as a registered command. A contributed {@link Command} need not be registered anywhere: it is
 * held by the {@link MenuEntry.Item} and run directly if the registry does not know its id, which is what
 * lets "open this specific recent file" exist without polluting the palette with one command per file.</p>
 *
 * <h3>Called on every open</h3>
 *
 * <p>That is the point — it is what "computed" means. Keep it cheap: it runs while a menu is being built,
 * in the frame the user clicked. Reading a list somebody else maintains is the intended cost; going to
 * disk is not.</p>
 */
@FunctionalInterface
public interface MenuContributor {

    /**
     * The rows to add to {@code menu} right now.
     *
     * <p>{@code menu} is passed even though contributors are registered per-menu, so one object may serve
     * several ids and still know which it is answering for.</p>
     *
     * <p>Return an empty list to contribute nothing; a section with no entries is dropped, so a
     * contributor that has nothing to say costs a separator rather than producing a stray one.</p>
     */
    List<MenuEntry> itemsFor(MenuId menu, CommandContext context);
}
