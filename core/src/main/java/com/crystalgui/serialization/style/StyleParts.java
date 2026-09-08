package com.crystalgui.serialization.style;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.serialization.DynamicOps;

/**
 * How a composite style value comes apart, so its pieces can be copied one at a time.
 *
 * <pre>{@code
 * StyleParts<Transform> parts = StylePartRegistry.forProperty(StylePropertyRegistry.TRANSFORM);
 * JsonElement rotation = parts.encodePart(JsonOps.INSTANCE, transform, "rotate");
 * Transform merged = parts.mergePart(JsonOps.INSTANCE, target, "rotate", rotation);
 * }</pre>
 *
 * <h3>Why the engine needs to say this rather than the window guessing</h3>
 *
 * <p>{@code transform} is one property holding four ideas, and a person who wants "the rotation, not the
 * scale" is asking about something CSS has no name for. Only the value's own type knows how it divides —
 * that a transform divides by FUNCTION and a gradient by direction-and-stops — so the split is declared
 * beside the codec that already knows how to write it, not inferred by whatever is offering the choice.</p>
 *
 * <h3>Encoded, not typed</h3>
 *
 * <p>A part is handed about in its encoded form because that is the only shape every part of every value
 * has in common: a transform's rotation is a {@code Transform}, a gradient's stops are a list of colours,
 * and no one Java type covers both without becoming {@code Object}. Going through {@link DynamicOps}
 * means each part chooses its own representation and the caller never needs to know what it chose.</p>
 *
 * <h3>Absent is null, and it is not the same as identity</h3>
 *
 * <p>{@link #encodePart} answers null when the value does not carry that part at all, and the window
 * then does not offer it. That is the difference between "this element has no rotation" and "this
 * element is rotated by zero" — the first is nothing to copy, the second is a value somebody chose.</p>
 */
public interface StyleParts<V> {

    /**
     * One addressable piece.
     *
     * @param id    stable, and what a chosen slot is keyed by — never shown
     * @param group the section it is filed under, which is a heading in the Paste Attributes window
     * @param label what a person reads, or EMPTY to be named after the property it came from — which is
     *              right when the part IS the property and the section says what kind of value it holds
     */
    record Part(String id, String group, String label) {
    }

    /** Every piece this kind of value can divide into, in the order they should be offered. */
    List<Part> parts();

    /**
     * Whether {@code value} is fully described by its parts — <b>all of it, or none of it</b>.
     *
     * <p>Answering false means the whole property is offered as one slot instead. That is not a fussy
     * edge case: if some of a value divides and the rest does not, the pieces that do not simply are not
     * offered, and ticking every box would silently produce something other than the source. A partial
     * decomposition is worse than none, because nothing about it says a part went missing.</p>
     */
    boolean divides(V value);

    /** The part's value on its own, or null when {@code value} does not carry it. */
    @Nullable
    <T> T encodePart(DynamicOps<T> ops, V value, String partId);

    /**
     * {@code base} with one part replaced by {@code encoded}.
     *
     * <p><b>Merges into a base rather than building from nothing</b>, because pasting one part must
     * leave the rest of the target alone — the whole promise of choosing which attributes land.</p>
     */
    <T> V mergePart(DynamicOps<T> ops, V base, String partId, T encoded);
}
