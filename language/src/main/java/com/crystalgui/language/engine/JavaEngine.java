package com.crystalgui.language.engine;

import com.crystalgui.language.engine.bridge.ScriptCompiler;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A live Java engine: a band's jars, an isolated loader, and the adapter on the far side of the bridge.
 *
 * <h3>The whole crossing, in one place</h3>
 *
 * <pre>
 *   host                          |  EngineClassLoader (child-first)
 *   ------------------------------+---------------------------------------
 *   ScriptCompiler   (interface)  |  EcjScriptCompiler  (implementation)
 *   ScriptCompiler.Result         |  org.eclipse.jdt.**  (invisible to host)
 * </pre>
 *
 * <p>The interface is in the bridge package, so both sides load it from the parent and mean the same
 * type. The implementation names ECJ, so only the child can load it. {@link #open} is the one place
 * those two facts meet, and if the bridge carve-out were ever removed this is the line that would throw
 * {@code ClassCastException: ScriptCompiler cannot be cast to ScriptCompiler}.</p>
 *
 * <h3>The adapter's own classes have to be on the child's classpath</h3>
 *
 * <p>Easy to miss, and it fails as a plain {@code ClassNotFoundException} for a class that plainly
 * exists: {@code EcjScriptCompiler} lives in <em>our</em> jar, not the engine's, so a loader built over
 * the engine jars alone cannot find it. {@link #open} adds this class's own code source to the child's
 * URLs for exactly that reason.</p>
 *
 * <h3>What this is not</h3>
 *
 * <p>Not the execution service. There is no script lifecycle here, no disposal of a previous run, no
 * safepoints and no kill switch — those are M7, and they are about a <em>running</em> script rather than
 * about reaching a compiler. This is the seam plus enough to prove it carries traffic.</p>
 */
public final class JavaEngine implements Closeable {

    /** Where the adapter lives, by name. Reached reflectively because the host cannot see the type. */
    private static final String ADAPTER = "com.crystalgui.language.java.EcjScriptCompiler";

    private final EngineClassLoader loader;
    private final ScriptCompiler compiler;
    private final EngineBand band;

    private JavaEngine(EngineBand band, EngineClassLoader loader, ScriptCompiler compiler) {
        this.band = band;
        this.loader = loader;
        this.compiler = compiler;
    }

    /**
     * Opens the engine for a band.
     *
     * @throws IllegalStateException when the band has no jars, or the adapter cannot be reached — both
     *                               are deployment faults, and both are worth failing on at the point
     *                               of opening rather than at first compile
     */
    public static JavaEngine open(EngineBand band, EngineSource source) throws IOException {
        ClassLoader host = JavaEngine.class.getClassLoader();
        EngineClassLoader loader = EngineClassLoader.over(band, withOwnClasses(source), host);
        try {
            Class<?> adapter = Class.forName(ADAPTER, true, loader);
            Object instance = adapter.getDeclaredConstructor().newInstance();
            // THE CAST THAT PROVES THE BRIDGE. Both sides resolved ScriptCompiler through the parent,
            // so they are one type. Without the carve-out this is where it would fail, with the least
            // helpful message the JVM produces.
            return new JavaEngine(band, loader, (ScriptCompiler) instance);
        } catch (ReflectiveOperationException unreachable) {
            loader.close();
            throw new IllegalStateException("engine band " + band + " loaded, but " + ADAPTER
                    + " could not be instantiated inside it", unreachable);
        } catch (RuntimeException failed) {
            loader.close();
            throw failed;
        }
    }

    /** The band's jars, plus the jar or directory this class itself came from. See the class note. */
    private static EngineSource withOwnClasses(EngineSource engines) {
        return band -> {
            List<java.net.URL> urls = new ArrayList<>(engines.jarsFor(band));
            if (urls.isEmpty()) return urls;
            java.security.CodeSource ours = JavaEngine.class.getProtectionDomain().getCodeSource();
            if (ours != null && ours.getLocation() != null) urls.add(ours.getLocation());
            return urls;
        };
    }

    public EngineBand band() {
        return band;
    }

    /** The compiler, on the far side of the bridge. */
    public ScriptCompiler compiler() {
        return compiler;
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
        return Math.min(EngineBand.hostFeatureVersion(), JlsLevel.highestAvailable(loader));
    }

    /**
     * Releases the engine.
     *
     * <p>Does <b>not</b> release anything a compile produced: those are bytes the caller owns, in a
     * loader the caller made. That separation is what lets a script be re-run — and the old one
     * collected — without restarting the engine. See {@link ScriptCompiler} on why bytes come back
     * rather than classes.</p>
     */
    @Override
    public void close() throws IOException {
        loader.close();
    }
}
