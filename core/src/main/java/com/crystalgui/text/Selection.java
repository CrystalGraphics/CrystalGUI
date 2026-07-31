package com.crystalgui.text;

/**
 * One caret, and the text it has selected — an {@code anchor} that stays put and a {@code head} that
 * moves.
 *
 * <p>Two offsets rather than a start and an end, because <b>direction is information</b>: shift-Left from
 * the end of a selection must shrink it, and shift-Right must grow it, which is impossible to know from a
 * sorted pair. {@link #start()} and {@link #end()} are derived for the cases that genuinely do not care.
 * </p>
 *
 * <p>An empty selection ({@code anchor == head}) is a plain caret. Treating a caret as a degenerate
 * selection rather than a separate thing is what lets every movement, edit and rendering path handle one
 * case instead of two.</p>
 */
public record Selection(int anchor, int head) implements Comparable<Selection> {

    public static Selection caret(int offset) {
        return new Selection(offset, offset);
    }

    public int start() {
        return Math.min(anchor, head);
    }

    public int end() {
        return Math.max(anchor, head);
    }

    public boolean isEmpty() {
        return anchor == head;
    }

    public int length() {
        return end() - start();
    }

    /** True when the head is before the anchor — i.e. the selection was made leftwards. */
    public boolean isReversed() {
        return head < anchor;
    }

    /** This selection with its head moved, keeping the anchor. */
    public Selection withHead(int newHead) {
        return new Selection(anchor, newHead);
    }

    /** A caret at this selection's head, discarding the selected range. */
    public Selection collapsed() {
        return caret(head);
    }

    /** Whether the two overlap, or touch — touching carets are the same caret. */
    public boolean touches(Selection other) {
        return start() <= other.end() && other.start() <= end();
    }

    /**
     * The smallest selection covering both, keeping <em>this</em> one's direction.
     *
     * <p>Direction is kept rather than recomputed because the merged selection continues the gesture that
     * produced this one: extending leftwards into a neighbour should still be extending leftwards.</p>
     */
    public Selection mergedWith(Selection other) {
        int lo = Math.min(start(), other.start());
        int hi = Math.max(end(), other.end());
        return isReversed() ? new Selection(hi, lo) : new Selection(lo, hi);
    }

    public Selection clampedTo(int documentLength) {
        int a = Math.max(0, Math.min(anchor, documentLength));
        int h = Math.max(0, Math.min(head, documentLength));
        return a == anchor && h == head ? this : new Selection(a, h);
    }

    @Override
    public int compareTo(Selection other) {
        int byStart = Integer.compare(start(), other.start());
        return byStart != 0 ? byStart : Integer.compare(end(), other.end());
    }

    @Override
    public String toString() {
        return isEmpty() ? "caret@" + head : anchor + "->" + head;
    }
}
