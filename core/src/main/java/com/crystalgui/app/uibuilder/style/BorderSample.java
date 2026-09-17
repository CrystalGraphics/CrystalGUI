package com.crystalgui.app.uibuilder.style;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;

/**
 * The element's edge in small, in the swatch of a Border row: its shape, its border in its own colors, and its outline at
 * its offset -- the part the row is about at full strength and the rest dimmed.
 *
 * <pre>{@code
 * BorderSample.follow(chip, node, BorderSample.Part.OUTLINE);   // the outline row's swatch
 * }</pre>
 *
 * <p><b>Drawn from the element's computed style</b>, not the row's text, so every row agrees with the others and with
 * the element whichever declaration changed. <b>Lengths are exaggerated but ordered</b>: none, a hairline, a few pixels
 * and more each draw differently, where at their own size in a 28x16 swatch a 1px border and a 3px one were the same
 * picture.</p>
 */
final class BorderSample {

    /** Which part of the edge a row is about. */
    enum Part {
        RADIUS, BORDER, OUTLINE, OFFSET
    }

    /** The element in small, inside the swatch with room round it for the outline. */
    static final String BOX_CLASS = "__border-sample-box__";

    /** How much of its color a part the row is not about keeps. */
    private static final float DIM = 0.3f;

    /** What a missing or invisible color is drawn in: the default border color is opaque black, lost on the band. */
    private static final int NEUTRAL = 0xFF9DA0A8;

    private BorderSample() {
    }

    /** Draws {@code node}'s edge in {@code chip}'s swatch with {@code focus} lit, and keeps it drawn as the element changes. */
    static void follow(StyleChip chip, @Nullable UIElement node, Part focus) {
        UIElement box = new UIElement().addClass(BOX_CLASS);
        chip.swatch().append(box);
        PropertyWatch.follow(chip, Property.derived(() -> Edge.signature(node)), signature -> paint(box, node, focus));
    }

    /**
     * What an element draws round its edge, read from its computed style and its box: the one reading both a Border row's
     * sample and the border lab's miniature draw, so the two cannot disagree.
     *
     * <pre>{@code
     * BorderSample.Edge edge = BorderSample.Edge.of(node);   // null before the element has a box
     * }</pre>
     *
     * @param borders    top, right, bottom, left, in px, as laid out
     * @param radii      {@link StyleFields#RADIUS_LONGHANDS} order, in px, a percentage resolved against the box
     * @param topColor   the top edge's color, {@code borderColor} where no override is set
     * @param offsets    top, right, bottom, left, in px
     * @param borderSet  whether the element sets its border color, rather than taking the engine's opaque black
     * @param outlineSet whether the element sets its outline color
     */
    record Edge(float width, float height, float[] borders, float[] radii, int borderColor, int topColor,
                int bottomColor, float outlineWidth, int outlineColor, float[] offsets, boolean borderSet,
                boolean outlineSet) {

        @Nullable
        static Edge of(@Nullable UIElement node) {
            if (node == null || node.box() == null) return null;
            ComputedStyle style = node.getStyle().computed();
            float width = node.box().width(), height = node.box().height();
            float[] radii = new float[8];
            StyleFields.Group corners = StyleFields.group(StyleFields.BORDER_RADIUS);
            for (int i = 0; i < 8; i++) {
                StyleProperty<?> longhand = StyleFields.propertyOf(corners.longhands().get(i));
                Object value = style.get(longhand);
                String written = value == null ? "" : StyleFields.cast(longhand).write(value);
                float px = CssValues.number(written, 0f);
                radii[i] = written.endsWith("%") ? px / 100f * (i % 2 == 0 ? width : height) : px;
            }
            float[] offsets = new float[4];
            StyleFields.Group sides = StyleFields.group(StyleFields.OUTLINE_OFFSET);
            for (int i = 0; i < 4; i++) offsets[i] = length(style, StyleFields.propertyOf(sides.longhands().get(i)));
            int border = style.get(StylePropertyRegistry.BORDER_COLOR);
            return new Edge(width, height,
                    new float[] {node.box().border().top, node.box().border().right, node.box().border().bottom,
                            node.box().border().left},
                    radii, border, edge(style, StylePropertyRegistry.BORDER_TOP_COLOR, border),
                    edge(style, StylePropertyRegistry.BORDER_BOTTOM_COLOR, border),
                    length(style, StylePropertyRegistry.OUTLINE_WIDTH), style.get(StylePropertyRegistry.OUTLINE_COLOR),
                    offsets, style.isSet(StylePropertyRegistry.BORDER_COLOR),
                    style.isSet(StylePropertyRegistry.OUTLINE_COLOR));
        }

        /** Whether any side has a border. */
        boolean bordered() {
            return borders[0] > 0f || borders[1] > 0f || borders[2] > 0f || borders[3] > 0f;
        }

        /**
         * Everything an edge is drawn from, as one number: a follower repaints only when it moves, and is asked every
         * frame by every Border row -- so it reads the style rather than building an {@link Edge} to throw away. A hash,
         * so two edges could in principle collide and leave a sample stale; it draws a swatch, and nothing reads it back.
         */
        static int signature(@Nullable UIElement node) {
            if (node == null || node.box() == null) return 0;
            ComputedStyle style = node.getStyle().computed();
            int hash = Float.hashCode(node.box().width()) * 31 + Float.hashCode(node.box().height());
            hash = hash * 31 + Arrays.hashCode(new float[] {node.box().border().top, node.box().border().right,
                    node.box().border().bottom, node.box().border().left});
            for (StyleFields.Group group : List.of(StyleFields.group(StyleFields.BORDER_RADIUS),
                    StyleFields.group(StyleFields.OUTLINE_OFFSET))) {
                for (String longhand : group.longhands()) {
                    hash = hash * 31 + Objects.hashCode(style.get(StyleFields.propertyOf(longhand)));
                }
            }
            for (StyleProperty<?> property : WATCHED) hash = hash * 31 + Objects.hashCode(style.get(property));
            return hash;
        }

        /** What the sample draws beyond the sides and the radii, each asked by {@link #signature}. */
        private static final StyleProperty<?>[] WATCHED = {
                StylePropertyRegistry.BORDER_COLOR, StylePropertyRegistry.BORDER_TOP_COLOR,
                StylePropertyRegistry.BORDER_BOTTOM_COLOR, StylePropertyRegistry.OUTLINE_WIDTH,
                StylePropertyRegistry.OUTLINE_COLOR};

        private static float length(ComputedStyle style, StyleProperty<?> property) {
            Object value = style.get(property);
            return value == null ? 0f : CssValues.number(StyleFields.cast(property).write(value), 0f);
        }

        /** A top or bottom override, as the engine reads it: transparent is the border color. */
        private static int edge(ComputedStyle style, StyleProperty<Integer> property, int fallback) {
            Integer argb = style.get(property);
            return argb == null || (argb >>> 24) == 0 ? fallback : argb;
        }
    }

    private static void paint(UIElement box, @Nullable UIElement node, Part focus) {
        Edge edge = Edge.of(node);
        if (edge == null) return;

        // THE SHAPE, at the sample's scale: the radii shrink with the box, so a pill stays a pill.
        StyleFields.Group corners = StyleFields.group(StyleFields.BORDER_RADIUS);
        double scale = edge.width() <= 0f || edge.height() <= 0f ? 1d
                : Math.min(SAMPLE_WIDTH / edge.width(), SAMPLE_HEIGHT / edge.height());
        for (int i = 0; i < 8; i++) {
            LiveEdits.setInline(box, StyleFields.propertyOf(corners.longhands().get(i)),
                    CssValues.px(Math.round(edge.radii()[i] * scale * 10d) / 10d));
        }
        box.toggleClass(LIT_CLASS, focus == Part.RADIUS);

        // THE BORDER, each side at its step, in the element's own colors. The shape's row draws everything lit: the
        // corners are what the border and outline follow.
        StyleFields.Group sides = StyleFields.group(StyleFields.BORDER_WIDTH);
        for (int i = 0; i < 4; i++) {
            LiveEdits.setInline(box, StyleFields.propertyOf(sides.longhands().get(i)), CssValues.px(step(edge.borders()[i])));
        }
        boolean borderLit = focus != Part.OUTLINE && focus != Part.OFFSET;
        int border = edge.borderSet() ? visible(edge.borderColor()) : NEUTRAL;
        LiveEdits.setInline(box, StylePropertyRegistry.BORDER_COLOR, CssValues.color(dim(border, borderLit)));
        LiveEdits.setInline(box, StylePropertyRegistry.BORDER_TOP_COLOR,
                CssValues.color(dim(edge.topColor() == edge.borderColor() ? border : visible(edge.topColor()), borderLit)));
        LiveEdits.setInline(box, StylePropertyRegistry.BORDER_BOTTOM_COLOR,
                CssValues.color(dim(edge.bottomColor() == edge.borderColor() ? border : visible(edge.bottomColor()), borderLit)));

        // THE OUTLINE, at its step and its offset's.
        LiveEdits.setInline(box, StylePropertyRegistry.OUTLINE_WIDTH, CssValues.px(step(edge.outlineWidth())));
        int outline = edge.outlineSet() ? visible(edge.outlineColor()) : NEUTRAL;
        LiveEdits.setInline(box, StylePropertyRegistry.OUTLINE_COLOR, CssValues.color(dim(outline, focus != Part.BORDER)));
        StyleFields.Group offsets = StyleFields.group(StyleFields.OUTLINE_OFFSET);
        for (int i = 0; i < 4; i++) {
            float px = edge.offsets()[i];
            LiveEdits.setInline(box, StyleFields.propertyOf(offsets.longhands().get(i)),
                    CssValues.px(Math.signum(px) * Math.min(MAX_OFFSET, step(Math.abs(px)))));
        }
    }

    /** The sample's box, in px: the swatch less room for an outline round it. @see #BOX_CLASS */
    private static final float SAMPLE_WIDTH = 18f;
    private static final float SAMPLE_HEIGHT = 8f;

    /** How far an offset is drawn either way. */
    private static final float MAX_OFFSET = 2f;

    /** On the box when the shape is the row's subject: the sheet lights its fill. */
    static final String LIT_CLASS = "__lit__";

    /** A length as the sample draws it: none, a hairline, two pixels for a few, three for more. */
    static float step(float px) {
        if (px <= 0f) return 0f;
        if (px <= 1f) return 1f;
        return px <= 3f ? 2f : 3f;
    }

    /** {@code argb}, or {@link #NEUTRAL} where it cannot be seen. */
    private static int visible(int argb) {
        return (argb >>> 24) == 0 ? NEUTRAL : argb;
    }

    private static int dim(int argb, boolean lit) {
        if (lit) return argb;
        int alpha = Math.round(((argb >>> 24) & 0xFF) * DIM);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
