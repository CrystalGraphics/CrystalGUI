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
 * is no wildcard syntax: a pattern language is a second thing to get wrong. What a deployment writes down
 * is what a script may touch.</p>
 *
 * <h3>And a denylist, which this deliberately did not have</h3>
 *
 * <p>The rule was "allowlist only", on the grounds that <b>a denylist is unsound the moment a new class
 * appears</b>. That is true and it is still written down, because it is the reason a denylist may never be
 * the thing a security claim rests on. It stopped being the whole argument once the honest posture was
 * settled (§19.1): for Java this is a <em>guardrail</em>, not a boundary — and the allowlist a guardrail
 * needs is the host API, the Minecraft surface and a usable slice of {@code java.*}, which is thousands of
 * entries. <b>A control nobody will write is worse than a leaky one that gets used.</b> Ten refusals a
 * deployment will actually maintain beats ten thousand permissions it will not.</p>
 *
 * <p>So the two compose rather than replace: <b>a denial is a veto</b>. A name must clear the denylist,
 * and then — if there is one at all — match the allowlist. That keeps every posture expressible: allow-all
 * minus a handful ({@link #denying}), a narrow slice ({@link #of(List)}), or a slice with holes punched in
 * it ({@link #of(List, List)}). It also means a deny cannot be re-permitted by an allow, which is the only
 * ordering that lets {@link #UNSAFE} mean anything.</p>
 *
 * <p><b>What a denylist cannot do is stay correct on its own.</b> A JDK release that adds a new way to
 * reach a class loader is admitted by an allow-all-minus-ten policy until somebody notices. That is
 * inherent to the shape and is the price being paid knowingly here — it is exactly why this is not, and
 * must never be described as, a boundary.</p>
 *
 * <p><b>Not a security boundary on its own.</b> A script runs in the game's own JVM, so a determined author
 * has reflection and the classloader; this stops accidents and casual reach, which is what the trust model
 * (a script is code the player installed) actually asks for. Saying so here rather than letting a reader
 * infer a sandbox that is not one.</p>
 */
public final class ScriptPolicy {

    /** Everything is reachable — the harness's posture, and a test's. */
    private static final ScriptPolicy ALLOW_ALL = new ScriptPolicy(null, null);

    /**
     * What no policy may permit — the machinery that enforces policies.
     *
     * <h3>A filter its subject can switch off is not a filter</h3>
     *
     * <p>{@code JavaLanguage.restrictTo} and {@code JsLanguage.restrictTo} are {@code public static}, they
     * sit on the host classpath, and {@code ScriptClassLoader} is parent-first. So under a policy of
     * "deny {@code java.io}" the name {@code com.crystalgui.language.java.JavaLanguage} was not denied —
     * it is not {@code java.io} — the ahead-of-time scan passed it, the loader passed it, and <b>one line
     * of script turned the filter off for every script after it</b>:</p>
     *
     * <pre>com.crystalgui.language.java.JavaLanguage.restrictTo(null);</pre>
     *
     * <p>This is therefore a <b>floor and not a default</b>. It is checked before the denylist and before
     * the allowlist, so naming it in {@link #of(List)} does not permit it; that is the whole property, and
     * it is why it is separate from {@link #UNSAFE} — UNSAFE is a list a host composes and may edit down,
     * and this is what enforces whatever they compose.</p>
     *
     * <p><b>{@code com.crystalgui.language} and nothing wider.</b> {@code com.crystalgui.ui} and
     * {@code com.crystalgui.text} are what scripts are FOR — driving the interface and the document — so
     * a floor over all of {@code com.crystalgui} would refuse the API in the name of protecting it. The
     * language stack is the only part a script has no business inside.</p>
     *
     * <p>One exception, and it is not reachable through this: {@code ScriptControl}, whose
     * {@code checkpoint()} is injected into every method of every script by {@code Safepoints}, so every
     * script links it whether or not its author has heard of it. The scan and the loader exempt it by
     * name. It exposes exactly one {@code public static void} that reads the calling thread's own
     * interrupt status, so reaching it buys a script nothing but the ability to stop itself.</p>
     */
    public static final List<String> ALWAYS_REFUSED = Collections.singletonList("com.crystalgui.language");

    /**
     * Whether this name is refused by every policy that restricts anything.
     *
     * <p><b>{@link #allowAll()} is the one exception, and it is not a hole.</b> The floor exists to stop a
     * script <em>relaxing</em> the policy in force; with nothing configured there is nothing to relax, and
     * a script that calls {@code restrictTo} itself can only make things narrower — after which the floor
     * refuses anything that could widen them again. A one-way ratchet, in the safe direction.</p>
     *
     * <p>Static because it is not a property of any one policy, and public because a caller may want to
     * ask without holding one.</p>
     */
    public static boolean isAlwaysRefused(@Nullable String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;
        String name = elementTypeOf(binaryName);
        for (String prefix : ALWAYS_REFUSED) {
            if (matches(name, prefix)) return true;
        }
        return false;
    }

    /**
     * The ways out of a class allowlist, as prefixes — what a denying policy usually wants first.
     *
     * <p><b>Without these a class filter is decorative.</b> Every entry here is a documented route from a
     * name a policy permits to one it does not: reflection and method handles resolve a class from a
     * string, a {@code ClassLoader} loads one outright, and {@code Runtime}/{@code ProcessBuilder} leave
     * the JVM altogether. Refusing {@code java.io} while admitting {@code java.lang.reflect} refuses a
     * spelling rather than a capability.</p>
     *
     * <p>It is a <b>list a host passes</b> rather than something implied by {@link #denying}, because a
     * policy that silently refuses more than it was told to is the mirror of one that silently allows
     * more — and because a deployment that genuinely wants reflection has to be able to drop an entry.
     * {@code ScriptPolicy.denying(UNSAFE)} is the intended spelling; {@code denying(plus(UNSAFE, …))} is
     * the next one.</p>
     *
     * <p>Two omissions are deliberate and both are holes. <b>{@code java.lang.Thread}</b> escapes §19.3's
     * kill switch — a stop names one thread and a spawned one runs on — but threads are ordinary in
     * correct scripts, and this set is for accidents rather than for determined authors.
     * <b>{@code java.lang.System}</b> carries {@code System.exit} and also {@code System.out}, which is
     * the console; prefix matching cannot separate them, so refusing the class would take the one thing
     * every script uses. Both are named here so the gap is a decision rather than an oversight.</p>
     */
    public static final List<String> UNSAFE = List.of(
            "java.lang.reflect",
            "java.lang.invoke", 
            "java.lang.ClassLoader",
            "java.lang.Runtime", 
            "java.lang.ProcessBuilder",
            "java.lang.Process", 
            "java.security",
            "sun.misc.Unsafe",
            "jdk.internal");

    /**
     * The allowed prefixes, or null for "everything".
     *
     * <p>Null rather than a list containing {@code ""}, so the common case costs one reference comparison
     * and no iteration: this is asked once per class Rhino's shutter sees, which is every class a script
     * touches.</p>
     */
    @Nullable
    private final List<String> allowed;

    /** The refused prefixes, or null for "nothing is refused outright". A match here is final. */
    @Nullable
    private final List<String> denied;

    private ScriptPolicy(@Nullable List<String> allowed, @Nullable List<String> denied) {
        this.allowed = allowed;
        this.denied = denied;
    }

    public static ScriptPolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Everything <b>except</b> these packages and classes — the posture a guardrail can actually be
     * written in.
     *
     * <p>The intended pairing is {@code denying(UNSAFE)}, which refuses the routes out of a class filter
     * and admits the rest of the world. See the class note for why this exists beside {@link #of(List)}
     * and for what it cannot promise.</p>
     */
    public static ScriptPolicy denying(@Nullable List<String> deniedPrefixes) {
        List<String> cleaned = clean(deniedPrefixes);
        return cleaned == null || cleaned.isEmpty() ? ALLOW_ALL : new ScriptPolicy(null, cleaned);
    }

    /** Only these, minus those — a slice with holes punched in it. A denial wins. */
    public static ScriptPolicy of(@Nullable List<String> allowedPrefixes,
                                  @Nullable List<String> deniedPrefixes) {
        List<String> denials = clean(deniedPrefixes);
        if (allowedPrefixes == null) return denying(denials);
        return new ScriptPolicy(clean(allowedPrefixes),
                denials == null || denials.isEmpty() ? null : denials);
    }

    @Nullable
    private static List<String> clean(@Nullable List<String> prefixes) {
        if (prefixes == null) return null;
        List<String> cleaned = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty()) cleaned.add(prefix);
        }
        return Collections.unmodifiableList(cleaned);
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
        return new ScriptPolicy(clean(allowedPrefixes), null);
    }

    /** Whether anything is refused at all — what lets a consumer skip filtering entirely. */
    public boolean allowsEverything() {
        return allowed == null && denied == null;
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
        // ALLOW-ALL IS ANSWERED FIRST, and the floor applies to every policy after it. The floor exists to
        // stop a script RELAXING the policy in force; with nothing configured there is nothing to relax,
        // and the worst a script can do by calling `restrictTo` itself is restrict -- a one-way ratchet,
        // since the floor then refuses anything that could undo it.
        //
        // The order is also what keeps the default posture free: `allowsEverything` is what lets the type
        // index, the completion list and the loader gate skip their work entirely, and a floor ahead of it
        // would make every one of them filter on every lookup for a deployment that restricted nothing.
        if (allowed == null && denied == null) return true;
        // AND NOW THE FLOOR, ahead of both lists: a policy cannot permit the thing that enforces policies,
        // so naming it in an allowlist does not admit it. @see #ALWAYS_REFUSED
        if (isAlwaysRefused(binaryName)) return false;
        if (binaryName == null || binaryName.isEmpty()) return false;
        String name = elementTypeOf(binaryName);
        // A PRIMITIVE IS NOT A CLASS ANYBODY CAN REACH THROUGH. `int` has no package and no members, and
        // refusing it would make every method taking one undescribable.
        if (name.indexOf('.') < 0 && isPrimitive(name)) return true;
        // A DENIAL IS A VETO, and it is asked FIRST. Anything else lets an allowlist entry re-permit a
        // refusal -- `allow java.lang` would undo `deny java.lang.reflect` -- and then UNSAFE means
        // whatever the two lists happen to say about each other rather than what it says.
        if (denied != null) {
            for (String prefix : denied) {
                if (matches(name, prefix)) return false;
            }
        }
        if (allowed == null) return true;
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
        if (allowed == null && denied == null) return true;
        // AT OR UNDER THE FLOOR, so `com.crystalgui.language` is not offered as a completion root either.
        // Not the reverse test the denylist gets below: `com.crystalgui` must stay walkable, or the floor
        // would hide `com.crystalgui.ui` on the way past.
        if (isAlwaysRefused(packageName)) return false;
        if (packageName == null || packageName.isEmpty()) return false;
        // AT OR UNDER A DENIAL ONLY, and never the other way round. `java.lang.reflect` is refused, and
        // `java.lang` is not refused for containing it -- a package is not its worst member. That is the
        // opposite of the allow test below, where an allowed prefix UNDER the package does admit it,
        // because a path has to be walkable to reach what is at the end of it.
        if (denied != null) {
            for (String prefix : denied) {
                if (matches(packageName, prefix)) return false;
            }
        }
        if (allowed == null) return true;
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
        if (allowed == null && denied == null) return "ScriptPolicy[allow all]";
        StringBuilder text = new StringBuilder("ScriptPolicy[");
        if (allowed != null) text.append("allow ").append(allowed);
        if (allowed != null && denied != null) text.append(", ");
        if (denied != null) text.append("deny ").append(denied);
        return text.append(']').toString();
    }
}
