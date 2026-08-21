package com.crystalgui.language.engine;

import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.engine.bridge.Decompiler;
import com.crystalgui.language.engine.bridge.TypeBytes;
import com.crystalgui.language.java.classpath.PlatformTypeBytes;
import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

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
    private static final String COMPILER = "com.crystalgui.language.java.ecj.EcjScriptCompiler";
    private static final String ANALYZER = "com.crystalgui.language.java.ecj.EcjSourceAnalyzer";
    private static final String DECOMPILER = "com.crystalgui.language.java.cfr.CfrDecompiler";

    private final EngineHost host;
    private final boolean ownsHost;
    private final ScriptCompiler compiler;
    private final SourceAnalyzer analyzer;

    /**
     * Null when this band has no decompiler staged, which is an ordinary deployment.
     *
     * <p>Unlike the compiler and the analyser, this one is <b>optional</b>: a band assembled before CFR
     * was added, or one a host trimmed, still opens and still does everything else. So it is resolved
     * defensively rather than through the constructor, and its absence costs the viewer its fallback and
     * nothing else.</p>
     */
    private final Decompiler decompiler;

    /** The live bytes both sides resolve through — also what feeds the decompiler. @see #decompile */
    private final TypeBytes types;

    private JavaEngine(EngineHost host, boolean ownsHost, ScriptCompiler compiler,
                       SourceAnalyzer analyzer) {
        this.host = host;
        this.ownsHost = ownsHost;
        // THE ONE PLACE THE LIVE TIERS ARE INSTALLED, and it is here because it is the one place every
        // consumer of a compiler passes through -- ScriptHost, a language's register(), a test. Doing it
        // at a call site instead would mean a compiler that resolves live for the runner and not for
        // whatever obtained one second, which is the disagreement the seam exists to prevent.
        //
        // `PlatformTypeBytes.of` answers NONE when no platform is registered, so a harness and every
        // test are unaffected and resolution goes entirely through the classpath, exactly as before.
        //
        // Which namespace the runtime speaks is PROBED rather than passed -- see PlatformMappings -- so
        // there is nothing to decide here and no way for two engines to decide it differently.
        // ONE OBJECT, BOTH SIDES. Handing the analyser its own would let the editor and the runner
        // disagree about what exists -- which they did: the compiler resolved live and the analyser read
        // files, so an obfuscated host compiled and ran a script the editor could not resolve at all.
        TypeBytes types = PlatformTypeBytes.of();
        this.types = types;
        this.compiler = compiler.resolveAgainst(types);
        this.analyzer = analyzer.resolveAgainst(types);
        this.decompiler = optionalAdapter(host);
    }

    /**
     * The decompiler adapter, or null.
     *
     * <p>Caught rather than thrown, and that is the whole difference between this and the two adapters
     * above. A missing compiler is a broken deployment worth failing at the point of opening; a missing
     * decompiler is a band that predates the feature, and the right response is a viewer that shows
     * attached source and says nothing for a class without any. <b>Announced on stderr</b> either way,
     * because "the decompiler is absent" and "the decompiler returned nothing" look identical on
     * screen — the same rule the mapping and the live name environment are held to.</p>
     */
    private static Decompiler optionalAdapter(EngineHost host) {
        try {
            return host.adapter(DECOMPILER, Decompiler.class);
        } catch (RuntimeException | LinkageError absent) {
            System.err.println("[crystalgui] no decompiler in engine band " + host.band()
                    + "; a class with no attached source will show nothing: " + absent);
            return null;
        }
    }

    /**
     * The decompiled source of a class, or null when nothing can produce one.
     *
     * <p><b>Fed from {@link TypeBytes} first and the classpath second</b>, which is what makes this
     * different from pointing a decompiler at a jar: on a live Minecraft host the bytes are the ones the
     * runtime holds — after every transformer and mixin, and already remapped to readable names — so
     * the view shows the class as the game has it rather than as it shipped. Where no platform is
     * registered, which is the harness and every test, {@code TypeBytes.NONE} answers null for everything
     * and the classpath fallback carries it.</p>
     */
    @Nullable
    public String decompile(String binaryName, List<String> classpath) {
        Decompiler ready = decompiler;
        if (ready == null) return null;
        ClasspathBytes files = new ClasspathBytes(classpath);
        try {
            return ready.decompile(binaryName, internal -> {
                byte[] live = types.readable(internal);
                return live != null ? live : files.read(internal);
            });
        } finally {
            files.close();
        }
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
