package com.crystalgui.language.js;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.engine.bridge.JsSourceAnalyzer;
import com.crystalgui.language.java.HostClasspath;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.java.JavaLanguageServices;
import com.crystalgui.language.java.TypeIndex;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;

import javax.annotation.Nullable;

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
    private static final String ANALYZER = "com.crystalgui.language.js.RhinoSourceAnalyzer";
    private static final String EXECUTOR = "com.crystalgui.language.js.RhinoExecutor";

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
        analyzer = null;
        executor = null;
        scheduler = null;
    }
}
