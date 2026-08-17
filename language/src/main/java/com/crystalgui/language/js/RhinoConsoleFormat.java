package com.crystalgui.language.js;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

/**
 * What {@code console.log(value)} prints — Node's {@code util.inspect}, to one level.
 *
 * <h3>Why not {@code Context.toString}</h3>
 *
 * <p>That is JavaScript's own {@code String(value)}, which is right for a string and a number and wrong
 * for everything an author actually wants to look at: an array prints as {@code 1,2,3}, an object as
 * {@code [object Object]}, and a function as its whole source. Every JavaScript console people have used
 * prints {@code [ 1, 2, 3 ]}, {@code { a: 1, b: 'x' }} and {@code [Function: name]}, and a console that
 * did otherwise would read as broken rather than as different.</p>
 *
 * <p><b>One level, deliberately.</b> Nested containers print as {@code [Array]} and {@code [Object]},
 * exactly as Node does at its default depth for the level below the one it expands — a graph with a cycle
 * would otherwise never finish, and a console row is one line. Strings are quoted <em>inside</em> a
 * container and bare at the top level, which is also Node's rule and is what makes
 * {@code console.log('hello')} print {@code hello} rather than {@code 'hello'}.</p>
 *
 * <p>Java objects — a binding the host put in scope, a value from {@code Java.type} — print by their own
 * {@code toString()}, because that is what their author wrote for exactly this purpose. A Java class
 * prints as {@code [JavaClass java.util.ArrayList]}, since its {@code toString()} says {@code class
 * java.util.ArrayList} and reads as a typo.</p>
 */
final class RhinoConsoleFormat {

    /** How many entries of a container are shown before {@code ... N more items}. Node's default. */
    private static final int MAX_ENTRIES = 100;

    private RhinoConsoleFormat() {
    }

    /** {@code console.log(a, b, c)} — each formatted at the top level, joined by one space. */
    static String line(Object[] arguments) {
        if (arguments == null || arguments.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) out.append(' ');
            out.append(topLevel(arguments[i]));
        }
        return out.toString();
    }

    /** One value as {@code console.log} shows it on its own — a string bare, everything else inspected. */
    static String topLevel(Object value) {
        if (value instanceof CharSequence) return value.toString();
        return inspect(value, 0);
    }

    private static String inspect(Object value, int depth) {
        if (value == null) return "null";
        if (Undefined.isUndefined(value)) return "undefined";
        // `ConsString` IS a CharSequence, which is the whole reason Rhino's rope type can be passed around
        // as one -- naming it separately said the opposite.
        if (value instanceof CharSequence) return quote(value.toString());
        if (value instanceof Number || value instanceof Boolean) return Context.toString(value);
        // A JAVA VALUE IS TOLD FROM A JAVA CLASS BY WHAT IT WRAPS, never by the wrapper's own type: under
        // a member-name mapping every one of them arrives inside a membrane and none is a NativeJavaClass,
        // so a class printed through its own `toString()` as `class java.util.ArrayList` -- which is
        // exactly the "reads as a typo" this branch exists to prevent.
        if (value instanceof Wrapper) {
            Object unwrapped = ((Wrapper) value).unwrap();
            if (unwrapped instanceof Class) return "[JavaClass " + ((Class<?>) unwrapped).getName() + "]";
            return String.valueOf(unwrapped);
        }
        if (value instanceof Function) return function((Function) value);
        if (value instanceof NativeArray) return array((NativeArray) value, depth);
        if (value instanceof Scriptable) {
            Scriptable object = (Scriptable) value;
            // A PLAIN OBJECT IS WALKED; anything with its own class -- Error, Date, RegExp, a Map -- says
            // what it is through JavaScript's own String(), which is what Node prints for those too.
            if ("Object".equals(object.getClassName())) return object(object, depth);
            return Context.toString(value);
        }
        // A Java value that reached the script unwrapped -- a String is caught above, so this is a host
        // object handed over as itself.
        return String.valueOf(value);
    }

    private static String function(Function function) {
        String name = function instanceof BaseFunction ? ((BaseFunction) function).getFunctionName() : "";
        return name == null || name.isEmpty() ? "[Function (anonymous)]" : "[Function: " + name + "]";
    }

    private static String array(NativeArray array, int depth) {
        if (depth > 0) return "[Array]";
        long length = array.getLength();
        if (length == 0) return "[]";
        StringBuilder out = new StringBuilder("[ ");
        long shown = Math.min(length, MAX_ENTRIES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            Object element = array.get(i, array);
            // A HOLE IS NOT undefined. `[ , 1 ]` has no element 0 at all, and Node says so.
            out.append(element == Scriptable.NOT_FOUND ? "<empty>" : inspect(element, depth + 1));
        }
        if (length > shown) out.append(", ... ").append(length - shown).append(" more items");
        return out.append(" ]").toString();
    }

    private static String object(Scriptable object, int depth) {
        if (depth > 0) return "[Object]";
        Object[] ids = object.getIds();
        if (ids.length == 0) return "{}";
        StringBuilder out = new StringBuilder("{ ");
        int shown = Math.min(ids.length, MAX_ENTRIES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            Object id = ids[i];
            Object member = id instanceof Integer
                    ? ScriptableObject.getProperty(object, (Integer) id)
                    : ScriptableObject.getProperty(object, String.valueOf(id));
            out.append(key(id)).append(": ").append(inspect(member, depth + 1));
        }
        if (ids.length > shown) out.append(", ... ").append(ids.length - shown).append(" more items");
        return out.append(" }").toString();
    }

    /** A key is bare when it is an identifier and quoted otherwise — {@code { a: 1, 'b-c': 2 }}. */
    private static String key(Object id) {
        String name = String.valueOf(id);
        if (id instanceof Integer) return name;
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return quote(name);
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return quote(name);
        }
        return name;
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\'': out.append("\\'"); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        return out.append('\'').toString();
    }
}
