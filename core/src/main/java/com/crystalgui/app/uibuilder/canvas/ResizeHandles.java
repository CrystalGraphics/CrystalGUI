package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.text.UIText;


import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Eight handles on the selection, writing {@code width} and {@code height} inline.
 *
 * <p>Real elements with real listeners, not something painted: that is what lets them carry a CSS
 * {@code cursor} and take a press through ordinary dispatch, which they can because {@code TreePolicy}
 * answers <em>tree</em> for anything outside the artboard.</p>
 *
 * <h3>One undo step per gesture</h3>
 *
 * <p>A drag writes inline style on every frame so the box resizes under the pointer, and records
 * <b>nothing</b> until it ends — then one {@link BuilderEdit.SetInlineStyle} carrying the whole style map
 * before and after. Recording per frame would put sixty steps in the history for one drag, and the
 * document's own rule is that a gesture that should be one step says so by being one edit.</p>
 *
 * <p><b>Double-click a handle to hug</b>: the size on that axis is withdrawn back to {@code auto}, so a
 * box that was pinned returns to content-sizing. It is Figma's <em>Hug contents</em> and the fastest way
 * out of a pinned inline size.</p>
 */
public final class ResizeHandles extends UIElement {

    public static final Name NAME = Name.of("resizehandles");

    public static final String LAYER_CLASS = "__resize-handles__";

    /** The live size readout shown while a handle is dragged. */
    public static final String BADGE_CLASS = "__resize-badge__";

    /** Where a handle is, which is also its class and the axes it writes. */
    public enum Spot {
        TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
        RIGHT(1, 0), BOTTOM_RIGHT(1, 1), BOTTOM(0, 1),
        BOTTOM_LEFT(-1, 1), LEFT(-1, 0);

        private final int x;
        private final int y;

        Spot(int x, int y) {
            this.x = x;
            this.y = y;
        }

        /** {@code -1} leading, {@code 0} not on this axis, {@code 1} trailing. */
        public int xDirection() {
            return x;
        }

        /** @see #xDirection */
        public int yDirection() {
            return y;
        }

        /** Whether this handle moves both axes, which is the only place an aspect ratio means anything. */
        public boolean isCorner() {
            return x != 0 && y != 0;
        }

        String cssClass() {
            return "__handle-" + name().toLowerCase(Locale.ROOT).replace('_', '-') + "__";
        }
    }

    /**
     * The handle's box, which is also its <b>hit target</b>.
     *
     * <p>Not a decoration size: this element is what takes the press, so shrinking it shrinks what a
     * pointer has to land on. Six logical pixels is about as small as a grab target goes before it stops
     * being reliably hittable, and the sheet rounds it fully so it reads as a small dot on the line
     * rather than as a block sitting over it.</p>
     */
    private static final float SIZE = 6f;

    private final BuilderContext ctx;

    private final UiBuilderDocument document;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final List<UIElement> handles = new ArrayList<>();

    /** The live readout during a drag. @see #showBadge */
    private final UIElement badge = new UIElement();

    private final UIText badgeText = new UIText();

    @Nullable
    private UIElement target;

    public ResizeHandles(BuilderContext ctx, UiBuilderDocument document) {
        super(NAME);
        this.ctx = ctx;
        this.document = document;
        addClass(LAYER_CLASS);
        anchorWithoutCovering(this);

        for (Spot spot : Spot.values()) handles.add(buildHandle(spot));
        badge.addClass(BADGE_CLASS);
        badge.setHitTest(false);
        badge.setDisplayed(false);
        StyleGroup.defaultPipeline(badge.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f));
        badge.append(badgeText);
        append(badge);
    }

    /** What the handles are on, or null when the selection is not exactly one node. */
    @Nullable
    public UIElement target() {
        return target;
    }

    /** The eight, in {@link Spot} order. For a test, and for a theme that wants to find one. */
    public List<UIElement> handles() {
        return List.copyOf(handles);
    }

    /**
     * The layer the handles are placed inside — full size, and never a hit target.
     *
     * <p><b>It must never be the answer to a hit test.</b> A full-size hittable layer over the canvas
     * eats every click that lands on background — you select one node, the layer appears, and nothing on
     * the canvas can be clicked again. That is this codebase's most-repeated failure and {@code
     * Box.search} names it outright.</p>
     *
     * <p>{@code HIT_TRANSPARENT} says exactly that and nothing else: never the answer, children still
     * searched. It was briefly a zero-sized box instead, which achieves the same thing for hit testing by
     * accident and leaves the layer with no area to paint its handles in.</p>
     */
    private static void anchorWithoutCovering(UIElement layer) {
        StyleGroup.defaultPipeline(layer.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
        layer.set(Attribute.HIT_TRANSPARENT, true);
    }

    private UIElement buildHandle(Spot spot) {
        UIElement handle = new UIElement();
        handle.addClass(LAYER_CLASS + spot.cssClass());
        handle.addClass(spot.cssClass());
        StyleGroup.defaultPipeline(handle.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(0f).width(SIZE).height(SIZE));
        // TARGET-ONLY. A handle hears its own press and nothing a descendant was targeted with, which
        // is all eight of them and no bubbling between the layer and the canvas underneath.
        handle.events.getGroup(MouseEvent.Down.class)
                .attachListener((element, event) -> beginResize(spot, event), false, false);
        append(handle);
        return handle;
    }

    /**
     * A press on a handle starts the resize and keeps the press.
     *
     * <p>Nothing else may see it: the press is over the canvas, and letting it through would start a
     * marquee behind the handle the user is holding.</p>
     */
    private void beginResize(Spot spot, MouseEvent.Down event) {
        UIElement node = target;
        Box box = node == null ? null : node.box();
        if (box == null) return;
        event.stopPropagation();
        event.preventDefault();

        if (event.getDetail() >= 2) {
            hug(node, spot);
            return;
        }

        float startWidth = box.width();
        float startHeight = box.height();
        // WHERE IT STARTED, read ONCE. holdOppositeEdge writes an inset derived from these, and reading
        // the LIVE box instead fed its own output back in: each frame moved the origin, the next frame
        // measured from the moved origin and moved it again, so the box shot off screen on the smallest
        // movement. A gesture measures from where it began, never from what it has already done.
        float startX = box.x();
        float startY = box.y();
        JsonElement before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        float zoom = Math.max(0.0001f, ctx.surface().zoom());
        showBadge(node, startWidth, startHeight);

        Drag.start(this, event.getPosition().x(), event.getPosition().y(), new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                int modifiers = modifiersNow();
                // ABOUT THE CENTRE doubles the delta: the handle moves one edge, and holding both edges
                // apart by the same amount is what "about the centre" means for a box whose position is
                // its parent's business.
                float scale = CgModifiers.hasAlt(modifiers) ? 2f : 1f;
                float width = startWidth + spot.xDirection() * dx / zoom * scale;
                float height = startHeight + spot.yDirection() * dy / zoom * scale;

                if (CgModifiers.hasShift(modifiers) && spot.isCorner()) {
                    float[] locked = lockAspect(startWidth, startHeight, width, height);
                    width = locked[0];
                    height = locked[1];
                }
                // A BOX HAS NO NEGATIVE SIZE, and clamping only inside `write` was not enough: the badge
                // read the unclamped number and reported "-64 x -62" while the box sat at zero, so the
                // readout disagreed with the thing it was describing. Clamped once, here, and everything
                // downstream sees the same figure.
                width = Math.max(MIN_SIZE, width);
                height = Math.max(MIN_SIZE, height);
                write(node, spot, width, height);
                // AND THE EDGE YOU GRABBED FOLLOWS THE POINTER. Writing a size alone anchors the box at
                // its own origin, so dragging the LEFT handle left grew it to the RIGHT -- the top-left
                // handle resized towards the bottom-right, which is the one thing a corner handle must
                // never do. Only an out-of-flow node can be held in place: its inset is ours to write.
                // An in-flow one is positioned by its parent, so its origin is not ours to move and the
                // box still grows from where the layout put it.
                holdOppositeEdge(node, spot, startX, startY, width - startWidth, height - startHeight);
                showBadge(node, width, height);
            }

            @Override
            public void onDragEnd(float mx, float my) {
                hideBadge();
                commit(node, before);
            }

            @Override
            public void onDragCancel() {
                hideBadge();
                InlineStyleCodec.decodeInto(JsonOps.INSTANCE, before, node);
            }
        });
    }

    /**
     * The size a corner drag takes with the aspect ratio held, as {@code {width, height}}.
     *
     * <p><b>A projection, with no branch in it.</b> A corner under an aspect lock can only travel along
     * the box's own diagonal, so the answer is the point on that line nearest the one the hand asked for
     * — a least-squares projection, continuous everywhere by construction.</p>
     *
     * <p>Two branching versions came before it and both burst. The first compared raw pixel deltas and
     * applied the aspect RATIO to the winner: different units, so the arms disagreed exactly where they
     * swapped. The second compared the proposed SCALES, which is continuous only while both sit on the
     * same side of 1 — on a corner drag where one axis grows as the other shrinks they straddle it, and
     * the swap jumped from x1.05 to x0.95.</p>
     *
     * <p>A purely horizontal drag therefore moves the corner LESS than the pointer, which is correct
     * rather than sluggish: the corner is constrained to the diagonal, so only the component along it
     * counts.</p>
     */
    static float[] lockAspect(float startWidth, float startHeight, float width, float height) {
        float w = Math.max(MIN_SIZE, startWidth);
        float h = Math.max(MIN_SIZE, startHeight);
        float scale = 1f + (w * (width - startWidth) + h * (height - startHeight)) / (w * w + h * h);
        // Both sides stay whole: clamping each axis afterwards would hold the size and lose the shape,
        // which is the one thing this method exists to keep.
        scale = Math.max(scale, MIN_SIZE / Math.min(w, h));
        return new float[]{w * scale, h * scale};
    }

    /** One pixel, not zero: a box with no extent cannot be grabbed again to undo the drag. */
    private static final float MIN_SIZE = 1f;

    /**
     * Keeps the edge OPPOSITE the handle where it was, by moving the node's own inset.
     *
     * <p>Only for a node the builder positions — one in flow has no inset of its own, and its origin is
     * the parent's to decide. @see MoveOutOfFlow#isMovable</p>
     */
    private static void holdOppositeEdge(UIElement node, Spot spot,
                                         float startX, float startY, float growX, float growY) {
        if (!MoveOutOfFlow.isMovable(node)) return;
        UIElement parent = node.parentElement();
        Box parentBox = parent == null ? null : parent.box();
        if (parentBox == null) return;

        // The edge that must not move is the one the handle is NOT on: dragging the left edge pins the
        // right, so the left inset absorbs the whole size change. Everything here is measured from the
        // START of the gesture -- see the note where startX is taken.
        boolean rightAnchored = MoveOutOfFlow.anchorsRight(node);
        boolean bottomAnchored = MoveOutOfFlow.anchorsBottom(node);
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (spot.xDirection() < 0 && !rightAnchored) l.left(Math.round(startX - growX));
            if (spot.yDirection() < 0 && !bottomAnchored) l.top(Math.round(startY - growY));
        });
    }

    /** Writes the axes this handle owns, and only those — a side handle must not pin the other one. */
    private static void write(UIElement node, Spot spot, float width, float height) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (spot.xDirection() != 0) l.width(Math.max(MIN_SIZE, Math.round(width)));
            if (spot.yDirection() != 0) l.height(Math.max(MIN_SIZE, Math.round(height)));
        });
    }

    /**
     * The live size readout, and what the box is going to <b>ignore</b>.
     *
     * <p>A {@code flex-grow} child's main-axis size is the parent's to decide, so writing one from a
     * handle produces a number the layout discards. The badge says so rather than letting the drag look
     * broken — which is the same thing the pinned-size lint will offer to fix.</p>
     */
    private void showBadge(UIElement node, float width, float height) {
        String text = Math.round(width) + " x " + Math.round(height);
        if (growsOnMainAxis(node)) text += "   pinned - flex-grow ignored";
        badgeText.setText(text);
        badge.setDisplayed(true);
    }

    /**
     * Whatever the platform reports now.
     *
     * <p>A drag callback carries no modifiers of its own — it reports where the pointer went, not what
     * the other hand is doing — so a gesture that changes meaning under Shift or Alt has to ask. The
     * engine's own Select tool asks the same way.</p>
     */
    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    private void hideBadge() {
        badge.setDisplayed(false);
    }

    /** Whether the parent will overrule a size written on this node's main axis. */
    private static boolean growsOnMainAxis(UIElement node) {
        Float grow = node.getStyle().computed().get(LayoutProperties.FLEX_GROW);
        return grow != null && grow > 0f;
    }

    /** Back to {@code auto} on this handle's axes — Figma's <em>Hug contents</em>. */
    private void hug(UIElement node, Spot spot) {
        JsonElement before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (spot.xDirection() != 0) l.widthAuto();
            if (spot.yDirection() != 0) l.heightAuto();
        });
        commit(node, before);
    }

    /**
     * The whole gesture as one edit.
     *
     * <p>Applied rather than merely recorded, which reads oddly and is right: the tree already carries
     * the new value, so {@code apply()} rewrites what is already there and leaves an entry that undoes
     * back to {@code before}. Recording without applying would put an edit in the history whose
     * {@code undo} has never had a matching {@code apply}.</p>
     */
    private void commit(UIElement node, JsonElement before) {
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (after.equals(before)) return;
        document.apply(new BuilderEdit.SetInlineStyle(node, before, after));
    }

    /**
     * Notes what is selected; <b>shows or hides on the next frame</b>.
     *
     * <p>Selection changes while a press is being dispatched — the tool selects from inside
     * {@code pointerDown} — and this codebase's rule is that a widget must never restructure the
     * elements it is being clicked on. Toggling {@code display} here tears boxes out from under the
     * walk in progress, which is the {@code "Cannot read field events because path[i] is null"} failure
     * {@code Inspector.inspect} records and why that method defers too: event, then a flag, then one
     * change next frame.</p>
     */
    /** {@code -Dcrystalgui.builder.diagnose=true} — one line per frame from the handle layer. */
    private static final boolean DIAGNOSE =
            Boolean.getBoolean("crystalgui.builder.diagnose");

    private void followSelection() {
        List<UIElement> selected = ctx.builderSelection().nodes();
        target = selected.size() == 1 ? selected.get(0) : null;
        pendingVisibility = true;
    }

    /** @see #followSelection */
    private boolean pendingVisibility = true;

    /**
     * Shows the handles only when there is a laid-out node to put them on.
     *
     * <p><b>A selected node with no box is the ordinary state</b>, not an error: a document whose tab is
     * not in front is hidden, and hidden means no boxes. {@code place()} then has nothing to measure and
     * returns — leaving eight handles at their layer's own origin, stacked in the corner of the canvas,
     * which is what "the handles are jumbled up in the top left" was. Measured in the harness: 579 frames
     * with a target and no box.</p>
     *
     * <p>Re-checked every frame rather than only when the selection changes, because the box appearing
     * and disappearing is not a selection change and nothing announces it.</p>
     */
    /** The class on a handle whose axis the parent will overrule. @see #showBadge */
    public static final String OVERRULED_CLASS = "__handle-overruled__";

    /**
     * Marks the handles whose axis the layout is going to ignore.
     *
     * <p>Drawn before the drag rather than explained after it: a {@code flex-grow} child's main-axis size
     * is computed by its parent, so dragging that handle writes a number nothing reads.</p>
     */
    private void markOverruledHandles() {
        boolean overruled = target != null && growsOnMainAxis(target);
        boolean column = target != null && target.parentElement() != null
                && target.parentElement().getStyle().computed()
                        .get(LayoutProperties.FLEX_DIRECTION) == FlexDirection.COLUMN;
        for (int i = 0; i < handles.size(); i++) {
            Spot spot = Spot.values()[i];
            boolean onMainAxis = column ? spot.yDirection() != 0 : spot.xDirection() != 0;
            UIElement handle = handles.get(i);
            boolean wanted = overruled && onMainAxis;
            if (handle.hasClass(OVERRULED_CLASS) != wanted) {
                if (wanted) handle.addClass(OVERRULED_CLASS);
                else handle.removeClass(OVERRULED_CLASS);
            }
        }
    }

    private void applyVisibility() {
        // DESIGN MODE IS PART OF THE ANSWER, and leaving it out is why handles came back during a
        // preview. BuilderEditor hides them when the mode flips, but this runs every frame from an
        // afterLayout hook and re-showed them on the next one -- a one-shot instruction losing to a
        // standing rule. The rule has to state the whole condition.
        boolean wanted = ctx.isDesignMode() && target != null && target.box() != null;
        pendingVisibility = false;
        if (isDisplayed() != wanted) setDisplayed(wanted);
    }

    /**
     * Puts each handle on its corner, through the compositor override.
     *
     * <p>Post-layout, so it reads a measured box; a {@code transform} rather than an inline offset,
     * because writing style from here would dirty layout and the handles would trail the box by a frame.
     * </p>
     */
    private void place() {
        Box own = box();
        float[] rect = CanvasRects.of(target, this);
        if (DIAGNOSE) {
            CrystalGuiCore.LOGGER.info("[handles] place target={} targetBox={} ownBox={} rect={}",
                    target, target == null ? null : target.box(), own,
                    rect == null ? null : java.util.Arrays.toString(rect));
        }
        if (own == null || rect == null) return;
        float half = SIZE * 0.5f;
        for (int i = 0; i < handles.size(); i++) {
            Spot spot = Spot.values()[i];
            Box handleBox = handles.get(i).box();
            if (handleBox == null) continue;
            float x = rect[0] + (rect[2] * (spot.xDirection() + 1) * 0.5f) - half;
            float y = rect[1] + (rect[3] * (spot.yDirection() + 1) * 0.5f) - half;
            handleBox.setTransform(Transform.translate(x, y));
        }
        Box badgeBox = badge.box();
        if (badgeBox != null) {
            // BELOW THE BOX'S BOTTOM-LEFT, where it does not sit under the pointer that is dragging.
            badgeBox.setTransform(Transform.translate(rect[0], rect[1] + rect[3] + half));
        }
    }

    @Override
    protected void connected() {
        super.connected();
        connections.add(ctx.builderSelection().onChanged.connect(this::followSelection));
        followSelection();
        if (document() == null) return;
        if (DIAGNOSE) CrystalGuiCore.LOGGER.info("[handles] connected, registering afterLayout");
        document().animation().afterLayout(this, delta -> {
            applyVisibility();
            markOverruledHandles();
            place();
            return true;
        });
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }
}
