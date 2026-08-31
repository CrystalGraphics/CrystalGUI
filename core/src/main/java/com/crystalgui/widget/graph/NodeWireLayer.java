package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.box.Box;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.canvas.WorldRect;
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
 * per-type palette stays in a stylesheet even though {@code CgVectorRenderer} needs an ARGB int. A wire
 * between two different types is drawn as a gradient between them, which costs nothing (the instance
 * record already carries two colours) and makes a promotion visible as exactly what it is.</p>
 */
public class NodeWireLayer extends UINode {

    /**
     * This layer's kind.
     *
     * <p>No sheet names {@code nodewirelayer} — the wires take their width, feather and colour from
     * the view that owns them — so this exists for the rule rather than for the cascade: a concrete
     * node that declares no {@code NAME} INHERITS one, reports {@code crystalgui:element}, and is
     * indistinguishable from a widget that forgot. Declaring it is how this one says it did not.</p>
     */
    public static final Name NAME = Name.of("nodewirelayer");

    /**
     * An unattached layer: no view, so nothing to draw and nothing to pick.
     *
     * <p>What {@code UINodeRegistry} builds. A wire layer belongs to a {@link GraphView} and is made
     * by one; this is the inert form every other registered-but-{@code INERT} widget also has, and
     * the two guards it needs are on {@link #pick} and {@link #paintContent}.</p>
     */
    public NodeWireLayer() {
        super(NAME);
        this.view = null;
        this.connections = List.of();
    }

    /** Minimum horizontal pull on the control points, so a short wire still leaves its port sideways
     * rather than cutting the corner. Unity, Blender and Unreal all do this; a straight line between
     * two ports reads as a wire passing behind the node rather than into it. */
    private static final float MIN_TANGENT = 24f;


    /** Wires are drawn under nodes, so a stroke that clipped a node's corner would be hidden anyway —
     * but a wire whose endpoints are both off-screen can still cross the viewport, so the cull rect is
     * grown rather than tested exactly. */
    private static final float CULL_MARGIN = 64f;

    @Nullable
    private final GraphView view;
    private final List<GraphConnection> connections;

    @Nullable
    private NodePort pendingFrom;
    private float pendingX, pendingY;
    private boolean pendingLive;

    NodeWireLayer(GraphView view, List<GraphConnection> connections) {
        super(NAME);
        this.view = view;
        this.connections = connections;
        // The hook the selection accent is read through -- see selectedWireColor().
        addClass("__wire-layer__");
        // A painter, never a target: a wire under the cursor must not swallow a press meant for the
        // canvas underneath (marquee, in 6.2.4) or for a node above.
        setHitTest(false);
    }

    // ── The wire being dragged ──────────────────────────────────────────────

    void beginPending(NodePort from) {
        this.pendingFrom = from;
        this.pendingLive = false;
    }

    /**
     * @param planeX pointer position in the plane's own space, which is what this layer paints in.
     *               <b>NOT what a drag callback reports</b> — those are relative to the drag's SOURCE
     *               and, since M6.1, to that source's own origin. {@code NodePort.pointerInPlane} is
     *               the conversion; this used to say a listener reported plane coordinates directly,
     *               which was true of the old engine and drew the live wire a node's width away here.
     */
    void updatePending(float planeX, float planeY) {
        this.pendingX = planeX;
        this.pendingY = planeY;
        this.pendingLive = pendingFrom != null;
    }

    /**
     * Where the wire being dragged currently ends, in the plane's own space.
     *
     * <p>The only observable this has: a live wire is PAINTED rather than laid out, so nothing about
     * it is reachable through a box or a style. Without it the pointer end can only be checked by
     * eye, which is how it shipped drawn a node's width from the cursor.</p>
     */
    public Vector2f pendingEnd() {
        return new Vector2f(pendingX, pendingY);
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
     * quintic — the same reason {@code CgVectorRenderer}'s primitive is a quadratic rather than a cubic.
     * Twenty-four samples along a wire is well under a pixel apart at any zoom a user clicks at, costs
     * nothing at this scale, and cannot be subtly wrong the way a hand-rolled solver can. If a graph
     * ever has enough wires for this to matter, the fix is a broad-phase rejection by bounding box, not
     * a cleverer solve.</p>
     */
    @Nullable
    public GraphConnection pickWire(float worldX, float worldY) {
        float originX = 0f, originY = 0f;
        Box cache = box();
        originX = cache.x();
        originY = cache.y();

        // Divided by the zoom so the grab band is a constant thickness ON SCREEN. As a flat plane-space
        // value it shrank with the view: zoomed out, a wire drawn at its minimum width had a hit target
        // narrower than the line you can see, and zoomed in it was a wide invisible ribbon that stole
        // presses meant for the canvas. Same reasoning as getWireWidth's own screen-space clamp.
        GraphConnection best = null;
        // An unattached layer picks nothing -- see the no-arg constructor.
        if (view == null) return null;
        float bestDistance = PICK_TOLERANCE / Math.max(1e-4f, view.getZoom());
        for (GraphConnection connection : connections) {
            Vector2f a = connection.from().dotCenterIn(this);
            Vector2f b = connection.to().dotCenterIn(this);
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
    public void paintContent(CgUiPaintContext ctx, Box box) {
        super.paintContent(ctx, box);
        if (connections.isEmpty() && !pendingLive) return;

        // ZERO, because `dotCenterIn(this)` already answers in this layer's own space and
        // `BoxPainter` poses every box at its own origin. This used to add the layer's own `x`/`y`,
        // which was right when a dot's centre was an ABSOLUTE layout coordinate and is a double
        // offset now that it is not. Kept as named locals rather than deleted: the cull test below
        // reads them, and the plane-to-world shift they represent is the thing that changed.
        float ox = 0f, oy = 0f;
        // ...and draws nothing.
        if (view == null) return;
        WorldRect visible = view.visibleWorldRect().expand(CULL_MARGIN);

        for (GraphConnection connection : connections) {
            Vector2f a = connection.from().dotCenterIn(this);
            Vector2f b = connection.to().dotCenterIn(this);
            if (!isVisible(a, b, ox, oy, visible)) continue;
            boolean selected = connection.equals(view.getSelection().wire());
            // Thicker for BOTH, recoloured for selection only — the two states have to stay tellable
            // apart, and it is the split every editor uses: hover says "you would hit this one", which
            // is about aim, while selection says "this one is the subject of your next command".
            //
            // These are the only affordances a wire has. It cannot carry a border, a :hover or a
            // :checked rule, being painted rather than laid out.
            boolean hovered = connection.equals(view.getHoveredWire());
            int selectedColor = selectedWireColor();
            int colorA = selected ? selectedColor : connection.from().typeColor();
            int colorB = selected ? selectedColor : connection.to().typeColor();
            wire(ctx, a.x(), a.y(), b.x(), b.y(), connection.from().dotRadius(), connection.to().dotRadius(),
                    colorA, colorB, selected || hovered);
        }

        if (pendingLive && pendingFrom != null) {
            Vector2f a = pendingFrom.dotCenterIn(this);
            int color = pendingFrom.typeColor();
            // Drawn from the port toward the pointer regardless of which direction the port is, so a
            // drag started from an input still reads as a wire being pulled out of it. The pointer end
            // trims by 0 — there is no dot there to stop short of.
            wire(ctx, a.x(), a.y(), pendingX, pendingY, pendingFrom.dotRadius(), 0f, color, color, false);
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
     * One wire: a cubic with horizontal tangents, split into quadratics by {@code CgVectorRenderer}.
     *
     * <p>Horizontal tangents are the whole visual idiom of a node editor — a wire must leave an output
     * to the right and enter an input from the left, so that a backwards connection loops visibly
     * instead of drawing a straight diagonal that looks like a mistake.</p>
     *
     * <h3>Endpoints stop at the dot's edge, not its centre</h3>
     * <p>{@code radius0}/{@code radius1} trim {@code x0}/{@code x1} inward before the curve is built, so
     * the line ends at the ring's outer edge — Unity's own construction (see the reference this was
     * checked against) — rather than running under the ring into the hole. Trimming along X alone, not
     * toward the other endpoint, is exact rather than an approximation: the cubic's tangent at t=0 is
     * {@code (P1-P0) = (pull, 0)} and at t=1 is {@code (P3-P2) = (pull, 0)} — both purely horizontal by
     * construction — so shrinking {@code x0} forward and {@code x1} backward by a dot's radius moves each
     * endpoint exactly along the curve's own initial/final direction, however the two ports sit
     * vertically. {@code pull} is recomputed from the ALREADY-trimmed span, matching what the curve
     * would have used from the true endpoints closely enough that a several-pixel trim on a
     * `MIN_TANGENT`-or-wider curve is not worth a second computation.</p>
     */
    private void wire(CgUiPaintContext ctx, float x0, float y0, float x1, float y1,
                      float radius0, float radius1, int color0, int color1, boolean emphasised) {
        x0 += radius0;
        x1 -= radius1;
        float pull = Math.max(MIN_TANGENT, Math.abs(x1 - x0) * 0.5f);
        ctx.curve()
                .cubic(x0, y0, x0 + pull, y0, x1 - pull, y1, x1, y1)
                // Exactly double, so hover reads as "the same wire, thicker" — Unity's own pair is a
                // hairline and twice a hairline.
                .width(view.getWireWidth() * (emphasised ? 2f : 1f))
                .feather(view.getWireFeather())
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
     * <p><b>Read from the cascade, like {@link NodePort#typeColor()}</b> — and it used to be a
     * hard-coded constant, on the argument that one fixed accent needs no stylesheet hook. Theming
     * broke that argument: the node's ring is {@code var(--graph-selection-ring)} now, so a theme
     * that moves it would leave a constant-coloured wire announcing selection in a different colour
     * than the ring — the exact mismatch this colour exists to avoid. The hook is
     * {@code selection-color} on this layer's own {@code .__wire-layer__} rule in graph.css, which
     * routes through the same token; the fallback covers a graph running without its sheet.</p>
     */
    private int selectedWireColor() {
        return getStyle().getGeneralGroup()
                .getValue(StylePropertyRegistry.SELECTION_COLOR)
                .orElse(0xFF44C0FF);
    }
}
