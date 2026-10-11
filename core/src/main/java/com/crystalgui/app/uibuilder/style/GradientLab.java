package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;

/**
 * The gradient lab: the declaration's layers, then the picked layer's ramp with its stops beside it, its direction or
 * radial shape, and the stops as a list.
 *
 * <pre>{@code
 * GradientLab.open(chip, StylePropertyRegistry.BACKGROUND, css);
 * }</pre>
 *
 * <p>A gradient is edited on the thing it makes: the bar is the ramp laid left to right, a stop is a swatch under it,
 * and the direction is a dial with its eight sides and corners beside it. A radial layer trades the dial for its
 * shape, size and a pad for its centre. The layer list picks which layer the rows under it edit, and the stop list
 * which stop. A layer that is not a gradient opens on {@link Gradient#DEFAULT} and is only replaced once something
 * is changed.</p>
 */
public final class GradientLab {

    /** On the stops list: between the rows above and below it, where the shadow lab's stack opens the lab. */
    public static final String STOPS_CLASS = "__gradient-stops__";

    /** The list header's button that flips the ramp end to end. */
    static final String REVERSE = "Reverse";

    static final String LINEAR = "Linear";
    static final String RADIAL = "Radial";
    private static final List<String> TYPES = List.of(LINEAR, RADIAL);
    private static final List<String> SHAPES = List.of("Ellipse", "Circle");
    private static final List<String> SIZES = new ArrayList<>(Gradient.Radial.EXTENTS);

    static {
        SIZES.add(Gradient.Radial.LENGTH);
    }

    private GradientLab() {
    }

    /** Opens the lab over {@code anchor} for the declaration {@code css}. */
    public static void open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        StyleLab lab = StyleLab.over(anchor, "Gradient");
        lab.specimen().preview(property, css);

        // THE LAYERS FIRST: a background is a stack, and editing it as one gradient replaced every layer at once.
        Property<Integer> layer = Property.of(0);
        Property<List<String>> layers = css.map(GradientLab::layersOf, CssValues::joinLayerStack);
        Property<Gradient> gradient = Property.derived(
                () -> Gradient.parse(CssValues.bodyOf(at(layers.get(), layer.get()))),
                next -> layers.set(replaced(layers.get(), layer.get(), next.toString())))
                .editedIn(css.history());
        LayerStack stack = new LayerStack("lab.layers", property, layer).titled("Layers");
        stack.adding("+ Add", () -> stack.add(Gradient.DEFAULT.toString()));
        stack.bind(layers);
        lab.content().append(stack);

        Property<Integer> selected = Property.of(0);
        Property<Gradient.Stop> stop = gradient.map(
                ramp -> ramp.stops().get(clamp(selected.get(), ramp.stops().size())),
                next -> gradient.get().withStop(clamp(selected.get(), gradient.get().stops().size()), next));

        lab.content().append(new GradientBar("lab.ramp", property, selected).bind(gradient));
        lab.form().prop(ConfigDescriptor.select("lab.type", "Type", TYPES), gradient.map(
                ramp -> ramp.radial() != null ? RADIAL : LINEAR,
                type -> RADIAL.equals(type) ? gradient.get().withRadial(gradient.get().shape())
                        : gradient.get().withDirection(gradient.get().direction())));
        Configurator angle = lab.form().control("lab.angle", "Angle", new AngleDial("lab.angle").bind(gradient.map(
                ramp -> (double) ramp.angle(),
                degrees -> gradient.get().withDirection(CssValues.write(degrees) + "deg"))));
        Configurator shape = lab.form().prop(ConfigDescriptor.select("lab.shape", "Shape", SHAPES), gradient.map(
                ramp -> SHAPES.get(ramp.shape().circle() ? 1 : 0),
                chosen -> radial(gradient, gradient.get().shape().withCircle(SHAPES.get(1).equals(chosen)))));
        Configurator size = lab.form().prop(ConfigDescriptor.select("lab.size", "Size", SIZES), gradient.map(
                ramp -> ramp.shape().extent(),
                chosen -> radial(gradient, gradient.get().shape().withExtent(chosen))));
        Configurator radiusX = lab.form().prop(ConfigDescriptor.number("lab.radius", "Radius").range(0f, 400f).decimals(1),
                gradient.map(ramp -> ramp.shape().radius(0),
                        value -> radial(gradient, gradient.get().shape().withRadius(0, value))));
        Configurator radiusY = lab.form().prop(ConfigDescriptor.number("lab.radius-y", "Radius Y").range(0f, 400f).decimals(1),
                gradient.map(ramp -> ramp.shape().radius(1),
                        value -> radial(gradient, gradient.get().shape().withRadius(1, value))));
        Configurator centre = lab.form().control("lab.centre", "Centre", new OffsetPad("lab.centre", OffsetPad.Space.BOX)
                .bind(gradient.map(ramp -> ramp.shape().centre(),
                        at -> radial(gradient, gradient.get().shape().withCentre(at)))));
        // FOLLOWED EACH FRAME: the type is changed by a row of this form, and rebuilding it would replace that row.
        lab.content().append(new Follower(() -> {
            Gradient.Radial now = gradient.get().radial();
            angle.setDisplayed(now == null);
            shape.setDisplayed(now != null);
            size.setDisplayed(now != null);
            centre.setDisplayed(now != null);
            radiusX.setDisplayed(now != null && now.sized());
            radiusY.setDisplayed(now != null && now.sized() && !now.circle());
        }));

        // THE STOPS AS A LIST: every stop named, the picked one the rows under it edit. Ordered by position, so a row is
        // never moved by hand.
        LayerStack stops = new LayerStack("lab.stops", property, selected).titled("Stops").reorderable(false);
        stops.addClass(STOPS_CLASS);
        // THE PATCH ITSELF IS THE SPECIMEN: a stop is a colour, and a colour is the whole swatch.
        stops.sample(patch -> patch, (patch, text) -> StyleChip.paintColor(patch, argbOf(text)));
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

    private static Gradient radial(Property<Gradient> gradient, Gradient.Radial shape) {
        return gradient.get().withRadial(shape);
    }

    /** The declaration's layers, or one default gradient to start from when it has none. */
    private static List<String> layersOf(String css) {
        List<String> layers = CssValues.layerStack(css);
        return layers.isEmpty() ? List.of(Gradient.DEFAULT.toString()) : layers;
    }

    private static String at(List<String> layers, Integer index) {
        return layers.get(clamp(index, layers.size()));
    }

    private static List<String> replaced(List<String> layers, Integer index, String layer) {
        List<String> next = new ArrayList<>(layers);
        int at = clamp(index, next.size());
        next.set(at, CssValues.withBody(next.get(at), layer));
        return next;
    }

    /** An invisible part of the lab that runs {@code follow} after every layout while the lab is open. */
    private static final class Follower extends UIElement {

        Follower(Runnable follow) {
            follow.run();
            setDisplayed(false);
            onConnected(() -> {
                UIDocument window = document();
                if (window != null) window.animation().afterLayout(this, delta -> {
                    follow.run();
                    return true;
                });
            });
        }
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
        return ramp.withStops(next);
    }

    private static int argbOf(String text) {
        List<String> terms = CssValues.terms(text);
        Integer argb = terms.isEmpty() ? null : ColorValue.parseCssColor(terms.get(0));
        return argb == null ? 0 : argb;
    }
}
