package com.crystalgui.ui.dom;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * A typed attribute key — {@code Attribute.ENABLED}, {@code Attribute.INERT} — with the value a node
 * holds when nothing has set it.
 *
 * <p>The old node carried each of these as a field with a setter, and the audit's census (§1) lists
 * what that grew into: focus policy, hit-test, inert, popover invoker, keymap, settings, scroll
 * exemption, user-sized axes, resize mode. A typed key is one map, one {@code set}, one observer
 * signal, and one place for a lookup through the tree the way {@code DataContext} already walks —
 * which is what retires the keymap and settings fields (plan_m5.md D5.4).</p>
 *
 * <p>Every key registers itself by name so the codec can carry a value it has never seen the key of:
 * {@link #named(String)} finds it and {@link #parse(String)} reads it back by type. A value that is not
 * one of the four carried types (boolean, integer, float, string) or an enum stays local to the
 * process, which is the right default for a key nobody has thought about the wire for.</p>
 *
 * @param <T> the value type
 */
public final class Attribute<T> {

    private static final Map<String, Attribute<?>> BY_NAME = new ConcurrentHashMap<>();

    /** Whether the node responds to input at all; {@code :disabled} when false. */
    public static final Attribute<Boolean> ENABLED = of("enabled", Boolean.class, true);
    /** The HTML {@code inert} attribute: the subtree keeps its box and stops being interactive. */
    public static final Attribute<Boolean> INERT = of("inert", Boolean.class, false);
    /** Whether hit-testing may land on this subtree; {@code pointer-events: none} when false. */
    public static final Attribute<Boolean> HIT_TEST = of("hit-test", Boolean.class, true);
    /** The name of the slot a light child asks to be placed in; empty for the default slot. */
    public static final Attribute<String> SLOT = of("slot", String.class, "");

    private final String name;
    private final Class<T> type;
    private final T initial;

    private Attribute(String name, Class<T> type, T initial) {
        this.name = name;
        this.type = type;
        this.initial = initial;
    }

    /**
     * Declares a key. The name must be unique across the process; a second declaration with the
     * same name is refused rather than silently shadowing the first.
     */
    public static <T> Attribute<T> of(String name, Class<T> type, T initial) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Attribute<T> attribute = new Attribute<>(name, type, initial);
        Attribute<?> existing = BY_NAME.putIfAbsent(name, attribute);
        if (existing != null) {
            throw new IllegalStateException("An attribute named '" + name + "' is already declared as "
                    + existing.type.getName());
        }
        return attribute;
    }

    /** The key declared under {@code name}, or {@code null} — how the codec finds one it is handed. */
    @Nullable
    public static Attribute<?> named(String name) {
        return BY_NAME.get(name);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /** What a node holds when nothing has set this. */
    public T initial() {
        return initial;
    }

    /** Whether values of this type can be written as text and read back — the wire's question. */
    public boolean isCarried() {
        return type == Boolean.class || type == Integer.class || type == Float.class
                || type == String.class || type.isEnum();
    }

    /** The text form of a value, for a key that {@link #isCarried()}. */
    public String write(T value) {
        return type.isEnum() ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    /** Reads {@link #write}'s output back. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public T parse(String text) {
        if (type == Boolean.class) return type.cast(Boolean.parseBoolean(text));
        if (type == Integer.class) return type.cast(Integer.parseInt(text));
        if (type == Float.class) return type.cast(Float.parseFloat(text));
        if (type == String.class) return type.cast(text);
        if (type.isEnum()) return (T) Enum.valueOf((Class<? extends Enum>) type, text);
        throw new IllegalArgumentException("Attribute '" + name + "' of type " + type.getName()
                + " is not carried on the wire");
    }

    @Override
    public String toString() {
        return name;
    }
}
