package com.crystalgui.serialization.style;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.CodecException;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.CssComments;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.Styleable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * An element's <b>author-set</b> styles, as a {@code property-name → value} map.
 *
 * <h3>Only INLINE origin travels, and that is the whole design</h3>
 * <p>An element's cascade holds candidates at five origins, and only one of them is worth sending:</p>
 * <ul>
 *   <li>{@code DEFAULT} — written by a widget's own constructor ({@code Button}'s
 *       {@code flex-direction: row}). The client constructs the same widget, so it gets these for
 *       free. Sending them would be pure duplication.</li>
 *   <li>{@code USER_AGENT}/{@code STYLESHEET} — come from sheets, which travel by reference.</li>
 *   <li>{@code IMPORTANT} — runtime state a widget computes about itself ({@code Tab}'s pane
 *       {@code display}, {@code Slider}'s fill weight). The client recomputes them from restored
 *       widget state; sending them would fight the widget's own logic.</li>
 *   <li>{@code ANIMATION} — a transition mid-flight. Transient by definition.</li>
 *   <li><b>{@code INLINE}</b> — what the UI's author wrote. Nothing else can reproduce it, so this
 *       is exactly what has to be sent.</li>
 * </ul>
 *
 * <p>The upshot is that a typical widget serializes <em>no</em> styles at all, and only what an
 * author explicitly set on it travels.</p>
 *
 * <h3>A switched-off declaration travels as the comment it is</h3>
 * <p>{@code "color": "/* #FFF8CB *}{@code /"} is a declaration switched off where it stands, and
 * {@code "text-shadow": "#000 0 1px 2px /* , #F00 0 0 4px *}{@code /"} one holding a switched-off layer: the element's
 * {@link com.crystalgui.style.ElementStyle#inlineText written text} is sent in place of its value, and decoding
 * applies what is left once the comments are stripped.</p>
 */
public final class InlineStyleCodec {

    private InlineStyleCodec() {
    }

    /** Encodes {@code element}'s INLINE-origin candidates. Returns {@code null} when there are none,
     * so the caller can omit the field entirely rather than writing an empty map. */
    public static <T> T encode(DynamicOps<T> ops, Styleable element) {
        // Sorted by property name so the encoding is a function of the element alone. The cascade
        // stores candidates in a HashMap, whose iteration order varies between JVM runs — and these
        // descriptions are content-addressed, so an unstable order would produce an unstable hash
        // and silently defeat the client's cache.
        Map<String, T> byName = new TreeMap<>();
        for (var entry : element.getStyle().candidates.entrySet()) {
            for (StyleSlot<?> slot : entry.getValue()) {
                if (slot.origin() == StyleOrigin.INLINE) byName.put(slot.property().name, encodeSlot(ops, slot));
            }
        }
        for (StyleProperty<?> property : element.getStyle().inlineTextProperties()) {
            byName.put(property.name, ops.createString(element.getStyle().inlineText(property)));
        }
        if (byName.isEmpty()) return null;

        Map<T, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : byName.entrySet()) out.put(ops.createString(entry.getKey()), entry.getValue());
        return ops.createMap(out);
    }

    @SuppressWarnings("unchecked")
    private static <T, V> T encodeSlot(DynamicOps<T> ops, StyleSlot<V> slot) {
        StyleProperty<V> property = slot.property();
        // EVERY property has one now: a codec is built from the property's own parser and writer, so
        // there is no longer a class of style that cannot be sent. @see StyleValueCodecs
        return StyleValueCodecs.forProperty(property).encode(ops, slot.value());
    }

    /**
     * Makes the element's inline style <b>exactly</b> the encoded map, dropping anything else it holds.
     *
     * <p>{@link #decodeInto} merges, which is right for a description that adds to what is there and
     * wrong for anything that has to restore a remembered state: undoing an edit that ADDED a property
     * would write back the old values and leave the new property standing. In the builder that read as
     * "Ctrl+Z did nothing" on the first transform or the first resize of a node with no inline style —
     * the visible half of the edit was the added property, and it was the half undo could not reach.</p>
     *
     * <pre>{@code
     * InlineStyleCodec.replaceInto(JsonOps.INSTANCE, remembered, node);   // exactly `remembered`
     * }</pre>
     */
    public static <T> void replaceInto(DynamicOps<T> ops, T encoded, Styleable element) {
        element.getStyle().removeCandidates(slot -> slot.origin() == StyleOrigin.INLINE);
        element.getStyle().clearInlineTexts();
        decodeInto(ops, encoded, element);
    }

    /** Adds every property in the map at {@code INLINE} origin, leaving anything else untouched. */
    public static <T> void decodeInto(DynamicOps<T> ops, T encoded, Styleable element) {
        for (var entry : ops.getMapValue(encoded).entrySet()) {
            String name = ops.getStringValue(entry.getKey());
            StyleProperty<Object> property = StylePropertyRegistry.byName(name);
            if (property == null) {
                throw new CodecException("Unknown style property '" + name + "' in an element description");
            }
            Codec<Object> codec = StyleValueCodecs.forProperty(property);
            if (codec == null) {
                throw new CodecException("Style property '" + name + "' has no value codec — "
                        + "the sender should not have been able to encode it");
            }
            String written = writtenText(ops, entry.getValue());
            if (written != null && CssComments.has(written)) {
                // SWITCHED OFF WHERE IT STANDS: what is left of it is applied, and the text is kept for the save.
                String live = CssComments.strip(written).trim();
                element.getStyle().removeCandidates(property, slot -> slot.origin() == StyleOrigin.INLINE);
                if (!live.isEmpty()) {
                    element.getStyle().replaceOrPutCandidate(property,
                            StyleSlot.of(property, StyleOrigin.INLINE, 0, 0L, codec.decode(ops, ops.createString(live))));
                }
                element.getStyle().setInlineText(property, written);
                continue;
            }
            element.getStyle().replaceOrPutCandidate(property,
                    StyleSlot.of(property, StyleOrigin.INLINE, 0, 0L, codec.decode(ops, entry.getValue())));
            // A PLAIN VALUE RETIRES THE WRITTEN TEXT: one equal to what the text was written for would keep it, and the
            // next save would put back a comment this description no longer holds.
            element.getStyle().setInlineText(property, null);
        }
    }

    /** The value as a string when it is one; a codec may write a value some other way. */
    @Nullable
    private static <T> String writtenText(DynamicOps<T> ops, T value) {
        try {
            return ops.getStringValue(value);
        } catch (RuntimeException notAString) {
            return null;
        }
    }
}
