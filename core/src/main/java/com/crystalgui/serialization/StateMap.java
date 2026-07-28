package com.crystalgui.serialization;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A widget's own serializable state, as a small string-keyed bag.
 *
 * <p>Exists so {@code UIElement.writeState}/{@code readState} implementations never see a
 * {@link DynamicOps}. A widget author writes {@code out.putBool("checked", checked)}, and stays
 * unaware of whether the result becomes JSON, NBT or bytes — which is the same separation
 * {@link Codec} gives, one level up.</p>
 *
 * <p><b>Insertion-ordered.</b> Descriptions are content-addressed, so encoding the same widget twice
 * must produce identical bytes; a {@code HashMap} here would make the hash vary per JVM run.</p>
 *
 * <p>Reads take a default rather than throwing. A widget gaining a new state key must still be able
 * to read a description written before that key existed — the alternative is that every added field
 * is a breaking protocol change.</p>
 */
public final class StateMap<T> {

    private final DynamicOps<T> ops;
    private final Map<String, T> entries;

    /** An empty map, for writing. */
    public StateMap(DynamicOps<T> ops) {
        this.ops = ops;
        this.entries = new LinkedHashMap<>();
    }

    /** Wraps an already-encoded map, for reading. */
    public StateMap(DynamicOps<T> ops, T encoded) {
        this.ops = ops;
        this.entries = new LinkedHashMap<>();
        for (Map.Entry<T, T> entry : ops.getMapValue(encoded).entrySet()) {
            entries.put(ops.getStringValue(entry.getKey()), entry.getValue());
        }
    }

    // ── Write ───────────────────────────────────────────────────────────────

    public StateMap<T> putString(String key, String value) {
        entries.put(key, ops.createString(value == null ? "" : value));
        return this;
    }

    public StateMap<T> putInt(String key, int value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public StateMap<T> putFloat(String key, float value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public StateMap<T> putDouble(String key, double value) {
        entries.put(key, ops.createNumber(value));
        return this;
    }

    public StateMap<T> putBool(String key, boolean value) {
        entries.put(key, ops.createBoolean(value));
        return this;
    }

    public StateMap<T> putEnum(String key, Enum<?> value) {
        if (value == null) return this;
        entries.put(key, ops.createString(value.name()));
        return this;
    }

    /** Writes only when {@code value} differs from {@code omitWhen} — keeps a default-valued widget
     * from carrying its own defaults over the wire. */
    public StateMap<T> putStringIfNot(String key, String value, String omitWhen) {
        return java.util.Objects.equals(value, omitWhen) ? this : putString(key, value);
    }

    public StateMap<T> putBoolIfNot(String key, boolean value, boolean omitWhen) {
        return value == omitWhen ? this : putBool(key, value);
    }

    // ── Read ────────────────────────────────────────────────────────────────

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public String getString(String key, String fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getStringValue(raw);
    }

    public int getInt(String key, int fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).intValue();
    }

    public float getFloat(String key, float fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).floatValue();
    }

    public double getDouble(String key, double fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getNumberValue(raw).doubleValue();
    }

    public boolean getBool(String key, boolean fallback) {
        T raw = entries.get(key);
        return raw == null ? fallback : ops.getBooleanValue(raw);
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E fallback) {
        T raw = entries.get(key);
        if (raw == null) return fallback;
        String name = ops.getStringValue(raw);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new CodecException("No " + type.getSimpleName() + " constant named '" + name
                    + "' for state key '" + key + "'", e);
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public DynamicOps<T> ops() {
        return ops;
    }

    public T encode() {
        Map<T, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries.entrySet()) {
            out.put(ops.createString(entry.getKey()), entry.getValue());
        }
        return ops.createMap(out);
    }
}
