package com.crystalgui.ui.elements.graph;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.canvas.WorldRect;
import org.joml.Vector2f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Paints every wire in the graph, plus the one currently being dragged.
 *
 * <h3>One layer, not an element per wire — a trade, made deliberately</h3>
 * <p>An element per wire would hand us {@code :hover} and {@code :checked} for free, and would cost a
 * Taffy node, a layout pass and a draw-call boundary <em>per edge</em>. One layer paints the lot in a
 * single {@code ctx.curve()} batch: a thousand-edge graph becomes a thousand instances in one flush
 * rather than a thousand elements. What it gives up is per-wire CSS state, which has to come back as
 * data this class holds. That is the right side of the trade for a graph editor, and it is written down
 * here so the absence reads as a decision rather than an oversight.</p>
 *
 * <h3>It sits at world (0,0) and is exempt from culling</h3>
 * <p>Two consequences, both load-bearing. Because the layer is positioned at the plane's origin, its own
 * {@code getX()} <em>is</em> the plane origin — so a port's plane-space coordinate minus that is world,
 * with no conversion helper and nothing to get out of step. And because a painter is not a node, it opts
 * out of the canvas's cull: culling tests an element's <em>box</em>, and this one's box says nothing
 * about where its wires are. Per-wire culling happens here instead, where the endpoints are known.</p>
 *
 * <h3>Wire colour comes from the cascade</h3>
 * <p>{@link NodePort#typeColor()} reads the port dot's computed {@code border-color}, so Unity's
 * per-type palette stays in a stylesheet even though {@code CgCurveRenderer} needs an ARGB int. A wire
 * between two different types is drawn as a gradient between them, which costs nothing (the instance
 * record already carries two colours) and makes a promotion visible as exactly what it is.</p>
 */
public class NodeWireLayer extends UIElement {

    /** Minimum horizontal pull on the control points, so a short wire still leaves its port sideways
     * rather than cutting the corner. Unity, Blender and Unreal all do this; a straight line between
     * two ports reads as a wire passing behind the node rather than into it. */
    private static final float MIN_TANGENT = 24f;

    /** Wires are drawn under nodes, so a stroke that clipped a node's corner would be hidden anyway —
     * but a wire whose endpoints are both off-screen can still cross the viewport, so the cull rect is
     * grown rather than tested exactly. */
    private static final float CULL_MARGIN = 64f;

    private final GraphView view;
    private final List<GraphConnection> connections;

    @Nullable
    private NodePort pendingFrom;
    private float pendingX, pendingY;
    private boolean pendingLive;

    NodeWireLayer(GraphView view, List<GraphConnection> connections) {
        this.view = view;
        this.connections = connections;
        // A painter, never a target: a wire under the cursor must not swallow a press meant for the
        // canvas underneath (marquee, in 6.2.4) or for a node above.
        setHitTest(false);
    }

    // ── The wire being dragged ──────────────────────────────────────────────

    void beginPending(NodePort from) {
        this.pendingFrom = from;
        this.pendingLive = false;
    }

    /** @param planeX pointer position in the plane's own space — what a {@code DragListener} on a port
     *                reports, and the space this layer paints in. */
    void updatePending(float planeX, float planeY) {
        this.pendingX = planeX;
        this.pendingY = planeY;
        this.pendingLive = pendingFrom != null;
    }

    void endPending() {
        this.pendingFrom = null;
        this.pendingLive = false;
    }

    // ── Picking ─────────────────────────────────────────────────────────────

    /** How close a click must land, in world units. Generous, because a 2px wire is a 2px target and a
     * pointer is not that accurate — the same reasoning that makes a port's padding its hit area. */
    private static final float PICK_TOLERANCE = 5f;

    /** Samples per wire when picking. */
    private static final int PICK_SAMPLES = 24;

    /**
     * The wire nearest {@code (worldX, worldY)} within {@link #PICK_TOLERANCE}, or {@code null}.
     *
     * <p><b>Sampled, not solved.</b> The exact answer is the cubic's closest-point parameter, which is a
     * quintic — the same reason {@code CgCurveRenderer}'s primitive is a quadratic rather than a cubic.
     * Twenty-four samples along a wire is well under a pixel apart at any zoom a user clicks at, costs
     * nothing at this scale, and cannot be subtly wrong the way a hand-rolled solver can. If a graph
     * ever has enough wires for this to matter, the fix is a broad-phase rejection by bounding box, not
     * a cleverer solve.</p>
     */
    @Nullable
    public GraphConnection pickWire(float worldX, float worldY) {
        float originX = 0f, originY = 0f;
        var cache = getRuntimeCache();
        originX = cache.getX();
        originY = cache.getY();

        // Divided by the zoom so the grab band is a constant thickness ON SCREEN. As a flat plane-space
        // value it shrank with the view: zoomed out, a wire drawn at its minimum width had a hit target
        // narrower than the line you can see, and zoomed in it was a wide invisible ribbon that stole
        // presses meant for the canvas. Same reasoning as getWireWidth's own screen-space clamp.
        GraphConnection best = null;
        float bestDistance = PICK_TOLERANCE / Math.max(1e-4f, view.getZoom());
        for (GraphConnection connection : connections) {
            Vector2f a = connection.from().dotCenter();
            Vector2f b = connection.to().dotCenter();
            float distance = distanceToWire(worldX + originX, worldY + originY, a, b);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = connection;
            }
        }
        return best;
    }

    /**
     * Closest approach of the drawn cubic to a point. Plane-space coordinates.
     *
     * <p><b>Distance to the SEGMENTS between samples, not to the samples.</b> Measuring to the sample
     * points makes the tolerance depend on how far apart they are: on a 600-unit wire the 24 samples sit
     * ~25 units apart, so a point exactly on the curve halfway between two of them is ~12 units from the
     * nearest — well past a 5-unit tolerance. The result is evenly-spaced dead spots where a wire
     * visibly under the cursor cannot be hit, which reads as random flakiness rather than as a sampling
     * artefact.</p>
     *
     * <p>Against the segments, the only error left is the sagitta — how far the true curve bows away
     * from each chord — which is sub-pixel at this sample count and, unlike the gap, does not grow with
     * the length of the wire.</p>
     */
    private static float distanceToWire(float px, float py, Vector2f a, Vector2f b) {
        float pull = Math.max(MIN_TANGENT, Math.abs(b.x() - a.x()) * 0.5f);
        float c1x = a.x() + pull, c1y = a.y();
        float c2x = b.x() - pull, c2y = b.y();

        float best = Float.MAX_VALUE;
        float prevX = a.x(), prevY = a.y();
        for (int i = 1; i <= PICK_SAMPLES; i++) {
            float t = i / (float) PICK_SAMPLES;
            float u = 1f - t;
            float x = u * u * u * a.x() + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * b.x();
            float y = u * u * u * a.y() + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * b.y();
            best = Math.min(best, distanceSqToSegment(px, py, prevX, prevY, x, y));
            prevX = x;
            prevY = y;
        }
        return (float) Math.sqrt(best);
    }

    /** Squared distance from a point to a line segment — the projection clamped to the segment. */
    private static float distanceSqToSegment(float px, float py,
                                             float x0, float y0, float x1, float y1) {
        float vx = x1 - x0, vy = y1 - y0;
        float wx = px - x0, wy = py - y0;
        float lengthSq = vx * vx + vy * vy;
        // A degenerate segment (two coincident samples) collapses to its start point rather than
        // dividing by zero — which happens for real on a wire whose ends are at the same place.
        float t = lengthSq <= 1e-6f ? 0f : Math.max(0f, Math.min(1f, (wx * vx + wy * vy) / lengthSq));
        float dx = wx - t * vx, dy = wy - t * vy;
        return dx * dx + dy * dy;
    }

    // ── Paint ───────────────────────────────────────────────────────────────

    @Override
    protected void paintSelf(CgUiPaintContext ctx) {
        super.paintSelf(ctx);
        if (connections.isEmpty() && !pendingLive) return;

        // The layer sits at world (0,0), so its own origin is the plane origin — this is what turns a
        // plane-space coordinate into a world one for the cull test below.
        float ox = getRuntimeCache().getX(), oy = getRuntimeCache().getY();
        WorldRect visible = view.visibleWorldRect().expand(CULL_MARGIN);

        for (GraphConnection connection : connections) {
            Vector2f a = connection.from().dotCenter();
            Vector2f b = connection.to().dotCenter();
            if (!isVisible(a, b, ox, oy, visible)) continue;
            boolean selected = connection.equals(view.getSelection().wire());
            // Thicker for BOTH, recoloured for selection only — the two states have to stay tellable
            // apart, and it is the split every editor uses: hover says "you would hit this one", which
            // is about aim, while selection says "this one is the subject of your next command".
            //
            // These are the only affordances a wire has. It cannot carry a border, a :hover or a
            // :checked rule, being painted rather than laid out.
            boolean hovered = connection.equals(view.getHoveredWire());
            int colorA = selected ? SELECTED_WIRE_COLOR : connection.from().typeColor();
            int colorB = selected ? SELECTED_WIRE_COLOR : connection.to().typeColor();
            wire(ctx, a.x(), a.y(), b.x(), b.y(), colorA, colorB, selected || hovered);
        }

        if (pendingLive && pendingFrom != null) {
            Vector2f a = pendingFrom.dotCenter();
            int color = pendingFrom.typeColor();
            // Drawn from the port toward the pointer regardless of which direction the port is, so a
            // drag started from an input still reads as a wire being pulled out of it.
            wire(ctx, a.x(), a.y(), pendingX, pendingY, color, color, false);
        }

        ctx.flush();
    }

    /** AABB of the two endpoints against the visible slice, both in world space. Conservative on
     * purpose: the curve bulges past its endpoints by up to the tangent length, which the margin
     * covers, and drawing one wire too many is invisible while dropping one is not. */
    private boolean isVisible(Vector2f a, Vector2f b, float originX, float originY, WorldRect visible) {
        float x0 = Math.min(a.x(), b.x()) - originX, x1 = Math.max(a.x(), b.x()) - originX;
        float y0 = Math.min(a.y(), b.y()) - originY, y1 = Math.max(a.y(), b.y()) - originY;
        return visible.intersects(new WorldRect(x0, y0, x1 - x0, y1 - y0));
    }

    /**
     * One wire: a cubic with horizontal tangents, split into quadratics by {@code CgCurveRenderer}.
     *
     * <p>Horizontal tangents are the whole visual idiom of a node editor — a wire must leave an output
     * to the right and enter an input from the left, so that a backwards connection loops visibly
     * instead of drawing a straight diagonal that looks like a mistake.</p>
     */
    private void wire(CgUiPaintContext ctx, float x0, float y0, float x1, float y1,
                      int color0, int color1, boolean emphasised) {
        float pull = Math.max(MIN_TANGENT, Math.abs(x1 - x0) * 0.5f);
        ctx.curve()
                .cubic(x0, y0, x0 + pull, y0, x1 - pull, y1, x1, y1)
                // Exactly double, so hover reads as "the same wire, thicker" — Unity's own pair is a
                // hairline and twice a hairline.
                .width(view.getWireWidth() * (emphasised ? 2f : 1f))
                .colors(color0, color1)
                .submit();
    }

    /**
     * The selection accent — the same {@code #44C0FF} a selected node is ringed in, and Unity's own
     * colour for a selected wire.
     *
     * <p>It was near-white, on the theory that white is legible against every type colour in the palette.
     * True, and beside the point: white does not <em>mean</em> anything here, while this blue already
     * means "selected" everywhere else in the editor. A selected wire and a selected node should not be
     * announcing the same state in two different colours.</p>
     *
     * <p>Hard-coded rather than read from the cascade, unlike {@link NodePort#typeColor()}: that one is
     * read back out of CSS because the per-type palette genuinely belongs to the theme. This is one
     * fixed accent, and giving it a stylesheet hook would imply the other twelve wire states have one
     * too.</p>
     */
    private static final int SELECTED_WIRE_COLOR = 0xFF44C0FF;
}
