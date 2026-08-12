package com.crystalgui.language.engine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * An engine's jars, loaded in isolation and <b>child-first</b>.
 *
 * <h3>Why isolation, and why it is not optional here</h3>
 *
 * <p>Three things fall out of it, and the first is the one that forces it: <b>other mods ship Rhino.</b>
 * Several do, at versions we do not choose, and the flat classpath a mod loader builds means whichever
 * wins is whichever was seen first. An engine on the application classpath would be a coin flip between
 * our pinned version and someone else's, resolved differently on every install. The other two are
 * consequences worth having anyway: the sandbox gets a natural enforcement point (§19), and an engine
 * can be dropped wholesale by dropping its loader.</p>
 *
 * <h3>Child-first, with a parent-delegated list that is the whole design</h3>
 *
 * <p>Java's default is parent-first, which would find the host's Rhino before ours and defeat the point.
 * So this asks its own URLs first — but a loader that asked its own URLs for <em>everything</em> would
 * load a second copy of the classes the host and the engine must <b>share</b>, and then a perfectly
 * correct cast fails with the least helpful message the JVM produces:</p>
 *
 * <pre>ClassCastException: com.example.Bridge cannot be cast to com.example.Bridge</pre>
 *
 * <p>Two classes with the same name from two loaders are different types. So the prefixes in
 * {@link #PARENT_FIRST} are delegated upward unconditionally: the JDK itself, and the <b>bridge
 * package</b> — the interfaces the host calls the engine through. Anything the host and the engine
 * both name has to be on that list, and anything on it stops being isolated. Keeping it to one package
 * is what keeps that trade honest.</p>
 *
 * <h3>The adapter is loaded HERE, not by the host</h3>
 *
 * <p>An adapter references ECJ or Rhino types directly, so it can only be loaded by a loader that can
 * see them — this one. That is why {@link EngineSource} is expected to include our own classes beside
 * the engine's: the host loads the bridge interface (parent), the child loads the implementation, and
 * the two meet at the one package they share.</p>
 */
public final class EngineClassLoader extends URLClassLoader {

    /**
     * Prefixes that always come from the parent.
     *
     * <p>The JDK, because loading a second {@code java.lang.String} is not merely wrong but forbidden,
     * and the bridge package, because it is the one thing host and engine must agree on the identity of.
     * {@code javax.} and {@code jdk.} are here for the same reason as {@code java.}; {@code sun.} because
     * a compiler reaches into it on some JVMs and a duplicate would be a second copy of internal state.</p>
     *
     * <p><b>Adding to this list gives away isolation for whatever it names</b>, so it is a decision
     * rather than a convenience — the reason it is a constant with this note on it.</p>
     */
    static final String[] PARENT_FIRST = {
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            BridgePackage.NAME + ".",
            // THE DOCUMENT MODEL AND THE LANGUAGE SPI, added when the analyzer landed. An engine
            // produces `SyntaxToken`s and `Diagnostic`s and `SymbolInfo`s -- that is the vocabulary the
            // whole stack speaks -- so those types must mean the same thing on both sides or every
            // result would have to be translated through a parallel set of bridge-only copies.
            //
            // It is a real widening of the shared surface and it is affordable for one reason: this
            // package tree is pure Java with no natives, no GL and no engine, which is the same
            // property that lets it live in `core/` at all. Nothing here can drag an engine across.
            //
            // WITHOUT THIS the failure is the confusing one: JavaEngine puts our own code source on the
            // child's URLs so the adapter can be found, so the child would happily define its OWN
            // SyntaxToken -- and a perfectly correct assignment fails with
            // `SyntaxToken cannot be cast to SyntaxToken`.
            "com.crystalgui.text.",
    };

    private final EngineBand band;

    private EngineClassLoader(EngineBand band, URL[] urls, ClassLoader parent) {
        super(urls, parent);
        this.band = band;
    }

    /**
     * Builds a loader over one band's jars.
     *
     * @param parent what the JDK and the bridge package come from — normally this class's own loader
     * @throws IOException            if a jar cannot be read
     * @throws IllegalStateException  if the source has nothing for this band, which is a deployment
     *                                fault rather than a runtime condition: an engine that is meant to
     *                                be present and is not should say so at the point of loading rather
     *                                than as a {@code ClassNotFoundException} several frames later
     */
    public static EngineClassLoader over(EngineBand band, EngineSource source, ClassLoader parent)
            throws IOException {
        List<URL> urls = source.jarsFor(band);
        if (urls.isEmpty()) {
            throw new IllegalStateException("no jars for engine band " + band + " from " + source
                    + " — the band is selected correctly and there is nothing to load");
        }
        return new EngineClassLoader(band, urls.toArray(new URL[0]),
                parent == null ? EngineClassLoader.class.getClassLoader() : parent);
    }

    public EngineBand band() {
        return band;
    }

    /**
     * Child-first, except for {@link #PARENT_FIRST}.
     *
     * <p>Synchronized on the class-loading lock rather than on {@code this}: parallel-capable loaders
     * lock per class name, and locking the loader itself is how a plugin classloader deadlocks against
     * an application thread loading in the other order.</p>
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isParentFirst(name) ? loadFromParent(name) : loadChildFirst(name);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private Class<?> loadFromParent(String name) throws ClassNotFoundException {
        ClassLoader parent = getParent();
        if (parent != null) return parent.loadClass(name);
        return findSystemClass(name);
    }

    private Class<?> loadChildFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException notOurs) {
            // Falling back is what makes the engine's own dependencies on the host work at all -- an
            // engine legitimately references things we did not ship for it. This is the direction that
            // is safe: the parent's copy is only reached when we genuinely have none.
            return loadFromParent(name);
        }
    }

    static boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Resources follow the same rule as classes, and forgetting that is a quiet failure.
     *
     * <p>ECJ reads {@code messages.properties} for every diagnostic it produces. Parent-first here
     * finds the host's copy if the host happens to have one — a different version's messages, or none —
     * and the symptom is diagnostics that render as raw keys rather than an exception anybody notices.</p>
     */
    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        if (own != null) return own;
        ClassLoader parent = getParent();
        return parent == null ? super.getResource(name) : parent.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>();
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) all.add(own.nextElement());
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> theirs = parent.getResources(name);
            while (theirs.hasMoreElements()) {
                URL url = theirs.nextElement();
                if (!all.contains(url)) all.add(url);
            }
        }
        return java.util.Collections.enumeration(all);
    }

    @Override
    public String toString() {
        return "EngineClassLoader[" + band + ", " + getURLs().length + " jars]";
    }

    /**
     * The one package host and engine share by name.
     *
     * <p>A holder rather than a literal in the array above, so that the package this names and the
     * package the bridge interfaces actually live in cannot drift apart without a compile error —
     * {@link #NAME} is derived from a real class's package rather than typed as a string.</p>
     */
    static final class BridgePackage {
        static final String NAME = EngineClassLoader.class.getPackage().getName() + ".bridge";

        private BridgePackage() {
        }
    }

    static {
        // Parallel-capable: this loader locks per class name (see loadClass), so declaring it lets the
        // JVM skip the coarse per-loader lock. Without the registration the fine-grained locking above
        // is not merely unused -- getClassLoadingLock returns `this`, so it is actively misleading.
        registerAsParallelCapable();
    }
}
