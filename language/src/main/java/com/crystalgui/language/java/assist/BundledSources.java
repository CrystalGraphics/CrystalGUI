package com.crystalgui.language.java.assist;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>Which jars on the classpath ship their own sources</b> — {@code assets/&lt;namespace&gt;/sources/},
 * discovered rather than declared.
 *
 * <h3>The convention</h3>
 *
 * <p>M13 §25.4. A jar makes its own declarations quotable in the in-game editor's documentation popup by
 * putting loose {@code .java} files under {@code assets/<its modid>/sources/}, mirroring the package
 * layout:</p>
 *
 * <pre>{@code assets/mymod/sources/com/example/mymod/api/Thing.java}</pre>
 *
 * <p>That is the whole contract. <b>No registration call, no entry in our source, nothing to be added to
 * a list</b> — the same shape {@code CgUiSpriteRegistry} already uses to let a resource pack ship a theme
 * by shipping JSON and a PNG. In Gradle it is one line:</p>
 *
 * <pre>{@code tasks.jar { from(sourceSets.main.get().allJava) { into("assets/mymod/sources") } } }</pre>
 *
 * <h3>Why one namespace per project and not one shared directory</h3>
 *
 * <p>It began as a two-entry constant naming CrystalGUI and CrystalGraphics, which works for exactly the
 * two projects that can edit this file. <b>A list a third party has to be added to is a list a third party
 * cannot use</b>, and the convention exists for third parties.</p>
 *
 * <p>Per namespace rather than everything under {@code assets/crystalgui/} because CrystalGraphics is a
 * standalone rendering library used by mods with no CrystalGUI anywhere in the pack, and a jar shipping
 * them an {@code assets/crystalgui/} directory is claiming a namespace it does not own. The paths beneath
 * are package paths, so two namespaces cannot collide in any case.</p>
 *
 * <h3>Why it must be a scan, and what that costs</h3>
 *
 * <p>A {@code ClassLoader} cannot enumerate what is under a prefix — the same fact that forced
 * {@code ScriptNameEnvironment.isPackage} to be inverted rather than delegated. {@code getResources}
 * answers only for a name you can already spell, and the namespace is precisely what is not known. So the
 * jars are opened and their central directories read.</p>
 *
 * <p><b>Measured</b> against 359 jars and 268,187 entries, which is a modded classpath's scale:
 * <b>232 ms cold, ~105 ms warm</b> — 67 ms of that is opening the files and the rest is walking entries.
 * Paid once per classpath and cached with the archives for the session, on the analysis thread; a session
 * that never hovers pays nothing, because nothing builds a {@link SourceArchives} until something asks.</p>
 *
 * <p>A {@code getEntry("assets/")} pre-filter to skip the walk was considered and rejected on that same
 * measurement: it skips nothing in production, where every mod jar has assets, and it would make discovery
 * depend on the jar's builder having written directory entries.</p>
 */
final class BundledSources {

    /**
     * Where <b>this</b> project ships its own.
     *
     * <p>Not a registration and not privileged — {@link #prefixesIn} finds it the same way it finds
     * anybody else's. It is named because the build writes to it and the tests assert against it.</p>
     */
    static final String PREFIX = "assets/crystalgui/sources/";

    private static final String ASSETS = "assets/";
    private static final String SOURCES = "/sources/";

    private BundledSources() {
    }

    /**
     * Every {@code assets/<namespace>/sources/} prefix any jar on {@code classpath} ships, in the order
     * the classpath names them.
     *
     * <p>Keyed on finding a real {@code .java} <b>file</b> rather than the directory entry above it. That
     * costs the full walk and buys independence from whether the jar's builder wrote directory entries at
     * all — and it means a returned prefix is one that definitely has something in it, which is what lets
     * the diagnostic line report what it found instead of that an object was constructed.</p>
     *
     * <p>Directory classpath entries are skipped, not walked: sources are injected at packaging time, so a
     * {@code build/classes} directory never holds them and recursing one would be a filesystem walk per
     * module for a guaranteed miss.</p>
     */
    static Set<String> prefixesIn(List<String> classpath) {
        return prefixesIn(classpath, null);
    }

    /**
     * The same, also walking {@code loader}'s own URLs.
     *
     * <p><b>Because discovery and reading must not be able to disagree.</b> A prefix found here is read
     * back through the classloader, so a jar the analysis classpath names but the loader does not have —
     * or the reverse — is a source that is discovered and unreadable, or readable and never discovered.
     * Both fail silently, with the popup simply staying thin. On 1.7.10 the loader <em>is</em> a
     * {@code URLClassLoader} ({@code LaunchClassLoader}), which makes it the authoritative answer there;
     * on a modern JVM the application loader is not one, and the classpath list covers it. Taking the
     * union means each covers the other's gap and neither has to be trusted alone.</p>
     */
    static Set<String> prefixesIn(List<String> classpath, ClassLoader loader) {
        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        if (classpath != null) {
            for (String entry : classpath) collect(entry, visited, prefixes);
        }
        for (ClassLoader at = loader; at != null; at = at.getParent()) {
            if (!(at instanceof URLClassLoader)) continue;
            for (URL url : ((URLClassLoader) at).getURLs()) {
                collect(fileOf(url), visited, prefixes);
            }
        }
        return prefixes;
    }

    private static void collect(String entry, Set<String> visited, Set<String> into) {
        if (entry == null || !entry.toLowerCase(Locale.ROOT).endsWith(".jar")) return;
        File jar = new File(entry);
        // A CLASSPATH REPEATS ITSELF -- a shadowed module and its origin, a jar named twice by two
        // resolutions, and now every jar reachable both ways -- and opening one twice is pure cost on the
        // slowest part of this.
        if (!jar.isFile() || !visited.add(jar.getAbsolutePath())) return;
        collectFrom(jar, into);
    }

    /** A {@code file:} URL as a path, or null for anything else a loader might be carrying. */
    private static String fileOf(URL url) {
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) return null;
        try {
            return new File(url.toURI()).getPath();
        } catch (URISyntaxException | RuntimeException notAPath) {
            // A URL with a space or an unusual escape. Losing one entry is a thinner popup, not a fault.
            return null;
        }
    }

    private static void collectFrom(File jar, Set<String> into) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                String name = all.nextElement().getName();
                if (!name.startsWith(ASSETS) || !name.endsWith(".java")) continue;
                int at = name.indexOf(SOURCES, ASSETS.length());
                if (at > 0) into.add(name.substring(0, at + SOURCES.length()));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A classpath names things that are not there, a mods folder holds files that are not jars,
            // and a half-written download is a real state to be in. None of them is this method's problem.
        }
    }

    /** {@code assets/crystalgraphics/sources/} → {@code crystalgraphics}, for the diagnostic line. */
    static String namespaceOf(String prefix) {
        if (prefix == null || !prefix.startsWith(ASSETS)) return String.valueOf(prefix);
        int end = prefix.indexOf('/', ASSETS.length());
        return end < 0 ? prefix.substring(ASSETS.length()) : prefix.substring(ASSETS.length(), end);
    }
}
