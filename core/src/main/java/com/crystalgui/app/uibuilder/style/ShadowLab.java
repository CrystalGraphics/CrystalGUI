package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * The shadow lab: the stack of shadows, and the picked one's offset, blur, spread, color and whether it is inset.
 *
 * <pre>{@code
 * ShadowLab.open(chip, StylePropertyRegistry.TEXT_SHADOW, css, node);
 * }</pre>
 *
 * <p>{@code text-shadow} is a comma list, first on top, and the stack is that list — so reordering is an edit and
 * removing one is one row gone. The list comes first: it chooses which shadow the rows under it edit.</p>
 */
public final class ShadowLab {

    static final String DEFAULT = "0 1px 2px #000000";

    /** How far a fitted shadow may reach from the mark, in px. Half the chip, so the far side survives. */
    private static final float SAMPLE_REACH = 7f;

    /** The properties the specimen copies from the element, so the shadow is cast by the text it will be cast by. */
    private static final List<StyleProperty<?>> TYPE = List.of(StylePropertyRegistry.FONT_FAMILY,
            StylePropertyRegistry.FONT_WEIGHT, StylePropertyRegistry.FONT_STYLE, StylePropertyRegistry.COLOR);

    private ShadowLab() {
    }

    /**
     * One shadow as its parts — CSS Text Decoration 4's: two offsets, a blur, a spread, a color, and {@code inset}.
     */
    record Shadow(float x, float y, float blur, float spread, int argb, boolean inset) {

        /**
         * CSS writes {@code 0 1px 2px #000} and this engine's writer {@code #000 0px 1px 2px}, so the color is
         * whichever term parses as one, {@code inset} is the keyword wherever it sits, and the lengths are the rest
         * in order.
         */
        static Shadow parse(String layer) {
            List<String> lengths = new ArrayList<>();
            boolean inset = false;
            for (String term : CssValues.terms(layer)) {
                if (term.toLowerCase(Locale.ROOT).equals("inset")) {
                    inset = true;
                } else if (ColorValue.parseCssColor(term) == null) {
                    lengths.add(term);
                }
            }
            return new Shadow(CssValues.number(lengths, 0, 0f), CssValues.number(lengths, 1, 0f),
                    CssValues.number(lengths, 2, 0f), CssValues.number(lengths, 3, 0f), colorOf(layer), inset);
        }

        double[] offset() {
            return new double[] {x, y};
        }

        Shadow withOffset(double[] at) {
            return new Shadow((float) at[0], (float) at[1], blur, spread, argb, inset);
        }

        Shadow withBlur(double next) {
            return new Shadow(x, y, tenth(next), spread, argb, inset);
        }

        Shadow withSpread(double next) {
            return new Shadow(x, y, blur, tenth(next), argb, inset);
        }

        Shadow withArgb(int next) {
            return new Shadow(x, y, blur, spread, next, inset);
        }

        Shadow withInset(boolean next) {
            return new Shadow(x, y, blur, spread, argb, next);
        }

        /** How far the shadow reaches past the glyph, in px: its offset, its blur and its spread together. */
        float reach() {
            return Math.max(Math.abs(x), Math.abs(y)) + blur + spread;
        }

        @Override
        public String toString() {
            // A SPREAD ONLY WHEN THERE IS ONE, so a plain shadow keeps Level 3's spelling.
            String lengths = CssValues.px(x) + " " + CssValues.px(y) + " " + CssValues.px(blur)
                    + (spread != 0f ? " " + CssValues.px(spread) : "");
            return lengths + " " + CssValues.color(argb) + (inset ? " inset" : "");
        }

        private static float tenth(double value) {
            return (float) (Math.round(value * 10d) / 10d);
        }
    }

    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        open(anchor, property, css, null);
    }

    /** @param node the element the shadow is on, whose face and color the specimen takes, or null for the lab's own */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, @Nullable UIElement node) {
        StyleLab lab = StyleLab.over(anchor, "Shadow");
        // TEXT, because a shadow is cast by glyphs; the property inherits, so the specimen's reaches the sample.
        UIText specimen = new UIText("Ag");
        lab.specimen(specimen).preview(property, css);
        if (node != null) {
            // THE ELEMENT'S OWN TYPE: a shadow under a thin white face is not the shadow under a bold red one.
            for (StyleProperty<?> type : TYPE) LiveEdits.follow(specimen, type, TypographyLab.computed(node, type));
        }
        lab.contrastWith(() -> specimen.getStyle().computed().get(StylePropertyRegistry.COLOR));

        Property<Integer> selected = Property.of(0);
        Property<List<String>> layers = css.map(ShadowLab::layersOf, CssValues::join);
        Property<Shadow> shadow = Property.derived(
                () -> Shadow.parse(at(layers.get(), selected.get())),
                next -> layers.set(replaced(layers.get(), selected.get(), next.toString())))
                .editedIn(css.history());

        // THE STACK FIRST: it chooses which shadow the rows under it edit.
        LayerStack stack = new LayerStack("lab.layers", property, selected).titled("Shadows");
        // THE SHADOW ITSELF, on an "Ag" of its own and scaled to the patch: a 16px blur is bigger than the row.
        stack.sample(patch -> patch.append(new UIText("Ag").addClass(LayerStack.SAMPLE_TEXT_CLASS)),
                (patch, layer) -> LiveEdits.setInline(patch, property, fittedLayer(layer)));
        stack.adding("+ Add", () -> stack.add(DEFAULT));
        stack.bind(layers);
        lab.content().append(stack);

        lab.form().control("lab.offset", "Offset", new OffsetPad("lab.offset")
                .bind(shadow.map(Shadow::offset, at -> shadow.get().withOffset(at))));
        lab.form().prop(ConfigDescriptor.number("lab.blur", "Blur").range(0f, 40f).unit("px").decimals(1),
                shadow.map(s -> (double) s.blur(), blur -> shadow.get().withBlur(blur)));
        lab.form().prop(ConfigDescriptor.number("lab.spread", "Spread").range(0f, 20f).unit("px").decimals(1),
                shadow.map(s -> (double) s.spread(), spread -> shadow.get().withSpread(spread)));
        lab.form().prop(ConfigDescriptor.color("lab.color", "Color"),
                shadow.map(Shadow::argb, argb -> shadow.get().withArgb(argb)));
        lab.form().prop(ConfigDescriptor.bool("lab.inset", "Inset"),
                shadow.map(Shadow::inset, inset -> shadow.get().withInset(Boolean.TRUE.equals(inset))));

        // WHAT THE READOUT CANNOT SAY: how far past the glyph this shadow reaches, which is what clips.
        lab.caption(Property.derived(() -> {
            Shadow now = shadow.get();
            return (now.inset() ? "inset — reaches " : "reaches ") + CssValues.write(Math.round(now.reach() * 10d) / 10d)
                    + "px past the glyph";
        }));
        lab.readout(property.name, css);
        lab.open();
    }

    /** The declaration's layers, or one default shadow to start from when it has none. */
    private static List<String> layersOf(String css) {
        List<String> layers = CssValues.layers(css);
        return layers.isEmpty() ? List.of(DEFAULT) : layers;
    }

    private static String at(List<String> layers, Integer index) {
        return layers.get(Math.max(0, Math.min(index == null ? 0 : index, layers.size() - 1)));
    }

    private static List<String> replaced(List<String> layers, Integer index, String layer) {
        List<String> next = new ArrayList<>(layers);
        next.set(Math.max(0, Math.min(index == null ? 0 : index, next.size() - 1)), layer);
        return next;
    }

    /**
     * One shadow, scaled to fit a chip: the offsets, the blur and the spread shrink together, so the direction, the
     * softness and the color all survive a 28x16 box. <b>Display only</b> — the real value is printed beside it.
     */
    static String fittedLayer(String layer) {
        Shadow shadow = Shadow.parse(layer);
        float reach = shadow.reach();
        float scale = reach > SAMPLE_REACH ? SAMPLE_REACH / reach : 1f;
        return new Shadow(shadow.x() * scale, shadow.y() * scale, shadow.blur() * scale, shadow.spread() * scale,
                shadow.argb(), shadow.inset()).toString();
    }

    /** A whole {@code text-shadow}, every layer fitted to a chip and the stack kept. @see #fittedLayer */
    static String fitted(String css) {
        List<String> out = new ArrayList<>();
        for (String layer : CssValues.layers(css)) out.add(fittedLayer(layer));
        return CssValues.join(out);
    }

    /**
     * A layer's color, wherever it sits among the lengths, or opaque black.
     *
     * <p><b>{@code parseCssColor}, never {@code parseColor}</b>: a color property also takes a decimal ARGB
     * literal, so {@code parseColor("0")} is transparent black — and the {@code 0} in {@code 0 1px 2px #000}
     * is an offset.</p>
     */
    static int colorOf(String layer) {
        for (String term : CssValues.terms(layer)) {
            Integer color = ColorValue.parseCssColor(term);
            if (color != null) return color;
        }
        return 0xFF000000;
    }
}
