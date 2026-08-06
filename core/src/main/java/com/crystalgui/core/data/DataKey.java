package com.crystalgui.core.data;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A typed, named question that can be asked of an element's surroundings — IntelliJ's {@code DataKey}.
 *
 * <h3>Why a key rather than a method</h3>
 *
 * <p>A command needs to know what it is acting on. The obvious answer is for the command to reach for
 * the widget it expects, and that answer does not scale: {@code Delete} works in the file tree, the
 * node graph and the text editor, and none of those three should appear in its source. With keys, the
 * command asks for a <em>subject</em> and each widget answers with whatever it has — so a command
 * written today keeps working in a widget written next year.</p>
 *
 * <p>This codebase already had the pattern three times, hand-rolled and type-specific:
 * {@code GraphCommands.graphFor}, {@code ShaderGraphEditor.editorFor} and {@code UndoScope.nearest}
 * are the same walk with a different {@code instanceof}. A key is that walk with the type pulled out.</p>
 *
 * <h3>Interned by name</h3>
 *
 * <p>{@link #create} returns the same instance for the same name, so keys compare by identity and a key
 * declared in two places cannot become two different questions. Re-declaring with a different type is
 * refused rather than silently accepted — a provider answering the wrong type for a key is a
 * {@code ClassCastException} at some unrelated call site, which is the least useful place to find out.</p>
 *
 * @param <T> what an answer to this question is
 */
public final class DataKey<T> {

    private static final Map<String, DataKey<?>> INTERNED = new ConcurrentHashMap<>();

    private final String name;
    private final Class<T> type;

    private DataKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * The key called {@code name}, creating it on first use.
     *
     * @throws IllegalArgumentException if this name was already declared with a different type
     */
    @SuppressWarnings("unchecked")
    public static <T> DataKey<T> create(String name, Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        DataKey<?> existing = INTERNED.computeIfAbsent(name, key -> new DataKey<>(key, type));
        if (existing.type != type) {
            throw new IllegalArgumentException("data key '" + name + "' is already declared as "
                    + existing.type.getName() + ", not " + type.getName());
        }
        return (DataKey<T>) existing;
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /**
     * Narrows a provider's raw answer, or null when it answered with the wrong thing.
     *
     * <p>Wrong-typed answers are dropped rather than thrown on, so one bad provider cannot break a
     * command that would have found a good answer further out. The cast is here, once, rather than at
     * every call site.</p>
     */
    @SuppressWarnings("unchecked")
    public T cast(Object value) {
        return type.isInstance(value) ? (T) value : null;
    }

    @Override
    public String toString() {
        return "DataKey(" + name + ")";
    }
}
