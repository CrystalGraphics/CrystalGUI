package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgModifiers;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Dragging an <b>out-of-flow</b> node, with snapping and the guides that explain it.
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
 * <p>The overlay draws the guides it snapped to. They are viewport-space and one pixel at any zoom, like
 * every other canvas overlay, and they are gone the moment the drag ends — a guide that outlives its
 * gesture is a line the designer has to work out the meaning of.</p>
 */
public final class MoveOutOfFlow extends UIElement {

    public static final Name NAME = Name.of("smartguides");

    public static final String LAYER_CLASS = "__smart-guides__";

    /** How close counts, in SCREEN pixels — so it feels the same at every zoom. */
    private static final float TOLERANCE = 6f;

    /** Matched to {@code SelectionOutline}'s: the two mark the same edge and must read as one line. */
    private static final float GUIDE_THICKNESS = 1f;

    private final BuilderContext ctx;

    private final UiBuilderDocument document;

    @Nullable
    private UIElement moving;

    private List<Snap.Guide> guides = List.of();

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

    /** The alignments the last update took. For a test, and for anyone reading the overlay. */
    public List<Snap.Guide> guides() {
        return List.copyOf(guides);
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
        float zoom = Math.max(0.0001f, ctx.surface().zoom());
        moving = node;

        Drag.start(this, rawX, rawY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                int modifiers = modifiersNow();
                float wantX = startX + dx / zoom;
                float wantY = startY + dy / zoom;

                // SHIFT CONSTRAINS to the axis the hand actually committed to, which is measured from the
                // whole gesture rather than the last frame -- otherwise a slow diagonal flickers between
                // the two.
                if (CgModifiers.hasShift(modifiers)) {
                    if (Math.abs(dx) >= Math.abs(dy)) wantY = startY;
                    else wantX = startX;
                }

                // ALT SUSPENDS SNAPPING for the drag. Every editor spends Alt on this, because the one
                // thing you cannot do with snapping on is put something NEAR an edge.
                if (CgModifiers.hasAlt(modifiers)) {
                    guides = List.of();
                    write(node, wantX, wantY);
                    return;
                }
                Snap.Result snapped = Snap.of(node, wantX, wantY, TOLERANCE / zoom);
                guides = snapped.guides();
                write(node, snapped.x(), snapped.y());
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

    private void end() {
        moving = null;
        guides = List.of();
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

    /** @see MoveOutOfFlow the note on writing the inset the node is anchored by */
    private static void write(UIElement node, float x, float y) {
        boolean rightAnchored = anchorsRight(node);
        boolean bottomAnchored = anchorsBottom(node);
        Box parent = node.parentElement() == null ? null : node.parentElement().box();
        float parentWidth = parent == null ? 0f : parent.width();
        float parentHeight = parent == null ? 0f : parent.height();
        float width = node.box() == null ? 0f : node.box().width();
        float height = node.box() == null ? 0f : node.box().height();

        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (rightAnchored) l.right(Math.round(parentWidth - x - width));
            else l.left(Math.round(x));
            if (bottomAnchored) l.bottom(Math.round(parentHeight - y - height));
            else l.top(Math.round(y));
        });
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

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (box == null || guides.isEmpty() || moving == null) return;
        UIElement parent = moving.parentElement();
        if (parent == null) return;
        float[] area = CanvasRects.ofLayout(parent, this);
        if (area == null) return;
        float zoom = Math.max(0.0001f, ctx.surface().zoom());
        int colour = getStyle().computed().get(StylePropertyRegistry.COLOR);

        // CENTRED ON THE LINE IT MARKS, not starting at it.
        //
        // A guide and the selection outline mark the same edge and have to look like one line. The
        // outline hugs its box from OUTSIDE, so it occupies the pixel before the edge; a guide drawn
        // from the coordinate occupies the pixel after it, and the two sit side by side as a two-pixel
        // band where a reader expects one. They coincided before the outline moved out, which is why
        // this only started reading as crooked then.
        //
        // Half the stroke, so the coordinate stays the guide's CENTRE at any zoom -- which is also what
        // makes it agree with an edge it aligned to on the far side of the canvas, where there is no
        // outline to match and the true line is all there is.
        float half = GUIDE_THICKNESS * 0.5f;
        for (Snap.Guide guide : guides) {
            if (guide.vertical()) {
                paint.fillRect(area[0] + guide.at() * zoom - half, area[1] + guide.from() * zoom,
                        GUIDE_THICKNESS, Math.max(1f, (guide.to() - guide.from()) * zoom), colour);
            } else {
                paint.fillRect(area[0] + guide.from() * zoom, area[1] + guide.at() * zoom - half,
                        Math.max(1f, (guide.to() - guide.from()) * zoom), GUIDE_THICKNESS, colour);
            }
        }
    }
}
