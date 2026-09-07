package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.surface.SurfaceContext;

/**
 * A one-pixel outline on the node a click would select, with a tag naming it.
 *
 * <p>Registered as a surface overlay, so it is a viewport child: the outline is 1px at any zoom, and it
 * never joins the tree it is describing.</p>
 *
 * <p><b>Nothing under the pointer changes state.</b> A live {@code :hover} would mean the design surface
 * and the running UI disagree about what is being pointed at, and would light a button the designer is
 * only aiming at. The artboard is {@code hit-test: false} for exactly that reason; this draws what
 * {@code :hover} would have shown.</p>
 */
public final class HoverHighlight extends UIElement {

    public static final Name NAME = Name.of("hoverhighlight");

    /** The outline. Its colour is this element's own {@code color}, so a theme sets it. */
    public static final String OVERLAY_CLASS = "__hover-highlight__";

    /** The label at the corner — kind, {@code #id}, size. */
    public static final String TAG_PART = "tag";

    private static final float THICKNESS = 1f;

    private final SurfaceContext ctx;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final UIText tag = new UIText();

    @Nullable
    private UIElement target;

    public HoverHighlight(SurfaceContext ctx) {
        super(NAME);
        this.ctx = ctx;
        addClass(OVERLAY_CLASS);
        set(Attribute.HIT_TEST, false);
        // AND NOT THE ANSWER TO A PICK EITHER. hit-test alone is not enough here: a design surface
        // resolves what is under the pointer with a PICK, which reaches through that attribute on
        // purpose -- so a full-size overlay was the answer to every click on the canvas.
        set(Attribute.HIT_TRANSPARENT, true);

        attachShadow(false);
        tag.set(Attribute.PART, TAG_PART);
        appendStructural(tag);

        connections.add(ctx.picking().onDidChangeHover.connect(this::follow));
    }

    /** What the outline is on, or null. */
    @Nullable
    public UIElement target() {
        return target;
    }

    /** The text the tag is showing — the only observable evidence of what a click would select. */
    public String tagText() {
        return tag.getText();
    }

    private void follow(@Nullable UIElement node) {
        this.target = node;
        tag.setText(node == null ? "" : describe(node));
    }

    /**
     * {@code button #ok 64x20} — the kind, the id when there is one, and the size in logical px.
     *
     * <p>The size is read from the box and is zero when there is none, which is the honest answer for a
     * node that is hidden or has not been laid out yet.</p>
     */
    private static String describe(UIElement node) {
        StringBuilder out = new StringBuilder(node.tagName());
        String id = node.getId();
        if (id != null && !id.isEmpty()) out.append(" #").append(id);
        Box box = node.box();
        if (box != null) {
            out.append("  ").append(Math.round(box.width())).append('x').append(Math.round(box.height()));
        }
        return out.toString();
    }

    /**
     * Puts the tag at the target's top-left, through the box's compositor override.
     *
     * <p>A {@code transform} override rather than an inline {@code left}/{@code top}: writing style here
     * would dirty layout from a post-layout pass, so the tag would trail the pointer by a frame. The
     * override is read by the box tree and reflows nothing, which is what it is for.</p>
     */
    private void placeTag() {
        Box own = box();
        Box tagBox = tag.box();
        float[] rect = CanvasRects.ofLayout(target, this);
        if (own == null || tagBox == null) return;
        if (rect == null) {
            tagBox.setTransform(null);
            return;
        }
        // ABOVE the outline where there is room, inside it where there is not -- a tag drawn off the top
        // of the viewport names nothing.
        float above = rect[1] - tagBox.height();
        float y = above >= 0f ? above : rect[1];
        tagBox.setTransform(Transform.translate(rect[0], y));
    }

    @Override
    protected void connected() {
        super.connected();
        if (document() == null) return;
        document().animation().afterLayout(this, delta -> {
            placeTag();
            return true;
        });
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (box == null) return;
        float[] rect = CanvasRects.ofLayout(target, this);
        if (rect == null) return;
        CanvasRects.outline(paint, rect, THICKNESS,
                getStyle().computed().get(StylePropertyRegistry.COLOR));
    }
}
