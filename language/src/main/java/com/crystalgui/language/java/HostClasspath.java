package com.crystalgui.language.java;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a script compiles against: the classpath of the process that is running it.
 *
 * <h3>The running process, not a declared one</h3>
 *
 * <p>A script's whole point is calling into the application hosting it, so the compiler has to resolve
 * against what that application actually loaded — not a list somebody maintains. Every platform exposes
 * a route to it and they are all different, which is why this is a probe rather than a constant
 * (§15.2): {@code LaunchClassLoader.getSources()} on 1.7.10, the loader's URL list on modern loaders,
 * plain {@code URLClassLoader.getURLs()} in the harness and in tests.</p>
 *
 * <p>Three strategies are tried in order and the results are merged, because on a real loader stack the
 * answer is genuinely spread across them: a modded launch has the application classpath property
 * <em>and</em> a URL-bearing loader with mod jars the property never mentions.</p>
 *
 * <h3>What this deliberately does NOT do</h3>
 *
 * <p><b>On a Minecraft host the disk view is a lie</b> and this is only the baseline (§15.5). Production
 * 1.7.10 ships obfuscated jars remapped as they load, and every platform runs transformers that add
 * members no class file on disk has — so a file-based classpath resolves against something that is not
 * what will execute. The answer there is a live name environment reading post-transform bytecode, and
 * this class is what it falls back to for everything ordinary. Said here because a file list that looks
 * complete is exactly how that gets forgotten.</p>
 */
public final class HostClasspath {

    private HostClasspath() {
    }

    /** The best available answer for the loader that loaded this class. */
    public static List<String> detect() {
        return detect(HostClasspath.class.getClassLoader());
    }

    /**
     * The best available answer for {@code loader}.
     *
     * <p>Entries that do not exist are dropped: a classpath assembled by a launcher routinely names
     * things that are not there, and handing a nonexistent path to the compiler produces a warning
     * about a file the author has never heard of on every single analysis.</p>
     */
    public static List<String> detect(ClassLoader loader) {
        Set<String> entries = new LinkedHashSet<>();
        addUrlsOf(loader, entries);
        addReflectiveSources(loader, entries);
        addSystemProperty(entries);

        List<String> existing = new ArrayList<>(entries.size());
        for (String entry : entries) {
            Path path = new File(entry).toPath();
            if (Files.exists(path)) existing.add(entry);
        }
        return existing;
    }

    /** Every {@link URLClassLoader} in the parent chain, which covers the harness and plain JVMs. */
    private static void addUrlsOf(ClassLoader loader, Set<String> into) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (!(current instanceof URLClassLoader)) continue;
            for (URL url : ((URLClassLoader) current).getURLs()) {
                String path = toPath(url);
                if (path != null) into.add(path);
            }
        }
    }

    /**
     * {@code getSources()} and friends, by reflection.
     *
     * <p>Reflection rather than an import, and that is the point of the whole class: {@code core/} and
     * {@code language/} may not name a loader type, so the only way to ask {@code LaunchClassLoader} —
     * which is Forge's, on the other side of an import guard — is to ask any loader whether it happens
     * to have the method. A loader that does not simply contributes nothing.</p>
     */
    private static void addReflectiveSources(ClassLoader loader, Set<String> into) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            for (String method : new String[]{"getSources", "getURLs"}) {
                Object result = invokeQuietly(current, method);
                if (result instanceof Object[]) {
                    for (Object item : (Object[]) result) collect(item, into);
                } else if (result instanceof Iterable) {
                    for (Object item : (Iterable<?>) result) collect(item, into);
                }
            }
        }
    }

    private static void collect(Object item, Set<String> into) {
        if (item instanceof URL) {
            String path = toPath((URL) item);
            if (path != null) into.add(path);
        } else if (item instanceof File) {
            into.add(((File) item).getAbsolutePath());
        } else if (item instanceof Path) {
            into.add(((Path) item).toAbsolutePath().toString());
        }
    }

    private static Object invokeQuietly(Object target, String method) {
        try {
            java.lang.reflect.Method found = target.getClass().getMethod(method);
            // No setAccessible: a public method on a public class needs none, and forcing it on a
            // non-public one is exactly the reflective overreach a module system refuses. A route that
            // needs it is a route that should be added explicitly rather than prised open.
            return found.invoke(target);
        } catch (Throwable absentOrRefused) {
            // Absent on most loaders and refused on some. Both mean "this route has nothing", which is
            // the ordinary case rather than a failure -- there are two other routes.
            return null;
        }
    }

    /**
     * {@code java.class.path}, which is the only route on a JVM 9+ where the application loader is no
     * longer a {@link URLClassLoader}.
     *
     * <p>Worth stating because it is the trap in the other direction: code written against
     * {@code URLClassLoader.getURLs()} on Java 8 returns nothing at all on 9+ and looks like an empty
     * classpath rather than an unavailable one.</p>
     */
    private static void addSystemProperty(Set<String> into) {
        String property = System.getProperty("java.class.path");
        if (property == null || property.isEmpty()) return;
        for (String entry : property.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) into.add(new File(trimmed).getAbsolutePath());
        }
    }

    /** A {@code file:} URL as a plain path, or null for anything else. */
    private static String toPath(URL url) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) return null;
        try {
            return new File(url.toURI()).getAbsolutePath();
        } catch (Exception notAFile) {
            // A jar-in-jar or a nested URL. Not addressable as a path, and a compiler cannot read it
            // anyway -- on the platforms where that matters, §15.5's name environment is the answer.
            return null;
        }
    }
}
