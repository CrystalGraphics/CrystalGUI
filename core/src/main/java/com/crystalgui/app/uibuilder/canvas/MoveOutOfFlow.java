package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.surface.snap.AxisLock;
import com.crystalgui.widget.surface.snap.BoxTargets;
import com.crystalgui.widget.surface.snap.SnapAxis;
import com.crystalgui.widget.surface.snap.SnapIndicator;
import com.crystalgui.widget.surface.snap.SnapScene;
import com.crystalgui.widget.surface.snap.SnapSolver;
import com.crystalgui.widget.surface.snap.SnapSuspend;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Dragging an <b>out-of-flow</b> node, with snapping.
 *
 * <p>Only for a node whose {@code position} is absolute. An in-flow node is placed by its parent's
 * layout, so dragging it means reorder or reparent — a different gesture, and not this one. That split is
 * the whole reason {@code TreePolicy.movesItems()} answers false: there is no coordinate to write for a
 * node the parent positions.</p>
 *
 * <h3>The inset it is anchored BY</h3>
 *
 * <p>A node the sheet pinned with {@code right} keeps being right-anchored: writing {@code left} instead
 * would move it now and move it again the moment the parent resized, which is the bug that makes a
 * designed panel drift. So the axis is written on whichever side the node already states, and only
 * {@code left}/{@code top} when it states neither.</p>
 *
 * <p>It draws nothing itself: what a move snapped to goes to {@link SmartGuides}.</p>
 */
public final class MoveOutOfFlow extends UIElement {

    public static final Name NAME = Name.of("moveoutofflow");

    public static final String LAYER_CLASS = "__move-out-of-flow__";

    private final BuilderContext ctx;

    private final UiBuilderDocument document;

    @Nullable
    private UIElement moving;

    /** What the move can snap to, gathered once: an out-of-flow node reflows nothing as it moves. */
    @Nullable
    private SnapScene scene;

    private List<SnapIndicator> indicators = List.of();

    /** Shift's constraint, latched for the gesture. @see AxisLock */
    private final AxisLock lock = new AxisLock();

    public MoveOutOfFlow(BuilderContext ctx, UiBuilderDocument document) {
        super(NAME);
        this.ctx = ctx;
        this.document = document;
        addClass(LAYER_CLASS);
        set(Attribute.HIT_TEST, false);
        set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
    }

    /** Whether this node is one a drag may position at all. */
    public static boolean isMovable(@Nullable UIElement node) {
        return node != null && node.box() != null
                && node.getStyle().computed().get(LayoutProperties.POSITION) == TaffyPosition.ABSOLUTE;
    }

    /** What is being dragged, or null. */
    @Nullable
    public UIElement moving() {
        return moving;
    }

    /** What the last update snapped to. For a test. */
    public List<SnapIndicator> indicators() {
        return indicators;
    }

    /**
     * Begins a move from a raw pointer position.
     *
     * @return whether one started — false for an in-flow node, which is reorder's business
     */
    public boolean begin(@Nullable UIElement node, float rawX, float rawY) {
        if (!isMovable(node)) return false;
        Box box = node.box();
        float startX = box.x();
        float startY = box.y();
        JsonElement before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        float scale = Math.max(0.0001f, CanvasRects.scaleOf(node.parentElement(), this));
        moving = node;

        Drag.start(this, rawX, rawY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                dragged(node, startX, startY, scale, dx, dy, modifiersNow());
            }

            @Override
            public void onDragEnd(float mx, float my) {
                end();
                commit(node, before);
            }

            @Override
            public void onDragCancel() {
                end();
                InlineStyleCodec.decodeInto(JsonOps.INSTANCE, before, node);
            }
        });
        return true;
    }

    /**
     * One drag update, with the modifiers passed in rather than read.
     *
     * <p>Package-private and taking its own state so the constraint is assertable without a pointer:
     * what goes wrong here is that two writers disagree about one number, and that is a property of this
     * method rather than of the gesture around it.</p>
     *
     * @param scale screen pixels per layout pixel in the parent. @see CanvasRects#scaleOf
     * @param dx    how far the pointer has come since the press, in SCREEN pixels
     */
    void dragged(UIElement node, float startX, float startY, float scale,
                 float dx, float dy, int modifiers) {
        // SHIFT CONSTRAINS, and the lock LATCHES: the axis is chosen once, when the hand has
        // committed far enough to mean it, and held while Shift is. @see AxisLock
        SnapAxis free = lock.update(CgModifiers.hasShift(modifiers), dx, dy);
        float wantX = free == SnapAxis.VERTICAL ? startX : startX + dx / scale;
        float wantY = free == SnapAxis.HORIZONTAL ? startY : startY + dy / scale;

        // CTRL SUSPENDS SNAPPING for the drag, and Alt does too here -- it is what this gesture has
        // always spent on it, and unlike a resize it has nothing else to spend it on. The one thing you
        // cannot do with snapping on is put something deliberately NEAR an edge. @see SnapSuspend
        if (SnapSuspend.isSuspended(modifiers) || CgModifiers.hasAlt(modifiers)) {
            show(node, List.of());
            write(node, wantX, wantY);
            return;
        }

        if (scene == null) scene = BoxTargets.sceneFor(node, ctx == null ? null : ctx.artboard());

        // THE BOX AS DRAWN, where the move would put it: a transform travels with its box, so the drawing
        // sits the same distance from the layout origin wherever the box goes. What snaps is what is seen.
        Box box = node.box();
        SnapScene.Outline drawn = BoxTargets.outlineIn(node, node.parentElement());
        if (box == null || drawn == null) {
            show(node, List.of());
            write(node, wantX, wantY);
            return;
        }
        float[] xs = drawn.xs().clone();
        float[] ys = drawn.ys().clone();
        for (int i = 0; i < xs.length; i++) {
            xs[i] += wantX - box.x();
            ys[i] += wantY - box.y();
        }

        // A PINNED AXIS IS NEVER OFFERED TO THE SOLVER, which is the whole of the fix rather
        // than a guard on top of one: solving it and restoring afterwards is what the constraint
        // used to do, and the solver's answer won because it was written last.
        SnapSolver.ShapeSnap snapped = SnapSolver.moveShape(xs, ys, lock.isPinned(SnapAxis.HORIZONTAL),
                lock.isPinned(SnapAxis.VERTICAL), SnapSolver.SCREEN_TOLERANCE, scale, scene);
        show(node, snapped.indicators());
        write(node, wantX + snapped.dx(), wantY + snapped.dy());
    }

    /** Keeps what this update snapped to, and hands it to the guides layer. */
    private void show(UIElement node, List<SnapIndicator> found) {
        indicators = List.copyOf(found);
        if (ctx != null) ctx.smartGuides().show(node.parentElement(), indicators);
    }

    private void end() {
        moving = null;
        scene = null;
        indicators = List.of();
        if (ctx != null) ctx.smartGuides().clear();
        lock.reset();
    }

    /**
     * Whether this node is pinned by its RIGHT edge, and so must keep being moved by it.
     *
     * <p>Public because it is the rule, not an implementation detail: writing {@code left} on a
     * right-anchored node moves it now and moves it again the moment the parent resizes.</p>
     */
    public static boolean anchorsRight(UIElement node) {
        return node.getStyle().computed().isSet(LayoutProperties.RIGHT)
                && !node.getStyle().computed().isSet(LayoutProperties.LEFT);
    }

    /** @see #anchorsRight */
    public static boolean anchorsBottom(UIElement node) {
        return node.getStyle().computed().isSet(LayoutProperties.BOTTOM)
                && !node.getStyle().computed().isSet(LayoutProperties.TOP);
    }

    /**
     * Puts {@code node}'s BORDER box at {@code x}, {@code y} — through the inset it is anchored by.
     *
     * @see MoveOutOfFlow the note on writing the inset the node is anchored by
     * @see #leftInset
     */
    private static void write(UIElement node, float x, float y) {
        boolean rightAnchored = anchorsRight(node);
        boolean bottomAnchored = anchorsBottom(node);
        Box box = node.box();
        Box parent = node.parentElement() == null ? null : node.parentElement().box();
        // A far-side inset is measured from the parent's inner far edge to the node's far margin.
        float farX = box == null || parent == null ? 0f
                : parent.width() - parent.border().right - box.margin().right - box.width();
        float farY = box == null || parent == null ? 0f
                : parent.height() - parent.border().bottom - box.margin().bottom - box.height();

        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (rightAnchored) l.right(Math.round(farX - x));
            else l.left(Math.round(leftInset(node, x)));
            if (bottomAnchored) l.bottom(Math.round(farY - y));
            else l.top(Math.round(topInset(node, y)));
        });
    }

    /**
     * The {@code left} that puts {@code node}'s BORDER box at {@code x} — the position {@code Box.x()}
     * reports and a snap solves for.
     *
     * <p>CSS places the MARGIN box at the inset, measured from inside the containing block's border, so
     * both come off. Written straight in, a margined node landed its margin further on than the guides
     * said, and again at the start of every drag.</p>
     */
    static float leftInset(UIElement node, float x) {
        Box box = node.box();
        Box parent = node.parentElement() == null ? null : node.parentElement().box();
        return x - (parent == null ? 0f : parent.border().left) - (box == null ? 0f : box.margin().left);
    }

    /** @see #leftInset */
    static float topInset(UIElement node, float y) {
        Box box = node.box();
        Box parent = node.parentElement() == null ? null : node.parentElement().box();
        return y - (parent == null ? 0f : parent.border().top) - (box == null ? 0f : box.margin().top);
    }

    /** One edit for the gesture, as the resize handles do. */
    private void commit(UIElement node, JsonElement before) {
        JsonElement after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        if (after.equals(before)) return;
        document.apply(new BuilderEdit.SetInlineStyle(node, before, after));
    }

    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }
}
