package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The shadow lab: a stack of shadows, the selected one dragged on a pad.
 *
 * <pre>{@code
 * ShadowLab.open(chip, StylePropertyRegistry.TEXT_SHADOW, css);
 * }</pre>
 *
 * <p>A shadow is four numbers and a colour, and three of the four are a direction and a softness — things
 * the eye judges and a field of numbers does not. The pad is the offset: drag the dot and the shadow moves
 * the way the dot moved. The specimens above it carry the whole stack on dark and on light, because a
 * shadow that reads on one ground routinely vanishes on the other.</p>
 *
 * <p>{@code text-shadow} is a comma list, first on top, and the stack is that list — so reordering is an
 * edit and removing one is one row gone.</p>
 */
public final class ShadowLab {

    public static final String PAD_CLASS = "__offset-pad__";
    public static final String DOT_CLASS = "__offset-dot__";

    static final String DEFAULT = "0 1px 2px #000000";

    private final Property<String> css;
    private final StyleProperty<?> property;
    private final StyleLab lab;
    private final LayerStack stack;

    private final UIElement pad = new UIElement();
    private final UIElement dot = new UIElement();

    private final List<String> layers = new ArrayList<>();
    private int selected;

    /** The selected shadow, as its parts. */
    private float x;
    private float y;
    private float blur;
    private int argb = 0xFF000000;

    private ShadowLab(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        this.css = css;
        this.property = property;
        this.lab = StyleLab.over(anchor, "Shadow", property, css);
        this.stack = new LayerStack(property);
        layers.addAll(CssValues.layers(css.get()));
        if (layers.isEmpty()) layers.add(DEFAULT);
        readSelected();
    }

    public static ShadowLab open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        ShadowLab shadow = new ShadowLab(anchor, property, css);
        shadow.build();
        shadow.lab.open();
        return shadow;
    }

    private void build() {
        // TEXT, because `text-shadow` is what this lab edits and an empty box has nothing to cast one.
        // The property inherits, so the shadow the lab applies to each specimen reaches the sample in it.
        lab.specimen(() -> {
            UIText sample = new UIText("Ag");
            sample.addClass(StyleLab.SAMPLE_CLASS);
            return sample;
        });

        pad.addClass(PAD_CLASS);
        dot.addClass(DOT_CLASS);
        pad.append(dot);
        float[] start = new float[2];
        StyleGizmos.drag(pad, (dx, dy) -> {
            x = start[0] + dx;
            y = start[1] + dy;
            replaceSelected();
        }, this::commit);
        pad.onMouseDown.attachListener((element, event) -> {
            start[0] = x;
            start[1] = y;
        }, false, true);
        lab.content().append(pad);

        lab.form().prop(ConfigDescriptor.number("lab.blur", "Blur").range(0f, 40f).unit("px").decimals(2),
                Property.derived(() -> (double) blur, value -> {
                    blur = (float) CssValues.dragged(value);
                    replaceSelected();
                    commit();
                }));
        lab.form().prop(ConfigDescriptor.color("lab.colour", "Colour"),
                Property.derived(() -> argb, colour -> {
                    argb = colour;
                    replaceSelected();
                    commit();
                }));

        Button add = new Button("+ shadow");
        add.addClass("__lab-keyword__");
        add.attachListener(() -> {
            layers.add(0, DEFAULT);
            selected = 0;
            readSelected();
            commit();
        });
        lab.content().append(add);

        // THE SHADOW ITSELF, on the same "Ag" the inspector's own row draws it on. A shadow is a thing
        // text does, so the patch needs some; `text-shadow` inherits, so applying the layer to the patch
        // reaches the glyph inside it. Clipped by the patch, as the chip's is -- at this size a 16px blur
        // is a smudge either way, and left unclipped it paints across the rows above and below.
        stack.sample((patch, layer) -> {
            UIText glyph = new UIText("Ag");
            glyph.addClass(LayerStack.SAMPLE_TEXT_CLASS);
            patch.append(glyph);
            LiveEdits.setInline(patch, cast(property), fittedLayer(layer));
        });
        stack.onSelect(index -> {
            selected = index;
            readSelected();
            refresh();
        });
        stack.onChange(values -> {
            layers.clear();
            layers.addAll(values);
            selected = stack.selected();
            readSelected();
            commit();
        });
        lab.content().append(stack);

        lab.caption(() -> layers.size() + (layers.size() == 1 ? " shadow" : " shadows")
                + " — offset " + CssValues.px(x) + ", " + CssValues.px(y) + ", blur " + CssValues.px(blur));
        refresh();
    }

    // ── The value ───────────────────────────────────────────────────────────

    /**
     * The selected layer's parts, <b>wherever they sit</b>.
     *
     * <p>CSS writes {@code 0 1px 2px #000} and this engine's own writer answers
     * {@code #000 0px 1px 2px} — colour first. Both are valid input, so the colour is whichever term
     * parses as one and the lengths are the rest in order. Reading by position instead took the colour for
     * an offset, which is how a lab opened on a value it had itself written and showed different numbers.</p>
     */
    private void readSelected() {
        if (selected < 0 || selected >= layers.size()) return;
        String layer = layers.get(selected);
        argb = colourOf(layer);
        List<String> lengths = new ArrayList<>();
        for (String term : CssValues.terms(layer)) {
            if (ColorValue.parseCssColor(term) == null) lengths.add(term);
        }
        x = CssValues.number(lengths, 0, 0f);
        y = CssValues.number(lengths, 1, 0f);
        blur = CssValues.number(lengths, 2, 0f);
    }

    /**
     * One shadow, scaled to fit a chip.
     *
     * <p>A swatch is 28x16 and a shadow is routinely larger than that in every direction, so at its own
     * scale what reaches the box is a corner of a blur: the direction, the softness and most of the colour
     * fall outside it. Scaling the offsets and the blur together keeps all three, which is what a reader
     * is actually asking a 28x16 picture. <b>Display only</b> — the exact value is printed beside it, so
     * the picture is the shape and the text is the truth.</p>
     *
     * <p>ONE layer. {@link #fitted} is the whole declaration, and handing this the comma list instead
     * read it as a single shadow: a five-shadow stack drew as its first colour alone.</p>
     */
    static String fittedLayer(String layer) {
        List<String> lengths = new ArrayList<>();
        for (String term : CssValues.terms(layer)) {
            if (ColorValue.parseCssColor(term) == null) lengths.add(term);
        }
        float x = CssValues.number(lengths, 0, 0f);
        float y = CssValues.number(lengths, 1, 0f);
        float blur = CssValues.number(lengths, 2, 0f);
        float reach = Math.max(Math.abs(x), Math.abs(y)) + blur;
        float scale = reach > SAMPLE_REACH ? SAMPLE_REACH / reach : 1f;
        return CssValues.px(x * scale) + " " + CssValues.px(y * scale) + " " + CssValues.px(blur * scale)
                + " " + CssValues.color(colourOf(layer));
    }

    /** How far a fitted shadow may reach from the mark, in px. Half the chip, so the far side survives. */
    private static final float SAMPLE_REACH = 7f;

    /** A whole {@code text-shadow}, every layer fitted to a chip and the stack kept. @see #fittedLayer */
    static String fitted(String css) {
        List<String> out = new ArrayList<>();
        for (String layer : CssValues.layers(css)) out.add(fittedLayer(layer));
        return CssValues.join(out);
    }

    /**
     * A layer's colour, wherever it sits among the lengths, or opaque black.
     *
     * <p><b>{@code parseCssColor}, never {@code parseColor}</b>, and that distinction is this whole
     * method. A colour PROPERTY also accepts a decimal ARGB literal, so {@code parseColor("0")} answers
     * transparent black - and the first term of {@code 0 1px 2px #000000} is an OFFSET. Reading it as the
     * colour made every shadow the lab added fully transparent and opened the picker at zero alpha, so
     * choosing a colour for it changed the value and nothing on the screen. {@code ColorValue}'s own
     * javadoc names a shadow's lengths as the case to watch for.</p>
     */
    static int colourOf(String layer) {
        for (String term : CssValues.terms(layer)) {
            Integer colour = ColorValue.parseCssColor(term);
            if (colour != null) return colour;
        }
        return 0xFF000000;
    }

    private String written() {
        return CssValues.px(x) + " " + CssValues.px(y) + " " + CssValues.px(blur) + " " + CssValues.color(argb);
    }

    /** Puts the dragged shadow back in the stack and shows it, without writing the file yet. */
    private void replaceSelected() {
        if (selected >= 0 && selected < layers.size()) layers.set(selected, written());
        refresh();
    }

    /** One write, when the gesture ends. @see SheetPreview */
    private void commit() {
        css.set(CssValues.join(layers));
        refresh();
    }

    private void refresh() {
        stack.show(layers, selected);
        // The dot sits where the offset says, from the pad's middle.
        LiveEdits.setInline(dot, cast(StylePropertyRegistry.TRANSFORM),
                "translate(" + CssValues.px(x) + ", " + CssValues.px(y) + ")");
        lab.refresh();
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
