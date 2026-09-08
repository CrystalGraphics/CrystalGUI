package com.crystalgui.serialization.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.serialization.Codec;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.transform.Transform;

/**
 * {@code transform}, divided by FUNCTION — translate, rotate, scale, skew, under one heading.
 *
 * <p>Each part is itself a {@link Transform} holding only that kind's functions, so it encodes through
 * the codec the whole property already uses and no second representation exists to disagree with it.</p>
 *
 * <h3>Order is the whole difficulty</h3>
 *
 * <p>A transform is an ORDERED list — {@code translate(10px) scale(2)} is not {@code scale(2)
 * translate(10px)} — so merging one kind back has to answer where it goes. Replacing a kind the target
 * already has is unambiguous: the new functions go exactly where the old ones were, and everything else
 * keeps its place. A kind the target does not have is APPENDED, which is a choice rather than a
 * discovery: appending composes it after everything, and there is no position that is neutral.</p>
 *
 * <p><b>A kind that appears more than once, split apart by another kind, is refused</b> — see
 * {@link #encodePart}. {@code translate(1px) rotate(1rad) translate(2px)} cannot be described as "the
 * translation" without saying where the rotation went, and answering it either way changes what the
 * element looks like. Offering the whole property instead is the only honest answer.</p>
 */
public final class TransformParts implements StyleParts<Transform> {

    public static final TransformParts INSTANCE = new TransformParts();

    /**
     * All four under ONE heading, because the group is the COMPOSITE and the parts are what is in it.
     *
     * <p>A section per function reads as four unrelated properties that happen to sit next to each
     * other — and they are one property, whose whole difficulty is that its parts compose in an order.
     * The window should say the same thing the value does: here is {@code transform}, and here is what
     * it is made of. {@code transform-origin-x} and {@code -y} are filed under the same heading by the
     * carrier, so the section holds the family rather than a slice of it.</p>
     */
    private static final String GROUP = "Transform";

    private static final List<Part> PARTS = List.of(
            new Part("translate", GROUP, "translate"),
            new Part("rotate", GROUP, "rotate"),
            new Part("scale", GROUP, "scale"),
            new Part("skew", GROUP, "skew"));

    private TransformParts() {
    }

    /** The one path every style value travels by. @see StyleValueCodecs */
    private static Codec<Transform> codec() {
        return StyleValueCodecs.forProperty(StylePropertyRegistry.TRANSFORM);
    }

    @Override
    public List<Part> parts() {
        return PARTS;
    }

    /**
     * True unless some kind appears more than once with another kind between.
     *
     * <p>All or nothing: a transform where the rotation interleaves but the scale does not would
     * otherwise offer the scale alone, and ticking every box would drop the rotation without a word.</p>
     */
    @Override
    public boolean divides(Transform value) {
        for (Transform.Kind kind : Transform.Kind.values()) {
            if (isInterrupted(value, kind)) return false;
        }
        return true;
    }

    @Nullable
    private static Transform.Kind kindOf(String partId) {
        return switch (partId) {
            case "translate" -> Transform.Kind.TRANSLATE;
            case "rotate" -> Transform.Kind.ROTATE;
            case "scale" -> Transform.Kind.SCALE;
            case "skew" -> Transform.Kind.SKEW;
            default -> null;
        };
    }

    @Nullable
    @Override
    public <T> T encodePart(DynamicOps<T> ops, Transform value, String partId) {
        Transform.Kind kind = kindOf(partId);
        if (kind == null) return null;
        List<Transform.Op> mine = new ArrayList<>();
        for (Transform.Op op : value.ops()) {
            if (op.kind() == kind) mine.add(op);
        }
        if (mine.isEmpty()) return null;
        return codec().encode(ops, Transform.of(mine));
    }

    /** Whether another kind sits between two functions of {@code kind}. @see #divides */
    private static boolean isInterrupted(Transform value, Transform.Kind kind) {
        int first = -1;
        int last = -1;
        List<Transform.Op> ops = value.ops();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind() != kind) continue;
            if (first < 0) first = i;
            last = i;
        }
        // A KIND THAT IS NOT THERE CANNOT BE INTERRUPTED, and `divides` asks about all four whether the
        // value uses them or not -- so the absent ones have to answer rather than walk from -1.
        if (first < 0) return false;
        for (int i = first; i <= last; i++) {
            if (ops.get(i).kind() != kind) return true;
        }
        return false;
    }

    @Override
    public <T> Transform mergePart(DynamicOps<T> ops, Transform base, String partId, T encoded) {
        Transform.Kind kind = kindOf(partId);
        if (kind == null) return base;
        List<Transform.Op> incoming = codec().decode(ops, encoded).ops();

        List<Transform.Op> merged = new ArrayList<>();
        boolean placed = false;
        for (Transform.Op op : base.ops()) {
            if (op.kind() != kind) {
                merged.add(op);
                continue;
            }
            // IN PLACE, at the first of the ones being replaced: the incoming functions take the
            // position the outgoing ones held, so nothing else in the list changes meaning.
            if (!placed) {
                merged.addAll(incoming);
                placed = true;
            }
        }
        if (!placed) merged.addAll(incoming);
        return Transform.of(merged);
    }
}
