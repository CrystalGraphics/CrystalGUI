package com.crystalgui.ui.service;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.DragEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.joml.Vector4f;

/**
 * A drag, as a {@link InputMode}: pointer capture plus an optional payload, an activation threshold, drop
 * targeting, and an Escape that cancels — ported from {@code UIDragController} with its semantics
 * intact and its two couplings removed (it is no longer a field on the input handler, and Escape is
 * no longer a hard-coded rung above the close watchers).
 *
 * <p><b>Not HTML5 drag-and-drop.</b> The web moved to pointer events; so does this.</p>
 *
 * <h3>The four rules that are easy to get wrong, all kept</h3>
 *
 * <ol>
 *   <li><b>Rejection is the default.</b> A target accepts by calling {@code preventDefault()} on its
 *   {@link DragEvent.Over}, which is re-read every frame and never latched — HTML5 DnD's one
 *   genuinely good idea.</li>
 *   <li><b>Drop targeting is GEOMETRIC.</b> It asks the box tree directly rather than the input
 *   service's hover, which is substituted by the capture for the whole drag — conflating the two
 *   makes every drop land on the thing being dragged.</li>
 *   <li><b>The source and its subtree are never a drop target.</b></li>
 *   <li><b>The drag ends when the button that STARTED it is released</b>, not button 0. A
 *   middle-button pan would otherwise never be told its button came up while the implicit capture
 *   release still fired, leaving a live drag eating every move with no button held.</li>
 * </ol>
 *
 * <h3>One divergence, stated</h3>
 *
 * <p>The listener's coordinates are in the SOURCE BOX's own space — origin at its top-left — because
 * that is the space the whole new engine works in (the painter draws a box at 0,0 and the hit test
 * inverts the same matrix). The old controller handed over {@code screenToLocal}'s answer, which is
 * an ABSOLUTE layout coordinate that does not subtract the source's origin, and reading that as
 * "relative to the source" cost two separate bugs in opposite directions.</p>
 */
public final class Drag implements InputMode {

    /**
     * How far the pointer must travel before a payload drag begins.
     *
     * <p>Not a CSS value and deliberately not one: the web has no drag-threshold property. In
     * SURFACE pixels, because the threshold is a physical distance — the hand moves in pixels.</p>
     */
    public static final float DEFAULT_THRESHOLD_PX = 4f;

    /** What a drag reports back to whoever started it. */
    public interface Listener {
        /** Every frame the pointer moves, once the drag is active. Coordinates in the source's own space. */
        void onDragUpdate(float x, float y, float startX, float startY, float deltaX, float deltaY);

        default void onDragEnd(float x, float y) {
        }

        default void onDragCancel() {
        }
    }

    private final Input input;
    private final UINode source;
    private final Listener listener;
    private final @Nullable Object payload;
    private final int button;
    private final float threshold;
    private final float pressSurfaceX;
    private final float pressSurfaceY;
    private final float startX;
    private final float startY;

    private boolean activated;
    private @Nullable UINode ghost;
    private float ghostOffsetX, ghostOffsetY;
    private @Nullable UINode dropTarget;
    private boolean dropAccepted;
    private boolean live = true;

    private Drag(Input input, UINode source, float surfaceX, float surfaceY, int button,
                 @Nullable Object payload, float threshold, Listener listener) {
        this.input = input;
        this.source = source;
        this.listener = listener;
        this.payload = payload;
        this.button = button;
        this.threshold = Math.max(0f, threshold);
        this.pressSurfaceX = surfaceX;
        this.pressSurfaceY = surfaceY;
        float[] local = toLocal(source, surfaceX, surfaceY);
        this.startX = local[0];
        this.startY = local[1];
        this.activated = this.threshold <= 0f;
    }

    /** A positional drag: no payload, no threshold, live from the first movement. */
    public static Drag start(UINode source, float surfaceX, float surfaceY, Listener listener) {
        return start(source, surfaceX, surfaceY, CgMouseCodes.LEFT_BUTTON, null, 0f, listener);
    }

    /** A payload drag at the default threshold: nothing fires until the pointer has really moved. */
    public static Drag startWithPayload(UINode source, float surfaceX, float surfaceY,
                                        Object payload, Listener listener) {
        return start(source, surfaceX, surfaceY, CgMouseCodes.LEFT_BUTTON, payload,
                DEFAULT_THRESHOLD_PX, listener);
    }

    /**
     * Begins a drag and pushes it as the innermost mode. Takes pointer capture, so every pointer
     * event reaches the source however far the pointer travels, and {@code :hover} stays pinned.
     */
    public static Drag start(UINode source, float surfaceX, float surfaceY, int button,
                             @Nullable Object payload, float thresholdPx, Listener listener) {
        Input input = source.document().input();
        Drag drag = new Drag(input, source, surfaceX, surfaceY, button, payload, thresholdPx, listener);
        // THE GHOST OFFERED ON THE WAY DOWN, which is the only moment a caller has: `DragGhost.follow`
        // runs from the mouse-down handler that is about to call this, so it had no drag to hand it to.
        UINode offered = input.takePendingGhost();
        if (offered != null) drag.withGhost(offered, input.pendingGhostX(), input.pendingGhostY());
        input.setPointerCapture(source);
        input.pushMode(drag);
        return drag;
    }

    @Override
    public String name() {
        return "drag";
    }

    // ── The ghost ────────────────────────────────────────────────────────────

    /**
     * The thing that follows the cursor for this drag's duration — promoted to the top layer, moved
     * each frame, and let go of when the drag ends.
     *
     * <p><b>Per drag, never once.</b> The old controller dropped its ghost reference at the end of
     * every drag on purpose, and a retained one once outlived its drag and reappeared on unrelated
     * screens. Here it is a field of the gesture, so there is nothing to retain: a ghost belongs to
     * a drag the way a drop target does.</p>
     *
     * <p>The node must already be IN the tree — promotion is a re-host of a box, and a node with no
     * box has nothing to promote. Its own position is not written: the ghost is hosted in the top
     * layer, whose containing block is the document, and a transform moves it. That is what keeps
     * this free of the "only AnchoredPlacement writes left/top" rule and of any layout at all.</p>
     *
     * @param offsetX where the cursor sits within the ghost, in the ghost's own space
     */
    public Drag withGhost(UINode ghost, float offsetX, float offsetY) {
        this.ghost = ghost;
        this.ghostOffsetX = offsetX;
        this.ghostOffsetY = offsetY;
        // RECORDED ONLY. This used to read `ghost.box()` here and give up when it was null -- which it
        // always is, because a ghost is `display: none` until its drag and a node that is not
        // displayed HAS NO BOX. So the whole body was skipped, `ghostBox` stayed null, and every
        // `moveGhost` returned on its first line: the ghost was attached to the drag and never shown.
        //
        // It also wrote `box.setHost(top)`, which is the thing M6.0 records as not surviving: a box is
        // destroyed and rebuilt whenever its subtree is hidden or restructured, and going from
        // `display: none` to displayed is exactly that -- so even a box that HAD existed would have
        // lost its host on the next sync. Promotion belongs on the NODE.
        return this;
    }

    /**
     * Shows the ghost and promotes it — idempotent, from the per-move update.
     *
     * <p><b>Here rather than at {@code withGhost}</b>, for the reason the old controller states at its
     * own call site: showing on the threshold transition misses a zero-threshold positional drag, which
     * activates immediately and never passes through that branch, and misses a ghost handed over after
     * the drag has already begun. Promotion is idempotent, so asking every move costs a set lookup.</p>
     *
     * <p>The two halves are what the old engine's {@code showGhost} did — {@code display: FLEX} and
     * {@code addToTopLayer} — expressed as this engine's own: {@code setDisplayed} for the box, and
     * {@code UIDocument.promote} for the layer, which is recorded on the node and therefore survives
     * the box being rebuilt by the display change itself.</p>
     */
    private void showGhost() {
        if (ghost == null) return;
        UIDocument document = ghost.document();
        if (document == null || document.isPromoted(ghost)) return;
        ghost.setDisplayed(true);
        // Unhittable for the gesture's life: it is under the cursor by construction, so a hittable
        // ghost is a ghost that answers every drop query with itself.
        ghost.setHitTest(false);
        document.promote(ghost);
    }

    @Nullable
    public UINode ghost() {
        return ghost;
    }

    private void moveGhost(float surfaceX, float surfaceY) {
        // READ LIVE, never cached. The ghost's box comes into existence when it is shown and is
        // rebuilt whenever anything restructures it, so a reference taken once is a reference to a box
        // that may already be gone -- and, on the frame the ghost is first displayed, to no box at all.
        Box box = ghost == null ? null : ghost.box();
        if (box == null) return;
        Box top = box.host();
        if (top == null) return;
        // Surface pixels into the top layer's own space: the ghost is hosted there, so that is the
        // space its transform is applied in. The layer is at the document's origin, so this is the
        // root transform's inverse and nothing more -- but going through the matrix is what keeps it
        // right when uiScale moves.
        org.joml.Vector4f p = new org.joml.Vector4f(surfaceX, surfaceY, 0f, 1f).mul(top.worldToLocal());
        box.setTransform(UITransform.translate(p.x - ghostOffsetX, p.y - ghostOffsetY));
    }

    private void releaseGhost() {
        if (ghost != null) {
            Box box = ghost.box();
            if (box != null) box.setTransform(null);
            UIDocument document = ghost.document();
            if (document != null) document.demote(ghost);
            // BACK TO HIDDEN, which is the state rule 2 says a ghost is constructed in: it is a
            // permanent node in somebody's tree, and one left displayed is a stray label sitting in a
            // panel between drags.
            ghost.setDisplayed(false);
            ghost.setHitTest(true);
        }
        ghost = null;
    }

    public boolean isActivated() {
        return activated;
    }

    public UINode source() {
        return source;
    }

    public int button() {
        return button;
    }

    @Nullable
    public Object payload() {
        return payload;
    }

    /** What the pointer is over and would drop onto, or null. */
    @Nullable
    public UINode dropTarget() {
        return dropTarget;
    }

    /** Whether the current target has accepted, this frame. Never latched. */
    public boolean isDropAccepted() {
        return dropAccepted;
    }

    // ── Mode ─────────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        // Escape ends a drag before anything else can have it: a drag is the innermost live
        // interaction, and while one is running that is what Escape means, whatever holds focus.
        if (key != CgKeyCodes.KEY_ESCAPE) return false;
        cancel();
        return true;
    }

    @Override
    public boolean pointerMoved(float x, float y) {
        if (!live) return false;
        if (!activated) {
            float dx = x - pressSurfaceX, dy = y - pressSurfaceY;
            if (dx * dx + dy * dy < threshold * threshold) return true;
            activated = true;
        }
        showGhost();
        moveGhost(x, y);
        float[] local = toLocal(source, x, y);
        // THE DELTA IS A VECTOR, NOT A DIFFERENCE OF TWO POINTS, and it has to be: `toLocal` puts the
        // box's own origin at zero (M6.1), so a source that MOVES WITH THE DRAG changes the frame its
        // own delta is measured in. `local - startX` then reports pointer travel MINUS how far the
        // source has already travelled, the window moves less than the hand, the next frame reports
        // less still -- which on screen is a title bar rubber-banding against the cursor.
        //
        // Transforming the SURFACE delta with w = 0 drops the translation and keeps the scale and
        // rotation, so the answer is in the source's units and independent of where the source is.
        // Every earlier consumer was a Slider, a Scroller or a TextField, none of which moves; the
        // Dialog is the first drag whose source travels, and SplitView's divider is the second.
        float[] delta = toLocalVector(source, x - pressSurfaceX, y - pressSurfaceY);
        listener.onDragUpdate(local[0], local[1],
                local[0] - delta[0], local[1] - delta[1], delta[0], delta[1]);
        if (payload != null) updateDropTarget(x, y);
        return true;
    }

    @Override
    public boolean pointerButton(int pressedButton, boolean pressed, float x, float y) {
        if (pressed || pressedButton != button) return false;
        end(x, y);
        // NOT consumed: the release must still reach whatever it landed on, which is what lets a
        // click complete when a drag never passed its threshold.
        return false;
    }

    // ── Ending ───────────────────────────────────────────────────────────────

    /** Drops on the accepting target if there is one, then reports the end. */
    public void end(float x, float y) {
        if (!live) return;
        live = false;
        UINode dropOn = dropAccepted ? dropTarget : null;
        input.popMode(this);
        if (dropOn != null && payload != null) {
            input.send(dropOn, new DragEvent.Drop(dropOn, input.pointer(), source, payload));
        }
        float[] local = toLocal(source, x, y);
        listener.onDragEnd(local[0], local[1]);
    }

    /** Abandons the drag: the target hears a Leave, the source hears a Cancel, capture is released. */
    public void cancel() {
        if (!live) return;
        live = false;
        UINode staleTarget = dropTarget;
        dropTarget = null;
        dropAccepted = false;
        input.popMode(this);
        if (staleTarget != null) {
            for (UINode at = staleTarget; at != null; at = at.composedParent()) {
                input.send(at, new DragEvent.Leave(at, input.pointer(), source, payload));
            }
        }
        input.releasePointerCapture();
        input.send(source, new DragEvent.Cancel(source, input.pointer(), source, payload));
        listener.onDragCancel();
    }

    @Override
    public void ended() {
        // Popped by someone else -- a teardown, a mode stack being cleared. Nothing to report; the
        // two paths that DO report (end and cancel) pop themselves first. The ghost is released HERE
        // rather than in end()/cancel(), because this is the one path every ending goes through.
        live = false;
        releaseGhost();
    }

    // ── Drop targeting ───────────────────────────────────────────────────────

    private void updateDropTarget(float x, float y) {
        Focus focus = source.document().focus();
        Box box = source.document().boxes().hitTest(x, y, b -> focus.isInert(b.node()));
        UINode under = box == null ? null : box.node();
        if (isSelfOrInsideSource(under)) under = null;

        if (under != dropTarget) {
            UINode common = commonAncestor(dropTarget, under);
            // Innermost first on the way out, outermost first on the way in -- the same order the
            // mouse pair uses, so a target and its ancestors never disagree about what is entered.
            for (UINode at = dropTarget; at != null && at != common; at = at.composedParent()) {
                input.send(at, new DragEvent.Leave(at, input.pointer(), source, payload));
            }
            List<UINode> entered = new ArrayList<>();
            for (UINode at = under; at != null && at != common; at = at.composedParent()) entered.add(at);
            for (int i = entered.size() - 1; i >= 0; i--) {
                UINode at = entered.get(i);
                input.send(at, new DragEvent.Enter(at, input.pointer(), source, payload));
            }
            dropTarget = under;
        }

        if (dropTarget == null) {
            dropAccepted = false;
            return;
        }
        DragEvent.Over over = new DragEvent.Over(dropTarget, input.pointer(), source, payload);
        input.send(dropTarget, over);
        dropAccepted = over.isDefaultPrevented();
    }

    private boolean isSelfOrInsideSource(@Nullable UINode candidate) {
        for (UINode at = candidate; at != null; at = at.composedParent()) {
            if (at == source) return true;
        }
        return false;
    }

    @Nullable
    private static UINode commonAncestor(@Nullable UINode a, @Nullable UINode b) {
        if (a == null || b == null) return null;
        for (UINode up = a; up != null; up = up.composedParent()) {
            for (UINode down = b; down != null; down = down.composedParent()) {
                if (up == down) return up;
            }
        }
        return null;
    }

    /**
     * A surface DIRECTION into a box's own units — the same matrix with the translation dropped.
     *
     * <p>{@code w = 0} is the whole of it: a point carries the box's position and a vector does not,
     * so this answers the same thing however far the box has moved since the drag began.</p>
     */
    private static float[] toLocalVector(UINode node, float surfaceDx, float surfaceDy) {
        Box box = node.box();
        if (box == null) return new float[]{surfaceDx, surfaceDy};
        Vector4f vector = new Vector4f(surfaceDx, surfaceDy, 0f, 0f);
        box.worldToLocal().transform(vector);
        return new float[]{vector.x, vector.y};
    }

    /** Surface pixels into a box's own space, through the matrix layout composed. */
    private static float[] toLocal(UINode node, float surfaceX, float surfaceY) {
        Box box = node.box();
        if (box == null) return new float[]{surfaceX, surfaceY};
        Vector4f point = new Vector4f(surfaceX, surfaceY, 0f, 1f);
        box.worldToLocal().transform(point);
        return new float[]{point.x, point.y};
    }
}
