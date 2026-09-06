package com.crystalgui.style.property.layout;

import com.crystalgui.style.TaffyBridge;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.IValueInterpolator;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.general.floats.AutoFloatProperty;
import com.crystalgui.style.property.layout.dimension.DimensionProperty;
import com.crystalgui.style.property.layout.grid.*;
import com.crystalgui.style.property.layout.length.*;
import dev.vfyjxf.taffy.style.*;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

public class LayoutProperties {
    // Every `addListener` registration that stood here is gone with the property-listener API -- see
    // `StyleProperty`. They were the old cascade's bridge into Taffy and into `UIElement`'s caches
    // (`invalidateFocusableChain`, `onPositionModeChanged`, `invalidatePoseCachesRecursively`,
    // `onResizeModeChanged`, `taffyBridge.set*`). The new engine needs none of them: `BoxStyle` reads
    // `ComputedStyle` on every sync, and the two cases that still need telling -- a `font-size` that
    // moves an `em`, and `resize` growing handles -- go through `UIElement.computedChanged`.

    public static final List<AlignItems> DEFAULT_ALIGN_ITEMS = Arrays.asList(
            AlignItems.AUTO,
            AlignItems.START,
            AlignItems.END,
            AlignItems.FLEX_START,
            AlignItems.FLEX_END,
            AlignItems.CENTER,
            AlignItems.STRETCH
    );

    /** @see #DISPLAY */
    public static final IValueInterpolator<TaffyDisplay> DISPLAY_ALLOW_DISCRETE =
            (from, to, progress) -> {
                if (progress >= 1f) return to;
                // Whichever end is not `none`: the element stays laid out for the whole transition, so
                // there is something on screen to animate.
                if (from != TaffyDisplay.NONE) return from;
                return to != TaffyDisplay.NONE ? to : from;
            };

    /**
     * {@code display}, and it is <b>transitionable</b> — CSS's {@code transition-behavior: allow-discrete}.
     *
     * <p>{@code display} cannot be interpolated, so on its own it snaps — and a box that snaps to
     * {@code none} takes every other transition on that element with it. A fade-out is the case: the
     * opacity transition starts, {@code display: none} lands on the same frame, and nothing is ever seen
     * fading. The web's answer is to make {@code display} part of the transition, and
     * {@link #DISPLAY_ALLOW_DISCRETE} is that rule — the visible end wins until the very end, so showing
     * flips immediately and hiding flips last.</p>
     *
     * <p>Inert unless a sheet names {@code display} or {@code all}, so nothing that does not ask changes.</p>
     */
    public static final StyleProperty<TaffyDisplay> DISPLAY = StylePropertyRegistry
            .create("display", TaffyDisplay.class, TaffyDisplay.FLEX)
            .setAllowTransition(true)
            .setInterpolator(DISPLAY_ALLOW_DISCRETE);
    public static final StyleProperty<TaffyDirection> LAYOUT_DIRECTION = StylePropertyRegistry.create("layout-direction", TaffyDirection.class, TaffyDirection.INHERIT);
    public static final StyleProperty<TaffyDimension> FLEX_BASIS = create("flex-basis", TaffyDimension.auto());
    public static final StyleProperty<Float> FLEX = StylePropertyRegistry.create(new AutoFloatProperty("flex", Float.NaN));
    public static final StyleProperty<Float> FLEX_GROW = StylePropertyRegistry.create("flex-grow", 0f);
    public static final StyleProperty<Float> FLEX_SHRINK = StylePropertyRegistry.create("flex-shrink", 0f);
    public static final StyleProperty<FlexDirection> FLEX_DIRECTION = StylePropertyRegistry.create("flex-direction", FlexDirection.class, FlexDirection.COLUMN);//;//.setIconProvider(FlexIcons::getFlexDirectionIcon);
    public static final StyleProperty<FlexWrap> FLEX_WRAP = StylePropertyRegistry.create("flex-wrap", FlexWrap.class, FlexWrap.NO_WRAP);//;//.setIconProvider(FlexIcons::getFlexWrapIcon);
    public static final StyleProperty<TaffyPosition> POSITION = StylePropertyRegistry.create("position", TaffyPosition.class, TaffyPosition.RELATIVE);
    // BORDER_BOX is a deliberate project default, NOT real CSS's actual initial value
    // (content-box) — see TaffyBridge.DEFAULT_TAFFY_STYLE's own explicit assignment, which this
    // property's default value stays consistent with.
    public static final StyleProperty<BoxSizing> BOX_SIZING = StylePropertyRegistry.create("box-sizing", BoxSizing.class, BoxSizing.BORDER_BOX);

    public static final StyleProperty<LengthPercentageAuto> LEFT = create("left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> TOP = create("top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> RIGHT = create("right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> BOTTOM = create("bottom", LengthPercentageAuto.AUTO);

    // margin/padding/border-width are 4 real longhand properties each — the composite name and its
    // -all/-horizontal/-vertical aliases are pure parse-time shorthand syntax (see
    // BoxEdgeShorthands), expanded into these longhands before the cascade ever runs; they are not
    // separately-registered StyleProperty instances.
    public static final StyleProperty<LengthPercentageAuto> MARGIN_LEFT = create("margin-left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_TOP = create("margin-top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_RIGHT = create("margin-right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_BOTTOM = create("margin-bottom", LengthPercentageAuto.AUTO);

    public static final StyleProperty<LengthPercentageAuto> PADDING_LEFT = create("padding-left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_TOP = create("padding-top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_RIGHT = create("padding-right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_BOTTOM = create("padding-bottom", LengthPercentageAuto.AUTO);

    public static final StyleProperty<LengthPercentageAuto> BORDER_LEFT = create("border-width-left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> BORDER_TOP = create("border-width-top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> BORDER_RIGHT = create("border-width-right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> BORDER_BOTTOM = create("border-width-bottom", LengthPercentageAuto.AUTO);

    public static final StyleProperty<LengthPercentageAuto> GAP_ROW = create("gap-row", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> GAP_COLUMN = create("gap-column", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> GAP_ALL = create("gap-all", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LPSize> GAP = create("gap", LPSize.ZERO);

    public static final StyleProperty<TaffyDimension> WIDTH = create("width", TaffyDimension.auto());

    public static final StyleProperty<TaffyDimension> HEIGHT = create("height", TaffyDimension.auto());

    public static final StyleProperty<TaffyDimension> MIN_WIDTH = create("min-width", TaffyDimension.ZERO);
    public static final StyleProperty<TaffyDimension> MIN_HEIGHT = create("min-height", TaffyDimension.ZERO);

    public static final StyleProperty<TaffyDimension> MAX_WIDTH = create("max-width", TaffyDimension.auto());
    public static final StyleProperty<TaffyDimension> MAX_HEIGHT = create("max-height", TaffyDimension.auto());

    public static final StyleProperty<Float> ASPECT_RATE = StylePropertyRegistry.create(new AutoFloatProperty("aspect-rate", Float.NaN));
    public static final StyleProperty<AlignItems> ALIGN_ITEMS = StylePropertyRegistry.create("align-items", AlignItems.class, AlignItems.STRETCH, DEFAULT_ALIGN_ITEMS);//.setIconProvider(v -> IGuiTexture.EMPTY);
    public static final StyleProperty<AlignItems> ALIGN_SELF = StylePropertyRegistry.create("align-self", AlignItems.class, AlignItems.AUTO, DEFAULT_ALIGN_ITEMS);//.setIconProvider(v -> IGuiTexture.EMPTY);
    public static final StyleProperty<AlignContent> ALIGN_CONTENT = StylePropertyRegistry.create("align-content", AlignContent.class, AlignContent.FLEX_START);//.setIconProvider(v -> IGuiTexture.EMPTY);
    public static final StyleProperty<AlignItems> JUSTIFY_ITEMS = StylePropertyRegistry.create("justify-items", AlignItems.class, AlignItems.AUTO, DEFAULT_ALIGN_ITEMS);//.setIconProvider(v -> IGuiTexture.EMPTY);
    public static final StyleProperty<AlignItems> JUSTIFY_SELF = StylePropertyRegistry.create("justify-self", AlignItems.class, AlignItems.AUTO, DEFAULT_ALIGN_ITEMS);//.setIconProvider(v -> IGuiTexture.EMPTY);
    public static final StyleProperty<AlignContent> JUSTIFY_CONTENT = StylePropertyRegistry.create("justify-content", AlignContent.class, AlignContent.FLEX_START);//.setIconProvider(v -> IGuiTexture.EMPTY);

    public static final StyleProperty<GridTemplate> GRID_TEMPLATE_ROWS = create("grid-template-rows", GridTemplate.EMPTY);
    public static final StyleProperty<GridTemplate> GRID_TEMPLATE_COLUMNS = create("grid-template-columns", GridTemplate.EMPTY);
    public static final StyleProperty<GridTemplateAreas> GRID_TEMPLATE_AREAS = create("grid-template-areas", GridTemplateAreas.EMPTY);
    public static final StyleProperty<GridAuto> GRID_AUTO_ROWS = create("grid-auto-rows", GridAuto.EMPTY);
    public static final StyleProperty<GridAuto> GRID_AUTO_COLUMNS = create("grid-auto-columns", GridAuto.EMPTY);
    public static final StyleProperty<GridAutoFlow> GRID_AUTO_FLOW = StylePropertyRegistry.create("grid-auto-flow", GridAutoFlow.class, GridAutoFlow.ROW);
    public static final StyleProperty<Grid> GRID_ROW = create("grid-row", Grid.EMPTY);
    public static final StyleProperty<Grid> GRID_COLUMN = create("grid-column", Grid.EMPTY);

    static {
        init();
    }

    public static void init() {
        createSetter(LayoutProperties.DISPLAY, TaffyBridge::setDisplay);
        createSetter(LayoutProperties.LAYOUT_DIRECTION, TaffyBridge::setDirection);
        createSetter(LayoutProperties.FLEX_BASIS, TaffyBridge::setFlexBasis);
        createSetter(LayoutProperties.FLEX, TaffyBridge::setFlex);
        createSetter(LayoutProperties.FLEX_GROW, TaffyBridge::setFlexGrow);
        createSetter(LayoutProperties.FLEX_SHRINK, TaffyBridge::setFlexShrink);
        createSetter(LayoutProperties.FLEX_DIRECTION, TaffyBridge::setFlexDirection);
        createSetter(LayoutProperties.FLEX_WRAP, TaffyBridge::setFlexWrap);
        createSetter(LayoutProperties.POSITION, TaffyBridge::setPosition);
        // Whether an element is out of flow decides whether it may have LEADING resize handles, and
        createSetter(LayoutProperties.BOX_SIZING, TaffyBridge::setBoxSizing);
        createSetter(LayoutProperties.ALIGN_ITEMS, TaffyBridge::setAlignItems);
        createSetter(LayoutProperties.JUSTIFY_CONTENT, TaffyBridge::setJustifyContent);
        createSetter(LayoutProperties.JUSTIFY_ITEMS, TaffyBridge::setJustifyItems);
        createSetter(LayoutProperties.JUSTIFY_SELF, TaffyBridge::setJustifySelf);
        createSetter(LayoutProperties.ALIGN_SELF, TaffyBridge::setAlignSelf);
        createSetter(LayoutProperties.ALIGN_CONTENT, TaffyBridge::setAlignContent);
        createSetter(LayoutProperties.ASPECT_RATE, TaffyBridge::setAspectRate);

        createSetter(LayoutProperties.LEFT, TaffyBridge::setLeft);
        createSetter(LayoutProperties.TOP, TaffyBridge::setTop);
        createSetter(LayoutProperties.RIGHT, TaffyBridge::setRight);
        createSetter(LayoutProperties.BOTTOM, TaffyBridge::setBottom);

        createSetter(LayoutProperties.WIDTH, TaffyBridge::setWidth);
        createSetter(LayoutProperties.HEIGHT, TaffyBridge::setHeight);
        createSetter(LayoutProperties.MIN_WIDTH, TaffyBridge::setMinWidth);
        createSetter(LayoutProperties.MAX_WIDTH, TaffyBridge::setMaxWidth);
        createSetter(LayoutProperties.MIN_HEIGHT, TaffyBridge::setMinHeight);
        createSetter(LayoutProperties.MAX_HEIGHT, TaffyBridge::setMaxHeight);

        createSetter(LayoutProperties.MARGIN_LEFT, TaffyBridge::setMarginLeft);
        createSetter(LayoutProperties.MARGIN_TOP, TaffyBridge::setMarginTop);
        createSetter(LayoutProperties.MARGIN_RIGHT, TaffyBridge::setMarginRight);
        createSetter(LayoutProperties.MARGIN_BOTTOM, TaffyBridge::setMarginBottom);

        createSetter(LayoutProperties.PADDING_LEFT, TaffyBridge::setPaddingLeft);
        createSetter(LayoutProperties.PADDING_TOP, TaffyBridge::setPaddingTop);
        createSetter(LayoutProperties.PADDING_RIGHT, TaffyBridge::setPaddingRight);
        createSetter(LayoutProperties.PADDING_BOTTOM, TaffyBridge::setPaddingBottom);

        createSetter(LayoutProperties.BORDER_LEFT, TaffyBridge::setBorderLeft);
        createSetter(LayoutProperties.BORDER_TOP, TaffyBridge::setBorderTop);
        createSetter(LayoutProperties.BORDER_RIGHT, TaffyBridge::setBorderRight);
        createSetter(LayoutProperties.BORDER_BOTTOM, TaffyBridge::setBorderBottom);

        createSetter(LayoutProperties.GAP_ROW, (style, value) -> style.gap.setVertical(value));
        createSetter(LayoutProperties.GAP_COLUMN, (style, value) -> style.gap.setHorizontal(value));
        createSetter(LayoutProperties.GAP_ALL, (style, value) -> style.gap.setAll(value));
        createSetter(LayoutProperties.GAP, (style, value) -> style.gap.setSize(value));

        // Grid properties (Taffy-specific, no Yoga equivalents)
        createSetter(LayoutProperties.GRID_TEMPLATE_ROWS, TaffyBridge::setGridTemplateRows);
        createSetter(LayoutProperties.GRID_TEMPLATE_COLUMNS, TaffyBridge::setGridTemplateColumns);
        createSetter(LayoutProperties.GRID_TEMPLATE_AREAS, TaffyBridge::setGridTemplateAreas);
        createSetter(LayoutProperties.GRID_AUTO_ROWS, TaffyBridge::setGridAutoRows);
        createSetter(LayoutProperties.GRID_AUTO_COLUMNS, TaffyBridge::setGridAutoColumns);
        createSetter(LayoutProperties.GRID_AUTO_FLOW, TaffyBridge::setGridAutoFlow);
        createSetter(LayoutProperties.GRID_ROW, TaffyBridge::setGridRow);
        createSetter(LayoutProperties.GRID_COLUMN, TaffyBridge::setGridColumn);
    }

    public static StyleProperty<LengthPercentageAuto> create(String name, LengthPercentageAuto initialValue) {
        return StylePropertyRegistry.create(new LPAProperty(name, initialValue));
    }

    public static StyleProperty<LPSize> create(String name, LPSize initialValue) {
        return StylePropertyRegistry.create(new LPSizeProperty(name, initialValue));
    }

    public static StyleProperty<TaffyDimension> create(String name, TaffyDimension initialValue) {
        return StylePropertyRegistry.create(new DimensionProperty(name, initialValue));
    }

    public static StyleProperty<GridTemplate> create(String name, GridTemplate initialValue) {
        return StylePropertyRegistry.create(new GridTemplateProperty(name, initialValue));
    }

    public static StyleProperty<GridAuto> create(String name, GridAuto initialValue) {
        return StylePropertyRegistry.create(new GridAutoProperty(name, initialValue));
    }

    public static StyleProperty<GridTemplateAreas> create(String name, GridTemplateAreas initialValue) {
        return StylePropertyRegistry.create(new GridTemplateAreasProperty(name, initialValue));
    }

    public static StyleProperty<Grid> create(String name, Grid initialValue) {
        return StylePropertyRegistry.create(new GridProperty(name, initialValue));
    }


    /**
     * <b>Nothing to wire any more.</b> This attached a listener that carried a computed layout value
     * into the live Taffy style, which is how the old cascade reached layout at all. The new engine
     * has no per-property hook: {@code BoxStyle} reads the whole {@code ComputedStyle} on every sync,
     * so a layout property arrives by being read rather than by announcing itself.
     *
     * <p>Kept as a no-op rather than deleted with its ~60 call sites, which say WHICH Taffy setter a
     * property belongs to -- a table worth keeping legible for whoever writes the next one.</p>
     */
    private static <T> void createSetter(StyleProperty<T> property,
                                         BiConsumer<TaffyBridge, T> taffySetter) {
    }
}
