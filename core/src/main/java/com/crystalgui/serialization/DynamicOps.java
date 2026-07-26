package com.crystalgui.serialization;

import java.util.List;
import java.util.Map;

/**
 * Abstracts "how do I build/read a string, number, boolean, list, or map in wire format {@code T}"
 * — the other half of the {@link Codec}/{@code DynamicOps} split that makes {@link Codec}
 * definitions format-agnostic. A {@link Codec} never touches a concrete tree type directly; it only
 * ever calls through a {@code DynamicOps<T>}, so the same {@link Codec} works against JSON, NBT,
 * XML, or any other format just by supplying a different {@code DynamicOps<T>} implementation.
 *
 * <p>{@link JsonOps} (backed by Gson's {@code JsonElement}) is the one concrete implementation
 * shipped in {@code core/}. A future NBT-backed {@code DynamicOps<CompoundTag>} would live in a
 * loader module instead (real NBT types aren't importable from {@code core/} — the loader-blind
 * import guard blocks {@code net.minecraft.*} generally, which covers {@code net.minecraft.nbt}
 * too) — it would reuse every {@link Codec} defined here unchanged, since {@link Codec}s carry no
 * format-specific code at all.</p>
 *
 * @param <T> the native value type of this format (e.g. {@code JsonElement} for JSON)
 */
public interface DynamicOps<T> {

    /** The format's representation of "nothing"/absent (e.g. {@code JsonNull} for JSON). */
    T empty();

    T createString(String value);

    T createNumber(Number value);

    T createBoolean(boolean value);

    T createList(List<T> values);

    /** Both keys and values are {@code T} — a format's own map keys are themselves just format
     * values (e.g. JSON object keys are strings, which are already representable as {@code T} via
     * {@link #createString}). */
    T createMap(Map<T, T> entries);

    /** Throws {@link CodecException} if {@code value} isn't a string in this format. */
    String getStringValue(T value);

    /** Throws {@link CodecException} if {@code value} isn't a number in this format. */
    Number getNumberValue(T value);

    /** Throws {@link CodecException} if {@code value} isn't a boolean in this format. */
    boolean getBooleanValue(T value);

    /** Throws {@link CodecException} if {@code value} isn't a list in this format. */
    List<T> getListValue(T value);

    /** Throws {@link CodecException} if {@code value} isn't a map in this format. */
    Map<T, T> getMapValue(T value);
}
