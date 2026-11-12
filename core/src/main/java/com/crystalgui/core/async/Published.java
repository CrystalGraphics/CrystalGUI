package com.crystalgui.core.async;

import com.crystalgui.core.signal.Connection;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * State that many sequences read and one owner changes, held as immutable generations.
 *
 * <pre>{@code
 * // The owner
 * static final Published<Theme> THEME = Published.of("theme", Theme.DEFAULT);
 * THEME.update(t -> t.withAccent(0xFF3366FF));
 *
 * // Anyone, any thread: lock-free, never half a generation
 * Theme now = THEME.current();
 *
 * // A document: told when a new generation exists, on its own sequence
 * THEME.changes().subscribe(generation -> document.invalidateStyle());
 *
 * // A frame reads one generation of everything it touches
 * try (Published.Pin ignored = Published.pin()) {
 *     layout(); paint();
 * }
 * }</pre>
 *
 * <p>The value must be immutable: a reader holds it as long as it likes, and an update builds the next one beside
 * it. {@link #update} applies on the calling thread under the owner's lock, so a registration followed by a lookup
 * in the same call sees it, and two updates never lose one another.</p>
 *
 * <p>Easy to get wrong: a value that is mutated after it is published is a data race the type cannot see, and a
 * {@link #pin()} left open past its frame hides every later update from that thread.</p>
 */
public final class Published<T> {

    /** One state of the published value, numbered from 0 for the initial one. */
    public record Generation<T>(long number, T value) {
    }

    private final String name;
    private final Broadcast<Generation<T>> changes;
    private volatile Generation<T> current;

    private Published(String name, T initial) {
        this.name = name;
        this.changes = Broadcast.create(name);
        this.current = new Generation<>(0, initial);
    }

    public static <T> Published<T> of(String name, T initial) {
        Objects.requireNonNull(name, "name");
        return new Published<>(name, Objects.requireNonNull(initial, "initial"));
    }

    public String name() {
        return name;
    }

    /** The value this thread should read: the one pinned by an open {@link #pin()}, else the latest. */
    public T current() {
        return generation().value;
    }

    /** The generation this thread should read, as {@link #current()}. */
    public Generation<T> generation() {
        Pins pins = PINS.get();
        if (pins.depth == 0) return current;
        return pins.pinned(this);
    }

    /** The latest generation, whatever this thread has pinned. For the owner. */
    public Generation<T> latest() {
        return current;
    }

    /**
     * Builds the next generation from the latest and publishes it, telling {@link #changes()}. Returning the same
     * instance changes nothing.
     */
    public T update(UnaryOperator<T> change) {
        Generation<T> next;
        synchronized (this) {
            Generation<T> was = current;
            T value = Objects.requireNonNull(change.apply(was.value), "the next generation of " + name);
            if (value == was.value) return value;
            next = new Generation<>(was.number + 1, value);
            current = next;
        }
        // A thread reads what it wrote: a widget class that registers its contract while a frame builds it must
        // find it in that same frame. Other threads' updates stay hidden until the pin closes.
        PINS.get().repin(this, next);
        changes.post(next);
        return next.value;
    }

    /** Told, on each subscriber's sequence, of every new generation. */
    public Broadcast<Generation<T>> changes() {
        return changes;
    }

    /** Subscribes {@code listener} to the new values only. */
    public Connection onChange(Consumer<? super T> listener) {
        return changes.subscribe(generation -> listener.accept(generation.value));
    }

    @Override
    public String toString() {
        return "Published(" + name + " #" + current.number + ")";
    }

    // ── Pinning ─────────────────────────────────────────────────────────────────────────────────

    private static final ThreadLocal<Pins> PINS = ThreadLocal.withInitial(Pins::new);

    /**
     * Opens a scope in which this thread reads each published state at one generation: the first one it reads
     * inside the scope. A document's frame opens one, so layout, paint and every hook of a frame agree.
     *
     * <p>Nestable, and allocation-free: close it with try-with-resources.</p>
     */
    public static Pin pin() {
        Pins pins = PINS.get();
        pins.depth++;
        return pins;
    }

    /** An open pin scope. */
    public interface Pin extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Pins implements Pin {
        int depth;
        int count;
        Published<?>[] keys = new Published<?>[8];
        Generation<?>[] values = new Generation<?>[8];

        @SuppressWarnings("unchecked")
        <T> Generation<T> pinned(Published<T> key) {
            for (int i = 0; i < count; i++) {
                if (keys[i] == key) return (Generation<T>) values[i];
            }
            if (count == keys.length) {
                keys = Arrays.copyOf(keys, count * 2);
                values = Arrays.copyOf(values, count * 2);
            }
            Generation<T> now = key.current;
            keys[count] = key;
            values[count++] = now;
            return now;
        }

        void repin(Published<?> key, Generation<?> now) {
            for (int i = 0; i < count; i++) {
                if (keys[i] == key) {
                    values[i] = now;
                    return;
                }
            }
        }

        @Override
        public void close() {
            if (--depth > 0) return;
            for (int i = 0; i < count; i++) {
                keys[i] = null;
                values[i] = null;
            }
            count = 0;
        }
    }
}
