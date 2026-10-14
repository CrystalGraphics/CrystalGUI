package com.crystalgui.ui.contract;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgStateMap;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * How one kind of value crosses a {@link CgStateMap}, for a {@link State} slot: a {@link CgCodec} and the value's class.
 * {@link StateTypes} holds the common ones; any codec is a state type.
 *
 * <pre>{@code
 * State.of("count", StateTypes.INT, Counter::count, Counter::setCount, 0);
 * StateType<Range> RANGE = StateType.of(Range.CODEC, Range.class);           // a record's codec, as a slot
 * StateType<List<String>> TAGS = StateType.of(CgCodecs.listOf(CgCodecs.STRING), List.class)
 *         .nullAs(List.of()).absentWhen(List::isEmpty);                       // empty reads as the fallback
 * }</pre>
 *
 * <ul>
 *   <li>An absent key reads the slot's fallback.</li>
 *   <li>A null value writes nothing, unless {@link #nullAs} names what it is written as.</li>
 *   <li>{@link #valueClass} is what a generic form picks a control from: a {@code Boolean} is a checkbox, an enum a
 *       dropdown; null gets a read-only row.</li>
 * </ul>
 *
 * @param <V> the Java type of the value
 */
public final class StateType<V> {

    private final CgCodec<V> codec;
    @Nullable
    private final Class<?> valueClass;
    @Nullable
    private final V whenNull;
    @Nullable
    private final Predicate<? super V> readsAsAbsent;

    private StateType(CgCodec<V> codec, @Nullable Class<?> valueClass, @Nullable V whenNull,
                      @Nullable Predicate<? super V> readsAsAbsent) {
        this.codec = codec;
        this.valueClass = valueClass;
        this.whenNull = whenNull;
        this.readsAsAbsent = readsAsAbsent;
    }

    public static <V> StateType<V> of(CgCodec<V> codec, @Nullable Class<?> valueClass) {
        return new StateType<>(codec, valueClass, null, null);
    }

    /** This type, writing a null value as {@code value}, and reading an absent key with a null fallback as it too. */
    public StateType<V> nullAs(V value) {
        return new StateType<>(codec, valueClass, value, readsAsAbsent);
    }

    /** This type, reading a value that passes {@code test} as absent: an empty list gives the slot's fallback. */
    public StateType<V> absentWhen(Predicate<? super V> test) {
        return new StateType<>(codec, valueClass, whenNull, test);
    }

    /** Writes {@code value} under {@code key}. */
    public <T> void put(CgStateMap<T> out, String key, @Nullable V value) {
        V written = value != null ? value : whenNull;
        if (written != null) out.putRaw(key, codec.encode(out.ops(), written));
    }

    /** Reads back what {@link #put} wrote, or {@code fallback} when the key is absent. */
    public <T> V get(CgStateMap<T> in, String key, @Nullable V fallback) {
        T raw = in.getRaw(key);
        if (raw == null) return fallback != null ? fallback : whenNull;
        V read = codec.decode(in.ops(), raw);
        return readsAsAbsent != null && readsAsAbsent.test(read) ? fallback : read;
    }

    public CgCodec<V> codec() {
        return codec;
    }

    @Nullable
    public Class<?> valueClass() {
        return valueClass;
    }
}
