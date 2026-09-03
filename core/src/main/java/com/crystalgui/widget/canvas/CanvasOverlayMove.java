package com.crystalgui.widget.canvas;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * Makes a floating panel draggable by a handle, and keeps it inside its container.
 *
 * <h3>Extracted because the second consumer arrived</h3>
 * <p>All of this lived inside {@code MainPreviewPanel}, and every paragraph below is a bug that was
 * shipped once. The Blackboard needs exactly the same behaviour, and re-deriving it would have meant
 * making all three mistakes again — which is the point at which a private method becomes a class.</p>
 *
 * <h3>Three things that are not obvious and cost a session each</h3>
 *
 * <p><b>{@code getX()} is not in the same space as {@code left}.</b> It is expressed in the frame
 * {@code screenToLocal} maps into, which carries the root transform, while {@code left} is an inset
 * inside the containing block. Assigning one to the other teleports the panel by the difference on the
 * first press, before the pointer has moved at all. What {@code left} means is the offset within the
 * containing block, so that is what is measured.</p>
 *
 * <p><b>{@code resizeOriginLeft()} used not to be the answer either</b> for a panel the stylesheet anchors
 * by {@code right}/{@code bottom}: its {@code left} inset is {@code auto}, that method answered 0, and
 * that was the same teleport wearing a different hat. It now measures the offset — the very thing this
 * class does below — precisely because the warning here never reached {@code UIResizer}, which reads it
 * as a leading-edge resize's origin and threw both floating panels into the corner on a press.</p>
 *
 * <p><b>Clamping only while dragging is not enough.</b> The position is written once and then stays, so
 * shrinking the container slides its edge past a panel that never moved — which looks like the panel
 * sinking behind a border, points at z-order, and is nowhere near it. {@link #reclampIfPlaced()} is what
 * a container calls when it resizes.</p>
 */
public final class CanvasOverlayMove {

    /** The panel being moved — used only for geometry, never mutated except through the style pipeline. */
    private final UIElement panel;

    private final ContainingBlock block;

    /**
     * How far across the container a panel's centre must sit before it anchors to the FAR edge.
     *
     * <p>Not the midpoint, which is the obvious value and is wrong in practice: a panel sitting anywhere
     * past halfway — including squarely in the middle of the graph — would latch to the right or bottom and
     * then travel with an edge it is nowhere near. Anchoring should mean "this belongs to that edge", and
     * at 50%% a panel qualifies for it by a pixel.</p>
     *
     * <p>Biased towards the start edge, so the far anchor is something a panel opts into by genuinely being
     * over there. A panel in the middle stays put when the container grows, which is what it looks like it
     * should do.</p>
     */
    private static final float FAR_EDGE_FRACTION = 0.7f;

    /** Panel origin at the moment a move began, in the containing block's own space. */
    private float dragLeft;
    private float dragTop;

    /** True once dragged, so the position is the user's rather than the stylesheet's. @see #reclampIfPlaced */
    private boolean placed;

    /**
     * How to find the box a panel is positioned inside.
     *
     * <p>An interface because {@code UIElement.resizeContainingBlock()} is {@code protected}, so only the
     * panel itself can answer — which is correct: whether an element is out of flow, and against what, is
     * its own business rather than a helper's guess.</p>
     */
    @FunctionalInterface
    public interface ContainingBlock {
        @Nullable
        UIElement get();
    }

    private CanvasOverlayMove(UIElement panel, ContainingBlock block) {
        this.panel = panel;
        this.block = block;
    }

    /**
     * Installs the gesture.
     *
     * @param handle what starts a move — a title bar, typically. It must be hit-testable, and anything
     *               inside it that should not start a move must take the press itself
     */
    public static CanvasOverlayMove install(UIElement panel, UIElement handle, ContainingBlock block) {
        CanvasOverlayMove move = new CanvasOverlayMove(panel, block);
        handle.onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x(), rawY = event.getPosition().y();
            // A synthesized activation press (Space/Enter on a focused element) carries the cursor's
            // position, which may be nowhere near the handle. Honouring one would teleport the panel.
            if (!handle.containsSurfacePoint(rawX, rawY)) return;

            UIDocument window = panel.document();
            UIElement container = block.get();
            if (window == null || container == null) return;

            Box panelBox = panel.box();
            Box containerBox = container.box();
            if (panelBox == null || containerBox == null) return;
            move.dragLeft = panelBox.x() - containerBox.x();
            move.dragTop = panelBox.y() - containerBox.y();
            Drag.start(handle, rawX, rawY,
                    (mouseX, mouseY, startX, startY, deltaX, deltaY) -> move.moveBy(deltaX, deltaY));
            event.stopPropagation();
        }, false, true);
        return move;
    }

    private void moveBy(float deltaX, float deltaY) {
        placed = true;
        placeAt(dragLeft + deltaX, dragTop + deltaY);
    }

    /**
     * Writes a position, clamped to the containing block.
     *
     * <p>The same clamp {@code UIResizer} applies to a resize. Without it the panel goes straight out of
     * the canvas — and since a viewport is {@code overflow: hidden}, it does not end up somewhere awkward,
     * it is simply <b>gone</b>, with no edge left to grab it back by. Unity states the same guarantee for
     * its Blackboard: you cannot drag it off the graph and lose it.</p>
     *
     * <p><b>Anchored to the nearer edge on each axis</b>, so a panel tracks a resizing container in both
     * directions rather than only inwards — see the comment in the body, which is where that bug is
     * recorded.</p>
     */
    public void placeAt(float wantedLeft, float wantedTop) {
        UIElement container = block.get();
        if (container == null) return;

        // NEITHER BOX MAY BE ZERO says the note below, and a null box is the same statement -- the
        // node is checked above and its box was not, which is the difference between the old engine's
        // always-present cache and this one's.
        Box containerBox = container.box();
        Box panelBox = panel.box();
        float containerWidth = containerBox == null ? 0f : containerBox.width();
        float containerHeight = containerBox == null ? 0f : containerBox.height();
        float panelWidth = panelBox == null ? 0f : panelBox.width();
        float panelHeight = panelBox == null ? 0f : panelBox.height();

        // NEITHER BOX MAY BE ZERO, and this is the whole of the bug it fixes.
        //
        // Opening, closing or resizing a region relayouts the canvas, and for one frame a box measures
        // zero -- so the clamp below computes max = 0 - width, floors it at 0, and writes left: 0; top: 0.
        // The write is at INLINE origin and permanent, so the panel does not drift back: it goes to the
        // canvas's top-left corner and stays there. Everything absolutely positioned in the graph flashes
        // to the origin on that frame; the ones re-placed every frame recover, and one placed once did not.
        //
        // A zero box carries no information about where anything belongs, so the only correct response is
        // to leave the position alone until there is something to clamp against.
        if (containerWidth <= 0f || containerHeight <= 0f) return;
        if (panelWidth <= 0f || panelHeight <= 0f) return;

        float left = Math.max(0f, Math.min(Math.max(0f, containerWidth - panelWidth), wantedLeft));
        float top = Math.max(0f, Math.min(Math.max(0f, containerHeight - panelHeight), wantedTop));

        // ANCHORED TO THE NEARER EDGE ON EACH AXIS, which is what makes a panel track a resizing
        // container in BOTH directions. A clamp only ever REDUCES, so shrinking pushes the panel in and
        // expanding gives the space back with nothing to pull it out again -- `left` is the stored number
        // and it genuinely has not changed.
        //
        // ONE INSET PER AXIS, the other explicitly `auto`. With both set and a definite size Taffy
        // resolves in favour of the START edge, so a stale `left` silently wins and the anchor does
        // nothing -- the same rule this code already relies on for a panel that is never dragged.
        //
        // Derived from position rather than stored: the anchor is whichever edge the panel's centre is
        // nearer, recomputed for free after a drag or a restore.
        boolean toRight = left + panelWidth / 2f > containerWidth * FAR_EDGE_FRACTION;
        boolean toBottom = top + panelHeight / 2f > containerHeight * FAR_EDGE_FRACTION;
        float right = Math.max(0f, containerWidth - (left + panelWidth));
        float bottom = Math.max(0f, containerHeight - (top + panelHeight));

        // No-ops when unchanged: replaceOrPutCandidate drops an identical value, which is what lets this
        // run every frame without re-dirtying layout forever.
        StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(), l -> {
            if (toRight) l.leftAuto().right(right);
            else l.rightAuto().left(left);
            if (toBottom) l.topAuto().bottom(bottom);
            else l.bottomAuto().top(top);
        });
    }

    /**
     * Re-clamps after the container changed size. Call it per frame; it is cheap and idempotent.
     *
     * <p><b>The offset is MEASURED, not remembered and not read from an inset.</b> Both of those were
     * tried and both are wrong in a way that only shows up once the anchor can change:</p>
     *
     * <ul>
     *   <li>{@code resizeOriginLeft()} reads the {@code left} inset, which is {@code auto} on a
     *       right-anchored panel and answers 0 — so the clamp hauled the panel leftwards every frame and
     *       it could not be dragged right at all.</li>
     *   <li>A remembered {@code left} is worse: holding it constant across a resize is the exact opposite
     *       of anchoring to the right edge, so the clamp and the anchor pulled against each other and the
     *       panel oscillated.</li>
     * </ul>
     *
     * <p>The measured offset is neither. It is where the panel actually <em>is</em>, whichever inset put it
     * there — so a right-anchored panel that Taffy already moved with the edge reports its new position and
     * the clamp is a no-op, while a shrinking container leaves it out of range and the clamp pulls it in.
     * That is the whole job, and it is the only reading that is true under both anchors.</p>
     */
    public void reclampIfPlaced(float originLeft, float originTop) {
        if (!placed) return;
        // NOT WHILE A DRAG IS LIVE. The clamp reads the panel's MEASURED box, which lags the drag by a
        // frame -- so running both writes last frame's position back over the one the pointer just asked
        // for, and the panel simply will not move. The clamp exists for a container that resized, and a
        // drag is not that.
        UIDocument window = panel.document();
        if (window != null && window.input().mode(Drag.class) != null) return;
        // The arguments are IGNORED, and kept only so the call site reads unchanged.
        UIElement container = block.get();
        if (container == null) return;
        Box panelBox = panel.box();
        Box containerBox = container.box();
        if (panelBox == null || containerBox == null) return;
        placeAt(panelBox.x() - containerBox.x(), panelBox.y() - containerBox.y());
    }

    /**
     * Marks the panel as deliberately positioned, without moving it.
     *
     * <p>For a rect restored from a document. {@link #reclampIfPlaced} is gated on this, and it used to be
     * set only by a drag — so a panel whose position came from the file did not track its container at all
     * after a relaunch, and started doing so the moment it was clicked once. <b>A position read from a file
     * is every bit as deliberate as one dragged to</b>, and the click that appeared to fix it was only ever
     * setting this flag.</p>
     *
     * <p>Takes no coordinates, and that is the correction: it used to seed a remembered position, back when
     * the re-clamp worked from one. It measures now — see {@link #reclampIfPlaced} for why that is the only
     * reading true under both anchors — so a seed would be a number nothing reads.</p>
     */
    public void markPlaced() {
        placed = true;
    }

    public boolean isPlaced() {
        return placed;
    }
}
