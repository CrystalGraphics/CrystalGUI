package com.crystalgui.rewrite.layout;

import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentage;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyPosition;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.TaffyStyle;
import dev.vfyjxf.taffy.geometry.TaffyRect;

/**
 * Fluent wrapper over a raw {@link TaffyStyle}.
 *
 * <p>{@code TaffyStyle} itself is a plain mutable POJO with public fields (verified
 * directly against {@code dev.vfyjxf:taffy} 1.1.4 source — it is NOT a builder), so this
 * class is just chainable sugar over those fields. Percent values follow Taffy's own
 * convention: a 0.0–1.0 fraction, not 0–100 (e.g. {@code widthPercent(0.5f)} = 50%).</p>
 *
 * <p>Every setter here marks the owning node dirty via the callback passed at
 * construction — {@code UIElement.layout(...)} wires this to
 * {@code TaffyTree.markDirty(nodeId)} when the element is already attached to a
 * {@code UiRuntime}. Elements not yet attached simply mutate their {@code TaffyStyle}
 * directly; the whole style is read when the node is first created in the tree.</p>
 */
public final class LayoutStyle {

    private final TaffyStyle style;
    private final Runnable onDirty;

    public LayoutStyle(TaffyStyle style, Runnable onDirty) {
        this.style = style;
        this.onDirty = onDirty;
    }

    public TaffyStyle raw() {
        return style;
    }

    private LayoutStyle mark() {
        onDirty.run();
        return this;
    }

    // ── Size ─────────────────────────────────────────────────────────────────

    public LayoutStyle width(float px) {
        style.size.width = TaffyDimension.length(px);
        return mark();
    }

    public LayoutStyle widthPercent(float fraction) {
        style.size.width = TaffyDimension.percent(fraction);
        return mark();
    }

    public LayoutStyle widthAuto() {
        style.size.width = TaffyDimension.auto();
        return mark();
    }

    public LayoutStyle height(float px) {
        style.size.height = TaffyDimension.length(px);
        return mark();
    }

    public LayoutStyle heightPercent(float fraction) {
        style.size.height = TaffyDimension.percent(fraction);
        return mark();
    }

    public LayoutStyle heightAuto() {
        style.size.height = TaffyDimension.auto();
        return mark();
    }

    public LayoutStyle size(float widthPx, float heightPx) {
        style.size = TaffySize.of(TaffyDimension.length(widthPx), TaffyDimension.length(heightPx));
        return mark();
    }

    // ── Flex container ──────────────────────────────────────────────────────

    public LayoutStyle flexDirection(FlexDirection direction) {
        style.flexDirection = direction;
        return mark();
    }

    public LayoutStyle flexGrow(float grow) {
        style.flexGrow = grow;
        return mark();
    }

    public LayoutStyle flexShrink(float shrink) {
        style.flexShrink = shrink;
        return mark();
    }

    public LayoutStyle flexBasis(float px) {
        style.flexBasis = TaffyDimension.length(px);
        return mark();
    }

    public LayoutStyle alignItems(AlignItems align) {
        style.alignItems = align;
        return mark();
    }

    public LayoutStyle justifyContent(AlignContent justify) {
        style.justifyContent = justify;
        return mark();
    }

    public LayoutStyle gap(float px) {
        style.gap = TaffySize.all(LengthPercentage.length(px));
        return mark();
    }

    // ── Box model ────────────────────────────────────────────────────────────

    public LayoutStyle padding(float px) {
        style.padding = TaffyRect.all(LengthPercentage.length(px));
        return mark();
    }

    public LayoutStyle padding(float left, float top, float right, float bottom) {
        style.padding = TaffyRect.of(LengthPercentage.length(left), LengthPercentage.length(right),
                LengthPercentage.length(top), LengthPercentage.length(bottom));
        return mark();
    }

    public LayoutStyle margin(float px) {
        style.margin = TaffyRect.all(LengthPercentageAuto.length(px));
        return mark();
    }

    public LayoutStyle margin(float left, float top, float right, float bottom) {
        style.margin = TaffyRect.of(LengthPercentageAuto.length(left), LengthPercentageAuto.length(right),
                LengthPercentageAuto.length(top), LengthPercentageAuto.length(bottom));
        return mark();
    }

    // ── Positioning ──────────────────────────────────────────────────────────

    public LayoutStyle position(TaffyPosition position) {
        style.position = position;
        return mark();
    }

    /** Only meaningful with {@link #position(TaffyPosition) position(ABSOLUTE)}. */
    public LayoutStyle inset(float left, float top, float right, float bottom) {
        style.inset = TaffyRect.of(LengthPercentageAuto.length(left), LengthPercentageAuto.length(right),
                LengthPercentageAuto.length(top), LengthPercentageAuto.length(bottom));
        return mark();
    }

    /**
     * Sets only left/top (right/bottom stay AUTO, i.e. unconstrained) — the common case of
     * positioning an absolutely-positioned element at (x, y) while letting its own
     * {@link #width}/{@link #height} determine its size, rather than stretching it to fill
     * the containing block. Only meaningful with {@link #position(TaffyPosition) position(ABSOLUTE)}.
     */
    public LayoutStyle inset(float left, float top) {
        style.inset.left = LengthPercentageAuto.length(left);
        style.inset.top = LengthPercentageAuto.length(top);
        return mark();
    }
}
