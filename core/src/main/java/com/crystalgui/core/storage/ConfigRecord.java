package com.crystalgui.core.storage;

import java.util.Objects;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.JsonOps;

/**
 * One typed record kept as a JSON file in a {@link ConfigStorage} — what a user chose and would miss, read once
 * and rewritten whole on each change.
 *
 * <pre>{@code
 * record Shelf(boolean rows, List<String> pinned) { static final Codec<Shelf> CODEC = ...; }
 *
 * ConfigRecord<Shelf> shelf = ConfigRecord.in(workbench.extensionStore("mymod:shelf"), "shelf.json",
 *         Shelf.CODEC, new Shelf(false, List.of()));
 * shelf.onChanged.connect(this::redraw);
 * shelf.update(s -> new Shelf(!s.rows(), s.pinned()));   // written, then announced
 * }</pre>
 *
 * <ul>
 *   <li><b>The value is immutable</b> — a record — so {@link #update} hands back a new one and a change is a
 *       comparison by {@code equals}: setting an equal value writes nothing and announces nothing.</li>
 *   <li><b>A file that cannot be read yields the default</b> and is left alone until the next change, so a file
 *       somebody is halfway through editing by hand is not destroyed by having been read.</li>
 *   <li><b>With no store</b> — a test, a host with nowhere private — the record lives for the session.</li>
 *   <li>Written pretty-printed, since this is a file somebody may open. Atomic when the store is
 *       {@link LocalConfigStorage}.</li>
 * </ul>
 */
public final class ConfigRecord<T> {

    /** After the value changed, with the new value. */
    public final Signal.Value<T> onChanged = new Signal.Value<>();

    private final ConfigStorage store;
    private final String file;
    private final Codec<T> codec;
    private T value;

    private ConfigRecord(ConfigStorage store, String file, Codec<T> codec, T empty) {
        this.store = store;
        this.file = file;
        this.codec = codec;
        this.value = read(empty);
    }

    /**
     * The record in {@code file} of {@code store}, or {@code empty} when there is none or it cannot be read.
     *
     * @param store where it is kept, or null for the session alone
     */
    public static <T> ConfigRecord<T> in(@Nullable ConfigStorage store, String file, Codec<T> codec, T empty) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(empty, "empty");
        return new ConfigRecord<>(store == null ? new InMemoryConfigStorage() : store, file, codec, empty);
    }

    public T get() {
        return value;
    }

    /** Keeps {@code next}: written and announced, unless it equals what is kept. */
    public void set(T next) {
        Objects.requireNonNull(next, "value");
        if (next.equals(value)) return;
        value = next;
        store.write(file, new GsonBuilder().setPrettyPrinting().create()
                .toJson(codec.encode(JsonOps.INSTANCE, next)) + "\n");
        onChanged.emit(next);
    }

    /** {@link #set} with what {@code change} makes of the current value. */
    public void update(UnaryOperator<T> change) {
        set(change.apply(value));
    }

    private T read(T empty) {
        String json = store.read(file);
        if (json == null || json.trim().isEmpty()) return empty;
        try {
            // INSTANCE parse, not the static parseString: 1.7.10 ships a gson without it.
            T decoded = codec.decode(JsonOps.INSTANCE, new JsonParser().parse(json));
            return decoded == null ? empty : decoded;
        } catch (RuntimeException unreadable) {
            CrystalGuiCore.LOGGER.warn("[cgui] {} could not be read; continuing with the defaults until it changes",
                    file, unreadable);
            return empty;
        }
    }
}
