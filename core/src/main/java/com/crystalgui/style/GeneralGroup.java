package com.crystalgui.style;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.transition.TransitionSpec;

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
    public Overflow overflow() {
        return getValueSave(StylePropertyRegistry.OVERFLOW);
    }

    public GeneralGroup overflow(Overflow overflow) {
        set(StylePropertyRegistry.OVERFLOW, overflow);
        return this;
    }

    public CgUiDrawable mask() {
        return getValueSave(StylePropertyRegistry.MASK);
    }

    public GeneralGroup mask(CgUiDrawable overflowClip) {
        set(StylePropertyRegistry.MASK, overflowClip);
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
