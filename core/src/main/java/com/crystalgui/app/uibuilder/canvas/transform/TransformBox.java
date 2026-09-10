package com.crystalgui.app.uibuilder.canvas.transform;

import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import com.google.gson.JsonElement;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.service.CursorDecoration;
import com.crystalgui.ui.service.RotationCursor;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.CanvasRects;
import com.crystalgui.app.uibuilder.canvas.ResizeHandles.Spot;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Grip;
import com.crystalgui.app.uibuilder.canvas.transform.TransformGesture.Kind;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The Free Transform box — what the gesture looks like, and where it is written.
 *
 * <p>{@link TransformGesture} owns every number; this owns the element, the pixels and the undo entry.
 * It is driven by {@code FreeTransformTool} and is live only while that tool is current.</p>
 *
 * <h3>Preview through the compositor, commit through the cascade</h3>
 *
 * <p>While the box is up the transform is written as {@code Box.setTransform} — the compositor override,
 * which sits above the cascade and is withdrawn with a {@code null}. Nothing is recorded, so cancel has
 * nothing to undo: it drops the override and the element is exactly what the stylesheet says. Only Enter
 * writes inline style, as one {@link BuilderEdit.SetInlineStyle}. That is the engine's own split between
 * what animates and what rests.</p>
 *
 * <h3>The frame is measured with the node's own transform removed</h3>
 *
 * <p>A gesture that measured against the box as drawn would be reading its own output: rotating would
 * rotate the frame the angle is measured in, and the box would spin away from the pointer. The frame is
 * the node's {@code localToWorld} with the transform right-multiplied out of it, so it still carries the
 * pan, the zoom, the artboard's scale and every ancestor's transform — and none of this one's.</p>
 */
public final class TransformBox extends UIElement {

    public static final Name NAME = Name.of("transformbox");

    public static final String LAYER_CLASS = "__transform-box__";

    /** {@code -Dcrystalgui.builder.diagnose=true} — one line per drag and per commit. */
    private static final boolean DIAGNOSE = Boolean.getBoolean("crystalgui.builder.diagnose");


    /** The band outside a corner that rotates instead of scaling. */
    private static final float ROTATE_BAND = 18f;

    /**
     * Matched to {@code ResizeHandles.SIZE}, so the two gestures read as the same family of chrome.
     *
     * <p>Solid accent, where a resize handle is white with an accent ring — the whole visual difference
     * between the two, at the same size and shape. No ring of its own: a white one was tried and reads
     * as a third kind of chrome rather than as the same dot filled in.</p>
     */
    private static final float HANDLE_SIZE = 6f;

    /**
     * How close a pointer has to be to a handle to scale by it — <b>the dot's own radius, not more.</b>
     *
     * <p>It was seven, which is a fourteen-pixel circle around a six-pixel dot, and the cost is not that
     * scaling is easy to hit: it is that rotate cannot begin until the pointer is clear of it. Rotate has
     * no mark of its own, so every pixel this claims is taken from the only affordance rotate has, and
     * the band started a good ten pixels out from the corner instead of at the edge of the dot.</p>
     *
     * <p>Half the dot plus a pixel of tolerance, so the scale zone is what the eye sees plus a hair.</p>
     */
    private static final float GRAB = HANDLE_SIZE * 0.5f + 1f;

    private static final float PIVOT_SIZE = 9f;

    /**
     * How close a press has to be to the pivot, and it is NOT {@link #PIVOT_SIZE}.
     *
     * <p>That is the crosshair's whole span, so using it as a radius claimed a circle twice the width of
     * the mark: the pivot cursor appeared over empty box and the crosshair could be grabbed from nowhere
     * near it. Half the span is the mark's own reach.</p>
     */
    private static final float PIVOT_GRAB = PIVOT_SIZE * 0.5f;

    private final BuilderContext ctx;

    private final UiBuilderDocument document;

    private final TransformGesture gesture = new TransformGesture();

    @Nullable
    private UIElement target;

    @Nullable
    private JsonElement before;

    private boolean active;

    /** @see #press */
    @Nullable
    private Matrix4f pressOuter;

    /** Where the pointer was last seen, so the cursor can be re-decided without it moving. */
    private float hoverX = Float.NaN;

    private float hoverY;

    private boolean dragging;

    /** One entry per completed adjustment. @see #undoStep */
    private final Deque<TransformGesture.State> undone = new ArrayDeque<>();

    private final Deque<TransformGesture.State> redone = new ArrayDeque<>();

    /** The most recent press, KEPT after release: what a typed number is understood to be about. */
    private Grip lastGrip = Grip.NONE;

    @Nullable
    private TransformOptionsBar options;

    /** What the last commit wrote, for Transform Again. @see #transformAgain */
    @Nullable
    private Again again;

    /**
     * A committed transform, with its pivot kept as FRACTIONS.
     *
     * <p>Fractions because the whole point is to apply it to something else, and something else is a
     * different size — an absolute pivot carried over would land outside the next element, which is the
     * same fault the commit path had before it started writing percentages.</p>
     */
    private record Again(Transform transform, float pivotX, float pivotY) {
    }

    public TransformBox(BuilderContext ctx, UiBuilderDocument document) {
        super(NAME);
        this.ctx = ctx;
        this.document = document;
        addClass(LAYER_CLASS);
        set(Attribute.HIT_TEST, false);
        set(Attribute.HIT_TRANSPARENT, true);
        setDisplayed(false);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
    }

    /**
     * Re-decides the cursor every frame while the box is up.
     *
     * <p>Not on pointer movement alone: <b>Ctrl turns a scale handle into a skew handle</b>, and a
     * modifier arriving while the hand is still is exactly when nothing moves. Pressing Ctrl over an edge
     * left the resize cursor showing until the pointer was nudged, which is the one moment the cursor is
     * meant to be answering a question.</p>
     *
     * <p>Owned by this node, so it stops when the node leaves the tree.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            refreshCursor();
            // TRUE KEEPS IT RUNNING. The hook lives as long as the node, which is the whole life of the
            // canvas -- the box being down is a state it reads, not a reason to unregister.
            return true;
        });
    }

    /**
     * The decoration outlives the hook that clears it, so it is cleared here too.
     *
     * <p>{@link #refreshCursor} runs from a node-owned {@code afterLayout} hook and takes the art down
     * on the first frame the band is not live. A node REMOVED while rotating never gets that frame, and
     * the arrow would stay on screen with nothing left to clear it.</p>
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        clearRotationArt();
    }

    private void clearRotationArt() {
        if (rotationArtHandle == null) return;
        rotationArtHandle.dispose();
        rotationArtHandle = null;
    }

    /** Notes where the pointer is; the cursor itself is decided per frame. @see #connected */
    public void hoverAt(float viewportX, float viewportY) {
        pointerAt(viewportX, viewportY);
    }

    /**
     * The ONE place the pointer lands, and why it is not just {@link #hoverAt}.
     *
     * <p>A drag does not come through {@code pointerMoved}: {@code pointerDown} hands the gesture to
     * {@link com.crystalgui.ui.service.Drag}, which owns the pointer until it ends and drives
     * {@link #dragTo} instead. So a rotation's angle, read from the hover position, was whatever it was
     * at the press and never moved again — the arrow pointed where the hand had BEEN.</p>
     */
    private void pointerAt(float viewportX, float viewportY) {
        hoverX = viewportX;
        hoverY = viewportY;
        Vector2f pivot = toViewport(gesture.originX(), gesture.originY());
        // MEASURED IN THIS OVERLAY'S SPACE, and handed over as an ANGLE rather than as points: an angle
        // between two points survives whatever translation and scale sits between here and the space the
        // decoration draws in, and a point would not.
        if (pivot != null) artAngle = (float) Math.atan2(hoverY - pivot.y, hoverX - pivot.x);
    }

    /** Whether a gesture is in progress, in which case the cursor is the one that was pressed. */
    public void setDragging(boolean dragging) {
        this.dragging = dragging;
        // THE HAND CLOSES. refreshCursor leaves the pressed cursor alone for the rest of the gesture, so
        // the one moment it can change is this one.
        if (dragging && gesture.grip().kind() == Kind.ROTATE) ctx.cursors().set(Cursor.GRABBING);
    }

    /** Pivot toward pointer, in this overlay's space. Declared FIRST: an initialiser below that reads
     * it is an illegal forward reference. @see RotationCursor#paint */
    private float artAngle;

    /**
     * ONE instance, set and cleared per frame — a lambda built in {@link #refreshCursor} would allocate
     * on a hook that runs every frame. It reads {@link #artAngle}, so the shape follows without the
     * decoration itself being rebuilt.
     */
    private final CursorDecoration rotationArt = (paint, x, y) -> RotationCursor.paint(paint, x, y, artAngle);

    /** Held rather than re-set per frame, and it is what {@link #disconnected} clears through: by then
     * {@code document()} is already null, so a node that goes mid-gesture has no way back to the
     * service. The handle closes over it. */
    @Nullable
    private Disposable rotationArtHandle;

    private void refreshCursor() {
        Grip live = liveGrip();
        boolean rotating = active && live != null && live.kind() == Kind.ROTATE;
        if (rotating) {
            if (rotationArtHandle == null) {
                rotationArtHandle = document().input().setCursorDecoration(rotationArt);
            }
        } else {
            clearRotationArt();
        }

        if (!active || dragging || Float.isNaN(hoverX)) return;
        ctx.cursors().set(cursorFor(live));
    }

    /**
     * Which way the rotation arrow is pointing, in radians — pivot toward pointer.
     *
     * <p>Public so the thing that goes wrong here is assertable: it is read once per frame by the
     * decoration and written from wherever the pointer lands, and the failure is that a path forgets to
     * report one. @see #pointerAt</p>
     */
    public float rotationArtAngle() {
        return artAngle;
    }

    /** What the pointer is over, or what it pressed while a gesture owns it. */
    @Nullable
    private Grip liveGrip() {
        if (Float.isNaN(hoverX)) return null;
        return dragging ? gesture.grip() : grip(hoverX, hoverY, CgModifiers.hasCtrl(modifiersNow()));
    }

    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    /**
     * What the pointer says a press would do here.
     *
     * <p>Lives beside {@link #grip} rather than in the tool: the two answer the same question, and a
     * grip whose cursor is decided somewhere else is a grip that can advertise the wrong gesture.</p>
     */
    @Nullable
    public static Cursor cursorFor(Grip grip) {
        Spot spot = grip.spot();
        switch (grip.kind()) {
            case ROTATE:
                // A HAND, and the arrow is DRAWN -- Paint.NET's split. The four rotate-* pictures curled
                // toward a fixed corner, so on a band you travel continuously around they are wrong
                // everywhere between them; a native cursor is one picture and cannot turn. RotationCursor
                // follows the angle exactly, and the hand under it stays crisp and instant.
                return Cursor.GRAB;
            case SKEW:
                return Cursor.SKEW;
            case PIVOT:
                return Cursor.PIVOT;
            case MOVE:
                return Cursor.MOVE;
            case SCALE:
                if (spot == null) return null;
                // The corner's own diagonal, as a resize handle would say. Not rotated with the gesture:
                // a cursor has four diagonals to offer and a box has any angle, so following it would
                // snap between two shapes partway through a rotation and say nothing useful.
                if (spot.xDirection() == 0) return Cursor.NS_RESIZE;
                if (spot.yDirection() == 0) return Cursor.EW_RESIZE;
                return spot.xDirection() == spot.yDirection()
                        ? Cursor.NWSE_RESIZE : Cursor.NESW_RESIZE;
            default:
                return null;
        }
    }

    /** The numbers, for the options bar and for a test. */
    public TransformGesture gesture() {
        return gesture;
    }

    /** What is being transformed, or null when the box is down. */
    @Nullable
    public UIElement target() {
        return target;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Opens the box on a node.
     *
     * @return whether it opened — false for a node with no laid-out box, which has nothing to transform
     */
    public boolean begin(@Nullable UIElement node) {
        Box box = node == null ? null : node.box();
        if (box == null) return false;
        target = node;
        before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        // NOT REFUSED HERE, however tempting: `activated()` has already entered the mode by the time
        // this runs -- its own note says switching tools from inside it re-enters the mode stack -- so a
        // false return leaves the tool live with no target, swallowing every click on the canvas. The
        // guard belongs where entry is DECIDED. @see BuilderCommands#canFreeTransform
        gesture.reset(box.width(), box.height(),
                node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM),
                resolvedOriginX(node, box), resolvedOriginY(node, box));
        active = true;
        setDisplayed(true);
        preview();
        if (DIAGNOSE) report("begin");
        return true;
    }

    /** Writes the gesture as one edit and closes the box. */
    public void commit() {
        UIElement node = target;
        JsonElement was = before;
        boolean changed = active && node != null && was != null;
        withdrawPreview();
        close();
        if (!changed) return;

        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> {
            g.transform(gesture.toTransform());
            g.transformOriginX(asFraction(gesture.originX(), gesture.width()));
            g.transformOriginY(asFraction(gesture.originY(), gesture.height()));
        });
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (DIAGNOSE) CrystalGuiCore.LOGGER.info("[transform] commit before={} after={}", was, after);
        if (after.equals(was)) return;
        again = new Again(gesture.toTransform(),
                fraction(gesture.originX(), gesture.width()),
                fraction(gesture.originY(), gesture.height()));
        document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    /**
     * The pivot as a FRACTION of the box, never as pixels.
     *
     * <p>An absolute origin is measured against the size the element had when it was written, and
     * nothing rewrites it when the element is later resized. Found in a running harness: a button
     * committed at 230 wide carried {@code transform-origin-x: 115}, was resized to 59, and kept an
     * origin sitting well outside itself — so every later scale mostly TRANSLATED the box instead of
     * growing it, and the gesture looked like it was doing something else entirely.</p>
     *
     * <p>CSS's own default is {@code 50%} for the same reason.</p>
     */
    private static LengthPercent asFraction(float pixels, float extent) {
        return LengthPercent.percent(extent < 1e-4f ? 0.5f : pixels / extent);
    }

    /**
     * Drops the box, writing nothing.
     *
     * <p>Nothing to restore: the preview was never in the cascade, so withdrawing the override IS the
     * undo. That is the whole reason the two channels are kept apart.</p>
     */
    public void cancel() {
        withdrawPreview();
        close();
    }

    private void close() {
        active = false;
        target = null;
        before = null;
        // AND THE GESTURE FLAGS. A drag left armed outlives the box otherwise -- the next Ctrl+T opens
        // onto a box that believes it is mid-drag, which silently disables the per-frame cursor for the
        // rest of the session.
        dragging = false;
        hoverX = Float.NaN;
        undone.clear();
        redone.clear();
        gesture.release();
        pressOuter = null;
        setDisplayed(false);
    }

    private void withdrawPreview() {
        Box box = target == null ? null : target.box();
        if (box == null) return;
        box.setTransform(null);
        box.setTransformOrigin(null, null);
    }

    /** Shows the current gesture without recording it. */
    public void preview() {
        Box box = target == null ? null : target.box();
        if (box == null) return;
        box.setTransform(gesture.toTransform());
        box.setTransformOrigin(gesture.originX(), gesture.originY());
    }

    // ---------------------------------------------------------------- spaces

    /**
     * The node's pre-transform local space mapped into this overlay's.
     *
     * @see TransformBox the note on why the node's own transform is removed
     */
    @Nullable
    private Matrix4f frame() {
        return CanvasRects.layoutFrame(target, this);
    }

    private static float resolvedOriginX(UIElement node, Box box) {
        LengthPercent origin = node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
        return origin == null ? box.width() * 0.5f : origin.resolve(box.width());
    }

    private static float resolvedOriginY(UIElement node, Box box) {
        LengthPercent origin = node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
        return origin == null ? box.height() * 0.5f : origin.resolve(box.height());
    }

    /** A node-local point in this overlay's space, with NO gesture applied — where the element sits. */
    @Nullable
    public Vector2f toViewportUntransformed(float localX, float localY) {
        Matrix4f frame = frame();
        return frame == null ? null : apply(frame, localX, localY);
    }

    /** A node-local point in this overlay's space, with the whole gesture applied. */
    @Nullable
    public Vector2f toViewport(float localX, float localY) {
        Matrix4f frame = frame();
        if (frame == null) return null;
        return apply(frame.mul(gesture.matrix()), localX, localY);
    }

    /**
     * A viewport point in the space {@code scaleTo} wants: after rotation and skew, before scale.
     *
     * <p><b>Measured through the mapping as it was at the PRESS, never the live one.</b> That mapping
     * contains the translate, and holding the anchor still WRITES the translate on every frame — so a
     * live one moves the space the scale is measured in by the correction it applied last frame. A
     * stationary pointer then keeps producing a new answer: one axis collapses and the other runs away.
     * It is the same defect the resize handles had, where the opposite edge was held by reading the box
     * being written; a gesture measures from where it began.</p>
     */
    @Nullable
    private Vector2f toScaleSpace(float viewportX, float viewportY) {
        Matrix4f mapping = pressOuter;
        if (mapping == null) return null;
        return apply(new Matrix4f(mapping).invert(), viewportX, viewportY);
    }

    /** A viewport point in the node's own pixels — where the pivot is placed. */
    @Nullable
    private Vector2f toNodeSpace(float viewportX, float viewportY) {
        Matrix4f frame = frame();
        if (frame == null) return null;
        return apply(frame.mul(gesture.matrix()).invert(), viewportX, viewportY);
    }

    /** A viewport DELTA in the node's own pixels — what a move and a skew are measured in. */
    @Nullable
    private Vector2f toNodeDelta(float dx, float dy) {
        Matrix4f frame = frame();
        if (frame == null) return null;
        Matrix4f inverse = frame.invert();
        return new Vector2f(inverse.m00() * dx + inverse.m10() * dy,
                inverse.m01() * dx + inverse.m11() * dy);
    }

    private static Vector2f apply(Matrix4f m, float x, float y) {
        Vector3f out = m.transformPosition(new Vector3f(x, y, 0f));
        return new Vector2f(out.x, out.y);
    }

    // ---------------------------------------------------------------- gestures

    /**
     * What a press at this point would do.
     *
     * <p>Ctrl over an EDGE is a skew; Ctrl over a corner is Photoshop's Distort, which is refused — a free
     * corner is a non-affine map and {@code Transform} is a matrix. It falls back to a scale rather than
     * doing nothing, so the handle still works with a finger on Ctrl.</p>
     */
    public Grip grip(float viewportX, float viewportY, boolean skewModifier) {
        if (!active) return Grip.NONE;
        Vector2f pivot = toViewport(gesture.originX(), gesture.originY());
        if (pivot != null && pivot.distance(viewportX, viewportY) <= PIVOT_GRAB) {
            return new Grip(Kind.PIVOT, null);
        }

        Spot nearest = null;
        float best = Float.MAX_VALUE;
        for (Spot spot : Spot.values()) {
            Vector2f at = handleAt(spot);
            if (at == null) continue;
            float distance = at.distance(viewportX, viewportY);
            if (distance < best) {
                best = distance;
                nearest = spot;
            }
        }
        if (nearest == null) return Grip.NONE;

        if (best <= GRAB) {
            if (skewModifier && !nearest.isCorner()) return new Grip(Kind.SKEW, nearest);
            return new Grip(Kind.SCALE, nearest);
        }
        Vector2f local = toNodeSpace(viewportX, viewportY);
        boolean inside = local != null && local.x >= 0f && local.y >= 0f
                && local.x <= gesture.width() && local.y <= gesture.height();

        // OUTSIDE A CORNER ROTATES, and OUTSIDE is half the rule rather than a detail of it: the band is
        // the only affordance rotate has, so it has to be somewhere a press means nothing else. Reaching
        // inside the box, it stole the corner region from Move -- pressing near a corner to drag the
        // element spun it instead.
        if (!inside && nearest.isCorner() && best <= GRAB + ROTATE_BAND) {
            return new Grip(Kind.ROTATE, nearest);
        }
        if (inside) return new Grip(Kind.MOVE, null);
        return Grip.NONE;
    }

    /** Where a handle is drawn, in this overlay's space. */
    @Nullable
    public Vector2f handleAt(Spot spot) {
        Vector2f corner = gesture.corner(spot);
        return toViewport(corner.x, corner.y);
    }

    /**
     * Steps back one adjustment <b>without leaving the box</b>.
     *
     * <p>Photoshop's rule, and the reason it is not the document's undo: nothing has reached the document
     * yet. The whole gesture is one edit, written on commit, so while the box is up the only history that
     * exists is the one kept here — and a Ctrl+Z falling through to the document would undo whatever was
     * done BEFORE the transform started, which is never what the hand meant.</p>
     *
     * @return whether there was anything to step back
     */
    public boolean undoStep() {
        if (!active || undone.isEmpty()) return false;
        redone.push(gesture.snapshot());
        gesture.restore(undone.pop());
        preview();
        return true;
    }

    /** @see #undoStep */
    public boolean redoStep() {
        if (!active || redone.isEmpty()) return false;
        undone.push(gesture.snapshot());
        gesture.restore(redone.pop());
        preview();
        return true;
    }

    /** Whether there is a transform to apply again. */
    public boolean hasSomethingToRepeat() {
        return again != null;
    }

    /**
     * Applies the last committed transform to another node, as one edit.
     *
     * <p>Illustrator's Ctrl+D and Photoshop's Ctrl+Shift+T. <b>The transform itself, not a delta between
     * two states</b>: composing a delta out of an op LIST is not a matter of subtraction — two transforms
     * with the same matrix can have different ops — and the case anyone actually repeats is a fresh
     * element wanting the same treatment as the last one.</p>
     *
     * @return whether anything was written
     */
    public boolean transformAgain(@Nullable UIElement node) {
        Again repeat = again;
        if (repeat == null || node == null || node.box() == null) return false;
        JsonElement was = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> {
            g.transform(repeat.transform());
            g.transformOriginX(LengthPercent.percent(repeat.pivotX()));
            g.transformOriginY(LengthPercent.percent(repeat.pivotY()));
        });
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (after.equals(was)) return false;
        document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
        return true;
    }

    /**
     * Whether this node's transform is a scale standing in for a size.
     *
     * <p>A scale on a STATIC element is almost always a mistake: it makes the box lie about its size to
     * everything that reads one — the layout, a sibling's alignment, the inspector — and it scales the
     * text with it. A scale on something that moves is the opposite, and is what the property is for, so
     * the test is deliberately narrow: nothing but a scale, and a real one.</p>
     */
    public static boolean isScaleStandingInForSize(@Nullable UIElement node) {
        if (node == null || node.box() == null) return false;
        Transform transform = node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM);
        if (transform == null || transform.isIdentity()) return false;
        boolean scaled = false;
        for (Transform.Op op : transform.ops()) {
            if (op.kind() != Transform.Kind.SCALE) return false;
            if (op.fx() != 1f || op.fy() != 1f) scaled = true;
        }
        return scaled;
    }

    /**
     * Rewrites a scale as {@code width}/{@code height} and clears the transform, as one edit.
     *
     * @return whether anything was written
     */
    public boolean convertToSize(@Nullable UIElement node) {
        if (!isScaleStandingInForSize(node)) return false;
        Box box = node.box();
        Transform transform = node.getStyle().computed().get(StylePropertyRegistry.TRANSFORM);
        float scaleX = 1f;
        float scaleY = 1f;
        for (Transform.Op op : transform.ops()) {
            scaleX *= op.fx();
            scaleY *= op.fy();
        }
        float width = Math.abs(box.width() * scaleX);
        float height = Math.abs(box.height() * scaleY);

        JsonElement was = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), g -> g.transform(Transform.IDENTITY));
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(),
                l -> l.width(Math.round(width)).height(Math.round(height)));
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (after.equals(was)) return false;
        document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
        return true;
    }

    private static float fraction(float pixels, float extent) {
        return extent < 1e-4f ? 0.5f : pixels / extent;
    }

    /** @see #lastGrip */
    public Grip lastGrip() {
        return lastGrip;
    }

    /** The bar showing this gesture's numbers, once the editor has built it. */
    public void showNumbersIn(TransformOptionsBar options) {
        this.options = options;
    }

    @Nullable
    public TransformOptionsBar options() {
        return options;
    }

    /** How many adjustments can still be stepped back. For a test. */
    public int undoDepth() {
        return undone.size();
    }

    /**
     * Begins a drag on whatever {@link #grip} answered.
     *
     * <p>Pins the viewport mapping a scale is measured through, for the reason {@link #toScaleSpace}
     * gives. Pinned here rather than recomputed there so there is exactly one moment it is taken.</p>
     */
    public void press(Grip grip) {
        // BEFORE the drag, so stepping back lands where the hand started. Discarded again on release if
        // nothing actually moved -- a press that turns out to be a click should not cost an undo.
        undone.push(gesture.snapshot());
        redone.clear();
        lastGrip = grip;
        gesture.press(grip);
        Matrix4f frame = frame();
        pressOuter = frame == null ? null : frame.mul(gesture.outer());
    }

    /**
     * Continues the drag.
     *
     * @param viewportX where the pointer is now, in this overlay's space
     * @param dx        how far it has come since the press, in the same space
     */
    public void dragTo(float viewportX, float viewportY, float dx, float dy,
                       boolean aspect, boolean aboutPivot) {
        if (!active) return;
        pointerAt(viewportX, viewportY);
        switch (gesture.grip().kind()) {
            case SCALE -> {
                Vector2f point = toScaleSpace(viewportX, viewportY);
                if (point != null) gesture.scaleTo(point, aspect, aboutPivot);
            }
            case ROTATE -> {
                Float delta = angleDelta(viewportX, viewportY, dx, dy);
                if (delta != null) gesture.rotateBy(delta, aspect);
            }
            case SKEW -> {
                // THROUGH THE ROTATION, unlike a move: a move writes the outermost op, so a screen delta
                // is already in its terms, while a lean runs along the box's own axes.
                Matrix4f frame = frame();
                if (frame != null) {
                    Vector2f delta = CanvasRects.toLocalDelta(
                            frame.mul(gesture.rotationMatrix()), dx, dy);
                    gesture.skewBy(delta.x, delta.y, aboutPivot);
                }
            }
            case MOVE -> {
                Vector2f delta = toNodeDelta(dx, dy);
                if (delta != null) gesture.moveBy(delta.x, delta.y, aspect);
            }
            case PIVOT -> {
                Vector2f point = toNodeSpace(viewportX, viewportY);
                if (point != null) gesture.pivotTo(point, !aboutPivot);
            }
            case NONE -> {
                return;
            }
        }
        preview();
        if (DIAGNOSE) report("drag");
    }

    /** What the gesture and the layout each say, so the two can be told apart in a running harness. */
    private void report(String what) {
        Box box = target == null ? null : target.box();
        if (box == null) return;
        CrystalGuiCore.LOGGER.info(
                "[transform] {} grip={} layout={}x{} world={}x{} scale={}x{} translate={},{} rot={}deg"
                        + " inline={}",
                what, gesture.grip().kind(), box.width(), box.height(),
                worldSpan(box, box.width(), 0f), worldSpan(box, 0f, box.height()),
                gesture.scaleX(), gesture.scaleY(), gesture.translateX(), gesture.translateY(),
                (float) Math.toDegrees(gesture.rotation()),
                InlineStyleCodec.encode(JsonOps.INSTANCE, target));
    }

    private static float worldSpan(Box box, float localX, float localY) {
        Vector2f from = apply(new Matrix4f(box.localToWorld()), 0f, 0f);
        Vector2f to = apply(new Matrix4f(box.localToWorld()), localX, localY);
        return from.distance(to);
    }

    /**
     * How far round the pivot the pointer has travelled, measured on SCREEN.
     *
     * <p>An angle is the one quantity that comes back from the viewport unchanged by the rotation being
     * edited, so measuring it here and handing the gesture a delta is what stops the box chasing itself.
     * </p>
     */
    @Nullable
    private Float angleDelta(float viewportX, float viewportY, float dx, float dy) {
        Vector2f pivot = toViewport(gesture.originX(), gesture.originY());
        if (pivot == null) return null;
        double now = Math.atan2(viewportY - pivot.y, viewportX - pivot.x);
        double then = Math.atan2(viewportY - dy - pivot.y, viewportX - dx - pivot.x);
        return (float) (now - then);
    }

    public void release() {
        if (!undone.isEmpty() && undone.peek().equals(gesture.snapshot())) undone.pop();
        gesture.release();
        pressOuter = null;
    }

    // ---------------------------------------------------------------- painting

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (!active || box == null) return;
        int colour = getStyle().computed().get(StylePropertyRegistry.COLOR);

        // THE LAYOUT BOX, as the reference the transform is read against -- where the element actually
        // sits, and where it will still be when the transform is removed. Skipped while the gesture is
        // identity, since the two outlines then coincide exactly and the second one only thickens the
        // first.
        if (!gesture.isIdentity()) {
            int reference = getStyle().computed().get(StylePropertyRegistry.BORDER_COLOR);
            Vector2f[] base = {
                    toViewportUntransformed(0f, 0f),
                    toViewportUntransformed(gesture.width(), 0f),
                    toViewportUntransformed(gesture.width(), gesture.height()),
                    toViewportUntransformed(0f, gesture.height())};
            if (base[0] != null && base[1] != null && base[2] != null && base[3] != null) {
                for (int i = 0; i < 4; i++) edge(paint, base[i], base[(i + 1) % 4], reference);
            }
        }

        Vector2f[] corners = {
                toViewport(0f, 0f),
                toViewport(gesture.width(), 0f),
                toViewport(gesture.width(), gesture.height()),
                toViewport(0f, gesture.height())};
        for (Vector2f corner : corners) {
            if (corner == null) return;
        }
        for (int i = 0; i < 4; i++) {
            edge(paint, corners[i], corners[(i + 1) % 4], colour);
        }

        // Fully rounded, so each square handle is a circle at any size. @see #HANDLE_SIZE
        for (Spot spot : Spot.values()) {
            Vector2f at = handleAt(spot);
            if (at == null) continue;
            paint.rect()
                    .at(at.x - HANDLE_SIZE * 0.5f, at.y - HANDLE_SIZE * 0.5f)
                    .size(HANDLE_SIZE, HANDLE_SIZE)
                    .radius(HANDLE_SIZE * 0.5f, HANDLE_SIZE * 0.5f)
                    .fillColor(colour)
                    .submit();
        }

        Vector2f pivot = toViewport(gesture.originX(), gesture.originY());
        if (pivot != null) {
            float arm = PIVOT_SIZE * 0.5f;
            edge(paint, new Vector2f(pivot.x - arm, pivot.y), new Vector2f(pivot.x + arm, pivot.y), colour);
            edge(paint, new Vector2f(pivot.x, pivot.y - arm), new Vector2f(pivot.x, pivot.y + arm), colour);
        }
    }

    /**
     * One side of the box, at any angle.
     *
     * <p><b>A stroke, not a rotated fill.</b> Four {@code fillRect}s can only draw the box this one used
     * to be, so it was drawn as a one-pixel rect inside a rotated pose — which is geometrically right
     * and the worst case there is for a quad: a hairline at 30 degrees has no whole pixel anywhere along
     * it, and the quad path carries no coverage term to soften one with. The stroke path computes
     * coverage from the segment's distance field, so the edge is smooth at every angle and every zoom
     * without a multisampled target underneath it.</p>
     */
    private static void edge(CgUiPaintContext paint, Vector2f from, Vector2f to, int colour) {
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        if (dx * dx + dy * dy < 0.0001f) return;
        // Half-width: `width` is the half, so this is the same one logical pixel as before -- and it
        // stays one LOGICAL pixel, because the pose scales stroke widths as it scales everything else.
        paint.curve().line(from.x, from.y, to.x, to.y).width(0.5f).color(colour).submit();
    }
}
