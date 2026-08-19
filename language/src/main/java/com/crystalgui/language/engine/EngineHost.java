package com.crystalgui.language.engine;

import com.crystalgui.core.async.Progress;

import com.crystalgui.language.platform.ScriptService;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.language.platform.ScriptServices;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

/**
 * One band's jars in one isolated loader, and the way to reach an adapter inside it — shared by every
 * engine that lives in that band.
 *
 * <h3>Why this is not part of {@link JavaEngine}</h3>
 *
 * <p>The band ships ECJ <em>and</em> Rhino in the same configuration, pinned together because both are
 * constrained by the same host Java version. They belong in one loader: two loaders over the same twenty
 * jars is twice the metaspace for the same classes, and worse, two copies of any type the engines happen
 * to share. So the loader is opened once per band and each engine asks it for its own adapters —
 * {@code JavaEngine} for the ECJ pair, a JavaScript engine for the Rhino pair — through
 * {@link #adapter}, which is the crossing itself.</p>
 *
 * <h3>The crossing, in one place</h3>
 *
 * <pre>
 *   host                          |  EngineClassLoader (child-first)
 *   ------------------------------+---------------------------------------
 *   bridge interface  (parent)    |  adapter class     (child, names the engine)
 * </pre>
 *
 * <p>The interface is in the bridge package, so both sides load it from the parent and mean the same
 * type. The implementation names the engine, so only the child can load it. {@link #adapter} is where
 * those two facts meet, and if the bridge carve-out were ever removed this is the line that would throw
 * {@code ClassCastException: ScriptCompiler cannot be cast to ScriptCompiler}.</p>
 *
 * <h3>The adapters' own classes have to be on the child's classpath</h3>
 *
 * <p>Easy to miss, and it fails as a plain {@code ClassNotFoundException} for a class that plainly
 * exists: an adapter lives in <em>our</em> jar, not the engine's, so a loader built over the engine jars
 * alone cannot find it. {@link #open} adds this class's own code source to the child's URLs for exactly
 * that reason.</p>
 *
 * <h3>Shared, so ownership is explicit</h3>
 *
 * <p>{@link #shared} opens the host's band once for the process and hands the same instance to every
 * language that registers. Closing an engine must therefore <b>not</b> close the host it was given —
 * {@link JavaEngine#close} releases nothing when it was built over a shared host — and {@link #shutdown}
 * is the one call that closes the loader, made by whoever ends the process.</p>
 */
public final class EngineHost implements Closeable {

    private final EngineBand band;
    private final EngineClassLoader loader;

    private EngineHost(EngineBand band, EngineClassLoader loader) {
        this.band = band;
        this.loader = loader;
    }

    /**
     * Opens a loader over {@code band}'s jars.
     *
     * @throws IllegalStateException when the band has no jars — a deployment fault worth failing on at
     *                               the point of opening rather than at first compile
     */
    public static EngineHost open(EngineBand band, EngineSource source) throws IOException {
        ClassLoader host = EngineHost.class.getClassLoader();
        return new EngineHost(band, EngineClassLoader.over(band, withOwnClasses(source), host));
    }

    /**
     * Instantiates an adapter <b>inside</b> the engine loader and hands it back as its bridge type.
     *
     * @throws IllegalStateException when the class cannot be reached, or was reached from the wrong side
     */
    public <T> T adapter(String className, Class<T> bridgeType) {
        try {
            Class<?> adapter = Class.forName(className, true, loader);
            // WHICH LOADER DEFINED IT IS THE ASSERTION, not a diagnostic nicety. `loadChildFirst` falls
            // back to the parent when the child has no such class -- which is right for an engine's own
            // dependencies and catastrophic here: the parent CAN load this class and cannot load the
            // engine, so the fallback succeeds and the failure surfaces much later as
            // `NoClassDefFoundError: org/eclipse/jdt/core/dom/AST` from inside a method that plainly
            // imports it. Caught once already, from a caller that put the wrong directory on the URLs.
            if (adapter.getClassLoader() != loader) {
                throw new IllegalStateException(className + " was loaded by "
                        + adapter.getClassLoader() + " rather than the engine loader — its own classes "
                        + "are missing from the engine's URLs, so it cannot see the engine");
            }
            return bridgeType.cast(adapter.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("the engine band loaded, but " + className
                    + " could not be instantiated inside it", unreachable);
        }
    }

    /**
     * The band's jars, plus the code source this class itself came from.
     *
     * <p><b>Read from a class in this module's MAIN output</b>, which is the whole subtlety: an adapter
     * lives beside this class, so pointing at any other directory — a test's output, say — leaves the
     * child unable to find it. See {@link #adapter} for what that failure looks like.</p>
     */
    public static EngineSource withOwnClasses(EngineSource engines) {
        return band -> {
            List<URL> urls = new ArrayList<>(engines.jarsFor(band));
            if (urls.isEmpty()) return urls;
            URL own = ownClasses();
            if (own != null) {
                urls.add(own);
            } else {
                // LOUD, because the downstream symptom names the wrong thing entirely. Without our own
                // classes on the child's URLs the adapter is not in the child, Class.forName falls back
                // to the parent, the parent loads it happily, and the first engine type it touches dies
                // as `NoClassDefFoundError: org/mozilla/javascript/ErrorReporter` — a message that points
                // at Rhino when the fault is that this module could not find itself.
                System.err.println("[crystalgui] the engine band cannot be opened: this module's own "
                        + "classes could not be located, so the adapters would load outside the band.");
            }
            return urls;
        };
    }

    /**
     * Where this module's classes live, as a URL a {@link java.net.URLClassLoader} can actually use.
     *
     * <p><b>Two sources, and both need normalising — that second part is the whole difficulty.</b> A
     * {@code CodeSource} is the direct answer and is what every environment but Minecraft gives: a plain
     * JVM, Gradle and the harness all report the jar or the classes directory. LaunchWrapper reports the
     * <em>class entry inside</em> the jar:</p>
     *
     * <pre>
     * jar:file:/…/crystalgui-1.0.0-dev.jar!/com/crystalgui/language/engine/EngineHost.class
     * </pre>
     *
     * <p>which is a perfectly good URL and a useless classpath root. Added verbatim it makes the child
     * loader look for classes <em>under a .class file</em>, so every {@code findClass} misses, delegation
     * falls to the parent, and the adapter is defined outside the band — the failure {@link #adapter}
     * exists to catch, reported against Rhino or ECJ rather than against this method.</p>
     *
     * <p>So whatever the source, the result is reduced to a root: an archive URL keeps only the archive,
     * and a file URL keeps only the part before this class's own package path.</p>
     */
    private static URL ownClasses() {
        CodeSource source = EngineHost.class.getProtectionDomain().getCodeSource();
        URL candidate = source == null ? null : source.getLocation();
        if (candidate == null) {
            candidate = EngineHost.class.getClassLoader()
                    .getResource(EngineHost.class.getName().replace('.', '/') + ".class");
        }
        return candidate == null ? null : asClasspathRoot(candidate);
    }

    /** Reduces a URL that may name a class entry to the root that contains it. */
    private static URL asClasspathRoot(URL url) {
        String text = url.toString();
        try {
            if (text.startsWith("jar:")) {
                int separator = text.indexOf("!/");
                // "jar:" is four characters; everything between it and "!/" is the archive itself.
                return separator > 0 ? new URL(text.substring(4, separator)) : url;
            }
            String path = EngineHost.class.getName().replace('.', '/') + ".class";
            return text.endsWith(path) ? new URL(text.substring(0, text.length() - path.length())) : url;
        } catch (java.net.MalformedURLException malformed) {
            return null;
        }
    }

    public EngineBand band() {
        return band;
    }

    /** The loader itself — for the one question only it can answer, which Java level it can compile. */
    EngineClassLoader loader() {
        return loader;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    // ── The process-wide host ───────────────────────────────────────────────────────────────────

    /**
     * Where a dev run says the staged bands are.
     *
     * <p>A directory laid out one subdirectory per band, which is what {@link EngineSource#directory}
     * reads and what a real deployment would ship. Absent everywhere else, and absent is fine.</p>
     *
     * <p><b>Here rather than on a language</b>, because what it names is a <em>band</em> — ECJ and Rhino
     * staged side by side in one directory, because they are pinned together and loaded together. It sat
     * on {@code JavaLanguage} while Java was the only engine, and the second language delegating to the
     * first for the location of its own jars is the shape that reads as a mistake even when it works.</p>
     */
    public static final String ENGINES_DIRECTORY_PROPERTY = "crystalgui.engines.dir";

    /**
     * Where the bands live inside a jar that bundles them — one directory per band beneath it.
     *
     * <p>Under {@code assets/} so it travels with the resources a mod jar already carries and cannot
     * collide with a package name.</p>
     */
    public static final String BUNDLED_ENGINES_ROOT = "assets/crystalgui/engines";

    /**
     * The staged directory a dev run points at, then the bands bundled in the jar, then nothing.
     *
     * <h3>The property stays first, and it is not merely legacy</h3>
     *
     * <p>A dev run and {@code runHarness} both set it, and both want the jars Gradle just resolved rather
     * than whatever a previous build extracted — otherwise changing a pin in {@code language/build.gradle
     * .kts} would leave the old band in the cache and the new one unused, which reads as the pin not
     * taking effect. An override that only applies when somebody sets it costs a property read.</p>
     *
     * <h3>All three answers are legitimate deployments</h3>
     *
     * <p>Extraction needs somewhere to write, which is {@link ScriptService#cacheRoot()} — so a process
     * with no platform registered simply has no second candidate, exactly as before this existed. And a
     * jar that bundles no band yields an empty listing rather than an error: shipping the editor without
     * the engines is a supported build, and it degrades to grammar-only colouring like every other
     * absence in this stack.</p>
     */
    public static EngineSource defaultSource() {
        return defaultSource(Progress.NONE);
    }

    /**
     * The same, reporting a download into {@code progress}.
     *
     * <p>{@code firstOf} takes the first NON-EMPTY answer, so the download is only ever reached by a host
     * whose band is not the bundled one — no ordinary launch touches the network, and the third candidate
     * costs a resource lookup that misses.</p>
     */
    public static EngineSource defaultSource(Progress progress) {
        return EngineSource.firstOf(configuredSource(), bundledSource(), downloadedSource(progress));
    }

    private static EngineSource configuredSource() {
        String configured = System.getProperty(ENGINES_DIRECTORY_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) return EngineSource.NONE;
        Path root = Path.of(configured.trim());
        return Files.isDirectory(root) ? EngineSource.directory(root) : EngineSource.NONE;
    }

    /** The fallback: fetch the band this host needs, verified against the shipped manifest. */
    private static EngineSource downloadedSource(Progress progress) {
        Path cacheRoot = CgPlatform.get(ScriptServices.SERVICE).cacheRoot();
        if (cacheRoot == null) return EngineSource.NONE;
        return EngineSource.downloadedFrom(EngineHost.class.getClassLoader(),
                BUNDLED_ENGINES_ROOT, cacheRoot.resolve("engines"),
                progress == null ? Progress.NONE : progress);
    }

    private static EngineSource bundledSource() {
        Path cacheRoot = CgPlatform.get(ScriptServices.SERVICE).cacheRoot();
        if (cacheRoot == null) return EngineSource.NONE;
        return EngineSource.extractedFrom(EngineHost.class.getClassLoader(),
                BUNDLED_ENGINES_ROOT, cacheRoot.resolve("engines"));
    }

    private static EngineHost shared;

    /**
     * The host's own band, opened once and shared by every language.
     *
     * <p>Null when the source has nothing for this band, or the loader would not open — both legitimate
     * deployments, and both reported once on stderr rather than thrown, because an environment with no
     * engines is the one the whole stack is designed to degrade through.</p>
     */
    public static synchronized EngineHost shared(EngineSource source) {
        if (shared != null) return shared;
        if (source == null) return null;
        EngineBand band = EngineBand.detect();
        try {
            if (source.jarsFor(band).isEmpty()) {
                System.err.println("[crystalgui] no engine jars for band " + band + " from " + source
                        + "; the editor will colour but not analyse");
                return null;
            }
            shared = open(band, source);
        } catch (IOException | RuntimeException unavailable) {
            System.err.println("[crystalgui] the engine band did not open; the editor will colour but "
                    + "not analyse: " + unavailable);
            return null;
        }
        return shared;
    }

    /** The shared host if one opened, without trying to open it. */
    public static synchronized EngineHost sharedIfOpen() {
        return shared;
    }

    /** Closes the shared host. Process end only — see the class note on ownership. */
    public static synchronized void shutdown() {
        if (shared == null) return;
        try {
            shared.close();
        } catch (IOException ignored) {
            // Nothing above this can act on it, and the process is ending.
        }
        shared = null;
    }
}
