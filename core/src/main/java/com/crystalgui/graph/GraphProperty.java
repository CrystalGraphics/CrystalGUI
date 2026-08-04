package com.crystalgui.graph;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A value the finished shader exposes — declared once on the graph, referenced by any number of nodes.
 *
 * <p>Unity reference: {@code docs/research/unity-blackboard/}, particularly
 * {@code 12-property-vector2-settings.png} for the form and {@code 11-add-property-menu.png} for the
 * type list.</p>
 *
 * <h3>Referenced by {@link #id}, never by name</h3>
 * <p>A node stores the id, so renaming a property cannot orphan the nodes using it — the rename is
 * invisible to them, which is the entire reason display name and identity are separate fields. Unity
 * keys on its own object id for the same reason. The two other names are not identity either:
 * {@link #name} is what a human reads and {@link #reference} is what the generated shader declares.</p>
 *
 * <h3>Values are text, like everything else in this layer</h3>
 * <p>{@link #defaultValue} and {@link #options} are strings for the same reason
 * {@link NodeData#properties()} is: it keeps the document serialisable, diffable and content-hashable
 * with no knowledge of value types, so a dedicated server can author a graph it will never render.</p>
 *
 * <h3>{@link #options} is a map because the extras differ per type</h3>
 * <p>A Float has a {@code mode} and possibly {@code min}/{@code max}; a Texture has a fallback; a Colour
 * has {@code hdr}. Modelling those as fields would mean a record with a dozen mostly-null members and a
 * schema change every time a type gains an option — and a schema change is what makes an already-saved
 * document unreadable.</p>
 *
 * @param category the group it is filed under on the Blackboard; {@code ""} for none. A plain string
 *                 rather than a category entity, the same shape {@link NodeType#category()} uses
 * @param exposed  whether a material inspector should offer it. <b>Not</b> whether it exists — an
 *                 unexposed property is still a uniform the shader declares and the graph can read
 */
public record GraphProperty(String id, String name, String reference, String typeId,
                            String defaultValue, boolean exposed, String category,
                            Map<String, String> options) {

    /** Where an option key lives. @see #options */
    public static final String OPTION_MODE = "mode";
    public static final String OPTION_MIN = "min";
    public static final String OPTION_MAX = "max";

    public GraphProperty {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("A property needs an id");
        if (typeId == null || typeId.isEmpty()) {
            throw new IllegalArgumentException("Property '" + id + "' has no type");
        }
        if (name == null || name.isEmpty()) name = "Property";
        reference = sanitiseReference(reference == null || reference.isEmpty()
                ? referenceFor(name) : reference);
        if (defaultValue == null) defaultValue = "";
        if (category == null) category = "";
        options = options == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /** A new property of {@code typeId}, with a generated id and a reference derived from the name. */
    public static GraphProperty of(String name, String typeId, String defaultValue) {
        return new GraphProperty(GraphIds.generate(), name, referenceFor(name), typeId, defaultValue,
                true, "", Map.of());
    }

    // ── Naming ──────────────────────────────────────────────────────────────

    /**
     * The shader-side name a display name implies — {@code Vec prop} → {@code _Vec_prop}.
     *
     * <p>Unity's rule, and worth copying rather than inventing: the leading underscore is the ShaderLab
     * convention for a material property, and it is what makes a generated name impossible to confuse
     * with a local variable in the emitted GLSL.</p>
     */
    public static String referenceFor(String displayName) {
        return sanitiseReference("_" + (displayName == null ? "" : displayName));
    }

    /**
     * {@code raw} made legal as a shader identifier.
     *
     * <p>Ported from Unity's stated behaviour: a name that does not begin with an underscore gets one,
     * and any character the shading language rejects becomes an underscore. Done here rather than at the
     * point of use so a document can never hold a reference that will not compile — the alternative is
     * discovering it at emit time, by which point the offending keystroke is long past.</p>
     *
     * <p>A digit immediately after the underscore is also illegal, so it gains one more.</p>
     */
    public static String sanitiseReference(@Nullable String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return "_Property";

        StringBuilder out = new StringBuilder(text.length() + 1);
        if (text.charAt(0) != '_') out.append('_');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        // "_1" is a legal identifier; "1" is not, and neither is a bare "_" followed by nothing.
        if (out.length() == 1) out.append("Property");
        return out.toString();
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Nullable
    public String option(String key) {
        return options.get(key);
    }

    public String option(String key, String fallback) {
        String held = options.get(key);
        return held == null ? fallback : held;
    }

    public boolean isCategorised() {
        return !category.isEmpty();
    }

    /** What the Blackboard's right-hand column shows — {@code Vector2}, {@code Texture2D}. */
    public String displayType() {
        return typeId;
    }

    // ── Writing, non-destructively ──────────────────────────────────────────

    /**
     * The same property under a new display name.
     *
     * <p>The reference is <b>left alone</b>, which is deliberate and is Unity's behaviour: once a
     * reference exists, material assets and C# scripts point at it, so silently rewriting it on a rename
     * would break every one of them. A caller wanting both does both.</p>
     */
    public GraphProperty withName(String value) {
        return new GraphProperty(id, value, reference, typeId, defaultValue, exposed, category, options);
    }

    public GraphProperty withReference(String value) {
        return new GraphProperty(id, name, value, typeId, defaultValue, exposed, category, options);
    }

    public GraphProperty withType(String value, String newDefault) {
        return new GraphProperty(id, name, reference, value, newDefault, exposed, category, options);
    }

    public GraphProperty withDefaultValue(String value) {
        return new GraphProperty(id, name, reference, typeId, value, exposed, category, options);
    }

    public GraphProperty withExposed(boolean value) {
        return new GraphProperty(id, name, reference, typeId, defaultValue, value, category, options);
    }

    public GraphProperty withCategory(String value) {
        return new GraphProperty(id, name, reference, typeId, defaultValue, exposed, value, options);
    }

    /** One option set, or removed when {@code value} is null. */
    public GraphProperty withOption(String key, @Nullable String value) {
        Map<String, String> next = new LinkedHashMap<>(options);
        if (value == null) next.remove(key); else next.put(key, value);
        return new GraphProperty(id, name, reference, typeId, defaultValue, exposed, category, next);
    }

    @Override
    public String toString() {
        return "GraphProperty[" + name + " : " + typeId + " -> " + reference + "]";
    }

    /** Lower-cased name, for a case-insensitive uniqueness check. */
    public String nameKey() {
        return name.toLowerCase(Locale.ROOT);
    }
}
