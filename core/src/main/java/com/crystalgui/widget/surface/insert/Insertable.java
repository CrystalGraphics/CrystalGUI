package com.crystalgui.widget.surface.insert;

import java.util.List;

import javax.annotation.Nullable;

/**
 * One row in the insert menu, and what happens when it is chosen.
 *
 * <p>Offered by an {@code InsertSource}. The engine's menu searches the {@link #label}, the
 * {@link #path} and the {@link #synonyms}, groups rows by path, and calls {@link #insert} with the
 * world point the menu was opened at.</p>
 *
 * <pre>{@code
 * new Insertable() {
 *     public String label()      { return "Button"; }
 *     public List<String> path() { return List.of("Controls"); }
 *     public void insert(float worldX, float worldY) { document.addButton(worldX, worldY); }
 * }
 * }</pre>
 *
 * <p>A source builds these already bound to whatever they need — the engine hands nothing but the
 * point, so an insertable holds its own document, context or factory.</p>
 */
public interface Insertable {

    /** The row's text, and the first thing a search matches. */
    String label();

    /** Categories from the root down, deciding where the row is grouped. Empty means top level. */
    default List<String> path() {
        return List.of();
    }

    /** Extra words a search should match — "rect" finding "Rectangle". */
    default List<String> synonyms() {
        return List.of();
    }

    /** An icon id, or null for none. */
    @Nullable
    default String icon() {
        return null;
    }

    /** Inserts, at the point the menu was opened. */
    void insert(float worldX, float worldY);
}
