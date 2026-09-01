package com.crystalgui.widget.graph;

import com.crystalgui.ui.box.Box;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.config.control.VectorControl;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * Unity's floating {@code X [0] •} — the box-and-dot pair {@link GraphView} places beside an
 * unconnected input, wrapping whatever {@link NodePort#getDefaultEditor()} returns. One instance per
 * port, built once and kept for that port's whole lifetime; {@link GraphView} owns the map, the
 * discovery scan and the tick-driven repositioning, but everything about what this widget IS — its two
 * elements, their geometry, their look, and how the connecting stub gets drawn — lives here.
 *
 * <h3>One instance per port, and the control is swapped IN PLACE</h3>
 * <p>{@link #syncControl()} is what makes that possible, and it is not a convenience: a port's control
 * legitimately arrives <em>after</em> this widget exists. {@code NodeFieldBinder} binds a
 * document-declared field on whatever tick the owning node is first seen, which for a node added from
 * the create menu is a different tick — and a different {@code Animation.Hook} — from the one
 * {@link GraphView#tickFrame} discovers the port on. Which runs first is a coin flip.</p>
 *
 * <p>The previous design rebuilt the whole {@code PortDefaultEditor} on every such change. That is what
 * made a vector editor render its X/Y boxes hundreds of pixels away from its own frame: the new box
 * adopted a control the old box still held, and reparenting an <b>internal</b> child used to be a silent
 * no-op (see {@code UINode.addChildAtInternal}), so both boxes claimed it — Taffy laid it out under
 * both and the wrong pass won. Swapping in place means there is only ever one owner, and
 * {@link #detachControl} makes the hand-off explicit rather than leaving it to a reparent.</p>
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
 * {@link #paintStub} exists to be called from {@link GraphNode#paintDecoration} — the target node drawing
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
    private final UINode box;
    private final UINode dot;
    private final UINode core;

    /** The bare control currently inside {@link #box} — the same instance
     * {@link NodePort#getDefaultEditor()} returns, or {@code null} while the port has none. Kept in step
     * by {@link #syncControl()}, never assumed to be the value it had at construction. */
    @Nullable
    private UINode control;

    /** The axis prefix, present only while {@link #control} is one that wants one — see
     * {@link #rebuildBoxContents}. */
    @Nullable
    private UIText label;

    private boolean mounted;

    /** Builds the box and the dot, and adopts the port's current default editor if it already has one.
     * A port with none yet is fine — {@link #syncControl()} picks it up whenever it arrives. */
    PortDefaultEditor(NodePort port, GraphView view) {
        this.port = port;
        this.view = view;

        box = new Placed();
        box.addClass(NodePort.EDITOR_CLASS);

        core = new UINode();
        core.addClass(NodePort.EDITOR_DOT_CORE_CLASS);
        core.setHitTest(false);
        UINode ring = new UINode();
        ring.addClass(NodePort.EDITOR_DOT_RING_CLASS);
        ring.setHitTest(false);
        ring.append(core);
        dot = new Placed();
        dot.addClass(NodePort.EDITOR_DOT_CLASS);
        dot.setHitTest(false);
        dot.append(ring);

        syncControl();
    }

    /**
     * Adopts the port's current default editor, if it is not the one already inside the box.
     *
     * <p>Called by {@link GraphView} both on discovery and whenever
     * {@link NodePort#onDefaultEditorChanged} fires. Cheap and idempotent — the common case is that
     * nothing changed and this returns immediately.</p>
     *
     * @return whether the control actually changed
     */
    boolean syncControl() {
        UINode current = port.getDefaultEditor();
        if (current == control) return false;

        detachControl();
        control = current;
        if (control != null) applyLiveUpdateMode(control);
        rebuildBoxContents();
        return true;
    }

    /**
     * Takes the current control back out of the box, explicitly.
     *
     * <p>{@code removeInternalChild} rather than {@code removeChild}: every {@code ConfigControl} marks
     * itself internal in its own constructor, and the public removal API refuses internal children by
     * design. Detaching here — rather than letting a later {@code addInternalChild} elsewhere do it as a
     * side effect of reparenting — is what guarantees exactly one owner at a time, and it is what runs
     * the {@code setAttachedWindow(null)} that lets the control register a fresh Taffy node under
     * whatever adopts it next.</p>
     */
    private void detachControl() {
        if (control == null) return;
        box.remove(control);
        control = null;
    }

    /**
     * Rebuilds the box's children for the current control: an optional axis prefix, then the control.
     *
     * <p>Unity's own axis prefix is generic, not the port's real name: a lone scalar field is always
     * "X" (RadialScale, LengthScale, Power — whatever the port is actually called), never repeated
     * per-port prose that would only fit once next to the port's own real label on the node itself.
     * The exception in both directions: a port ALREADY named X/Y/Z/W (Vector4's own four constituent
     * float ports, say) keeps its real id rather than being forced to "X" four times over; and a
     * {@link VectorControl} gets no outer label at all — it already draws its own per-axis X/Y/Z/W
     * sub-labels internally (see {@code VectorControl.AXES}), so a second "Center" prefix in front of
     * them would be redundant with nothing in Unity's reference to match.</p>
     */
    private void rebuildBoxContents() {
        if (label != null) {
            box.remove(label);
            label = null;
        }
        if (control == null) return;

        if (!(control instanceof VectorControl)) {
            String portId = port.getPortId();
            label = new UIText(isAxisLetter(portId) ? portId : "X");
            label.addClass(NodePort.EDITOR_LABEL_CLASS);
            // Hit-testable, NOT scenery, when there is a number behind it: this letter is the drag handle
            // that scrubs the value. `VectorControl` needs no equivalent here — it labels its own
            // components internally and hands each letter to its own component in its constructor.
            if (control instanceof NumberControl number) number.scrubWith(label);
            else label.setHitTest(false);
            box.append(label);
        }
        box.append(control);
    }

    /**
     * Live, not on-commit: a port default is a value you drag/scrub as much as type, and Unity's own
     * fields update the preview on every keystroke rather than waiting for Enter or a blur. Only the two
     * kinds that actually contain a plain number field get this — {@link NumberControl} directly, or
     * {@link VectorControl}'s own per-axis ones — {@code ColorControl} and {@code BooleanControl} have no
     * number text field to set a mode on at all.
     */
    private static void applyLiveUpdateMode(UINode control) {
        if (control instanceof NumberControl number) {
            number.field().setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        } else if (control instanceof VectorControl vector) {
            for (NumberControl component : vector.components()) {
                component.field().setUpdateMode(TextField.UpdateMode.IMMEDIATE);
            }
        }
    }

    /** Whether {@code portId} is already one of the bare axis letters {@link VectorControl}'s own
     * components use — see {@link #rebuildBoxContents} on why this is the one case that keeps its real
     * id instead of being generalised to {@code "X"}. Case-sensitive: a port genuinely named lowercase
     * {@code "x"} is a different (if confusing) name, not the axis. */
    private static boolean isAxisLetter(String portId) {
        return "X".equals(portId) || "Y".equals(portId) || "Z".equals(portId) || "W".equals(portId);
    }

    NodePort port() {
        return port;
    }

    /** The control itself — the same instance {@link NodePort#getDefaultEditor()} returns, exposed for
     * tests and for any future caller that needs to reach it directly instead of through the port. */
    @Nullable
    UINode control() {
        return control;
    }

    /** The dot element — exposed only for {@code isInsideNode}-style ancestor checks and tests; nothing
     * outside this class should reposition or restyle it directly. */
    UINode dot() {
        return dot;
    }

    boolean isMounted() {
        return mounted;
    }

    /** Whether this has anything worth showing — a port whose field has not been bound yet has not. */
    boolean hasControl() {
        return control != null;
    }

    /**
     * Puts the box and dot on the plane, or takes both off — Unity's field appearing and disappearing
     * with the wire. Idempotent.
     *
     * <p><b>Mounted as INTERNAL children, never public ones.</b> Every {@code ConfigControl} —
     * {@code NumberControl} among them — calls {@code markAsInternal()} on itself in its own
     * constructor, on the standing assumption that whatever host places it will always use
     * {@code addInternalChild}. That flag propagates upward to {@link #box} the moment its constructor
     * runs {@code box.SHADOW_APPEND(control)}, so mounting the box the same way the control itself
     * demands is not optional: {@code content().addChild} still succeeds (the public API does not check
     * the CHILD's own flag, only the caller's), but {@code content().removeChild} silently refuses —
     * internal children are excluded from the public removal API by design — so the box would mount once
     * and never come off the plane again.</p>
     */

    void setMounted(boolean value) {
        if (mounted == value) return;
        mounted = value;
        UINode content = view.content();
        if (value) {
            StyleGroup.defaultPipeline(box.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            StyleGroup.defaultPipeline(dot.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE));
            StyleGroup.inlinePipeline(box.getStyle().getGeneralGroup(), g -> g.zIndex(WIDGET_Z));
            StyleGroup.inlinePipeline(dot.getStyle().getGeneralGroup(), g -> g.zIndex(WIDGET_Z));
            content.append(box);
            content.append(dot);
            // Laid out for real now that both are in the tree — reposition()'s own width/height reads
            // would still see the stale (pre-mount) runtime cache otherwise, same reasoning as
            // GraphNode's own first-frame settle.
            reposition();
        } else {
            content.remove(box);
            content.remove(dot);
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
     * <p><b>{@link NodePort#dotCenterIn()} is not itself a world coordinate — it is a raw layout position,
     * accumulated through every ancestor down to the dot, exactly like {@link CanvasView#worldBoundsOf}
     * reads for a node's own bounds and exactly why that method subtracts the plane's own origin before
     * calling the result "world".</b> {@code moveNode}'s {@code left}/{@code top}, by contrast, ARE world
     * coordinates — that is the whole contract {@code CanvasView.addNode}'s javadoc states. Feeding
     * {@code dotCenter()} straight into {@code moveNode} without that same subtraction adds the plane's
     * own on-screen origin a second time, which is invisible at world origin (0, 0) — where a hastily
     * written test would place its node — and pushes the editor an entire panel-width off to the side
     * the moment a real graph node sits anywhere else.</p>
     */
    /**
     * The frame, which places the whole widget the moment it learns its own width.
     *
     * <h3>Why the position cannot simply be set at creation</h3>
     * <p>{@link #reposition} anchors the box by its RIGHT edge — the left it writes is
     * {@code portDot - GAP - width}. That width is Taffy's answer, and on the frame the box is created
     * there is no answer yet: it is registered during {@code tickAnimations} and does not get a box until
     * that same frame's {@code calculateLayout}. So the mounting call reads zero, and its result is the
     * port's own dot minus the gap — the widget lands <em>on the node</em> rather than beside it.</p>
     */
    private final class Placed extends UINode {
    /**
     * Geometry that can only be settled once layout has run.
     *
     * <p>{@code onLayoutChanged()} on the old engine; there is no such override here, because layout
     * is ONE pass with no feedback into it. A post-layout hook may move a box and read a box and may
     * not add one — a structural change would need a second pass, and there is not one.</p>
     */
        @Override
        protected void connected() {
            super.connected();
            document().animation().afterLayout(this, delta -> {
                onLayoutSettled();
                return true;
            });
        }

        private void onLayoutSettled() {
            // Mounted only: an unmounted box has no plane to be positioned on, and moveNode would write
            // world coordinates onto an element that is not in the world.
            if (mounted) reposition();
        }
    }

    void reposition() {
        // WORLD SPACE IS THE PLANE'S OWN LOCAL SPACE, so asking for the dot's centre in the plane
        // IS the conversion the subtraction used to perform by hand -- and it is now the only one
        // that holds, `Box.x()` being parent-relative where the old cache accumulated through every
        // ancestor. @see NodePort#dotCenterIn
        Vector2f portDot = port.dotCenterIn(view.content());
        float dotWorldX = portDot.x();
        float dotWorldY = portDot.y();

        Box boxCache = box.box();
        float boxX = dotWorldX - GAP - boxCache.width();
        float boxY = dotWorldY - boxCache.height() * 0.5f;
        view.moveNode(box, boxX, boxY);

        Box dotCache = dot.box();
        float boxRightEdge = boxX + boxCache.width();
        float dotX = boxRightEdge - DOT_OVERLAP - dotCache.width() * 0.5f;
        float dotY = dotWorldY - dotCache.height() * 0.5f;
        view.moveNode(dot, dotX, dotY);

        StyleGroup.inlinePipeline(core.getStyle().getGeneralGroup(), g -> g.backgroundColor(port.typeColor()));
    }

    /**
     * Draws the stub joining {@link #dot} to the real port — called from {@link GraphNode#paintDecoration},
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
     * dot's own {@code cache.width() * 0.5f}) — same reasoning as {@link NodeWireLayer#wire}: {@link
     * #dot} and the real port's dot sit at the same Y (see {@link #reposition}), so the segment is
     * already purely horizontal and the trim is exact, not an approximation.</p>
     */
    void paintStub(CgUiPaintContext ctx, UINode space) {
        Box cache = dot.box();
        // The dot has not been laid out yet on the very first frame after mounting — see setMounted —
        // and a zero-size box would draw a stub from nowhere to itself. Invisible either way, but
        // skipped rather than submitted as a degenerate draw call.
        if (cache == null || cache.width() <= 0f || cache.height() <= 0f) return;
        // BOTH ENDS IN THE CALLER'S SPACE. This stub is drawn from GraphNode.paintDecoration, so the
        // pose is the NODE's — while this editor's own dot hangs off the plane and the port's dot off
        // another subtree entirely. Reading either one's raw `x()` mixes three different parents'
        // coordinate systems; the old engine got away with it because that accessor was absolute.
        Vector2f own = Box.centreIn(cache, space == null ? null : space.box());
        float radius0 = cache.width() * 0.5f;
        float y0 = own.y();
        float x0 = own.x() + radius0; // trimmed forward, off this dot's own edge
        Vector2f target = port.dotCenterIn(space);
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
