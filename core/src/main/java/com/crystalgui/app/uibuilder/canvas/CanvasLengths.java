package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;

/**
 * A length a gesture MOVED rather than authored, written in the unit the node already states it in.
 *
 * <pre>{@code
 * l.set(LayoutProperties.TOP, CanvasLengths.inset(node, LayoutProperties.TOP, px, parent.height()));
 * l.set(LayoutProperties.WIDTH, CanvasLengths.size(node, LayoutProperties.WIDTH, px, parent.width()));
 * }</pre>
 *
 * <p><b>A drag that writes pixels over a percentage destroys what the percentage was for.</b> A {@code top} of
 * 46% follows the parent as it resizes; nudged on the canvas it came back as 589px, a constant, and the element
 * stopped following anything. Figma, Webflow and Framer all keep the unit a value was authored in through a
 * gesture, for this reason.</p>
 *
 * <p>Pixels are the answer for everything else: a value that is {@code auto}, unset, or stated in a unit a
 * gesture cannot measure against becomes the number the drag produced, which is what it always did.</p>
 */
public final class CanvasLengths {

    private CanvasLengths() {
    }

    /** An inset — {@code left}, {@code top}, {@code right}, {@code bottom} — as px, or as the percentage it was. */
    public static LengthPercentageAuto inset(@Nullable UIElement node,
                                             StyleProperty<LengthPercentageAuto> property, float px, float base) {
        LengthPercentageAuto now = node == null ? null : node.getStyle().computed().get(property);
        if (base > 0f && now != null && now.getType() == LengthPercentageAuto.Type.PERCENT) {
            return LengthPercentageAuto.percent(px / base);
        }
        return LengthPercentageAuto.length(Math.round(px));
    }

    /** A size — {@code width}, {@code height} — as px, or as the percentage it was. */
    public static TaffyDimension size(@Nullable UIElement node, StyleProperty<TaffyDimension> property,
                                      float px, float base) {
        TaffyDimension now = node == null ? null : node.getStyle().computed().get(property);
        if (base > 0f && now != null && now.getType() == TaffyDimension.Type.PERCENT) {
            return TaffyDimension.percent(px / base);
        }
        return TaffyDimension.length(Math.round(px));
    }
}
