package com.crystalgui.style;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.layout.grid.*;
import com.crystalgui.style.property.layout.length.LPARect;
import com.crystalgui.style.property.layout.length.LPSize;
import dev.vfyjxf.taffy.geometry.TaffyRect;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.*;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decorator for layout-related styles
 */
public class LayoutGroup extends StyleGroup<LayoutGroup> {

    public LayoutGroup(ElementStyle elementStyle) {
        super(elementStyle);
    }

    public LayoutGroup setWidth(TaffyDimension dimension) {
        set(LayoutProperties.WIDTH, dimension);
        return this;
    }

    public LayoutGroup width(float width) {
        set(LayoutProperties.WIDTH, TaffyDimension.length(width));
        return this;
    }

    public LayoutGroup widthPercent(float percent) {
        set(LayoutProperties.WIDTH, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup widthAuto() {
        set(LayoutProperties.WIDTH, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup widthMaxContent() {
        set(LayoutProperties.WIDTH, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup widthMinContent() {
        set(LayoutProperties.WIDTH, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup widthFitContent() {
        set(LayoutProperties.WIDTH, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup widthStretch() {
        set(LayoutProperties.WIDTH, TaffyDimension.stretch());
        return this;
    }

    public LayoutGroup setMinWidth(TaffyDimension dimension) {
        set(LayoutProperties.MIN_WIDTH, dimension);
        return this;
    }

    public LayoutGroup minWidth(float minWidth) {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.length(minWidth));
        return this;
    }

    public LayoutGroup minWidthPercent(float percent) {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup minWidthAuto() {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup minWidthMaxContent() {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup minWidthMinContent() {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup minWidthFitContent() {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup minWidthStretch() {
        set(LayoutProperties.MIN_WIDTH, TaffyDimension.stretch());
        return this;
    }

    public LayoutGroup setMaxWidth(TaffyDimension dimension) {
        set(LayoutProperties.MAX_WIDTH, dimension);
        return this;
    }

    public LayoutGroup maxWidth(float maxWidth) {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.length(maxWidth));
        return this;
    }

    public LayoutGroup maxWidthPercent(float percent) {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup maxWidthAuto() {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup maxWidthMaxContent() {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup maxWidthMinContent() {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup maxWidthFitContent() {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup maxWidthStretch() {
        set(LayoutProperties.MAX_WIDTH, TaffyDimension.stretch());
        return this;
    }

    /* Height properties */
    public LayoutGroup setHeight(TaffyDimension dimension) {
        set(LayoutProperties.HEIGHT, dimension);
        return this;
    }

    public LayoutGroup height(float height) {
        set(LayoutProperties.HEIGHT, TaffyDimension.length(height));
        return this;
    }

    public LayoutGroup heightPercent(float percent) {
        set(LayoutProperties.HEIGHT, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup heightAuto() {
        set(LayoutProperties.HEIGHT, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup heightMaxContent() {
        set(LayoutProperties.HEIGHT, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup heightMinContent() {
        set(LayoutProperties.HEIGHT, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup heightFitContent() {
        set(LayoutProperties.HEIGHT, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup heightStretch() {
        set(LayoutProperties.HEIGHT, TaffyDimension.stretch());
        return this;
    }

    public LayoutGroup setMinHeight(TaffyDimension dimension) {
        set(LayoutProperties.MIN_HEIGHT, dimension);
        return this;
    }

    public LayoutGroup minHeight(float minHeight) {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.length(minHeight));
        return this;
    }

    public LayoutGroup minHeightPercent(float percent) {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup minHeightAuto() {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup minHeightMaxContent() {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup minHeightMinContent() {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup minHeightFitContent() {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup minHeightStretch() {
        set(LayoutProperties.MIN_HEIGHT, TaffyDimension.stretch());
        return this;
    }

    public LayoutGroup setMaxHeight(TaffyDimension dimension) {
        set(LayoutProperties.MAX_HEIGHT, dimension);
        return this;
    }

    public LayoutGroup maxHeight(float maxHeight) {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.length(maxHeight));
        return this;
    }

    public LayoutGroup maxHeightPercent(float percent) {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup maxHeightAuto() {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup maxHeightMaxContent() {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup maxHeightMinContent() {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup maxHeightFitContent() {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup maxHeightStretch() {
        set(LayoutProperties.MAX_HEIGHT, TaffyDimension.stretch());
        return this;
    }

    /* Margin properties */
    public LayoutGroup marginLeft(float margin) {
        set(LayoutProperties.MARGIN_LEFT, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginTop(float margin) {
        set(LayoutProperties.MARGIN_TOP, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginRight(float margin) {
        set(LayoutProperties.MARGIN_RIGHT, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginBottom(float margin) {
        set(LayoutProperties.MARGIN_BOTTOM, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginHorizontal(float margin) {
        set(LayoutProperties.MARGIN_HORIZONTAL, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginVertical(float margin) {
        set(LayoutProperties.MARGIN_VERTICAL, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginAll(float margin) {
        set(LayoutProperties.MARGIN_ALL, LengthPercentageAuto.length(margin));
        return this;
    }

    public LayoutGroup marginLeftPercent(float margin) {
        set(LayoutProperties.MARGIN_LEFT, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginTopPercent(float margin) {
        set(LayoutProperties.MARGIN_TOP, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginRightPercent(float margin) {
        set(LayoutProperties.MARGIN_RIGHT, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginBottomPercent(float margin) {
        set(LayoutProperties.MARGIN_BOTTOM, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginHorizontalPercent(float margin) {
        set(LayoutProperties.MARGIN_HORIZONTAL, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginVerticalPercent(float margin) {
        set(LayoutProperties.MARGIN_VERTICAL, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginAllPercent(float margin) {
        set(LayoutProperties.MARGIN_ALL, LengthPercentageAuto.percent(margin / 100f));
        return this;
    }

    public LayoutGroup marginLeftAuto() {
        set(LayoutProperties.MARGIN_LEFT, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginTopAuto() {
        set(LayoutProperties.MARGIN_TOP, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginRightAuto() {
        set(LayoutProperties.MARGIN_RIGHT, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginBottomAuto() {
        set(LayoutProperties.MARGIN_BOTTOM, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginHorizontalAuto() {
        set(LayoutProperties.MARGIN_HORIZONTAL, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginVerticalAuto() {
        set(LayoutProperties.MARGIN_VERTICAL, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup marginAllAuto() {
        set(LayoutProperties.MARGIN_ALL, LengthPercentageAuto.auto());
        return this;
    }

    /* Padding properties */
    public LayoutGroup paddingLeft(float padding) {
        set(LayoutProperties.PADDING_LEFT, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingTop(float padding) {
        set(LayoutProperties.PADDING_TOP, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingRight(float padding) {
        set(LayoutProperties.PADDING_RIGHT, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingBottom(float padding) {
        set(LayoutProperties.PADDING_BOTTOM, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingHorizontal(float padding) {
        set(LayoutProperties.PADDING_HORIZONTAL, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingVertical(float padding) {
        set(LayoutProperties.PADDING_VERTICAL, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingAll(float padding) {
        set(LayoutProperties.PADDING_ALL, LengthPercentageAuto.length(padding));
        return this;
    }

    public LayoutGroup paddingLeftPercent(float padding) {
        set(LayoutProperties.PADDING_LEFT, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingTopPercent(float padding) {
        set(LayoutProperties.PADDING_TOP, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingRightPercent(float padding) {
        set(LayoutProperties.PADDING_RIGHT, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingBottomPercent(float padding) {
        set(LayoutProperties.PADDING_BOTTOM, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingHorizontalPercent(float padding) {
        set(LayoutProperties.PADDING_HORIZONTAL, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingVerticalPercent(float padding) {
        set(LayoutProperties.PADDING_VERTICAL, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    public LayoutGroup paddingAllPercent(float padding) {
        set(LayoutProperties.PADDING_ALL, LengthPercentageAuto.percent(padding / 100f));
        return this;
    }

    /* Position properties */
    public LayoutGroup positionType(TaffyPosition positionType) {
        set(LayoutProperties.POSITION, positionType);
        return this;
    }

    public LayoutGroup left(float position) {
        set(LayoutProperties.LEFT, LengthPercentageAuto.length(position));
        return this;
    }

    public LayoutGroup top(float position) {
        set(LayoutProperties.TOP, LengthPercentageAuto.length(position));
        return this;
    }

    public LayoutGroup right(float position) {
        set(LayoutProperties.RIGHT, LengthPercentageAuto.length(position));
        return this;
    }

    public LayoutGroup bottom(float position) {
        set(LayoutProperties.BOTTOM, LengthPercentageAuto.length(position));
        return this;
    }

    public LayoutGroup leftPercent(float percent) {
        set(LayoutProperties.LEFT, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup topPercent(float percent) {
        set(LayoutProperties.TOP, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup rightPercent(float percent) {
        set(LayoutProperties.RIGHT, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup bottomPercent(float percent) {
        set(LayoutProperties.BOTTOM, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup leftAuto() {
        set(LayoutProperties.LEFT, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup topAuto() {
        set(LayoutProperties.TOP, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup rightAuto() {
        set(LayoutProperties.RIGHT, LengthPercentageAuto.auto());
        return this;
    }

    public LayoutGroup bottomAuto() {
        set(LayoutProperties.BOTTOM, LengthPercentageAuto.auto());
        return this;
    }

    /* Alignment properties */
    public LayoutGroup alignContent(AlignContent alignContent) {
        set(LayoutProperties.ALIGN_CONTENT, alignContent);
        return this;
    }

    public LayoutGroup alignItems(AlignItems alignItems) {
        set(LayoutProperties.ALIGN_ITEMS, alignItems);
        return this;
    }

    public LayoutGroup alignSelf(AlignItems alignSelf) {
        set(LayoutProperties.ALIGN_SELF, alignSelf);
        return this;
    }

    /* Flex properties */
    public LayoutGroup flex(float flex) {
        set(LayoutProperties.FLEX, flex);
        return this;
    }

    public LayoutGroup flexAuto() {
        set(LayoutProperties.FLEX, Float.NaN);
        return this;
    }


    public LayoutGroup flexBasisAuto() {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.auto());
        return this;
    }

    public LayoutGroup flexBasisPercent(float percent) {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.percent(percent / 100f));
        return this;
    }

    public LayoutGroup flexBasis(float flexBasis) {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.length(flexBasis));
        return this;
    }

    public LayoutGroup flexBasisMaxContent() {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.maxContent());
        return this;
    }

    public LayoutGroup flexBasisMinContent() {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.minContent());
        return this;
    }

    public LayoutGroup flexBasisFitContent() {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.fitContent());
        return this;
    }

    public LayoutGroup flexBasisStretch() {
        set(LayoutProperties.FLEX_BASIS, TaffyDimension.stretch());
        return this;
    }

    public LayoutGroup flexDirection(FlexDirection flexDirection) {
        set(LayoutProperties.FLEX_DIRECTION, flexDirection);
        return this;
    }

    public LayoutGroup setFlexGrow(float flexGrow) {
        set(LayoutProperties.FLEX_GROW, flexGrow);
        return this;
    }

    public LayoutGroup flexGrow(float flexGrow) {
        return setFlexGrow(flexGrow);
    }

    public LayoutGroup setFlexGrowAuto() {
        set(LayoutProperties.FLEX_GROW, Float.NaN);
        return this;
    }

    public LayoutGroup flexGrowAuto() {
        return setFlexGrowAuto();
    }

    public LayoutGroup setFlexShrink(float flexShrink) {
        set(LayoutProperties.FLEX_SHRINK, flexShrink);
        return this;
    }

    public LayoutGroup flexShrink(float flexShrink) {
        return setFlexShrink(flexShrink);
    }

    public LayoutGroup setFlexShrinkAuto() {
        set(LayoutProperties.FLEX_SHRINK, Float.NaN);
        return this;
    }

    public LayoutGroup flexShrinkAuto() {
        return setFlexShrinkAuto();
    }

    /* Other properties */
    public LayoutGroup justifyContent(AlignContent justifyContent) {
        set(LayoutProperties.JUSTIFY_CONTENT, justifyContent);
        return this;
    }

    public LayoutGroup justifyItems(AlignItems justifyItems) {
        set(LayoutProperties.JUSTIFY_ITEMS, justifyItems);
        return this;
    }

    public LayoutGroup justifySelf(AlignItems justifySelf) {
        set(LayoutProperties.JUSTIFY_SELF, justifySelf);
        return this;
    }

    public LayoutGroup direction(TaffyDirection direction) {
        set(LayoutProperties.LAYOUT_DIRECTION, direction);
        return this;
    }

    public LayoutGroup wrap(FlexWrap wrap) {
        set(LayoutProperties.FLEX_WRAP, wrap);
        return this;
    }

    public LayoutGroup flexWrap(FlexWrap wrap) {
        set(LayoutProperties.FLEX_WRAP, wrap);
        return this;
    }

    public LayoutGroup setAspectRatio(float aspectRatio) {
        set(LayoutProperties.ASPECT_RATE, aspectRatio);
        return this;
    }

    public LayoutGroup aspectRatio(float aspectRatio) {
        return setAspectRatio(aspectRatio);
    }

    public LayoutGroup setAspectRatioAuto() {
        set(LayoutProperties.ASPECT_RATE, Float.NaN);
        return this;
    }

    public LayoutGroup aspectRatioAuto() {
        return setAspectRatioAuto();
    }

    public LayoutGroup gapColumn(float value) {
        set(LayoutProperties.GAP_COLUMN, LengthPercentageAuto.length(value));
        return this;
    }

    public LayoutGroup gapRow(float value) {
        set(LayoutProperties.GAP_ROW, LengthPercentageAuto.length(value));
        return this;
    }

    public LayoutGroup gapAll(float value) {
        set(LayoutProperties.GAP_ALL, LengthPercentageAuto.length(value));
        return this;
    }

    public LayoutGroup gapColumnPercent(float percent) {
        set(LayoutProperties.GAP_COLUMN, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup gapRowPercent(float percent) {
        set(LayoutProperties.GAP_ROW, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup gapAllPercent(float percent) {
        set(LayoutProperties.GAP_ALL, LengthPercentageAuto.percent(percent / 100f));
        return this;
    }

    public LayoutGroup display(TaffyDisplay display) {
        set(LayoutProperties.DISPLAY, display);
        return this;
    }

    // grid
    public LayoutGroup gridTemplateRows(String gridTemplateRows) {
        set(LayoutProperties.GRID_TEMPLATE_ROWS, GridTemplateValue.parse(gridTemplateRows));
        return this;
    }

    public LayoutGroup gridTemplateRows(GridTemplate gridTemplateRows) {
        set(LayoutProperties.GRID_TEMPLATE_ROWS, gridTemplateRows);
        return this;
    }

    public LayoutGroup gridTemplateColumns(String gridTemplateColumns) {
        set(LayoutProperties.GRID_TEMPLATE_COLUMNS, GridTemplateValue.parse(gridTemplateColumns));
        return this;
    }

    public LayoutGroup gridTemplateColumns(GridTemplate gridTemplateColumns) {
        set(LayoutProperties.GRID_TEMPLATE_COLUMNS, gridTemplateColumns);
        return this;
    }

    public LayoutGroup gridTemplateAreas(String gridTemplateAreas) {
        set(LayoutProperties.GRID_TEMPLATE_AREAS, GridTemplateAreasValue.parse(gridTemplateAreas));
        return this;
    }

    public LayoutGroup gridTemplateAreas(GridTemplateAreas templateAreas) {
        set(LayoutProperties.GRID_TEMPLATE_AREAS, templateAreas);
        return this;
    }

    public LayoutGroup gridAutoRows(String gridAutoRows) {
        set(LayoutProperties.GRID_AUTO_ROWS, GridAutoValue.parse(gridAutoRows));
        return this;
    }

    public LayoutGroup gridAutoRows(GridAuto gridAutoRows) {
        set(LayoutProperties.GRID_AUTO_ROWS, gridAutoRows);
        return this;
    }

    public LayoutGroup gridAutoColumns(String gridAutoColumns) {
        set(LayoutProperties.GRID_AUTO_COLUMNS, GridAutoValue.parse(gridAutoColumns));
        return this;
    }

    public LayoutGroup gridAutoColumns(GridAuto gridAutoColumns) {
        set(LayoutProperties.GRID_AUTO_COLUMNS, gridAutoColumns);
        return this;
    }

    public LayoutGroup gridAutoFlow(GridAutoFlow gridAutoFlow) {
        set(LayoutProperties.GRID_AUTO_FLOW, gridAutoFlow);
        return this;
    }

    public LayoutGroup gridRow(String gridRow) {
        set(LayoutProperties.GRID_ROW, GridValue.parse(gridRow));
        return this;
    }

    public LayoutGroup gridRow(Grid gridRow) {
        set(LayoutProperties.GRID_ROW, gridRow);
        return this;
    }

    public LayoutGroup gridColumn(String gridColumn) {
        set(LayoutProperties.GRID_COLUMN, GridValue.parse(gridColumn));
        return this;
    }

    public LayoutGroup gridColumn(Grid gridColumn) {
        set(LayoutProperties.GRID_COLUMN, gridColumn);
        return this;
    }
    

}
