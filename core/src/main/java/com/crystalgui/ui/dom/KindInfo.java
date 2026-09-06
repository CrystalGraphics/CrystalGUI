package com.crystalgui.ui.dom;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What a picker needs to know about a kind of element beyond its name: where it files, what else it is
 * called, and one line about it.
 *
 * <pre>{@code
 * UIElementRegistry.register(Button.NAME, Button::new, CONTRACT,
 *         KindInfo.of("Controls").synonyms("press", "click").describedAs("A labelled push button."));
 * }</pre>
 *
 * <p>Read by anything that lists kinds — an Insert menu, a Library panel — so both file and search them
 * identically. Optional: a kind registered without one gets {@link #derived}, which files it under
 * nothing and searches by its own local name.</p>
 *
 * <p>Synonyms are what makes typing {@code press} find {@code Button}. That cannot be a property of the
 * matcher — no amount of fuzziness knows a checkbox is a "tick" — so it is declared here and a picker
 * only has to ask.</p>
 */
public record KindInfo(String category, List<String> synonyms, @Nullable String description) {

    public KindInfo {
        category = category == null ? "" : category;
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    /** Files under {@code category} — {@code "Controls"}, or {@code "Layout/Containers"} for a path. */
    public static KindInfo of(String category) {
        return new KindInfo(category, List.of(), null);
    }

    public KindInfo synonyms(String... words) {
        return new KindInfo(category, List.of(words), description);
    }

    public KindInfo describedAs(String description) {
        return new KindInfo(category, synonyms, description);
    }

    /** What a kind that declared nothing gets: no category, no synonyms, its own name to search by. */
    public static KindInfo derived() {
        return new KindInfo("", List.of(), null);
    }

    /** The category as its segments, for a menu that draws a trail. Empty when it files under nothing. */
    public List<String> categorySegments() {
        if (category.isEmpty()) return List.of();
        List<String> segments = new java.util.ArrayList<>();
        for (String part : category.split("/")) {
            if (!part.isBlank()) segments.add(part.trim());
        }
        return List.copyOf(segments);
    }
}
