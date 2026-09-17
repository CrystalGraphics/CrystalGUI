package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.text.UIText;

/**
 * The transform lab: the ops in the order they compose, the picked one edited where it can be seen.
 *
 * <pre>{@code
 * TransformLab.open(chip, StylePropertyRegistry.TRANSFORM, css, fields.canWrite());
 * }</pre>
 *
 * <p><b>Order is the semantics.</b> {@code translate(10px) scale(2)} and {@code scale(2) translate(10px)} put
 * the element in different places, so the stack is the editor. Translate drags on a pad and rotate points on
 * a dial; each edits the picked op only when it is that kind.</p>
 */
public final class TransformLab {

    private TransformLab() {
    }

    /** @param hideable whether an op may be switched off rather than deleted, which a writable value can */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css, boolean hideable) {
        StyleLab lab = StyleLab.over(anchor, "Transform");
        lab.specimen().preview(property, css);

        Property<Integer> selected = Property.of(0);
        Property<List<String>> ops = css.map(CssValues::functionStack, CssValues::joinFunctionStack);
        Property<String> op = Property.derived(
                () -> CssValues.bodyOf(at(ops.get(), selected.get())),
                next -> {
                    List<String> list = new ArrayList<>(ops.get());
                    int index = selected.get() == null ? 0 : selected.get();
                    if (index >= 0 && index < list.size()) list.set(index, CssValues.withBody(list.get(index), next));
                    ops.set(list);
                }).editedIn(css.history());

        UIElement row = new UIElement();
        row.addClass(StyleLab.ROW_CLASS);
        row.append(new OffsetPad("lab.translate").bind(op.map(TransformLab::translation, at ->
                "translate".equals(CssValues.functionName(op.get()))
                        ? CssValues.function("translate", CssValues.px(at[0]), CssValues.px(at[1]))
                        : op.get())));
        row.append(new AngleDial("lab.rotate").bind(op.map(TransformLab::rotation, degrees ->
                "rotate".equals(CssValues.functionName(op.get()))
                        ? CssValues.function("rotate", CssValues.write(degrees) + "deg")
                        : op.get())));
        UIText numbers = new UIText("");
        PropertyWatch.follow(numbers, op, text -> numbers.setText(text.isEmpty() ? "—" : text));
        row.append(numbers);
        lab.content().append(row);

        LayerStack stack = new LayerStack("lab.ops", property, selected).titled("Transforms").hideable(hideable);
        for (String added : List.of("translate(0px, 0px)", "rotate(0deg)", "scale(1, 1)", "skew(0deg, 0deg)")) {
            stack.adding("+ " + CssValues.functionName(added), () -> {
                List<String> list = new ArrayList<>(ops.get());
                list.add(added);
                ops.set(list);
                selected.set(list.size() - 1);
            });
        }
        stack.bind(ops);
        lab.content().append(stack);

        lab.caption(Property.derived(() -> {
            int count = ops.get().size();
            return count == 0 ? "no transform"
                    : count + (count == 1 ? " op" : " ops, applied left to right")
                            + " — editing " + CssValues.functionName(op.get());
        }));
        lab.readout(property.name, css);
        lab.open();
    }

    private static String at(List<String> ops, Integer index) {
        int at = index == null ? 0 : index;
        return at >= 0 && at < ops.size() ? ops.get(at) : "";
    }

    private static double[] translation(String op) {
        if (!"translate".equals(CssValues.functionName(op))) return new double[2];
        List<String> terms = CssValues.layers(CssValues.arguments(op));
        return new double[] {CssValues.number(terms, 0, 0f), CssValues.number(terms, 1, 0f)};
    }

    private static double rotation(String op) {
        return "rotate".equals(CssValues.functionName(op)) ? CssValues.number(CssValues.arguments(op), 0f) : 0d;
    }
}
