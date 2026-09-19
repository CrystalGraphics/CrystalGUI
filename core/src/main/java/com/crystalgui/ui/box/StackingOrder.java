package com.crystalgui.ui.box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One stacking context's z-ordered descendants, in the three lists CSS paints them from — Blink's
 * {@code PaintLayerStackingNode}.
 *
 * <p>A box is z-ordered when it is a {@linkplain Box#isStackingContext() stacking context} or
 * {@linkplain Box#isPositioned() positioned}. Such a box is not painted where it sits in the tree but by the nearest
 * stacking context above it, after that context's normal flow when its {@code z-index} is {@code auto}, {@code 0} or
 * positive, and before it when negative (CSS 2.1 Appendix E). That is what lets a transformed button inside one row
 * paint, and take the pointer, over the row after it.</p>
 *
 * <ul>
 *   <li>A stacking context's own descendants stay inside it; a positioned box that is not one lends its z-ordered
 *       descendants to the context above, listed after it in tree order.</li>
 *   <li>{@link #negative} and {@link #positive} are ordered by {@code z-index}, ties in tree order; {@link #zero} is
 *       tree order. A context that {@linkplain Box#stacksByInsertion stacks by insertion} — the top layer — lists
 *       everything in {@link #zero}, in the order it was hosted.</li>
 *   <li>The top layer itself is in {@link #top}, after everything else whatever anything declares: CSS paints it
 *       after the document, outside every stacking context in it.</li>
 *   <li>Built for the tree's current {@link BoxTree#stackingEpoch}, and rebuilt once that moves.</li>
 * </ul>
 */
final class StackingOrder {

    private static final Comparator<Box> BY_Z = Comparator.comparingInt(box -> box.zIndex().orZero());

    final List<Box> negative = new ArrayList<>();
    final List<Box> zero = new ArrayList<>();
    final List<Box> positive = new ArrayList<>();
    final List<Box> top = new ArrayList<>();
    final int epoch;

    private StackingOrder(int epoch) {
        this.epoch = epoch;
    }

    /** The lists for {@code root}, as though it were a stacking context whether or not it is one. */
    static StackingOrder of(Box root, int epoch) {
        StackingOrder order = new StackingOrder(epoch);
        boolean byInsertion = root.stacksByInsertion();
        order.collect(root, byInsertion);
        if (!byInsertion) {
            // STABLE, so equal z-indices keep tree order.
            order.negative.sort(BY_Z);
            order.positive.sort(BY_Z);
        }
        return order;
    }

    private void collect(Box parent, boolean byInsertion) {
        for (Box child : parent.hosted) {
            if (!child.isZOrdered()) {
                collect(child, byInsertion);
                continue;
            }
            if (child.stacksByInsertion()) {
                top.add(child);
                continue;
            }
            int z = byInsertion ? 0 : child.zIndex().orZero();
            (z < 0 ? negative : z > 0 ? positive : zero).add(child);
            // A POSITIONED BOX THAT IS NOT A CONTEXT holds no list of its own: what it would, the context above does.
            if (!child.isStackingContext()) collect(child, byInsertion);
        }
    }
}
