package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.exec.ScriptHost;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.run.exec.ScriptCache;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.io.IOException;
import java.util.List;

/**
 * Puts the Java engine behind every {@code .java} document — the one call an application has to make.
 *
 * <h3>Why a host has to ask, and what it costs not to</h3>
 *
 * <p>Exactly the argument {@code TreeSitterLanguages} already makes, one layer up. Merely having this
 * module on the classpath must not open a compiler: a dedicated server has no editor, and
 * {@code LanguageServices} being absent is the feature flag the whole stack degrades through.</p>
 *
 * <p><b>And the cost of not calling it is invisible</b>, which is why this is a class of its own. The
 * editor still colours — tree-sitter is running — so nothing looks broken. What is missing is everything
 * an engine knows: no diagnostics reach the document's set, so Problems stays empty for Java; a
 * parameter, a local and a field take one colour because only resolution can tell them apart; and Run
 * does nothing because no {@code ScriptHost} exists. It reads as those features not being built. They
 * were built; nobody asked for them.</p>
 *
 * <h3>One engine per process, not per document</h3>
 *
 * <p>An engine is twenty jars in an isolated classloader. Per document that would be twenty jars per
 * open file. The engine is shared and the <em>services</em> are per document — which is the same split
 * {@code LanguageServices} already describes for a different reason, and it lands on the same shape.</p>
 */
public final class JavaLanguage {

    /**
     * @deprecated moved to {@link EngineHost#ENGINES_DIRECTORY_PROPERTY} — what it names is a
     *             <b>band</b>, staged with Rhino beside ECJ, not this language's engine. Kept because
     *             the harness and a dev run name it.
     */
    @Deprecated
    public static final String ENGINES_DIRECTORY_PROPERTY = EngineHost.ENGINES_DIRECTORY_PROPERTY;

    private static JavaEngine engine;
    private static JobScheduler scheduler;
    private static EngineSource source;
    /**
     * Whether {@link #register} has run — distinct from whether the engine opened. @see #register
     *
     * <p>Not cleared by {@link #shutdown()}, which deliberately does not unregister.</p>
     */
    private static boolean registered;

    private JavaLanguage() {
    }

    /** Registers with the shared scheduler and whatever engines this environment offers. */
    public static boolean register() {
        return register(JobScheduler.shared(), defaultSource());
    }

    /**
     * Opens the engine for this host's band and puts it behind the {@code .java} registration.
     *
     * <p>Idempotent, and <b>returns whether it worked</b> rather than throwing. An environment with no
     * engines is a legitimate one — a server, a build that ships none, a platform whose band has no jars
     * — and refusing to start over it would make the editor unusable for the case it was designed to
     * degrade through. A caller that wants to say "Java scripting unavailable" in a status bar has the
     * answer; one that does not can ignore it.</p>
     */
    public static synchronized boolean register(JobScheduler scheduler, EngineSource source) {
        // THE GUARD IS "HAVE I REGISTERED", NEVER "IS THE ENGINE OPEN" -- they are not the same question,
        // and reading the engine for the answer made registering JavaScript FIRST suppress Java entirely.
        // `JsLanguage.register` lends the Java engine to its interop tier, and it does that by calling
        // `JavaLanguage.engine()`, which OPENS the engine without registering anything. So by the time a
        // host called this method the field was already non-null, this returned true on its first line,
        // and none of what follows ever ran: `.java` kept the bare entry `LanguageRegistry`'s own static
        // initializer installs, so every Java document opened with NO services, and `ScriptRuntimes` never
        // heard that Java can run, so the Run panel had no runtime for a `.java` file.
        //
        // Nothing failed and nothing was logged -- the editor coloured from the grammar exactly as a
        // deployment with no engine does, which is a state this stack is designed to degrade through, so
        // it reads as "no engine here" rather than as a registration that was skipped. And it was decided
        // by REGISTRATION ORDER, so it reproduced only when a JavaScript host opened first.
        //
        // A first call that fails to OPEN the engine still registers, which is the same design D22 gave
        // `engine()`: the entry and the contribution are written whether or not the band arrived, and the
        // open is retried per document, so a band that lands late becomes usable without re-registering.
        if (registered) return true;
        registered = true;

        // THE BAND'S LOADER IS SHARED, not opened here: Rhino ships in the same band as ECJ, and a
        // JavaScript language registering after this one reaches its adapters through the same host.
        // Opening a loader per language would be two copies of twenty jars for one process.
        JavaLanguage.scheduler = scheduler;
        JavaLanguage.source = source;
        // ATTEMPTED, NOT REQUIRED -- everything below registers whether or not it opened. @see #engine()
        openEngine();
        List<String> classpath = HostClasspath.detect();

        // THE EXISTING ENTRY, WITH SERVICES ADDED -- not a new one. `.java` already resolves to a
        // tokenizer, and replacing the entry wholesale would drop whichever backend won: registering
        // after TreeSitterLanguages would discard tree-sitter, and before it would be discarded.
        // Reading the current entry and adding to it is order-independent, which is the only property
        // that makes two registrations safe to write in either sequence.
        LanguageRegistry.Entry current = LanguageRegistry.forFileName("Any.java");
        LanguageRegistry.registerExtensions(current.withServices(
                (buffer, resource) -> servicesFor(buffer, resource, classpath)), "java");

        // AND JAVA'S ONE COMMAND (M13 §25.5), into the GLOBAL registry rather than a workbench's.
        //
        // It has to be here rather than beside the Run commands: `language.run` is the engine-neutral
        // shell, and `RunShellIsEngineNeutralTest` is a bytecode scan that fails the commit which makes
        // it name `language.java` at all. Registering from each host instead would be a step every new
        // loader has to remember, and forgetting it leaves a working download nobody can start.
        //
        // The call also ADOPTS an extract an earlier session fetched, which is why it is not conditional
        // on the engine having opened: source attachment is the editor's, not the compiler's.
        JdkSourceCommands.register(CommandRegistry.global());

        // AND WARM THE ENGINE, off this thread. @see #warm
        warm(classpath);

        // AND THAT JAVA CAN RUN. The Run panel is written against ScriptRuntime and finds its runtimes
        // here rather than by asking this class -- so a second language contributes the same way and the
        // panel is not edited. The cache root is the workbench's to choose, which is why this is a
        // provider and not an instance.
        //
        // THE MAPPING IS READ WHEN THE RUNTIME OPENS, not here and not by the caller. It was
        // MappingSet.IDENTITY outright -- correct on every host whose runtime already speaks readable
        // names, and silently wrong on the one that does not: the script compiles against `Blocks.stone`
        // because the name environment shows it that way, nothing rewrites the bytes on the way out, and
        // the run dies with `NoSuchFieldError: stone` on a field whose runtime name is field_150348_b.
        // Compile clean, run broken, and only in production. Measured in the reobfuscated client.
        //
        // Inside the lambda because a provider is invoked when a workbench opens, which is later than
        // registration -- and later is what gives a first launch's background fetch time to land.
        ScriptRuntimes.contribute(Language.JAVA, cacheRoot -> {
            JavaEngine ready = engine();
            if (ready == null) return null;
            MappingSet mappings = PlatformMappings.current();
            return new ScriptHost(ready,
                    cacheRoot == null ? ScriptCache.inMemory() : ScriptCache.directory(cacheRoot),
                    mappings, mappings.isIdentity() ? "identity" : "mapped",
                    JavaLanguage.class.getClassLoader(), classpath);
        });
        return engine != null;
    }


    /**
     * <b>One throwaway analysis, on a daemon thread, so the first real one is not the first one.</b>
     *
     * <h3>Measured, not assumed</h3>
     *
     * <p>ECJ's first {@code analyze} costs <b>515 ms</b> and its second costs <b>11 ms</b> — that is class
     * loading and JIT, paid once per process by whoever asks first. In a Minecraft client the one who
     * asks first is a restored editor tab, on the client thread, during the first frame after F6: a
     * measured 970 ms of a four-second freeze, spent by a user who pressed a key rather than by a game
     * that was starting anyway.</p>
     *
     * <p>So it is paid here instead, where nothing is waiting for it. The cost does not move to a
     * different visible place — it moves to a thread nobody is watching, and it overlaps whatever the
     * game does next.</p>
     *
     * <h3>A daemon thread rather than the scheduler</h3>
     *
     * <p>The same choice, for the same reason, that {@code PlatformMappings.startLazily} makes:
     * {@code JobScheduler} is drained by {@code UIWindow.paintFrame}, and there is no window yet when a
     * language registers. A job submitted here would sit in the queue until the very frame it exists to
     * make cheaper. Daemon, because a warm-up must never be the reason a game cannot exit.</p>
     *
     * <p>Failures are swallowed on purpose and deliberately not logged at warn: this is an optimisation,
     * and an engine that cannot analyse a trivial class here will say so loudly enough the first time a
     * real file asks it.</p>
     */
    private static void warm(List<String> classpath) {
        JavaEngine ready = engine;
        if (ready == null) return;
        Thread worker = new Thread(() -> {
            try {
                // THE REAL CLASSPATH, which this deliberately did NOT pass until it was measured.
                //
                // The original note here said the type index was "a much larger and more speculative
                // thing to do on somebody's behalf", and warmed against an empty classpath so only the
                // parser, resolver and binding stack were loaded. That was the right call without a
                // number and the wrong one with it: restoring an editor tab on a .java file was
                // measured at 255 ms inside DockGroup.showContent, on the client thread, during the
                // frame after F6 -- and an analysis against the real classpath is what that is.
                //
                // So the speculation is now on this side of the boundary, where it is free if wrong: a
                // daemon thread at MIN_PRIORITY that nothing waits on, overlapping whatever the game
                // does next. The index is shared per classpath, so warming THIS classpath is what the
                // editor's own first analysis will look in -- warming any other would be work nobody
                // reads, which is the same silent failure the glyph warm has to avoid.
                //
                // The honest cost is memory: a full index is ~18 MB, held for a process that may never
                // open a .java file. That is the trade, and it is stated rather than hidden.
                ready.analyzer().analyze("Warm", "final class Warm { void f() { int i = 0; } }",
                        classpath, ready.releaseLevel(), 0L).close();
            } catch (Throwable ignored) {
                // See the note above: an optimisation that fails is silent.
            }
        }, "crystalgui-java-warm");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void openEngine() {
        if (engine != null) return;
        // THE BAND'S LOADER IS SHARED, not opened here: Rhino ships in the same band as ECJ, and a
        // JavaScript language registering after this one reaches its adapters through the same host.
        // Opening a loader per language would be two copies of twenty jars for one process.
        EngineHost host = EngineHost.shared(source);
        if (host == null) return;
        try {
            engine = JavaEngine.over(host);
        } catch (RuntimeException unavailable) {
            System.err.println("[crystalgui] the Java engine did not open; the editor will colour but "
                    + "not analyse: " + unavailable);
        }
    }

    private static LanguageServices servicesFor(TextBuffer buffer, Resource resource,
                                                List<String> classpath) {
        JavaEngine ready = engine();
        // NULL RATHER THAN A BROKEN SERVICES OBJECT. No engine is a legitimate deployment -- the editor
        // colours from the grammar and analyses nothing -- and it is the state a first launch is in while
        // the band is still arriving.
        if (ready == null) return null;
        return new JavaLanguageServices(buffer, ready, scheduler, classNameFor(resource), classpath);
    }

    /**
     * The compilation unit name for a document.
     *
     * <p><b>It has to match the type the file declares</b>, or ECJ reports "The public type X must be
     * defined in its own file" — a diagnostic about the analyser's own bookkeeping, on the author's
     * first line, saying nothing they can act on. The file name is the answer, because that is the rule
     * the language itself uses.</p>
     *
     * <p>An unsaved document has no name, and {@code Script} is as good as anything: a file with no path
     * cannot declare a public type that disagrees with it.</p>
     */
    static String classNameFor(Resource resource) {
        if (resource == null || resource.name() == null || resource.name().isEmpty()) return "Script";
        String name = resource.name();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.isEmpty() ? "Script" : stem;
    }

    /** The staged-directory source a dev run sets up, or nothing. @see EngineHost#defaultSource */
    public static EngineSource defaultSource() {
        return EngineHost.defaultSource();
    }

    /**
     * Which Java classes a script may reach — process-wide, for the same reason JavaScript's is.
     *
     * <p>One allowlist read by every surface that could leak a name: the executor's loader gate, the
     * ahead-of-time scan of the compiled bytes, the type index, and completion. A policy each of them
     * could be told separately is a policy some of them would be told, and a class offered by the popup
     * and refused at run time is a worse failure than either restriction alone.</p>
     *
     * <p><b>Read §19.1 before relying on this.</b> For Java it is a <em>guardrail</em>, not a security
     * boundary: compiled bytecode links what it links, {@code SecurityManager} is gone, and a script that
     * can reach reflection can resolve a name this never sees. It keeps honest scripts honest and keeps
     * the tool from teaching an API the deployment refuses. {@link ScriptPolicy#UNSAFE} is what makes it
     * worth anything at all.</p>
     */
    private static ScriptPolicy policy = ScriptPolicy.allowAll();

    /**
     * Restricts every Java surface at once — the <b>only</b> way to set it.
     *
     * <p>{@code ScriptPolicy.denying(ScriptPolicy.UNSAFE)} is the posture this was built for: everything
     * reachable except the handful of routes out of a class filter. An allowlist wide enough to be usable
     * is thousands of entries, and a control nobody writes is worse than a leaky one that gets used.</p>
     */
    public static synchronized void restrictTo(@Nullable ScriptPolicy target) {
        policy = target == null ? ScriptPolicy.allowAll() : target;
    }

    /** The policy every Java surface obeys. */
    public static synchronized ScriptPolicy policy() {
        return policy;
    }

    /**
     * The engine, opened on first use and <b>retried until it opens</b>.
     *
     * <h3>Why this is not resolved once at registration</h3>
     *
     * <p>It was, and a completed download would never have been used. {@code EngineHost.shared} returns
     * null <em>without caching the failure</em>, so a retry works — but this class resolved the engine
     * once during {@code register()} and, worse, returned early when it failed, so the services factory
     * was never registered either. A band arriving two minutes into a session would have downloaded,
     * verified, extracted, reported progress the whole way, and then sat unused with nothing to say why.
     * </p>
     *
     * <p>Resolving here costs a null check per document open and retries for free, because a document
     * cannot open before a workbench exists and a workbench is later than registration.</p>
     *
     * <p><b>What it does not fix:</b> an editor already open when the band lands still has no analysis
     * until its document is reopened. Re-attaching services to a live document is
     * {@code TextFileDocument}'s business, and this is deliberately not that.</p>
     */
    public static synchronized JavaEngine engine() {
        if (engine != null) return engine;
        openEngine();
        // A BAND THAT ARRIVED LATE IS ANNOUNCED, or only documents opened AFTER it ever benefit.
        //
        // D22 made this resolve retry per document instead of caching the first failure, which is what
        // lets a downloaded band become usable at all. What it could not do is reach a document that was
        // already open: services are attached once, at creation, so an editor on screen when the band
        // landed stayed dark until it was closed and reopened -- the feature working for some files and
        // not others, which reads as a defect in the analysis rather than in the wiring.
        //
        // Emitted from here because this is the moment the answer changes, and it is reached from
        // `servicesFor` on a document open -- the UI thread. @see LanguageRegistry#onCapabilityChanged
        if (engine != null) LanguageRegistry.capabilityChanged();
        return engine;
    }

    public static synchronized boolean isAvailable() {
        return engine != null;
    }

    /**
     * Forgets the engine, and closes the shared band host.
     *
     * <p>Does not unregister: a document opened afterwards would then get services over a closed engine,
     * which fails at the first analysis rather than at the point the decision was made. Shutdown is the
     * end of the process in every host that has one, and a host that genuinely restarts the stack should
     * be re-registering rather than half-tearing-down. Closing the shared host from here is the same
     * reasoning: this is process end, and every other language in that band is ending with it.</p>
     */
    public static synchronized void shutdown() {
        if (engine == null) return;
        try {
            engine.close();
        } catch (IOException ignored) {
            // Nothing above this can act on it, and the process is ending.
        }
        engine = null;
        EngineHost.shutdown();
    }
}
