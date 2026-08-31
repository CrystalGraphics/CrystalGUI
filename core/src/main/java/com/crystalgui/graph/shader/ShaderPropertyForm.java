package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgShaderType;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.core.config.ConfigDescriptor;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * How a property's <b>default value</b> is edited and stored — the one place that knows the literal form.
 *
 * <p>Unity reference: {@code docs/research/unity-blackboard/12-property-vector2-settings.png}, where
 * {@code Default} is a typed editor rather than a text box — two number fields for a Vector 2, a swatch
 * for a Colour. That is the whole reason this class exists: the row cannot be one fixed kind.</p>
 *
 * <h3>A property literal is NOT the same text as a port literal</h3>
 * <p>{@code ShaderColorFieldWidget} formats {@code vec4(1.000, 0.000, ...)}, which is GLSL and is what a
 * port default has to be. A {@code Properties} block takes a bare parenthesised tuple —
 * {@code (1,0,0,1)} — and rejects the constructor form. The two look close enough to be confused and are
 * consumed by different parsers, so the conversions live apart on purpose.</p>
 *
 * <h3>Vector 3 stores four components and shows three</h3>
 * <p>Because it is <b>declared</b> as {@code vec4} — the parser bans {@code vec3} as a property type over
 * STD140 alignment, which {@code CgShaderType.propertyDeclarationType} absorbs. The stored default must
 * therefore be four-wide to be a valid {@code vec4}, while the editor shows the three components the
 * graph actually reads. Padding happens here so nothing else has to know.</p>
 */
public final class ShaderPropertyForm {

    private ShaderPropertyForm() {
    }

    /** The row id every default editor uses, so the inspector can find its control again. */
    public static final String DEFAULT_ID = "property.default";

    /**
     * What kind of control edits {@code property}'s default.
     *
     * <p>A sampler gets a plain text row: its default is a fallback <em>name</em> ({@code "white"},
     * {@code "black"}), not a value, and there is no asset browser to pick one with.</p>
     */
    public static ConfigDescriptor describeDefault(GraphProperty property) {
        if (BlackboardPanel.KIND_COLOR.equals(property.option(BlackboardPanel.KIND_OPTION))) {
            return ConfigDescriptor.color(DEFAULT_ID, "Default");
        }
        CgShaderType type = CgShaderType.parse(property.typeId());
        if (type == null) return ConfigDescriptor.text(DEFAULT_ID, "Default");

        switch (type) {
            case BOOL:
                return ConfigDescriptor.bool(DEFAULT_ID, "Default");
            case FLOAT:
            case INT:
                return ConfigDescriptor.number(DEFAULT_ID, "Default").integral(type == CgShaderType.INT);
            case VEC2:
            case VEC3:
            case VEC4:
                return ConfigDescriptor.vector(DEFAULT_ID, "Default", type.components());
            default:
                // Every sampler kind, plus anything a later build adds that this does not know yet.
                return ConfigDescriptor.text(DEFAULT_ID, "Default");
        }
    }

    /** The stored default, in the shape {@link #describeDefault}'s control expects. */
    @Nullable
    public static Object readDefault(GraphProperty property) {
        String stored = property.defaultValue();
        if (BlackboardPanel.KIND_COLOR.equals(property.option(BlackboardPanel.KIND_OPTION))) {
            return toArgb(components(stored, 4));
        }
        CgShaderType type = CgShaderType.parse(property.typeId());
        if (type == null) return stored;

        switch (type) {
            case BOOL:
                return Boolean.parseBoolean(stored.trim());
            case FLOAT:
            case INT:
                return scalar(stored);
            case VEC2:
            case VEC3:
            case VEC4:
                return components(stored, type.components());
            default:
                return unquote(stored);
        }
    }

    /**
     * A control's value, back as the literal the {@code Properties} block takes.
     *
     * <p>Returns null when the value is not one this property can hold, so a caller writes nothing rather
     * than storing something the shader parser will reject.</p>
     */
    @Nullable
    public static String writeDefault(GraphProperty property, @Nullable Object value) {
        if (value == null) return null;
        if (BlackboardPanel.KIND_COLOR.equals(property.option(BlackboardPanel.KIND_OPTION))) {
            return value instanceof Integer argb ? fromArgb(argb) : null;
        }
        CgShaderType type = CgShaderType.parse(property.typeId());
        if (type == null) return String.valueOf(value);

        switch (type) {
            case BOOL:
                return String.valueOf(Boolean.TRUE.equals(value));
            case INT:
                return value instanceof Number n ? String.valueOf(n.intValue()) : null;
            case FLOAT:
                return value instanceof Number n ? trim(n.doubleValue()) : null;
            case VEC2:
            case VEC3:
            case VEC4:
                if (!(value instanceof double[] parts)) return null;
                // PADDED TO THE DECLARED WIDTH, which is 4 for a VEC3 -- see the class note.
                int declared = type == CgShaderType.VEC3 ? 4 : type.components();
                return tuple(parts, declared);
            default:
                return quote(String.valueOf(value));
        }
    }

    /**
     * The property's default as a <b>GLSL</b> literal, or null for a type that cannot be one.
     *
     * <p>Not the same text as the stored default, and that is the point: a {@code Properties} block takes
     * {@code (0,0,0,1)} while GLSL needs {@code vec4(0,0,0,1)}. Two vocabularies, one value.</p>
     *
     * <p>Null for every sampler kind — a texture cannot be written as a literal at all, so there is
     * nothing to substitute and a caller has to fall back to the uniform.</p>
     */
    @Nullable
    public static String glslLiteral(GraphProperty property) {
        String stored = property.defaultValue();
        CgShaderType type = CgShaderType.parse(property.typeId());
        if (type == null) return null;

        switch (type) {
            case BOOL:
                return String.valueOf(Boolean.parseBoolean(stored.trim()));
            case INT:
                return String.valueOf((int) scalar(stored));
            case FLOAT:
                return trim(scalar(stored));
            case VEC2:
            case VEC3:
            case VEC4: {
                // The COMPONENT count, not the declared width: a VEC3 stores four (it is declared vec4)
                // and reads three, so the literal a graph consumes is three-wide.
                int width = type.components();
                double[] parts = components(stored, width);
                StringBuilder out = new StringBuilder(type.glsl()).append('(');
                for (int i = 0; i < width; i++) {
                    if (i > 0) out.append(", ");
                    out.append(trim(parts[i]));
                }
                return out.append(')').toString();
            }
            default:
                return null;
        }
    }

    // ── Literal forms ───────────────────────────────────────────────────────

    /** {@code "(1,0,0,1)"} → {@code [1,0,0,1]}, padded or truncated to {@code want} components. */
    static double[] components(@Nullable String literal, int want) {
        double[] out = new double[Math.max(1, want)];
        if (literal == null) return out;
        String body = literal.trim();
        int open = body.indexOf('(');
        if (open >= 0) body = body.substring(open + 1);
        int close = body.lastIndexOf(')');
        if (close >= 0) body = body.substring(0, close);

        String[] parts = body.split(",");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException malformed) {
                // A stored value a later build wrote differently degrades to zero rather than throwing
                // out of a panel that is only trying to draw itself.
                out[i] = 0d;
            }
        }
        return out;
    }

    static double scalar(@Nullable String literal) {
        if (literal == null) return 0d;
        try {
            return Double.parseDouble(literal.trim());
        } catch (NumberFormatException malformed) {
            return 0d;
        }
    }

    static String tuple(double[] parts, int width) {
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < width; i++) {
            if (i > 0) out.append(',');
            out.append(trim(i < parts.length ? parts[i] : 0d));
        }
        return out.append(')').toString();
    }

    /** Trailing zeros stripped, so a default reads {@code 0.5} rather than {@code 0.500000}. */
    static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.4f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    static int toArgb(double[] rgba) {
        return (channel(rgba, 3, 1d) << 24) | (channel(rgba, 0, 0d) << 16)
                | (channel(rgba, 1, 0d) << 8) | channel(rgba, 2, 0d);
    }

    private static int channel(double[] rgba, int index, double fallback) {
        double value = index < rgba.length ? rgba[index] : fallback;
        return (int) Math.round(Math.max(0d, Math.min(1d, value)) * 255d);
    }

    static String fromArgb(int argb) {
        return "(" + trim(((argb >> 16) & 0xFF) / 255d) + ","
                + trim(((argb >> 8) & 0xFF) / 255d) + ","
                + trim((argb & 0xFF) / 255d) + ","
                + trim(((argb >>> 24) & 0xFF) / 255d) + ")";
    }

    /** A sampler default is a quoted fallback name in the shader; the editor shows it unquoted. */
    static String unquote(@Nullable String literal) {
        String text = literal == null ? "" : literal.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    static String quote(String name) {
        String bare = unquote(name);
        return "\"" + bare + "\"";
    }
}
