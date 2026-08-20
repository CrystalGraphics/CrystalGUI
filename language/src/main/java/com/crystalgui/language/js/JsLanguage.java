package com.crystalgui.language.js;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
import com.crystalgui.language.engine.bridge.MemberNameMapper;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.MemberResolution;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.java.JavaLanguageServices;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;

import javax.annotation.Nullable;

import java.util.Set;

/**
 * Puts the JavaScript engine behind every {@code .js} document, and tells the Run panel it can run one —
 * the one call an application has to make.
 *
 * <h3>The same shape {@code JavaLanguage} has, which is the point</h3>
 *
 * <p>Two registrations, both additive, both order-independent: {@code withServices} on the existing
 * {@code .js} entry, so whichever of this and {@code TreeSitterLanguages.register()} runs second keeps
 * what the other put there; and a {@code ScriptRuntimes} contribution, so the Run panel finds a runtime
 * without ever naming a language. A second engine registering the same way is what those two registries
 * exist for — and this being the second is what proves they work.</p>
 *
 * <h3>One band host, two engines</h3>
 *
 * <p>{@link EngineHost#shared} opens the band's jars once for the process. Rhino ships in the same
 * configuration as ECJ, pinned together because both are bounded by the same host Java version, so a
 * loader of this language's own would be a second copy of twenty jars and a second identity for every
 * type the two happen to share. Whichever language registers first opens the host; the other joins it.
 * Closing is the process's, through {@code EngineHost.shutdown()}.</p>
 *
 * <h3>Not calling this is invisible, which is why it is a class of its own</h3>
 *
 * <p>The editor still colours — tree-sitter has been parsing JavaScript since M3 — so nothing looks
 * broken. What is missing is everything an engine knows: no diagnostics reach the document's set, so a
 * syntax error is silent until the script is run; no scopes, so a parameter and a global take one
 * colour; and Run does not recognise the file, so the command greys out with no explanation. It reads as
 * those features not being built.</p>
 */
public final class JsLanguage {

    /** The extensions this claims — the three the grammar and the registry already agree on. */
    private static final String[] EXTENSIONS = {"js", "mjs", "cjs"};

    private static JsSourceAnalyzer analyzer;
    private static JsExecutor executor;
    private static JobScheduler scheduler;

    /** The adapters, by name. Reached through the band host because the host cannot see their types. */
    private static final String ANALYZER = "com.crystalgui.language.js.rhino.RhinoSourceAnalyzer";
    private static final String EXECUTOR = "com.crystalgui.language.js.rhino.exec.RhinoExecutor";

    private JsLanguage() {
    }

    /** Registers with the shared scheduler and whatever engines this environment offers. */
    public static boolean register() {
        return register(JobScheduler.shared(), defaultSource());
    }

    /**
     * Opens Rhino for this host's band and puts it behind the {@code .js} registrations.
     *
     * <p>Idempotent, and <b>returns whether it worked</b> rather than throwing, for the reason
     * {@code JavaLanguage} gives: an environment with no engines is a legitimate one, and refusing to
     * start over it would make the editor unusable for exactly the case the whole stack is designed to
     * degrade through.</p>
     */
    public static synchronized boolean register(JobScheduler jobs, EngineSource source) {
        if (analyzer != null) {
            // ALREADY REGISTERED IS NOT ALREADY FINISHED. Registering JavaScript BEFORE Java leaves the
            // interop tier unlent, and a second `register()` call -- which is exactly what a host or a test
            // that opens both languages makes -- used to return here without ever retrying it. So the
            // member list behind `new java.util.ArrayList().` silently fell back to reflection, or to
            // nothing at all, depending only on which language registered first.
            lendTheJavaEngine();
        warm();
            return true;
        }

        EngineHost host = EngineHost.shared(source);
        if (host == null) return false;
        try {
            analyzer = host.adapter(ANALYZER, JsSourceAnalyzer.class);
            executor = host.adapter(EXECUTOR, JsExecutor.class);
        } catch (RuntimeException unavailable) {
            System.err.println("[crystalgui] the JavaScript engine did not open; the editor will colour "
                    + "but not analyse: " + unavailable);
            analyzer = null;
            executor = null;
            return false;
        }

        JsLanguage.scheduler = jobs;
        // THE POLICY REACHES A FRESHLY OPENED ANALYSER. A host that restricted before registering -- or
        // that registers twice -- must not end up with an analyser obeying allow-all.
        analyzer.restrictTo(policy::allowsClass);
        // AND THE COMPATIBILITY BAND REACHES A FRESHLY OPENED ANALYSER, for the reason the policy above
        // does: a host that set it before registering must not end up with an analyser warning about
        // nothing.
        if (!refusedByTarget.isEmpty()) analyzer.compatibleWith(refusedByTarget, targetLabel);
        // AND THE MAPPINGS REACH A FRESHLY OPENED ENGINE, for the reason the policy does: a host that
        // installed them before registering must not end up with an engine mapping nothing.
        MemberNameMapper mapper = mapperFor(mappings);
        analyzer.useMemberNames(mapper);
        executor.useMemberNames(mapper);
        lendTheJavaEngine();

        // THE EXISTING ENTRIES, WITH SERVICES ADDED -- not new ones. Every extension is read and
        // rewritten individually because they need not share an entry: nothing stops a host registering
        // a different tokenizer for `.mjs`, and replacing all three from one read would quietly impose
        // whatever `.js` happened to have.
        for (String extension : EXTENSIONS) {
            LanguageRegistry.Entry current = LanguageRegistry.forFileName("any." + extension);
            LanguageRegistry.registerExtensions(
                    current.withServices(JsLanguage::servicesFor), extension);
        }

        // AND THAT JAVASCRIPT CAN RUN. The Run panel is written against `ScriptRuntime` and finds its
        // runtimes here rather than by asking this class, so the panel is not edited for a second
        // language. The cache root is the workbench's to choose and Rhino has nothing to cache, so it
        // is accepted and ignored -- see JsHost.
        // THE SCHEDULER TRAVELS WITH THE HOST: a run reports its outcome from the script's own thread,
        // and putting a thrown exception on the document's line means reaching the document, which is
        // UI-thread work. The host hops through the same scheduler the analyser uses.
        ScriptRuntimes.contribute(Language.JAVASCRIPT, cacheRoot -> new JsHost(executor, jobs));
        return true;
    }

    /** Whether the Java engine has already been lent. @see #lendTheJavaEngine */
    private static boolean javaLent;

    /**
     * What a script may reach — <b>one policy for the process</b>.
     *
     * <p>Process-wide rather than per workbench, and that is the point: the same allowlist is read by the
     * executor's class shutter, by resolution, by the completion list and by the type-index view, and a
     * policy each of them could be told separately is a policy some of them would be told. An allowlist is
     * a deployment decision, so there is one deployment's worth of it.</p>
     */
    private static ScriptPolicy policy = ScriptPolicy.allowAll();

    /**
     * Restricts every JavaScript surface at once.
     *
     * <p>The <b>only</b> way to set it. {@code JsHost.restrictTo} forwards here rather than keeping its own,
     * because a class refused at run time and offered by the completion list is a worse failure than either
     * restriction alone — and two fields is how that happens.</p>
     */
    public static synchronized void restrictTo(@Nullable ScriptPolicy target) {
        policy = target == null ? ScriptPolicy.allowAll() : target;
        if (analyzer != null) analyzer.restrictTo(policy::allowsClass);
    }

    /** The policy every JavaScript surface obeys. */
    public static synchronized ScriptPolicy policy() {
        return policy;
    }

    // ── The compatibility band (§10.3b) ────────────────────────────────────────────

    /** What the target band refuses, empty when the target is this host. @see #compatibleWith */
    private static Set<String> refusedByTarget = Set.of();

    /** How the target is named to the author. @see #compatibleWith */
    private static String targetLabel = "older";

    /**
     * Warn about syntax an older host would refuse — <b>off by default</b>.
     *
     * <p>Default "this host", because a warning nobody asked for about a deployment nobody named is
     * noise: most scripts are written and run on one machine. A pack author shipping to 1.7.10 sets
     * {@link EngineBand#JAVA_8} and is told, before a player ever sees it, which lines will not load.</p>
     *
     * <p>Read from the target band's <b>measured</b> probe file, which ships as a resource for exactly
     * this: those jars are not on a deployment's classpath, so the older parser cannot be asked and the
     * answer has to travel as data. {@code RhinoCapabilityProbeTest} asserts the resource still matches
     * the jars it describes, so it cannot quietly stop being true.</p>
     *
     * <p>Only ever warns <em>downward</em>. A target at or above this host produces nothing: a construct
     * the local engine refuses is already a syntax error, and saying it twice in two severities is worse
     * than saying it once.</p>
     *
     * @param target the band a script must also load on, or null for "this host"
     */
    public static synchronized void compatibleWith(@Nullable EngineBand target) {
        EngineBand host = EngineBand.detect();
        refusedByTarget = target == null || target.minimumFeatureVersion() >= host.minimumFeatureVersion()
                ? Set.of() : refusedBy(target);
        targetLabel = target == null ? "older" : "Java " + target.minimumFeatureVersion();
        if (analyzer != null) analyzer.compatibleWith(refusedByTarget, targetLabel);
    }

    /** Which constructs a band's shipped probe file records as refused. */
    private static Set<String> refusedBy(EngineBand band) {
        String resource = "/assets/crystalgui/language/rhino-"
                + band.minimumFeatureVersion() + ".properties";
        try (java.io.InputStream in = JsLanguage.class.getResourceAsStream(resource)) {
            // NO FILE IS NOT AN ERROR. Only band 8's ships -- it is the only one anything targets --
            // and a host asking about a band with no file gets silence rather than a failure, which is
            // the same way every other absent capability in this stack degrades.
            if (in == null) return Set.of();
            java.util.Properties measured = new java.util.Properties();
            measured.load(in);
            Set<String> refused = new java.util.LinkedHashSet<>();
            for (String key : measured.stringPropertyNames()) {
                if (!key.startsWith("syntax.")) continue;
                String verdict = measured.getProperty(key);
                if (verdict != null && verdict.startsWith("refused")) {
                    refused.add(key.substring("syntax.".length()));
                }
            }
            return java.util.Collections.unmodifiableSet(refused);
        } catch (java.io.IOException unreadable) {
            return Set.of();
        }
    }

    /** How member names are written and shown. @see #useMemberNames */
    private static MappingSet mappings = MappingSet.IDENTITY;

    /**
     * Installs the readable↔runtime member-name mapping, in <b>both</b> directions at once.
     *
     * <p>One entry point, for the reason the policy has one: the executor translates on the way out and the
     * member lists rename on the way in, and a deployment that could set them separately would eventually
     * set one — leaving a completion list offering names the runtime refuses, or a runtime accepting names
     * the editor never showed.</p>
     *
     * <p>The {@code MappingSet} is adapted to the bridge's string interface here, because the engine side
     * cannot see {@code language.map}. @see MemberNameMapper</p>
     */
    public static synchronized void useMemberNames(@Nullable MappingSet target) {
        mappings = target == null ? MappingSet.IDENTITY : target;
        MemberNameMapper mapper = mapperFor(mappings);
        if (analyzer != null) analyzer.useMemberNames(mapper);
        if (executor != null) executor.useMemberNames(mapper);
    }

    /** What member names are mapped through. */
    public static synchronized MappingSet memberNames() {
        return mappings;
    }

    /**
     * A {@link MappingSet} as the bridge's string interface.
     *
     * <p>Identity maps to {@link MemberNameMapper#IDENTITY} by reference, which is what lets the executor
     * leave Rhino's own wrap factory in place rather than wrapping every Java value for nothing.</p>
     */
    private static MemberNameMapper mapperFor(MappingSet set) {
        if (set == null || set.isIdentity()) return MemberNameMapper.IDENTITY;
        // THE OWNER IS CONSULTED, exactly as the bytecode remapper does it, and for the same reason:
        // MCP's entries carry no owner, so `add` (func_76163_a) and `run` (func_99999_d) are ordinary
        // readable names that a blind reverse lookup would rename on ANY receiver -- including a plain
        // java.util.List, through this very membrane. Cached, because the membrane asks about the same
        // few types on every property access. @see MemberResolution
        MemberResolution.Members members =
                MemberResolution.caching(MemberResolution.fromClassLoader(JsLanguage.class.getClassLoader()));
        return new MemberNameMapper() {
            @Override
            public String runtimeName(String ownerInternalName, String readableName) {
                String method = MemberResolution.runtimeMethod(set, members, ownerInternalName, readableName);
                // A METHOD FIRST, THEN A FIELD. A name is one or the other and the two tables are separate;
                // asking both and preferring the method is what makes `world.getBlock` and `world.rand`
                // both work without the caller having to know which it is.
                return method.equals(readableName)
                        ? MemberResolution.runtimeField(set, members, ownerInternalName, readableName)
                        : method;
            }

            @Override
            public String readableName(String ownerInternalName, String runtimeName) {
                String method = set.readableMethod(ownerInternalName, runtimeName);
                return method.equals(runtimeName)
                        ? set.readableField(ownerInternalName, runtimeName) : method;
            }

            @Override
            public boolean mapsAnythingIn(String ownerInternalName) {
                return set.mapsAnyMemberOf(ownerInternalName);
            }
        };
    }

    /**
     * The classpath index a {@code Java.type("…")} string completes from, or null.
     *
     * <p>The <b>Java</b> language's index, shared rather than rebuilt: it is one scan of one classpath and
     * fifty thousand entries, and the question "which types exist" has the same answer whichever language
     * is asking. Null when no Java engine opened, which costs the class-name list and nothing else.</p>
     */
    @Nullable
    private static TypeIndex typeIndex() {
        return JavaLanguage.engine() == null ? null
                : JavaLanguageServices.typeIndexFor(HostClasspath.detect());
    }

    /** The same index a document would get — for a test that builds its own services. */
    @Nullable
    public static TypeIndex typeIndexForTesting() {
        return typeIndex();
    }

    /**
     * Hands the analyser the Java engine, when this build has one.
     *
     * <p>What it buys is the interop tier: a Java type reached from a script is answered by the resolver
     * that answers for Java, so the member list behind {@code new java.util.ArrayList().} is the same
     * list a {@code .java} file would show — generic substitution, accessibility, deprecation marks and
     * the binding keys that quote a signature out of {@code src.zip}.</p>
     *
     * <p><b>Tried again whenever a document opens</b>, rather than only at registration, so the order
     * the two languages register in does not decide whether interop works. It would be easy to require
     * Java first — every host we ship does it that way — and the failure would be silent: the member list
     * would quietly fall back to reflection, which answers plausibly and less well. An ordering rule
     * nothing enforces is one somebody eventually breaks.</p>
     */
    /**
     * One throwaway parse, off this thread, so a restored {@code .js} tab is not the first.
     *
     * <p>The same measurement and the same reasoning as {@code JavaLanguage.warm}: the first analysis a
     * Rhino engine performs pays for loading and JIT-ing the parser, and in a client the caller who pays
     * it is a restored editor tab on the frame after F6. Read that method's note for why this is a daemon
     * thread rather than a scheduler job, and why a failure here is silent.</p>
     */
    private static void warm() {
        JsSourceAnalyzer ready = analyzer;
        if (ready == null) return;
        Thread worker = new Thread(() -> {
            try {
                ready.analyze("warm.js", "function f(a) { return a + 1; } f(1);", 0L).close();
            } catch (Throwable ignored) {
                // An optimisation that fails is silent; a real file will report it loudly enough.
            }
        }, "crystalgui-js-warm");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void lendTheJavaEngine() {
        if (analyzer == null || javaLent) return;
        JavaEngine java = JavaLanguage.engine();
        if (java == null) return;
        javaLent = true;
        analyzer.useJavaEngine(java.analyzer(), HostClasspath.detect(), java.releaseLevel());
    }

    /** Re-lends the Java engine — for a host that opened the two languages in the other order. */
    public static synchronized void useJavaEngine() {
        lendTheJavaEngine();
    }

    private static synchronized LanguageServices servicesFor(TextBuffer buffer,
                                                            @Nullable Resource resource) {
        // THE LATE CHANCE. A document cannot open before both languages have registered, so this is the
        // last moment the ordering could still be wrong -- and the first at which it certainly is not.
        lendTheJavaEngine();
        return new JsLanguageServices(buffer, analyzer, scheduler, sourceNameFor(resource), resource,
                typeIndex(), JsLanguage::policy);
    }

    /**
     * What the engine should call this document in a message and a stack frame.
     *
     * <p>The file's whole name, extension included — unlike Java's, which is stripped to a class name.
     * Rhino puts it verbatim into every frame, so it is what the console's link filter matches; a name
     * that disagreed with the file would produce frames that look right and open nothing.</p>
     *
     * <p>An unsaved document has none, and {@code script.js} is as good as anything: there is no file to
     * navigate to, so a frame naming it correctly links nowhere, which is the truth.</p>
     */
    static String sourceNameFor(@Nullable Resource resource) {
        if (resource == null || resource.name() == null || resource.name().isEmpty()) return "script.js";
        return resource.name();
    }

    /** The staged-directory source a dev run sets up, or nothing. @see EngineHost#defaultSource */
    public static EngineSource defaultSource() {
        return EngineHost.defaultSource();
    }

    /**
     * Puts every process-wide posture back to its default — <b>for a test</b>.
     *
     * <p>The policy and the member-name mapping are deployment decisions and are therefore static, which
     * makes them the two things a test can leak into every test that runs after it in the same JVM. Both
     * {@code JsSandboxTest} and {@code JsRemapTest} restore their own in an {@code @After} and say so; a
     * test that fails before its {@code @After} runs restores neither, and the next class to run sees an
     * allowlist or a rename nothing in it installed — reported as resolution being broken.</p>
     *
     * <p>One call, so a new test cannot restore half of it. Deliberately not part of {@link #shutdown()},
     * which a host calls and which must not silently widen a policy a deployment set.</p>
     */
    public static synchronized void resetPosturesForTesting() {
        restrictTo(null);
        useMemberNames(null);
    }

    /** The analyser, or null when no engine opened. */
    public static synchronized JsSourceAnalyzer analyzer() {
        return analyzer;
    }

    /** The executor, or null when no engine opened — for a caller building its own {@link JsHost}. */
    public static synchronized JsExecutor executor() {
        return executor;
    }

    public static synchronized boolean isAvailable() {
        return analyzer != null;
    }

    /**
     * Forgets the engine.
     *
     * <p>Does not close the shared band host — that is {@code EngineHost.shutdown()}'s, at process end,
     * because the Java engine is in the same loader and is not ending because this one did. Does not
     * unregister either, for the reason {@code JavaLanguage} gives: a document opened afterwards would
     * get services over a closed engine, failing at the first analysis rather than at the point the
     * decision was made.</p>
     */
    public static synchronized void shutdown() {
        javaLent = false;
        // BOTH POSTURES, not one. The mapping was restored and the policy was not, so a host or a test
        // that restricted and then shut down left every later JavaScript surface in the process obeying
        // an allowlist nothing could see -- which reads as resolution being broken rather than as a leak.
        mappings = MappingSet.IDENTITY;
        policy = ScriptPolicy.allowAll();
        analyzer = null;
        executor = null;
        scheduler = null;
    }
}
