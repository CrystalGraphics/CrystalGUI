package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.app.uibuilder.BuilderSelection;

/**
 * What is selected, and what is laying it out.
 *
 * <p>The selection in the accent colour; <b>its parent in a second, dimmer outline</b>, which is Figma's
 * and is not decoration: in a flex tree the answer to "why is this where it is" is always the parent, and
 * a surface that shows only the selection makes the designer click around to find it.</p>
 *
 * <p>A viewport child, so every stroke is 1px at any zoom, and nothing is added to the tree being
 * designed — a class on the selected element would be encoded, and the document would save the
 * selection.</p>
 */
public final class SelectionOutline extends UIElement {

    public static final Name NAME = Name.of("selectionoutline");

    /** The selection's stroke reads this element's {@code color}; the parent's reads its {@code border-color}. */
    public static final String OVERLAY_CLASS = "__selection-outline__";

    private static final float THICKNESS = 1f;

    private final BuilderContext builder;

    public SelectionOutline(BuilderContext builder) {
        super(NAME);
        this.builder = builder;
        addClass(OVERLAY_CLASS);
        set(Attribute.HIT_TEST, false);
        // AND NOT THE ANSWER TO A PICK EITHER. hit-test alone is not enough here: a design surface
        // resolves what is under the pointer with a PICK, which reaches through that attribute on
        // purpose -- so a full-size overlay was the answer to every click on the canvas.
        set(Attribute.HIT_TRANSPARENT, true);
    }

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (box == null) return;
        // THE BUILDER'S SELECTION, which is the one the inspector and the hierarchy read. The engine's
        // item set is what a GESTURE moves and is kept in step by the plane -- but "in step" is a
        // property that can fail, and when it did the canvas outlined one node while the inspector
        // described another. One source for everything a reader sees; the other stays an implementation
        // detail of dragging.
        List<UIElement> selected = builder.builderSelection().nodes();
        if (selected.isEmpty()) return;

        int accent = getStyle().computed().get(StylePropertyRegistry.COLOR);
        int parentStroke = getStyle().computed().get(StylePropertyRegistry.BORDER_COLOR);

        // THE PARENT FIRST, so the selection's own stroke wins where they touch -- a child flush against
        // its parent's padding box shares an edge, and the one you are moving is the one to see.
        for (UIElement node : selected) {
            float[] parent = parentRectWorthDrawing(node);
            if (parent != null) CanvasRects.outline(paint, parent, THICKNESS, parentStroke);
        }
        for (UIElement node : selected) {
            CanvasRects.outline(paint, CanvasRects.of(node, this), THICKNESS, accent);
        }
    }

    /**
     * The parent's rectangle, or null when drawing it would say nothing.
     *
     * <p>Two cases, and both were reported as the outlines looking arbitrary:</p>
     *
     * <ul>
     *   <li><b>The parent is the artboard.</b> The page already draws its own edge, so a second stroke
     *       on top of it is a line that means "this is inside the page" — which is true of everything.</li>
     *   <li><b>The parent is the same rectangle as the selection.</b> A root that is {@code height: auto}
     *       around a single child is exactly its child's box, so the context stroke lands under the accent
     *       one and reads as a stray grey edge rather than as context.</li>
     * </ul>
     */
    @Nullable
    private float[] parentRectWorthDrawing(UIElement node) {
        UIElement parent = node.parentElement();
        if (parent == null || parent == builder.artboard()) return null;
        float[] rect = CanvasRects.of(parent, this);
        float[] own = CanvasRects.of(node, this);
        if (rect == null || own == null) return rect;
        return sameRect(rect, own) ? null : rect;
    }

    private static boolean sameRect(float[] a, float[] b) {
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > 0.5f) return false;
        }
        return true;
    }
}
