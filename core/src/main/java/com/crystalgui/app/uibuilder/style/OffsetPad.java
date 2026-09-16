package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;

/**
 * An offset dragged on a pad: {@code [x, y]} in pixels, drawn as a dot that far from the pad's middle.
 *
 * <pre>{@code
 * lab.content().append(new OffsetPad("offset").bind(shadow.map(Shadow::offset, at -> shadow.get().withOffset(at))));
 * }</pre>
 *
 * <p>A drag moves the value by what the pointer moved, from where it was at the press, and is one undo step.</p>
 */
public final class OffsetPad extends ValueControl<double[]> {

    public static final Name NAME = Name.of("offsetpad");

    public static final String PAD_CLASS = "__offset-pad__";
    public static final String DOT_CLASS = "__offset-dot__";

    private final UIElement dot = new UIElement();

    public OffsetPad(String id) {
        super(NAME, ConfigDescriptor.vector(id, "", 2), new double[2]);
        addClass(PAD_CLASS);
        dot.addClass(DOT_CLASS);
        append(dot);
        double[] from = new double[2];
        StyleGizmos.drag(this, () -> {
            double[] now = getValue();
            from[0] = now == null ? 0d : now[0];
            from[1] = now == null ? 0d : now[1];
            beginInteraction();
        }, (dx, dy) -> commit(new double[] {Math.round(from[0] + dx), Math.round(from[1] + dy)}),
                this::endInteraction);
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable double[] value) {
        double x = value == null ? 0d : value[0];
        double y = value == null ? 0d : value[1];
        LiveEdits.setInline(dot, StylePropertyRegistry.TRANSFORM,
                "translate(" + CssValues.px(x) + ", " + CssValues.px(y) + ")");
    }
}
