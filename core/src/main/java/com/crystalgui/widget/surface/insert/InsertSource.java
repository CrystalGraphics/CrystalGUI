package com.crystalgui.widget.surface.insert;

import java.util.List;


/**
 * Where the insert menu gets its rows — a node library, a template folder, a list of recent picks.
 *
 * <p>Register one and its offers appear in the menu the engine opens on the surface; several sources
 * merge into one searchable tree, grouped by each row's {@code path()}.</p>
 *
 * <pre>{@code
 * ctx.registerInsertSource(() -> library.kinds().stream()
 *         .map(kind -> new KindInsertable(document, kind))
 *         .toList());
 * }</pre>
 *
 * <p>{@link #offers()} is called when the menu opens, not per keystroke — build the list there rather
 * than caching one that goes stale when the library changes.</p>
 */
@FunctionalInterface
public interface InsertSource {

    List<Insertable> offers();
}
