package com.crystalgui.ui.box;

import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.TaffyBridge;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyPosition;

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
 * <h3>The PROJECT's defaults, not CSS's — D5.8 reversed at M6.1</h3>
 *
 * <p>This wrote CSS's initial for anything unset, on the reasoning that the old bridge's five
 * divergences ({@code flex-direction: column}, {@code flex-shrink: 0}, {@code min-size: 0},
 * {@code align-content: flex-start}) are a standing source of surprise, and that the sheets relying
 * on them would be ported at M6. <b>The bill came due at M6.1 and could not be paid.</b></p>
 *
 * <p>A default is not a property a sheet sets — it is the answer for every rule that does not mention
 * it, which in a 6,200-line user-agent sheet is nearly all of them. Flipping {@code flex-direction}
 * turns every unstated column into a row, and the failure is silent in the worst way: {@code menu}
 * states no direction, so its item column became a row, {@code align-items: stretch} stretched the
 * items container across the menu's height, and a three-row menu drew 166px tall with its rows in the
 * top 43. Nothing errored; the menu simply looked wrong in a way that reads as bad CSS. The gallery
 * scene met the same thing three times in one sitting, and each looked like a different bug.</p>
 *
 * <p>So the defaults are the registry's, which is what the OLD engine writes and what every shipped
 * sheet was authored against. The divergences are documented in {@code AGENTS.md} with their
 * reasoning — {@code border-box} matching the common UI-framework convention, {@code flex-shrink: 0}
 * so content is not compressed below its own size — and they are project decisions rather than
 * accidents. <b>Both engines now answer the same question the same way</b>, which is what makes a
 * geometry difference between them a defect rather than a default.</p>
 *
 * <p>The bridge is reused for its value conversions (our {@code LengthPercentageAuto} into the
 * engine's {@code LengthPercentage}, our grid types into track lists), not for its defaults.</p>
 */
public final class BoxStyle {

    private BoxStyle() {
    }

    /** Writes every layout-facing value of {@code computed} into {@code bridge}'s style. */
    public static void apply(TaffyBridge bridge, ComputedStyle c) {
        apply(bridge, c, false);
    }

    /**
     * As {@link #apply(TaffyBridge, ComputedStyle)}, forcing {@code position: absolute} when the box
     * is <b>hosted somewhere other than its natural parent</b>.
     *
     * <h3>Hosting IS out-of-flow, and nothing else was saying so</h3>
     *
     * <p>A promoted popup keeps whatever {@code position} it cascaded to, so it arrived in the top
     * layer as an ordinary flex ITEM — and the top layer is a zero-sized box, so with this engine's
     * CSS-initial {@code flex-shrink: 1} the popup was compressed to nothing. Measured on a popover
     * whose text child laid out at 56x52 inside a parent box of 60x4: it painted, it was hit-testable,
     * and it clipped its own content away, which on screen is a popup that does not open.</p>
     *
     * <p>The old engine forced this from the widget side — top-layer promotion wrote
     * {@code position: absolute} at IMPORTANT origin, and {@code ua/overlays.css} still says so in a
     * comment. The new engine may not write into the cascade at all, and should not have to: being
     * hosted somewhere other than where you sit in the tree is exactly what out-of-flow MEANS, so it
     * is the box tree's fact rather than a style a widget has to remember.</p>
     */
    public static void apply(TaffyBridge bridge, ComputedStyle c, boolean hosted) {
        apply(bridge, c, hosted, false);
    }

    /**
     * @param mirrorRoot whether this box is the root of a MIRROR, which is laid out at its host's
     *                   origin rather than at the insets its source node carries
     */
    public static void apply(TaffyBridge bridge, ComputedStyle c, boolean hosted, boolean mirrorRoot) {
        bridge.setDisplay(c.get(LayoutProperties.DISPLAY));
        bridge.setOverflow(StylePropertyRegistry.toTaffyOverflow(c.get(StylePropertyRegistry.OVERFLOW)));
        bridge.setDirection(c.get(LayoutProperties.LAYOUT_DIRECTION));
        bridge.setPosition(hosted ? TaffyPosition.ABSOLUTE : c.get(LayoutProperties.POSITION));
        bridge.setBoxSizing(c.get(LayoutProperties.BOX_SIZING));

        // Flex -- the two divergent defaults go back to CSS's when nothing set them.
        bridge.setFlexDirection(c.get(LayoutProperties.FLEX_DIRECTION));
        bridge.setFlexShrink(c.get(LayoutProperties.FLEX_SHRINK));
        bridge.setFlexWrap(c.get(LayoutProperties.FLEX_WRAP));
        bridge.setFlexBasis(c.get(LayoutProperties.FLEX_BASIS));
        bridge.setFlexGrow(c.get(LayoutProperties.FLEX_GROW));
        bridge.setFlex(c.isSet(LayoutProperties.FLEX) ? c.get(LayoutProperties.FLEX) : Float.NaN);

        // Alignment -- align-content back to CSS's `normal`.
        bridge.setAlignItems(c.get(LayoutProperties.ALIGN_ITEMS));
        bridge.setAlignSelf(c.get(LayoutProperties.ALIGN_SELF));
        bridge.setAlignContent(c.get(LayoutProperties.ALIGN_CONTENT));
        bridge.setJustifyItems(c.get(LayoutProperties.JUSTIFY_ITEMS));
        bridge.setJustifySelf(c.get(LayoutProperties.JUSTIFY_SELF));
        bridge.setJustifyContent(c.get(LayoutProperties.JUSTIFY_CONTENT));
        bridge.setAspectRate(c.isSet(LayoutProperties.ASPECT_RATE) ? c.get(LayoutProperties.ASPECT_RATE) : Float.NaN);

        // Box -- min-size back to CSS's `auto`.
        // A MIRROR ROOT TAKES NO INSETS FROM ITS SOURCE. It shares the source node, so it shares the
        // source's computed style -- and a window's style carries the `left`/`top` that place it on the
        // DESKTOP. Applied to the copy those became an offset inside the thumbnail, so a taskbar
        // preview drew its picture at the window's own desktop position scaled down: a window near the
        // left edge came out slightly off-centre and one near the right was drawn outside the preview
        // panel entirely, over the taskbar. Which reads as a broken preview rather than a correctly
        // drawn picture in the wrong place.
        //
        // THE OLD ENGINE DID THE SAME THING IN THE OTHER COORDINATE SYSTEM, which is what confirms this
        // is the right seam rather than a patch. Its thumbnail composed a pose by hand and its third
        // line was `pose.translate(-src.getX(), -src.getY(), 0f)`, commented "put the window's own
        // origin at zero" -- there, elements drew at ABSOLUTE layout coordinates, so cancelling the
        // source's position was a translation. Here geometry is host-relative and the same statement is
        // made by not applying the source's insets at all. That line simply had no counterpart in the
        // port: the two before it (translate to the picture, then scale) became the caller's transform
        // and were carried over, and the one that cancels the origin was the one with nowhere to go.
        //
        // Zero rather than `auto`, because `auto` on an absolutely positioned box means the STATIC
        // position -- where it would have sat in flow -- and a mirror root has no flow to fall back on.
        // Where the picture actually goes is the caller's business, written as a TRANSFORM on the
        // returned box; see BoxTree.mirror, which says so for exactly this reason.
        bridge.setLeft(mirrorRoot ? LengthPercentageAuto.ZERO : c.get(LayoutProperties.LEFT));
        bridge.setTop(mirrorRoot ? LengthPercentageAuto.ZERO : c.get(LayoutProperties.TOP));
        bridge.setRight(c.get(LayoutProperties.RIGHT));
        bridge.setBottom(c.get(LayoutProperties.BOTTOM));
        bridge.setWidth(c.get(LayoutProperties.WIDTH));
        bridge.setHeight(c.get(LayoutProperties.HEIGHT));
        bridge.setMinWidth(c.get(LayoutProperties.MIN_WIDTH));
        bridge.setMinHeight(c.get(LayoutProperties.MIN_HEIGHT));
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

}
