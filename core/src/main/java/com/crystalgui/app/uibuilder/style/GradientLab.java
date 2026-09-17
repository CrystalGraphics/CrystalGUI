package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;

/**
 * The gradient lab: the ramp with its stops beside it, the direction, and the stops as a list.
 *
 * <pre>{@code
 * GradientLab.open(chip, StylePropertyRegistry.BACKGROUND, css);
 * }</pre>
 *
 * <p>A gradient is edited on the thing it makes: the bar is the ramp laid left to right, a stop is a swatch under it,
 * and the direction is a dial with its eight sides and corners beside it. The list names every stop and picks the one
 * the rows under it edit. A value that is not a linear gradient opens on {@link Gradient#DEFAULT} and is only replaced
 * once something is changed.</p>
 */
public final class GradientLab {

    /** On the stops list: between the rows above and below it, where the shadow lab's stack opens the lab. */
    public static final String STOPS_CLASS = "__gradient-stops__";

    /** The list header's button that flips the ramp end to end. */
    static final String REVERSE = "Reverse";

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
        lab.form().control("lab.angle", "Angle", new AngleDial("lab.angle").bind(gradient.map(
                ramp -> (double) ramp.angle(),
                degrees -> gradient.get().withDirection(CssValues.write(degrees) + "deg"))));

        // THE STOPS AS A LIST: every stop named, the picked one the rows under it edit. Ordered by position, so a row is
        // never moved by hand.
        LayerStack stops = new LayerStack("lab.stops", property, selected).titled("Stops").reorderable(false);
        stops.addClass(STOPS_CLASS);
        stops.sample(patch -> { }, (patch, text) -> StyleChip.paintColor(patch, argbOf(text)));
        stops.adding("+ Add", () -> gradient.set(gradient.get().withStopInWidestGap()));
        stops.adding(REVERSE, () -> {
            gradient.set(gradient.get().reversed());
            selected.set(gradient.get().stops().size() - 1 - clamp(selected.get(), gradient.get().stops().size()));
        });
        stops.bind(gradient.map(GradientLab::stopTexts, texts -> fromTexts(gradient.get(), texts)));
        lab.content().append(stops);

        lab.form().prop(ConfigDescriptor.color("lab.stop", "Stop color"),
                stop.map(Gradient.Stop::argb, argb -> stop.get().withArgb(argb)));
        lab.form().prop(ConfigDescriptor.number("lab.at", "Stop at").range(0f, 100f).unit("%").decimals(1),
                gradient.map(ramp -> (double) (ramp.position(clamp(selected.get(), ramp.stops().size())) * 100f),
                        percent -> {
                            Gradient now = gradient.get();
                            int at = clamp(selected.get(), now.stops().size());
                            float position = (float) (Math.round(percent * 10d) / 1000d);
                            selected.set(now.indexAfterMove(at, position));
                            return now.withStopMoved(at, position);
                        }));
        lab.readout(property.name, css);
        lab.open();
    }

    private static int clamp(Integer index, int size) {
        return Math.max(0, Math.min(index == null ? 0 : index, size - 1));
    }

    /** Each stop as a list row reads it: its color, then where it sits. */
    private static List<String> stopTexts(Gradient ramp) {
        List<String> out = new ArrayList<>(ramp.stops().size());
        for (int i = 0; i < ramp.stops().size(); i++) {
            out.add(CssValues.color(ramp.stops().get(i).argb()) + " "
                    + CssValues.write(Math.round(ramp.position(i) * 1000d) / 10d) + "%");
        }
        return out;
    }

    /** The list's rows back into the ramp — refused below two stops, which a gradient needs to be one. */
    private static Gradient fromTexts(Gradient ramp, List<String> texts) {
        if (texts.size() < 2) return ramp;
        List<Gradient.Stop> next = new ArrayList<>(texts.size());
        for (String text : texts) {
            List<String> terms = CssValues.terms(text);
            float position = terms.size() > 1 ? CssValues.number(terms.get(1), 0f) / 100f : Float.NaN;
            next.add(new Gradient.Stop(position, argbOf(text)));
        }
        return new Gradient(ramp.direction(), next);
    }

    private static int argbOf(String text) {
        List<String> terms = CssValues.terms(text);
        Integer argb = terms.isEmpty() ? null : ColorValue.parseCssColor(terms.get(0));
        return argb == null ? 0 : argb;
    }
}
