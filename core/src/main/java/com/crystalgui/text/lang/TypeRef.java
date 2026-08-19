package com.crystalgui.text.lang;

/**
 * A type, as far as anything outside the engine needs to know.
 *
 * <h3>An interface rather than a string, and that is the whole point of this file</h3>
 *
 * <p>The obvious shape for a seam like this is {@code String typeName}. It is also lossy in the one
 * direction that matters: an engine holding an ECJ {@code ITypeBinding} would have to stringify it to
 * answer {@code resolveAt}, and then {@code membersOf} would have to parse the string back into something
 * it can enumerate. {@code List<String>} survives that round trip as text and not as a type — the members
 * come back as {@code E get(int)} rather than {@code String get(int)}, because the substitution was thrown
 * away at the boundary and cannot be recovered from the name.</p>
 *
 * <p>So the engine's own representation travels, behind an interface that exposes only what a
 * <em>consumer</em> reads. A hover reads {@link #displayName()}; a completion list reads it too; nothing
 * outside the engine ever needs more. When the engine is handed one back — {@link Resolver#membersOf} —
 * it casts to its own implementation and has the binding intact.</p>
 *
 * <h3>{@link #of} exists for the engines that genuinely have nothing behind the name</h3>
 *
 * <p>A tree-sitter-only language, a test fake, and JavaScript's runtime introspection all know a type only
 * as text. Forcing them to invent a binding to satisfy this interface would be ceremony, so the plain
 * implementation is provided here rather than copied into each of them.</p>
 */
public interface TypeRef {

    /** What a human should see: {@code List<String>}, {@code int[]}, {@code ? extends Number}. */
    String displayName();

    /**
     * The name that identifies this type to a compiler: {@code java.util.List}, {@code int}.
     *
     * <p>Erased and unqualified by generics on purpose — this answers "which type is it" rather than
     * "what does it look like", and the two differ for every parameterised type. A cache keyed on this is
     * keyed on the right thing; a cache keyed on {@link #displayName()} has an entry per instantiation.</p>
     */
    String qualifiedName();

    /** A type that is only a name — for engines with no binding model, and for tests. */
    static TypeRef of(String displayName, String qualifiedName) {
        return new TypeRef() {
            @Override
            public String displayName() {
                return displayName == null ? "" : displayName;
            }

            @Override
            public String qualifiedName() {
                return qualifiedName == null ? "" : qualifiedName;
            }

            @Override
            public String toString() {
                return displayName();
            }
        };
    }

    /** A type that is only a name, where the display and the identity are the same string. */
    static TypeRef of(String name) {
        return of(name, name);
    }
}
