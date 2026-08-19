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
 * <h3>The places sources actually are</h3>
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
 *   <li><b>Any OTHER JDK on this machine</b> (M13 §25.5) — {@code JAVA_HOME}, the conventional install
 *       roots, the toolchain caches. A modded player launches on a jlink'd JRE that carries no
 *       {@code src.zip} while frequently having installed a full JDK, so this is the step that turns the
 *       row above from "absent in production" into "usually present".</li>
 *   <li><b>A fetched extract</b>, named through {@link JdkSourceExtract#SOURCES_PROPERTY} — and the same
 *       property is how somebody points at a {@code src.zip} of their own.</li>
 *   <li><b>A jar that ships its own</b> (M13 §25.4) — loose {@code .java} under
 *       {@code assets/<namespace>/sources/}, <b>discovered</b> by {@link BundledSources} rather than
 *       declared anywhere, and searched last so anything more specific wins. CrystalGUI and
 *       CrystalGraphics both do it, and so may any mod, with no registration and no entry here.</li>
 * </ul>
 */
public final class SourceArchives {

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
        List<Archive> archives = discover(classpath);
        announce(archives);
        return new SourceArchives(archives);
    }

    /**
     * One line saying what was found — because <b>attached and unattached look the same</b>.
     *
     * <p>The engine's own rule, applied here: a capability that can be silently skipped has to say it is
     * on. A quoted declaration and an assembled one are both plausible renderings of the same member, so
     * a {@code src.zip} that was looked for in the wrong place produces a popup nobody reports — it just
     * reads as the popup being a bit thin. There is nothing to search a log for either, because nothing
     * failed.</p>
     *
     * <p>Cheap enough to be unconditional: {@code over} is called once per (classpath, JDK archive) pair
     * and the result is cached for the session, so this is one line at the first hover and never again.
     * The <b>platform</b> archives are named individually because which one answered is the whole
     * question; the {@code -sources.jar}s are counted, because a modded classpath has hundreds and
     * listing them would bury the answer.</p>
     */
    private static void announce(List<Archive> archives) {
        int libraries = 0;
        int platforms = 0;
        StringBuilder bundled = new StringBuilder();
        String first = null;
        for (Archive archive : archives) {
            if (archive instanceof ResourceArchive) {
                // REAL BY CONSTRUCTION now that these are discovered: a prefix is only here because a
                // .java file was seen inside a jar under it. The earlier version set a flag from the
                // archive merely EXISTING, which it always did -- so it said "ours bundled" even on a
                // build that had dropped the packaging, a line lying about the thing it exists to reveal.
                if (bundled.length() > 0) bundled.append(", ");
                bundled.append(((ResourceArchive) archive).namespace());
            } else if (archive.platform()) {
                platforms++;
                // THE PATH, not toString(): "src.zip" is the answer to a different question -- which
                // JDK answered is the whole point, and every candidate is called the same thing.
                if (first == null) first = ((ZipArchive) archive).path();
            } else {
                libraries++;
            }
        }
        StringBuilder line = new StringBuilder("[crystalgui] source attachment: ");
        if (platforms == 0) {
            line.append("no JDK sources found");
        } else {
            // THE FIRST ONE AND A COUNT. A developer's machine turned out to hold TEN -- the toolchain
            // caches alone are half of that -- and listing them buries the one fact worth reading. The
            // first is the one asked first, and they are asked in order until one has the file.
            line.append("JDK ").append(first);
            if (platforms > 1) line.append(" (+").append(platforms - 1).append(" more)");
        }
        line.append(libraries == 0 ? ", no library sources" : ", " + libraries + " library source jars");
        line.append(bundled.length() == 0 ? ", NO bundled sources" : ", bundled: " + bundled);
        System.err.println(line);
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
                found = new Found(text, archive.platform());
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
        return discover(classpath, SourceArchives.class.getClassLoader());
    }

    /**
     * The same, with the loader the bundled producer reads from named — which a test supplies.
     *
     * <h3>The order is a precedence rule, and every step of it was a decision</h3>
     *
     * <ol>
     *   <li><b>The JDK</b>, because it answers the overwhelming majority of hovers and because a jar
     *       that happens to ship a {@code java/…} source tree must not shadow it.</li>
     *   <li><b>A real {@code -sources.jar} on disk</b>, which is the artifact somebody deliberately put
     *       beside the jar and is therefore the most specific answer available.</li>
     *   <li><b>Our own sources out of the jar</b> — last, so a working tree or a published
     *       {@code -sources.jar} for the same types wins in a dev workspace. It is the production
     *       fallback, and being last is what keeps it from shadowing something better.</li>
     * </ol>
     */
    static List<Archive> discover(List<String> classpath, ClassLoader bundled) {
        List<Archive> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (File candidate : jdkSources()) addArchive(candidate, true, seen, found);
        if (classpath != null) {
            for (String entry : classpath) {
                for (File candidate : sourcesBeside(entry)) addArchive(candidate, false, seen, found);
            }
        }
        if (bundled != null) {
            for (String prefix : BundledSources.prefixesIn(classpath, bundled)) {
                found.add(new ResourceArchive(bundled, prefix));
            }
        }
        return found;
    }


    private static void addArchive(File candidate, boolean platform, Set<String> seen,
                                   List<Archive> into) {
        if (candidate == null || !candidate.isFile()) return;
        if (!seen.add(candidate.getAbsolutePath())) return;
        into.add(new ZipArchive(candidate, platform));
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

        // 1. WHAT SOMEBODY CHOSE, or what M13 §25.5 fetched. Named through a system property because
        // that is the one channel the engine band and the host genuinely share -- a static field set on
        // the host is invisible to the band's own copy of this class. @see JdkSourceExtract
        String named = System.getProperty(JdkSourceExtract.SOURCES_PROPERTY);
        if (named != null && !named.trim().isEmpty()) candidates.add(new File(named.trim()));

        // 2. THE RUNNING JVM. Present on a JDK and absent on a JRE, which is the split that decides
        // whether any of the rest of this is needed.
        addJdkAt(System.getProperty("java.home"), candidates);

        // 3. ANY OTHER JDK ON THIS MACHINE, and this is the step that matters in production. A modded
        // player launches on a jlink'd JRE -- Mojang's launcher ships one, and it carries no src.zip --
        // while very often having installed a full JDK because a pack's own guide told them to. Reading
        // theirs costs nothing, needs no network, and raises no licence question at all, which makes it
        // strictly the first thing to try before fetching anything.
        addJdkAt(System.getenv("JAVA_HOME"), candidates);
        addJdkAt(System.getenv("JDK_HOME"), candidates);
        for (File root : INSTALL_ROOTS) {
            // GATED ON THE DIRECTORY EXISTING, so a Windows host does not stat /usr/lib/jvm and a Linux
            // one does not walk Program Files. This runs once per classpath, and the same lesson the
            // Gradle sibling search records applies: an ungated walk is waste on every host but one.
            File[] installed = root.isDirectory() ? root.listFiles() : null;
            if (installed == null) continue;
            for (File home : installed) {
                if (!home.isDirectory()) continue;
                addJdkAt(home.getPath(), candidates);
                // macOS buries the home one level further, and always at this exact path.
                addJdkAt(new File(home, "Contents/Home").getPath(), candidates);
            }
        }
        return candidates;
    }

    /**
     * The two places a JDK home keeps {@code src.zip}, added if the home is named at all.
     *
     * <p>On 9+ it is {@code $JAVA_HOME/lib/src.zip}. On 8 {@code java.home} points at the {@code jre}
     * subdirectory, so the file sits one level <em>up</em> — and a check that knows only the modern
     * layout finds nothing on exactly the host band 8 exists for.</p>
     */
    private static void addJdkAt(String home, List<File> into) {
        if (home == null || home.trim().isEmpty()) return;
        File javaHome = new File(home.trim());
        into.add(new File(javaHome, "lib/src.zip"));
        File parent = javaHome.getParentFile();
        if (parent != null) {
            into.add(new File(parent, "src.zip"));
            into.add(new File(parent, "lib/src.zip"));
        }
    }

    /** Where the conventional installers put a JDK, per platform. Missing ones cost one {@code isDirectory}. */
    private static final File[] INSTALL_ROOTS = installRoots();

    private static File[] installRoots() {
        List<File> roots = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && !programFiles.isEmpty()) {
            roots.add(new File(programFiles, "Java"));
            roots.add(new File(programFiles, "Eclipse Adoptium"));
            roots.add(new File(programFiles, "Microsoft"));
            roots.add(new File(programFiles, "Zulu"));
            roots.add(new File(programFiles, "Amazon Corretto"));
        }
        roots.add(new File("/usr/lib/jvm"));
        roots.add(new File("/Library/Java/JavaVirtualMachines"));
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            // Toolchain caches, which on a developer's machine hold more JDKs than the installers do.
            roots.add(new File(userHome, ".gradle/jdks"));
            roots.add(new File(userHome, ".sdkman/candidates/java"));
            roots.add(new File(userHome, "Library/Java/JavaVirtualMachines"));
        }
        return roots.toArray(new File[0]);
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
     * Somewhere source files can be read from by package path.
     *
     * <p>Two implementations and they are not the same shape, which is the point of the seam.
     * {@link ZipArchive} has to open a zip, walk every entry and build an index, and does it lazily
     * precisely because that is expensive. {@link ResourceArchive} needs none of it — the lookup either
     * answers or it does not, because the classloader indexed the jar's central directory when it opened
     * it. Writing the second as a mode of the first would have meant a class with an index it never
     * builds and a file it never has.</p>
     */
    interface Archive {

        /** The JDK's own {@code src.zip}, which is parsed differently. @see AttachedSources */
        boolean platform();

        /** The decoded text at {@code packagePath} ({@code java/util/List.java}), or null. */
        String read(String packagePath);
    }

    /**
     * Our own sources, shipped as loose entries in a jar and read through the loader that has it.
     *
     * <p>M13 §25.4. Everything this class would otherwise need — an index, a handle, a lazy pass, a
     * staleness question — is the classloader's already. What is left is one {@code getResourceAsStream}
     * and a decode.</p>
     *
     * <p><b>Never platform.</b> Ours are read at the band's ceiling like any other library, and must be:
     * they are written in the same Java the engine compiles scripts at, and the pre-module compliance the
     * JDK's sources need exists only to dodge a {@code java.util} package clash that nothing here has.</p>
     */
    static final class ResourceArchive implements Archive {

        private final ClassLoader loader;
        private final String prefix;

        ResourceArchive(ClassLoader loader, String prefix) {
            this.loader = loader;
            this.prefix = prefix;
        }

        @Override
        public boolean platform() {
            return false;
        }

        /** {@code assets/crystalgraphics/sources/} → {@code crystalgraphics}, for the diagnostic line. */
        String namespace() {
            return BundledSources.namespaceOf(prefix);
        }

        @Override
        public String read(String packagePath) {
            // A PACKAGE PATH BECOMES A RESOURCE PATH, so it may not climb out of the prefix. The name
            // comes from a binding rather than from user text today, and that is a fact about today.
            if (packagePath == null || packagePath.isEmpty() || packagePath.contains("..")) return null;
            try (InputStream stream = loader.getResourceAsStream(prefix + packagePath)) {
                if (stream == null) return null;
                return new String(readAll(stream), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        @Override
        public String toString() {
            return prefix + " (bundled)";
        }
    }

    /** Shared by both archive kinds, which is the only reason it is not a private method of one. */
    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

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
    static final class ZipArchive implements Archive {

        private final File file;
        /** The JDK's own {@code src.zip}, which is parsed differently. @see AttachedSources */
        private final boolean platform;
        /** Package path to the archive's own entry name; null until something asks. */
        private Map<String, String> entries;

        ZipArchive(File file, boolean platform) {
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

        @Override
        public boolean platform() {
            return platform;
        }

        /** Where this archive is — the only thing that distinguishes one {@code src.zip} from another. */
        String path() {
            return file.getAbsolutePath();
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

        @Override
        public String read(String packagePath) {
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

        @Override
        public String toString() {
            // NOT entries(), which would index the archive just because something logged it.
            return file.getName() + (entries == null ? " (not indexed)" : " (" + entries.size() + ")");
        }
    }
}
