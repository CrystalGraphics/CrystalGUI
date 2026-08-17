package com.crystalgui.language.java.assist;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where a classpath symbol's <b>source</b> is on disk — {@code -sources.jar} beside a jar, {@code
 * src.zip} for the JDK — found, indexed and decoded.
 *
 * <h3>Why this is separate from {@link AttachedSources}</h3>
 *
 * <p>Nothing here names a JDT type, and that is the whole point: the engine jars are supplied to the
 * adapter at runtime and are deliberately <em>not</em> on the test compile classpath, so a class that
 * imports {@code ASTParser} cannot be reached by a unit test at all. Everything in this file is a rule
 * that can be got quietly wrong — which directory Gradle keeps sources in, how a modular {@code src.zip}
 * keys its entries — and every one of those failures is silent, because the fallback is simply the older
 * and poorer rendering. So the half that can be tested directly is the half that is.</p>
 *
 * <h3>The three places sources actually are</h3>
 *
 * <ul>
 *   <li><b>Beside the jar</b> — {@code foo.jar} → {@code foo-sources.jar}. A hand-built classpath, a
 *       Maven local repository, most mod distributions.</li>
 *   <li><b>One directory over</b> — Gradle's module cache gives every artifact its own SHA-1 directory,
 *       so {@code …/1.0/<hash>/foo-1.0.jar} keeps its sources at
 *       {@code …/1.0/<otherhash>/foo-1.0-sources.jar}: a sibling of the <em>parent</em>, never of the
 *       jar. A "look next to it" rule finds nothing in the one layout every Gradle build produces.</li>
 *   <li><b>The JDK</b> — {@code src.zip}, at {@code lib/src.zip} on 9+ and one level up from the
 *       {@code jre} home on 8. The one that matters most, since most hovers are {@code java.*}.</li>
 * </ul>
 */
final class SourceArchives {

    private final List<Archive> archives;
    /** Decoded source by top-level type name; a null value means "looked, and there is none". */
    private final Map<String, Found> texts = new LinkedHashMap<>();

    /** Enough decoded files to cover a reading session, few enough never to be a leak. */
    private static final int MAX_TEXTS = 48;

    private SourceArchives(List<Archive> archives) {
        this.archives = archives;
    }

    /** The archives a classpath resolves to, in the order they will be searched. */
    static SourceArchives over(List<String> classpath) {
        return new SourceArchives(discover(classpath));
    }

    /** A source file and <b>which kind of archive it came out of</b>, which decides how it is parsed. */
    static final class Found {
        final String text;
        /** True for the JDK's own {@code src.zip}. @see AttachedSources */
        final boolean platform;

        Found(String text, boolean platform) {
            this.text = text;
            this.platform = platform;
        }
    }

    /** The source declaring {@code topLevelName}, or null when no archive has it. */
    synchronized Found find(String topLevelName) {
        if (topLevelName == null || topLevelName.isEmpty()) return null;
        if (texts.containsKey(topLevelName)) return texts.get(topLevelName);
        String path = topLevelName.replace('.', '/') + ".java";
        Found found = null;
        for (Archive archive : archives) {
            String text = archive.read(path);
            if (text != null) {
                found = new Found(text, archive.platform);
                break;
            }
        }
        if (texts.size() >= MAX_TEXTS) texts.clear();
        texts.put(topLevelName, found);
        return found;
    }

    // ── Finding the archives ────────────────────────────────────────────────────────────────────

    /** Visible for testing — which archives a classpath resolves to, in search order. */
    static List<Archive> discover(List<String> classpath) {
        List<Archive> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // THE JDK FIRST, because it answers the overwhelming majority of hovers and because a jar that
        // happens to ship a `java/…` source tree must not shadow it.
        for (File candidate : jdkSources()) addArchive(candidate, true, seen, found);
        if (classpath != null) {
            for (String entry : classpath) {
                for (File candidate : sourcesBeside(entry)) addArchive(candidate, false, seen, found);
            }
        }
        return found;
    }

    private static void addArchive(File candidate, boolean platform, Set<String> seen,
                                   List<Archive> into) {
        if (candidate == null || !candidate.isFile()) return;
        if (!seen.add(candidate.getAbsolutePath())) return;
        into.add(new Archive(candidate, platform));
    }

    /**
     * {@code src.zip}, in both places a JDK puts it.
     *
     * <p>On 9+ it is {@code $JAVA_HOME/lib/src.zip}. On 8 {@code java.home} points at the {@code jre}
     * subdirectory, so the file sits one level <em>up</em> — and a check that knows only the modern
     * layout finds nothing on exactly the host band 8 exists for.</p>
     */
    static List<File> jdkSources() {
        List<File> candidates = new ArrayList<>();
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) return candidates;
        File javaHome = new File(home);
        candidates.add(new File(javaHome, "lib/src.zip"));
        File parent = javaHome.getParentFile();
        if (parent != null) {
            candidates.add(new File(parent, "src.zip"));
            candidates.add(new File(parent, "lib/src.zip"));
        }
        return candidates;
    }

    /**
     * Where a jar's own sources would be — beside it, and (in a Gradle cache only) one directory over.
     *
     * <p>Named candidates only, never a recursive walk: this runs once per classpath entry, and a
     * production Minecraft classpath is hundreds of them.</p>
     *
     * <h3>The sibling search is gated on the layout it was written for</h3>
     *
     * <p>It exists for one thing: Gradle's module cache, which files every artifact under its own SHA-1
     * directory, so {@code …/1.0/<hash>/foo-1.0.jar} keeps its sources at
     * {@code …/1.0/<otherhash>/foo-1.0-sources.jar}. Applied unconditionally it is both wasteful and
     * nonsense everywhere else — a {@code mods/} folder is the case that matters, where three hundred
     * jars share one grandparent, so the directory listing runs three hundred identical times and then
     * looks for {@code foo-sources.jar} inside {@code config/} and {@code saves/}.</p>
     *
     * <p>So the jar's own directory has to <em>look like</em> a cache entry first. That is one string
     * test, it happens before any filesystem call, and it is exact: nothing but Gradle names a directory
     * with forty hex characters.</p>
     */
    static List<File> sourcesBeside(String entry) {
        List<File> candidates = new ArrayList<>();
        if (entry == null || !entry.toLowerCase(Locale.ROOT).endsWith(".jar")) return candidates;
        File jar = new File(entry);
        String name = jar.getName();
        String stem = name.substring(0, name.length() - ".jar".length());
        // A SOURCES JAR IS NOT ITSELF A CLASSPATH ENTRY to find sources for, and left unguarded it
        // would look for `foo-sources-sources.jar`.
        if (stem.endsWith("-sources") || stem.endsWith("-src")) return candidates;

        File directory = jar.getParentFile();
        if (directory == null) return candidates;
        for (String suffix : SOURCE_SUFFIXES) candidates.add(new File(directory, stem + suffix));

        if (!looksLikeCacheEntry(directory)) return candidates;
        File grandparent = directory.getParentFile();
        File[] siblings = grandparent == null ? null : grandparent.listFiles();
        if (siblings == null) return candidates;
        for (File sibling : siblings) {
            if (!sibling.isDirectory() || sibling.equals(directory)) continue;
            for (String suffix : SOURCE_SUFFIXES) candidates.add(new File(sibling, stem + suffix));
        }
        return candidates;
    }

    /** A Gradle module-cache directory: the artifact's SHA-1, and nothing else is named that way. */
    private static boolean looksLikeCacheEntry(File directory) {
        String name = directory.getName();
        if (name.length() != 40) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** Maven's convention first, then the one some older publications use. */
    private static final String[] SOURCE_SUFFIXES = {"-sources.jar", "-src.jar"};

    // ── One archive ─────────────────────────────────────────────────────────────────────────────

    /**
     * A source archive, indexed by package path <b>on first read</b> and reopened per read after that.
     *
     * <h3>Two decisions, both about not paying for what nobody asked for</h3>
     *
     * <p><b>The index is lazy.</b> Discovery finds candidates with an {@code isFile()} check, which is
     * free; building the index is a full pass over every zip entry, which is not — {@code src.zip} alone
     * holds about eighteen thousand. Indexing everything when the classpath is first seen would put that
     * on the first keystroke, and on a modded classpath with dozens of {@code -sources.jar}s it would be
     * seconds of it. Deferred, a session that never hovers pays nothing and one that hovers only
     * {@code java.*} indexes only {@code src.zip}.</p>
     *
     * <p><b>The {@code ZipFile} is not held open.</b> A handful of archives open for the life of the
     * editor is a handful of file handles, and on Windows an open handle is also a lock on a file the
     * user may want to replace. Reopening costs about a millisecond and happens once per type, because
     * the decoded text is cached above.</p>
     */
    static final class Archive {

        private final File file;
        /** The JDK's own {@code src.zip}, which is parsed differently. @see AttachedSources */
        final boolean platform;
        /** Package path to the archive's own entry name; null until something asks. */
        private Map<String, String> entries;

        Archive(File file, boolean platform) {
            this.file = file;
            this.platform = platform;
        }

        /** Built once — and an unreadable archive indexes to empty rather than being retried per hover. */
        private Map<String, String> entries() {
            if (entries != null) return entries;
            Map<String, String> index = new HashMap<>();
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (!name.endsWith(".java")) continue;
                    index.putIfAbsent(packagePathOf(name), name);
                }
            } catch (IOException | RuntimeException unreadable) {
                // Not an archive, or not readable. A classpath names things that are not there, and a
                // `-sources.jar` half-written by a download is a real state to be in.
                index.clear();
            }
            entries = index;
            return entries;
        }

        /** Visible for testing — how many source files this archive offers. */
        int size() {
            return entries().size();
        }

        /**
         * {@code java.base/java/util/List.java} → {@code java/util/List.java}.
         *
         * <p>A modular {@code src.zip} keys everything by module first; an ordinary {@code -sources.jar}
         * keys by package alone. A leading segment containing a {@code .} is a module name, because a
         * package segment is a Java identifier and cannot contain one — so that single test covers both
         * layouts with no need to know which is in hand.</p>
         */
        static String packagePathOf(String entryName) {
            String name = entryName.startsWith("/") ? entryName.substring(1) : entryName;
            int slash = name.indexOf('/');
            if (slash > 0 && name.lastIndexOf('.', slash) >= 0) return name.substring(slash + 1);
            return name;
        }

        String read(String packagePath) {
            String entryName = entries().get(packagePath);
            if (entryName == null) return null;
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) return null;
                try (InputStream stream = zip.getInputStream(entry)) {
                    return new String(readAll(stream), StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        private static byte[] readAll(InputStream stream) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }

        @Override
        public String toString() {
            // NOT entries(), which would index the archive just because something logged it.
            return file.getName() + (entries == null ? " (not indexed)" : " (" + entries.size() + ")");
        }
    }
}
