package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.surface.SurfaceContext;

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

        String cssClass() {
            return "__handle-" + name().toLowerCase(Locale.ROOT).replace('_', '-') + "__";
        }
    }

    /** Never smaller than this on screen, whatever the zoom — a handle you cannot hit is not one. */
    private static final float SIZE = 8f;

    private final SurfaceContext ctx;

    private final UiBuilderDocument document;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final List<UIElement> handles = new ArrayList<>();

    @Nullable
    private UIElement target;

    public ResizeHandles(SurfaceContext ctx, UiBuilderDocument document) {
        super(NAME);
        this.ctx = ctx;
        this.document = document;
        addClass(LAYER_CLASS);
        anchorWithoutCovering(this);

        for (Spot spot : Spot.values()) handles.add(buildHandle(spot));
        connections.add(ctx.selection().onChanged.connect(this::followSelection));
        followSelection();
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
        JsonElement before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        float zoom = Math.max(0.0001f, ctx.surface().zoom());

        Drag.start(this, event.getPosition().x(), event.getPosition().y(), new Drag.Listener() {
            @Override
            public void onDragUpdate(float mx, float my, float sx, float sy, float dx, float dy) {
                write(node, spot, startWidth + spot.xDirection() * dx / zoom,
                        startHeight + spot.yDirection() * dy / zoom);
            }

            @Override
            public void onDragEnd(float mx, float my) {
                commit(node, before);
            }

            @Override
            public void onDragCancel() {
                InlineStyleCodec.decodeInto(JsonOps.INSTANCE, before, node);
            }
        });
    }

    /** Writes the axes this handle owns, and only those — a side handle must not pin the other one. */
    private static void write(UIElement node, Spot spot, float width, float height) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), l -> {
            if (spot.xDirection() != 0) l.width(Math.max(0f, Math.round(width)));
            if (spot.yDirection() != 0) l.height(Math.max(0f, Math.round(height)));
        });
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

    private void followSelection() {
        List<UIElement> selected = ctx.selection().items();
        target = selected.size() == 1 ? selected.get(0) : null;
        setDisplayed(target != null);
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
    }

    @Override
    protected void connected() {
        super.connected();
        if (document() == null) return;
        document().animation().afterLayout(this, delta -> {
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
