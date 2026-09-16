package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * The transform lab: the ops in the order they compose, each edited where it can be seen.
 *
 * <pre>{@code
 * TransformLab.open(chip, StylePropertyRegistry.TRANSFORM, css);
 * }</pre>
 *
 * <p><b>Order is the semantics here, not a preference.</b> {@code translate(10px) scale(2)} and
 * {@code scale(2) translate(10px)} put the element in different places, because CSS composes left to right
 * as matrix multiplication — so the stack is the editor, and moving a row up changes the result. That is
 * also why this cannot be three number fields: a decomposition into translate/scale/rotate cannot express
 * the difference at all.</p>
 *
 * <p>Translate drags on a pad, rotate points on a dial, scale and skew scrub. The specimen shows the whole
 * chain, which is the only way to judge a chain.</p>
 */
public final class TransformLab {

    public static final String PAD_CLASS = "__offset-pad__";
    public static final String DOT_CLASS = "__offset-dot__";
    public static final String DIAL_CLASS = "__angle-dial__";
    public static final String NEEDLE_CLASS = "__angle-needle__";

    private final Property<String> css;
    private final StyleLab lab;
    private final LayerStack stack;

    private final UIElement pad = new UIElement();
    private final UIElement dot = new UIElement();
    private final UIElement dial = new UIElement();
    private final UIElement needle = new UIElement();
    private final UIText numbers = new UIText("");

    private final List<String> ops = new ArrayList<>();
    private int selected;

    private TransformLab(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        this.css = css;
        this.lab = StyleLab.over(anchor, "Transform", property, css);
        this.stack = new LayerStack(property);
        ops.addAll(CssValues.functions(css.get()));
    }

    public static TransformLab open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        TransformLab transform = new TransformLab(anchor, property, css);
        transform.build();
        transform.lab.open();
        return transform;
    }

    private void build() {
        lab.specimen();

        UIElement row = new UIElement();
        row.addClass("__lab-row__");
        pad.addClass(PAD_CLASS);
        dot.addClass(DOT_CLASS);
        pad.append(dot);
        float[] start = new float[2];
        StyleGizmos.drag(pad, (dx, dy) -> {
            if (!"translate".equals(kind())) return;
            List<String> terms = args();
            setSelected(CssValues.function("translate",
                    CssValues.px(CssValues.number(terms, 0, 0f) + dx),
                    CssValues.px(CssValues.number(terms, 1, 0f) + dy)));
            start[0] = dx;
            start[1] = dy;
        }, this::commit);
        row.append(pad);

        dial.addClass(DIAL_CLASS);
        needle.addClass(NEEDLE_CLASS);
        dial.append(needle);
        StyleGizmos.aim(dial, degrees -> {
            if (!"rotate".equals(kind())) return;
            setSelected(CssValues.function("rotate", CssValues.write(Math.round(degrees)) + "deg"));
        }, this::commit);
        row.append(dial);
        row.append(numbers);
        lab.content().append(row);

        UIElement adds = new UIElement();
        adds.addClass("__lab-row__");
        addButton(adds, "translate", "translate(0px, 0px)");
        addButton(adds, "rotate", "rotate(0deg)");
        addButton(adds, "scale", "scale(1, 1)");
        addButton(adds, "skew", "skew(0deg, 0deg)");
        lab.content().append(adds);

        stack.onSelect(index -> {
            selected = index;
            refresh();
        });
        stack.onChange(values -> {
            ops.clear();
            ops.addAll(values);
            selected = stack.selected();
            commit();
        });
        lab.content().append(stack);

        lab.caption(() -> ops.isEmpty() ? "no transform"
                : ops.size() + (ops.size() == 1 ? " op" : " ops, applied left to right") + " — editing " + kind());
        refresh();
    }

    private void addButton(UIElement row, String name, String op) {
        Button button = new Button("+ " + name);
        button.addClass("__lab-keyword__");
        button.attachListener(() -> {
            ops.add(op);
            selected = ops.size() - 1;
            commit();
        });
        row.append(button);
    }

    // ── The value ───────────────────────────────────────────────────────────

    /** What the selected op is: {@code translate}, {@code rotate}, {@code scale} or {@code skew}. */
    private String kind() {
        return selected >= 0 && selected < ops.size() ? CssValues.functionName(ops.get(selected)) : "";
    }

    private List<String> args() {
        if (selected < 0 || selected >= ops.size()) return List.of();
        return CssValues.layers(CssValues.arguments(ops.get(selected)));
    }

    private void setSelected(String op) {
        if (selected < 0 || selected >= ops.size()) return;
        ops.set(selected, op);
        refresh();
    }

    private void commit() {
        css.set(CssValues.joinFunctions(ops));
        refresh();
    }

    private void refresh() {
        stack.show(ops, selected);
        numbers.setText(selected >= 0 && selected < ops.size() ? ops.get(selected) : "—");
        lab.refresh();
    }
}
