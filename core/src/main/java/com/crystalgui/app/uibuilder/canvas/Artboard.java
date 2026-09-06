package com.crystalgui.app.uibuilder.canvas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.StyleGroup;
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
