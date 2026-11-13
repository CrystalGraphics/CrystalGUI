package com.crystalgui.ui.service;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.input.DragScrub;
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
 *   release still fired, leaving a live drag eating every move with no button held. The convenience
 *   overloads of {@code start} read which button is HELD rather than assuming the left one — see
 *   {@link #heldButton}.</li>
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
     * How far the pointer must travel before a payload drag begins — in <b>logical</b> pixels.
     *
     * <p>Not a CSS value and deliberately not one: the web has no drag-threshold property. The number is
     * Windows' {@code SM_CXDRAG}, and like every platform's it is stated in the units the interface is
     * laid out in rather than in device pixels: Qt's {@code startDragDistance} is 10, GTK's is 8, and all
     * of them scale with the display.</p>
     *
     * <p>It was read as SURFACE pixels, on the argument that a threshold is a physical distance because
     * the hand moves in pixels. True of the hand and not of the interface: at the default {@code uiScale}
     * of 2 it made the real threshold <b>two logical pixels</b>, so the lightest movement during an
     * ordinary click armed a drag — reported as "even the slightest of drags" tearing a tool window off
     * its rail. Scaled here, once, where the surface distance is compared.</p>
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
    private final UIElement source;
    private final Listener listener;
    private final @Nullable Object payload;
    private final int button;
    private final float threshold;
    private final float pressSurfaceX;
    private final float pressSurfaceY;
    private final float startX;
    private final float startY;

    private boolean activated;
    private @Nullable DragPreview preview;
    /** Where the press landed within the source, in surface pixels: what a {@linkplain DragPreview#grabbing grabbing} preview keeps. */
    private final float grabX, grabY;
    private @Nullable UIElement dropTarget;
    private boolean dropAccepted;
    private boolean live = true;

    private Drag(Input input, UIElement source, float surfaceX, float surfaceY, int button,
                 @Nullable Object payload, float threshold, Listener listener) {
        this.input = input;
        this.source = source;
        this.listener = listener;
        this.payload = payload;
        this.button = button;
        // SCALED TO THE SURFACE, because that is what `pointerMoved` is given. @see #DEFAULT_THRESHOLD_PX
        this.threshold = Math.max(0f, threshold) * uiScaleOf(source);
        this.pressSurfaceX = surfaceX;
        this.pressSurfaceY = surfaceY;
        float[] local = toLocal(source, surfaceX, surfaceY);
        this.startX = local[0];
        this.startY = local[1];
        Box sourceBox = source.box();
        this.grabX = sourceBox == null ? 0f : surfaceX - sourceBox.worldX();
        this.grabY = sourceBox == null ? 0f : surfaceY - sourceBox.worldY();
        this.activated = this.threshold <= 0f;
    }

    /** What a logical distance is worth on this surface. One, for a source that is in no document yet. */
    private static float uiScaleOf(UIElement source) {
        UIDocument window = source.document();
        float scale = window == null ? 1f : window.boxes().uiScale();
        return scale > 0f ? scale : 1f;
    }

    /** A positional drag: no payload, no threshold, live from the first movement. */
    /**
     * Physical pixels per one local unit of {@code handle}, measured through its own transform chain — what turns a
     * {@link Listener}'s local deltas into how far the hand moved.
     *
     * <pre>{@code
     * float perUnit = Drag.pixelsPerLocalUnit(handle);   // sample once, on press
     * double value = DragScrub.value(anchor, deltaX * perUnit, deltaY * perUnit, modifiers, spec);
     * }</pre>
     *
     * <p>A handle inside a zoomed plane or under {@code uiScale} reports local deltas, so a rate applied to them
     * would change with the zoom. Answers 1 for a degenerate transform or a handle not laid out.</p>
     */
    public static float pixelsPerLocalUnit(UIElement handle) {
        final float probe = 100f;
        // Read before the second call: toLocal may hand back a shared vector.
        float originX = handle.toLocal(0f, 0f).x();
        float spanInLocalUnits = handle.toLocal(probe, 0f).x() - originX;
        if (!Float.isFinite(spanInLocalUnits) || Math.abs(spanInLocalUnits) < 1e-4f) return 1f;
        return probe / spanInLocalUnits;
    }

    /**
     * Makes {@code handle} scrub a number: press it and slide, right or up to increase, Escape to put it back.
     *
     * <pre>{@code
     * Drag.scrub(label, field, target);   // a field's label: NumberControl's and TextControl's
     * }</pre>
     *
     * <ul>
     *   <li><b>A press that does not travel is still a click</b>: below the gesture's threshold nothing is written, and
     *       {@code focus} takes focus on the press either way, so the label stays a way into the box.</li>
     *   <li><b>Focus on the press</b>, pointer-style: commands resolve outward from focus, and a drag that left focus
     *       untouched made an edit Ctrl+Z could not reach.</li>
     *   <li><b>Deltas in physical pixels</b>, sampled through the handle's transform, so the rate holds at any
     *       {@code uiScale} or zoom.</li>
     *   <li>{@code began} and {@code ended} bracket only a drag past its threshold, and {@code ended} runs on release
     *       and Escape alike, or the host's merge run never closes.</li>
     * </ul>
     */
    public static void scrub(UIElement handle, UIElement focus, DragScrub.Target target) {
        handle.setHitTest(true);
        handle.onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            // A synthesized activation press carries the cursor's position, nowhere near this handle.
            if (!handle.containsSurfacePoint(rawX, rawY)) return;
            UIDocument window = handle.document();
            if (window == null) return;
            window.focus().requestPointerFocus(focus);
            if (!target.scrubbable()) return;

            DragScrub.Gesture scrub = new DragScrub.Gesture(target.spec());
            scrub.begin(target.start());
            float perUnit = pixelsPerLocalUnit(handle);
            start(handle, rawX, rawY, new Listener() {
                @Override
                public void onDragUpdate(float mouseX, float mouseY, float startX, float startY, float dx, float dy) {
                    boolean wasLive = scrub.isLive();
                    if (!scrub.update(dx * perUnit, dy * perUnit)) return;
                    if (!wasLive) target.began();
                    target.apply(scrub.value());
                }

                @Override
                public void onDragEnd(float mouseX, float mouseY) {
                    if (scrub.isLive()) target.ended();
                }

                @Override
                public void onDragCancel() {
                    // THE PRESS VALUE, not the anchor: a modifier mid-drag moves the anchor, and Escape means as it was.
                    if (!scrub.isLive()) return;
                    target.apply(scrub.start(0));
                    target.ended();
                }
            });
            event.stopPropagation();
        }, false, true);
    }

    public static Drag start(UIElement source, float surfaceX, float surfaceY, Listener listener) {
        return start(source, surfaceX, surfaceY, heldButton(source), null, 0f, listener);
    }

    /** A payload drag at the default threshold: nothing fires until the pointer has really moved. */
    public static Drag startWithPayload(UIElement source, float surfaceX, float surfaceY,
                                        Object payload, Listener listener) {
        return start(source, surfaceX, surfaceY, heldButton(source), payload,
                DEFAULT_THRESHOLD_PX, listener);
    }

    /**
     * Whichever button is down as the drag begins, falling back to the left.
     *
     * <p><b>Read, not assumed.</b> These overloads used to declare LEFT, and a gesture begun from any
     * other button then waited for a left release that was never coming — it stayed live with nothing
     * held, eating every move until something else cancelled it. Reported against the builder's resize
     * handles and the split view's divider, which is every caller of these overloads rather than a fault
     * in either widget: neither checks the button, and neither should have to.</p>
     *
     * <p>A gesture starts from a mouse-down handler, so the press has already been dispatched and the
     * only record of which button it was is the input service's.</p>
     */
    private static int heldButton(UIElement source) {
        UIDocument window = source.document();
        Input input = window == null ? null : window.input();
        if (input == null) return CgMouseCodes.LEFT_BUTTON;
        if (input.isButtonDown(CgMouseCodes.LEFT_BUTTON)) return CgMouseCodes.LEFT_BUTTON;
        if (input.isButtonDown(CgMouseCodes.RIGHT_BUTTON)) return CgMouseCodes.RIGHT_BUTTON;
        if (input.isButtonDown(CgMouseCodes.MIDDLE_BUTTON)) return CgMouseCodes.MIDDLE_BUTTON;
        return CgMouseCodes.LEFT_BUTTON;
    }

    /**
     * Begins a drag and pushes it as the innermost mode. Takes pointer capture, so every pointer
     * event reaches the source however far the pointer travels, and {@code :hover} stays pinned.
     */
    public static Drag start(UIElement source, float surfaceX, float surfaceY, int button,
                             @Nullable Object payload, float thresholdPx, Listener listener) {
        Input input = source.document().input();
        Drag drag = new Drag(input, source, surfaceX, surfaceY, button, payload, thresholdPx, listener);
        input.setPointerCapture(source);
        input.pushMode(drag);
        return drag;
    }

    @Override
    public String name() {
        return "drag";
    }

    // ── The preview ──────────────────────────────────────────────────────────

    /**
     * What this drag looks like under the pointer, drawn by the host from activation to the end: the shell while the
     * document is a window's client, so it is never cut off at the window's edge, else the document.
     *
     * <pre>{@code
     * Drag.startWithPayload(row, x, y, payload, listener).preview(DragPreview.of(icon, "Problems"));
     * }</pre>
     *
     * <p>A drag with none shows nothing: sliders, splits and scrubs. Once, right after {@code start}; edit the preview
     * itself to change it mid-drag.</p>
     */
    public Drag preview(@Nullable DragPreview value) {
        preview = value;
        if (value != null) {
            value.grabbedAt(grabX, grabY);
            // IN THE TREE BEFORE THE DRAG RUNS, and declared as following the pointer so a compositor may move it.
            DragPresenter presenter = input.presenter();
            if (presenter != null && followed == null) followed = presenter.element();
        }
        if (activated && live) input.dragPreview(value);
        return this;
    }

    @Nullable
    public DragPreview preview() {
        return preview;
    }

    private void showPreview(float surfaceX, float surfaceY) {
        DragPresenter presenter = input.presenter();
        if (presenter == null) return;
        if (input.ghostHandedOver()) presenter.hide();
        else presenter.show(preview, surfaceX, surfaceY);
    }

    public boolean isActivated() {
        return activated;
    }

    public UIElement source() {
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
    public UIElement dropTarget() {
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
    public boolean claimsKey(int key, int modifiers) {
        return key == CgKeyCodes.KEY_ESCAPE;
    }

    @Override
    public boolean pointerMoved(float x, float y) {
        if (!live) return false;
        if (!activated) {
            float dx = x - pressSurfaceX, dy = y - pressSurfaceY;
            if (dx * dx + dy * dy < threshold * threshold) return true;
            activated = true;
            declareFollow();
            input.portableDrag(TransferData.shared(payload));
            input.dragPreview(preview);
        }
        if (preview != null) showPreview(x, y);
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
        if (activated) input.markReleaseEndedDrag();
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
        UIElement dropOn = dropAccepted ? dropTarget : null;
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
        UIElement staleTarget = dropTarget;
        dropTarget = null;
        dropAccepted = false;
        input.popMode(this);
        if (staleTarget != null) {
            for (UIElement at = staleTarget; at != null; at = at.composedParent()) {
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
        // two paths that DO report (end and cancel) pop themselves first. The preview is let go HERE
        // rather than in end()/cancel(), because this is the one path every ending goes through.
        live = false;
        input.portableDrag(null);
        input.dragPreview(null);
        input.handOverGhost(false);
        follows(null);
        DragPresenter own = preview == null ? null : input.presenter();
        if (own != null) own.hide();
    }

    /**
     * Declares that {@code element} moves one for one with this drag's pointer, so a compositor may move it ahead of a
     * busy document; null withdraws it. A drag preview's ghost is declared already. From activation to the drag's end, however it
     * ends.
     *
     * <pre>{@code
     * Drag.start(titleBar, x, y, (mx, my, sx, sy, dx, dy) -> moveTo(startX + dx, startY + dy)).follows(this);
     * }</pre>
     *
     * <p>Only an element that moves exactly with the pointer: a drag that clamps, snaps, eases or resizes as it goes
     * would be drawn somewhere it is not, until the document's next frame.</p>
     */
    public Drag follows(@Nullable UIElement element) {
        return follows(element, null);
    }

    /**
     * {@link #follows(UIElement)} for an element the drag clamps inside {@code within}'s box, so the compositor stops it
     * at the same edges.
     *
     * <pre>{@code
     * Drag.start(header, x, y, (mx, my, sx, sy, dx, dy) -> moveClamped(startX + dx, startY + dy)).follows(this, container);
     * }</pre>
     */
    public Drag follows(@Nullable UIElement element, @Nullable UIElement within) {
        if (declared && followed != null) input.stopFollowingPointer(followed);
        declared = false;
        followed = element;
        followedWithin = within;
        if (activated && live) declareFollow();
        return this;
    }

    private void declareFollow() {
        if (declared || followed == null) return;
        input.followPointer(followed, followedWithin);
        declared = true;
    }

    /** What moves one for one with this drag's pointer, what bounds it, and whether the input has been told yet. */
    @Nullable
    private UIElement followed, followedWithin;
    private boolean declared;

    // ── Drop targeting ───────────────────────────────────────────────────────

    private void updateDropTarget(float x, float y) {
        Focus focus = source.document().focus();
        Box box = source.document().boxes().hitTest(x, y, b -> focus.isInert(b.node()));
        UIElement under = box == null ? null : box.node();
        if (isSelfOrInsideSource(under)) under = null;

        if (under != dropTarget) {
            UIElement common = commonAncestor(dropTarget, under);
            // Innermost first on the way out, outermost first on the way in -- the same order the
            // mouse pair uses, so a target and its ancestors never disagree about what is entered.
            for (UIElement at = dropTarget; at != null && at != common; at = at.composedParent()) {
                input.send(at, new DragEvent.Leave(at, input.pointer(), source, payload));
            }
            List<UIElement> entered = new ArrayList<>();
            for (UIElement at = under; at != null && at != common; at = at.composedParent()) entered.add(at);
            for (int i = entered.size() - 1; i >= 0; i--) {
                UIElement at = entered.get(i);
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

    private boolean isSelfOrInsideSource(@Nullable UIElement candidate) {
        for (UIElement at = candidate; at != null; at = at.composedParent()) {
            if (at == source) return true;
        }
        return false;
    }

    @Nullable
    private static UIElement commonAncestor(@Nullable UIElement a, @Nullable UIElement b) {
        if (a == null || b == null) return null;
        for (UIElement up = a; up != null; up = up.composedParent()) {
            for (UIElement down = b; down != null; down = down.composedParent()) {
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
    private static float[] toLocalVector(UIElement node, float surfaceDx, float surfaceDy) {
        Box box = node.box();
        if (box == null) return new float[]{surfaceDx, surfaceDy};
        Vector4f vector = new Vector4f(surfaceDx, surfaceDy, 0f, 0f);
        box.worldToLocal().transform(vector);
        return new float[]{vector.x, vector.y};
    }

    /** Surface pixels into a box's own space, through the matrix layout composed. */
    private static float[] toLocal(UIElement node, float surfaceX, float surfaceY) {
        Box box = node.box();
        if (box == null) return new float[]{surfaceX, surfaceY};
        Vector4f point = new Vector4f(surfaceX, surfaceY, 0f, 1f);
        box.worldToLocal().transform(point);
        return new float[]{point.x, point.y};
    }
}
