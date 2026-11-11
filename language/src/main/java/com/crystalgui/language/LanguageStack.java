package com.crystalgui.language;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.grammar.TreeSitterLanguages;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.js.JsLanguage;
import com.crystalgui.text.syntax.LanguageKinds;
import com.crystalgui.text.syntax.LanguageRegistry;

import java.util.concurrent.Callable;

/**
 * Every language this module ships: the tree-sitter grammars, then the Java and JavaScript engines. The
 * {@link LanguageKinds} service {@link LanguageRegistry#bootstrap()} finds, so no host calls it.
 *
 * <ul>
 *   <li>Each missing piece is logged and skipped: no engine colours by grammar, no grammar by core's lexers.</li>
 *   <li>On the thread that owns the UI: registering writes {@code LanguageRegistry} and {@code CommandRegistry}.</li>
 *   <li>Idempotent.</li>
 * </ul>
 */
public final class LanguageStack implements LanguageKinds {

    /** For {@code ServiceLoader}. */
    public LanguageStack() {
    }

    @Override
    public void register() {
        registerAll();
    }

    /**
     * What registering costs that any thread may pay, paid ahead on a worker: the tree-sitter native, the engine band
     * opened and its classes defined, the classpath's archives checked. Registers nothing; {@link #registerAll} then
     * finds each done.
     *
     * <pre>{@code
     * plan.task("mymod:languages").work(LanguageStack::prepare).render(task -> {
     *     LanguageRegistry.bootstrap();   // the render thread's part: the registries
     *     return true;
     * }).start();
     * }</pre>
     */
    public static void prepare() {
        TreeSitterLanguages.loadAhead();
        try {
            JavaLanguage.openAhead();
            JsLanguage.openAhead();
            HostClasspath.detect();
        } catch (Throwable later) {
            // Registering opens it again and reports what failed
        }
    }

    /** Registers the grammars, then the engines; each carries the other's entry over, so the order is free. */
    public static void registerAll() {
        try {
            TreeSitterLanguages.register();
        } catch (Throwable t) {
            // A missing jar or a linkage error out of the binding: losing the grammars must not take the editor
            System.err.println("[crystalgui] tree-sitter grammars are off; the built-in lexers will "
                    + "colour: " + t);
        }
        registerEngine("Java", JavaLanguage::register);
        registerEngine("JavaScript", JsLanguage::register);
    }

    /**
     * One engine, caught as {@code Throwable}: a band present but unopenable throws {@code NoClassDefFoundError} out
     * of {@code EngineClassLoader}, and no engine is a supported state where a crash is not.
     */
    private static void registerEngine(String name, Callable<Boolean> register) {
        try {
            if (Boolean.TRUE.equals(register.call())) return;
            System.err.println("[crystalgui] " + name + " analysis is off: no engine band under "
                    + System.getProperty(EngineHost.ENGINES_DIRECTORY_PROPERTY, "<unset>"));
        } catch (Throwable t) {
            // Printed, not logged: 1.7.10's log4j resolves each frame on the app loader, which cannot see the band's
            // classes, and the lookup failing inside the log call loses the report and the client
            System.err.println("[crystalgui] " + name + " analysis is off: the engine band did not open. "
                    + "The editor will colour but not analyse.");
            t.printStackTrace();
        }
    }
}
