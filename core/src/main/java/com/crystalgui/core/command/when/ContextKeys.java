package com.crystalgui.core.command.when;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a name means inside a {@link WhenExpression} — VS Code's context keys.
 *
 * <h3>Every {@link DataKey} is already a context key</h3>
 *
 * <p>That is the whole design, and it is why this file is short. VS Code needs an
 * {@code IContextKeyService} because its context is a flat name→value bag that components have to
 * <em>push</em> into; here the context is a {@link DataContext}, which components already answer by name
 * through {@code DataProvider.getData}. So {@code "undoStack"} in an expression resolves to the same
 * {@code UNDO_STACK} key a Java predicate would ask for, with no registration and no second place for the
 * two to disagree.</p>
 *
 * <p>{@link #define} exists for the rest: a name that is a <em>derived</em> fact rather than a subject —
 * {@code "canUndo"}, {@code "hasSelection"} — where there is no key to resolve because the answer is a
 * question asked of one.</p>
 *
 * <h3>Truthiness, and why it is spelled out</h3>
 *
 * <p>An expression says {@code "undoStack && !readOnly"} and never {@code "undoStack != null"}, so every
 * value has to answer yes or no. The rules are JavaScript's, minus the parts nobody wants: null is false,
 * a boolean is itself, an empty string or collection is false, zero is false, and <b>anything else present
 * is true</b>. That last clause is the one that matters — it makes a bare subject name mean "there is
 * one", which is what a menu condition is almost always asking.</p>
 */
public final class ContextKeys {

    private ContextKeys() {
    }

    private static final Map<String, Function<DataContext, Object>> DEFINED = new ConcurrentHashMap<>();

    /**
     * Names a derived fact, for the cases {@link DataKey} cannot express.
     *
     * <p>Replaces any previous definition, like {@code CommandRegistry.register} and for the same reason:
     * overriding a built-in is how a host customises one, and refusing would make that impossible.</p>
     */
    public static void define(String name, Function<DataContext, Object> value) {
        DEFINED.put(name, value);
    }

    /** Forgets a definition. Returns whether there was one. */
    public static boolean undefine(String name) {
        return DEFINED.remove(name) != null;
    }

    /**
     * What {@code name} evaluates to here, or null when nothing answers.
     *
     * <p>A definition wins over a key of the same name — the specific over the general, and the only
     * order that lets a host refine what a built-in key means without renaming it.</p>
     */
    @Nullable
    public static Object resolve(String name, DataContext context) {
        Function<DataContext, Object> defined = DEFINED.get(name);
        if (defined != null) return defined.apply(context);
        DataKey<?> key = DataKey.find(name);
        return key == null ? null : context.get(key);
    }

    /** @see ContextKeys the class note on truthiness */
    public static boolean isTruthy(@Nullable Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        if (value instanceof CharSequence text) return !text.isEmpty();
        if (value instanceof Collection<?> items) return !items.isEmpty();
        if (value instanceof Number number) return number.doubleValue() != 0;
        return true;
    }

    /**
     * Whether {@code value} equals the literal {@code text}.
     *
     * <p>Compared as <b>text</b>, deliberately. An expression is data — it may have come from a resource
     * — so it has no types of its own, and {@code "language == 'java'"} must work whether the provider
     * answered with a {@code String}, an enum constant or something with a {@code toString}. A boolean
     * literal is the one carve-out, so {@code "readOnly == false"} means what it looks like rather than
     * comparing against the four characters.</p>
     */
    public static boolean matches(@Nullable Object value, String text) {
        if ("true".equals(text) || "false".equals(text)) {
            return isTruthy(value) == "true".equals(text);
        }
        return value != null && String.valueOf(value).equals(text);
    }

    /** For tests, which must not inherit definitions from each other. */
    public static void resetForTesting() {
        DEFINED.clear();
    }
}
