package com.crystalgui.ui.contract;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.serialization.StateMap;

/**
 * The value types a {@link State} slot can carry. {@code plan_ui_rewrite.md} M1.
 *
 * <p>Deliberately a closed, small set. Every one of these is something {@link StateMap} already knows
 * how to encode, because widget state has to survive a content hash — see
 * {@code docs/CGUI_SERVER_AND_SERIALIZATION.md} on why a description must be byte-identical for the
 * same tree. A widget wanting something exotic is a widget whose state is not really state.</p>
 */
public final class StateTypes {

    private StateTypes() {
    }

    public static final StateType<String> STRING = new StateType<String>() {
        @Override public <T> void put(StateMap<T> out, String key, String value) {
            out.putString(key, value == null ? "" : value);
        }
        @Override public <T> String get(StateMap<T> in, String key, String fallback) {
            return in.getString(key, fallback == null ? "" : fallback);
        }
    };

    public static final StateType<Integer> INT = new StateType<Integer>() {
        @Override public <T> void put(StateMap<T> out, String key, Integer value) {
            out.putInt(key, value);
        }
        @Override public <T> Integer get(StateMap<T> in, String key, Integer fallback) {
            return in.getInt(key, fallback);
        }
    };

    public static final StateType<Float> FLOAT = new StateType<Float>() {
        @Override public <T> void put(StateMap<T> out, String key, Float value) {
            out.putFloat(key, value);
        }
        @Override public <T> Float get(StateMap<T> in, String key, Float fallback) {
            return in.getFloat(key, fallback);
        }
    };

    public static final StateType<Double> DOUBLE = new StateType<Double>() {
        @Override public <T> void put(StateMap<T> out, String key, Double value) {
            out.putDouble(key, value);
        }
        @Override public <T> Double get(StateMap<T> in, String key, Double fallback) {
            return in.getDouble(key, fallback);
        }
    };

    public static final StateType<Boolean> BOOL = new StateType<Boolean>() {
        @Override public <T> void put(StateMap<T> out, String key, Boolean value) {
            out.putBool(key, value);
        }
        @Override public <T> Boolean get(StateMap<T> in, String key, Boolean fallback) {
            return in.getBool(key, fallback);
        }
    };

    /** An enum, by constant name — so adding a constant is compatible and reordering one is not. */
    public static <E extends Enum<E>> StateType<E> enumOf(Class<E> type) {
        return new StateType<E>() {
            @Override public <T> void put(StateMap<T> out, String key, E value) {
                out.putEnum(key, value);
            }
            @Override public <T> E get(StateMap<T> in, String key, E fallback) {
                return in.getEnum(key, type, fallback);
            }
        };
    }

    /**
     * A list of strings, each written as a one-key entry.
     *
     * <p>{@code entryKey} is part of the wire format and not cosmetic: {@code Dropdown} has always
     * written its options as {@code [{label: …}]} rather than a bare string array, and changing that
     * would change the content hash of every description holding one.</p>
     */
    public static StateType<List<String>> stringListUnder(String entryKey) {
        return new StateType<List<String>>() {
            @Override public <T> void put(StateMap<T> out, String key, List<String> value) {
                out.putList(key, value == null ? List.of() : value,
                        (entry, item) -> entry.putString(entryKey, item == null ? "" : item));
            }
            @Override public <T> List<String> get(StateMap<T> in, String key, List<String> fallback) {
                List<String> read = in.getList(key, entry -> entry.getString(entryKey, ""));
                return read.isEmpty() ? fallback : read;
            }
        };
    }

    /**
     * A {@code float[]}, each element written as a one-key entry.
     *
     * <p>An array rather than a {@code List<Float>} because that is what {@code SplitView.getWeights}
     * and {@code setWeights} speak, and a contract that made every widget box its own primitives to
     * satisfy the contract would be the contract serving itself.</p>
     */
    public static StateType<float[]> floatArrayUnder(String entryKey) {
        return new StateType<float[]>() {
            @Override public <T> void put(StateMap<T> out, String key, float[] value) {
                List<Float> boxed = new ArrayList<>(value == null ? 0 : value.length);
                if (value != null) for (float item : value) boxed.add(item);
                out.putList(key, boxed, (entry, item) -> entry.putFloat(entryKey, item));
            }
            @Override public <T> float[] get(StateMap<T> in, String key, float[] fallback) {
                List<Float> read = in.getList(key, entry -> entry.getFloat(entryKey, 0f));
                if (read.isEmpty()) return fallback;
                float[] out = new float[read.size()];
                for (int i = 0; i < out.length; i++) out[i] = read.get(i);
                return out;
            }
        };
    }

    /** A {@code double[]}, for the vector and matrix controls. */
    public static StateType<double[]> doubleArrayUnder(String entryKey) {
        return new StateType<double[]>() {
            @Override public <T> void put(StateMap<T> out, String key, double[] value) {
                List<Double> boxed = new ArrayList<>(value == null ? 0 : value.length);
                if (value != null) for (double item : value) boxed.add(item);
                out.putList(key, boxed, (entry, item) -> entry.putDouble(entryKey, item));
            }
            @Override public <T> double[] get(StateMap<T> in, String key, double[] fallback) {
                List<Double> read = in.getList(key, entry -> entry.getDouble(entryKey, 0d));
                if (read.isEmpty()) return fallback;
                double[] out = new double[read.size()];
                for (int i = 0; i < out.length; i++) out[i] = read.get(i);
                return out;
            }
        };
    }
}
