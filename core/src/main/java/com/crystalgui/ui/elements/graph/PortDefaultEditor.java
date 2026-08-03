package com.crystalgui.ui.elements.graph;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.canvas.CanvasView;
import com.crystalgui.ui.elements.config.control.NumberControl;
import com.crystalgui.ui.elements.config.control.VectorControl;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.joml.Vector2f;

/**
 * Unity's floating {@code X [0] •} — the box-and-dot pair {@link GraphView} places beside an
 * unconnected input, wrapping whatever {@link NodePort#getDefaultEditor()} returns. One instance per
 * port, built once and reused across every connect/disconnect for that port's lifetime; {@link GraphView}
 * owns the map, the discovery scan and the tick-driven repositioning, but everything about what this
 * widget IS — its two elements, their geometry, their look, and how the connecting stub gets drawn —
 * lives here.
 *
 * <h3>Two elements, deliberately never parent and child</h3>
 * <p>{@link #box} is the rounded, panel-toned frame holding the axis label and the bare control.
 * {@link #dot} is the small ringed mark that visually overlaps the box's trailing edge. They read as one
 * widget and are mounted, moved and z-ordered together by every method here, but they are not nested —
 * {@code dot} is never a child of {@code box}. A flex child's on-screen position is at the mercy of
 * whatever its container measures as its own content width, and that width is exactly what
 * {@link #reposition} anchors the box's own world position off; pulling the dot onto the box with a
 * flex margin therefore does not move the dot relative to the box, it changes what the box measures
 * itself as — which moves the box's own left edge, dot riding along with it. Positioning both
 * independently, straight off each other's live {@code RuntimeCache}, has no such coupling.</p>
 *
 * <h3>The dot is a target, drawn dark to coloured, not coloured to dark</h3>
 * <p>Three concentric layers: {@link #dot} itself is a static dark backdrop (the same tone as a node's
 * own border), holding a static neutral-grey ring, holding {@link #core} — the port's own type colour,
 * refreshed every {@link #reposition()} call. Colour at the centre, not the edge, is what stops the mark
 * reading as a flat coloured disc with no relation to the port it belongs to; a hollow-looking edge is
 * the same "this is not really connected" language {@link NodePort}'s own unconnected dot already
 * speaks with its transparent fill.</p>
 *
 * <h3>The connecting stub is not painted by this class</h3>
 * <p>Unity draws that line over a node's body but under its selection ring — sandwiched between two
 * steps of the SAME node's own atomic paint call ({@code paintSelf}, children, {@code paintOverlay},
 * THEN {@code paintOutline}), which no sibling element can land inside of no matter how it is z-ordered.
 * {@link #paintStub} exists to be called from {@link GraphNode#paintOverlay} — the target node drawing
 * its own incoming stub, at exactly the point in its own paint sequence that sits after its children and
 * before its ring. {@link #box} and {@link #dot} still need an explicit z ({@link #WIDGET_Z}) above any
 * node's, raised or not — see that constant's own note — but the stub itself never does.</p>
 */
final class PortDefaultEditor {

    /** World-space gap between the dot's centre and the real port's own dot — the same every zoom, like
     * every other plane-space measurement in this view. Wide enough for the stub to read as a real line
     * between two distinct dots rather than two dots touching with nothing visible between them. */
    private static final float GAP = 16f;

    /** How far LEFT of the box's own right edge the dot's centre sits — how much of the dot visually
     * overlaps the box, Unity's own "the dot is part of the widget" look rather than a mark floating in
     * open canvas beside it. */
    private static final float DOT_OVERLAP = 7f;

    /**
     * The z-index {@link #box} and {@link #dot} paint at — always above any node, however many times it
     * has been {@link GraphView#raise raised}.
     *
     * <p>Safe in a way a z-index on the STUB never could be: neither the box nor the dot ever overlaps a
     * node's rounded border or its selection ring (both sit outside the node entirely, beside the port),
     * so there is no "must lose to the ring" constraint here — see {@link #paintStub}'s own note on why
     * the stub is drawn from inside the target node's paint instead of given a z-index at all. What DOES
     * need one is this: the stub is part of that node's own atomic paint (body, children, the embedded
     * stub, then the ring — all one unit), so the instant {@code raise()} lifts that whole unit above
     * z:0, an un-raised box/dot would lose to it too — the wire would start drawing OVER the widget the
     * moment the NODE IT POINTS AT got selected, regardless of which node that is. A fixed high z keeps
     * the box and dot above every node's entire draw regardless of raise state, so the only stacking
     * question left is ever "stub vs. that one node's own ring" — never "stub vs. this widget".</p>
     */
    private static final int WIDGET_Z = 1_000_000;

    private final NodePort port;
    private final GraphView view;
    private final UIElement control;
    private final UIElement box;
    private final UIElement dot;
    private final UIElement core;
    private boolean mounted;

    /** Builds the box and the dot around {@code port.getDefaultEditor()}. Callers must not construct one
     * for a port whose {@link NodePort#getDefaultEditor()} is {@code null} — {@link GraphView}'s
     * discovery scan already guards this. */
    PortDefaultEditor(NodePort port, GraphView view) {
        this.port = port;
        this.view = view;
        // Owned here, not just passed through to box.addInternalChild below and forgotten — this is the
        // thing NodePort.getDefaultEditor() actually returns, and code that needs to reach it (the
        // update-mode setup right below, a test, a future caller) should ask THIS class for it rather
        // than dig back through box.getChildren(), which would also hand back the internal-only label.
        this.control = port.getDefaultEditor();

        // Live, not on-commit: a port default is a value you drag/scrub as much as type, and Unity's own
        // fields update the preview on every keystroke rather than waiting for Enter or a blur. Only the
        // two kinds that actually contain a plain number field get this — NumberControl directly, or
        // VectorControl's own per-axis NumberControls — ColorControl and BooleanControl have no number
        // text field to set a mode on at all.
        if (control instanceof NumberControl number) {
            number.field().setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        } else if (control instanceof VectorControl vector) {
            for (NumberControl component : vector.components()) {
                component.field().setUpdateMode(TextField.UpdateMode.IMMEDIATE);
            }
        }

        box = new UIElement();
        box.addClass(NodePort.EDITOR_CLASS);
        UIText label = new UIText(port.getPortId());
        label.addClass(NodePort.EDITOR_LABEL_CLASS);
        label.setHitTest(false);
        box.addInternalChild(label);
        box.addInternalChild(control);

        core = new UIElement();
        core.addClass(NodePort.EDITOR_DOT_CORE_CLASS);
        core.setHitTest(false);
        UIElement ring = new UIElement();
        ring.addClass(NodePort.EDITOR_DOT_RING_CLASS);
        ring.setHitTest(false);
        ring.addInternalChild(core);
        dot = new UIElement();
        dot.addClass(NodePort.EDITOR_DOT_CLASS);
        dot.setHitTest(false);
        dot.addInternalChild(ring);
    }

    NodePort port() {
        return port;
    }

    /** The control itself — the same instance {@link NodePort#getDefaultEditor()} returns, exposed for
     * tests and for any future caller that needs to reach it directly instead of through the port. */
    UIElement control() {
        return control;
    }

    /** The dot element — exposed only for {@code isInsideNode}-style ancestor checks and tests; nothing
     * outside this class should reposition or restyle it directly. */
    UIElement dot() {
        return dot;
    }

    boolean isMounted() {
        return mounted;
    }

    /**
     * Puts the box and dot on the plane, or takes both off — Unity's field appearing and disappearing
     * with the wire. Idempotent.
     *
     * <p><b>Mounted as INTERNAL children, never public ones.</b> Every {@code ConfigControl} —
     * {@code NumberControl} among them — calls {@code markAsInternal()} on itself in its own
     * constructor, on the standing assumption that whatever host places it will always use
     * {@code addInternalChild}. That flag propagates upward to {@link #box} the moment its constructor
     * runs {@code box.addInternalChild(control)}, so mounting the box the same way the control itself
     * demands is not optional: {@code content().addChild} still succeeds (the public API does not check
     * the CHILD's own flag, only the caller's), but {@code content().removeChild} silently refuses —
     * internal children are excluded from the public removal API by design — so the box would mount once
     * and never come off the plane again.</p>
     */
    void setMounted(boolean value) {
        if (mounted == value) return;
        mounted = value;
        UIElement content = view.content();
        if (value) {
            StyleGroup.defaultPipeline(box.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            StyleGroup.defaultPipeline(dot.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            StyleGroup.importantPipeline(box.getStyle().getGeneralGroup(), g -> g.zIndex(WIDGET_Z));
            StyleGroup.importantPipeline(dot.getStyle().getGeneralGroup(), g -> g.zIndex(WIDGET_Z));
            content.addInternalChild(box);
            content.addInternalChild(dot);
            // Laid out for real now that both are in the tree — reposition()'s own width/height reads
            // would still see the stale (pre-mount) runtime cache otherwise, same reasoning as
            // GraphNode's own first-frame settle.
            reposition();
        } else {
            content.removeInternalChild(box);
            content.removeInternalChild(dot);
        }
    }

    /**
     * Places {@link #box} so its right edge sits {@link #GAP} plus {@link #DOT_OVERLAP} short of the
     * port's own dot, then places {@link #dot} independently so its centre sits {@link #DOT_OVERLAP}
     * inside that same right edge — overlapping the box. Also refreshes {@link #core}'s colour to the
     * port's current type every call, the same "read it back out of the cascade" idiom
     * {@link NodePort#typeColor()} exists for, so a dynamic port recolours the instant the compiler
     * resolves a concrete type for it, with no separate invalidation to wire up. No-op-safe to call
     * whether or not {@link #isMounted()} — callers only do so while mounted, but nothing here assumes it.
     *
     * <p><b>{@link NodePort#dotCenter()} is not itself a world coordinate — it is a raw layout position,
     * accumulated through every ancestor down to the dot, exactly like {@link CanvasView#worldBoundsOf}
     * reads for a node's own bounds and exactly why that method subtracts the plane's own origin before
     * calling the result "world".</b> {@code moveNode}'s {@code left}/{@code top}, by contrast, ARE world
     * coordinates — that is the whole contract {@code CanvasView.addNode}'s javadoc states. Feeding
     * {@code dotCenter()} straight into {@code moveNode} without that same subtraction adds the plane's
     * own on-screen origin a second time, which is invisible at world origin (0, 0) — where a hastily
     * written test would place its node — and pushes the editor an entire panel-width off to the side
     * the moment a real graph node sits anywhere else.</p>
     */
    void reposition() {
        var contentCache = view.content().getRuntimeCache();
        Vector2f portDot = port.dotCenter();
        float dotWorldX = portDot.x() - contentCache.getX();
        float dotWorldY = portDot.y() - contentCache.getY();

        var boxCache = box.getRuntimeCache();
        float boxX = dotWorldX - GAP - boxCache.getWidth();
        float boxY = dotWorldY - boxCache.getHeight() * 0.5f;
        view.moveNode(box, boxX, boxY);

        var dotCache = dot.getRuntimeCache();
        float boxRightEdge = boxX + boxCache.getWidth();
        float dotX = boxRightEdge - DOT_OVERLAP - dotCache.getWidth() * 0.5f;
        float dotY = dotWorldY - dotCache.getHeight() * 0.5f;
        view.moveNode(dot, dotX, dotY);

        StyleGroup.importantPipeline(core.getStyle().getGeneralGroup(), g -> g.backgroundColor(port.typeColor()));
    }

    /**
     * Draws the stub joining {@link #dot} to the real port — called from {@link GraphNode#paintOverlay},
     * never from this class's own paint and never from a plane sibling. {@code paintOverlay} is the only
     * hook that runs after a node's own children and before its own outline, which is what makes "over
     * the body, under the ring" possible at all; see {@link #WIDGET_Z}'s own note for the other half of
     * this split (why the WIDGET still needs an explicit z even though the STUB must not have one).
     *
     * <p>A plain segment, not a real wire's bezier: the gap this spans is {@link #GAP}, a handful of
     * pixels, and a bezier with {@code NodeWireLayer}'s own 24-unit minimum tangent pull over a span
     * that short would bulge into a visible loop rather than read as a nub. A cubic whose control points
     * equal its endpoints degenerates to a straight line, so this reuses {@code ctx.curve()} rather than
     * inventing a second draw path.</p>
     *
     * <p>Both ends trim inward by their own dot's live radius ({@link NodePort#dotRadius()}, and this
     * dot's own {@code cache.getWidth() * 0.5f}) — same reasoning as {@link NodeWireLayer#wire}: {@link
     * #dot} and the real port's dot sit at the same Y (see {@link #reposition}), so the segment is
     * already purely horizontal and the trim is exact, not an approximation.</p>
     */
    void paintStub(CgUiPaintContext ctx) {
        var cache = dot.getRuntimeCache();
        // The dot has not been laid out yet on the very first frame after mounting — see setMounted —
        // and a zero-size box would draw a stub from nowhere to itself. Invisible either way, but
        // skipped rather than submitted as a degenerate draw call.
        if (cache.getWidth() <= 0f || cache.getHeight() <= 0f) return;
        float radius0 = cache.getWidth() * 0.5f;
        float centerX = cache.getX() + radius0, y0 = cache.getY() + cache.getHeight() * 0.5f;
        float x0 = centerX + radius0; // trimmed forward, off this dot's own edge
        Vector2f target = port.dotCenter();
        float x1 = target.x() - port.dotRadius();
        int color = port.typeColor();
        ctx.curve()
                .cubic(x0, y0, x0, y0, x1, target.y(), x1, target.y())
                .width(view.getWireWidth())
                // Same zoom-floored ramp NodeWireLayer's own wires use — see GraphView.getWireFeather's
                // own note for why the ramp stays a constant screen width while the wire's own width
                // keeps shrinking.
                .feather(view.getWireFeather())
                .colors(color, color)
                .submit();
        ctx.flush();
    }
}
