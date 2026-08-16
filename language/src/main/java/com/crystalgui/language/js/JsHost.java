package com.crystalgui.language.js;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.bridge.JsExecutor;
import com.crystalgui.language.run.ConsoleFilter;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.ScriptRef;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.text.syntax.Language;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The JavaScript execution service — {@code ScriptRuntime}, over Rhino.
 *
 * <h3>Host side of a two-sided runtime</h3>
 *
 * <p>This is what the Run panel holds; {@link RhinoExecutor} is what names Rhino, and it is defined by
 * the band loader. The split is the same law {@code ScriptHost} obeys from the Java side, and it is
 * enforced from both ends: {@code language.run} is not parent-first, so the child could not implement
 * {@code ScriptRuntime} even if it wanted to, and {@code RunShellIsEngineNeutralTest} refuses the shell
 * any knowledge of either engine.</p>
 *
 * <p>So everything crossing between them is a JDK type — the console is two {@code Consumer}s, input is
 * a {@code Supplier}, the sandbox is a {@code Predicate}. The wiring to {@code ScriptOutput},
 * {@code ScriptInput} and the policy object all happens on this side.</p>
 *
 * <h3>What is here at M10.2</h3>
 *
 * <p>Enough for the shell to recognise a {@code .js} file as a script and to refuse one that does not
 * compile: {@link #language()}, {@link #compileScript}, and the console filter. Running arrives at
 * M10.5. {@link #runAsync} therefore throws rather than doing nothing, because a Run button that
 * silently succeeded would be the one failure mode that reads as the feature working.</p>
 */
public final class JsHost implements ScriptRuntime {

    /** The extension a JavaScript file is named with. @see #compileScript */
    private static final String[] EXTENSIONS = {".js", ".mjs", ".cjs"};

    private final JsExecutor executor;

    /**
     * Where run states are reported, or null for a host nobody is watching — a dedicated server that
     * runs scripts and has no rail to show them in, or a test.
     */
    @Nullable
    private RunSessions sessions;

    public JsHost(JsExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Language language() {
        return Language.JAVASCRIPT;
    }

    @Override
    public JsHost reportTo(@Nullable RunSessions target) {
        this.sessions = target;
        return this;
    }

    /**
     * Compiles what a Run is about.
     *
     * <p>The file's <b>whole name</b> reaches the engine, extension included — the opposite of the Java
     * side, which strips it to derive a class name. Rhino has no such requirement and puts the name
     * verbatim into every stack frame, so {@code Main.js} is what a runtime error will say and therefore
     * what the console's link filter has to match. Stripping it would break the link and nothing
     * else, which is exactly the kind of fault that survives a release.</p>
     */
    @Override
    public Compiled compileScript(String scriptName, String source, Map<String, String> bindingTypes) {
        String name = scriptName == null || scriptName.isEmpty() ? "script.js" : scriptName;
        // BINDING TYPES ARE IGNORED, and that is not an omission. A binding is declared to the Java
        // compiler as a typed field because Java needs one; JavaScript takes the VALUE at run time and
        // has nothing to declare, so the type half of `ScriptBindings` simply does not apply here. The
        // parameter stays on the seam because the seam serves both.
        return new JsCompiled(this, executor.compile(name, source == null ? "" : source));
    }

    @Override
    public Thread runAsync(Compiled compiled, Map<String, Object> bindings,
                           @Nullable BiConsumer<ScriptRef, Throwable> onFailure) {
        throw new UnsupportedOperationException(
                "the JavaScript runtime lands at M10.5; this build compiles but does not run scripts");
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    /**
     * What in this runtime's output a click can navigate.
     *
     * <p>Empty until M10.5, because a filter is a promise that a row is a place: offering one that
     * matched a frame nothing could open would make every JS stack line look clickable and do nothing.
     * @see RhinoStackFrameFilter, which arrives with the runtime that produces the frames</p>
     */
    @Override
    public List<ConsoleFilter> consoleFilters() {
        return Collections.emptyList();
    }

    @Override
    public void close() {
        stop();
    }

    /** Whether {@code fileName} is a script this runtime would compile — for a caller with no registry. */
    public static boolean isJavaScript(@Nullable String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    /** The bridge's compilation, wearing the seam's interface. */
    private static final class JsCompiled implements Compiled {

        private final JsHost host;
        private final JsExecutor.Compiled compiled;
        private ScriptRef ref;

        JsCompiled(JsHost host, JsExecutor.Compiled compiled) {
            this.host = host;
            this.compiled = compiled;
        }

        @Override
        public boolean successful() {
            return compiled.successful();
        }

        @Override
        public List<String> messages() {
            return compiled.messages();
        }

        @Override
        public Compiled withSource(Resource file) {
            // NO ORIGIN YET. `ScriptRef.Origin` is how a console row finds which of the script's lines
            // printed it, and Rhino's answer needs a live interpreter frame -- which exists only once
            // something runs (M10.5). `Origin.NONE` is the honest placeholder and is a real answer: the
            // row still shows, still filters, still stops; only the column naming its source is empty.
            this.ref = file == null ? null : new ScriptRef(file, ScriptRef.Origin.NONE);
            return this;
        }

        @Override
        public ScriptRef ref() {
            return ref;
        }

        @Override
        public ScriptRuntime runtime() {
            return host;
        }

        /** The engine-side handle, for {@code runAsync} when it lands. */
        JsExecutor.Compiled engineCompiled() {
            return compiled;
        }
    }
}
