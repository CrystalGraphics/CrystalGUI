package com.crystalgui.core.property;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <b>An observable value</b> — held here, or living somewhere else and reached through accessors.
 *
 * <p>{@link #changed} emits {@code (old, new)} whenever the value moves, per {@code Objects.deepEquals} —
 * so an array that is read fresh each time but holds the same numbers has not moved. A control bound to a
 * property edits it and follows it; see {@code ValueControl.bind}.</p>
 *
 * <h3>Stored</h3>
 *
 * <pre>{@code
 * Property<String> name = Property.of("Untitled");
 * name.changed.connect((was, now) -> title.setText(now));
 * name.set("Scene");                      // emits ("Untitled", "Scene")
 * }</pre>
 *
 * <h3>Derived — the value lives in a model you do not rewrite</h3>
 *
 * <pre>{@code
 * Property<Double> angle = Property.derived(
 *         () -> Math.toDegrees(gesture.rotation()),
 *         degrees -> gesture.setRotation((float) Math.toRadians(degrees)));
 * }</pre>
 *
 * <p>{@link #get} reads through and {@link #set} writes through, then reads back what the model kept —
 * so a model that clamps or rounds reports the value it actually holds. Nothing tells a derived property
 * that the model moved on its own, so it is <b>polled</b>: whoever displays it calls {@link #refresh},
 * which re-reads and emits if the value moved. A bound control does that every frame by itself.</p>
 *
 * <p>When the model announces its own changes, say so and nothing polls:</p>
 *
 * <pre>{@code
 * Property<Object> fontSize = Property.derived(() -> settings.get(FONT_SIZE), v -> settings.set(FONT_SIZE, v))
 *         .announcedBy(refresh -> settings.onChanged.connect(change -> refresh.run()));
 * }</pre>
 *
 * <p>Read-only, for a fact that is displayed and never typed into:</p>
 *
 * <pre>{@code
 * Property<String> size = Property.derived(() -> box.width() + " x " + box.height());
 * }</pre>
 *
 * <h3>Where its edits go</h3>
 *
 * <pre>{@code
 * Property.derived(read, write).editedIn(document.undoStack());
 * }</pre>
 *
 * <p>The writer records whatever edit the domain records; {@link #editedIn} names the history, so a bound
 * control can make a continuous gesture — a scrub, a slider drag — one step of it rather than one per
 * frame.</p>
 *
 * <h3>Another shape of the same value</h3>
 *
 * <pre>{@code
 * Property<String> stored = Property.derived(() -> node.field("scale"), v -> node.setField("scale", v));
 * Property<Double> scale = stored.map(Double::parseDouble, String::valueOf);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #set} on a read-only property throws.</li>
 *   <li>A listener that calls {@code set} on the property it is listening to is ignored — the guard that
 *       keeps {@link #bindBidirectional} from recursing.</li>
 *   <li>A derived property reads its model the first time it is asked, never at construction.</li>
 *   <li><b>Single-thread only.</b></li>
 * </ul>
 *
 * @param <T> the value type
 */
public final class Property<T> {

    /** Emits {@code (oldValue, newValue)} when the value changes. */
    public final Signal.Pair<T, T> changed = new Signal.Pair<>();

    /** Stored: the value. Derived: the value last seen, which {@link #refresh} compares against. */
    private T value;

    /** Whether a derived property has read its model yet. Always true for a stored one. */
    private boolean seen;

    private boolean updating;

    @Nullable
    private final Supplier<T> reader;

    @Nullable
    private final Consumer<T> writer;

    /** The property this one is a {@link #map} of, which it refreshes and listens to. */
    @Nullable
    private final Property<?> upstream;

    @Nullable
    private Function<Runnable, Connection> announcer;

    @Nullable
    private UndoStack history;

    public Property(T initialValue) {
        this.value = initialValue;
        this.seen = true;
        this.reader = null;
        this.writer = null;
        this.upstream = null;
    }

    private Property(Supplier<T> reader, @Nullable Consumer<T> writer, @Nullable Property<?> upstream) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = writer;
        this.upstream = upstream;
    }

    /** A value held here. */
    public static <T> Property<T> of(T initialValue) {
        return new Property<>(initialValue);
    }

    /** A value that lives in a model, read and written through these. Polled until {@link #announcedBy}. */
    public static <T> Property<T> derived(Supplier<T> read, Consumer<T> write) {
        return new Property<>(read, Objects.requireNonNull(write, "write"), null);
    }

    /** A value that lives in a model and cannot be written from here. */
    public static <T> Property<T> derived(Supplier<T> read) {
        return new Property<>(read, null, null);
    }

    /** Returns the current value — for a derived property, what the model holds right now. */
    public T get() {
        return reader == null ? value : reader.get();
    }

    /**
     * Sets the value, emitting {@link #changed} when it moved.
     *
     * @throws IllegalStateException on a {@linkplain #isReadOnly read-only} property
     */
    public void set(T newValue) {
        if (updating) return;
        T oldValue = get();
        if (Objects.deepEquals(oldValue, newValue)) return;
        if (reader != null && writer == null) {
            throw new IllegalStateException("a read-only property cannot be set");
        }
        updating = true;
        try {
            if (writer == null) {
                value = newValue;
            } else {
                writer.accept(newValue);
                // READ BACK, so a model that clamps reports what it kept rather than what it was offered.
                value = reader.get();
                seen = true;
            }
            if (!Objects.deepEquals(oldValue, value)) changed.emit(oldValue, value);
        } finally {
            updating = false;
        }
    }

    /**
     * Re-reads a derived property and emits {@link #changed} if its model moved since it was last seen.
     *
     * <pre>{@code
     * window.animation().afterLayout(owner, delta -> { angle.refresh(); return true; });
     * }</pre>
     *
     * <p>The first call only records what the model holds — there is nothing yet to have moved from. A
     * no-op on a stored property, and while this property is itself writing.</p>
     *
     * @return whether it emitted
     */
    public boolean refresh() {
        if (reader == null || updating) return false;
        if (upstream != null) upstream.refresh();
        T now = reader.get();
        if (!seen) {
            value = now;
            seen = true;
            return false;
        }
        if (Objects.deepEquals(value, now)) return false;
        T oldValue = value;
        value = now;
        updating = true;
        try {
            changed.emit(oldValue, now);
        } finally {
            updating = false;
        }
        return true;
    }

    /**
     * Declares how a derived property hears about changes made elsewhere, so nothing has to poll it.
     *
     * <pre>{@code
     * .announcedBy(refresh -> document.onChanged.connect(refresh::run))
     * }</pre>
     *
     * <p>{@code subscribe} is handed {@link #refresh} and returns the connection. It is <b>stored, not
     * called</b>: whoever displays the property connects it through {@link #watchSource} for as long as
     * it is on screen.</p>
     */
    public Property<T> announcedBy(Function<Runnable, Connection> subscribe) {
        if (reader == null) throw new IllegalStateException("a stored property announces itself");
        this.announcer = Objects.requireNonNull(subscribe, "subscribe");
        return this;
    }

    /** Names the history this property's edits are recorded in. @see Property the class note */
    public Property<T> editedIn(@Nullable UndoStack history) {
        this.history = history;
        return this;
    }

    /** The history its edits are recorded in, or null for none. */
    @Nullable
    public UndoStack history() {
        if (history == null && upstream != null) return upstream.history();
        return history;
    }

    /** Whether the value lives in a model rather than here. */
    public boolean isDerived() {
        return reader != null;
    }

    /** Whether {@link #set} is refused. */
    public boolean isReadOnly() {
        return reader != null && writer == null;
    }

    /** Whether something must call {@link #refresh} for this to notice a change it did not make. */
    public boolean isPolled() {
        if (reader == null) return false;
        if (upstream != null) return upstream.isPolled();
        return announcer == null;
    }

    /**
     * Connects whatever tells this property its model moved, calling {@link #refresh} on each change.
     *
     * <pre>{@code
     * control.whileConnected(property::watchSource);
     * }</pre>
     *
     * @return the connection, or {@link Connection#DISCONNECTED} when there is nothing to listen to — a
     *         stored property, or a derived one that is {@linkplain #isPolled polled}
     */
    public Connection watchSource() {
        if (upstream != null) {
            Connection above = upstream.changed.connect((was, now) -> refresh());
            Connection source = upstream.watchSource();
            return () -> {
                above.disconnect();
                source.disconnect();
            };
        }
        return announcer == null ? Connection.DISCONNECTED : announcer.apply(this::refresh);
    }

    /**
     * The same value in another shape — a document's text as the number a field edits.
     *
     * <pre>{@code
     * Property<Double> scale = stored.map(Double::parseDouble, String::valueOf);
     * }</pre>
     *
     * <p>Refreshing, polling, listening and history all pass through to this property.</p>
     */
    public <U> Property<U> map(Function<T, U> to, Function<U, T> from) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(from, "from");
        return new Property<>(() -> to.apply(get()), next -> set(from.apply(next)), this);
    }

    /** As {@link #map(Function, Function)}, read-only. */
    public <U> Property<U> map(Function<T, U> to) {
        Objects.requireNonNull(to, "to");
        return new Property<>(() -> to.apply(get()), null, this);
    }

    /**
     * One-way binding: this property mirrors the source.
     * The current value is set immediately to the source's current value.
     *
     * @param source the source property to follow
     * @return a connection that can be disconnected to stop following
     */
    public Connection bindTo(final Property<T> source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        set(source.get());
        return source.changed.connect((oldVal, newVal) -> set(newVal));
    }

    /**
     * One-way binding with a transform: this property mirrors {@code source}, but through
     * {@code transform} rather than a straight same-type copy. The current value is set immediately to
     * {@code transform.apply(source.get())}.
     *
     * @param source    the source property to follow
     * @param transform maps the source's value type to this property's value type
     * @return a connection that can be disconnected to stop following
     */
    public <S> Connection bindTo(final Property<S> source, final Function<S, T> transform) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (transform == null) throw new IllegalArgumentException("transform must not be null");
        set(transform.apply(source.get()));
        return source.changed.connect((oldVal, newVal) -> set(transform.apply(newVal)));
    }

    /**
     * Bidirectional binding: both properties stay in sync.
     * This property is immediately set to the other's current value.
     *
     * <p>The reentrancy guard in {@link #set} prevents infinite recursion. A derived property on either
     * side emits only when set or {@linkplain #refresh refreshed}, so a polled one needs polling for the
     * other to hear its model move.</p>
     *
     * @param other the other property
     * @return a connection that disconnects both directions when called
     */
    public Connection bindBidirectional(final Property<T> other) {
        if (other == null) throw new IllegalArgumentException("other must not be null");
        set(other.get());

        final Connection forward = other.changed.connect((oldVal, newVal) -> set(newVal));
        final Connection reverse = changed.connect((oldVal, newVal) -> other.set(newVal));

        return new Connection() {
            private boolean connected = true;

            @Override
            public void disconnect() {
                if (!connected) return;
                connected = false;
                forward.disconnect();
                reverse.disconnect();
            }

            @Override
            public boolean isConnected() {
                return connected;
            }
        };
    }
}
