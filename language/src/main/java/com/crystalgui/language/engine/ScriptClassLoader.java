package com.crystalgui.language.engine;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One compiled script's classes, in a loader the <b>host</b> owns.
 *
 * <h3>Why the script does not live in the engine's loader</h3>
 *
 * <p>A script is re-run constantly — that is the authoring loop. Each run must replace the last, and
 * the last must become collectable, which means the <em>only</em> thing referencing its classes is a
 * loader nothing else holds. Loading a script into the engine's own loader would tie every version of
 * every script to the engine's lifetime: the engine is expensive to build and is meant to be long-lived,
 * so it is precisely the wrong owner. Dropping this object drops the script.</p>
 *
 * <p>Its parent is the <b>host</b> classloader and deliberately not the engine's: a script calls the
 * host's API, and has no business seeing ECJ.</p>
 *
 * <h3>Parent-first, unlike the engine's loader</h3>
 *
 * <p>The opposite choice to {@link EngineClassLoader}, for the opposite reason. That one is child-first
 * because our pinned engine must beat whatever else is on the classpath. A script must <em>not</em> beat
 * the host — a script class named the same as a host class has to resolve to the host's, or a script
 * could shadow the API it is being given. Parent-first is the default, so this only overrides
 * {@link #findClass}.</p>
 */
public final class ScriptClassLoader extends ClassLoader {

    /**
     * The one class a script names without its author writing it — never policed.
     *
     * <p>{@code Safepoints} injects a call to {@code ScriptControl.checkpoint()} into every method of
     * every script, so every script links it. Asking the policy about it makes the <b>kill switch</b> the
     * thing that refuses the script: any allowlist that does not happen to name our own internals refuses
     * everything, with a message pointing at a class the author never heard of.</p>
     *
     * <p>Named as a string rather than as {@code ScriptControl.class.getName()} because that class lives
     * in {@code language.run.exec} and this one is in {@code language.engine} — reached from both sides
     * of the bridge, where the only things that may cross are JDK types. {@code RefusedTypes} makes the
     * same exemption from the other side, where the reference is legal.</p>
     */
    private static final String INJECTED_RUNTIME = "com.crystalgui.language.run.exec.ScriptControl";

    /**
     * What the JVM itself resolves to link a script — never the author's reach.
     *
     * <p>A string concatenation compiles to an {@code invokedynamic} against {@code StringConcatFactory}
     * and a lambda to one against {@code LambdaMetafactory}, and the JVM resolves those <b>through this
     * loader</b> when the call site links. Gating them turned {@code "count: " + n} into a
     * {@code NoClassDefFoundError: java/lang/invoke/StringConcatFactory} thrown from the script's own
     * first line — under a policy the author had no way to connect to string concatenation.</p>
     *
     * <p>Kept as an explicit set rather than exempting {@code java.lang.invoke} wholesale, and mirrored
     * by {@code RefusedTypes.BOOTSTRAP_SURFACE} on the scanning side — the two halves have to agree or a
     * script is refused by one and linked by the other.</p>
     *
     * <p><b>The residual hole, stated:</b> {@code Class.forName("java.lang.invoke.MethodHandles")} is
     * indistinguishable here from the JVM linking a call site, so a name built at run time reaches these
     * seven classes. Using them then needs {@code java.lang.reflect}, which the scan does catch — and
     * §19.1 is the standing answer for why a determined author is not the threat this addresses.</p>
     */
    private static final java.util.Set<String> LINKAGE_SURFACE =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "java.lang.invoke.StringConcatFactory",
                    "java.lang.invoke.LambdaMetafactory",
                    "java.lang.invoke.MethodHandles",
                    "java.lang.invoke.MethodHandles$Lookup",
                    "java.lang.invoke.MethodHandle",
                    "java.lang.invoke.MethodType",
                    "java.lang.invoke.CallSite"));

    private final Map<String, byte[]> pending;
    @Nullable
    private final Predicate<String> permitted;

    /**
     * @param classes compiled class files by binary name — {@link
     *                com.crystalgui.language.engine.bridge.ScriptCompiler.Result#classes()}
     * @param parent  the host loader, which is what a script is allowed to see
     */
    public ScriptClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
        this(classes, parent, null);
    }

    /**
     * @param permitted asked of every name this loader is about to delegate upward, or null for none —
     *                  a {@link Predicate} rather than the policy type, because this class is reached
     *                  from both sides of the bridge and a JDK type is what may cross
     */
    public ScriptClassLoader(Map<String, byte[]> classes, ClassLoader parent,
                             @Nullable Predicate<String> permitted) {
        super(parent);
        this.pending = new HashMap<>(classes);
        this.permitted = permitted;
    }

    /**
     * Parent-first, and refused first of all.
     *
     * <p>The gate is <b>here rather than only in the ahead-of-time scan</b> because this is the one place
     * a late name is seen. {@code RefusedTypes} reads what a script's bytes name and can refuse the whole
     * script before a line of it runs, which is the better failure by far — but it reads names, and
     * {@code Class.forName(built + "at" + "runtime")} has none to read. Everything a script actually links
     * comes through here.</p>
     *
     * <p><b>Its own classes are never asked about.</b> They are in {@link #pending}, they are not on the
     * parent's classpath, and a policy has no opinion about a synthetic package the compiler invented —
     * asking would refuse every script under any allowlist that did not happen to name it.</p>
     *
     * <p>A refusal is a {@link ClassNotFoundException} and deliberately not a bespoke type: it is what
     * every caller in the JVM's linkage path already handles, and the message is where the reason goes.
     * A {@code NoClassDefFoundError} escaping from a static initialiser somewhere is what a novel
     * exception buys.</p>
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (permitted != null && !pending.containsKey(name) && findLoadedClass(name) == null
                && !INJECTED_RUNTIME.equals(name) && !LINKAGE_SURFACE.contains(name)
                && !permitted.test(name)) {
            throw new ClassNotFoundException(name
                    + " is not reachable from a script under this deployment's ScriptPolicy");
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = pending.remove(name);
        if (bytes == null) throw new ClassNotFoundException(name);
        // REMOVED, NOT READ. defineClass throws if called twice for one name, and the parent-first walk
        // can reach findClass more than once for the same name under concurrent loads. Handing the
        // bytes over exactly once makes the second attempt a plain ClassNotFoundException, which
        // loadClass then resolves from findLoadedClass.
        return defineClass(name, bytes, 0, bytes.length);
    }

    /** Whether this loader holds a class of that name that has not been defined yet. */
    public boolean has(String binaryName) {
        return pending.containsKey(binaryName);
    }

    static {
        registerAsParallelCapable();
    }
}
