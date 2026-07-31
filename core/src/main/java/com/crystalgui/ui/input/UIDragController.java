package com.crystalgui.ui.input;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.tree.UITreeTraversal;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;
import org.joml.Vector2f;

import javax.annotation.Nullable;

/**
 * Drag: pointer capture plus an optional payload and drop targeting. One active drag at a time,
 * matching this engine's single-cursor input model. Owned 1:1 by a {@link UIInputHandler}; reach it
 * via {@code element.getAttachedWindow().getInputHandler().getDragController()}.
 *
 * <h3>Two kinds of drag, one mechanism</h3>
 * <ul>
 *   <li><b>Positional</b> — Slider, Scroller, SplitView. No payload, no drop target; the source just
 *       wants a stream of coordinates. This is what the class originally did and it still works
 *       exactly the same way, through {@link DragListener}.</li>
 *   <li><b>Payload</b> — dragging a thing <em>onto</em> another thing. Adds
 *       {@link DragEvent}s dispatched to whatever is under the pointer.</li>
 * </ul>
 * A positional drag pays for none of the drop-target machinery: with no payload and nothing
 * listening, the per-frame hit test is the only extra work, and it is one call.
 *
 * <h3>Pointer capture</h3>
 * <p>{@link #startDrag} takes pointer capture (Pointer Events L3) on the source, so every mouse event
 * for the rest of the drag is targeted there "as if the pointer is always over the capturing target".
 * That is what lets a drag end anywhere on screen, and it is also what stops {@code :hover} and
 * {@code mouseenter}/{@code mouseleave} firing across everything the cursor crosses mid-drag.</p>
 *
 * <p><b>Drop targeting cannot use that same answer</b>, since capture makes hit testing always report
 * the source. So this class asks the window separately for what is <em>geometrically</em> under the
 * pointer. {@code UIWindow.getHoveredElement} is deliberately left free of capture substitution to
 * make that possible — see {@link DragEvent}.</p>
 *
 * <h3>Coordinate space</h3>
 * <p>Every coordinate handed to a {@link DragListener} is in the <b>drag source's local space</b>,
 * converted here via {@link UIElement#screenToLocal}. Raw input is in physical pixels while element
 * geometry is in logical units — at the default {@code uiScale} of 2 they differ by a factor of two —
 * so a listener comparing raw coordinates against layout values would be wrong everywhere but the
 * top-left corner. Converting once, here, keeps every consumer correct by default.</p>
 *
 * <p>Deltas are the difference of two <em>separately converted</em> endpoints, not a converted
 * difference: the transform carries translation, which would corrupt a vector transformed directly.</p>
 */
public final class UIDragController {

    /**
     * Movement required before a payload drag activates, in <b>physical</b> pixels.
     *
     * <p>Not a CSS value and deliberately not one: the web has no drag-threshold property, so
     * inventing one would be exactly the kind of non-web concept this codebase avoids. Libraries
     * (dnd-kit's "activation constraints", jQuery UI's {@code distance}) all keep it in code, and so
     * does this — as a caller-supplied number with a conventional default.</p>
     *
     * <p>Physical, not logical, because it models a property of the human hand and the mouse, which
     * do not rescale when {@code uiScale} changes.</p>
     */
    public static final float DEFAULT_THRESHOLD_PX = 4f;

    public interface DragListener {
        /** Fired once per frame while the drag is active (ticked from {@link UIInputHandler#endFrame()},
         * unconditionally — matches this engine's existing immediate-mode philosophy of recomputing
         * freely each frame rather than gating on movement). All coordinates are in the drag
         * source's local space. */
        void onDragUpdate(float mouseX, float mouseY, float startX, float startY, float deltaX, float deltaY);

        /** Fired once when the drag ends (button-0 release), wherever the mouse is at that point —
         * not gated on being back over the drag source. */
        default void onDragEnd(float mouseX, float mouseY) {}

        /** Fired instead of {@link #onDragEnd} when the drag is aborted rather than completed. */
        default void onDragCancel() {}
    }

    private UIElement source;
    private DragListener listener;
    private float startX, startY;

    /** The mouse button holding this drag open. Released, it ends the drag; any other button's release
     * is ignored. @see #startDrag(UIElement, float, float, int, DragListener) */
    private int button = CgMouseCodes.LEFT_BUTTON;

    @Nullable
    private Object payload;
    private float threshold;
    /** Where inside the source the press landed, in logical units — the ghost anchor. */
    private float grabOffsetX, grabOffsetY;
    /** Raw press position, kept in physical pixels — the threshold is a physical distance. */
    private float pressPhysX, pressPhysY;
    /** False until the pointer has moved past {@link #threshold}. A payload drag fires nothing before
     * this, so a press that never really moved stays an ordinary click. */
    private boolean activated;
    /** Whether the current drop target accepted on the latest frame. @see #isDropAccepted() */
    private boolean dropAccepted;

    @Nullable
    private UIElement dropTarget;

    @Nullable
    private UIElement ghost;

    /**
     * A visual stand-in that follows the pointer for the rest of the drag, or {@code null} for none.
     *
     * <p>Deliberately a plain {@link UIElement} the caller builds and styles, not a {@code DragGhost}
     * widget — the input layer has no business importing {@code ui.elements}, and a ghost is just
     * "some element that follows the cursor". Its content and appearance are entirely the caller's.</p>
     *
     * <p>The controller only does three things to it: promotes it into the top layer on activation
     * (so it draws above everything, which is the one hard dependency drag has on P1), positions it
     * each frame, and hides it when the drag ends. It is {@code display: none} while idle, so it is
     * safe to parent anywhere — including inside the drag source, which is the recommended spot:
     * {@link #isSelfOrInsideSource} then excludes it from drop targeting automatically, on top of the
     * {@code hitTest(false)} set below.</p>
     *
     * <p><b>Register per drag, not once.</b> The controller drops its reference when the drag ends, so a
     * caller that registers a ghost at construction time gets one for the first drag and none afterwards.
     * That is deliberate: a retained ghost survived the drag that owned it and turned up again on
     * unrelated pages the next time anything was dragged, which is far harder to explain than
     * re-registering on mouse-down.</p>
     */
    public void setGhost(@Nullable UIElement ghost) {
        this.ghost = ghost;
        if (ghost == null) return;
        // A ghost sitting under the cursor would otherwise be the drop target for its own drag.
        ghost.setHitTest(false);
        hideGhost(ghost);
    }

    @Nullable
    public UIElement getGhost() {
        return ghost;
    }

    private void showGhost(UIElement g) {
        StyleGroup.importantPipeline(g.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX).positionType(TaffyPosition.ABSOLUTE));
        if (g.getAttachedWindow() != null) g.addToTopLayer();
    }

    private void hideGhost(UIElement g) {
        g.removeFromTopLayer();
        StyleGroup.importantPipeline(g.getStyle().getLayoutGroup(), l -> l.display(TaffyDisplay.NONE));

    }

    /**
     * Keeps the ghost under the cursor, preserving the grab offset.
     *
     * <p>Anchored by where inside the source the press landed, not by the ghost's own centre, so the
     * ghost sits exactly where the source visually was relative to the pointer. Grabbing a card by
     * its corner and having it jump to centre-on-cursor is the classic tell of a ghost positioned the
     * lazy way.</p>
     */
    private void positionGhost(UIElement g, float mouseX, float mouseY, UIWindow window) {
        UIElement rootElement = window.ui.rootElement;
        var rootCache = rootElement.getRuntimeCache();
        // Physical pointer -> absolute logical -> root-relative, which is what `left`/`top` resolve
        // against for a promoted element (its Taffy node is reparented to the root).
        Vector2f inRoot = Transform2D.apply(rootCache.worldToLocal.get(), mouseX, mouseY);

        final float left = inRoot.x() - rootCache.getX() - grabOffsetX;
        final float top = inRoot.y() - rootCache.getY() - grabOffsetY;
        StyleGroup.importantPipeline(g.getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /** Positional drag — no payload, no drop targeting, activates immediately. What Slider,
     * Scroller and SplitView use, unchanged. */
    public void startDrag(UIElement source, float mouseX, float mouseY, DragListener listener) {
        begin(source, mouseX, mouseY, listener, null, 0f, CgMouseCodes.LEFT_BUTTON);
    }

    /**
     * Positional drag driven by a button other than the left one — the middle-button pan a
     * {@code CanvasView} does.
     *
     * <p>The button has to be declared because <b>a drag ends when the button that started it is
     * released</b>, and {@link UIInputHandler} has no other way to know which that was. It used to
     * assume button 0 unconditionally, which is invisible for every left-button drag in the engine and
     * fatal for any other: the release arrives, nothing ends the drag, pointer capture is dropped
     * because no button is down — and the drag keeps consuming mouse movement forever, with the view
     * sliding around under a pointer nobody is pressing.</p>
     */
    public void startDrag(UIElement source, float mouseX, float mouseY, int button, DragListener listener) {
        begin(source, mouseX, mouseY, listener, null, 0f, button);
    }

    /**
     * Payload drag with the {@linkplain #DEFAULT_THRESHOLD_PX default threshold}. Nothing fires until
     * the pointer has moved far enough, so a click on a draggable element is still a click.
     */
    public void startDrag(UIElement source, float mouseX, float mouseY, Object payload, DragListener listener) {
        begin(source, mouseX, mouseY, listener, payload, DEFAULT_THRESHOLD_PX, CgMouseCodes.LEFT_BUTTON);
    }

    /** As above, with an explicit activation distance in physical pixels. */
    public void startDrag(UIElement source, float mouseX, float mouseY, Object payload,
                          float thresholdPx, DragListener listener) {
        begin(source, mouseX, mouseY, listener, payload, Math.max(0f, thresholdPx), CgMouseCodes.LEFT_BUTTON);
    }

    /**
     * Called by the drag source's own {@code onMouseDown} listener, after it has already decided
     * (via its own hit-test / hover state) that this press should start a drag rather than an
     * ordinary click.
     *
     * <p>Pass the <b>raw</b> pointer position (i.e. straight from {@code MouseEvent.getPosition()});
     * the conversion to the source's local space happens here.</p>
     */
    private void begin(UIElement source, float mouseX, float mouseY, DragListener listener,
                       @Nullable Object payload, float thresholdPx, int button) {
        // A second startDrag while one is live used to overwrite the state outright: the previous
        // drag's listener never heard that it ended, its drop target stayed highlighted, and its
        // ghost stayed promoted. Cancelling first gives the old drag the same defined teardown it
        // would get from Escape.
        if (isDragging()) cancelDrag();

        this.source = source;
        this.listener = listener;
        this.payload = payload;
        this.threshold = thresholdPx;
        this.button = button;
        this.activated = thresholdPx <= 0f;
        this.pressPhysX = mouseX;
        this.pressPhysY = mouseY;
        this.dropTarget = null;

        Vector2f start = source.screenToLocal(mouseX, mouseY);
        this.startX = start.x();
        this.startY = start.y();

        // How far INTO the source the press landed. screenToLocal returns coordinates in the frame
        // getX()/getY() live in — absolute logical units, NOT an offset within the element — so the
        // source's own origin has to come off to get a grab offset. Subtracting startX directly was
        // the bug that made the ghost start at the top-left corner and then track the cursor 1:1.
        var sourceCache = source.getRuntimeCache();
        this.grabOffsetX = start.x() - sourceCache.getX();
        this.grabOffsetY = start.y() - sourceCache.getY();

        // Pointer Events L3 pointer capture: every subsequent event is targeted at the source "as if
        // the pointer is always over the capturing target". Two things follow, and the second is the
        // one that was broken before capture existed:
        //   1. the drag can end anywhere on screen, because the release still reaches the source;
        //   2. no boundary events leak. The spec treats everything during capture as inside the
        //      capturing element's boundary, so :hover no longer flickers across every element the
        //      cursor crosses mid-drag, and mouseenter/mouseleave stop firing on them.
        UIWindow window = source.getAttachedWindow();
        if (window != null) window.getInputHandler().setPointerCapture(source);
    }

    public boolean isDragging() {
        return source != null;
    }

    /** True once a payload drag has passed its activation threshold. Always true for a positional
     * drag, which has no threshold. */
    public boolean isActivated() {
        return activated;
    }

    public UIElement getSource() {
        return source;
    }

    /** The button whose release ends this drag. Meaningless while {@link #isDragging()} is false. */
    public int getButton() {
        return button;
    }

    @Nullable
    public Object getPayload() {
        return payload;
    }

    /**
     * The element currently under the pointer that the drag is being <em>offered</em> to, if any.
     *
     * <p>Being offered is not the same as accepting — see {@link #isDropAccepted()}. Enter/leave fire
     * on whatever the drag crosses regardless, matching the DOM, so a target can react to a drag
     * passing over it without agreeing to receive it.</p>
     */
    @Nullable
    public UIElement getDropTarget() {
        return dropTarget;
    }

    /**
     * Whether {@link #getDropTarget()} accepted the drag on the most recent frame, by calling
     * {@code preventDefault()} on its {@link DragEvent.Over}.
     *
     * <p>Rejection is the default — HTML5 drag-and-drop's one genuinely good idea, kept even though
     * the rest of that API was discarded. Without it, every element in the tree is silently a drop
     * target and a payload lands wherever the pointer happened to be.</p>
     */
    public boolean isDropAccepted() {
        return dropAccepted;
    }

    void tick(float mouseX, float mouseY) {
        if (listener == null) return;

        if (!activated) {
            float dx = mouseX - pressPhysX, dy = mouseY - pressPhysY;
            if (dx * dx + dy * dy < threshold * threshold) return;
            activated = true;
        }

        Vector2f local = source.screenToLocal(mouseX, mouseY);
        listener.onDragUpdate(local.x(), local.y(), startX, startY, local.x() - startX, local.y() - startY);

        UIWindow window = source.getAttachedWindow();
        if (ghost != null && window != null) {
            // Shown here rather than on the threshold transition, so it covers both a zero-threshold
            // positional drag (which activates in begin(), never passing through that branch) and a
            // ghost handed over after the drag already started. Promotion is idempotent, so the
            // isInTopLayer check is the only guard needed.
            if (!ghost.isInTopLayer()) showGhost(ghost);
            positionGhost(ghost, mouseX, mouseY, window);
        }

        if (payload != null) updateDropTarget(mouseX, mouseY);
    }

    /**
     * Re-hit-tests and fires enter/leave/over.
     *
     * <p>Uses the window's <em>geometric</em> hit test, not the input handler's — the latter answers
     * "the drag source" for the whole drag, because that is what pointer capture means.</p>
     */
    private void updateDropTarget(float mouseX, float mouseY) {
        UIWindow window = source.getAttachedWindow();
        if (window == null) return;

        UIElement under = window.getHoveredElement(mouseX, mouseY);
        // The source is dragging itself around; neither it nor anything inside it is a drop target.
        // (A drag ghost in the top layer would otherwise sit under the cursor and swallow every drop.)
        if (isSelfOrInsideSource(under)) under = null;

        if (under != dropTarget) {
            UIElement common = UITreeTraversal.commonAncestor(dropTarget, under);
            fireLeaveChain(dropTarget, common, window);
            fireEnterChain(under, common, window);
            dropTarget = under;
        }

        if (dropTarget != null) {
            var over = new DragEvent.Over(dropTarget, window.getInputHandler().pointerPosition(), source, payload);
            window.getInputHandler().sendInputEvent(dropTarget, over);
            // Re-evaluated every frame, never latched: a target may accept only while some condition
            // holds (a full inventory slot, a disabled panel), and a latched "yes" from an earlier
            // frame would let a drop through after it stopped being valid.
            dropAccepted = over.isDefaultPrevented();
        } else {
            dropAccepted = false;
        }
    }

    private boolean isSelfOrInsideSource(@Nullable UIElement candidate) {
        for (var e = candidate; e != null; e = e.getParent()) {
            if (e == source) return true;
        }
        return false;
    }

    /** Innermost first, mirroring {@code mouseleave} — and sharing its walk, so the two orders cannot
     * drift apart. */
    private void fireLeaveChain(@Nullable UIElement from, @Nullable UIElement common, UIWindow window) {
        var handler = window.getInputHandler();
        UIElement dragSource = source;
        Object carried = payload;
        UITreeTraversal.forEachLeft(from, common,
                e -> handler.sendInputEvent(e, new DragEvent.Leave(e, handler.pointerPosition(), dragSource, carried)));
    }

    /** Outermost first, mirroring {@code mouseenter}. */
    private void fireEnterChain(@Nullable UIElement to, @Nullable UIElement common, UIWindow window) {
        var handler = window.getInputHandler();
        UIElement dragSource = source;
        Object carried = payload;
        UITreeTraversal.forEachEntered(to, common,
                e -> handler.sendInputEvent(e, new DragEvent.Enter(e, handler.pointerPosition(), dragSource, carried)));
    }

    void endDrag(float mouseX, float mouseY) {
        if (listener == null) {
            clear();
            return;
        }
        UIElement droppedOn = dropAccepted ? dropTarget : null;
        UIElement dragSource = source;
        Object droppedPayload = payload;
        UIWindow window = source.getAttachedWindow();

        // Resolved BEFORE the drop is dispatched, because a drop handler is entitled to change the tree
        // and this reads the source's transform.
        Vector2f local = source.screenToLocal(mouseX, mouseY);

        // The DROP fires first, then the source's onDragEnd. That is the web's order — `drop` on the
        // target, then `dragend` on the source — and it is the only order that lets a source ask "did my
        // drag land?" in onDragEnd.
        //
        // It used to be the other way around, and the failure was a good one: a wire dragged onto a port
        // connected AND opened the create-node menu. The port decides which happened by comparing its
        // connection count against a snapshot taken at drag start, so running onDragEnd first meant it
        // always saw the count from before the drop and always concluded the wire had landed on nothing.
        // Both outcomes then happened, in that order, and the menu looked like it had opened for no
        // reason rather than like an ordering bug.
        //
        // Only payload drags are affected: a positional drag (resize, marquee, node move) dispatches no
        // Drop at all, so its onDragEnd is still the first thing to run.
        if (droppedPayload != null && droppedOn != null && window != null) {
            window.getInputHandler().sendInputEvent(droppedOn,
                    new DragEvent.Drop(droppedOn, window.getInputHandler().pointerPosition(),
                            dragSource, droppedPayload));
        }

        listener.onDragEnd(local.x(), local.y());
        clear();
    }

    /**
     * Aborts the drag — Escape, or the source leaving the tree.
     *
     * <p>Modelled on {@code pointercancel}, whose whole purpose is that cleanup has one defined path.
     * The current drop target is told the drag left it, so a target that highlighted itself on enter
     * has a symmetric un-highlight and cannot be stranded lit.</p>
     */
    public void cancelDrag() {
        if (source == null) return;
        UIElement dragSource = source;
        Object cancelledPayload = payload;
        UIElement staleTarget = dropTarget;
        UIWindow window = source.getAttachedWindow();
        DragListener cancelled = listener;

        clear();

        if (window != null) {
            if (staleTarget != null) {
                fireCancelLeave(staleTarget, window, dragSource, cancelledPayload);
            }
            window.getInputHandler().releasePointerCapture();
            window.getInputHandler().sendInputEvent(dragSource,
                    new DragEvent.Cancel(dragSource, window.getInputHandler().pointerPosition(),
                            dragSource, cancelledPayload));
        }
        if (cancelled != null) cancelled.onDragCancel();
    }

    private void fireCancelLeave(UIElement target, UIWindow window, UIElement dragSource,
                                 @Nullable Object cancelledPayload) {
        for (var e = target; e != null; e = e.getParent()) {
            window.getInputHandler().sendInputEvent(e,
                    new DragEvent.Leave(e, window.getInputHandler().pointerPosition(), dragSource, cancelledPayload));
        }
    }

    private void clear() {
        // Before the fields go — hideGhost demotes, and demotion needs the element still attached.
        if (ghost != null) hideGhost(ghost);
        // The ghost is DROPPED, not kept for a future drag, and that is the point rather than an
        // oversight: a retained ghost outlived the drag that registered it and reappeared on unrelated
        // pages the next time anything was dragged. Registration is therefore per drag — see setGhost.
        ghost = null;
        source = null;
        listener = null;
        payload = null;
        dropTarget = null;
        activated = false;
        dropAccepted = false;
        threshold = 0f;
        button = CgMouseCodes.LEFT_BUTTON;
        // The ghost itself is NOT cleared: it belongs to the caller, who set it once and expects it
        // to still be theirs for the next drag.
    }
}
