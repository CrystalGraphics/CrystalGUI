package com.crystalgui.ui.elements.list;

/**
 * Every row the same height — a division rather than a search, and what the overwhelming majority of
 * lists actually are.
 *
 * <p>Deliberately the first and only strategy. A file list, a command palette, a properties table and an
 * unwrapped code editor are all uniform; the cases that are not (wrapped lines) need measurement, which
 * is a different algorithm rather than a harder version of this one.</p>
 */
public final class FixedHeightStrategy implements ItemSizeStrategy {

    private final float itemHeight;

    public FixedHeightStrategy(float itemHeight) {
        if (!(itemHeight > 0f)) {
            // Zero would make indexAt divide by zero, and worse, would make every row occupy the same
            // offset — the window would be "every row at once", which is the exact failure this widget
            // exists to prevent, arrived at silently.
            throw new IllegalArgumentException("Row height must be positive, was " + itemHeight);
        }
        this.itemHeight = itemHeight;
    }

    public float itemHeight() {
        return itemHeight;
    }

    @Override
    public float sizeOf(int index) {
        return itemHeight;
    }

    @Override
    public float totalSize(int count) {
        return Math.max(0, count) * itemHeight;
    }

    @Override
    public float offsetOf(int index) {
        return Math.max(0, index) * itemHeight;
    }

    @Override
    public int indexAt(float offset, int count) {
        if (offset <= 0f) return 0;
        int index = (int) (offset / itemHeight);
        return Math.max(0, Math.min(count, index));
    }
}
