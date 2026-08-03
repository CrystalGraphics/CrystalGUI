package com.crystalgui.graph.shader;

import com.crystalgui.graph.NodeField;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.control.VectorControl;
import com.crystalgui.ui.elements.graph.NodeFieldWidgets;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Puts {@link VectorControl} behind every {@link NodeField.Kind#VECTOR} field — {@code vec2(x, y)}
 * (the only arity {@link ShaderGraphBridge#widgetKindFor} currently maps to this kind; {@code vec3}/
 * {@code vec4} are treated as {@link NodeField.Kind#COLOR}, see that mapping's own comment) typed into
 * X/Y boxes instead of a bare string.
 *
 * <h3>Why the registration lives here and not with the widget</h3>
 * <p>Same reasoning as {@link ShaderColorFieldWidget}: {@code NodeFieldWidgets} does not know GLSL
 * exists, so parsing {@code vec2(...)} is this package's business, not its. Before this class existed,
 * {@link NodeField.Kind#VECTOR} fell through to a bare text field — functional, but a component pair
 * typed as free text rather than as the two numbers it actually is.</p>
 *
 * <h3>Arity is read from the literal, not fixed</h3>
 * <p>{@link NodeField} carries no arity of its own — only a kind — so {@link #parse} counts the
 * comma-separated components in whatever is already there rather than assuming two. That keeps this
 * ready for a {@code vec3}/{@code vec4} field to route here later without a signature change, even
 * though nothing does today.</p>
 */
public final class ShaderVectorFieldWidget {

    private ShaderVectorFieldWidget() {
    }

    /** Registers the control for vector fields. Idempotent. */
    public static void install() {
        NodeFieldWidgets.register(NodeField.Kind.VECTOR, ShaderVectorFieldWidget::build);
    }

    private static UIElement build(NodeField field, String value, Consumer<String> onChange) {
        double[] initial = parse(field.resolve(value));
        ConfigDescriptor descriptor = ConfigDescriptor.vector(field.id(), field.label(), initial.length);
        VectorControl control = new VectorControl(descriptor, initial);
        control.changed.connect(v -> onChange.accept(format((double[]) v)));
        return control;
    }

    /** {@code vecN(a, b, ...)} to its components. Malformed or missing falls back to a 2-vector of
     * zeroes — arity 2 because that is the one shape this kind is used for today (see the class
     * javadoc), and a control needs SOME arity to build with. */
    static double[] parse(String literal) {
        if (literal == null) return new double[] { 0, 0 };
        int open = literal.indexOf('(');
        int close = literal.lastIndexOf(')');
        if (open < 0 || close <= open) return new double[] { 0, 0 };

        String[] parts = literal.substring(open + 1, close).split(",");
        double[] out = new double[Math.max(1, parts.length)];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException malformed) {
                return new double[] { 0, 0 };
            }
        }
        return out;
    }

    /** One component on its own — what a {@code vecN} literal collapses to when a dynamic port narrows
     * back to a scalar. Same fixed precision as {@link #format}, for the same reason. */
    static String formatScalar(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Components back to {@code vecN(...)}, at a fixed precision so an unchanged vector is an
     * unchanged string. */
    static String format(double[] values) {
        StringBuilder out = new StringBuilder("vec").append(values.length).append('(');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(", ");
            out.append(String.format(Locale.ROOT, "%.3f", values[i]));
        }
        return out.append(')').toString();
    }
}
