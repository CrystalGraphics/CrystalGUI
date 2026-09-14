package com.crystalgui.widget.surface.insert;

import java.util.List;

import javax.annotation.Nullable;

/**
 * One row in the insert menu, and what happens when it is chosen.
 *
 * <p>Offered by an {@code InsertSource}. The engine's menu ranks the {@link #label}, the {@link #synonyms} and the
 * {@link #path} against a query, groups rows by path while browsing, and calls {@link #insert} with the world
 * point the menu was opened at.</p>
 *
 * <pre>{@code
 * new Insertable() {
 *     public String label()      { return "Button"; }
 *     public List<String> path() { return List.of("Controls"); }
 *     public void insert(float worldX, float worldY) { document.addButton(worldX, worldY); }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A source builds these already bound to whatever they need — the engine hands nothing but the point, so an
 *       insertable holds its own document, context or factory.</li>
 *   <li>A shortcut to an offer listed elsewhere — a Recent row — is {@link #browsingOnly}, so a search does not
 *       list it twice.</li>
 * </ul>
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

    /** One line saying what it is, shown for the highlighted row, or null. */
    @Nullable
    default String description() {
        return null;
    }

    /** An icon id, or null for none. */
    @Nullable
    default String icon() {
        return null;
    }

    /** A class the icon wears, so a theme can tint it, or null. */
    @Nullable
    default String iconClass() {
        return null;
    }

    /** Whether the row is only listed while browsing: a shortcut to an offer a search already finds under its own path. */
    default boolean browsingOnly() {
        return false;
    }

    /** Inserts, at the point the menu was opened. */
    void insert(float worldX, float worldY);
}
