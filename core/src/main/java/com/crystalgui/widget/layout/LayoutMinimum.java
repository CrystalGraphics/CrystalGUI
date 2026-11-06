package com.crystalgui.widget.layout;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.layout.length.LPSize;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.geometry.FloatRect;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The smallest an element can be with nothing in it overflowing: what a window asks its content before a resize goes
 * below it, as Windows asks a window in {@code WM_GETMINMAXINFO}.
 *
 * <pre>{@code
 * float minWidth = LayoutMinimum.of(root, false);    // a window's content, the horizontal axis
 * float minHeight = LayoutMinimum.of(root, true);
 * }</pre>
 *
 * <p>What an element states wins first: a {@link MinimumSize} answers for itself (a dock area, a split), and a
 * {@code min-width} or {@code min-height} is a floor. Otherwise a flex container folds its children: along its main
 * axis their minimums added up with its gaps, across it the largest, inside its padding and border. A block stacks
 * them vertically.</p>
 *
 * <ul>
 *   <li>A measured leaf, text, needs the height it is laid out at, and its min-content width unless it elides or
 *       states a width: a sentence that neither wraps nor elides holds the window open.</li>
 *   <li>A child that neither grows nor shrinks along its parent's main axis needs the size layout gave it: a row of
 *       content-sized buttons cannot be narrower than they are.</li>
 *   <li>A box that clips or scrolls ({@code overflow} other than visible) contributes only what it states: what is
 *       inside it scrolls, so it forces nothing.</li>
 *   <li>Hidden and absolutely positioned boxes take no part, and an element with no box answers what it states.</li>
 *   <li>Read from the last layout, so call it from a frame, not while the tree is being built.</li>
 * </ul>
 */
public final class LayoutMinimum {

    private LayoutMinimum() {
    }

    /** The smallest {@code element} may be on the vertical axis, or the horizontal one, in pixels. */
    public static float of(UIElement element, boolean vertical) {
        float stated = SplitView.minimumOf(element, vertical);
        Box box = element.box();
        if (box == null || element instanceof MinimumSize) return stated;
        ComputedStyle style = element.computedStyle();
        Overflow overflow = style.get(StylePropertyRegistry.OVERFLOW);
        if (overflow != null && overflow != Overflow.VISIBLE) return stated;
        if (element instanceof Measurable measured && box.children().isEmpty()) {
            return Math.max(stated, vertical ? box.height() : textWidth(measured, style));
        }
        return Math.max(stated, folded(box, style, vertical));
    }

    /** {@code element}'s own {@code min-width} or {@code min-height} in pixels, or zero: for a {@link MinimumSize} folding its own floor in. */
    public static float stated(UIElement element, boolean vertical) {
        return SplitView.ownMinimum(element, vertical);
    }

    /**
     * The narrowest a measured leaf (text) can be: none if it elides; the whole line if it does not wrap
     * ({@code white-space: nowrap}); else its min-content width, the longest word. A stated {@code width} caps either,
     * as a flex item's automatic minimum is, so {@code width: 0; flex-grow: 1} gives way.
     */
    private static float textWidth(Measurable measured, ComputedStyle style) {
        if (style.get(StylePropertyRegistry.TEXT_OVERFLOW) == TextOverflow.ELLIPSIS) return 0f;
        WhiteSpace whiteSpace = style.get(StylePropertyRegistry.WHITE_SPACE);
        Measurable.Fit fit = whiteSpace != null && !whiteSpace.wraps() ? Measurable.Fit.MAX_CONTENT : Measurable.Fit.MIN_CONTENT;
        TaffyDimension width = style.get(LayoutProperties.WIDTH);
        float content = measured.measure(new Measurable.Constraints(Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                fit, Measurable.Fit.MAX_CONTENT)).width();
        if (width != null && width.getType() == TaffyDimension.Type.LENGTH) return Math.min(width.getValue(), content);
        return content;
    }

    /** What {@code box}'s in-flow children need, inside its padding and border. */
    private static float folded(Box box, ComputedStyle style, boolean vertical) {
        TaffyDisplay display = style.get(LayoutProperties.DISPLAY);
        boolean along;
        if (display == TaffyDisplay.FLEX) {
            FlexDirection direction = style.get(LayoutProperties.FLEX_DIRECTION);
            boolean column = direction == FlexDirection.COLUMN || direction == FlexDirection.COLUMN_REVERSE;
            along = column == vertical;
        } else if (display == TaffyDisplay.BLOCK) {
            along = vertical;
        } else {
            // A grid's tracks are its own business: its children's largest is a floor it cannot go under
            along = false;
        }
        float sum = 0f;
        int counted = 0;
        for (Box child : box.children()) {
            UIElement node = child.node();
            if (node == null || !node.isDisplayed()) continue;
            if (node.computedStyle().get(LayoutProperties.POSITION) == TaffyPosition.ABSOLUTE) continue;
            float need = Math.max(of(node, vertical), fixed(node, child, vertical, along)) + margins(child, vertical);
            sum = along ? sum + need : Math.max(sum, need);
            counted++;
        }
        if (along && counted > 1) sum += gap(style, vertical, box) * (counted - 1);
        FloatRect padding = box.padding(), border = box.border();
        float edges = vertical ? padding.top + padding.bottom + border.top + border.bottom
                : padding.left + padding.right + border.left + border.right;
        return sum + edges;
    }

    /**
     * What a child cannot give up. Across its parent's main axis, a definite {@code width} or {@code height}. Along it,
     * when it neither grows nor shrinks ({@code flex-shrink} defaults to 0 here), the size layout gave it, which is its
     * basis: a toolbar's content-sized buttons and labels, which state no width yet cannot be narrower.
     */
    private static float fixed(UIElement node, Box box, boolean vertical, boolean alongParent) {
        ComputedStyle style = node.computedStyle();
        if (alongParent) {
            Float shrink = style.get(LayoutProperties.FLEX_SHRINK);
            Float grow = style.get(LayoutProperties.FLEX_GROW);
            if (shrink != null && shrink > 0f) return 0f;
            if (grow == null || grow <= 0f) return vertical ? box.height() : box.width();
        }
        TaffyDimension size = style.get(vertical ? LayoutProperties.HEIGHT : LayoutProperties.WIDTH);
        return size == null || size.getType() != TaffyDimension.Type.LENGTH ? 0f : size.getValue();
    }

    private static float margins(Box box, boolean vertical) {
        FloatRect margin = box.margin();
        return vertical ? margin.top + margin.bottom : margin.left + margin.right;
    }

    /** The gap between two children along {@code vertical}: {@code row-gap} between rows, {@code column-gap} between columns. */
    private static float gap(ComputedStyle style, boolean vertical, Box box) {
        LengthPercentageAuto own = style.get(vertical ? LayoutProperties.ROW_GAP : LayoutProperties.COLUMN_GAP);
        float context = vertical ? box.height() : box.width();
        if (own != null && !own.isAuto()) return own.resolveOrZero(context);
        LPSize gap = style.get(LayoutProperties.GAP);
        if (gap == null) return 0f;
        return (vertical ? gap.size().height : gap.size().width).resolveOrZero(context);
    }
}
