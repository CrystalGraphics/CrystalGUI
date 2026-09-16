package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * A {@code linear-gradient(...)} as its parts: a direction and two or more stops.
 *
 * <pre>{@code
 * Property<Gradient> gradient = css.map(Gradient::parse, Gradient::toString);
 * gradient.set(gradient.get().withStop(1, stop.withArgb(0xFFFF0000)));
 * }</pre>
 *
 * <p>The grammar is the engine's own ({@code TextureValue.parseLinearGradient}). A value it cannot read
 * parses as {@link #DEFAULT}, so a lab opened on something else starts from a workable ramp.</p>
 */
public record Gradient(String direction, List<Stop> stops) {

    /** One stop: its position as a fraction, or NaN to be spread evenly as CSS does, and its colour. */
    public record Stop(float position, int argb) {

        public Stop withPosition(float next) {
            return new Stop(next, argb);
        }

        public Stop withArgb(int next) {
            return new Stop(position, next);
        }
    }

    public static final Gradient DEFAULT = new Gradient("180deg",
            List.of(new Stop(0f, 0xFF6AA9FF), new Stop(1f, 0xFFC86AFF)));

    public Gradient {
        stops = List.copyOf(stops);
    }

    public static Gradient parse(@Nullable String css) {
        String text = css == null ? "" : css.trim();
        if (!text.toLowerCase(Locale.ROOT).startsWith("linear-gradient(")) return DEFAULT;
        List<String> parts = CssValues.layers(CssValues.arguments(text));
        String direction = DEFAULT.direction();
        if (!parts.isEmpty() && isDirection(parts.get(0))) {
            direction = parts.get(0).trim();
            parts = parts.subList(1, parts.size());
        }
        List<Stop> stops = new ArrayList<>();
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
        return new Gradient(direction, stops.size() < 2 ? DEFAULT.stops() : stops);
    }

    /** An angle or a {@code to <side>} — the same test the engine's parser makes. */
    private static boolean isDirection(String part) {
        String head = part.trim().toLowerCase(Locale.ROOT);
        return head.startsWith("to ") || head.endsWith("deg") || head.endsWith("turn")
                || head.endsWith("rad") || head.endsWith("grad");
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("linear-gradient(").append(direction);
        for (Stop stop : stops) {
            out.append(", ").append(CssValues.color(stop.argb()));
            if (!Float.isNaN(stop.position())) {
                out.append(' ').append(CssValues.write(stop.position() * 100)).append('%');
            }
        }
        return out.append(')').toString();
    }

    /** Where stop {@code index} sits: its own position, else spread evenly. */
    public float position(int index) {
        if (index < 0 || index >= stops.size()) return 0f;
        float declared = stops.get(index).position();
        return Float.isNaN(declared) ? (stops.size() == 1 ? 0f : (float) index / (stops.size() - 1)) : declared;
    }

    /** The direction as degrees clockwise from up — a keyword is its own angle. */
    public float angle() {
        String head = direction.trim().toLowerCase(Locale.ROOT);
        return switch (head) {
            case "to top" -> 0f;
            case "to right" -> 90f;
            case "to bottom" -> 180f;
            case "to left" -> 270f;
            default -> head.endsWith("turn") ? CssValues.number(head, 0.5f) * 360f : CssValues.number(head, 180f);
        };
    }

    public Gradient withDirection(String next) {
        return new Gradient(next, stops);
    }

    public Gradient withStop(int index, Stop stop) {
        if (index < 0 || index >= stops.size()) return this;
        List<Stop> next = new ArrayList<>(stops);
        next.set(index, stop);
        return new Gradient(direction, next);
    }

    /** Adds a stop at {@code where} in the colour the ramp already shows there, so adding changes nothing. */
    public Gradient withStopAt(float where) {
        List<Stop> next = new ArrayList<>(stops);
        next.add(indexAt(where), new Stop(where, colourAt(where)));
        return new Gradient(direction, next);
    }

    /** Removes a stop, never below the two a gradient needs to parse. */
    public Gradient withoutStop(int index) {
        if (stops.size() <= 2 || index < 0 || index >= stops.size()) return this;
        List<Stop> next = new ArrayList<>(stops);
        next.remove(index);
        return new Gradient(direction, next);
    }

    /** The index a stop added at {@code where} lands at. */
    public int indexAt(float where) {
        for (int i = 0; i < stops.size(); i++) {
            if (position(i) > where) return i;
        }
        return stops.size();
    }

    private int colourAt(float where) {
        for (int i = 0; i < stops.size() - 1; i++) {
            float from = position(i);
            float to = position(i + 1);
            if (where < from || where > to || to <= from) continue;
            return lerp(stops.get(i).argb(), stops.get(i + 1).argb(), (where - from) / (to - from));
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
}
