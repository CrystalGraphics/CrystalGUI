package com.crystalgui.template;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgui.ui.dom.UIElement;

/**
 * Fills an owner's {@link Bound} fields from an inflated document.
 *
 * <pre>{@code
 * UIElement tree = UiTemplates.load("mymod:ui/status").inflate();
 * panel.append(tree);
 * TemplateBinder.bind(panel, tree);
 * }</pre>
 *
 * <p>Called for you by {@code UiType.build} when the panel class carries {@link UiTemplate.Source}, and
 * by {@link UiTemplate#inflateInto}. The field list is worked out once per class and cached — a bind is
 * a lookup per field, not a reflection scan.</p>
 */
public final class TemplateBinder {

    private TemplateBinder() {
    }

    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    /**
     * Resolves every {@link Bound} field of {@code owner} out of {@code tree}.
     *
     * @throws IllegalStateException naming the field when a required id is missing, the element is the
     *                               wrong type, or the field was already assigned
     */
    public static void bind(Object owner, UIElement tree) {
        for (Field field : fieldsOf(owner.getClass())) {
            Bound declared = field.getAnnotation(Bound.class);
            String id = declared.value().isEmpty() ? field.getName() : declared.value();
            UIElement found = tree.getElementById(id);
            if (found == null) {
                if (declared.optional()) continue;
                throw new IllegalStateException(owner.getClass().getSimpleName() + "." + field.getName()
                        + " is @Bound to id \"" + id + "\", which the document has not got");
            }
            if (!field.getType().isInstance(found)) {
                throw new IllegalStateException(owner.getClass().getSimpleName() + "." + field.getName()
                        + " is a " + field.getType().getSimpleName() + " and \"" + id + "\" is a <"
                        + found.tagName() + ">");
            }
            try {
                // TWO OWNERS OF ONE PART. A field with an initializer AND a @Bound is a widget built
                // twice, one of which is in the tree; refused here rather than left to whichever wins.
                if (field.get(owner) != null) {
                    throw new IllegalStateException(owner.getClass().getSimpleName() + "."
                            + field.getName() + " is @Bound and also assigned by an initializer");
                }
                field.set(owner, found);
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException("cannot write " + field, unreachable);
            }
        }
    }

    /** Whether anything on this class asks to be bound — so a panel without a document pays nothing. */
    public static boolean binds(Class<?> type) {
        return !fieldsOf(type).isEmpty();
    }

    /**
     * The annotated fields of {@code type} and its supertypes, made accessible once.
     *
     * <p>Reflection rather than a {@code VarHandle} per field, which is what this would be on a modern
     * runtime: {@code core/} compiles to Java 8 bytecode, where there is no such thing. The scan is what
     * matters and it happens once per class.</p>
     */
    private static List<Field> fieldsOf(Class<?> type) {
        return FIELDS.computeIfAbsent(type, TemplateBinder::collect);
    }

    private static List<Field> collect(Class<?> type) {
        List<Field> found = new ArrayList<>();
        for (Class<?> each = type; each != null && each != Object.class; each = each.getSuperclass()) {
            for (Field field : each.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Bound.class)) continue;
                if (Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException(field + " is @Bound and static; a bound part "
                            + "belongs to one panel, not to the class");
                }
                if (!UIElement.class.isAssignableFrom(field.getType())) {
                    throw new IllegalStateException(field + " is @Bound and is not a UIElement");
                }
                field.setAccessible(true);
                found.add(field);
            }
        }
        return List.copyOf(found);
    }
}
