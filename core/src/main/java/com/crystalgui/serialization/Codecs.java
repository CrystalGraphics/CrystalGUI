package com.crystalgui.serialization;

import java.util.ArrayList;
import java.util.List;

/** Built-in primitive {@link Codec}s and generic combinators — the reusable low-level vocabulary
 * hand-written, type-specific codecs (like a future UI-tree codec) are built from. */
public final class Codecs {

    public static final Codec<String> STRING = new Codec<String>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, String input) {
            return ops.createString(input);
        }

        @Override
        public <T> String decode(DynamicOps<T> ops, T input) {
            return ops.getStringValue(input);
        }
    };

    public static final Codec<Integer> INT = new Codec<Integer>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, Integer input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Integer decode(DynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).intValue();
        }
    };

    public static final Codec<Float> FLOAT = new Codec<Float>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, Float input) {
            return ops.createNumber(input);
        }

        @Override
        public <T> Float decode(DynamicOps<T> ops, T input) {
            return ops.getNumberValue(input).floatValue();
        }
    };

    public static final Codec<Boolean> BOOL = new Codec<Boolean>() {
        @Override
        public <T> T encode(DynamicOps<T> ops, Boolean input) {
            return ops.createBoolean(input);
        }

        @Override
        public <T> Boolean decode(DynamicOps<T> ops, T input) {
            return ops.getBooleanValue(input);
        }
    };

    /** A {@code Codec<List<A>>} built from an element codec — encodes/decodes every element via
     * {@code elementCodec}, wrapping the result in the format's own list representation. */
    public static <A> Codec<List<A>> listOf(Codec<A> elementCodec) {
        return new Codec<List<A>>() {
            @Override
            public <T> T encode(DynamicOps<T> ops, List<A> input) {
                List<T> encoded = new ArrayList<>(input.size());
                for (A a : input) encoded.add(elementCodec.encode(ops, a));
                return ops.createList(encoded);
            }

            @Override
            public <T> List<A> decode(DynamicOps<T> ops, T input) {
                List<T> raw = ops.getListValue(input);
                List<A> decoded = new ArrayList<>(raw.size());
                for (T t : raw) decoded.add(elementCodec.decode(ops, t));
                return decoded;
            }
        };
    }

    private Codecs() {
    }
}
