package com.crystalgui.style;

import com.crystalgui.style.property.layout.grid.Grid;
import com.crystalgui.style.property.layout.grid.GridAuto;
import com.crystalgui.style.property.layout.grid.GridTemplate;
import com.crystalgui.style.property.layout.grid.GridTemplateAreas;
import com.crystalgui.style.property.layout.length.LPSize;
import dev.vfyjxf.taffy.geometry.TaffyRect;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.*;
import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class TaffyBridge {
    public static final TaffyStyle DEFAULT_TAFFY_STYLE = new TaffyStyle();
    static {
        DEFAULT_TAFFY_STYLE.flexDirection = FlexDirection.COLUMN;
        DEFAULT_TAFFY_STYLE.flexShrink = 0;
        DEFAULT_TAFFY_STYLE.minSize = TaffySize.all(TaffyDimension.ZERO);
        DEFAULT_TAFFY_STYLE.alignContent = AlignContent.FLEX_START;
        // Deliberately BORDER_BOX, NOT real CSS's actual initial value (content-box) — a project
        // choice, matching the common UI-framework convention (Bootstrap et al.) of defaulting to
        // the more intuitive box model where a declared width/height already includes padding+border
        // instead of growing past it. This happens to already match Taffy's own native default, but
        // is kept as an explicit assignment rather than relying on that implicitly, so the intended
        // default stays self-documented and isn't silently at the mercy of a future Taffy version
        // changing its own default.
        DEFAULT_TAFFY_STYLE.boxSizing = BoxSizing.BORDER_BOX;
    }

    public final TaffyStyle style;
    // runtime
    public final LPSizeData gap;

    @Getter
    private final ElementStyle styleList;


    public TaffyBridge(ElementStyle styleList) {
        this.styleList = styleList;
        this.style = DEFAULT_TAFFY_STYLE.copy();
        this.gap = new LPSizeData(() -> style.gap, gap -> {
            style.gap = gap;
            styleList.markTaffyStyleDirty();
        });
    }

    public void setDisplay(TaffyDisplay display) {
        if (style.display != display) {
            style.display = display;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setDirection(TaffyDirection direction) {
        if (style.direction != direction) {
            style.direction = direction;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlexBasis(TaffyDimension flexBasis) {
        if (!style.flexBasis.equals(flexBasis)) {
            style.flexBasis = flexBasis;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlex(float flex) {
        if (Float.isNaN(flex) && Float.isNaN(style.flex)) return;
        if (style.flex != flex) {
            style.flex = flex;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlexGrow(float flexGrow) {
        if (Float.isNaN(flexGrow) && Float.isNaN(style.flexGrow)) return;
        if (style.flexGrow != flexGrow) {
            style.flexGrow = flexGrow;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlexShrink(float flexShrink) {
        if (Float.isNaN(flexShrink) && Float.isNaN(style.flexShrink)) return;
        if (style.flexShrink != flexShrink) {
            style.flexShrink = flexShrink;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlexDirection(FlexDirection flexDirection) {
        if (style.flexDirection != flexDirection) {
            style.flexDirection = flexDirection;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setFlexWrap(FlexWrap flexWrap) {
        if (style.flexWrap != flexWrap) {
            style.flexWrap = flexWrap;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setPosition(TaffyPosition position) {
        if (style.position != position) {
            style.position = position;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBoxSizing(BoxSizing boxSizing) {
        if (style.boxSizing != boxSizing) {
            style.boxSizing = boxSizing;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setAlignItems(AlignItems alignItems) {
        if (style.alignItems != alignItems) {
            style.alignItems = alignItems;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setJustifyContent(AlignContent justifyContent) {
        if (style.justifyContent != justifyContent) {
            style.justifyContent = justifyContent;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setJustifySelf(AlignItems justifySelf) {
        if (style.justifySelf != justifySelf) {
            style.justifySelf = justifySelf;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setJustifyItems(AlignItems justifyItems) {
        if (style.justifyItems != justifyItems) {
            style.justifyItems = justifyItems;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setAlignSelf(AlignItems alignSelf) {
        if (style.alignSelf != alignSelf) {
            style.alignSelf = alignSelf;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setAlignContent(AlignContent alignContent) {
        if (style.alignContent != alignContent) {
            style.alignContent = alignContent;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setAspectRate(float aspectRatio) {
        if (Float.isNaN(style.aspectRatio) && Float.isNaN(aspectRatio)) return;
        if (style.aspectRatio != aspectRatio) {
            style.aspectRatio = aspectRatio;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setWidth(TaffyDimension width) {
        if (!Objects.equals(style.size.width, width)) {
            style.size = new TaffySize<>(width, style.size.height);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setHeight(TaffyDimension height) {
        if (!Objects.equals(style.size.height, height)) {
            style.size = new TaffySize<>(style.size.width, height);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMinWidth(TaffyDimension minWidth) {
        if (!Objects.equals(style.minSize.width, minWidth)) {
            style.minSize = new TaffySize<>(minWidth, style.minSize.height);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMinHeight(TaffyDimension minHeight) {
        if (!Objects.equals(style.minSize.height, minHeight)) {
            style.minSize = new TaffySize<>(style.minSize.width, minHeight);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMaxWidth(TaffyDimension maxWidth) {
        if (!Objects.equals(style.maxSize.width, maxWidth)) {
            style.maxSize = new TaffySize<>(maxWidth, style.maxSize.height);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMaxHeight(TaffyDimension maxHeight) {
        if (!Objects.equals(style.maxSize.height, maxHeight)) {
            style.maxSize = new TaffySize<>(style.maxSize.width, maxHeight);
            styleList.markTaffyStyleDirty();
        }
    }

    // ==================== Grid Properties ====================

    public void setGridTemplateRows(GridTemplate value) {
        var dirty = false;
        if (!Objects.equals(style.gridTemplateRows, value.simples())) {
            style.gridTemplateRows = value.simples();
            dirty = true;
        }
        if (!Objects.equals(style.gridTemplateRowsWithRepeat, value.repeats())) {
            style.gridTemplateRowsWithRepeat = value.repeats();
            dirty = true;
        }
        if (!Objects.equals(style.gridTemplateRowNames, value.names())) {
            style.gridTemplateRowNames = value.names();
            dirty = true;
        }
        if (dirty) {
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridTemplateColumns(GridTemplate value) {
        var dirty = false;
        if (!Objects.equals(style.gridTemplateColumns, value.simples())) {
            style.gridTemplateColumns = value.simples();
            dirty = true;
        }
        if (!Objects.equals(style.gridTemplateColumnsWithRepeat, value.repeats())) {
            style.gridTemplateColumnsWithRepeat = value.repeats();
            dirty = true;
        }
        if (!Objects.equals(style.gridTemplateColumnNames, value.names())) {
            style.gridTemplateColumnNames = value.names();
            dirty = true;
        }
        if (dirty) {
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridTemplateAreas(GridTemplateAreas value) {
        if (!Objects.equals(style.gridTemplateAreas, value.areas())) {
            style.gridTemplateAreas = value.areas();
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridAutoRows(GridAuto value) {
        if (!Objects.equals(style.gridAutoRows, value.values())) {
            style.gridAutoRows = value.values();
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridAutoColumns(GridAuto value) {
        if (!Objects.equals(style.gridAutoColumns, value.values())) {
            style.gridAutoColumns = value.values();
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridAutoFlow(GridAutoFlow value) {
        if (style.gridAutoFlow != value) {
            style.gridAutoFlow = value;
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridRow(Grid value) {
        if (!Objects.equals(style.gridRow, value.grid())) {
            style.gridRow = value.grid();
            styleList.markTaffyStyleDirty();
        }
    }

    public void setGridColumn(Grid value) {
        if (!Objects.equals(style.gridColumn, value.grid())) {
            style.gridColumn = value.grid();
            styleList.markTaffyStyleDirty();
        }
    }

    public void setLeft(LengthPercentageAuto left) {
        if (!Objects.equals(style.inset.left, left)) {
            style.inset = new TaffyRect<>(left, style.inset.right, style.inset.top, style.inset.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setTop(LengthPercentageAuto top) {
        if (!Objects.equals(style.inset.top, top)) {
            style.inset = new TaffyRect<>(style.inset.left, style.inset.right, top, style.inset.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setRight(LengthPercentageAuto right) {
        if (!Objects.equals(style.inset.right, right)) {
            style.inset = new TaffyRect<>(style.inset.left, right, style.inset.top, style.inset.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBottom(LengthPercentageAuto bottom) {
        if (!Objects.equals(style.inset.bottom, bottom)) {
            style.inset = new TaffyRect<>(style.inset.left, style.inset.right, style.inset.top, bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    // ==================== Box-model edges (margin/padding/border) ====================
    // Each of these four properties per group (-left/-top/-right/-bottom) is the ONLY real,
    // independently-cascading StyleProperty for that edge — margin/padding/border-width themselves,
    // and their -all/-horizontal/-vertical aliases, are pure parse-time shorthand syntax
    // (see BoxEdgeShorthands) that expands into these same four longhands before the cascade ever
    // runs. So there is no "which of 8 candidates wins" precedence to resolve here at all — just a
    // direct field patch per edge, exactly like setLeft/setTop/setRight/setBottom above.

    public void setMarginLeft(LengthPercentageAuto left) {
        if (!Objects.equals(style.margin.left, left)) {
            style.margin = new TaffyRect<>(left, style.margin.right, style.margin.top, style.margin.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMarginTop(LengthPercentageAuto top) {
        if (!Objects.equals(style.margin.top, top)) {
            style.margin = new TaffyRect<>(style.margin.left, style.margin.right, top, style.margin.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMarginRight(LengthPercentageAuto right) {
        if (!Objects.equals(style.margin.right, right)) {
            style.margin = new TaffyRect<>(style.margin.left, right, style.margin.top, style.margin.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setMarginBottom(LengthPercentageAuto bottom) {
        if (!Objects.equals(style.margin.bottom, bottom)) {
            style.margin = new TaffyRect<>(style.margin.left, style.margin.right, style.margin.top, bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setPaddingLeft(LengthPercentageAuto left) {
        LengthPercentage converted = toLP(left);
        if (!Objects.equals(style.padding.left, converted)) {
            style.padding = new TaffyRect<>(converted, style.padding.right, style.padding.top, style.padding.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setPaddingTop(LengthPercentageAuto top) {
        LengthPercentage converted = toLP(top);
        if (!Objects.equals(style.padding.top, converted)) {
            style.padding = new TaffyRect<>(style.padding.left, style.padding.right, converted, style.padding.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setPaddingRight(LengthPercentageAuto right) {
        LengthPercentage converted = toLP(right);
        if (!Objects.equals(style.padding.right, converted)) {
            style.padding = new TaffyRect<>(style.padding.left, converted, style.padding.top, style.padding.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setPaddingBottom(LengthPercentageAuto bottom) {
        LengthPercentage converted = toLP(bottom);
        if (!Objects.equals(style.padding.bottom, converted)) {
            style.padding = new TaffyRect<>(style.padding.left, style.padding.right, style.padding.top, converted);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBorderLeft(LengthPercentageAuto left) {
        LengthPercentage converted = toLP(left);
        if (!Objects.equals(style.border.left, converted)) {
            style.border = new TaffyRect<>(converted, style.border.right, style.border.top, style.border.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBorderTop(LengthPercentageAuto top) {
        LengthPercentage converted = toLP(top);
        if (!Objects.equals(style.border.top, converted)) {
            style.border = new TaffyRect<>(style.border.left, style.border.right, converted, style.border.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBorderRight(LengthPercentageAuto right) {
        LengthPercentage converted = toLP(right);
        if (!Objects.equals(style.border.right, converted)) {
            style.border = new TaffyRect<>(style.border.left, converted, style.border.top, style.border.bottom);
            styleList.markTaffyStyleDirty();
        }
    }

    public void setBorderBottom(LengthPercentageAuto bottom) {
        LengthPercentage converted = toLP(bottom);
        if (!Objects.equals(style.border.bottom, converted)) {
            style.border = new TaffyRect<>(style.border.left, style.border.right, style.border.top, converted);
            styleList.markTaffyStyleDirty();
        }
    }

    /** {@code padding}/{@code border} are natively {@code TaffyRect<LengthPercentage>} (no AUTO
     * variant) — the style properties feeding them are {@code LengthPercentageAuto} (shared type with
     * margin/inset), so this converts at the write boundary. AUTO itself has no LengthPercentage
     * equivalent; falls back to zero. */
    private static LengthPercentage toLP(LengthPercentageAuto lpa) {
        if (lpa.isPercent()) return LengthPercentage.percent(lpa.getValue());
        if (lpa.isLength()) return LengthPercentage.length(lpa.getValue());
        return LengthPercentage.ZERO;
    }

    public static class LPSizeData {
        private LengthPercentageAuto vertical = LengthPercentageAuto.AUTO;
        private LengthPercentageAuto horizontal = LengthPercentageAuto.AUTO;
        private LengthPercentageAuto all = LengthPercentageAuto.AUTO;
        private LPSize size = LPSize.ZERO;

        private final Supplier<TaffySize<LengthPercentage>> getter;
        private final Consumer<TaffySize<LengthPercentage>> setter;

        public LPSizeData(Supplier<TaffySize<LengthPercentage>> getter, Consumer<TaffySize<LengthPercentage>> setter) {
            this.setter = setter;
            this.getter = getter;
        }

        public void setVertical(LengthPercentageAuto bottom) {
            if (!Objects.equals(this.vertical, bottom)) {
                this.vertical = bottom;
                onChanged();
            }
        }

        public void setHorizontal(LengthPercentageAuto bottom) {
            if (!Objects.equals(this.horizontal, bottom)) {
                this.horizontal = bottom;
                onChanged();
            }
        }

        public void setAll(LengthPercentageAuto bottom) {
            if (!Objects.equals(this.all, bottom)) {
                this.all = bottom;
                onChanged();
            }
        }

        public void setSize(LPSize size) {
            if (!Objects.equals(this.size, size)) {
                this.size = size;
                onChanged();
            }
        }

        public void onChanged() {
            var current = getter.get();
            var width = (this.horizontal.isAuto() ?
                    (this.all.isAuto() ? this.size.size().width :
                            toLP(this.all)) :
                    toLP(this.horizontal));
            var height = (this.vertical.isAuto() ?
                    (this.all.isAuto() ? this.size.size().height :
                            toLP(this.all)) :
                    toLP(this.vertical));
            if (!Objects.equals(width, current.width) ||
                    !Objects.equals(height, current.height)) {
                setter.accept(TaffySize.of(width, height));
            }
        }

        public static LengthPercentage toLP(LengthPercentageAuto lpa) {
            if (lpa.isPercent()) return LengthPercentage.percent(lpa.getValue());
            if (lpa.isLength()) return LengthPercentage.length(lpa.getValue());
            return LengthPercentage.ZERO;
        }
    }
}
