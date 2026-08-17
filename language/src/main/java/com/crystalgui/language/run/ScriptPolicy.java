package com.crystalgui.language.run;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which Java classes a script may reach — one allowlist, asked everywhere a name could leak.
 *
 * <h3>Why this is in {@code language.run} and not in {@code language.js}</h3>
 *
 * <p>JavaScript is the first consumer with real teeth, and it is not the only one. The same question is
 * asked by the <b>type index</b> (which types may be offered at all), by <b>completion and hover</b> (which
 * members may be described), and — advisory, and outside M10 — by <b>Java compilation</b>. A policy owned
 * by one language would have to be reached through that language by the other three, which is how a rule
 * ends up enforced in some places and not others.</p>
 *
 * <p>It is also the host's rather than the engine's: an allowlist is a deployment decision, and the engine
 * is what obeys it. That is why the child side of the bridge receives a {@code Predicate<String>} and never
 * this type — a JDK-typed shadow of a host object, exactly as the console and the input are.</p>
 *
 * <h3>Refusing is a fact about a name, not about a caller</h3>
 *
 * <p>One question, {@link #allowsClass}, asked identically wherever it is asked. The alternative — a
 * per-consumer permission model — is how a class comes to be absent from a completion list and callable at
 * run time, or the reverse: offered, accepted, and then refused. Neither is a security property; both are
 * a bug that looks like one.</p>
 *
 * <h3>Prefix matching, and what it deliberately does not do</h3>
 *
 * <p>An entry is a package or a class prefix, matched on a dot boundary so {@code java.util} admits
 * {@code java.util.List} and {@code java.util.concurrent.Future} but not {@code java.utility.Thing}. There
 * is no denylist and no wildcard syntax: a denylist is unsound the moment a new class appears, and a
 * pattern language is a second thing to get wrong. What a deployment writes down is what a script may
 * touch.</p>
 *
 * <p><b>Not a security boundary on its own.</b> A script runs in the game's own JVM, so a determined author
 * has reflection and the classloader; this stops accidents and casual reach, which is what the trust model
 * (a script is code the player installed) actually asks for. Saying so here rather than letting a reader
 * infer a sandbox that is not one.</p>
 */
public final class ScriptPolicy {

    /** Everything is reachable — the harness's posture, and a test's. */
    private static final ScriptPolicy ALLOW_ALL = new ScriptPolicy(null);

    /**
     * The allowed prefixes, or null for "everything".
     *
     * <p>Null rather than a list containing {@code ""}, so the common case costs one reference comparison
     * and no iteration: this is asked once per class Rhino's shutter sees, which is every class a script
     * touches.</p>
     */
    @Nullable
    private final List<String> allowed;

    private ScriptPolicy(@Nullable List<String> allowed) {
        this.allowed = allowed;
    }

    public static ScriptPolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Only these packages and classes.
     *
     * <p>An empty list means <b>nothing</b> is reachable, which is a legitimate posture and not a mistake
     * to be helpfully corrected into {@code allowAll}: a host that means "no Java at all" has to be able to
     * say it, and silently widening a policy is the worst thing this class could do.</p>
     */
    public static ScriptPolicy of(@Nullable List<String> allowedPrefixes) {
        if (allowedPrefixes == null) return ALLOW_ALL;
        List<String> cleaned = new ArrayList<>(allowedPrefixes.size());
        for (String prefix : allowedPrefixes) {
            if (prefix != null && !prefix.isEmpty()) cleaned.add(prefix);
        }
        return new ScriptPolicy(Collections.unmodifiableList(cleaned));
    }

    /** Whether anything is refused at all — what lets a consumer skip filtering entirely. */
    public boolean allowsEverything() {
        return allowed == null;
    }

    /**
     * May a script reach this binary name?
     *
     * <p>An array's element type is what is asked about: {@code java.util.List[]} is reachable exactly when
     * {@code java.util.List} is, and a policy that refused the array form while admitting the element would
     * be refusing a spelling rather than a class. Same for a nested class, whose binary name carries its
     * outer name as a prefix and so matches on the enclosing entry for free.</p>
     */
    public boolean allowsClass(@Nullable String binaryName) {
        if (allowed == null) return true;
        if (binaryName == null || binaryName.isEmpty()) return false;
        String name = elementTypeOf(binaryName);
        // A PRIMITIVE IS NOT A CLASS ANYBODY CAN REACH THROUGH. `int` has no package and no members, and
        // refusing it would make every method taking one undescribable.
        if (name.indexOf('.') < 0 && isPrimitive(name)) return true;
        for (String prefix : allowed) {
            if (matches(name, prefix)) return true;
        }
        return false;
    }

    /**
     * May a script see this package — for a completion root, and for {@code Packages.*}.
     *
     * <p>True when the package is <em>at or under</em> an allowed prefix, and also when an allowed prefix is
     * under <em>it</em>: {@code java} must be offerable for {@code java.util.List} to be reachable through
     * it, or the policy would admit a class by a path it refuses to show.</p>
     */
    public boolean allowsPackage(@Nullable String packageName) {
        if (allowed == null) return true;
        if (packageName == null || packageName.isEmpty()) return false;
        for (String prefix : allowed) {
            if (matches(packageName, prefix) || matches(prefix, packageName)) return true;
        }
        return false;
    }

    /**
     * The element type of an array, in <b>either</b> spelling — or the name unchanged.
     *
     * <p>{@code java.util.List[]} is what a source-level name looks like and what the editor asks about;
     * {@code [Ljava.util.List;} is what the JVM calls the same type, and it is what a {@code ClassShutter}
     * is handed when a script touches one. Handling only the first meant the javadoc's promise about arrays
     * was kept for the surface that never sees one and broken for the surface that does.</p>
     */
    private static String elementTypeOf(String binaryName) {
        String name = binaryName;
        while (name.endsWith("[]")) name = name.substring(0, name.length() - 2);
        int depth = 0;
        while (depth < name.length() && name.charAt(depth) == '[') depth++;
        if (depth == 0) return name;
        String element = name.substring(depth);
        // `[Lfoo.Bar;` is a reference array; `[I`, `[D` and the rest are primitive ones, whose one-letter
        // element name is not a class name at all and is left to the primitive test.
        if (element.startsWith("L") && element.endsWith(";")) {
            return element.substring(1, element.length() - 1);
        }
        return element;
    }

    /** A dot-boundary prefix test — so {@code java.util} does not admit {@code java.utility}. */
    private static boolean matches(String name, String prefix) {
        if (!name.startsWith(prefix)) return false;
        return name.length() == prefix.length() || name.charAt(prefix.length()) == '.'
                // A NESTED CLASS is separated by `$`, and is part of the class its prefix named.
                || name.charAt(prefix.length()) == '$';
    }

    /**
     * Whether this names a primitive — the source spelling, and the JVM's one-letter array element codes.
     *
     * <p>The second half is why an array of primitives is reachable: the shutter sees {@code [I} for an
     * {@code int[]}, whose element name is {@code I}.</p>
     */
    private static boolean isPrimitive(String name) {
        switch (name) {
            case "boolean": case "byte": case "char": case "short":
            case "int": case "long": case "float": case "double": case "void":
            case "Z": case "B": case "C": case "S":
            case "I": case "J": case "F": case "D":
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return allowed == null ? "ScriptPolicy[allow all]" : "ScriptPolicy" + allowed;
    }
}
