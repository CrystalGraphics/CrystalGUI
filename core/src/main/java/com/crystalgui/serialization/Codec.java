package com.crystalgui.serialization;

/**
 * Format-agnostic bidirectional serializer/deserializer for {@code A} — describes pure data
 * <em>shape</em> only (what fields a value has, what type each one is), with zero opinions about
 * the wire format it eventually ends up in. That's the whole point of the {@link DynamicOps}
 * parameter: encoding to JSON, NBT, XML, or anything else is a matter of supplying a different
 * {@link DynamicOps} implementation, never rewriting the {@code Codec}. Mirrors the shape (not the
 * implementation) of Mojang's {@code com.mojang.serialization.Codec} — that library isn't a
 * dependency here (unavailable, and not loader-blind-safe to pull into {@code core/} anyway), so
 * this is a small hand-rolled equivalent covering exactly what CrystalGUI needs.
 *
 * @param <A> the value type this codec encodes/decodes
 */
public interface Codec<A> {

    /** Encodes {@code input} into format {@code T} via {@code ops}. */
    <T> T encode(DynamicOps<T> ops, A input);

    /** Decodes a value of type {@code A} out of {@code input} (format {@code T}) via {@code ops}.
     * Throws {@link CodecException} on malformed/unexpected input — never returns {@code null} for
     * a failed decode. */
    <T> A decode(DynamicOps<T> ops, T input);
}
