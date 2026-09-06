package com.crystalgui.language;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.grammar.TreeSitterLanguages;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.text.syntax.LanguageKinds;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.util.concurrent.Callable;

/**
 * <b>Switches on every language this module ships</b> — grammars, then the analysis engines.
 *
 * <h3>Why this is here and not in a host</h3>
 *
 * <p>It was written twice, in a harness scene and in a Minecraft client, and both copies named exactly
 * the same four types out of {@code language/} and nothing else. Neither was a <em>platform</em> concern:
 * which grammars exist, which engines exist, what order they go in and what it means when one is missing
 * are facts about this module, and a host that has to know them is a host that can get them wrong. The
 * two copies had already diverged on the thing that matters most: one caught {@code Throwable} around
 * each engine, and the other caught <b>nothing at all</b> — so the failure {@link #registerEngine}
 * exists for would have come straight out of a constructor and taken the harness with it.</p>
 *
 * <p>So a host now says <em>"turn the languages on"</em> and this decides what that means. A seventh
 * grammar or a third engine is a line here rather than a line in every host.</p>
 *
 * <h3>...and a host no longer says even that</h3>
 *
 * <p>This <b>is</b> the {@link LanguageKinds} service — one class per feature, so there is no
 * {@code LanguageContribution} beside it whose only real content would be this class's name — and
 * {@link LanguageRegistry#bootstrap()} finds it. Two hosts used to call {@link #registerAll()}, each with
 * a paragraph explaining why, and a third got no grammars, no ECJ and no Rhino with nothing to see: an
 * editor colouring from the built-in lexers is a supported configuration, so the absence reads as a
 * deployment rather than as a call somebody forgot.</p>
 *
 * <p>The host call survives as a <b>warm-up</b>, which is the honest name for what it always was —
 * see the timing section below. Skipping it now costs a stall on the first editor open rather than the
 * languages themselves, and that is the whole change: forgetting became a performance question instead
 * of a silent feature loss.</p>
 *
 * <h3>Everything degrades, nothing is fatal</h3>
 *
 * <p>Three tiers, and each absence is silent by design: no engine → grammar colouring; no grammar
 * module → core's word-list lexers; neither → plain text. That is what makes this safe to call anywhere,
 * and also why every miss is <b>reported</b> — a capability that can be skipped without a symptom has to
 * say it is on, or "the editor does not do that" and "the editor could not switch that on" are the same
 * observation.</p>
 *
 * <h3>Call it early, and on the thread that owns the UI</h3>
 *
 * <p>Measured at 443 ms on a Minecraft client — the engine band's loader, six grammars and a tree-sitter
 * native — and it was being paid on the first keystroke that opened an editor, as part of a four-second
 * freeze. Moving it to FML init made it free: the <em>same thread</em>, but at a moment when a loading
 * screen is already up and nobody is waiting.</p>
 *
 * <p><b>Deliberately not made concurrent</b>, which was the tempting version. Registration writes to
 * {@code LanguageRegistry} and to {@code CommandRegistry}, whose {@code byId} is a plain
 * {@code LinkedHashMap}, and the UI reads both — so a background register is the same class of hazard as
 * emitting a signal from a worker, which this engine has already paid for once. Early on the right
 * thread costs nothing and risks nothing.</p>
 *
 * <p>Idempotent: {@code JavaLanguage.register} returns early once an engine is open, and the grammars
 * read-and-add rather than replace.</p>
 */
public final class LanguageStack implements LanguageKinds {

    /** {@code ServiceLoader} needs a public no-argument constructor. Holds nothing; {@link #registerAll} is static. */
    public LanguageStack() {
    }

    /** @see #registerAll() */
    @Override
    public void register() {
        registerAll();
    }

    /**
     * Registers the grammars, then the engines, and reports whatever could not be switched on.
     *
     * <p><b>Before anything opens a document.</b> {@code LanguageRegistry} is consulted when an editor is
     * built, so a file already open keeps whichever tokenizer it was handed. That is now guaranteed
     * rather than arranged — the registry's first read runs this through {@link LanguageKinds} — so a
     * host calling it directly is <b>warming</b> it at a moment of its own choosing, which is what the
     * timing section on this class is about.</p>
     *
     * <p>Order between the grammars and the engines is free — each reads the existing entry and carries
     * the other's contribution over, which is what the two registries were built for. Grammars go first
     * only because that is the tier a host is most likely to have.</p>
     */
    public static void registerAll() {
        try {
            TreeSitterLanguages.register();
        } catch (Throwable t) {
            // register() already handles a native that will not load and says so. This is for the
            // failure it does not promise to contain -- a missing jar, a linkage error out of the
            // binding itself -- because losing the grammars must never take the editor with it.
            System.err.println("[crystalgui] tree-sitter grammars are off; the built-in lexers will "
                    + "colour: " + t);
        }
        registerEngine("Java", JavaLanguage::register);
        registerEngine("JavaScript", JsLanguage::register);
        // THE RUN SHELL IS NOT CONTRIBUTED HERE ANY MORE. It is a ServiceLoader entry in this
        // module's own META-INF/services, so the jar being present is what offers it -- the same door
        // the engine's panels and a mod's own extension come through. ScriptWorkbench IS that extension.
    }

    /**
     * Registers one engine, and <b>never lets it take the editor down</b>.
     *
     * <p>{@link EngineHost} already treats an absent band as legitimate — it returns false and prints a
     * line. What it does not promise is that a band which is <em>present but unopenable</em> fails the
     * same way, and under LaunchWrapper that is exactly what happens: the staged jars are found, so
     * registration proceeds past the early return, and then {@code EngineHost.adapter} throws
     * {@code NoClassDefFoundError: org/mozilla/javascript/ErrorReporter} out of
     * {@code EngineClassLoader} — a loader-visibility problem between the band's isolation and the
     * host's own class loader.</p>
     *
     * <p>An {@code Error} is not caught by anything upstream, so it propagated out of {@code initGui} and
     * killed the client. <b>That is strictly worse than having no engines</b>, which is a supported
     * configuration: the editor is meant to colour and not analyse. Catching {@code Throwable} rather
     * than {@code Exception} is the whole point — {@code NoClassDefFoundError} is an {@code Error}, and
     * the copy of this that caught {@code Exception} is the reason this lives in one place now.</p>
     */
    private static void registerEngine(String name, Callable<Boolean> register) {
        try {
            if (Boolean.TRUE.equals(register.call())) return;
            System.err.println("[crystalgui] " + name + " analysis is off: no engine band under "
                    + System.getProperty(EngineHost.ENGINES_DIRECTORY_PROPERTY, "<unset>"));
        } catch (Throwable t) {
            // PRINTED, NOT LOGGED, and that is load-bearing rather than a house style. This module keeps
            // log4j off its compile classpath entirely, and the host that used to own these lines handed
            // the throwable straight to a logger -- on 1.7.10 that is log4j 2.0-beta9, whose
            // ThrowableProxy walks every frame and Class.forName's it on the APP loader to annotate the
            // line with a jar name. This stack can contain engine-band frames by construction: it is
            // raised by EngineClassLoader, about classes only the band can define. The lookup then fails
            // INSIDE the logging call, so the act of reporting the failure destroys the report and takes
            // the client with it -- and the original error is never seen. printStackTrace resolves
            // nothing and cannot.
            System.err.println("[crystalgui] " + name + " analysis is off: the engine band did not open. "
                    + "The editor will colour but not analyse.");
            t.printStackTrace();
        }
    }
}
