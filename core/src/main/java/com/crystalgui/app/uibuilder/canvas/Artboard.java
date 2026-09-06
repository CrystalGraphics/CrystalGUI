package com.crystalgui.app.uibuilder.canvas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

/**
 * One canvas size, with the document's real tree inside it — the thing you design on.
 *
 * <p>A fixed-size frame placed on the surface's plane, holding the live root. Not a picture of the UI: a
 * document opened at 800×480 is the same tree, laid out by the same engine, at that size — which is what
 * makes what you see what a player gets.</p>
 *
 * <pre>{@code
 * Artboard board = new Artboard(document);
 * surface.surface().place(board, 0f, 0f);
 * }</pre>
 *
 * <p>Its size comes from the document's {@code preview.sizes}, first entry, and falls back to 800×480 so
 * a document that declares none still opens.</p>
 */
public final class Artboard extends UIElement {

    public static final Name NAME = Name.of("artboard");

    /** On the frame, for a theme to draw the page edge. */
    public static final String FRAME_CLASS = "__artboard__";

    private static final float DEFAULT_WIDTH = 800f;
    private static final float DEFAULT_HEIGHT = 480f;

    private final UiBuilderDocument document;

    private float width;
    private float height;

    public Artboard(UiBuilderDocument document) {
        super(NAME);
        this.document = document;
        addClass(FRAME_CLASS);
        float[] size = declaredSize(document);
        this.width = size[0];
        this.height = size[1];
        applySize();
        setDesignMode(true);
        append(document.root());
    }

    /** Not {@code document()}: that is {@code UINode}'s, and it answers the window this is shown in. */
    public UiBuilderDocument model() {
        return document;
    }

    public float boardWidth() {
        return width;
    }

    public float boardHeight() {
        return height;
    }

    /**
     * Design or preview, and it is <b>one attribute</b>.
     *
     * <p>{@code hit-test: false} is {@code pointer-events: none} for a whole subtree, so setting it on
     * the frame makes every widget in the document quiescent — no hover, no {@code :hover}, no tooltip,
     * no focus, no wheel, because the engine never looks inside. Preview clears it and the widgets are
     * simply used. The frame is not part of the document, so this is never encoded.</p>
     *
     * <p>The builder still selects through it: {@code Picking} resolves with {@code BoxTree.pick}, which
     * reaches into unhittable subtrees deliberately.</p>
     */
    public Artboard setDesignMode(boolean design) {
        setHitTest(!design);
        return this;
    }

    /** @see #setDesignMode */
    public boolean isDesignMode() {
        return !get(Attribute.HIT_TEST);
    }

    /**
     * How big a pixel is on this page — Minecraft's GUI scale, 1 to 4.
     *
     * <p>A {@code transform}, so the layout underneath stays in logical pixels and nothing reflows: a
     * document designed at 2x has the same box tree as at 1x and is simply drawn twice the size. That is
     * also why hit-testing still lands, since it inverts the same matrix the painter used.</p>
     */
    public Artboard setUiScale(float scale) {
        this.uiScale = scale;
        StyleGroup.inlinePipeline(getStyle().getGeneralGroup(), g -> g
                .transform(Transform.scale(scale, scale))
                .transformOriginX(LengthPercent.ZERO)
                .transformOriginY(LengthPercent.ZERO));
        return this;
    }

    /** @see #setUiScale */
    public float uiScale() {
        return uiScale;
    }

    private float uiScale = 1f;

    /** Resizes the page. What the preset menu and the size matrix write. */
    public Artboard setSize(float width, float height) {
        this.width = width;
        this.height = height;
        applySize();
        return this;
    }

    /** Puts the document's current root back in, after an adopt replaced it. */
    public Artboard resync() {
        removeAll();
        append(document.root());
        return this;
    }

    private void applySize() {
        // INLINE, never IMPORTANT: a page size is this element's own, the way a caller writing
        // style="width: 800px" is -- and the engine writes nothing at an author's !important.
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.width(width).height(height));
    }

    private static float[] declaredSize(UiBuilderDocument document) {
        JsonElement preview = document.header().get("preview");
        if (preview != null && preview.isJsonObject()) {
            JsonElement sizes = preview.getAsJsonObject().get("sizes");
            if (sizes != null && sizes.isJsonArray() && sizes.getAsJsonArray().size() > 0) {
                JsonElement first = sizes.getAsJsonArray().get(0);
                if (first.isJsonArray() && first.getAsJsonArray().size() >= 2) {
                    JsonArray pair = first.getAsJsonArray();
                    return new float[]{pair.get(0).getAsFloat(), pair.get(1).getAsFloat()};
                }
            }
        }
        return new float[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
    }

}
