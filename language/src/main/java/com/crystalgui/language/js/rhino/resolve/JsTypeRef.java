package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.text.lang.TypeRef;

import javax.annotation.Nullable;

import java.util.List;

/**
 * A JavaScript type as this engine can know one — a name, and whether a Java class is behind it.
 *
 * <h3>Three cases, and the third is why this is not just {@code TypeRef.of}</h3>
 *
 * <ul>
 *   <li>A <b>JavaScript</b> pseudo-type: {@code string}, {@code number}, {@code Array}, {@code Object},
 *       {@code function}. Nothing is behind the name; the members come from the standard prototype.</li>
 *   <li>A Java <b>instance</b> — {@code new java.util.ArrayList()} — whose members are the Java engine's
 *       answer for that class.</li>
 *   <li>A Java <b>class object</b> — what {@code Java.type("a.b.C")} and a bare {@code java.util.List}
 *       evaluate to. Same name, and the members are the <em>statics</em>. Encoding it as a flag rather
 *       than as a different name keeps {@code qualifiedName()} the thing a cache should be keyed on,
 *       which is what {@link TypeRef} asks of it.</li>
 * </ul>
 */
public final class JsTypeRef implements TypeRef {

    /** What a JavaScript value's type is called when there is no Java class behind it. */
    static final String STRING = "string";
    static final String NUMBER = "number";
    static final String BOOLEAN = "boolean";
    static final String FUNCTION = "function";
    static final String ARRAY = "Array";
    static final String OBJECT = "Object";
    static final String REGEXP = "RegExp";
    static final String UNDEFINED = "undefined";
    static final String NULL = "null";

    private final String display;
    private final String qualified;
    private final boolean java;
    private final boolean staticSide;

    /**
     * The property names an object carries, when they are knowable — an object literal's, or a live
     * object's own ids after a run. Empty for every other type.
     *
     * <p><b>Final, like everything else here.</b> It began as a field assigned after construction, which
     * is a trap in a value type: two literals with different properties share
     * {@code qualifiedName() == "Object"}, so anything keying a cache on the qualified name — which
     * {@link TypeRef} explicitly asks callers to do — would hand one literal's members to another.</p>
     */
    private final List<String> keys;

    private JsTypeRef(String display, String qualified, boolean java, boolean staticSide,
                      @Nullable List<String> keys) {
        this.display = display;
        this.qualified = qualified;
        this.java = java;
        this.staticSide = staticSide;
        this.keys = keys == null || keys.isEmpty() ? List.of() : List.copyOf(keys);
    }

    /** A JavaScript type that is only a name. */
    static JsTypeRef js(String name) {
        return new JsTypeRef(name, name, false, false, null);
    }

    /**
     * An object whose properties are known — a literal's, or what a run found on it.
     *
     * <p>The names travel on the type because that is the only place they can: {@code membersOf} is handed a
     * {@code TypeRef} and an offset, and re-deriving "which object was this" from the offset would mean the
     * resolver answering the same question twice — once to type the receiver and once to list it.</p>
     */
    static JsTypeRef object(@Nullable List<String> keys) {
        return new JsTypeRef(OBJECT, OBJECT, false, false, keys);
    }

    /** The properties this object is known to have, or empty. */
    List<String> keys() {
        return keys;
    }

    /** An instance of a Java class — its instance members are what it offers. */
    static JsTypeRef javaInstance(String binaryName) {
        return new JsTypeRef(binaryName, binaryName, true, false, null);
    }

    /** The Java class object itself — {@code Java.type("a.b.C")} — offering its statics. */
    static JsTypeRef javaClass(String binaryName) {
        return new JsTypeRef(binaryName, binaryName, true, true, null);
    }

    /** Whether a Java class is behind this name. */
    boolean isJava() {
        return java;
    }

    /** Whether this is the class object rather than an instance of it. */
    boolean isStaticSide() {
        return staticSide;
    }

    @Override
    public String displayName() {
        return display;
    }

    @Override
    public String qualifiedName() {
        return qualified;
    }

    @Override
    public String toString() {
        return display;
    }

    /** Whether {@code type} came from this engine and names a Java class. */
    static boolean isJava(TypeRef type) {
        return type instanceof JsTypeRef && ((JsTypeRef) type).java;
    }

    /**
     * The Java binary name a {@code TypeRef} carries, or null.
     *
     * <p>Accepts a plain {@link TypeRef} too — a JSDoc {@code {java.util.List}} arrives as one, and so
     * does anything the Java engine handed back — so a dotted qualified name is taken at its word.</p>
     */
    static String javaNameOf(TypeRef type) {
        if (type == null) return null;
        if (type instanceof JsTypeRef) return ((JsTypeRef) type).java ? type.qualifiedName() : null;
        String qualified = type.qualifiedName();
        return qualified != null && qualified.indexOf('.') > 0 ? qualified : null;
    }
}
