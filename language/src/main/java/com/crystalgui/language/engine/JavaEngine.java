package com.crystalgui.language.engine;

import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;

import java.io.Closeable;
import java.io.IOException;

/**
 * A live Java engine: the two ECJ adapters, reached through a band's {@link EngineHost}.
 *
 * <h3>What this owns and what it borrows</h3>
 *
 * <p>The loader is the host's. Rhino sits in the same band beside ECJ, and a JavaScript engine reaches
 * <em>its</em> adapters through the same {@link EngineHost} — so this class holds only what is Java's:
 * the compiler, the analyser, and the answer to "which Java level". {@link #open} builds a host of its
 * own for a caller that has none (a test over one band), and {@link #over} joins a shared one; only the
 * former is closed by {@link #close}, because closing a loader somebody else is using is how a second
 * engine fails with a {@code NoClassDefFoundError} on a class it loaded fine a moment ago.</p>
 *
 * <h3>What this is not</h3>
 *
 * <p>Not the execution service. There is no script lifecycle here, no disposal of a previous run, no
 * safepoints and no kill switch — those are {@code ScriptHost}, and they are about a <em>running</em>
 * script rather than about reaching a compiler.</p>
 */
public final class JavaEngine implements Closeable {

    /** The adapters, by name. Reached reflectively because the host cannot see their types. */
    private static final String COMPILER = "com.crystalgui.language.java.EcjScriptCompiler";
    private static final String ANALYZER = "com.crystalgui.language.java.EcjSourceAnalyzer";

    private final EngineHost host;
    private final boolean ownsHost;
    private final ScriptCompiler compiler;
    private final SourceAnalyzer analyzer;

    private JavaEngine(EngineHost host, boolean ownsHost, ScriptCompiler compiler,
                       SourceAnalyzer analyzer) {
        this.host = host;
        this.ownsHost = ownsHost;
        this.compiler = compiler;
        this.analyzer = analyzer;
    }

    /**
     * Opens the engine over a host of its own, for a band.
     *
     * @throws IllegalStateException when the band has no jars, or the adapter cannot be reached — both
     *                               are deployment faults, and both are worth failing on at the point
     *                               of opening rather than at first compile
     */
    public static JavaEngine open(EngineBand band, EngineSource source) throws IOException {
        EngineHost host = EngineHost.open(band, source);
        try {
            return new JavaEngine(host, true, host.adapter(COMPILER, ScriptCompiler.class),
                    host.adapter(ANALYZER, SourceAnalyzer.class));
        } catch (RuntimeException failed) {
            host.close();
            throw failed;
        }
    }

    /** The engine over a host somebody else owns — the shape a registered language uses. */
    public static JavaEngine over(EngineHost host) {
        return new JavaEngine(host, false, host.adapter(COMPILER, ScriptCompiler.class),
                host.adapter(ANALYZER, SourceAnalyzer.class));
    }

    public EngineBand band() {
        return host.band();
    }

    /** The compiler, on the far side of the bridge. */
    public ScriptCompiler compiler() {
        return compiler;
    }

    /** The analyser — diagnostics, semantic tokens and resolution. @see SourceAnalyzer */
    public SourceAnalyzer analyzer() {
        return analyzer;
    }

    /**
     * The Java level to compile for: as new as the band's compiler allows, but never newer than the
     * host can load.
     *
     * <p><b>The host's ceiling is the binding one and it is not negotiable</b> — a class file above it
     * is refused at load with {@code UnsupportedClassVersionError}, which names a version number and
     * says nothing about the script. The compiler's own maximum matters too, in the other direction: a
     * band whose ECJ predates the host would be asked for a level it does not know.</p>
     */
    public int releaseLevel() {
        return Math.min(EngineBand.hostFeatureVersion(), JlsLevel.highestAvailable(host.loader()));
    }

    /**
     * Releases the engine — and its host only if it opened one.
     *
     * <p>Does <b>not</b> release anything a compile produced: those are bytes the caller owns, in a
     * loader the caller made. That separation is what lets a script be re-run — and the old one
     * collected — without restarting the engine. See {@link ScriptCompiler} on why bytes come back
     * rather than classes.</p>
     */
    @Override
    public void close() throws IOException {
        if (ownsHost) host.close();
    }
}
