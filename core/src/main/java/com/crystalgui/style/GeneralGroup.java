package com.crystalgui.style;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.visual.OverflowClip;

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

    public OverflowClip overflow() {
        return getValueSave(StylePropertyRegistry.CLIP);
    }

    public GeneralGroup overflow(OverflowClip clip) {
        set(StylePropertyRegistry.CLIP, clip);
        return this;
    }

    public CgUiDrawable mask() {
        return getValueSave(StylePropertyRegistry.MASK);
    }

    public GeneralGroup mask(CgUiDrawable overflowClip) {
        set(StylePropertyRegistry.MASK, overflowClip);
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
