package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * Where a dragged box wants to land — sibling edges, sibling centres, and the parent's padding box.
 *
 * <pre>{@code
 * Snap.Result snapped = Snap.of(node, wantedLeft, wantedTop, 6f / zoom);
 * write(snapped.x(), snapped.y());
 * overlay.showGuides(snapped.guides());
 * }</pre>
 *
 * <p><b>The tolerance is in SCREEN pixels and divided by the zoom by the caller</b>, so snapping feels the
 * same distance at every zoom rather than becoming impossible when you are zoomed out and unavoidable
 * when you are in. Six screen pixels is Figma's and is close enough to a pointer's own accuracy.</p>
 *
 * <p>Each axis snaps independently: a box can be aligned to one sibling's left edge and another's centre
 * at once, which is the ordinary case and what makes the guides worth drawing.</p>
 */
public final class Snap {

    private Snap() {
    }

    /** Where the box should go, and the lines worth drawing for it. */
    public record Result(float x, float y, List<Guide> guides) {
    }

    /** One alignment that was taken, in the parent's coordinates. */
    public record Guide(boolean vertical, float at, float from, float to) {
    }

    /**
     * Snaps a proposed top-left against the node's siblings and its parent's box.
     *
     * @param tolerance how close counts, in the PARENT's units — screen pixels divided by the zoom
     */
    public static Result of(UIElement node, float x, float y, float tolerance) {
        Box box = node.box();
        UIElement parent = node.parentElement();
        Box parentBox = parent == null ? null : parent.box();
        if (box == null || parentBox == null) return new Result(x, y, List.of());

        float width = box.width();
        float height = box.height();
        List<Guide> guides = new ArrayList<>();

        // EACH AXIS KNOWS WHERE THE MOVING BOX SITS ON THE OTHER ONE, so a guide can be drawn from the
        // box to the thing it lined up with. @see Axis#consider
        Axis horizontal = new Axis(x, width, tolerance, y, y + height);
        Axis vertical = new Axis(y, height, tolerance, x, x + width);

        // The parent's content box first, so an edge-aligned child beats a sibling that happens to be
        // the same distance away -- "flush with the container" is the alignment somebody meant.
        horizontal.offer(parentBox.padding().left, 0f, parentBox.width());
        horizontal.offer(parentBox.width() - parentBox.padding().right, 0f, parentBox.width());
        vertical.offer(parentBox.padding().top, 0f, parentBox.height());
        vertical.offer(parentBox.height() - parentBox.padding().bottom, 0f, parentBox.height());

        for (UIElement sibling : parent.children()) {
            if (sibling == node) continue;
            Box other = sibling.box();
            if (other == null) continue;
            horizontal.offer(other.x(), other.y(), other.y() + other.height());
            horizontal.offer(other.x() + other.width(), other.y(), other.y() + other.height());
            horizontal.offerCentre(other.x() + other.width() * 0.5f,
                    other.y(), other.y() + other.height());
            vertical.offer(other.y(), other.x(), other.x() + other.width());
            vertical.offer(other.y() + other.height(), other.x(), other.x() + other.width());
            vertical.offerCentre(other.y() + other.height() * 0.5f,
                    other.x(), other.x() + other.width());
        }

        if (horizontal.taken()) guides.add(new Guide(true, horizontal.line, horizontal.from, horizontal.to));
        if (vertical.taken()) guides.add(new Guide(false, vertical.line, vertical.from, vertical.to));
        return new Result(horizontal.result(), vertical.result(), guides);
    }

    /**
     * One axis, keeping the closest alignment offered to it.
     *
     * <p>Three candidates per edge — the box's leading edge, its trailing edge and its centre — because a
     * designer aligning to a sibling means any of the three and does not want to think about which.</p>
     */
    private static final class Axis {

        private final float wanted;
        private final float extent;
        private final float tolerance;

        /** Where the MOVING box sits on the other axis. @see #consider */
        private final float crossFrom;
        private final float crossTo;

        private float best = Float.MAX_VALUE;
        private float adjusted;
        private float line;
        private float from;
        private float to;

        Axis(float wanted, float extent, float tolerance, float crossFrom, float crossTo) {
            this.crossFrom = crossFrom;
            this.crossTo = crossTo;
            this.wanted = wanted;
            this.extent = extent;
            this.tolerance = tolerance;
        }

        void offer(float edge, float spanFrom, float spanTo) {
            consider(edge, edge, spanFrom, spanTo);
            consider(edge - extent, edge, spanFrom, spanTo);
        }

        void offerCentre(float centre, float spanFrom, float spanTo) {
            consider(centre - extent * 0.5f, centre, spanFrom, spanTo);
        }

        /**
         * <b>The span reaches from the moving box to what it aligned to</b>, rather than covering only
         * the target.
         *
         * <p>A guide drawn over the target alone is a line with nothing on it: snap a box to a small
         * sibling on the far side of the canvas and you get a stub floating in space, and the only way
         * to know what it is a border OF is to go looking. Reaching across says it — one line touching
         * both things is the whole sentence, and it is what every editor with smart guides draws.</p>
         *
         * <p>Union rather than the target's span, because the target may sit inside the moving box's own
         * extent as easily as beyond it.</p>
         */
        private void consider(float candidate, float guideAt, float spanFrom, float spanTo) {
            float distance = Math.abs(candidate - wanted);
            if (distance > tolerance || distance >= best) return;
            best = distance;
            adjusted = candidate;
            line = guideAt;
            from = Math.min(spanFrom, crossFrom);
            to = Math.max(spanTo, crossTo);
        }

        boolean taken() {
            return best < Float.MAX_VALUE;
        }

        float result() {
            return taken() ? adjusted : wanted;
        }
    }

    /** The guides a caller should draw, or an empty list. Never null. */
    public static List<Guide> guidesOf(@Nullable Result result) {
        return result == null ? List.of() : result.guides();
    }
}
