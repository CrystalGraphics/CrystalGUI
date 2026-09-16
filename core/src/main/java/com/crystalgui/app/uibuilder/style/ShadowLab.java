package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The shadow lab: the stack of shadows, and the picked one's offset on a pad, its blur and its colour.
 *
 * <pre>{@code
 * ShadowLab.open(chip, StylePropertyRegistry.TEXT_SHADOW, css);
 * }</pre>
 *
 * <p>{@code text-shadow} is a comma list, first on top, and the stack is that list — so reordering is an edit
 * and removing one is one row gone.</p>
 */
public final class ShadowLab {

    static final String DEFAULT = "0 1px 2px #000000";

    /** How far a fitted shadow may reach from the mark, in px. Half the chip, so the far side survives. */
    private static final float SAMPLE_REACH = 7f;

    private ShadowLab() {
    }

    /** One shadow as its parts, read with the colour wherever it sits. */
    record Shadow(float x, float y, float blur, int argb) {

        /**
         * CSS writes {@code 0 1px 2px #000} and this engine's writer {@code #000 0px 1px 2px}, so the colour is
         * whichever term parses as one and the lengths are the rest in order.
         */
        static Shadow parse(String layer) {
            List<String> lengths = new ArrayList<>();
            for (String term : CssValues.terms(layer)) {
                if (ColorValue.parseCssColor(term) == null) lengths.add(term);
            }
            return new Shadow(CssValues.number(lengths, 0, 0f), CssValues.number(lengths, 1, 0f),
                    CssValues.number(lengths, 2, 0f), colourOf(layer));
        }

        double[] offset() {
            return new double[] {x, y};
        }

        Shadow withOffset(double[] at) {
            return new Shadow((float) at[0], (float) at[1], blur, argb);
        }

        Shadow withBlur(double next) {
            return new Shadow(x, y, (float) CssValues.dragged(next), argb);
        }

        Shadow withArgb(int next) {
            return new Shadow(x, y, blur, next);
        }

        @Override
        public String toString() {
            return CssValues.px(x) + " " + CssValues.px(y) + " " + CssValues.px(blur) + " " + CssValues.color(argb);
        }
    }

    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        StyleLab lab = StyleLab.over(anchor, "Shadow");
        // TEXT, because a shadow is cast by glyphs; the property inherits, so the specimen's reaches the sample.
        lab.specimen(new UIText("Ag")).preview(property, css);

        Property<Integer> selected = Property.of(0);
        Property<List<String>> layers = css.map(ShadowLab::layersOf, CssValues::join);
        Property<Shadow> shadow = Property.derived(
                () -> Shadow.parse(at(layers.get(), selected.get())),
                next -> layers.set(replaced(layers.get(), selected.get(), next.toString())))
                .editedIn(css.history());

        lab.content().append(new OffsetPad("lab.offset")
                .bind(shadow.map(Shadow::offset, at -> shadow.get().withOffset(at))));
        lab.form().prop(ConfigDescriptor.number("lab.blur", "Blur").range(0f, 40f).unit("px").decimals(2),
                shadow.map(s -> (double) s.blur(), blur -> shadow.get().withBlur(blur)));
        lab.form().prop(ConfigDescriptor.color("lab.colour", "Colour"),
                shadow.map(Shadow::argb, argb -> shadow.get().withArgb(argb)));

        LayerStack stack = new LayerStack("lab.layers", property, selected);
        // THE SHADOW ITSELF, on an "Ag" of its own and scaled to the patch: a 16px blur is bigger than the row.
        stack.sample(patch -> patch.append(new UIText("Ag").addClass(LayerStack.SAMPLE_TEXT_CLASS)),
                (patch, layer) -> LiveEdits.setInline(patch, property, fittedLayer(layer)));
        stack.bind(layers);

        Button add = new Button("+ shadow");
        add.addClass(StyleLab.KEYWORD_CLASS);
        add.attachListener(() -> stack.add(DEFAULT));
        lab.content().append(add);
        lab.content().append(stack);

        lab.caption(Property.derived(() -> {
            Shadow now = shadow.get();
            int count = layers.get().size();
            return count + (count == 1 ? " shadow" : " shadows") + " — offset " + CssValues.px(now.x()) + ", "
                    + CssValues.px(now.y()) + ", blur " + CssValues.px(now.blur());
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
     * One shadow, scaled to fit a chip: the offsets and the blur shrink together, so the direction, the
     * softness and the colour all survive a 28x16 box. <b>Display only</b> — the real value is printed beside it.
     */
    static String fittedLayer(String layer) {
        Shadow shadow = Shadow.parse(layer);
        float reach = Math.max(Math.abs(shadow.x()), Math.abs(shadow.y())) + shadow.blur();
        float scale = reach > SAMPLE_REACH ? SAMPLE_REACH / reach : 1f;
        return new Shadow(shadow.x() * scale, shadow.y() * scale, shadow.blur() * scale, shadow.argb()).toString();
    }

    /** A whole {@code text-shadow}, every layer fitted to a chip and the stack kept. @see #fittedLayer */
    static String fitted(String css) {
        List<String> out = new ArrayList<>();
        for (String layer : CssValues.layers(css)) out.add(fittedLayer(layer));
        return CssValues.join(out);
    }

    /**
     * A layer's colour, wherever it sits among the lengths, or opaque black.
     *
     * <p><b>{@code parseCssColor}, never {@code parseColor}</b>: a colour property also takes a decimal ARGB
     * literal, so {@code parseColor("0")} is transparent black — and the {@code 0} in {@code 0 1px 2px #000}
     * is an offset.</p>
     */
    static int colourOf(String layer) {
        for (String term : CssValues.terms(layer)) {
            Integer colour = ColorValue.parseCssColor(term);
            if (colour != null) return colour;
        }
        return 0xFF000000;
    }
}
