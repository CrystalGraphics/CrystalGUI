package com.crystalgui.ui.contract;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * The value types a {@link State} slot usually carries. {@code plan/engine-rewrite.md} M1.
 *
 * <p>Each is a codec ({@link StateType#of}); a widget with a value of its own declares a state type over that value's
 * codec. Widget state has to survive a content hash, so every encoding here is pinned: see
 * {@code docs/CGUI_SERVER_AND_SERIALIZATION.md} on why a description must be byte-identical for the same tree.</p>
 */
public final class StateTypes {

    private StateTypes() {
    }

    public static final StateType<String> STRING = StateType.of(CgCodecs.STRING, String.class).nullAs("");

    public static final StateType<Integer> INT = StateType.of(CgCodecs.INT, Integer.class);

    public static final StateType<Float> FLOAT = StateType.of(CgCodecs.FLOAT, Float.class);

    public static final StateType<Double> DOUBLE = StateType.of(CgCodecs.DOUBLE, Double.class);

    public static final StateType<Boolean> BOOL = StateType.of(CgCodecs.BOOL, Boolean.class);

    /** An enum, by constant name — so adding a constant is compatible and reordering one is not. */
    public static <E extends Enum<E>> StateType<E> enumOf(Class<E> type) {
        return StateType.of(CgCodecs.enumOf(type), type);
    }

    /**
     * A list of strings, each written as a one-key entry; an empty list reads as the slot's fallback.
     *
     * <p>{@code entryKey} is part of the wire format and not cosmetic: {@code Dropdown} has always
     * written its options as {@code [{label: …}]} rather than a bare string array, and changing that
     * would change the content hash of every description holding one.</p>
     */
    public static StateType<List<String>> stringListUnder(String entryKey) {
        return StateType.of(entries(entryKey, CgCodecs.STRING, ""), List.class)
                .nullAs(List.of()).absentWhen(List::isEmpty);
    }

    /**
     * A {@code float[]}, each element written as a one-key entry.
     *
     * <p>An array rather than a {@code List<Float>} because that is what {@code SplitView.getWeights}
     * and {@code setWeights} speak, and a contract that made every widget box its own primitives to
     * satisfy the contract would be the contract serving itself.</p>
     */
    public static StateType<float[]> floatArrayUnder(String entryKey) {
        return StateType.of(CgCodecs.xmap(entries(entryKey, CgCodecs.FLOAT, 0f), list -> {
            float[] out = new float[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = list.get(i);
            return out;
        }, array -> {
            List<Float> boxed = new ArrayList<>(array.length);
            for (float item : array) boxed.add(item);
            return boxed;
        }), float[].class).nullAs(new float[0]).absentWhen(array -> array.length == 0);
    }

    /** A {@code double[]}, for the vector and matrix controls. */
    public static StateType<double[]> doubleArrayUnder(String entryKey) {
        return StateType.of(CgCodecs.xmap(entries(entryKey, CgCodecs.DOUBLE, 0d), list -> {
            double[] out = new double[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = list.get(i);
            return out;
        }, array -> {
            List<Double> boxed = new ArrayList<>(array.length);
            for (double item : array) boxed.add(item);
            return boxed;
        }), double[].class).nullAs(new double[0]).absentWhen(array -> array.length == 0);
    }

    /** An {@code int[]}, for a per-entry ARGB — a palette, which no sheet can enumerate. */
    public static StateType<int[]> intArrayUnder(String entryKey) {
        return StateType.of(CgCodecs.xmap(entries(entryKey, CgCodecs.INT, 0), list -> {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = list.get(i);
            return out;
        }, array -> {
            List<Integer> boxed = new ArrayList<>(array.length);
            for (int item : array) boxed.add(item);
            return boxed;
        }), int[].class).nullAs(new int[0]).absentWhen(array -> array.length == 0);
    }

    /** A list written as {@code [{entryKey: value}, ...]}; a null element as {@code whenAbsent}, a keyless entry read as it. */
    private static <E> CgCodec<List<E>> entries(String entryKey, CgCodec<E> codec, E whenAbsent) {
        return CgCodecs.listOf(CgCodecs.record(
                CgCodecs.fieldOr(entryKey, codec, (E e) -> e == null ? whenAbsent : e, whenAbsent), (E e) -> e));
    }
}
