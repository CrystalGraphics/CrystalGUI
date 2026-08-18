package com.crystalgui.language.java.classpath;

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
        // LAST, so an application jar shadowing a platform class still wins.
        addJavaClassLibrary(entries);

        List<String> existing = new ArrayList<>(entries.size());
        for (String entry : entries) {
            if (isUsable(new File(entry))) existing.add(entry);
        }
        return existing;
    }

    /**
     * A directory, or a file that really opens as an archive.
     *
     * <h3>Existing is not enough, and the difference is a crash</h3>
     *
     * <p>ECJ's {@code FileSystem} builds a {@code ClasspathJar} for every non-directory entry and calls
     * {@code initialize()} on it inside a {@code catch (IOException)} that <b>ignores the failure</b>. The
     * entry stays in the list with its {@code packageCache} left null — and every later
     * {@code getModulesDeclaringPackage} on it throws {@code NullPointerException} from inside ECJ.</p>
     *
     * <p>Which is not a compile error. It surfaces through {@code ClasspathLocation.isPackage} into
     * {@code LookupEnvironment.askForType}, out of {@code BinaryTypeBinding.availableMethods} — and JDT's
     * DOM catches it: {@code ITypeBinding.getDeclaredMethods()} wraps its work in
     * {@code catch (RuntimeException)}, logs "Could not retrieve declared methods" with no stack, and
     * returns an <b>empty array</b>. So every binary CLASS reports no methods while its fields are fine
     * and its interfaces are fine — JDT synthesises interface members rather than reading them off the
     * binding. {@code System.out.} offered nothing, {@code String.} offered {@code compareTo} alone from
     * {@code Comparable}, and {@code Minecraft.} offered {@code IPlayerUsage}'s three.</p>
     *
     * <p>A modded launch is where this comes from: {@code LaunchClassLoader.getSources()} reports what
     * the loader was given, which routinely includes natives directories, an absent coremod and entries
     * that are simply not archives. On an ordinary JVM every entry opens and the whole failure mode is
     * unreachable, which is why it missed the harness and every test.</p>
     *
     * <p>Opening each archive once at detection is the cost, and it is paid once per process — against a
     * compiler that would otherwise be handed an entry it cannot use and would fail on obscurely.</p>
     */
    private static boolean isUsable(File entry) {
        Path path = entry.toPath();
        if (!Files.exists(path)) return false;
        if (entry.isDirectory()) return true;
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(entry)) {
            return archive.size() >= 0;
        } catch (Exception notAnArchive) {
            // A native library, a text file, a truncated download. Not something a compiler can read, and
            // ECJ's own answer to being handed one is a null cache and a crash three layers away.
            return false;
        }
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
     * <b>The JDK's own class library, on a host that has no JRT filesystem.</b>
     *
     * <p>Java 9 replaced {@code rt.jar} with a module image the compiler reads through the {@code jrt:}
     * filesystem, which needs no classpath entry — so on every JVM this code has ever been developed or
     * tested on, {@code java.lang} resolves with nothing here doing anything. On <b>Java 8 there is no
     * such filesystem</b>: the platform classes live in {@code java.home/lib/rt.jar}, that jar is on no
     * URL list, in no {@code java.class.path}, and in nothing {@code getSources()} returns, and a
     * compiler handed a classpath without it cannot resolve {@code java.lang.Object}.</p>
     *
     * <h3>What that actually looked like</h3>
     *
     * <p>Not an error — a <b>silently empty member list</b>. A 1.7.10 client runs on Java 8, so in the
     * one environment this exists for, {@code System.out.} offered nothing, {@code System.} offered
     * nothing, and {@code Minecraft.getMinecraft().} offered exactly three rows: the methods of
     * {@code IPlayerUsage}, the only ones on that class whose signatures name no JDK type. Everything
     * else on Minecraft mentions a {@code String} or an {@code Object} somewhere and therefore could not
     * be typed. Object's own members were missing too, which is the tell — a receiver that fails to
     * resolve still reports eleven inherited members, and there were none, because {@code Object} was
     * unresolvable as well.</p>
     *
     * <p>It was invisible everywhere else by construction: the harness, the test JVMs and every modern
     * loader are on 9+, where the JRT covers it. Scripts still <em>ran</em>, because compilation resolves
     * through the live name environment rather than through this list — so the editor was blind while the
     * runtime was fine, which reads as a completion bug rather than a classpath one.</p>
     *
     * <p>Keyed on the jar EXISTING rather than on a version check: {@code rt.jar} is present exactly when
     * it is needed, so there is no version to get wrong and no branch to keep in step with a new release.
     * {@code java.home} may be a JRE or a JDK, hence both spellings.</p>
     */
    private static void addJavaClassLibrary(Set<String> into) {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) return;
        File base = new File(home);

        // A JDK's java.home holds the JRE beneath it; a JRE's does not.
        for (File lib : new File[]{new File(base, "lib"), new File(base, "jre" + File.separator + "lib")}) {
            File rt = new File(lib, "rt.jar");
            if (!rt.isFile()) continue;

            into.add(rt.getAbsolutePath());
            // The rest of the boot class path. rt.jar alone covers java.lang and java.util, which is what
            // the failure above was about, but a script reaching javax.crypto or java.nio.charset would
            // hit the same wall one type later -- and the whole point is that a missing platform type is
            // silent rather than reported.
            for (String beside : new String[]{"jce.jar", "jsse.jar", "charsets.jar", "resources.jar"}) {
                File jar = new File(lib, beside);
                if (jar.isFile()) into.add(jar.getAbsolutePath());
            }
            File extensions = new File(lib, "ext");
            File[] extensionJars = extensions.listFiles();
            if (extensionJars != null) {
                for (File jar : extensionJars) {
                    if (jar.isFile() && jar.getName().endsWith(".jar")) into.add(jar.getAbsolutePath());
                }
            }
            return;
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
