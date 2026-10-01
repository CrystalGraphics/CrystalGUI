package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The cascade's answer for one element, frozen: every property has a value, and the value does not
 * move under a reader.
 *
 * <p>{@link ElementStyle#getComputed} answers {@code null} for a property nothing wrote, because the
 * initial value is not a candidate (audit §5 S6, row 84) — so an element nobody touched reports
 * neither {@code FLEX} nor {@code NONE}, and a reader that forgets the fallback is a null pointer in
 * the frame loop (the {@code OVERFLOW} listener row). Here a property that nothing wrote answers its
 * initial, an inheritable one that nothing wrote answers what its parent computed, and the map is
 * taken once and never mutated: what the box tree and the paint pass read is an immutable output of
 * the cascade, as Blink's {@code ComputedStyle} is, not a live view of a store that a transition tick
 * may be writing.</p>
 *
 * <p>Built on demand by {@link ElementStyle#computed()} and cached until the store changes, so the
 * cost is one map per element per change rather than per read.</p>
 */
public final class ComputedStyle {

    private static final ComputedStyle INITIAL = new ComputedStyle(Collections.emptyMap());

    private final Map<StyleProperty<?>, Object> values;

    private ComputedStyle(Map<StyleProperty<?>, Object> values) {
        this.values = values;
    }

    /** A style with every property at its initial — what a detached, unstyled node computes. */
    public static ComputedStyle initial() {
        return INITIAL;
    }

    /**
     * Snapshots {@code style}: computed slots first, then inheritance for what has none. Walks what the element has
     * candidates for and what it can inherit, not every registered property: the same answer, built once per element
     * per change, and opening a large panel builds thousands.
     */
    static ComputedStyle of(ElementStyle style, @Nullable ComputedStyle inheritFrom) {
        Map<StyleProperty<?>, Object> out = new HashMap<>();
        Set<StyleProperty<?>> registered = registered();
        for (StyleProperty<?> property : style.candidates.keySet()) {
            if (!registered.contains(property)) continue;
            Object value = style.computeCandidate(property);
            if (value != null) out.put(property, value);
        }
        if (inheritFrom != null) {
            for (StyleProperty<?> property : inheritable()) {
                if (out.containsKey(property)) continue;
                Object value = inheritFrom.values.get(property);
                if (value != null) out.put(property, value);
            }
        }
        return new ComputedStyle(Collections.unmodifiableMap(out));
    }

    /**
     * Whether a child computed under {@code a} computes the same under {@code b}: every inheritable property
     * equal. What lets a parent's restyle that touched nothing inherited leave its whole subtree's styles, and
     * so their boxes and layout, alone.
     */
    static boolean sameInherited(@Nullable ComputedStyle a, @Nullable ComputedStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        for (StyleProperty<?> property : inheritable()) {
            if (!Objects.equals(a.values.get(property), b.values.get(property))) return false;
        }
        return true;
    }

    @Nullable
    private static volatile List<StyleProperty<?>> inheritable;
    @Nullable
    private static volatile Set<StyleProperty<?>> registered;

    /** Every registered property, by identity. Read after registration, like {@link #inheritable()}. */
    private static Set<StyleProperty<?>> registered() {
        Set<StyleProperty<?>> known = registered;
        if (known == null) {
            Set<StyleProperty<?>> found = Collections.newSetFromMap(new IdentityHashMap<>());
            found.addAll(StylePropertyRegistry.all());
            registered = known = Collections.unmodifiableSet(found);
        }
        return known;
    }

    /** Read after registration, which is done by the time anything computes. */
    private static List<StyleProperty<?>> inheritable() {
        List<StyleProperty<?>> known = inheritable;
        if (known == null) {
            List<StyleProperty<?>> found = new ArrayList<>();
            for (StyleProperty<?> property : StylePropertyRegistry.all()) {
                if (property.isInheritable()) found.add(property);
            }
            inheritable = known = List.copyOf(found);
        }
        return known;
    }

    /** The value — never null for a property whose initial is not. */
    @SuppressWarnings("unchecked")
    public <T> T get(StyleProperty<T> property) {
        Object value = values.get(property);
        return value != null ? (T) value : property.initialValue;
    }

    /** Whether something (a rule, an inline write, inheritance) decided this, as opposed to the initial. */
    public boolean isSet(StyleProperty<?> property) {
        return values.containsKey(property);
    }

    public Set<StyleProperty<?>> setProperties() {
        return values.keySet();
    }

    @Override
    public String toString() {
        return "ComputedStyle" + values.keySet();
    }
}
