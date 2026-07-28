package com.crystalgui.serialization.style;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.Codecs;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.property.StyleProperty;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codecs for the value types style properties actually hold, keyed by type.
 *
 * <h3>Why by type rather than by property</h3>
 * <p>There are 75 registered properties but only about twenty distinct value <em>types</em>, and they
 * collapse further than that: fourteen are enums, four are scalars, and
 * {@link LengthPercentageAuto} alone covers twenty-five properties. So roughly eight codecs reach
 * about sixty-five of the seventy-five — one table, not seventy-five hand-written writers.</p>
 *
 * <h3>Why values and not the CSS source text</h3>
 * <p>The obvious alternative is to keep the {@code rawValue} string every parsed value carries and
 * ship that. It works, costs nothing, and covers exactly the values that came from CSS — but the
 * fluent Java setters ({@code layout(l -> l.width(80))}) never produce a string at all. They call
 * {@code StyleGroup.set(property, T)} directly, so there is no source text to have kept. Since that
 * is how most UI code is actually written, the value has to be serializable on its own terms.</p>
 *
 * <h3>Unsupported types fail loudly</h3>
 * <p>{@link #forProperty} returns {@code null} for a type with no codec, and callers must treat that
 * as an error naming the property rather than skipping it. A silently dropped style is a UI that
 * renders subtly wrong on one side only — the single worst failure mode this whole layer has.</p>
 */
public final class StyleValueCodecs {

    private static final Map<Class<?>, Codec<?>> BY_TYPE = new LinkedHashMap<>();

    private StyleValueCodecs() {
    }

    /**
     * A tagged union of {@code {t: TYPE}} plus {@code {v: number}} when the type carries one.
     *
     * <p>{@code CALC} is deliberately rejected: a calc expression is a whole expression tree, the
     * parser for it doesn't exist in this codebase ({@code LPAValue} serializes it as the literal
     * text {@code "calc(...)"}), and quietly encoding it as its numeric fallback would ship a
     * different layout than the author wrote.</p>
     */
    private static <V> Codec<V> taggedUnion(Class<V> type,
                                            java.util.function.Function<V, String> tagOf,
                                            java.util.function.ToDoubleFunction<V> valueOf,
                                            java.util.function.BiFunction<String, Float, V> build) {
        return new Codec<V>() {
            @Override
            public <T> T encode(DynamicOps<T> ops, V input) {
                String tag = tagOf.apply(input);
                if ("CALC".equals(tag)) {
                    throw new CodecException("calc() values cannot be serialized — "
                            + type.getSimpleName() + " carries an expression tree with no parser to rebuild it");
                }
                Codecs.MapCodecBuilder<T> out = Codecs.map(ops).field("t", Codecs.STRING, tag);
                if ("LENGTH".equals(tag) || "PERCENT".equals(tag)) {
                    out.field("v", Codecs.FLOAT, (float) valueOf.applyAsDouble(input));
                }
                return out.build();
            }

            @Override
            public <T> V decode(DynamicOps<T> ops, T input) {
                var in = Codecs.read(ops, input);
                String tag = in.field("t", Codecs.STRING);
                float value = in.optional("v", Codecs.FLOAT, 0f);
                return build.apply(tag, value);
            }
        };
    }

    public static final Codec<LengthPercentageAuto> LENGTH_PERCENTAGE_AUTO = taggedUnion(
            LengthPercentageAuto.class,
            v -> v.getType().name(),
            LengthPercentageAuto::getValue,
            (tag, value) -> switch (tag) {
                case "LENGTH" -> LengthPercentageAuto.length(value);
                case "PERCENT" -> LengthPercentageAuto.percent(value);
                case "AUTO" -> LengthPercentageAuto.AUTO;
                case "MIN_CONTENT" -> LengthPercentageAuto.MIN_CONTENT;
                case "MAX_CONTENT" -> LengthPercentageAuto.MAX_CONTENT;
                case "FIT_CONTENT" -> LengthPercentageAuto.FIT_CONTENT;
                case "STRETCH" -> LengthPercentageAuto.STRETCH;
                default -> throw new CodecException("Unknown LengthPercentageAuto type '" + tag + "'");
            });

    public static final Codec<TaffyDimension> DIMENSION = taggedUnion(
            TaffyDimension.class,
            v -> v.getType().name(),
            TaffyDimension::getValue,
            (tag, value) -> switch (tag) {
                case "LENGTH" -> TaffyDimension.length(value);
                case "PERCENT" -> TaffyDimension.percent(value);
                case "AUTO" -> TaffyDimension.AUTO;
                case "MIN_CONTENT" -> TaffyDimension.MIN_CONTENT;
                case "MAX_CONTENT" -> TaffyDimension.MAX_CONTENT;
                case "FIT_CONTENT" -> TaffyDimension.FIT_CONTENT;
                case "STRETCH" -> TaffyDimension.STRETCH;
                default -> throw new CodecException("Unknown TaffyDimension type '" + tag + "'");
            });

    static {
        BY_TYPE.put(String.class, Codecs.STRING);
        BY_TYPE.put(Integer.class, Codecs.INT);
        BY_TYPE.put(Float.class, Codecs.FLOAT);
        BY_TYPE.put(Boolean.class, Codecs.BOOL);
        BY_TYPE.put(Double.class, Codecs.DOUBLE);
        BY_TYPE.put(Long.class, Codecs.LONG);
        BY_TYPE.put(LengthPercentageAuto.class, LENGTH_PERCENTAGE_AUTO);
        BY_TYPE.put(TaffyDimension.class, DIMENSION);
    }

    /**
     * The codec for {@code property}'s value type, or {@code null} if there isn't one.
     *
     * <p>Enums are handled generically — by constant name, never ordinal, so inserting a constant
     * mid-enum can't silently re-point an existing wire value at a different one.</p>
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> Codec<V> forProperty(StyleProperty<V> property) {
        Codec<?> known = BY_TYPE.get(property.type);
        if (known != null) return (Codec<V>) known;
        if (property.type.isEnum()) {
            return (Codec<V>) Codecs.enumOf((Class<? extends Enum>) property.type.asSubclass(Enum.class));
        }
        return null;
    }

    /** Whether {@code property}'s value can travel at all — see the class note on failing loudly. */
    public static boolean isSerializable(StyleProperty<?> property) {
        return forProperty(property) != null;
    }
}
