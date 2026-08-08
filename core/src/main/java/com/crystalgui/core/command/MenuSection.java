package com.crystalgui.core.command;

import java.util.List;

/**
 * A run of menu rows sharing one {@code group} — the unit a separator sits between.
 *
 * <h3>Why the grouping survives the query</h3>
 *
 * <p>{@code CommandRegistry.menu()} sorted by group and then <b>threw the grouping away</b>, returning a
 * flat list. A renderer given that list cannot draw a separator anywhere, because the one fact that says
 * where a separator goes has already been discarded — so every menu in the application was a single
 * undivided run of rows, and the {@code "1_new"} / {@code "2_open"} convention every contributor was
 * dutifully following did nothing but sort.</p>
 *
 * <p>{@code ContextMenu} worked around it by re-querying {@link CommandRegistry#all()} and re-deriving the
 * grouping itself, which is the same computation in a second place and the reason the menu bar would have
 * been a third. See {@link CommandRegistry#sections}.</p>
 *
 * <p><b>Separators are drawn BETWEEN sections, never declared.</b> VS Code's rule and IntelliJ's outcome:
 * a contributor states which section it belongs to and never states a separator, so adding an item to an
 * existing section cannot produce a stray rule and adding the first item of a new one cannot fail to.</p>
 *
 * @param group   the group id, e.g. {@code "1_new"} — sorts lexicographically, hence the leading digit
 * @param entries rows in {@code order}, never empty
 */
public record MenuSection(String group, List<MenuEntry> entries) {
}
