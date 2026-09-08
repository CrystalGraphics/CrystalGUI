package com.crystalgui.serialization.style;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.property.StyleProperty;

/**
 * How a style value travels: <b>as the CSS that would produce it</b>.
 *
 * <pre>{@code
 * Codec<Integer> codec = StyleValueCodecs.forProperty(StylePropertyRegistry.COLOR);
 * JsonElement wire = codec.encode(JsonOps.INSTANCE, 0xFF3574F0);   // "#3574F0FF"
 * V value = codec.decode(JsonOps.INSTANCE, wire);
 * }</pre>
 *
 * <p>There is one codec and it is built from the property itself: encoding is
 * {@link StyleProperty#write}, decoding is the {@code ValueParser} that reads the same property out of a
 * stylesheet. So every registered property can travel — {@link #forProperty} never returns null — and
 * adding a CSS property does not also mean adding a codec.</p>
 *
 * <p><b>Text rather than a structured encoding</b>, which is larger and untyped and deliberate: a
 * structure is a second description of every value, and a second description drifts from the parser
 * beside it with nothing to notice. CSS text is the one description both sides must already agree on to
 * render anything at all. {@code StyleValueRoundTripTest} and {@code ShippedStyleRoundTripTest} hold
 * every property, and every declaration this engine ships, to writing something its own parser reads
 * back as an equal value.</p>
 *
 * <p>A value that fails that round trip at runtime throws rather than degrading — see
 * {@link #forProperty}.</p>
 */
public final class StyleValueCodecs {

    private StyleValueCodecs() {
    }

    /**
     * The codec for {@code property}, never null.
     *
     * <p>Decoding throws {@link CodecException} when the text does not parse. That text came from this
     * engine's own writer, so it means the property's reader and its writer disagree — quietly
     * substituting the initial value would put a default where a real one was copied from.</p>
     */
    public static <V> Codec<V> forProperty(StyleProperty<V> property) {
        return new Codec<V>() {

            @Override
            public <T> T encode(DynamicOps<T> ops, V value) {
                return ops.createString(property.write(value));
            }

            @Override
            public <T> V decode(DynamicOps<T> ops, T encoded) {
                String text = ops.getStringValue(encoded);
                V parsed = property.valueParser.parse(text).compute();
                if (parsed == null) {
                    // LOUD, not the initial value. A stylesheet degrades on a bad declaration because the
                    // rest of the page is worth rendering; this text came from our own writer, so null
                    // means the two halves of one property disagree, and quietly substituting a default
                    // would put it somewhere a real value was copied from.
                    throw new CodecException("Style property '" + property.name
                            + "' could not read back what it wrote: '" + text + "'. Its write() and its"
                            + " ValueParser disagree — see StyleValueRoundTripTest.");
                }
                return parsed;
            }
        };
    }
}
