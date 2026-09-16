package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.style.property.general.ints.IntProperty;
import com.crystalgui.style.property.visual.color.ColorProperty;

/**
 * The control a style property gets, resolved from the property itself.
 *
 * <pre>{@code
 * DeclarationEditors.Field field = DeclarationEditors.of(property, fields.value(property.name));
 * form.prop(field.descriptor(), field.value());
 *
 * DeclarationEditors.register(StylePropertyRegistry.BACKGROUND, new GradientEditor());   // a bespoke one
 * }</pre>
 *
 * <p>By TYPE, with per-property overrides — the same shape as the Element tab's {@code descriptorFor}. A
 * colour is a swatch, a bounded number a slider, an enum a dropdown, a flag a checkbox; <b>anything else is
 * a text row that validates through the property's own parser</b>, so a value the engine cannot read is
 * refused at the field instead of being written into a sheet. Nothing is ever skipped: a property
 * registered this morning is editable this afternoon, at worst as text.</p>
 *
 * <p>Values here are CSS <b>text</b> in and out — what a sheet holds and what an inline style holds. A
 * control that wants a number or a colour is given a mapped view, and a value it produces that the property
 * cannot parse is never written.</p>
 */
public final class DeclarationEditors {

    /** A descriptor and the property to bind it to — what {@code ConfigForm.prop} takes. */
    public record Field(ConfigDescriptor descriptor, Property<?> value) {
    }

    /** A bespoke editor for one property: the gradient bar, the corner box, the glass sliders. */
    @FunctionalInterface
    public interface Editor {
        Field build(StyleProperty<?> property, String id, String label, Property<String> css);
    }

    private static final Map<StyleProperty<?>, Editor> OVERRIDES = new HashMap<>();

    private DeclarationEditors() {
    }

    /**
     * Gives {@code property} an editor of its own, replacing the type-driven default.
     *
     * <p>Process-wide and last-in-wins, like a widget kind: a layer registering the gradient bar means
     * every Styles pane in the process gets it.</p>
     */
    public static void register(StyleProperty<?> property, Editor editor) {
        OVERRIDES.put(property, editor);
    }

    /** Forgets a registered editor, back to the type-driven default. */
    public static void unregister(StyleProperty<?> property) {
        OVERRIDES.remove(property);
    }

    /** Whether {@code property} has an editor of its own. */
    public static boolean hasEditor(StyleProperty<?> property) {
        return OVERRIDES.containsKey(property);
    }

    /**
     * The field for a declaration of {@code property}, bound to its CSS text.
     *
     * @param css the declaration's value, read and written as a sheet spells it — {@link StyleFields#value}
     */
    public static Field of(@Nullable StyleProperty<?> property, String id, String label, Property<String> css) {
        if (property == null) {
            // A name no property claims -- a custom property, or a typo somebody wrote. It is in the file, so
            // it is shown and editable; nothing can validate it.
            return new Field(ConfigDescriptor.text(id, label).placeholder("value"), css);
        }
        Editor editor = OVERRIDES.get(property);
        if (editor != null) return editor.build(property, id, label, css);
        return byType(property, id, label, css);
    }

    /** The field a property gets when nothing bespoke is registered for it. */
    private static Field byType(StyleProperty<?> property, String id, String label, Property<String> css) {
        String tooltip = property.name + (property.getAuthoredThrough() == null ? ""
                : " — written as " + property.getAuthoredThrough());

        if (property instanceof ColorProperty colour) {
            return new Field(ConfigDescriptor.color(id, label).tooltip(tooltip),
                    css.map(text -> parsed(colour, text, 0), value -> hex(value)));
        }
        if (property.type == Boolean.class) {
            return new Field(ConfigDescriptor.bool(id, label).tooltip(tooltip),
                    css.map(text -> Boolean.TRUE.equals(parsed(property, text, Boolean.FALSE)),
                            value -> String.valueOf(value)));
        }
        if (property.type.isEnum()) {
            List<String> keywords = keywordsOf(property);
            return new Field(ConfigDescriptor.select(id, label, keywords).tooltip(tooltip),
                    css.map(text -> keyword(keywords, text), value -> value == null ? "" : value));
        }
        if (property instanceof FloatProperty number) {
            ConfigDescriptor descriptor = ConfigDescriptor.number(id, label).tooltip(tooltip);
            // A RANGE MAKES IT A SLIDER, which is only honest when the property actually has one: the
            // defaults are the float limits, and a slider from -3.4e38 to 3.4e38 is a fiction.
            if (bounded(number.getMin(), number.getMax())) {
                descriptor = descriptor.range(number.getMin(), number.getMax());
            }
            return new Field(descriptor, numeric(property, css));
        }
        if (property instanceof IntProperty) {
            return new Field(ConfigDescriptor.number(id, label).integral(true).tooltip(tooltip), numeric(property, css));
        }
        // Lengths, drawables, transforms, gradients, fonts: text until a lab is built for them, and
        // validated so an unparseable value never reaches a sheet.
        return new Field(ConfigDescriptor.text(id, label).tooltip(tooltip).placeholder("value")
                .validator(text -> text == null || text.isBlank() || parses(property, text)), css);
    }

    /** A number property as a number, refusing anything its own parser will not read. */
    private static Property<Double> numeric(StyleProperty<?> property, Property<String> css) {
        return css.map(text -> {
            Object value = parsed(property, text, null);
            return value instanceof Number number ? number.doubleValue() : 0d;
        }, value -> value == null ? "" : trim(value));
    }

    /** {@code 12} rather than {@code 12.0}, and {@code 0.25} kept — what a person would have typed. */
    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static boolean bounded(float min, float max) {
        return min > -Float.MAX_VALUE && max < Float.MAX_VALUE;
    }

    /** The CSS keywords of an enum property, as the property itself writes them. */
    private static List<String> keywordsOf(StyleProperty<?> property) {
        List<String> keywords = new ArrayList<>();
        for (Object constant : property.type.getEnumConstants()) keywords.add(write(property, constant));
        return keywords;
    }

    /** The keyword a value's text names, or the first — a select cannot show what it has no option for. */
    private static String keyword(List<String> keywords, @Nullable String text) {
        if (text != null) {
            String trimmed = text.trim();
            for (String keyword : keywords) {
                if (keyword.equalsIgnoreCase(trimmed)) return keyword;
            }
        }
        return keywords.isEmpty() ? "" : keywords.get(0);
    }

    /** Whether the property's own parser reads {@code text}. The one validity test there is. */
    public static boolean parses(StyleProperty<?> property, String text) {
        return parsed(property, text, null) != null;
    }

    /** {@code text} as the property's value, or {@code fallback} when it does not parse. */
    @Nullable
    private static <T> T parsed(StyleProperty<?> property, @Nullable String text, @Nullable T fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            @SuppressWarnings("unchecked")
            T value = (T) property.valueParser.parse(text.trim()).compute();
            return value != null ? value : fallback;
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static String write(StyleProperty<?> property, Object value) {
        return ((StyleProperty<Object>) property).write(value);
    }

    /** A colour as a sheet spells it. Eight digits only when there is transparency to state. */
    private static String hex(@Nullable Integer argb) {
        if (argb == null) return "";
        int value = argb;
        return (value >>> 24) == 0xFF
                ? String.format("#%06X", value & 0xFFFFFF)
                : String.format("#%08X", value);
    }
}
