package com.crystalgui.core.collection.list;

import com.crystalgui.core.collection.list.FixedHeightStrategy;

/**
 * How tall a row is, and therefore how a scroll offset maps to an index.
 *
 * <p>Pluggable rather than one algorithm covering every case — Angular CDK's shape, and the right one,
 * because the two cases have genuinely different maths. With a fixed size, offset&rarr;index is a
 * division. With measured sizes it is an anchor plus an estimate, walked in whichever direction the user
 * scrolled, and that machinery is pure cost for the ninety percent of lists that do not need it.</p>
 *
 * <p>{@link FixedHeightStrategy} is the only implementation today. Variable height is deferred, not
 * skipped: wrapped code lines need it, so 6.1.7 does — and the interface exists now so that landing it is
 * an addition rather than a rewrite of everything built against a fixed assumption.</p>
 */
public interface ItemSizeStrategy {

    /** Height of the row at {@code index}, in logical pixels. */
    float sizeOf(int index);

    /** Total height of {@code count} rows — what {@code ListView.getScrollHeight()} reports. */
    float totalSize(int count);

    /** The offset of the top of row {@code index}. */
    float offsetOf(int index);

    /** The first row whose bottom is past {@code offset}, clamped to {@code [0, count]}. */
    int indexAt(float offset, int count);
}
