package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;

/**
 * An angle in whole degrees, chosen by pointing: clockwise from up, CSS's own reading of a gradient's angle.
 *
 * <pre>{@code
 * lab.content().append(new AngleDial("angle").bind(gradient.map(g -> (double) g.angle(), deg -> gradient.get().withDirection(deg + "deg"))));
 * }</pre>
 */
public final class AngleDial extends ValueControl<Double> {

    public static final Name NAME = Name.of("angledial");

    public static final String DIAL_CLASS = "__angle-dial__";
    public static final String NEEDLE_CLASS = "__angle-needle__";

    private final UIElement needle = new UIElement();

    public AngleDial(String id) {
        super(NAME, ConfigDescriptor.number(id, "").unit("°"), 0d);
        addClass(DIAL_CLASS);
        needle.addClass(NEEDLE_CLASS);
        append(needle);
        StyleGizmos.aim(this, this::beginInteraction, degrees -> commit((double) Math.round(degrees)),
                this::endInteraction);
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable Double degrees) {
        LiveEdits.setInline(needle, StylePropertyRegistry.TRANSFORM,
                "rotate(" + CssValues.write(degrees == null ? 0d : degrees) + "deg)");
    }
}
