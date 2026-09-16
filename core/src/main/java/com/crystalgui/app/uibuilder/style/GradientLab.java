package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.color.ColorValue;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.control.Button;

/**
 * The gradient lab: the ramp itself, with its stops on it.
 *
 * <pre>{@code
 * GradientLab.open(chip, StylePropertyRegistry.BACKGROUND, css);
 * }</pre>
 *
 * <p>A gradient is edited on the thing it makes. The bar <b>is</b> the value — an element whose background
 * is the declaration as it stands — and a stop is a handle on it, dragged along the bar to move it and
 * clicked to open a colour. The angle is a dial pointing the way the ramp runs, which is what the number
 * means and what a field of degrees never shows.</p>
 *
 * <p>The grammar is the engine's own ({@code TextureValue.parseLinearGradient}): an angle or a
 * {@code to <side>}/{@code to <corner>}, then two or more colours each with an optional percentage. A value
 * this cannot read is left alone and the lab opens on a default ramp rather than rewriting what somebody
 * hand-authored.</p>
 */
public final class GradientLab {

    public static final String BAR_CLASS = "__gradient-bar__";
    public static final String STOP_CLASS = "__gradient-stop__";
    public static final String DIAL_CLASS = "__angle-dial__";
    public static final String NEEDLE_CLASS = "__angle-needle__";

    /** What a gradient with nothing readable in it starts from. */
    private static final String DEFAULT = "linear-gradient(180deg, #6AA9FF, #C86AFF)";

    /** The directions a dropdown offers; the dial writes an angle, which is what "custom" means here. */
    private static final List<String> SIDES = List.of("custom", "top", "right", "bottom", "left");

    private final Property<String> css;
    private final StyleProperty<?> property;
    private final StyleLab lab;

    private final UIElement bar = new UIElement();
    private final UIElement dial = new UIElement();
    private final UIElement needle = new UIElement();

    /** The value being edited, as parts: the direction, then the stops in order. */
    private String direction = "180deg";
    private final List<Stop> stops = new ArrayList<>();

    private int selected;

    /** One stop: where it sits along the ramp, and what colour it is. */
    private record Stop(float position, int argb) {
    }

    private GradientLab(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        this.css = css;
        this.property = property;
        this.lab = StyleLab.over(anchor, "Gradient", property, css);
        read(css.get());
    }

    /** Opens the lab over {@code anchor} for the declaration {@code css}. */
    public static GradientLab open(UIElement anchor, StyleProperty<?> property, Property<String> css) {
        GradientLab gradient = new GradientLab(anchor, property, css);
        gradient.build();
        gradient.lab.open();
        return gradient;
    }

    private void build() {
        lab.specimen();
        bar.addClass(BAR_CLASS);
        bar.setHitTest(true);
        lab.content().append(bar);

        // A PRESS ON THE BAR ADDS A STOP where it lands -- Figma's and DevTools' gradient bars both do it,
        // and it is the only gesture that does not need a button somewhere else.
        bar.onMouseDown.attachListener((element, event) -> {
            if (!(event instanceof MouseEvent.Down down) || down.getDetail() < 2) return;
            addStopAt(StyleGizmos.fractionAt(bar, down.getPosition().x(), down.getPosition().y()));
            event.preventDefault();
        }, false, true);

        dial.addClass(DIAL_CLASS);
        needle.addClass(NEEDLE_CLASS);
        dial.append(needle);
        StyleGizmos.aim(dial, degrees -> {
            direction = CssValues.write(Math.round(degrees)) + "deg";
            write();
        }, this::write);
        lab.content().append(dial);

        lab.form().prop(ConfigDescriptor.select("lab.side", "Direction", SIDES),
                Property.derived(this::side, chosen -> {
                    direction = chosen.equals(SIDES.get(0)) ? direction : "to " + chosen;
                    write();
                }));
        lab.form().prop(ConfigDescriptor.color("lab.stop", "Stop colour"),
                Property.derived(() -> stops.isEmpty() ? 0xFFFFFFFF : stops.get(selected).argb(), argb -> {
                    if (selected >= 0 && selected < stops.size()) {
                        stops.set(selected, new Stop(stops.get(selected).position(), argb));
                        write();
                    }
                }));
        lab.form().prop(ConfigDescriptor.number("lab.at", "Stop at").range(0f, 100f).unit("%"),
                Property.derived(() -> (double) (position(selected) * 100f), at -> {
                    if (selected >= 0 && selected < stops.size()) {
                        stops.set(selected, new Stop((float) (at / 100d), stops.get(selected).argb()));
                        write();
                    }
                }));

        Button remove = new Button("Remove stop");
        remove.addClass("__lab-keyword__");
        remove.attachListener(() -> {
            // TWO IS A GRADIENT'S FLOOR: below that the engine's own parser refuses the value.
            if (stops.size() > 2 && selected >= 0 && selected < stops.size()) {
                stops.remove(selected);
                selected = Math.max(0, selected - 1);
                write();
            }
        });
        lab.content().append(remove);

        lab.caption(() -> stops.size() + " stops, " + direction
                + (stops.isEmpty() ? "" : " — selected stop at " + Math.round(position(selected) * 100) + "%"));
        refresh();
    }

    // ── The value ───────────────────────────────────────────────────────────

    /** Reads the declaration into parts, falling back to a workable ramp when it says something else. */
    private void read(@Nullable String value) {
        stops.clear();
        String text = value == null ? "" : value.trim();
        if (!text.toLowerCase(Locale.ROOT).startsWith("linear-gradient(")) text = DEFAULT;
        List<String> parts = CssValues.layers(CssValues.arguments(text));
        if (!parts.isEmpty() && isDirection(parts.get(0))) {
            direction = parts.get(0).trim();
            parts = parts.subList(1, parts.size());
        }
        for (String part : parts) {
            List<String> terms = CssValues.terms(part);
            float position = Float.NaN;
            String colour = part;
            if (terms.size() > 1 && terms.get(terms.size() - 1).endsWith("%")) {
                position = CssValues.number(terms.get(terms.size() - 1), Float.NaN) / 100f;
                colour = String.join(" ", terms.subList(0, terms.size() - 1));
            }
            Integer argb = ColorValue.parseCssColor(colour.trim());
            if (argb != null) stops.add(new Stop(position, argb));
        }
        if (stops.size() < 2) {
            stops.clear();
            stops.add(new Stop(0f, 0xFF6AA9FF));
            stops.add(new Stop(1f, 0xFFC86AFF));
        }
    }

    /** An angle or a {@code to <side>} — the same test the engine's parser makes. */
    private static boolean isDirection(String part) {
        String head = part.trim().toLowerCase(Locale.ROOT);
        return head.startsWith("to ") || head.endsWith("deg") || head.endsWith("turn")
                || head.endsWith("rad") || head.endsWith("grad");
    }

    /** The parts back into one declaration — what the sheet will hold. */
    private String written() {
        StringBuilder out = new StringBuilder("linear-gradient(").append(direction);
        for (Stop stop : stops) {
            out.append(", ").append(CssValues.color(stop.argb()));
            if (!Float.isNaN(stop.position())) {
                out.append(' ').append(CssValues.write(stop.position() * 100)).append('%');
            }
        }
        return out.append(')').toString();
    }

    private void write() {
        css.set(written());
        refresh();
    }

    /** Where a stop sits: its own position, else spread evenly, as CSS does for an unpositioned stop. */
    private float position(int index) {
        if (index < 0 || index >= stops.size()) return 0f;
        float declared = stops.get(index).position();
        return Float.isNaN(declared) ? (stops.size() == 1 ? 0f : (float) index / (stops.size() - 1)) : declared;
    }

    private void addStopAt(float where) {
        int at = stops.size();
        for (int i = 0; i < stops.size(); i++) {
            if (position(i) > where) {
                at = i;
                break;
            }
        }
        stops.add(at, new Stop(where, blended(where)));
        selected = at;
        write();
    }

    /** The colour the ramp already shows at {@code where}, so an added stop changes nothing by itself. */
    private int blended(float where) {
        for (int i = 0; i < stops.size() - 1; i++) {
            float from = position(i);
            float to = position(i + 1);
            if (where < from || where > to || to <= from) continue;
            float t = (where - from) / (to - from);
            return lerp(stops.get(i).argb(), stops.get(i + 1).argb(), t);
        }
        return stops.isEmpty() ? 0xFFFFFFFF : stops.get(stops.size() - 1).argb();
    }

    private static int lerp(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            out |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
        }
        return out;
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    /** The bar takes the value, and its handles move to where their stops are. */
    private void refresh() {
        LiveEdits.setInline(bar, cast(property), written());
        bar.removeAll();
        for (int i = 0; i < stops.size(); i++) {
            bar.append(handle(i));
        }
        LiveEdits.setInline(needle, cast(StylePropertyRegistry.TRANSFORM),
                "rotate(" + CssValues.write(angleOf(direction)) + "deg)");
        lab.refresh();
    }

    private UIElement handle(int index) {
        UIElement handle = new UIElement();
        handle.addClass(STOP_CLASS);
        if (index == selected) handle.addClass("__active__");
        LiveEdits.setInline(handle, cast(LayoutProperties.LEFT), CssValues.write(position(index) * 100f) + "%");
        float start = position(index);
        StyleGizmos.drag(handle, (dx, dy) -> {
            float width = bar.box() == null ? 1f : Math.max(1f, bar.box().width());
            float moved = Math.max(0f, Math.min(1f, start + dx / width));
            stops.set(index, new Stop(moved, stops.get(index).argb()));
            selected = index;
            refresh();
        }, this::write);
        handle.onMouseDown.attachListener((element, event) -> {
            selected = index;
            refresh();
        }, false, true);
        return handle;
    }

    /** Which side the direction names, or {@code custom} for an angle. */
    private String side() {
        String head = direction.trim().toLowerCase(Locale.ROOT);
        return head.startsWith("to ") && SIDES.contains(head.substring(3)) ? head.substring(3) : SIDES.get(0);
    }

    /** The direction as degrees, for the needle — a keyword is its own angle. */
    private static float angleOf(String direction) {
        String head = direction.trim().toLowerCase(Locale.ROOT);
        return switch (head) {
            case "to top" -> 0f;
            case "to right" -> 90f;
            case "to bottom" -> 180f;
            case "to left" -> 270f;
            default -> head.endsWith("turn") ? CssValues.number(head, 0.5f) * 360f : CssValues.number(head, 180f);
        };
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
