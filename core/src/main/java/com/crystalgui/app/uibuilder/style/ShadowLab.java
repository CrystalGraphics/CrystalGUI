package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ColorSelector;
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

    private static final String DEFAULT = "0 1px 2px #000000";

    private final Property<String> css;
    private final StyleProperty<?> property;
    private final StyleLab lab;
    private final LayerStack stack;

    private final UIElement pad = new UIElement();
    private final UIElement dot = new UIElement();
    private final UIText numbers = new UIText("");

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
        lab.specimens();

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

        UIElement row = new UIElement();
        row.addClass("__lab-row__");
        row.append(numbers);
        for (String step : List.of("blur −", "blur +")) {
            Button button = new Button(step);
            button.addClass("__lab-keyword__");
            button.attachListener(() -> {
                blur = Math.max(0f, blur + (step.endsWith("+") ? 1f : -1f));
                replaceSelected();
                commit();
            });
            row.append(button);
        }
        Button add = new Button("+ shadow");
        add.addClass("__lab-keyword__");
        add.attachListener(() -> {
            layers.add(0, DEFAULT);
            selected = 0;
            readSelected();
            commit();
        });
        row.append(add);
        lab.content().append(row);

        ColorSelector picker = new ColorSelector();
        picker.setColor(argb);
        picker.onColorChanged.connect(colour -> {
            argb = colour;
            replaceSelected();
            commit();
        });
        lab.content().append(picker);

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

    /** The selected layer's own terms: {@code x y blur colour}, in CSS's order. */
    private void readSelected() {
        if (selected < 0 || selected >= layers.size()) return;
        List<String> terms = CssValues.terms(layers.get(selected));
        x = CssValues.number(terms, 0, 0f);
        y = CssValues.number(terms, 1, 0f);
        blur = CssValues.number(terms, 2, 0f);
        Integer colour = terms.isEmpty() ? null : ColorValue.parseColor(terms.get(terms.size() - 1));
        argb = colour == null ? 0xFF000000 : colour;
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
        numbers.setText(written());
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
