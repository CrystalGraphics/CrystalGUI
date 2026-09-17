package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;

/**
 * Which section of the Styles tab a property belongs in, and what each section can offer.
 *
 * <pre>{@code
 * StyleFamilies.Family family = StyleFamilies.of("border-top-width");     // BORDER
 * List<StyleProperty<?>> found = StyleFamilies.search("radius");          // the eight corner longhands
 * }</pre>
 *
 * <p>Seven families, named for what a person is doing rather than for how the engine stores it — the split
 * every design tool uses. A property nobody filed lands in {@link Family#OTHER}, which is listed last and is
 * never empty on purpose: a property registered today is offered today.</p>
 *
 * <p><b>Longhands only.</b> The shorthands a sheet may write ({@code margin}, {@code border-radius},
 * {@code text-stroke}) are not registered properties at all — {@code DeclarationParser} turns them into
 * these — so the palette offers what can actually be written, and a declaration a person hand-wrote as a
 * shorthand is shown under the family its longhands are in.</p>
 */
public final class StyleFamilies {

    private StyleFamilies() {
    }

    /** A section of the Styles tab. */
    public enum Family {
        LAYOUT("Layout"),
        SIZE("Size"),
        TEXT("Text"),
        FILL("Fill"),
        BORDER("Border"),
        EFFECTS("Effects"),
        MOTION("Motion"),
        OTHER("Other");

        private final String label;

        Family(String label) {
            this.label = label;
        }

        /** What the section's header says. */
        public String label() {
            return label;
        }
    }

    /** Exact names first; anything else is decided by the prefixes below. */
    private static final Map<String, Family> BY_NAME = byName();

    /** Prefix → family, longest first so {@code border-*-color} beats {@code border}. */
    private static final Map<String, Family> BY_PREFIX = byPrefix();

    /** Which family {@code property} belongs in. */
    public static Family of(StyleProperty<?> property) {
        return of(property.name);
    }

    /** Which family the property called {@code name} belongs in — {@link Family#OTHER} for an unknown one. */
    public static Family of(String name) {
        Family exact = BY_NAME.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, Family> prefix : BY_PREFIX.entrySet()) {
            if (name.startsWith(prefix.getKey())) return prefix.getValue();
        }
        return Family.OTHER;
    }

    /**
     * Every registered property whose name or family matches {@code query}, best first — what the search
     * field over the palette lists.
     *
     * <p>Matches the shorthand spellings people type as well: {@code radius} finds the eight corner
     * longhands, {@code shadow} finds {@code text-shadow}, {@code bg} finds {@code background}.</p>
     */
    public static List<StyleProperty<?>> search(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.of();
        String expanded = ALIASES.getOrDefault(needle, needle);
        List<StyleProperty<?>> starts = new ArrayList<>();
        List<StyleProperty<?>> contains = new ArrayList<>();
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            String name = property.name;
            if (name.startsWith(expanded)) {
                starts.add(property);
            } else if (name.contains(expanded)) {
                contains.add(property);
            }
        }
        // THE CLOSEST NAME FIRST: `bg` is background before background-color, as the shorter completion is.
        starts.sort(Comparator.comparingInt((StyleProperty<?> property) -> property.name.length()));
        starts.addAll(contains);
        // A SECTION'S NAME FINDS THE SECTION, after everything the name itself matched: `text` is the Text family.
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            if (of(property).label().toLowerCase(Locale.ROOT).startsWith(needle) && !starts.contains(property)) {
                starts.add(property);
            }
        }
        return List.copyOf(starts);
    }

    /** What a person types for something the registry spells differently. Nothing that already matches. */
    private static final Map<String, String> ALIASES = Map.of(
            "bg", "background",
            "round", "radius",
            "corner", "radius",
            "blur", "backdrop-filter",
            "glass", "backdrop-filter",
            "colour", "color",
            "pad", "padding");

    private static Map<String, Family> byName() {
        Map<String, Family> names = new LinkedHashMap<>();
        for (String name : List.of("display", "position", "direction", "flex", "flex-basis", "flex-direction",
                "flex-grow", "flex-shrink", "flex-wrap", "align-content", "align-items", "align-self",
                "justify-content", "justify-items", "justify-self", "box-sizing", "gap", "row-gap", "column-gap",
                "top", "right", "bottom", "left", "z-index", "overflow", "resize", "aspect-ratio")) {
            names.put(name, Family.LAYOUT);
        }
        for (String name : List.of("width", "height", "min-width", "min-height", "max-width", "max-height")) {
            names.put(name, Family.SIZE);
        }
        for (String name : List.of("color", "font-family", "font-size", "font-style", "font-weight", "line-height",
                "text-align", "text-overflow", "white-space", "caret-color", "caret-width", "selection-color",
                // A SHADOW ON TEXT IS PART OF THE TYPE TREATMENT, judged with the weight and the stroke
                // rather than with a blur on a panel.
                "text-shadow", "paint-order", "stroke-align")) {
            names.put(name, Family.TEXT);
        }
        for (String name : List.of("background", "background-color", "opacity", "overlay", "mask")) {
            names.put(name, Family.FILL);
        }
        for (String name : List.of("outline", "outline-color", "outline-width")) {
            names.put(name, Family.BORDER);
        }
        for (String name : List.of("backdrop-filter", "cursor", "tooltip-delay")) {
            names.put(name, Family.EFFECTS);
        }
        for (String name : List.of("transition", "transform", "transform-origin-x", "transform-origin-y",
                "scroll-behavior", "scroll-duration")) {
            names.put(name, Family.MOTION);
        }
        return Map.copyOf(names);
    }

    private static Map<String, Family> byPrefix() {
        // A LinkedHashMap because the first match wins and these are ordered most specific first.
        Map<String, Family> prefixes = new LinkedHashMap<>();
        prefixes.put("grid-", Family.LAYOUT);
        prefixes.put("margin-", Family.LAYOUT);
        prefixes.put("padding-", Family.LAYOUT);
        prefixes.put("border-", Family.BORDER);          // widths, colors and the eight radius longhands
        prefixes.put("outline-", Family.BORDER);
        prefixes.put("text-decoration", Family.TEXT);
        prefixes.put("text-stroke", Family.TEXT);
        prefixes.put("text-fill", Family.TEXT);
        prefixes.put("text-offset", Family.TEXT);
        prefixes.put("font-", Family.TEXT);
        prefixes.put("overlay-", Family.FILL);
        prefixes.put("mask-", Family.FILL);
        return prefixes;
    }

    /**
     * Where {@code name} sits among its family's rows: always for {@link Family#TEXT}, whose sample rows must run
     * unbroken, and for every family of an inline style, which has no source order. Lower first; a name not listed
     * goes after, alphabetically.
     */
    public static int rank(String name) {
        int at = ORDER.indexOf(name);
        return at >= 0 ? at : ORDER.size();
    }

    /** The order a person reads a family in: what it is, then how big, then how it looks. */
    private static final List<String> ORDER = List.of(
            "display", "position", "top", "right", "bottom", "left", "z-index", "flex-direction", "flex-wrap",
            "align-items", "align-content", "align-self", "justify-content", "justify-items", "justify-self",
            "gap", "row-gap", "column-gap", "flex", "flex-grow", "flex-shrink", "flex-basis", "overflow",
            "width", "height", "min-width", "min-height", "max-width", "max-height", "aspect-ratio",
            // COLOR, THEN SIZE, then every row drawn as a sample in one unbroken run, then how the text is laid
            // out, the decoration's color, and what editing it looks like.
            "color", "font-size",
            "font-family", "font-weight", "font-style", "text-stroke", "text-decoration-line",
            "text-shadow", "paint-order",
            "stroke-align", "text-align", "white-space", "text-overflow", "text-offset-x", "text-offset-y",
            "text-decoration-color",
            "line-height", "caret-color", "caret-width", "selection-color",
            "background", "background-color", "opacity", "overlay", "mask",
            // THE SHAPE, THEN EVERY ROW DRAWN AS A SAMPLE, THEN THE COLORS: the corners first, the border and outline
            // chips in one run, and the colors that paint them after.
            "border-radius", "border-width", "outline", "outline-offset",
            "border-color", "border-top-color", "border-bottom-color",
            "backdrop-filter", "cursor", "tooltip-delay",
            "transform", "transition");

    /** The families in the order a tab shows them. */
    public static List<Family> inOrder() {
        return List.of(Family.LAYOUT, Family.SIZE, Family.TEXT, Family.FILL, Family.BORDER,
                Family.EFFECTS, Family.MOTION, Family.OTHER);
    }

    /** The family a declaration belongs to, for a name the registry does not know. */
    public static Family of(@Nullable StyleProperty<?> property, String name) {
        return property != null ? of(property) : of(name);
    }
}
