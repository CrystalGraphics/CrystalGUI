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

    /** One stop: its position as a fraction, or NaN to be spread evenly as CSS does, and its color. */
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
            String color = part;
            if (terms.size() > 1 && terms.get(terms.size() - 1).endsWith("%")) {
                position = CssValues.number(terms.get(terms.size() - 1), Float.NaN) / 100f;
                color = String.join(" ", terms.subList(0, terms.size() - 1));
            }
            Integer argb = ColorValue.parseCssColor(color.trim());
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
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            out.append(", ").append(CssValues.color(stop.argb()));
            // AN END AT ITS END SAYS NOTHING: CSS puts the first stop at 0% and the last at 100% unless told otherwise.
            boolean implied = i == 0 && stop.position() == 0f || i == stops.size() - 1 && stop.position() == 1f;
            if (!Float.isNaN(stop.position()) && !implied) {
                // A TENTH OF A PERCENT: a drag lands on 17.596, and nobody authors three places of one.
                out.append(' ').append(CssValues.write(Math.round(stop.position() * 1000d) / 10d)).append('%');
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
            // A CORNER'S ANGLE DEPENDS ON THE BOX; on a square one it is the diagonal, which is what the dial shows.
            case "to top right", "to right top" -> 45f;
            case "to bottom right", "to right bottom" -> 135f;
            case "to bottom left", "to left bottom" -> 225f;
            case "to top left", "to left top" -> 315f;
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

    /**
     * Moves stop {@code index} to {@code position}, keeping the stops in order: a stop dragged past its neighbour
     * passes it, where CSS would clamp it there. @see #indexAfterMove
     */
    public Gradient withStopMoved(int index, float position) {
        if (index < 0 || index >= stops.size()) return this;
        List<Stop> next = new ArrayList<>(stops);
        Stop moved = next.remove(index).withPosition(position);
        next.add(indexAfterMove(index, position), moved);
        return new Gradient(direction, next);
    }

    /** Where stop {@code index} lands after {@link #withStopMoved}: past every other stop before {@code position}. */
    public int indexAfterMove(int index, float position) {
        int at = 0;
        for (int i = 0; i < stops.size(); i++) {
            if (i == index) continue;
            // A TIE KEEPS ITS SIDE, so resting on a neighbour does not swap the two back and forth.
            if (position(i) < position || position(i) == position && i < index) at++;
        }
        return at;
    }

    /** Adds a stop at {@code where} in the color the ramp already shows there, so adding changes nothing. */
    public Gradient withStopAt(float where) {
        List<Stop> next = new ArrayList<>(stops);
        next.add(indexAt(where), new Stop(where, colorAt(where)));
        return new Gradient(direction, next);
    }

    /** The same ramp run the other way: each stop mirrored to where it would be read from the far end. */
    public Gradient reversed() {
        List<Stop> next = new ArrayList<>(stops.size());
        for (int i = stops.size() - 1; i >= 0; i--) {
            next.add(new Stop(1f - position(i), stops.get(i).argb()));
        }
        return new Gradient(direction, next);
    }

    /** Adds a stop in the middle of the widest gap between two stops, in the color the ramp shows there. */
    public Gradient withStopInWidestGap() {
        float widest = -1f;
        float middle = 0.5f;
        for (int i = 0; i < stops.size() - 1; i++) {
            float gap = position(i + 1) - position(i);
            if (gap > widest) {
                widest = gap;
                middle = position(i) + gap / 2f;
            }
        }
        return withStopAt(middle);
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

    private int colorAt(float where) {
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
