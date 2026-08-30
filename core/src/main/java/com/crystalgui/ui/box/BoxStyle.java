package com.crystalgui.ui.box;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.TaffyBridge;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;

/**
 * The ONE mapper from a node's {@link ComputedStyle} to the layout engine's style.
 *
 * <p>The old engine reached the layout engine through a listener per property — forty-odd
 * {@code createSetter} calls firing one at a time as candidates resolved, which is why a layout
 * property that cascaded correctly could still "change nothing on screen" when its listener was
 * missing (the "Adding a CSS property" step 5 trap). Here the box tree reads the whole computed
 * style at once, after the style pass, and writes the whole layout style: there is no listener to
 * forget.</p>
 *
 * <h3>CSS defaults, from the first line (D5.8)</h3>
 *
 * <p>The old bridge's defaults diverged from CSS in five places — {@code flex-direction: column},
 * {@code flex-shrink: 0}, {@code min-size: 0}, {@code align-content: flex-start} — and thirteen
 * invariant rows are sessions re-learning them. The layout engine's own defaults ARE CSS's, so a
 * property nothing set is written as the engine's default rather than as the old initial value. The
 * sheets that relied on the divergences are the old engine's and are ported at M6 (D4).</p>
 *
 * <p>The bridge is reused for its value conversions (our {@code LengthPercentageAuto} into the
 * engine's {@code LengthPercentage}, our grid types into track lists), not for its defaults.</p>
 */
public final class BoxStyle {

    private BoxStyle() {
    }

    /** Writes every layout-facing value of {@code computed} into {@code bridge}'s style. */
    public static void apply(TaffyBridge bridge, ComputedStyle c) {
        bridge.setDisplay(c.get(LayoutProperties.DISPLAY));
        bridge.setOverflow(toTaffy(c.get(StylePropertyRegistry.OVERFLOW)));
        bridge.setDirection(c.get(LayoutProperties.LAYOUT_DIRECTION));
        bridge.setPosition(c.get(LayoutProperties.POSITION));
        bridge.setBoxSizing(c.get(LayoutProperties.BOX_SIZING));

        // Flex -- the two divergent defaults go back to CSS's when nothing set them.
        bridge.setFlexDirection(c.isSet(LayoutProperties.FLEX_DIRECTION)
                ? c.get(LayoutProperties.FLEX_DIRECTION) : FlexDirection.ROW);
        bridge.setFlexShrink(c.isSet(LayoutProperties.FLEX_SHRINK) ? c.get(LayoutProperties.FLEX_SHRINK) : 1f);
        bridge.setFlexWrap(c.get(LayoutProperties.FLEX_WRAP));
        bridge.setFlexBasis(c.get(LayoutProperties.FLEX_BASIS));
        bridge.setFlexGrow(c.get(LayoutProperties.FLEX_GROW));
        bridge.setFlex(c.isSet(LayoutProperties.FLEX) ? c.get(LayoutProperties.FLEX) : Float.NaN);

        // Alignment -- align-content back to CSS's `normal`.
        bridge.setAlignItems(c.get(LayoutProperties.ALIGN_ITEMS));
        bridge.setAlignSelf(c.get(LayoutProperties.ALIGN_SELF));
        bridge.setAlignContent(c.isSet(LayoutProperties.ALIGN_CONTENT)
                ? c.get(LayoutProperties.ALIGN_CONTENT) : AlignContent.AUTO);
        bridge.setJustifyItems(c.get(LayoutProperties.JUSTIFY_ITEMS));
        bridge.setJustifySelf(c.get(LayoutProperties.JUSTIFY_SELF));
        bridge.setJustifyContent(c.get(LayoutProperties.JUSTIFY_CONTENT));
        bridge.setAspectRate(c.isSet(LayoutProperties.ASPECT_RATE) ? c.get(LayoutProperties.ASPECT_RATE) : Float.NaN);

        // Box -- min-size back to CSS's `auto`.
        bridge.setLeft(c.get(LayoutProperties.LEFT));
        bridge.setTop(c.get(LayoutProperties.TOP));
        bridge.setRight(c.get(LayoutProperties.RIGHT));
        bridge.setBottom(c.get(LayoutProperties.BOTTOM));
        bridge.setWidth(c.get(LayoutProperties.WIDTH));
        bridge.setHeight(c.get(LayoutProperties.HEIGHT));
        bridge.setMinWidth(c.isSet(LayoutProperties.MIN_WIDTH) ? c.get(LayoutProperties.MIN_WIDTH) : TaffyDimension.AUTO);
        bridge.setMinHeight(c.isSet(LayoutProperties.MIN_HEIGHT) ? c.get(LayoutProperties.MIN_HEIGHT) : TaffyDimension.AUTO);
        bridge.setMaxWidth(c.get(LayoutProperties.MAX_WIDTH));
        bridge.setMaxHeight(c.get(LayoutProperties.MAX_HEIGHT));
        // The registry's initial for a margin, a padding and a border is `auto`, which is not CSS's (0) -- and an auto margin on
        // an absolutely positioned box CENTRES it in its free space, which is how every unset margin
        // put every popup somewhere plausible and wrong. Unset means zero.
        bridge.setMarginLeft(c.isSet(LayoutProperties.MARGIN_LEFT) ? c.get(LayoutProperties.MARGIN_LEFT) : LengthPercentageAuto.ZERO);
        bridge.setMarginTop(c.isSet(LayoutProperties.MARGIN_TOP) ? c.get(LayoutProperties.MARGIN_TOP) : LengthPercentageAuto.ZERO);
        bridge.setMarginRight(c.isSet(LayoutProperties.MARGIN_RIGHT) ? c.get(LayoutProperties.MARGIN_RIGHT) : LengthPercentageAuto.ZERO);
        bridge.setMarginBottom(c.isSet(LayoutProperties.MARGIN_BOTTOM) ? c.get(LayoutProperties.MARGIN_BOTTOM) : LengthPercentageAuto.ZERO);
        bridge.setPaddingLeft(c.isSet(LayoutProperties.PADDING_LEFT) ? c.get(LayoutProperties.PADDING_LEFT) : LengthPercentageAuto.ZERO);
        bridge.setPaddingTop(c.isSet(LayoutProperties.PADDING_TOP) ? c.get(LayoutProperties.PADDING_TOP) : LengthPercentageAuto.ZERO);
        bridge.setPaddingRight(c.isSet(LayoutProperties.PADDING_RIGHT) ? c.get(LayoutProperties.PADDING_RIGHT) : LengthPercentageAuto.ZERO);
        bridge.setPaddingBottom(c.isSet(LayoutProperties.PADDING_BOTTOM) ? c.get(LayoutProperties.PADDING_BOTTOM) : LengthPercentageAuto.ZERO);
        bridge.setBorderLeft(c.isSet(LayoutProperties.BORDER_LEFT) ? c.get(LayoutProperties.BORDER_LEFT) : LengthPercentageAuto.ZERO);
        bridge.setBorderTop(c.isSet(LayoutProperties.BORDER_TOP) ? c.get(LayoutProperties.BORDER_TOP) : LengthPercentageAuto.ZERO);
        bridge.setBorderRight(c.isSet(LayoutProperties.BORDER_RIGHT) ? c.get(LayoutProperties.BORDER_RIGHT) : LengthPercentageAuto.ZERO);
        bridge.setBorderBottom(c.isSet(LayoutProperties.BORDER_BOTTOM) ? c.get(LayoutProperties.BORDER_BOTTOM) : LengthPercentageAuto.ZERO);

        // Gaps: shorthand first, longhands over it, and a reset first so a withdrawn gap is withdrawn.
        bridge.gap.setAll(LengthPercentageAuto.ZERO);
        if (c.isSet(LayoutProperties.GAP)) bridge.gap.setSize(c.get(LayoutProperties.GAP));
        if (c.isSet(LayoutProperties.GAP_ALL)) bridge.gap.setAll(c.get(LayoutProperties.GAP_ALL));
        if (c.isSet(LayoutProperties.GAP_ROW)) bridge.gap.setVertical(c.get(LayoutProperties.GAP_ROW));
        if (c.isSet(LayoutProperties.GAP_COLUMN)) bridge.gap.setHorizontal(c.get(LayoutProperties.GAP_COLUMN));

        // Grid: written only when set -- the initial of a track list is nothing, and the bridge
        // converts what it is given.
        if (c.isSet(LayoutProperties.GRID_TEMPLATE_ROWS)) bridge.setGridTemplateRows(c.get(LayoutProperties.GRID_TEMPLATE_ROWS));
        if (c.isSet(LayoutProperties.GRID_TEMPLATE_COLUMNS)) bridge.setGridTemplateColumns(c.get(LayoutProperties.GRID_TEMPLATE_COLUMNS));
        if (c.isSet(LayoutProperties.GRID_TEMPLATE_AREAS)) bridge.setGridTemplateAreas(c.get(LayoutProperties.GRID_TEMPLATE_AREAS));
        if (c.isSet(LayoutProperties.GRID_AUTO_ROWS)) bridge.setGridAutoRows(c.get(LayoutProperties.GRID_AUTO_ROWS));
        if (c.isSet(LayoutProperties.GRID_AUTO_COLUMNS)) bridge.setGridAutoColumns(c.get(LayoutProperties.GRID_AUTO_COLUMNS));
        if (c.isSet(LayoutProperties.GRID_AUTO_FLOW)) bridge.setGridAutoFlow(c.get(LayoutProperties.GRID_AUTO_FLOW));
        if (c.isSet(LayoutProperties.GRID_ROW)) bridge.setGridRow(c.get(LayoutProperties.GRID_ROW));
        if (c.isSet(LayoutProperties.GRID_COLUMN)) bridge.setGridColumn(c.get(LayoutProperties.GRID_COLUMN));
    }

    /**
     * Our CSS-facing overflow onto the engine's layout-facing one. {@code AUTO} is {@code HIDDEN} rather
     * than {@code SCROLL} because the engine reserves a scrollbar gutter only for {@code SCROLL}, and
     * this engine's bars overlay the content.
     */
    static dev.vfyjxf.taffy.style.Overflow toTaffy(Overflow overflow) {
        switch (overflow) {
            case CLIP: return dev.vfyjxf.taffy.style.Overflow.CLIP;
            case HIDDEN:
            case AUTO: return dev.vfyjxf.taffy.style.Overflow.HIDDEN;
            case SCROLL: return dev.vfyjxf.taffy.style.Overflow.SCROLL;
            default: return dev.vfyjxf.taffy.style.Overflow.VISIBLE;
        }
    }
}
