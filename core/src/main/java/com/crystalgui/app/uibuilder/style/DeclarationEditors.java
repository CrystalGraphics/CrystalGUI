package com.crystalgui.app.uibuilder.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.style.property.general.ints.IntProperty;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.control.ColorControl;

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
 * color is a swatch, a bounded number a slider, an enum a dropdown, a flag a checkbox; <b>anything else is
 * a text row that validates through the property's own parser</b>, so a value the engine cannot read is
 * refused at the field instead of being written into a sheet. Nothing is ever skipped: a property
 * registered this morning is editable this afternoon, at worst as text.</p>
 *
 * <p>Values here are CSS <b>text</b> in and out — what a sheet holds and what an inline style holds. A
 * control that wants a number or a color is given a mapped view, and a value it produces that the property
 * cannot parse is never written.</p>
 */
public final class DeclarationEditors {

    /**
     * A descriptor and the property to bind it to — what {@code ConfigForm.prop} takes — or a control the
     * editor built itself, which is what a lab-backed row is.
     */
    public record Field(ConfigDescriptor descriptor, Property<?> value, @Nullable ConfigControl control) {

        public Field(ConfigDescriptor descriptor, Property<?> value) {
            this(descriptor, value, null);
        }
    }

    /**
     * Everything a bespoke editor needs: the declaration, and the target it is part of.
     *
     * @param name   the declaration as the sheet spells it, which is also what the row is labelled -- the property's
     *               own name, or the shorthand for a row no property claims
     * @param fields what writes to the target — a lab that edits SEVERAL declarations at once (the corners)
     *               writes through this rather than through {@code css}
     * @param node   the element being styled, for a lab that measures it
     */
    public record Context(@Nullable StyleProperty<?> property, String id, String name, Property<String> css,
                          @Nullable StyleFields fields, @Nullable UIElement node) {
    }

    /** A bespoke editor for one property: the gradient bar, the corner box, the glass sliders. */
    @FunctionalInterface
    public interface Editor {
        Field build(Context context);
    }

    private static final Map<StyleProperty<?>, Editor> OVERRIDES = new HashMap<>();

    /** Editors for a name no property claims: a shorthand a sheet writes, such as {@code text-stroke}. */
    private static final Map<String, Editor> NAMED = new HashMap<>();

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

    /**
     * Gives a declaration NAME with no registered property an editor — a shorthand the sheet spells and the
     * registry does not hold. Its context's {@code property} is null and its {@code name} is this one.
     *
     * <pre>{@code
     * DeclarationEditors.register("text-stroke", context -> strokeChip(context));
     * }</pre>
     */
    public static void register(String name, Editor editor) {
        NAMED.put(name, editor);
    }

    /**
     * A number field in {@code unit}, bound to a declaration that spells a bare number — what a size row is.
     *
     * <pre>{@code
     * DeclarationEditors.register(FONT_SIZE, context -> DeclarationEditors.number(context, "px"));
     * }</pre>
     */
    public static Field number(Context context, String unit) {
        StyleProperty<?> property = context.property();
        ConfigDescriptor descriptor = ConfigDescriptor.number(context.id(), context.name()).unit(unit)
                .tooltip(context.name());
        return new Field(descriptor, property == null ? context.css() : numeric(property, context.css()));
    }

    /**
     * {@link #number(Context, String)} over a range, which the kit draws as a slider beside its field.
     *
     * <pre>{@code
     * DeclarationEditors.register(FONT_SIZE, context -> DeclarationEditors.number(context, "px", 6f, 96f));
     * }</pre>
     */
    public static Field number(Context context, String unit, float min, float max) {
        Field plain = number(context, unit);
        return new Field(plain.descriptor().range(min, max).integral(true), plain.value());
    }

    /**
     * The field for a declaration of {@code property}, bound to its CSS text.
     *
     * @param css the declaration's value, read and written as a sheet spells it — {@link StyleFields#value}
     */
    public static Field of(@Nullable StyleProperty<?> property, String id, String name, Property<String> css) {
        return of(property, id, name, css, null, null);
    }

    /** As {@link #of(StyleProperty, String, String, Property)}, for a lab that needs the whole target. */
    public static Field of(@Nullable StyleProperty<?> property, String id, String name, Property<String> css,
                           @Nullable StyleFields fields, @Nullable UIElement node) {
        if (property == null) {
            Editor named = NAMED.get(name);
            if (named != null) return named.build(new Context(null, id, name, css, fields, node));
            // A name no property claims -- a custom property, or a typo somebody wrote. It is in the file, so
            // it is shown and editable; nothing can validate it.
            return new Field(ConfigDescriptor.text(id, name).placeholder("value"), css);
        }
        Editor editor = OVERRIDES.get(property);
        if (editor != null) return editor.build(new Context(property, id, name, css, fields, node));
        return byType(property, id, name, css);
    }

    /** The field a property gets when nothing bespoke is registered for it. */
    private static Field byType(StyleProperty<?> property, String id, String label, Property<String> css) {
        String tooltip = property.name + (property.getAuthoredThrough() == null ? ""
                : " — written as " + property.getAuthoredThrough());

        if (property instanceof ColorProperty color) {
            ConfigDescriptor descriptor = ConfigDescriptor.color(id, label).tooltip(tooltip);
            Property<Integer> argb = css.map(text -> parsed(color, text, 0), value -> hex(value));
            ColorControl control = new ColorControl(descriptor, null);
            // ZERO IS "THE TEXT'S OWN COLOR" for these two, which a transparent swatch misreports.
            if (property == StylePropertyRegistry.CARET_COLOR || property == StylePropertyRegistry.TEXT_DECORATION_COLOR) {
                control.zeroLabel("currentColor");
            }
            control.bind(argb);
            return new Field(descriptor, argb, control);
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
        // SHOWN AS A PERSON READS IT, `52px` rather than the writer's `52.0px`, which is also valid to write back.
        return new Field(ConfigDescriptor.text(id, label).tooltip(tooltip).placeholder("value")
                .validator(text -> text == null || text.isBlank() || parses(property, text)),
                css.map(CssValues::readable, typed -> typed));
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

    /** A color as a sheet spells it. Eight digits only when there is transparency to state. */
    private static String hex(@Nullable Integer argb) {
        return argb == null ? "" : CssValues.color(argb);
    }
}
