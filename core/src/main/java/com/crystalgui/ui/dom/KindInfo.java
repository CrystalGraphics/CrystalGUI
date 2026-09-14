package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * What a picker or a tree needs to know about a kind of element beyond its name: what it is called, where it
 * files, what else it is called, one line about it, the glyph a tree row draws for it, and what a Library card
 * shows.
 *
 * <pre>{@code
 * UIElementRegistry.register(Button.NAME, Button::new, CONTRACT,
 *         KindInfo.named("Button")
 *                 .inCategory("Controls")
 *                 .synonyms("press", "click")
 *                 .describedAs("A labelled push button.")
 *                 .glyph(GlyphRole.CONTROL)                    // draws crystalgui:nodes/ui/button
 *                 .starter(() -> new Button("Button"))           // what placing one inserts
 *                 .preview(Preview.sample(() -> new Button("Save")).width(60)));   // what its card shows
 *
 * // a kind that is another kind in all but name borrows its glyph, and its role with it
 * KindInfo.named("Boolean Field").glyphOf(Checkbox.NAME);
 *
 * // an internal part a document never holds on its own
 * UIElementRegistry.register(Grip.NAME, Grip::new, CONTRACT, KindInfo.hidden());
 * }</pre>
 *
 * <p>Read by anything that lists or labels kinds — an Insert menu, a Library panel, the Hierarchy's glyphs —
 * so each files, searches and draws a kind identically. Everything is optional: a kind registered without
 * one gets {@link #derived}, which a reader fills in from the node itself.</p>
 *
 * <ul>
 *   <li>A kind's own glyph is found by its name: {@code mymod:machine} draws {@code ui/icons/nodes/ui/machine.svg}
 *       in the {@code mymod} namespace, drawn in {@code currentColor}; {@link #glyph(String, GlyphRole)} names
 *       another file. Declaring a glyph without shipping the file draws nothing.</li>
 *   <li>A kind with no glyph may still get one from its superclass or its layout — see the Hierarchy.</li>
 *   <li>Synonyms are what makes typing {@code press} find {@code Button}. That cannot be a property of the
 *       matcher — no amount of fuzziness knows a checkbox is a "tick" — so it is declared here.</li>
 *   <li>{@link #displayName} is null when undeclared: the node's class name, split at capitals, is the better
 *       answer and only the node has it.</li>
 *   <li>A kind is {@link #listed} unless it says otherwise, so an addon's widget is placeable with no
 *       declaration; an uncategorised one files under its namespace.</li>
 * </ul>
 */
public record KindInfo(String category, List<String> synonyms, @Nullable String description,
        @Nullable String displayName, @Nullable GlyphRole glyphRole, @Nullable String glyphIcon,
        @Nullable Name glyphOf, Preview preview, boolean listed,
        @Nullable Supplier<? extends UIElement> starter) {

    public KindInfo {
        category = category == null ? "" : category;
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        preview = preview == null ? Preview.DERIVED : preview;
    }

    /** Files under {@code category} — {@code "Controls"}, or {@code "Layout/Containers"} for a path. */
    public static KindInfo of(String category) {
        return derived().inCategory(category);
    }

    /** Called {@code displayName} wherever a person reads the kind — {@code "Tab View"}. */
    public static KindInfo named(String displayName) {
        return new KindInfo("", List.of(), null, displayName, null, null, null, Preview.DERIVED, true, null);
    }

    /** What a kind that declared nothing gets: no category, no synonyms, no name, no glyph, listed. */
    public static KindInfo derived() {
        return new KindInfo("", List.of(), null, null, null, null, null, Preview.DERIVED, true, null);
    }

    /** A kind no picker lists — machinery, or a part that only means something inside its parent. */
    public static KindInfo hidden() {
        return derived().hide();
    }

    public KindInfo inCategory(String category) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf, preview, listed, starter);
    }

    public KindInfo synonyms(String... words) {
        return new KindInfo(category, List.of(words), description, displayName, glyphRole, glyphIcon, glyphOf, preview, listed, starter);
    }

    public KindInfo describedAs(String description) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf, preview, listed, starter);
    }

    /** This kind ships its own glyph, named after it — {@code mymod:machine} is {@code mymod:nodes/ui/machine} — tinted by {@code role}. */
    public KindInfo glyph(GlyphRole role) {
        return new KindInfo(category, synonyms, description, displayName, role, null, null, preview, listed, starter);
    }

    /** This kind's glyph is the icon {@code icon}, {@code namespace:path} under {@code ui/icons/}, tinted by {@code role}. */
    public KindInfo glyph(String icon, GlyphRole role) {
        return new KindInfo(category, synonyms, description, displayName, role, icon, null, preview, listed, starter);
    }

    /** This kind draws {@code other}'s glyph in {@code other}'s role — a boolean field is a checkbox. */
    public KindInfo glyphOf(Name other) {
        return new KindInfo(category, synonyms, description, displayName, null, null, other, preview, listed, starter);
    }

    /** What a Library card shows for this kind. */
    public KindInfo preview(Preview preview) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf, preview, listed, starter);
    }

    /**
     * What placing this kind inserts — a button reading {@code Button}, a tab view with two tabs. Without one a
     * placement builds the registered factory. The card shows this too, unless a {@link Preview.Sample} says otherwise.
     */
    public KindInfo starter(Supplier<? extends UIElement> starter) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf, preview, listed, starter);
    }

    /** Keeps this kind out of every picker; its name, glyph and category still answer where a node of it exists. */
    public KindInfo hide() {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf, preview, false, starter);
    }

    /** The category as its segments, for a menu that draws a trail. Empty when it files under nothing. */
    public List<String> categorySegments() {
        if (category.isEmpty()) return List.of();
        List<String> segments = new ArrayList<>();
        for (String part : category.split("/")) {
            if (!part.isBlank()) segments.add(part.trim());
        }
        return List.copyOf(segments);
    }
}
