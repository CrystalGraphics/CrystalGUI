package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * What a picker or a tree needs to know about a kind of element beyond its name: what it is called, where it
 * files, what else it is called, one line about it, and the glyph a tree row draws for it.
 *
 * <pre>{@code
 * UIElementRegistry.register(Button.NAME, Button::new, CONTRACT,
 *         KindInfo.named("Button")
 *                 .inCategory("Controls")
 *                 .synonyms("press", "click")
 *                 .describedAs("A labelled push button.")
 *                 .glyph(GlyphRole.CONTROL));                  // draws crystalgui:nodes/ui/button
 *
 * // a kind that is another kind in all but name borrows its glyph, and its role with it
 * KindInfo.named("Boolean Field").glyphOf(Checkbox.NAME);
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
 * </ul>
 */
public record KindInfo(String category, List<String> synonyms, @Nullable String description,
        @Nullable String displayName, @Nullable GlyphRole glyphRole, @Nullable String glyphIcon,
        @Nullable Name glyphOf) {

    public KindInfo {
        category = category == null ? "" : category;
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    /** Files under {@code category} — {@code "Controls"}, or {@code "Layout/Containers"} for a path. */
    public static KindInfo of(String category) {
        return new KindInfo(category, List.of(), null, null, null, null, null);
    }

    /** Called {@code displayName} wherever a person reads the kind — {@code "Tab View"}. */
    public static KindInfo named(String displayName) {
        return new KindInfo("", List.of(), null, displayName, null, null, null);
    }

    /** What a kind that declared nothing gets: no category, no synonyms, no name, no glyph. */
    public static KindInfo derived() {
        return new KindInfo("", List.of(), null, null, null, null, null);
    }

    public KindInfo inCategory(String category) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf);
    }

    public KindInfo synonyms(String... words) {
        return new KindInfo(category, List.of(words), description, displayName, glyphRole, glyphIcon, glyphOf);
    }

    public KindInfo describedAs(String description) {
        return new KindInfo(category, synonyms, description, displayName, glyphRole, glyphIcon, glyphOf);
    }

    /** This kind ships its own glyph, named after it — {@code mymod:machine} is {@code mymod:nodes/ui/machine} — tinted by {@code role}. */
    public KindInfo glyph(GlyphRole role) {
        return new KindInfo(category, synonyms, description, displayName, role, null, null);
    }

    /** This kind's glyph is the icon {@code icon}, {@code namespace:path} under {@code ui/icons/}, tinted by {@code role}. */
    public KindInfo glyph(String icon, GlyphRole role) {
        return new KindInfo(category, synonyms, description, displayName, role, icon, null);
    }

    /** This kind draws {@code other}'s glyph in {@code other}'s role — a boolean field is a checkbox. */
    public KindInfo glyphOf(Name other) {
        return new KindInfo(category, synonyms, description, displayName, null, null, other);
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
