package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.style.property.visual.color.ColorValue;

/**
 * One gradient layer as its parts: two or more stops, and a direction or a radial shape.
 *
 * <pre>{@code
 * Property<Gradient> gradient = css.map(Gradient::parse, Gradient::toString);
 * gradient.set(gradient.get().withStop(1, stop.withArgb(0xFFFF0000)));
 * gradient.set(gradient.get().withRadial(Gradient.Radial.ELLIPSE.withCircle(true)));   // radial-gradient(circle, ...)
 * gradient.set(gradient.get().withDirection("90deg"));                                  // linear again
 * }</pre>
 *
 * <p>The grammar is the engine's own ({@code TextureValue.parseLinearGradient} and {@code parseRadialGradient}). A
 * value it cannot read parses as {@link #DEFAULT}, so a lab opened on something else starts from a workable ramp.
 * A radial gradient keeps the {@link #direction} it had, so switching back to linear restores it.</p>
 *
 * @param radial the shape when this is a {@code radial-gradient}, else null
 */
public record Gradient(String direction, List<Stop> stops, @Nullable Radial radial) {

    /** One stop: its position as a fraction, or NaN to be spread evenly as CSS does, and its color. */
    public record Stop(float position, int argb) {

        public Stop withPosition(float next) {
            return new Stop(next, argb);
        }

        public Stop withArgb(int next) {
            return new Stop(position, next);
        }
    }

    /**
     * A radial gradient's shape: what comes before its stops, kept as the words a sheet writes.
     *
     * @param size     {@code ""} for CSS's default (farthest-corner), an {@link #EXTENTS extent}, or one or two lengths
     * @param position what follows {@code at}, or {@code ""} for the centre
     */
    public record Radial(boolean circle, String size, String position) {

        /** CSS's default: {@code radial-gradient(stops)}. */
        public static final Radial ELLIPSE = new Radial(false, "", "");

        public static final List<String> EXTENTS = List.of("farthest-corner", "farthest-side", "closest-corner",
                "closest-side");

        /** What {@link #extent} answers for a size given as lengths. */
        public static final String LENGTH = "length";

        static Radial parse(String config) {
            Boolean circle = null;
            List<String> size = new ArrayList<>();
            List<String> position = new ArrayList<>();
            boolean at = false;
            for (String term : CssValues.terms(config.trim().toLowerCase(Locale.ROOT))) {
                if (at) position.add(term);
                else if (term.equals("at")) at = true;
                else if (term.equals("circle") || term.equals("ellipse")) circle = term.equals("circle");
                else size.add(term);
            }
            // ONE LENGTH ALONE IS A CIRCLE, as CSS reads it.
            if (circle == null) circle = size.size() == 1 && !EXTENTS.contains(size.get(0));
            return new Radial(circle, String.join(" ", size), String.join(" ", position));
        }

        /** The size as the lab's choice: an extent, or {@link #LENGTH}. */
        public String extent() {
            if (size.isEmpty()) return EXTENTS.get(0);
            return EXTENTS.contains(size) ? size : LENGTH;
        }

        public boolean sized() {
            return LENGTH.equals(extent());
        }

        /** A circle's radius ({@code axis} 0) or an ellipse's horizontal or vertical one, in the unit written. */
        public double radius(int axis) {
            List<String> terms = CssValues.terms(size);
            if (!sized() || terms.isEmpty()) return 0d;
            return CssValues.number(terms.get(Math.min(axis, terms.size() - 1)), 0f);
        }

        /** The radius set, in the unit it was written in. */
        public Radial withRadius(int axis, double value) {
            if (!sized()) return this;
            List<String> terms = new ArrayList<>(CssValues.terms(size));
            if (!circle && terms.size() == 1) terms.add(terms.get(0));
            int at = Math.min(axis, terms.size() - 1);
            String unit = terms.get(at).endsWith("%") ? "%" : "px";
            terms.set(at, CssValues.write(Math.round(Math.max(0d, value) * 10d) / 10d) + unit);
            return new Radial(circle, String.join(" ", terms), position);
        }

        /** A circle or an ellipse, its lengths carried over where CSS allows: a circle takes one, never a percentage. */
        public Radial withCircle(boolean next) {
            if (next == circle) return this;
            String nextSize = size;
            if (sized()) {
                List<String> terms = CssValues.terms(size);
                nextSize = next ? (terms.get(0).endsWith("%") ? "" : terms.get(0))
                        : terms.size() == 1 ? terms.get(0) + " " + terms.get(0) : size;
            }
            return new Radial(next, nextSize, position);
        }

        /** An extent, or {@link #LENGTH} for a starting radius to edit. */
        public Radial withExtent(String choice) {
            if (choice.equals(extent())) return this;
            String next = LENGTH.equals(choice) ? (circle ? "50px" : "50% 50%")
                    : choice.equals(EXTENTS.get(0)) ? "" : choice;
            return new Radial(circle, next, position);
        }

        /** The centre as fractions of the box; a coordinate in px reads as the middle, since the box is not known. */
        public double[] centre() {
            List<String> terms = new ArrayList<>(CssValues.terms(position));
            if (terms.isEmpty()) return new double[]{0.5d, 0.5d};
            if (terms.size() == 1) terms.add("center");
            String x = terms.get(0), y = terms.get(1);
            if (x.equals("top") || x.equals("bottom") || y.equals("left") || y.equals("right")) {
                String swap = x;
                x = y;
                y = swap;
            }
            return new double[]{fraction(x, "left", "right"), fraction(y, "top", "bottom")};
        }

        public Radial withCentre(double[] at) {
            boolean middle = at[0] == 0.5d && at[1] == 0.5d;
            return new Radial(circle, size, middle ? "" : percent(at[0]) + " " + percent(at[1]));
        }

        private static double fraction(String term, String start, String end) {
            if (term.equals(start)) return 0d;
            if (term.equals(end)) return 1d;
            return term.endsWith("%") ? CssValues.number(term, 50f) / 100d : 0.5d;
        }

        private static String percent(double fraction) {
            return CssValues.write(Math.round(fraction * 1000d) / 10d) + "%";
        }

        /** What goes before the stops — {@code ""} when every part is CSS's default. */
        @Override
        public String toString() {
            List<String> out = new ArrayList<>();
            if (circle) out.add("circle");
            if (!size.isEmpty()) out.add(size);
            if (!position.isEmpty()) out.add("at " + position);
            return String.join(" ", out);
        }
    }

    public static final Gradient DEFAULT = new Gradient("180deg",
            List.of(new Stop(0f, 0xFF6AA9FF), new Stop(1f, 0xFFC86AFF)));

    public Gradient {
        stops = List.copyOf(stops);
    }

    /** A linear gradient. */
    public Gradient(String direction, List<Stop> stops) {
        this(direction, stops, null);
    }

    public static Gradient parse(@Nullable String css) {
        String text = css == null ? "" : css.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean radial = lower.startsWith("radial-gradient(");
        if (!radial && !lower.startsWith("linear-gradient(")) return DEFAULT;
        List<String> parts = CssValues.layers(CssValues.arguments(text));
        String direction = DEFAULT.direction();
        Radial shape = radial ? Radial.ELLIPSE : null;
        if (!parts.isEmpty() && (radial ? stopOf(parts.get(0)) == null : isDirection(parts.get(0)))) {
            if (radial) shape = Radial.parse(parts.get(0));
            else direction = parts.get(0).trim();
            parts = parts.subList(1, parts.size());
        }
        List<Stop> stops = new ArrayList<>();
        for (String part : parts) {
            Stop stop = stopOf(part);
            if (stop != null) stops.add(stop);
        }
        return new Gradient(direction, stops.size() < 2 ? DEFAULT.stops() : stops, shape);
    }

    /** A colour with an optional {@code <n>%}, or null when {@code part} is not a stop. */
    @Nullable
    private static Stop stopOf(String part) {
        List<String> terms = CssValues.terms(part);
        float position = Float.NaN;
        String color = part;
        if (terms.size() > 1 && terms.get(terms.size() - 1).endsWith("%")) {
            position = CssValues.number(terms.get(terms.size() - 1), Float.NaN) / 100f;
            color = String.join(" ", terms.subList(0, terms.size() - 1));
        }
        Integer argb = ColorValue.parseCssColor(color.trim());
        return argb == null ? null : new Stop(position, argb);
    }

    /** An angle or a {@code to <side>} — the same test the engine's parser makes. */
    private static boolean isDirection(String part) {
        String head = part.trim().toLowerCase(Locale.ROOT);
        return head.startsWith("to ") || head.endsWith("deg") || head.endsWith("turn")
                || head.endsWith("rad") || head.endsWith("grad");
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(radial != null ? "radial-gradient(" : "linear-gradient(");
        String head = radial != null ? radial.toString() : direction;
        out.append(head);
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (i > 0 || !head.isEmpty()) out.append(", ");
            out.append(CssValues.color(stop.argb()));
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

    /** A linear gradient in {@code next}'s direction, whatever this one was. */
    public Gradient withDirection(String next) {
        return new Gradient(next, stops, null);
    }

    /** A radial gradient of this shape, keeping the direction a switch back to linear restores. */
    public Gradient withRadial(Radial next) {
        return new Gradient(direction, stops, next);
    }

    /** The radial shape, or CSS's default one for a linear gradient about to become radial. */
    public Radial shape() {
        return radial != null ? radial : Radial.ELLIPSE;
    }

    public Gradient withStops(List<Stop> next) {
        return new Gradient(direction, next, radial);
    }

    public Gradient withStop(int index, Stop stop) {
        if (index < 0 || index >= stops.size()) return this;
        List<Stop> next = new ArrayList<>(stops);
        next.set(index, stop);
        return withStops(next);
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
        return withStops(next);
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
        return withStops(next);
    }

    /** The same ramp run the other way: each stop mirrored to where it would be read from the far end. */
    public Gradient reversed() {
        List<Stop> next = new ArrayList<>(stops.size());
        for (int i = stops.size() - 1; i >= 0; i--) {
            next.add(new Stop(1f - position(i), stops.get(i).argb()));
        }
        return withStops(next);
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
        return withStops(next);
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
