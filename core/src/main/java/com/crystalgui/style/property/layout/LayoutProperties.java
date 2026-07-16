package com.crystalgui.style.property.layout;

import com.crystalgui.style.TaffyBridge;
import com.crystalgui.style.property.StyleProperty;
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
    public static final List<AlignItems> DEFAULT_ALIGN_ITEMS = Arrays.asList(
            AlignItems.AUTO,
            AlignItems.START,
            AlignItems.END,
            AlignItems.FLEX_START,
            AlignItems.FLEX_END,
            AlignItems.CENTER,
            AlignItems.STRETCH
    );

    public static final StyleProperty<TaffyDisplay> DISPLAY = StylePropertyRegistry.create("display", TaffyDisplay.class, TaffyDisplay.FLEX);
    public static final StyleProperty<TaffyDirection> LAYOUT_DIRECTION = StylePropertyRegistry.create("layout-direction", TaffyDirection.class, TaffyDirection.INHERIT);
    public static final StyleProperty<TaffyDimension> FLEX_BASIS = create("flex-basis", TaffyDimension.auto());
    public static final StyleProperty<Float> FLEX = StylePropertyRegistry.create(new AutoFloatProperty("flex", Float.NaN));
    public static final StyleProperty<Float> FLEX_GROW = StylePropertyRegistry.create("flex-grow", 0f);
    public static final StyleProperty<Float> FLEX_SHRINK = StylePropertyRegistry.create("flex-shrink", 0f);
    public static final StyleProperty<FlexDirection> FLEX_DIRECTION = StylePropertyRegistry.create("flex-direction", FlexDirection.class, FlexDirection.COLUMN);//;//.setIconProvider(FlexIcons::getFlexDirectionIcon);
    public static final StyleProperty<FlexWrap> FLEX_WRAP = StylePropertyRegistry.create("flex-wrap", FlexWrap.class, FlexWrap.NO_WRAP);//;//.setIconProvider(FlexIcons::getFlexWrapIcon);
    public static final StyleProperty<TaffyPosition> POSITION = StylePropertyRegistry.create("position", TaffyPosition.class, TaffyPosition.RELATIVE);

    public static final StyleProperty<LengthPercentageAuto> LEFT = create("left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> TOP = create("top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> RIGHT = create("right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> BOTTOM = create("bottom", LengthPercentageAuto.AUTO);

    public static final StyleProperty<LengthPercentageAuto> MARGIN_LEFT = create("margin-left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_TOP = create("margin-top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_RIGHT = create("margin-right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_BOTTOM = create("margin-bottom", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_VERTICAL = create("margin-vertical", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_HORIZONTAL = create("margin-horizontal", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> MARGIN_ALL = create("margin-all", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LPARect> MARGIN = create("margin", LPARect.ZERO);

    public static final StyleProperty<LengthPercentageAuto> PADDING_LEFT = create("padding-left", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_TOP = create("padding-top", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_RIGHT = create("padding-right", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_BOTTOM = create("padding-bottom", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_VERTICAL = create("padding-vertical", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_HORIZONTAL = create("padding-horizontal", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LengthPercentageAuto> PADDING_ALL = create("padding-all", LengthPercentageAuto.AUTO);
    public static final StyleProperty<LPARect> PADDING = create("padding", LPARect.ZERO);

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

        createSetter(LayoutProperties.MARGIN_LEFT, (style, value) -> style.margin.setLeft(value));
        createSetter(LayoutProperties.MARGIN_TOP, (style, value) -> style.margin.setTop(value));
        createSetter(LayoutProperties.MARGIN_RIGHT, (style, value) -> style.margin.setRight(value));
        createSetter(LayoutProperties.MARGIN_BOTTOM, (style, value) -> style.margin.setBottom(value));
        createSetter(LayoutProperties.MARGIN_VERTICAL, (style, value) -> style.margin.setVertical(value));
        createSetter(LayoutProperties.MARGIN_HORIZONTAL, (style, value) -> style.margin.setHorizontal(value));
        createSetter(LayoutProperties.MARGIN_ALL, (style, value) -> style.margin.setAll(value));
        createSetter(LayoutProperties.MARGIN, (style, value) -> style.margin.setRect(value));

        createSetter(LayoutProperties.PADDING_LEFT, (style, value) -> style.padding.setLeft(value));
        createSetter(LayoutProperties.PADDING_TOP, (style, value) -> style.padding.setTop(value));
        createSetter(LayoutProperties.PADDING_RIGHT, (style, value) -> style.padding.setRight(value));
        createSetter(LayoutProperties.PADDING_BOTTOM, (style, value) -> style.padding.setBottom(value));
        createSetter(LayoutProperties.PADDING_VERTICAL, (style, value) -> style.padding.setVertical(value));
        createSetter(LayoutProperties.PADDING_HORIZONTAL, (style, value) -> style.padding.setHorizontal(value));
        createSetter(LayoutProperties.PADDING_ALL, (style, value) -> style.padding.setAll(value));
        createSetter(LayoutProperties.PADDING, (style, value) -> style.padding.setRect(value));

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

    public static StyleProperty<LPARect> create(String name, LPARect initialValue) {
        return StylePropertyRegistry.create(new LPARectProperty(name, initialValue));
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


    private static <T> void createSetter(StyleProperty<T> property,
                                         BiConsumer<TaffyBridge, T> taffySetter) {
        property.addListener((el, p, oldValue, newValue) ->
                taffySetter.accept(el.getStyle().getTaffyBridge(), newValue == null ? property.initialValue : newValue));
    }
}
