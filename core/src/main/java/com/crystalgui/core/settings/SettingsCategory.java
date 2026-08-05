package com.crystalgui.core.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * What a level of a setting id <em>is</em> — a page in the navigation tree, or a section inside one.
 *
 * <h3>Two questions, one path</h3>
 *
 * <p>{@code editor.appearance.a11y.zoom} has to answer both "where do I click to find this" and "what
 * heading does it sit under once I am there". They are different questions and the temptation is two
 * mechanisms — a tree derived from the id, plus a {@code group("Accessibility")} field on the setting.
 * That gives a setting two positions that can disagree, and then "where does this appear" has two answers
 * and you have to know which wins.</p>
 *
 * <p>So there is one vocabulary — the id — and a level is either:</p>
 *
 * <ul>
 *   <li>{@link Kind#PAGE} — its own node in the tree, with a page of its own;</li>
 *   <li>{@link Kind#SECTION} — a titled heading inside the nearest enclosing page.</li>
 * </ul>
 *
 * <p>IntelliJ's "Accessibility" is exactly this: conceptually a sub-category of Appearance that is not big
 * enough to deserve a tree node. Same relationship, different rendering, and promoting it later is one
 * word here rather than a re-declaration of every setting under it.</p>
 *
 * <h3>The tree is authored; the sections fall out</h3>
 *
 * <p>Only pages need declaring. Everything below the deepest declared page becomes section structure, so
 * <b>adding a setting can never change the shape of the navigation</b> — which is the failure a fully
 * derived tree has, and it is invisible until somebody's menu grows a node they did not ask for.</p>
 *
 * <p>An undeclared level still works: it is a section, titled by prettifying its segment. Declaring it
 * buys a proper title and the choice of kind, not the ability to exist.</p>
 */
public final class SettingsCategory {

    public enum Kind {
        /** A node in the tree, with a page. */
        PAGE,
        /** A heading inside the nearest enclosing page. */
        SECTION
    }

    private static final Map<String, SettingsCategory> BY_PATH = new LinkedHashMap<>();

    private final String path;
    private final String title;
    private final Kind kind;

    private SettingsCategory(String path, String title, Kind kind) {
        this.path = path;
        this.title = title;
        this.kind = kind;
    }

    /** Declares {@code path} as a node in the tree. */
    public static SettingsCategory page(String path, String title) {
        return register(new SettingsCategory(path, title, Kind.PAGE));
    }

    /** Declares {@code path} as a heading inside its enclosing page — a title, without a tree node. */
    public static SettingsCategory section(String path, String title) {
        return register(new SettingsCategory(path, title, Kind.SECTION));
    }

    private static SettingsCategory register(SettingsCategory category) {
        // Replaces, like CommandRegistry and SettingsRegistry: a theme or a mod re-titling a built-in
        // category is the point, not a collision.
        BY_PATH.put(category.path, category);
        return category;
    }

    @Nullable
    public static SettingsCategory get(String path) {
        return BY_PATH.get(path);
    }

    /** Whether this level gets a node of its own. Undeclared levels do not — see the class note. */
    public static boolean isPage(String path) {
        SettingsCategory category = BY_PATH.get(path);
        return category != null && category.kind == Kind.PAGE;
    }

    /** The declared title, or null to let the caller prettify the segment. */
    @Nullable
    public static String titleOf(String path) {
        SettingsCategory category = BY_PATH.get(path);
        return category == null ? null : category.title;
    }

    public static Map<String, SettingsCategory> all() {
        return new LinkedHashMap<>(BY_PATH);
    }

    /** For tests, which must not inherit whatever another test declared. */
    public static void clear() {
        BY_PATH.clear();
    }

    public String path() {
        return path;
    }

    public String title() {
        return title;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public String toString() {
        return path + " (" + kind + ")";
    }
}
