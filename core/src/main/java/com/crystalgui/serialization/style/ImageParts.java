package com.crystalgui.serialization.style;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgui.render.image.CgUiImage;
import com.crystalgui.style.property.visual.image.ImageKinds;
import com.crystalgui.style.property.visual.image.ImageValue;

/**
 * An image property's value for the builder's copy and paste, filed under the CSS function that writes it (gradient,
 * icon, sprite) and a layer at a time when it is a stack.
 *
 * <pre>{@code
 * // background: grid(...), shape(chevron-down), linear-gradient(...)  ->  three rows: Grid, Shape, Gradient
 * List<Part> rows = ImageParts.INSTANCE.parts(style.get(BACKGROUND_IMAGE));
 * T layer = ImageParts.INSTANCE.encodePart(ops, value, "layer-2");
 * CgUiImage pasted = ImageParts.INSTANCE.mergePart(ops, target, "layer-2", layer);   // into the target's third layer
 * }</pre>
 *
 * <ul>
 *   <li>A single image is one part, under its kind; a stack, one part per layer.</li>
 *   <li>An image with no CSS spelling (a painter or a paint set from Java) does not divide: the builder copies the
 *       property whole, under Appearance.</li>
 *   <li>Pasting one layer onto a shorter stack pads it with {@code none}, so the layer keeps its depth.</li>
 * </ul>
 */
public final class ImageParts implements StyleParts<CgUiImage> {

    public static final ImageParts INSTANCE = new ImageParts();

    private static final String LAYER = "layer-";

    private ImageParts() {
    }

    @Override
    public List<Part> parts() {
        List<Part> parts = new ArrayList<>();
        // No label of its own: the section names the kind, the row is named after the property
        for (ImageKinds.Kind kind : ImageKinds.all()) parts.add(new Part(kind.id(), kind.label(), ""));
        return parts;
    }

    @Override
    public List<Part> parts(CgUiImage value) {
        if (!(value instanceof CgUiImage.Layers stack)) return parts();
        List<Part> parts = new ArrayList<>(stack.layers().size());
        for (int i = 0; i < stack.layers().size(); i++) {
            ImageKinds.Kind kind = ImageKinds.matching(css(stack.layers().get(i)));
            parts.add(new Part(LAYER + i, kind == null ? "Appearance" : kind.label(), ""));
        }
        return parts;
    }

    @Override
    public boolean divides(CgUiImage value) {
        if (!(value instanceof CgUiImage.Layers stack)) return kindOf(value) != null;
        for (CgUiImage layer : stack.layers()) {
            if (css(layer) == null) return false;
        }
        return true;
    }

    @Nullable
    @Override
    public <T> T encodePart(CgDynamicOps<T> ops, CgUiImage value, String partId) {
        if (value instanceof CgUiImage.Layers stack) {
            int index = indexOf(partId);
            if (index < 0 || index >= stack.layers().size()) return null;
            String css = css(stack.layers().get(index));
            return css == null ? null : ops.createString(css);
        }
        // The whole value, for the one part it is; null for every other kind keeps it in one section
        return partId.equals(kindOf(value)) ? ops.createString(value.css()) : null;
    }

    @Override
    public <T> CgUiImage mergePart(CgDynamicOps<T> ops, CgUiImage base, String partId, T encoded) {
        CgUiImage parsed = ImageValue.parse(ops.getStringValue(encoded));
        if (parsed == null) return base;
        int index = indexOf(partId);
        // A kind replaces: pasting a gradient onto an element that had a sprite means it has a gradient now
        if (index < 0) return parsed;

        List<CgUiImage> into = new ArrayList<>();
        if (base instanceof CgUiImage.Layers stack) into.addAll(stack.layers());
        else if (base != CgUiImage.NONE) into.add(base);
        while (into.size() <= index) into.add(CgUiImage.NONE);
        into.set(index, parsed);
        return CgUiImage.layers(into);
    }

    /** The CSS the image writes, or null for one built in Java with no spelling. */
    @Nullable
    private static String css(CgUiImage image) {
        try {
            return image.css();
        } catch (IllegalStateException unwritable) {
            return null;
        }
    }

    @Nullable
    private static String kindOf(CgUiImage value) {
        if (value == CgUiImage.NONE) return null;   // `none` is an absence, not a kind
        ImageKinds.Kind kind = ImageKinds.matching(css(value));
        return kind == null ? null : kind.id();
    }

    private static int indexOf(String partId) {
        if (!partId.startsWith(LAYER)) return -1;
        try {
            return Integer.parseInt(partId.substring(LAYER.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
