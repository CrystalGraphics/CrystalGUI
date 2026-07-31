package com.crystalgui.graph.shader;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.shadergraph.CgPreviewRenderer;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.UIElement;

/**
 * Paints one node's rendered thumbnail into its {@code __preview__} slot.
 *
 * <h3>Why this lives in {@code graph.shader} and not with the graph widgets</h3>
 * <p>It holds a {@link CgPreviewRenderer} in a <b>field</b>, and a field descriptor resolves at class
 * load rather than on first call. Putting it in {@code ui.elements.graph} would drag CrystalGraphics core
 * into a package a dedicated server loads, and the first headless test to touch a graph widget would fail
 * with {@code NoClassDefFoundError} naming a class it never asked for. This package is already the
 * documented exception — see {@link ShaderGraphBridge}.</p>
 *
 * <h3>The texture is upside down, and that is not a bug to work around silently</h3>
 * <p>GL's framebuffer origin is bottom-left and the UI's is top-left, so the V coordinate is flipped when
 * drawing. Every render-to-texture path in every engine deals with this; doing it here, once, at the only
 * place an FBO becomes a UI quad, is better than flipping the projection and having every future preview
 * shader author wonder why their maths is inverted.</p>
 */
public class ShaderNodePreview extends UIElement {

    private final CgPreviewRenderer renderer;
    private final String nodeId;

    /** The class {@code default.css} sizes this to its slot through. */
    public static final String PREVIEW_IMAGE_CLASS = "__preview-image__";

    public ShaderNodePreview(CgPreviewRenderer renderer, String nodeId) {
        this.renderer = renderer;
        this.nodeId = nodeId;
        addClass(PREVIEW_IMAGE_CLASS);
        // Nothing is ever clicked here, and letting it take hits would put a dead rectangle over the
        // middle of the node where a drag on the body should start.
        setHitTest(false);
    }

    public String nodeId() {
        return nodeId;
    }

    @Override
    protected void paintSelf(CgUiPaintContext ctx) {
        super.paintSelf(ctx);

        CgTexture texture = renderer.textureOf(nodeId);
        // Nothing yet: the node is new, or its turn in the budget has not come round. Painting nothing
        // is right — the slot keeps its background, so an unrendered preview reads as "not yet" rather
        // than as a hole in the node.
        if (!(texture instanceof CgTexture2D)) return;

        float x = getRuntimeCache().getX();
        float y = getRuntimeCache().getY();
        float w = getRuntimeCache().getWidth();
        float h = getRuntimeCache().getHeight();
        if (w <= 0f || h <= 0f) return;

        // CONTAIN, not stretch. The target is square and the slot is whatever the node's width made it,
        // so filling the slot turns every sphere into an ellipse — which reads as a broken preview
        // rather than as a wide box, because a sphere is the one shape everyone knows the shape of.
        // Letterboxing instead keeps the picture honest at any node width.
        float side = Math.min(w, h);
        float ox = x + (w - side) * 0.5f;
        float oy = y + (h - side) * 0.5f;

        // v1 and v0 swapped: the flip described in the class docs.
        ctx.drawImage((CgTexture2D) texture, ox, oy, side, side, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
    }
}
