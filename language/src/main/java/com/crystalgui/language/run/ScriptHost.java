package com.crystalgui.language.run;

import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.ScriptClassLoader;
import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.java.HostClasspath;
import com.crystalgui.language.java.ScriptPrelude;
import com.crystalgui.language.map.InheritanceAwareRemapper;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compile always, run explicitly, re-run replaces — the execution service.
 *
 * <h3>The lifecycle, and why it is that one</h3>
 *
 * <p>A script is compiled whenever it changes, because diagnostics have to be live. It is <b>run only
 * when asked</b>, because running is a side effect on a world and nobody wants one per keystroke. And a
 * re-run <b>replaces</b> the previous instance rather than hot-swapping it: hot swap is a non-goal
 * (§22), and replacement is both simpler and honest about what happened — the old objects are gone, not
 * migrated.</p>
 *
 * <h3>Four passes between source and a running class, in this order</h3>
 *
 * <ol>
 *   <li><b>Compile</b> — through the band's ECJ, or straight out of the cache.</li>
 *   <li><b>Remap</b> readable→runtime (§15.5 B), inheritance-aware.</li>
 *   <li><b>Instrument</b> with safepoints (§19.3), so a runaway script can be stopped.</li>
 *   <li><b>Define</b> in a loader this run owns.</li>
 * </ol>
 *
 * <p>The order of 2 and 3 is not free. <b>Remap first</b>: the remapper walks supertypes to decide
 * whether a declaration is an override, and instrumenting first would put calls to
 * {@code ScriptControl.checkpoint} into the bytecode the remapper then has to consider — harmless
 * today, and exactly the kind of coupling that turns into a bug when the remapper grows a rule about
 * call sites. Instrumenting second also means what gets cached is the <em>compile</em> output, which is
 * the thing the cache key describes.</p>
 *
 * <h3>Disposal is a property of the loader, not a method that frees things</h3>
 *
 * <p>Each run gets its own {@link ScriptClassLoader}. Dropping the reference makes the loader, its
 * classes, and everything they statically hold collectable — which is the entire mechanism, and the
 * reason {@code ScriptCompiler} hands back bytes rather than {@code Class} objects. The one thing that
 * would defeat it is the host keeping a reference to a script object; nothing here does, and a caller
 * that stores one has taken the decision knowingly.</p>
 */
public final class ScriptHost implements Closeable {

    /** The method a prelude-wrapped script exposes. @see ScriptPrelude */
    private static final String ENTRY_POINT = "run";

    private final JavaEngine engine;
    private final ScriptCache cache;
    private final MappingSet mappings;
    private final String mappingsId;
    private final ClassLoader hostLoader;
    private final List<String> classpath;

    /** The live run, if any. Replaced wholesale; never mutated. */
    private final AtomicReference<Running> running = new AtomicReference<>();

    /**
     * Where run states are reported, or null for a host nobody is watching.
     *
     * <p>Optional for the same reason the output ref is: a dedicated server runs scripts and has no rail
     * to show them in, and a test should not have to build one. Absent, nothing is recorded and nothing
     * fails.</p>
     */
    private RunSessions sessions;

    public ScriptHost(JavaEngine engine, ScriptCache cache, MappingSet mappings, String mappingsId,
                      ClassLoader hostLoader, List<String> classpath) {
        this.engine = engine;
        this.cache = cache == null ? ScriptCache.NONE : cache;
        this.mappings = mappings == null ? MappingSet.IDENTITY : mappings;
        this.mappingsId = mappingsId == null ? "identity" : mappingsId;
        this.hostLoader = hostLoader == null ? ScriptHost.class.getClassLoader() : hostLoader;
        this.classpath = classpath == null ? HostClasspath.detect() : new ArrayList<>(classpath);
    }

    /**
     * Reports every run's state to {@code sessions} — what the rail lists and the indicator marks.
     *
     * <p>Set on the host rather than passed per run, because the states this records are the host's own
     * transitions and it is the only thing that sees all of them: a caller sees the run it started, and
     * never sees the {@code STOPPED} that another caller's Stop produced.</p>
     */
    public ScriptHost reportTo(RunSessions sessions) {
        this.sessions = sessions;
        return this;
    }

    /** The ordinary setup: identity mappings, an in-memory cache, the host's own classpath. */
    public static ScriptHost of(JavaEngine engine) {
        return new ScriptHost(engine, ScriptCache.inMemory(), MappingSet.IDENTITY, "identity",
                ScriptHost.class.getClassLoader(), null);
    }

    // ── Compile ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Compiles a script, from the cache when it can.
     *
     * <p>Returns the compile output — pre-remap and pre-instrumentation — because that is what the
     * cache key describes and what a caller inspecting diagnostics wants to see.</p>
     */
    /**
     * Compiles a source file, picking its shape.
     *
     * <p>A file that already declares a type is compiled as-is; anything else is a snippet and gets the
     * prelude. The decision is made once, here, so the editor and the Run command cannot disagree about
     * what a given file is — which they would the moment each asked separately.</p>
     */
    public Compiled compileSource(String className, String source, Map<String, String> bindingTypes) {
        return compile(ScriptPrelude.declaresType(source)
                ? ScriptPrelude.compilationUnit(className, source)
                : preludeFor(className, bindingTypes).wrap(source));
    }

    public Compiled compile(ScriptPrelude.Wrapped wrapped) {
        ScriptCacheKey key = ScriptCacheKey.of(wrapped.unitSource(), mappingsId,
                engine.band().minimumFeatureVersion());
        Map<String, byte[]> cached = cache.get(key);
        if (cached != null) {
            return new Compiled(wrapped, key, cached, List.of(), true, true);
        }
        ScriptCompiler.Result result = engine.compiler().compile(
                wrapped.className(), wrapped.unitSource(), classpath, engine.releaseLevel());
        // ONLY A SUCCESSFUL COMPILE IS CACHED. Caching a failure would serve it back after the author
        // fixed the file, which reads as the editor refusing to notice an edit.
        if (result.successful()) cache.put(key, result.classes());
        return new Compiled(wrapped, key, result.classes(), result.messages(),
                false, result.successful());
    }

    /** A compilation, and where it came from. */
    public static final class Compiled {
        private final ScriptPrelude.Wrapped wrapped;
        private final ScriptCacheKey key;
        private final Map<String, byte[]> classes;
        private final List<String> messages;
        private final boolean fromCache;
        private final boolean successful;
        private ScriptRef ref;

        Compiled(ScriptPrelude.Wrapped wrapped, ScriptCacheKey key, Map<String, byte[]> classes,
                 List<String> messages, boolean fromCache, boolean successful) {
            this.wrapped = wrapped;
            this.key = key;
            this.classes = classes;
            this.messages = messages;
            this.fromCache = fromCache;
            this.successful = successful;
        }

        /**
         * Names the file this was compiled from, so its output can be attributed while it runs.
         *
         * <p><b>On the compiled script rather than on the run call, deliberately.</b> Whoever compiles
         * knows which file the source came from; whoever runs may be a keybinding several layers away
         * that has no idea. Putting it here means {@link #run} and {@link #runAsync} can bracket every
         * invocation themselves and <em>no caller can forget to</em> — which matters because forgetting
         * is silent: the output simply goes to the game's log instead of the panel, and looks like the
         * console not working.</p>
         *
         * <p>Optional. A host with no console — a test, a dedicated server — leaves it null and nothing
         * routes anywhere. @see ScriptOutput</p>
         */
        public Compiled withSource(Resource file) {
            // BUILT HERE, from the two halves neither side has both of: the caller knows which file the
            // source came from and cannot know what the prelude wrapped it into, while this knows the
            // generated class and has never heard of a file. Taking a whole ScriptRef instead would let
            // a caller pair a file with the wrong class -- which fails by attributing nothing, since no
            // stack frame would ever match.
            this.ref = file == null ? null : new ScriptRef(file, wrapped.className());
            return this;
        }

        public ScriptRef ref() {
            return ref;
        }

        /**
         * The COMPILER's verdict, not "did any bytes come back".
         *
         * <p>ECJ emits class files for the types it did manage even when the compile failed, which is
         * useful to inspect and is not permission to run: a script whose broken method produced no
         * body would load fine and fail at the first call, which is a worse report of the same error.</p>
         */
        public boolean successful() {
            return successful && !classes.isEmpty();
        }

        public Map<String, byte[]> classes() {
            return classes;
        }

        public List<String> messages() {
            return messages;
        }

        public ScriptCacheKey key() {
            return key;
        }

        /** Whether this came from the cache — the only observable difference, and worth asserting on. */
        public boolean fromCache() {
            return fromCache;
        }

        ScriptPrelude.Wrapped wrapped() {
            return wrapped;
        }
    }

    // ── Run ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs a compiled script on the calling thread, replacing whatever was running.
     *
     * @param bindings values for the host bindings the prelude declared, by name. A binding the script
     *                 does not declare is ignored rather than refused: the host offers a context and a
     *                 script uses the part of it that it needs
     */
    public Object run(Compiled compiled, Map<String, Object> bindings) throws Throwable {
        Running prepared = prepare(compiled, bindings);
        stop();
        running.set(prepared);
        try {
            return prepared.invoke();
        } finally {
            // CLEARED ONLY IF STILL OURS. A run that started another run -- or a stop that landed while
            // this was finishing -- must not have its state wiped by this one's completion.
            running.compareAndSet(prepared, null);
        }
    }

    /**
     * Runs on a fresh daemon thread and returns it, so a caller can {@link #stop} a runaway script.
     *
     * <p>Daemon, because a script that will not die must never be the reason the game cannot exit.
     * That is the backstop under the ssafepoints rather than a substitute for them: it makes shutdown
     * safe, and it does nothing at all for a session that is still running.</p>
     */
    public Thread runAsync(Compiled compiled, Map<String, Object> bindings,
                           java.util.function.Consumer<Throwable> onFailure) throws Throwable {
        Running prepared = prepare(compiled, bindings);
        stop();
        running.set(prepared);

        Thread thread = new Thread(() -> {
            try {
                prepared.invoke();
            } catch (ScriptStoppedException stopped) {
                // Asked for. Not a failure and not worth reporting as one.
            } catch (Throwable failed) {
                if (onFailure != null) onFailure.accept(failed);
            } finally {
                running.compareAndSet(prepared, null);
            }
        }, "cgui-script");
        thread.setDaemon(true);
        prepared.thread = thread;
        thread.start();
        return thread;
    }

    /** Everything between bytes and an invocable instance. */
    private Running prepare(Compiled compiled, Map<String, Object> bindings) throws Throwable {
        if (!compiled.successful()) {
            throw new IllegalStateException("cannot run a script that did not compile: "
                    + compiled.messages());
        }
        Map<String, byte[]> remapped = new InheritanceAwareRemapper(mappings,
                InheritanceAwareRemapper.fromClassLoader(hostLoader)).remap(compiled.classes());
        Map<String, byte[]> instrumented = Safepoints.inject(remapped);

        ScriptClassLoader loader = new ScriptClassLoader(instrumented, hostLoader);
        // THE BINARY NAME, which for a file declaring a package is not the file stem. Asking for
        // the stem compiles fine and then throws ClassNotFoundException for a class that is
        // plainly in the output -- see ScriptPrelude.Wrapped.binaryName.
        Class<?> type = Class.forName(compiled.wrapped().binaryName(), true, loader);
        return entryPointOf(type, loader, bindings, compiled.ref());
    }

    /**
     * Finds how to start this class, and prepares whatever that needs.
     *
     * <p><b>Two shapes, because a script is written two ways.</b> A snippet wrapped by
     * {@link ScriptPrelude} exposes {@code run()} on an instance whose fields are the host bindings. A
     * file the author wrote as an ordinary class — their own {@code Main.java} — has a
     * {@code static void main(String[])} and no such fields.</p>
     *
     * <p>{@code run()} is tried first and that order matters: a wrapped snippet could legally declare
     * its own {@code main}, and the prelude's entry point is the one that carries the bindings. A file
     * with both is a file the author meant to run as a class, and it will be reached only if the
     * prelude did not wrap it — in which case there is no {@code run()} to find.</p>
     */
    private Running entryPointOf(Class<?> type, ScriptClassLoader loader, Map<String, Object> bindings,
                                 ScriptRef ref) throws Throwable {
        Method entry = methodOrNull(type, ENTRY_POINT);
        if (entry != null && !java.lang.reflect.Modifier.isStatic(entry.getModifiers())) {
            Object instance = type.getDeclaredConstructor().newInstance();
            applyBindings(type, instance, bindings);
            return new Running(loader, entry, instance, null, ref, sessions);
        }
        if (entry != null) {
            applyBindings(type, null, bindings);
            return new Running(loader, entry, null, null, ref, sessions);
        }

        Method main = methodOrNull(type, "main", String[].class);
        if (main != null && java.lang.reflect.Modifier.isStatic(main.getModifiers())) {
            // BINDINGS ARE SKIPPED, not forced in. A file with a `main` was written as a program, not as
            // a script body, so it has no fields for them -- and setting statics that happen to share a
            // name would be reaching into somebody's class on a guess.
            return new Running(loader, main, null, new Object[]{new String[0]}, ref, sessions);
        }

        throw new IllegalStateException(type.getName() + " has neither a no-argument " + ENTRY_POINT
                + "() nor a static main(String[]) — nothing to run");
    }

    private static Method methodOrNull(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /**
     * Sets the prelude's fields from the host's context.
     *
     * <p>A binding the script did not declare is skipped. That is the right way round: the host offers
     * everything it has and a script names the parts it wants, so adding a new binding to the host
     * cannot break a script that was written before it existed.</p>
     */
    private static void applyBindings(Class<?> type, Object instance, Map<String, Object> bindings) {
        if (bindings == null || bindings.isEmpty()) return;
        for (Map.Entry<String, Object> binding : bindings.entrySet()) {
            try {
                Field field = type.getField(binding.getKey());
                field.set(instance, binding.getValue());
            } catch (ReflectiveOperationException | RuntimeException notDeclared) {
                // Skipped, per the note above.
            }
        }
    }

    // ── Stop ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Asks the running script to stop, and forgets it.
     *
     * <p>Interruption reaches a script two ways from one call: a blocked one throws
     * {@code InterruptedException} from the JDK, and a spinning one hits an injected safepoint
     * ({@link Safepoints}). Returns immediately — <b>this does not join</b>, because a script that
     * refuses to stop must not be able to block the thread that asked it to.</p>
     *
     * @return whether there was something to stop
     */
    public boolean stop() {
        Running current = running.getAndSet(null);
        if (current == null) return false;
        Thread thread = current.thread;
        if (thread != null && thread.isAlive()) thread.interrupt();
        return true;
    }

    public boolean isRunning() {
        Running current = running.get();
        return current != null && (current.thread == null || current.thread.isAlive());
    }

    /** One run: its loader, its entry point, its instance, and the thread it is on. */
    private static final class Running {
        final ScriptClassLoader loader;
        private final Method entryPoint;
        private final Object instance;
        private final Object[] arguments;
        private final ScriptRef ref;
        volatile Thread thread;

        @Nullable private final RunSessions sessions;

        Running(ScriptClassLoader loader, Method entryPoint, Object instance, Object[] arguments,
                @Nullable ScriptRef ref, @Nullable RunSessions sessions) {
            this.loader = loader;
            this.entryPoint = entryPoint;
            this.instance = instance;
            this.arguments = arguments == null ? new Object[0] : arguments;
            this.ref = ref;
            this.sessions = sessions;
        }

        private void report(RunState state) {
            if (sessions != null && ref != null) sessions.set(ref.file(), state);
        }

        /**
         * Invokes the entry point with the output marker set for its whole duration.
         *
         * <p><b>Here rather than at the two call sites</b>, because this is the one place a script
         * genuinely begins and ends — {@code run} and {@code runAsync} would each have to remember, and
         * a future third way to start a script would have to remember too. Forgetting is silent: the
         * output goes to the game's log and reads as the console being broken. @see ScriptOutput</p>
         */
        Object invoke() throws Throwable {
            ScriptRef previous = ref == null ? null : ScriptOutput.enter(ref);
            report(RunState.RUNNING);
            try {
                Object answer = invokeEntryPoint();
                // FINISHED, not LIVE. Nothing here registers handlers yet, so every script that returns
                // has genuinely left nothing behind -- and when an event model lands, THIS is the line
                // that has to start asking, rather than a new one somewhere else.
                report(RunState.FINISHED);
                return answer;
            } catch (ScriptStoppedException stopped) {
                // ASKED FOR, and therefore not a failure. Distinguished here rather than by the caller
                // because runAsync already swallows this one and would report nothing at all.
                report(RunState.STOPPED);
                throw stopped;
            } catch (Throwable failed) {
                report(RunState.FAILED);
                throw failed;
            } finally {
                if (ref != null) ScriptOutput.exit(previous);
            }
        }

        private Object invokeEntryPoint() throws Throwable {
            try {
                return entryPoint.invoke(instance, arguments);
            } catch (InvocationTargetException wrapped) {
                // UNWRAPPED. A script's own exception is what a caller wants to see and what the
                // prelude's line mapping describes; InvocationTargetException is an artefact of how it
                // was called and names nothing useful.
                throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * A prelude for a script with these host bindings — the shape the host and the editor must agree on.
     *
     * <p>Here rather than at each caller so that the class name, the entry point and the binding fields
     * are decided once. A script compiled with one prelude and run against another is a
     * {@code NoSuchMethodException} at the last possible moment.</p>
     */
    public static ScriptPrelude preludeFor(String className, Map<String, String> bindingTypes) {
        ScriptPrelude.Builder builder = ScriptPrelude.forClass(className);
        if (bindingTypes != null) {
            for (Map.Entry<String, String> binding : new LinkedHashMap<>(bindingTypes).entrySet()) {
                builder.binding(binding.getKey(), binding.getValue());
            }
        }
        return builder.build();
    }
}
