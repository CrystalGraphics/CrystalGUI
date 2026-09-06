package com.crystalgui.app.uibuilder.canvas;

import java.util.List;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.surface.SurfaceContext;

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

    private final SurfaceContext ctx;

    public SelectionOutline(SurfaceContext ctx) {
        super(NAME);
        this.ctx = ctx;
        addClass(OVERLAY_CLASS);
        set(Attribute.HIT_TEST, false);
    }

    @Override
    public void paintContent(CgUiPaintContext paint, Box box) {
        if (box == null) return;
        List<UIElement> selected = ctx.selection().items();
        if (selected.isEmpty()) return;

        int accent = getStyle().computed().get(StylePropertyRegistry.COLOR);
        int parentStroke = getStyle().computed().get(StylePropertyRegistry.BORDER_COLOR);

        // THE PARENT FIRST, so the selection's own stroke wins where they touch -- a child flush against
        // its parent's padding box shares an edge, and the one you are moving is the one to see.
        for (UIElement node : selected) {
            UIElement parent = node.parentElement();
            if (parent != null) CanvasRects.outline(paint, CanvasRects.of(parent, this),
                    THICKNESS, parentStroke);
        }
        for (UIElement node : selected) {
            CanvasRects.outline(paint, CanvasRects.of(node, this), THICKNESS, accent);
        }
    }
}
