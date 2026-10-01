package com.crystalgui.widget.surface.snap;

import java.util.List;

import com.crystalgui.render.CgUiPaintContext;

/**
 * <b>Draws what a snap landed on</b> — the one place a guide's look is decided, for every gesture that
 * snaps.
 *
 * <pre>{@code
 * float[] area = CanvasRects.ofLayout(parent, overlay);   // the solver's (0, 0), on screen
 * SnapIndicators.paint(paint, snap.indicators(), area[0], area[1], zoom, colour, faint, 0.5f);
 * }</pre>
 *
 * <p>A {@link SnapIndicator.Points} is a line from its first point to its last with a × at each, as
 * Excalidraw draws it; a {@link SnapIndicator.Gap} is a bar with a cap at each end; a
 * {@link SnapIndicator.Owner} is an outline in the fainter colour, under the rest. Marks and caps are in
 * screen pixels, so they read the same at any zoom. Allocates nothing.</p>
 */
public final class SnapIndicators {

    /** Half the width of a point's ×, in screen pixels. */
    private static final float MARK = 3f;

    /** Half the length of a gap bar's end cap, in screen pixels. */
    private static final float CAP = 4f;

    private SnapIndicators() {
    }

    /**
     * @param originX     where the solver's (0, 0) is on screen
     * @param scale       solver units to screen pixels
     * @param ownerColour what an {@link SnapIndicator.Owner} is outlined in — fainter than {@code colour}
     * @param strokeWidth handed to {@code curve().width(...)}
     */
    public static void paint(CgUiPaintContext paint, List<SnapIndicator> indicators, float originX,
                             float originY, float scale, int colour, int ownerColour, float strokeWidth) {
        // Owners first, so the lines and their marks are drawn over them.
        for (int i = 0; i < indicators.size(); i++) {
            if (indicators.get(i) instanceof SnapIndicator.Owner owner) {
                owner(paint, owner, originX, originY, scale, ownerColour, strokeWidth);
            }
        }
        for (int i = 0; i < indicators.size(); i++) {
            SnapIndicator indicator = indicators.get(i);
            if (indicator instanceof SnapIndicator.Points points) {
                points(paint, points, originX, originY, scale, colour, strokeWidth);
            } else if (indicator instanceof SnapIndicator.Gap gap) {
                gap(paint, gap, originX, originY, scale, colour, strokeWidth);
            }
        }
    }

    private static void points(CgUiPaintContext paint, SnapIndicator.Points points,
                               float ox, float oy, float scale, int colour, float width) {
        float[] crosses = points.crosses();
        if (crosses.length == 0) return;
        float at = points.at() * scale;
        float lo = crosses[0] * scale;
        float hi = crosses[crosses.length - 1] * scale;
        boolean vertical = points.axis() == SnapAxis.HORIZONTAL;
        if (vertical) line(paint, ox + at, oy + lo, ox + at, oy + hi, colour, width);
        else line(paint, ox + lo, oy + at, ox + hi, oy + at, colour, width);
        for (float cross : crosses) {
            float x = vertical ? ox + at : ox + cross * scale;
            float y = vertical ? oy + cross * scale : oy + at;
            line(paint, x - MARK, y - MARK, x + MARK, y + MARK, colour, width);
            line(paint, x - MARK, y + MARK, x + MARK, y - MARK, colour, width);
        }
    }

    private static void owner(CgUiPaintContext paint, SnapIndicator.Owner owner,
                              float ox, float oy, float scale, int colour, float width) {
        float[] xs = owner.xs();
        float[] ys = owner.ys();
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            line(paint, ox + xs[i] * scale, oy + ys[i] * scale, ox + xs[next] * scale, oy + ys[next] * scale,
                    colour, width);
        }
    }

    private static void gap(CgUiPaintContext paint, SnapIndicator.Gap gap,
                            float ox, float oy, float scale, int colour, float width) {
        float from = gap.from() * scale;
        float to = gap.to() * scale;
        float cross = gap.cross() * scale;
        if (gap.axis() == SnapAxis.HORIZONTAL) {
            line(paint, ox + from, oy + cross, ox + to, oy + cross, colour, width);
            line(paint, ox + from, oy + cross - CAP, ox + from, oy + cross + CAP, colour, width);
            line(paint, ox + to, oy + cross - CAP, ox + to, oy + cross + CAP, colour, width);
        } else {
            line(paint, ox + cross, oy + from, ox + cross, oy + to, colour, width);
            line(paint, ox + cross - CAP, oy + from, ox + cross + CAP, oy + from, colour, width);
            line(paint, ox + cross - CAP, oy + to, ox + cross + CAP, oy + to, colour, width);
        }
    }

    private static void line(CgUiPaintContext paint, float x0, float y0, float x1, float y1,
                             int colour, float width) {
        if (Math.abs(x1 - x0) < 0.01f && Math.abs(y1 - y0) < 0.01f) return;
        paint.curve().line(x0, y0, x1, y1).width(width).color(colour).submit();
    }
}
