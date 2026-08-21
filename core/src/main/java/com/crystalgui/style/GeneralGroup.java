package com.crystalgui.style;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.DrawableAlign;
import com.crystalgui.style.property.visual.DrawableFit;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgui.style.property.visual.text.FontStyle;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.property.visual.text.TextAlign;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.style.property.visual.ScrollBehavior;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.transition.TransitionSpec;
import com.crystalgui.ui.UITransform;

import java.util.List;

public class GeneralGroup extends StyleGroup<GeneralGroup> {

    public GeneralGroup(ElementStyle elementStyle) {
        super(elementStyle);
    }

    public CgUiDrawable background() {
        return getValueSave(StylePropertyRegistry.BACKGROUND);
    }

    public GeneralGroup background(CgUiDrawable drawable) {
        set(StylePropertyRegistry.BACKGROUND, drawable);
        return this;
    }

    public CgUiDrawable overlay() {
        return getValueSave(StylePropertyRegistry.OVERLAY);
    }

    public GeneralGroup overlay(CgUiDrawable drawable) {
        set(StylePropertyRegistry.OVERLAY, drawable);
        return this;
    }

    // ── overlay geometry longhands (CSS background-origin / object-fit / object-position) ────────

    public BoxOrigin overlayOrigin() {
        return getValueSave(StylePropertyRegistry.OVERLAY_ORIGIN);
    }

    public GeneralGroup overlayOrigin(BoxOrigin origin) {
        set(StylePropertyRegistry.OVERLAY_ORIGIN, origin);
        return this;
    }

    public DrawableFit overlayFit() {
        return getValueSave(StylePropertyRegistry.OVERLAY_FIT);
    }

    public GeneralGroup overlayFit(DrawableFit fit) {
        set(StylePropertyRegistry.OVERLAY_FIT, fit);
        return this;
    }

    public DrawableAlign overlayPosition() {
        return getValueSave(StylePropertyRegistry.OVERLAY_POSITION);
    }

    public GeneralGroup overlayPosition(DrawableAlign position) {
        set(StylePropertyRegistry.OVERLAY_POSITION, position);
        return this;
    }

    // ── outline: layout-free focus/decoration ring, drawn above overlay ──────────────────────────

    public CgUiDrawable outline() {
        return getValueSave(StylePropertyRegistry.OUTLINE);
    }

    public GeneralGroup outline(CgUiDrawable drawable) {
        set(StylePropertyRegistry.OUTLINE, drawable);
        return this;
    }

    public LengthPercent outlineOffsetTop() {
        return getValueSave(StylePropertyRegistry.OUTLINE_OFFSET_TOP);
    }

    public LengthPercent outlineOffsetRight() {
        return getValueSave(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT);
    }

    public LengthPercent outlineOffsetBottom() {
        return getValueSave(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM);
    }

    public LengthPercent outlineOffsetLeft() {
        return getValueSave(StylePropertyRegistry.OUTLINE_OFFSET_LEFT);
    }

    /** Sets all four edges — the Java equivalent of the one-value {@code outline-offset} shorthand. */
    public GeneralGroup outlineOffset(LengthPercent offset) {
        set(StylePropertyRegistry.OUTLINE_OFFSET_TOP, offset);
        set(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT, offset);
        set(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM, offset);
        set(StylePropertyRegistry.OUTLINE_OFFSET_LEFT, offset);
        return this;
    }

    public GeneralGroup outlineOffset(LengthPercent top, LengthPercent right,
                                      LengthPercent bottom, LengthPercent left) {
        set(StylePropertyRegistry.OUTLINE_OFFSET_TOP, top);
        set(StylePropertyRegistry.OUTLINE_OFFSET_RIGHT, right);
        set(StylePropertyRegistry.OUTLINE_OFFSET_BOTTOM, bottom);
        set(StylePropertyRegistry.OUTLINE_OFFSET_LEFT, left);
        return this;
    }

    public LengthPercent outlineWidth() {
        return getValueSave(StylePropertyRegistry.OUTLINE_WIDTH);
    }

    public GeneralGroup outlineWidth(LengthPercent width) {
        set(StylePropertyRegistry.OUTLINE_WIDTH, width);
        return this;
    }

    public int outlineColor() {
        return getValueSave(StylePropertyRegistry.OUTLINE_COLOR);
    }

    public GeneralGroup outlineColor(int argb) {
        set(StylePropertyRegistry.OUTLINE_COLOR, argb);
        return this;
    }

    public int backgroundColor() {
        return getValueSave(StylePropertyRegistry.BACKGROUND_COLOR);
    }

    public GeneralGroup backgroundColor(int colorArgb) {
        set(StylePropertyRegistry.BACKGROUND_COLOR, colorArgb);
        return this;
    }

    public float opacity() {
        return getValueSave(StylePropertyRegistry.OPACITY);
    }

    public GeneralGroup opacity(float opacity) {
        set(StylePropertyRegistry.OPACITY, opacity);
        return this;
    }

    public int color() {
        return getValueSave(StylePropertyRegistry.COLOR);
    }

    public GeneralGroup color(int color) {
        set(StylePropertyRegistry.COLOR, color);
        return this;
    }

    public float fontSize() {
        return getValueSave(StylePropertyRegistry.FONT_SIZE);
    }

    public GeneralGroup fontSize(float fontSize) {
        set(StylePropertyRegistry.FONT_SIZE, fontSize);
        return this;
    }

    /** CSS {@code font-weight} — inherited. Drawn by {@code UIText} only; see the registry entry. */
    public FontWeight fontWeight() {
        return getValueSave(StylePropertyRegistry.FONT_WEIGHT);
    }

    public GeneralGroup fontWeight(FontWeight weight) {
        set(StylePropertyRegistry.FONT_WEIGHT, weight);
        return this;
    }

    /** CSS {@code font-style} — inherited. Drawn by {@code UIText} only; see the registry entry. */
    public FontStyle fontStyle() {
        return getValueSave(StylePropertyRegistry.FONT_STYLE);
    }

    public GeneralGroup fontStyle(FontStyle style) {
        set(StylePropertyRegistry.FONT_STYLE, style);
        return this;
    }

    /** Line box height as a multiple of {@link #fontSize()}, as CSS's unitless line-height is. */
    public float lineHeight() {
        return getValueSave(StylePropertyRegistry.LINE_HEIGHT);
    }

    public GeneralGroup lineHeight(float lineHeight) {
        set(StylePropertyRegistry.LINE_HEIGHT, lineHeight);
        return this;
    }

    /** Text-insertion caret thickness, in logical px. */
    public float caretWidth() {
        return getValueSave(StylePropertyRegistry.CARET_WIDTH);
    }

    public GeneralGroup caretWidth(float caretWidth) {
        set(StylePropertyRegistry.CARET_WIDTH, caretWidth);
        return this;
    }

    /** The caret's colour, or 0 to follow {@code color}. @see StylePropertyRegistry#CARET_COLOR */
    public int caretColor() {
        return getValueSave(StylePropertyRegistry.CARET_COLOR);
    }

    public GeneralGroup caretColor(int caretColor) {
        set(StylePropertyRegistry.CARET_COLOR, caretColor);
        return this;
    }

    /** Fill behind selected text — CSS's {@code ::selection { background-color }}. */
    public int selectionColor() {
        return getValueSave(StylePropertyRegistry.SELECTION_COLOR);
    }

    public GeneralGroup selectionColor(int selectionColor) {
        set(StylePropertyRegistry.SELECTION_COLOR, selectionColor);
        return this;
    }

    /** Paint-time nudge of the glyphs within their already-computed box. Never affects layout. */
    public LengthPercent textOffsetX() {
        return getValueSave(StylePropertyRegistry.TEXT_OFFSET_X);
    }

    public GeneralGroup textOffsetX(LengthPercent offset) {
        set(StylePropertyRegistry.TEXT_OFFSET_X, offset);
        return this;
    }

    /** @see #textOffsetX() */
    public LengthPercent textOffsetY() {
        return getValueSave(StylePropertyRegistry.TEXT_OFFSET_Y);
    }

    public GeneralGroup textOffsetY(LengthPercent offset) {
        set(StylePropertyRegistry.TEXT_OFFSET_Y, offset);
        return this;
    }

    /**
     * CSS's {@code transform} — a paint-time affine over this element and its subtree, applied on top
     * of layout without disturbing it. Hit-testing follows it automatically.
     *
     * @see com.crystalgui.ui.UIElement#setTransform(UITransform)
     */
    public UITransform transform() {
        return getValueSave(StylePropertyRegistry.TRANSFORM);
    }

    public GeneralGroup transform(UITransform transform) {
        set(StylePropertyRegistry.TRANSFORM, transform == null ? UITransform.IDENTITY : transform);
        return this;
    }

    /** The point {@link #transform()} scales and rotates about. Defaults to 50% — the element's centre. */
    public LengthPercent transformOriginX() {
        return getValueSave(StylePropertyRegistry.TRANSFORM_ORIGIN_X);
    }

    public GeneralGroup transformOriginX(LengthPercent origin) {
        set(StylePropertyRegistry.TRANSFORM_ORIGIN_X, origin);
        return this;
    }

    /** @see #transformOriginX() */
    public LengthPercent transformOriginY() {
        return getValueSave(StylePropertyRegistry.TRANSFORM_ORIGIN_Y);
    }

    public GeneralGroup transformOriginY(LengthPercent origin) {
        set(StylePropertyRegistry.TRANSFORM_ORIGIN_Y, origin);
        return this;
    }

    /** Both axes at once — the {@code transform-origin} shorthand's Java equivalent. */
    public GeneralGroup transformOrigin(LengthPercent x, LengthPercent y) {
        return transformOriginX(x).transformOriginY(y);
    }

    /** Fallback stack of font asset paths, primary first. */
    public List<String> fontFamily() {
        return getValueSave(StylePropertyRegistry.FONT_FAMILY);
    }

    public GeneralGroup fontFamily(List<String> paths) {
        set(StylePropertyRegistry.FONT_FAMILY, paths);
        return this;
    }

    public int zIndex() {
        return getValueSave(StylePropertyRegistry.Z_INDEX);
    }

    public GeneralGroup zIndex(int zIndex) {
        set(StylePropertyRegistry.Z_INDEX, zIndex);
        return this;
    }

    /** Raw {@code overflow:} value — whether clipping happens at all. This is NOT the clip
     * mechanism to render/hit-test with; use {@code UIElement#resolveOverflowClip()} for that
     * (it auto-detects scissor vs mask from the element's actual resolved shape). */
    /** CSS `resize` — whether the user may drag this element's corner. @see Resize */
    public Resize resize() {
        return getValueSave(StylePropertyRegistry.RESIZE);
    }

    public GeneralGroup resize(Resize resize) {
        set(StylePropertyRegistry.RESIZE, resize);
        return this;
    }

    /** CSS `text-shadow`, as a boolean drop shadow. Inherited. Registered long before anything drew
     * it; UIText.paintOverlay finally consumes it. */
    public boolean textShadow() {
        return getValueSave(StylePropertyRegistry.TEXT_SHADOW);
    }

    public GeneralGroup textShadow(boolean textShadow) {
        set(StylePropertyRegistry.TEXT_SHADOW, textShadow);
        return this;
    }

    /** CSS `text-align` -- inherited. @see TextAlign */
    public TextAlign textAlign() {
        return getValueSave(StylePropertyRegistry.TEXT_ALIGN);
    }

    public GeneralGroup textAlign(TextAlign align) {
        set(StylePropertyRegistry.TEXT_ALIGN, align);
        return this;
    }

    /** CSS `white-space`, wrapping half only -- inherited. @see WhiteSpace */
    public WhiteSpace whiteSpace() {
        return getValueSave(StylePropertyRegistry.WHITE_SPACE);
    }

    public GeneralGroup whiteSpace(WhiteSpace whiteSpace) {
        set(StylePropertyRegistry.WHITE_SPACE, whiteSpace);
        return this;
    }

    /** CSS `text-overflow` -- NOT inherited. @see TextOverflow */
    public TextOverflow textOverflow() {
        return getValueSave(StylePropertyRegistry.TEXT_OVERFLOW);
    }

    public GeneralGroup textOverflow(TextOverflow overflow) {
        set(StylePropertyRegistry.TEXT_OVERFLOW, overflow);
        return this;
    }

    /** CSS `cursor` -- inherited, initial `auto`. @see CgCursor */
    public CgCursor cursor() {
        return getValueSave(StylePropertyRegistry.CURSOR);
    }

    public GeneralGroup cursor(CgCursor cursor) {
        set(StylePropertyRegistry.CURSOR, cursor);
        return this;
    }

    public Overflow overflow() {
        return getValueSave(StylePropertyRegistry.OVERFLOW);
    }

    public GeneralGroup overflow(Overflow overflow) {
        set(StylePropertyRegistry.OVERFLOW, overflow);
        return this;
    }

    public ScrollBehavior scrollBehavior() {
        return getValueSave(StylePropertyRegistry.SCROLL_BEHAVIOR);
    }

    public GeneralGroup scrollBehavior(ScrollBehavior behavior) {
        set(StylePropertyRegistry.SCROLL_BEHAVIOR, behavior);
        return this;
    }

    public float scrollDuration() {
        return getValueSave(StylePropertyRegistry.SCROLL_DURATION);
    }

    public GeneralGroup scrollDuration(float seconds) {
        set(StylePropertyRegistry.SCROLL_DURATION, seconds);
        return this;
    }

    public float tooltipDelay() {
        return getValueSave(StylePropertyRegistry.TOOLTIP_DELAY);
    }

    public GeneralGroup tooltipDelay(float seconds) {
        set(StylePropertyRegistry.TOOLTIP_DELAY, seconds);
        return this;
    }

    public CgUiDrawable mask() {
        return getValueSave(StylePropertyRegistry.MASK);
    }

    public GeneralGroup mask(CgUiDrawable overflowClip) {
        set(StylePropertyRegistry.MASK, overflowClip);
        return this;
    }

    // ── mask geometry longhands — same trio as overlay above, same semantics ─────────────────────

    public BoxOrigin maskOrigin() {
        return getValueSave(StylePropertyRegistry.MASK_ORIGIN);
    }

    public GeneralGroup maskOrigin(BoxOrigin origin) {
        set(StylePropertyRegistry.MASK_ORIGIN, origin);
        return this;
    }

    public DrawableFit maskFit() {
        return getValueSave(StylePropertyRegistry.MASK_FIT);
    }

    public GeneralGroup maskFit(DrawableFit fit) {
        set(StylePropertyRegistry.MASK_FIT, fit);
        return this;
    }

    public DrawableAlign maskPosition() {
        return getValueSave(StylePropertyRegistry.MASK_POSITION);
    }

    public GeneralGroup maskPosition(DrawableAlign position) {
        set(StylePropertyRegistry.MASK_POSITION, position);
        return this;
    }

    public LengthPercent maskOffset() {
        return getValueSave(StylePropertyRegistry.MASK_OFFSET);
    }

    public GeneralGroup maskOffset(LengthPercent offset) {
        set(StylePropertyRegistry.MASK_OFFSET, offset);
        return this;
    }

    /** Uniform circular radius on all 4 corners (px) — the common case. For per-corner/elliptical
     * control, set the 8 {@link com.crystalgui.style.property.visual.border.BorderRadiusProperties}
     * longhands directly, or use the {@code border-radius:} stylesheet shorthand. */
    public GeneralGroup borderRadius(float radiusPx) {
        var px = com.crystalgui.style.property.visual.border.LengthPercent.px(radiusPx);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_LEFT_X, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_LEFT_Y, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_RIGHT_X, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.TOP_RIGHT_Y, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.BOTTOM_RIGHT_X, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.BOTTOM_RIGHT_Y, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.BOTTOM_LEFT_X, px);
        set(com.crystalgui.style.property.visual.border.BorderRadiusProperties.BOTTOM_LEFT_Y, px);
        return this;
    }

    public int borderColor() {
        return getValueSave(StylePropertyRegistry.BORDER_COLOR);
    }

    public GeneralGroup borderColor(int colorArgb) {
        set(StylePropertyRegistry.BORDER_COLOR, colorArgb);
        return this;
    }

    /** 0 when unset — the sentinel {@link UIElement} reads as "fall back to {@link #borderColor()}". */
    public int borderTopColor() {
        return getValueSave(StylePropertyRegistry.BORDER_TOP_COLOR);
    }

    public GeneralGroup borderTopColor(int colorArgb) {
        set(StylePropertyRegistry.BORDER_TOP_COLOR, colorArgb);
        return this;
    }

    /** 0 when unset — the sentinel {@link UIElement} reads as "fall back to {@link #borderColor()}". */
    public int borderBottomColor() {
        return getValueSave(StylePropertyRegistry.BORDER_BOTTOM_COLOR);
    }

    public GeneralGroup borderBottomColor(int colorArgb) {
        set(StylePropertyRegistry.BORDER_BOTTOM_COLOR, colorArgb);
        return this;
    }

    public List<TransitionSpec> transition() {
        return getValueSave(StylePropertyRegistry.TRANSITION);
    }

    /** Parses a CSS-{@code transition}-shorthand string — see {@link TransitionSpec} for the grammar. */
    public GeneralGroup transition(String raw) {
        set(StylePropertyRegistry.TRANSITION, TransitionSpec.parse(raw));
        return this;
    }

    public GeneralGroup transition(List<TransitionSpec> specs) {
        set(StylePropertyRegistry.TRANSITION, specs);
        return this;
    }


//    public CgUiDrawable mask() {
//        return getValueSave(StylePropertyRegistry.MASK);
//    }
//
//    public VisualGroup mask(CgUiDrawable drawable) {
//        set(StylePropertyRegistry.MASK, drawable);
//        return this;
//    }

}
