package com.crystalgui.widget.dnd;

import java.util.List;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Where a drop lands among a container's laid-out children — before or after which one, and whether a
 * point means <em>into</em> a container or <em>beside</em> it.
 *
 * <pre>{@code
 * SortPlacement.Flow flow = SortPlacement.Flow.of(container);
 * List<SortPlacement.Cell> cells = ...;   // each in-flow child's rect, in one space, in visual order
 * SortPlacement.Found found = SortPlacement.find(cells, pointerX, pointerY);
 * int index = found.index(flow.reversed());   // the child index a drop inserts at
 * }</pre>
 *
 * <pre>{@code
 * float[] inner = SortPlacement.dropArea(x, y, width, height);
 * boolean into = SortPlacement.contains(inner, pointerX, pointerY);   // else it is a drop beside
 * }</pre>
 *
 * <p>Ported from GrapesJS ({@code packages/core/src/utils/sorter/}, BSD-3-Clause, © Artur Arseniev):
 * {@link #find} is {@code SorterUtils.findPosition}, {@link Cell#sideOf} is
 * {@code Dimension.determinePlacement}, and {@link #dropArea} is {@code Dimension.getDropArea} with
 * {@code CanvasComponentNode}'s configuration. One change: the three limits are unset as {@code NaN} rather
 * than as a falsy zero, which in the original ignored a limit at coordinate 0.</p>
 *
 * <ul>
 *   <li>Every rect and the point are in <b>one space</b>; this never converts.</li>
 *   <li>Cells go in <b>visual</b> order. For a {@code *-reverse} flow that is the children reversed, and
 *       {@link Found#index} flips the side back into child order.</li>
 * </ul>
 */
public final class SortPlacement {

    /** The share of a container a drop into it may land in. */
    public static final float DROP_AREA_RATIO = 0.8f;

    /** The band round that share, in the rects' pixels: never thinner than this... */
    public static final float MIN_BAND = 1f;

    /** ...and never thicker than this, so a large container's edge is not a wide dead zone. */
    public static final float MAX_BAND = 15f;

    private SortPlacement() {
    }

    public enum Side { BEFORE, AFTER }

    /**
     * How a container lays its children out.
     *
     * <p>{@code column} is flow down a column — a {@code column} direction that does not wrap, or a block.
     * Anything else, a wrapping column included, is row flow, where a point finds its line first.</p>
     */
    public record Flow(boolean column, boolean reversed) {

        /** From the container's computed style. */
        public static Flow of(UIElement container) {
            var computed = container.getStyle().computed();
            TaffyDisplay display = computed.get(LayoutProperties.DISPLAY);
            if (display == TaffyDisplay.BLOCK) return new Flow(true, false);
            if (display == TaffyDisplay.GRID) return new Flow(false, false);
            FlexDirection direction = computed.get(LayoutProperties.FLEX_DIRECTION);
            boolean wraps = computed.get(LayoutProperties.FLEX_WRAP) != FlexWrap.NO_WRAP;
            boolean column = direction == FlexDirection.COLUMN || direction == FlexDirection.COLUMN_REVERSE;
            boolean reversed = direction == FlexDirection.ROW_REVERSE || direction == FlexDirection.COLUMN_REVERSE;
            return new Flow(column && !wraps, reversed);
        }
    }

    /** Whether {@code child} takes a place in its parent's flow: laid out, shown, and not absolutely positioned. */
    public static boolean inFlow(UIElement child) {
        return child.box() != null && child.isDisplayed()
                && child.getStyle().computed().get(LayoutProperties.POSITION) != TaffyPosition.ABSOLUTE;
    }

    /**
     * One child: its rect, whether its parent's flow is a column, and its index among the parent's children.
     *
     * @param index the CHILD index, which is what a drop inserts at — not the cell's place in the list
     */
    public record Cell(float x, float y, float width, float height, boolean column, int index) {

        /** Before or after this child, by its centre on the flow's axis. */
        public Side sideOf(float px, float py) {
            return column ? (py < y + height / 2f ? Side.BEFORE : Side.AFTER)
                    : (px < x + width / 2f ? Side.BEFORE : Side.AFTER);
        }
    }

    /** The child a point is placed against, and on which side. */
    public record Found(Cell cell, Side side) {

        /** The child index a drop inserts at. {@code reversed} is the flow's, and swaps the side. */
        public int index(boolean reversed) {
            return cell.index() + ((side == Side.AFTER) != reversed ? 1 : 0);
        }
    }

    /**
     * The child {@code (px, py)} is placed against, among cells in visual order.
     *
     * <p>A column cell answers by its vertical centre, and the first one the point is above ends the walk.
     * A row cell answers by its horizontal centre, and the limits confine the answer to the line the point
     * is on: a cell starting past the centre already chosen, a cell on a line below the point's, and a
     * cell ending before the centre already passed are all skipped.</p>
     *
     * @throws IllegalArgumentException for no cells; an empty container is a drop inside, which has no side
     */
    public static Found find(List<Cell> cells, float px, float py) {
        if (cells.isEmpty()) throw new IllegalArgumentException("no cells to place against");
        int at = 0;
        Side side = Side.BEFORE;
        float xLimit = Float.NaN;
        float yLimit = Float.NaN;
        float leftLimit = Float.NaN;
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            float right = cell.x() + cell.width();
            float bottom = cell.y() + cell.height();
            float xCentre = cell.x() + cell.width() / 2f;
            float yCentre = cell.y() + cell.height() / 2f;
            // `>=` on the line limit, as the original has it: a cell whose centre is exactly on the limit is
            // on the next line.
            if ((!Float.isNaN(xLimit) && cell.x() > xLimit)
                    || (!Float.isNaN(yLimit) && yCentre >= yLimit)
                    || (!Float.isNaN(leftLimit) && right < leftLimit)) {
                continue;
            }
            at = i;
            if (!cell.column()) {
                if (py < bottom) yLimit = bottom;
                if (px < xCentre) {
                    xLimit = xCentre;
                    side = Side.BEFORE;
                } else {
                    leftLimit = xCentre;
                    side = Side.AFTER;
                }
            } else if (py < yCentre) {
                side = Side.BEFORE;
                break;
            } else {
                side = Side.AFTER;
            }
        }
        return new Found(cells.get(at), side);
    }

    /** The inner rect a drop INTO {x, y, width, height} lands in, at the default ratio and band. */
    public static float[] dropArea(float x, float y, float width, float height) {
        return dropArea(x, y, width, height, DROP_AREA_RATIO, MIN_BAND, MAX_BAND);
    }

    /** As {@link #dropArea(float, float, float, float)}, stating the ratio and the band's bounds. */
    public static float[] dropArea(float x, float y, float width, float height,
                                   float ratio, float minBand, float maxBand) {
        float bandX = band(width, ratio, minBand, maxBand);
        float bandY = band(height, ratio, minBand, maxBand);
        return new float[] {x + bandX, y + bandY, width - bandX * 2f, height - bandY * 2f};
    }

    private static float band(float size, float ratio, float minBand, float maxBand) {
        return Math.min(Math.max(size * (1f - ratio) / 2f, minBand), maxBand);
    }

    /** Whether a point is inside {x, y, width, height}, edges included. */
    public static boolean contains(float[] rect, float px, float py) {
        return px >= rect[0] && px <= rect[0] + rect[2] && py >= rect[1] && py <= rect[1] + rect[3];
    }
}
