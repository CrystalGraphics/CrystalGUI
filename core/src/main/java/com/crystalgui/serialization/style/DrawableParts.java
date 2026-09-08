package com.crystalgui.serialization.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.property.visual.texture.DrawableKinds;
import com.crystalgui.style.property.visual.texture.TextureValue;

/**
 * A drawable, filed under the <b>function that produced it</b> — gradient, glass, asset, icon.
 *
 * <p>{@code background}, {@code overlay}, {@code mask} and {@code outline} all hold the same Java type
 * and nothing else about them is alike: a nine-slice sprite, a blurred pane of glass and a colour ramp
 * are three different things a designer thinks about separately, and filing all of them under
 * "Appearance" asks somebody to tick a box called {@code background} without saying what it holds.</p>
 *
 * <h3>One part, and which one depends on the value</h3>
 *
 * <p>Every kind is declared as a part; a value answers for exactly the one it is. So a drawable is
 * never divided — there is nothing inside {@code linear-gradient(...)} to tick separately — but it IS
 * grouped, which is what the sections are for. A part carries no label of its own, so it is named after
 * its property: the section says what kind of value it is and the row says which property holds it.</p>
 *
 * <h3>Read off the authored text</h3>
 *
 * <p>A drawable cannot say what made it — {@code CgUiSvg} holds a parsed document, {@code CgUiSprite}
 * resolved UVs — so the kind is read from the CSS it was parsed from, which is what the whole value
 * serialises as anyway. A drawable built in Java has no text and is filed under Appearance like any
 * other property, rather than guessed at.</p>
 */
public final class DrawableParts implements StyleParts<CgUiDrawable> {

    public static final DrawableParts INSTANCE = new DrawableParts();

    private DrawableParts() {

    }

    /**
     * One part per registered kind, read from {@link DrawableKinds} rather than listed again here.
     *
     * <p>Built per call, because a kind can be registered at any time — a mod adding {@code blur()}
     * gets a Blur section with nothing else to touch. The list is nine long; nothing about this is hot
     * enough to cache against a registry that can change.</p>
     */
    @Override
    public List<Part> parts() {
        List<Part> parts = new ArrayList<>();
        for (DrawableKinds.Kind kind : DrawableKinds.all()) {
            // NO LABEL OF ITS OWN: the section says what kind of value it is, so the row is named after
            // the property that holds it. @see StyleParts.Part
            parts.add(new Part(kind.id(), kind.label(), ""));
        }
        return parts;
    }

    /** True when the value came from CSS this knows the shape of. @see StyleParts#divides */
    @Override
    public boolean divides(CgUiDrawable value) {
        return kindOf(value) != null;
    }

    /** Which part {@code value} is, or null when nothing parsed it or its function is unknown. */
    @Nullable
    private static String kindOf(CgUiDrawable value) {
        if (value == CgUiDrawable.EMPTY) return null;   // `none` is an absence, not a kind
        DrawableKinds.Kind kind = DrawableKinds.matching(TextureValue.sourceOf(value));
        return kind == null ? null : kind.id();
    }

    @Nullable
    @Override
    public <T> T encodePart(DynamicOps<T> ops, CgUiDrawable value, String partId) {
        // THE WHOLE VALUE, for the one part it is. There is nothing inside a gradient to tick on its
        // own, so this groups rather than divides -- and answering null for the other eight is what
        // puts it in one section instead of nine.
        return partId.equals(kindOf(value)) ? ops.createString(TextureValue.sourceOf(value)) : null;
    }

    @Override
    public <T> CgUiDrawable mergePart(DynamicOps<T> ops, CgUiDrawable base, String partId, T encoded) {
        // REPLACES, because the part IS the value: a background is one drawable, and pasting a gradient
        // onto an element that had a sprite means it has a gradient now.
        String text = ops.getStringValue(encoded);
        CgUiDrawable parsed = new TextureValue(text).compute();
        return parsed == null ? base : parsed;
    }
}
