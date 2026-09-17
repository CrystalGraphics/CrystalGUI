package com.crystalgui.app.uibuilder.style;

import java.util.List;
import java.util.Locale;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.control.Button;

/**
 * The gradient lab: the ramp itself, with its stops on it.
 *
 * <pre>{@code
 * GradientLab.open(chip, StylePropertyRegistry.BACKGROUND, css);
 * }</pre>
 *
 * <p>A gradient is edited on the thing it makes: the bar is the ramp laid left to right, a stop is a handle on it,
 * and the angle is a dial pointing the way the ramp runs. A value that is not a linear gradient opens on
 * {@link Gradient#DEFAULT} and is only replaced once something is changed.</p>
 */
public final class GradientLab {

    /** The directions a dropdown offers; the dial writes an angle, which is what "custom" means here. */
    private static final List<String> SIDES = List.of("custom", "top", "right", "bottom", "left");

    /** The button that takes the selected stop off, at the end of its row. */
    static final String REMOVE = "Remove";

    private GradientLab() {
    }

    /** Opens the lab over {@code anchor} for the declaration {@code css}. */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        StyleLab lab = StyleLab.over(anchor, "Gradient");
        lab.specimen().preview(property, css);

        Property<Integer> selected = Property.of(0);
        Property<Gradient> gradient = css.map(Gradient::parse, Gradient::toString);
        Property<Gradient.Stop> stop = gradient.map(
                ramp -> ramp.stops().get(clamp(selected.get(), ramp.stops().size())),
                next -> gradient.get().withStop(clamp(selected.get(), gradient.get().stops().size()), next));

        lab.content().append(new GradientBar("lab.ramp", property, selected).bind(gradient));

        lab.form().prop(ConfigDescriptor.select("lab.side", "Direction", SIDES), gradient.map(GradientLab::side,
                chosen -> SIDES.get(0).equals(chosen) ? gradient.get() : gradient.get().withDirection("to " + chosen)));
        lab.form().control("lab.angle", "Angle", new AngleDial("lab.angle").bind(gradient.map(
                ramp -> (double) ramp.angle(),
                degrees -> gradient.get().withDirection(CssValues.write(degrees) + "deg"))));

        lab.form().separator();
        lab.form().prop(ConfigDescriptor.color("lab.stop", "Stop color"),
                stop.map(Gradient.Stop::argb, argb -> stop.get().withArgb(argb)));
        Configurator at = lab.form().prop(ConfigDescriptor.number("lab.at", "Stop at").range(0f, 100f).unit("%")
                        .decimals(1),
                gradient.map(ramp -> (double) (ramp.position(clamp(selected.get(), ramp.stops().size())) * 100f),
                        percent -> gradient.get().withStop(clamp(selected.get(), gradient.get().stops().size()),
                                stop.get().withPosition((float) (Math.round(percent * 10d) / 1000d)))));

        // ON THE STOP'S OWN ROW, not a bar across the lab: it acts on the stop the row above is editing.
        Button remove = new Button(REMOVE);
        remove.addClass(StyleLab.KEYWORD_CLASS);
        remove.attachListener(() -> {
            int index = clamp(selected.get(), gradient.get().stops().size());
            gradient.set(gradient.get().withoutStop(index));
            selected.set(Math.max(0, index - 1));
        });
        at.append(remove);

        // WHAT THE READOUT CANNOT SAY: how the bar is worked.
        lab.caption(gradient.map(ramp -> ramp.stops().size() + " stops — double-click the bar to add one, "
                + "drag one off it to remove it"));
        lab.readout(property.name, css);
        lab.open();
    }

    private static int clamp(Integer index, int size) {
        return Math.max(0, Math.min(index == null ? 0 : index, size - 1));
    }

    /** Which side the direction names, or {@code custom} for an angle. */
    private static String side(Gradient gradient) {
        String head = gradient.direction().trim().toLowerCase(Locale.ROOT);
        return head.startsWith("to ") && SIDES.contains(head.substring(3)) ? head.substring(3) : SIDES.get(0);
    }
}
