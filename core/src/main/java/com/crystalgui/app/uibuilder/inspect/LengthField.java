package com.crystalgui.app.uibuilder.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;

import javax.annotation.Nullable;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * A CSS length: a number you can scrub, and the unit it is in — {@code 12px}, {@code 50%}, {@code auto}.
 *
 * <pre>{@code
 * form.control("style.width", "Width", new LengthField("style.width", LengthField.unitsOf(WIDTH)).bind(css));
 * }</pre>
 *
 * <p>The value is the declaration's own text, so a field is bound to the same {@code Property<String>} a text row
 * was. <b>The units are the property's own</b>: they are found by asking its parser what it reads, rather than by a
 * table here that would go stale the day a property learns {@code em} — {@link #unitsOf}.</p>
 *
 * <p>The unit cycles on a press, and <b>the length is CONVERTED rather than the number kept</b>: switching a
 * {@code top} of 593px to a percentage is 54.9% of the parent, not 593% of it — which put the element five
 * screens down and looked like the control had broken. Where there is nothing to measure against — no parent
 * laid out, no font size — the number is kept, because a made-up base is worse than an honest one. {@code auto}
 * is a unit here rather than a keyword to remember, which is what makes it reachable without typing at all.</p>
 */
public final class LengthField extends ValueControl<String> {

    public static final Name NAME = Name.of("lengthfield");

    /** The row: the number, then the unit. */
    public static final String FIELD_CLASS = "__length-field__";
    public static final String UNIT_CLASS = "__length-unit__";

    /** The keyword units, which carry no number. */
    public static final String AUTO = "auto";

    /** What a scrub is worth: a whole unit every three pixels, as everywhere else a length is dragged. */
    private static final double SCRUB_RATE = 1d / 3d;

    /** What 100% and 1em are worth in pixels, or NaN where there is nothing to measure. @see #against */
    private DoubleSupplier hundredPercent = () -> Double.NaN;
    private DoubleSupplier em = () -> Double.NaN;

    private final List<String> units;
    private final NumberControl number;
    private final Button unit;

    /**
     * The last length this field carried, IN PIXELS, for coming back from a keyword: {@code auto} has no number, so
     * without it a round trip through the chip left a width that had been 120px at zero.
     */
    private double remembered;

    /** @param units what this length may be in, in the order the chip cycles them. @see #unitsOf */
    public LengthField(String id, List<String> units) {
        super(NAME, ConfigDescriptor.text(id, ""), "");
        this.units = units.isEmpty() ? List.of("px") : units;
        addClass(FIELD_CLASS);

        number = new NumberControl(ConfigDescriptor.number(id + ".n", "")
                .scrubRate(SCRUB_RATE).step(1f).decimals(2).placeholder(AUTO), 0d);
        number.bind(Property.derived(this::carried, typed -> write(typed, unitOf(getValue()))));
        append(number);

        unit = new Button("");
        unit.addClass(UNIT_CLASS);
        unit.attachListener(this::cycle);
        Tooltip.attach(unit, String.join(" / ", this.units) + " — press to change");
        append(unit);
    }

    /**
     * The units {@code property} reads, asked of its own parser: {@code px}, {@code %} and {@code em} where it
     * takes them, and {@code auto} where that is a value at all.
     *
     * <p>Empty for a property that is not a length, which is how a caller tells one from a colour or a keyword
     * without a list of names to keep in step.</p>
     */
    public static List<String> unitsOf(@Nullable StyleProperty<?> property) {
        if (property == null) return List.of();
        List<String> out = new ArrayList<>(4);
        for (String unit : new String[] {"px", "%", "em"}) {
            if (parses(property, "1" + unit)) out.add(unit);
        }
        // A LENGTH IS WHAT TAKES A PERCENTAGE. Taking px is not the test: `FloatValue` reads a trailing px and
        // throws it away, so an opacity and a font size answer yes to `1px` and are plain numbers with the kit's
        // own field. Nothing that is not a length reads `1%`.
        if (!out.contains("px") || !out.contains("%")) return List.of();
        if (parses(property, AUTO)) out.add(AUTO);
        return out;
    }

    private static boolean parses(StyleProperty<?> property, String text) {
        try {
            return property.valueParser.parse(text).compute() != null;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /**
     * What this length's units are worth, so a change of unit keeps the length.
     *
     * <pre>{@code
     * field.against(() -> LengthField.hundredPercent(node, WIDTH), () -> LengthField.emOf(node));
     * }</pre>
     *
     * <p>Either may answer {@code NaN} — an element with no parent laid out has no percentage — and a conversion
     * that cannot be measured keeps the number instead.</p>
     */
    public LengthField against(DoubleSupplier hundredPercent, DoubleSupplier em) {
        this.hundredPercent = hundredPercent;
        this.em = em;
        return this;
    }

    /**
     * What 100% of {@code property} is worth on {@code node}, in pixels: <b>the box CSS resolves it against</b>.
     *
     * <p>A size or an inset is a share of the containing block, and a margin or a padding is a share of its WIDTH
     * on both axes — CSS Box Model §8, which is the rule people are surprised by and the one a conversion has to
     * follow to be reversible. A radius or a transform origin is a share of the element's own box instead.</p>
     */
    public static double hundredPercent(@Nullable UIElement node, @Nullable StyleProperty<?> property) {
        if (node == null || property == null) return Double.NaN;
        boolean own = property.name.startsWith("border-") || property.name.startsWith("transform-origin");
        Box box = own ? node.box() : node.parentElement() == null ? null : node.parentElement().box();
        if (box == null) return Double.NaN;
        return VERTICAL.contains(property.name) ? box.height() : box.width();
    }

    /** What one em is worth on {@code node}: its own font size, which is what the engine resolves an em against. */
    public static double emOf(@Nullable UIElement node) {
        if (node == null) return Double.NaN;
        Float size = node.getStyle().computed().get(StylePropertyRegistry.FONT_SIZE);
        return size == null || size <= 0f ? Double.NaN : size;
    }

    /**
     * The properties whose percentage is a share of the containing block's HEIGHT. Everything else on this list's
     * axis — a margin, a padding — is a share of its width, which is CSS's own rule rather than an oversight here.
     */
    private static final Set<String> VERTICAL = Set.of(
            "height", "min-height", "max-height", "top", "bottom", "row-gap", "text-offset-y",
            "border-top-left-radius-y", "border-top-right-radius-y", "border-bottom-right-radius-y",
            "border-bottom-left-radius-y", "transform-origin-y");

    /** The next unit this length may be in, keeping the LENGTH: what a press on the chip does. */
    void cycle() {
        String now = unitOf(getValue());
        String next = units.get((units.indexOf(now) + 1) % units.size());
        double px = pixels();
        double amount = fromPixels(px, next);
        write(Double.isNaN(amount) ? carried() : Math.round(amount * 100d) / 100d, next);
    }

    /** The number the value carries in its own unit, or the last one it did. */
    private double carried() {
        String now = getValue();
        return now == null || now.isBlank() || AUTO.equalsIgnoreCase(now.trim())
                ? fallback(fromPixels(remembered, unitOf(now)), 0d) : amount(now);
    }

    /** The value in pixels, or {@link Double#NaN} when its unit cannot be measured. */
    private double pixels() {
        String now = getValue();
        if (now == null || now.isBlank() || AUTO.equalsIgnoreCase(now.trim())) return remembered;
        return toPixels(amount(now), unitOf(now));
    }

    private double toPixels(double amount, String unit) {
        return switch (unit) {
            case "px" -> amount;
            case "%" -> amount / 100d * hundredPercent.getAsDouble();
            case "em" -> amount * em.getAsDouble();
            default -> remembered;
        };
    }

    private double fromPixels(double px, String unit) {
        if (Double.isNaN(px)) return Double.NaN;
        return switch (unit) {
            case "px" -> px;
            case "%" -> px / hundredPercent.getAsDouble() * 100d;
            case "em" -> px / em.getAsDouble();
            default -> Double.NaN;
        };
    }

    private static double fallback(double value, double otherwise) {
        return Double.isNaN(value) ? otherwise : value;
    }

    /** The row's label scrubs the number, as a plain number row's does. */
    @Override
    public boolean adoptLabel(UIElement label) {
        return number.adoptLabel(label);
    }

    /** The number of a length, or 0 for one that carries none. */
    private static double amount(@Nullable String css) {
        String text = css == null ? "" : css.trim();
        int end = 0;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.'
                || text.charAt(end) == '-' || text.charAt(end) == '+')) {
            end++;
        }
        try {
            return end == 0 ? 0d : Double.parseDouble(text.substring(0, end));
        } catch (NumberFormatException notANumber) {
            return 0d;
        }
    }

    /** The unit a value is in: what follows its number, a keyword as itself, and the first unit for an empty one. */
    private String unitOf(@Nullable String css) {
        String text = css == null ? "" : css.trim();
        if (text.isEmpty()) return units.contains(AUTO) ? AUTO : units.get(0);
        for (String candidate : units) {
            if (AUTO.equals(candidate) ? text.equalsIgnoreCase(AUTO) : text.endsWith(candidate)) return candidate;
        }
        return units.get(0);
    }

    private void write(@Nullable Double amount, String unit) {
        commitAndShow(AUTO.equals(unit) ? AUTO : trim(amount == null ? 0d : amount) + unit);
    }

    /** {@code 12} rather than {@code 12.0}, and {@code 0.5} kept — what a person would have typed. */
    private static String trim(double value) {
        double rounded = Math.round(value * 100d) / 100d;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    @Override
    protected void writeToWidgets(@Nullable String css) {
        String now = unitOf(css);
        unit.setText(now);
        boolean keyword = AUTO.equals(now);
        if (!keyword && css != null && !css.isBlank()) {
            double px = toPixels(amount(css), now);
            if (!Double.isNaN(px)) remembered = px;
        }
        // NO NUMBER ON A KEYWORD: the field goes empty and its placeholder says what the value is.
        number.setInert(keyword);
        number.field().setText(keyword || css == null || css.isBlank() ? "" : trim(amount(css)));
    }
}
