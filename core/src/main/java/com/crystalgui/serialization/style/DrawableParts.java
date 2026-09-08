package com.crystalgui.serialization.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiLayers;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.style.property.visual.texture.DrawableKinds;
import com.crystalgui.style.property.visual.texture.TextureValue;

/**
 * A drawable, filed under the <b>function that produced it</b> — gradient, glass, asset, icon — and a
 * <b>layer at a time</b> when the value is a stack.
 *
 * <p>{@code background}, {@code overlay}, {@code mask} and {@code outline} all hold the same Java type
 * and nothing else about them is alike: a nine-slice sprite, a blurred pane of glass and a colour ramp
 * are three different things a designer thinks about separately, and filing all of them under
 * "Appearance" asks somebody to tick a box called {@code background} without saying what it holds.</p>
 *
 * <h3>One part, unless it is a stack</h3>
 *
 * <p>A single drawable is never divided — there is nothing inside {@code linear-gradient(...)} to tick
 * separately — but it IS grouped, which is what the sections are for: it answers for exactly the one
 * kind it is, and the row is named after the property holding it.</p>
 *
 * <p>A stack is several values in one declaration, so it offers one part per LAYER, each filed under its
 * own kind: {@code background: grid(…), shape(…), linear-gradient(…)} comes out as three rows, under
 * Grid, Shape and Gradient, tickable separately. Two layers of the same kind land in one section as two
 * identically-named rows, which is the one thing this cannot yet tell apart.</p>
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

    /** Prefixes a layer's part id — {@code background/layer-0} is the topmost layer. */
    private static final String LAYER = "layer-";

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

    /**
     * A stack's parts are its layers, in CSS order — so the first row is the topmost layer.
     *
     * <p>Positional, and therefore the value's rather than the kind's: {@link #parts()} cannot answer it,
     * because how many there are and what each one is are facts about this declaration.</p>
     */
    @Override
    public List<Part> parts(CgUiDrawable value) {
        List<CgUiDrawable> layers = layersOf(value);
        if (layers == null) return parts();

        List<Part> parts = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            DrawableKinds.Kind kind = kindOfLayer(layers.get(i));
            parts.add(new Part(LAYER + i, kind == null ? "Appearance" : kind.label(), ""));
        }
        return parts;
    }

    /**
     * True when every piece of {@code value} can be described. @see StyleParts#divides
     *
     * <p>For a stack that means every LAYER: one layer nothing could write would offer the rest and drop
     * it in silence, and ticking every box would then produce a different background from the one that
     * was copied.</p>
     */
    @Override
    public boolean divides(CgUiDrawable value) {
        List<CgUiDrawable> layers = layersOf(value);
        if (layers == null) return kindOf(value) != null;
        for (CgUiDrawable layer : layers) {
            if (TextureValue.sourceOf(layer) == null) return false;
        }
        return true;
    }

    @Nullable
    @Override
    public <T> T encodePart(DynamicOps<T> ops, CgUiDrawable value, String partId) {
        List<CgUiDrawable> layers = layersOf(value);
        if (layers != null) {
            int index = indexOf(partId);
            if (index < 0 || index >= layers.size()) return null;
            return ops.createString(TextureValue.sourceOf(layers.get(index)));
        }
        // THE WHOLE VALUE, for the one part it is. There is nothing inside a gradient to tick on its
        // own, so this groups rather than divides -- and answering null for the other eight is what
        // puts it in one section instead of nine.
        return partId.equals(kindOf(value)) ? ops.createString(TextureValue.sourceOf(value)) : null;
    }

    @Override
    public <T> CgUiDrawable mergePart(DynamicOps<T> ops, CgUiDrawable base, String partId, T encoded) {
        String text = ops.getStringValue(encoded);
        CgUiDrawable parsed = new TextureValue(text).compute();
        if (parsed == null) return base;

        int index = indexOf(partId);
        if (index < 0) {
            // REPLACES, because the part IS the value: a background is one drawable, and pasting a
            // gradient onto an element that had a sprite means it has a gradient now.
            return parsed;
        }

        // ONE LAYER INTO THE TARGET'S OWN STACK, at the depth it had in the source. A shorter target
        // grows, padded with `none`: pasting the third layer of one element onto an element holding one
        // must not silently become its first.
        List<CgUiDrawable> into = new ArrayList<>();
        List<CgUiDrawable> existing = layersOf(base);
        if (existing != null) {
            into.addAll(existing);
        } else if (base != CgUiDrawable.EMPTY) {
            into.add(base);
        }
        while (into.size() <= index) into.add(CgUiDrawable.EMPTY);
        into.set(index, parsed);

        return into.size() == 1 ? into.get(0) : new CgUiLayers(into);
    }

    /** The layers of a stack, or null when {@code value} is a single drawable. */
    @Nullable
    private static List<CgUiDrawable> layersOf(CgUiDrawable value) {
        return value instanceof CgUiLayers stack ? stack.layers() : null;
    }

    /** {@code 2} for {@code layer-2}, or {@code -1} when the id names a kind rather than a layer. */
    private static int indexOf(String partId) {
        if (!partId.startsWith(LAYER)) return -1;
        try {
            return Integer.parseInt(partId.substring(LAYER.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Which kind {@code value} is, or null when nothing parsed it or its function is unknown. */
    @Nullable
    private static String kindOf(CgUiDrawable value) {
        if (value == CgUiDrawable.EMPTY) return null;   // `none` is an absence, not a kind
        DrawableKinds.Kind kind = kindOfLayer(value);
        return kind == null ? null : kind.id();
    }

    @Nullable
    private static DrawableKinds.Kind kindOfLayer(CgUiDrawable value) {
        return DrawableKinds.matching(TextureValue.sourceOf(value));
    }
}
