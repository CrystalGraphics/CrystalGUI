package com.crystalgui.language.java.assist;

import com.crystalgui.core.async.Progress;
import com.crystalgui.language.cache.CacheFiles;
import com.crystalgui.language.cache.Download;
import com.crystalgui.language.cache.Downloads;
import com.crystalgui.language.cache.TarArchive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <b>The JDK's sources, fetched rather than shipped — and derived on the user's machine, never ours.</b>
 *
 * <p>M13 §25.5. Hovering {@code java.util.List.add} quotes the JDK authors' own declaration and their
 * javadoc instead of a form assembled from the binding. A player with a full JDK already has
 * {@code src.zip} and this is not needed; a player on a JRE — which is what Mojang's launcher and every
 * jlink'd runtime ship — has nothing, and that is most people.</p>
 *
 * <h3>The licence position, stated rather than assumed</h3>
 *
 * <p>OpenJDK source is GPLv2 with Classpath Exception. The exception covers <em>linking</em>, not
 * redistributing a modified extract, and there is no LICENSE file in this repository — so
 * "GPL-compatible" is not established and nothing derived from it may go in our jar. Two consequences,
 * and both are structural rather than a note somewhere:</p>
 *
 * <ul>
 *   <li><b>We host nothing.</b> The archive is fetched from whoever publishes the JDK, over HTTPS, by the
 *       user's own client. We are not in the distribution chain at all — the same position the MCP
 *       mapping data is in, and the reason that one is fetched too.</li>
 *   <li><b>The extract is made here, on this machine, for this machine.</b> Stripping bodies produces a
 *       derived work; producing one for your own use is not distributing it. Had we built the extract at
 *       our build time and shipped it, that same act would be redistribution of a modified GPL work.</li>
 * </ul>
 *
 * <p>It is therefore <b>never automatic</b>. A fetch of this size, from a third party, on the user's
 * connection, is a thing somebody asks for — which is also how IntelliJ does it, and what
 * {@code plan/lang-resolver.md} §24.1 already named as the popup's "Download documentation" entry.</p>
 *
 * <h3>Why the cached form is a plain zip of stripped files</h3>
 *
 * <p>The upstream archive is the whole OpenJDK tree, gzipped tar, laid out as
 * {@code src/java.base/share/classes/java/util/List.java}. What lands in the cache is
 * {@code java/util/List.java} in an ordinary zip — the exact shape {@code SourceArchives.ZipArchive}
 * already reads, and the exact shape a real {@code src.zip} has.</p>
 *
 * <p>That is deliberate and it is the risk control. Every developer with a JDK exercises the reader
 * daily; almost nobody exercises the producer. Keeping the producer's <em>output</em> indistinguishable
 * from what the common path already reads means a defect here cannot be a defect in how it is read —
 * which is the failure mode this project has been bitten by repeatedly, and the reason
 * {@link SourceHeaders} is tested directly rather than through the path that uses it.</p>
 */
public final class JdkSourceExtract {

    /**
     * Where the extract is, once there is one — read by {@link SourceArchives#jdkSources()}.
     *
     * <p><b>A system property because it is the one channel both sides of the engine bridge share.</b>
     * {@code SourceArchives} is reached from {@link AttachedSources}, which is child-side, so the band
     * loader defines its own copy of it — a static field set on the host would be invisible there, which
     * is exactly how {@code ScriptNameEnvironment} was silently inert for a release. {@code System} is
     * parent-first on every loader in this process, so a property set anywhere is read everywhere.</p>
     *
     * <p>It doubles as the manual override: point it at a {@code src.zip} and nothing needs fetching.</p>
     */
    public static final String SOURCES_PROPERTY = "crystalgui.jdk.sources";

    /** Where to fetch from, when the shipped default is not what a deployment wants. */
    public static final String URL_PROPERTY = "crystalgui.jdk.sources.url";

    /**
     * Adoptium's published <b>sources</b> artifact for a feature version.
     *
     * <p>Eclipse Adoptium is where most modded players' runtimes come from, it publishes over HTTPS, and
     * its {@code sources} image is the unmodified OpenJDK tree. The {@code linux/x64} segments are
     * required by the API's path shape and are irrelevant to the content, which is platform-independent
     * text.</p>
     *
     * <p><b>Unverified from this repository.</b> Nothing in the build reaches the network, so this URL's
     * shape is taken from Adoptium's published API and not from a request anybody here has made. If it
     * has moved, {@link #URL_PROPERTY} is the answer and the failure is one reported line rather than
     * anything broken — which is why the fetch is a command somebody runs rather than a startup step.</p>
     */
    private static final String DEFAULT_URL =
            "https://api.adoptium.net/v3/binary/latest/%d/ga/linux/x64/sources/hotspot/normal/eclipse";

    /**
     * <b>The public API of {@code java.base}</b> — which is what this list adds up to, measured rather
     * than chosen.
     *
     * <p>It began as eight packages somebody picked as "what a script author touches", which is a
     * judgement nobody can check. Measured against a real {@code src.zip} it turned out to cover 41% of
     * {@code java.base}'s files, and <b>the only public packages it missed were {@code java.security} and
     * {@code javax.security}</b> — 224 files and 1.72 MB, now included. Everything else left out of the
     * module is {@code jdk.internal.*}, {@code sun.*} and {@code com.sun.*}: implementation internals a
     * script cannot usefully call, that {@code ScriptPolicy} refuses anyway, and that no completion list
     * offers.</p>
     *
     * <p>So the rule is now sayable in one line instead of being a list to argue with, and the ten entries
     * are its spelling. <b>Only {@code java.base}</b>: {@code java.desktop} is Swing and AWT, {@code
     * java.xml} is a parser stack, and neither is something a Minecraft script reaches for — together they
     * are most of {@code src.zip}'s 185 MB and would multiply the cache for nothing.</p>
     *
     * <p>Spelled as path prefixes rather than module names because a Java 8 {@code src.zip} has no
     * modules in its layout at all, and the same list has to work against all three shapes
     * {@link #relativePathOf} normalises.</p>
     */
    static final String[] PACKAGES = {
            "java/lang/", "java/util/", "java/io/", "java/nio/",
            "java/time/", "java/text/", "java/math/", "java/net/",
            "java/security/", "javax/security/",
    };

    /** Where a JDK repository keeps the sources that become {@code src.zip}. */
    private static final String CLASSES = "/share/classes/";

    private JdkSourceExtract() {
    }

    /** What acquiring did, so a caller can say it once and mean it. */
    public enum State {
        /** Already on disk from a previous run; nothing was fetched. */
        CACHED,
        /** Fetched, stripped and installed. */
        INSTALLED,
        /** No URL to fetch from — the default was cleared and nothing replaced it. */
        NOT_CONFIGURED,
        /** Reached for and did not arrive: offline, moved, refused, or nothing usable inside. */
        UNAVAILABLE,
    }

    /** The outcome and one line about it. Both are said aloud, because the two look identical on screen. */
    public static final class Result {
        private final State state;
        private final String detail;
        private final int files;
        private final long bytes;

        Result(State state, String detail) {
            this(state, detail, 0, 0L);
        }

        /**
         * With the numbers a report wants, which {@link #detail} is the wrong place for.
         *
         * <p>{@code detail} carries the URL because the one stderr line is for whoever is diagnosing a
         * failure. A notification wants what arrived — and building that by parsing the detail string
         * would be inventing a format to read it back out of.</p>
         */
        Result(State state, String detail, int files, long bytes) {
            this.state = state;
            this.detail = detail;
            this.files = files;
            this.bytes = bytes;
        }

        /** How many source files were kept. */
        public int files() {
            return files;
        }

        /** How big the installed extract is. */
        public long bytes() {
            return bytes;
        }

        public State state() {
            return state;
        }

        public String detail() {
            return detail;
        }

        @Override
        public String toString() {
            return state + " — " + detail;
        }
    }

    /** The feature version of the running JVM: {@code 1.8} is 8, {@code 17} is 17. */
    public static int runningFeatureVersion() {
        String spec = System.getProperty("java.specification.version", "8");
        int dot = spec.indexOf('.');
        // "1.8" -> 8 and "17" -> 17. The 1.x spelling stopped at 8, so the tail is the answer for it.
        String number = dot < 0 ? spec : spec.substring(dot + 1);
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException unreadable) {
            return 8;
        }
    }

    /**
     * Where this JVM's extract lives under a cache root.
     *
     * <h3>The name carries the FORM, and it has to</h3>
     *
     * <p>An extract fetched before {@link #build} stopped stripping is a valid zip of valid Java that
     * every reader accepts — and every method in it is empty. Nothing in its bytes distinguishes it
     * from a full one, so a session finding the old file would adopt it, serve headers to a viewer, and
     * present as a decompiler producing empty bodies with no way to tell it to try again.</p>
     *
     * <p>So the form is in the file name. An old {@code jdk-N-sources.zip} is simply never looked for
     * again: it is stale cache, it costs disk until somebody clears it, and it can no longer be mistaken
     * for the thing it is not.</p>
     */
    public static Path extractFile(Path cacheRoot, int feature) {
        return cacheRoot.resolve("jdk-sources").resolve("jdk-" + feature + "-sources-full.zip");
    }

    /**
     * Points {@link #SOURCES_PROPERTY} at an extract already on disk, if there is one.
     *
     * <p>Called at registration so a second launch is as good as the first. It never fetches and never
     * overwrites a property somebody set deliberately — an explicit {@code src.zip} outranks our cache,
     * because somebody choosing one has a reason.</p>
     */
    public static boolean useCachedIfPresent(Path cacheRoot) {
        if (System.getProperty(SOURCES_PROPERTY) != null) return true;
        Path file = extractFile(cacheRoot, runningFeatureVersion());
        if (!CacheFiles.isValid(file, null)) return false;
        System.setProperty(SOURCES_PROPERTY, file.toAbsolutePath().toString());
        return true;
    }

    /** Whether a fetch would do anything — what the command's {@code enabledWhen} asks. */
    public static boolean isCached(Path cacheRoot) {
        return CacheFiles.isValid(extractFile(cacheRoot, runningFeatureVersion()), null);
    }

    /**
     * Fetches, strips and installs the extract for the running JVM, reporting into {@code progress}.
     *
     * <p>Blocking, and on whatever thread the caller chose — the same contract {@code PlatformMappings}
     * has, and for the same reason: this module must not reach for a scheduler, because a dedicated
     * server has no frame to drain one on.</p>
     */
    public static Result acquire(Path cacheRoot, Progress progress,
                                 java.util.function.BooleanSupplier cancelled) {
        int feature = runningFeatureVersion();
        Path target = extractFile(cacheRoot, feature);
        if (CacheFiles.isValid(target, null)) {
            return new Result(State.CACHED, target.toString());
        }
        String url = urlFor(feature);
        if (url == null || url.isEmpty()) {
            return new Result(State.NOT_CONFIGURED, "no " + URL_PROPERTY + " and no default");
        }

        Path scratch = target.resolveSibling(target.getFileName() + ".building");
        try {
            // THE DESCRIBED FORM. Announcing before the connect, retargeting once the response says how
            // big it is, counting the bytes past and rate-limiting the reports are all Downloads' now --
            // this class had its own Counting stream and its own begin/retarget dance, and the next
            // consumer would have had a second copy of both.
            Files.createDirectories(target.getParent());

            int written;
            try (Download download = Downloads.from(url)
                         .named("Downloading JDK sources").reporting(progress)
                         .cancelledWhen(cancelled).open();
                 TarArchive archive = TarArchive.gzip(download.stream());
                 OutputStream file = Files.newOutputStream(scratch);
                 ZipOutputStream out = new ZipOutputStream(file)) {
                progress.detail("Java " + feature);
                written = build(archive, out, progress);
            }
            if (written == 0) {
                Files.deleteIfExists(scratch);
                return new Result(State.UNAVAILABLE, "nothing usable in the archive at " + url);
            }
            // THROUGH CacheFiles so the atomic-install rule has one implementation. The extra copy of a
            // few megabytes is the price of not writing the .part-then-move dance a second time.
            try (InputStream built = Files.newInputStream(scratch)) {
                if (!CacheFiles.install(target, built, null)) {
                    return new Result(State.UNAVAILABLE, "the extract would not install at " + target);
                }
            }
            System.setProperty(SOURCES_PROPERTY, target.toAbsolutePath().toString());
            return new Result(State.INSTALLED, written + " files from " + url,
                    written, Files.size(target));
        } catch (IOException | RuntimeException unavailable) {
            // THE CLASS NAME FOR THE LOG, THE SENTENCE FOR THE PERSON. `toString` is what somebody
            // debugging wants and it is not what a balloon should say -- offline, this read
            // "java.net.UnknownHostException: api.adoptium.net" to a user whose wifi was simply off.
            System.err.println("[crystalgui] jdk sources failed: " + unavailable);
            return new Result(State.UNAVAILABLE, Downloads.describe(unavailable));
        } finally {
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException leftBehind) {
                // A scratch file we could not remove is litter, not a failure of the fetch.
            }
        }
    }

    private static String urlFor(int feature) {
        String override = System.getProperty(URL_PROPERTY);
        if (override != null) return override.trim();
        return String.format(DEFAULT_URL, feature);
    }

    // ── The transform half, which is what the tests drive ───────────────────────────────────────

    /**
     * Copies every wanted source out of {@code archive} into {@code out}, <b>whole</b>.
     *
     * <h3>It used to strip method bodies, and the viewer is why it stopped</h3>
     *
     * <p>{@link SourceHeaders} cut every body to {@code {}} so the cache was single-digit megabytes
     * rather than about forty-three, which was exactly right while the only reader was a documentation
     * popup: a popup quotes a declaration and its comment, and a body it never shows is bytes on disk
     * for nothing.</p>
     *
     * <p>The library viewer reads the same archive and shows the file, so a stripped extract renders
     * {@code ArrayList} as real signatures and real javadoc over <b>empty methods</b> — which does not
     * read as a cache optimisation, it reads as a decompiler that failed. Full text is a superset of the
     * headers, so the popup is unaffected and both readers are served by one copy.</p>
     *
     * <p><b>The package filter stays.</b> The size accepted here is for bodies of {@code java.base}'s
     * public API, not for pulling in Swing and the XML stack — those are most of {@code src.zip}'s
     * 185 MB and a script author reaches for neither. {@link SourceHeaders} stays in the tree too: it is
     * tested directly, and a memory-constrained host may want it back.</p>
     *
     * <p>Package-private and taking the two streams so a test can drive it over an archive it built
     * itself — the network is the one part of this that cannot be exercised offline, and it is also the
     * part with no logic in it.</p>
     *
     * @return how many files were written
     */
    static int build(TarArchive archive, ZipOutputStream out, Progress progress) throws IOException {
        Set<String> written = new LinkedHashSet<>();
        int count = 0;
        for (TarArchive.Entry entry = archive.next(); entry != null; entry = archive.next()) {
            if (!entry.isFile() || !entry.name().endsWith(".java")) continue;
            String path = relativePathOf(entry.name());
            // A DUPLICATE ENTRY MAKES ZipOutputStream THROW, and the same package path really does appear
            // twice in a JDK tree -- once under `share` and again under an OS-specific directory.
            if (path == null || !written.add(path)) continue;

            out.putNextEntry(new ZipEntry(path));
            out.write(archive.read());
            out.closeEntry();
            count++;
            if ((count & 0x7F) == 0) progress.detail(path);
        }
        return count;
    }

    /**
     * {@code src/java.base/share/classes/java/util/List.java} → {@code java/util/List.java}, or null.
     *
     * <p>Three layouts answer to one rule. A JDK <b>repository</b> keys by module and build directory and
     * is cut at {@code /share/classes/}; a modular {@code src.zip} keys by module alone; a Java 8
     * {@code src.zip} keys by package. Null for everything outside {@link #PACKAGES}, which is most of
     * the archive — the test tree, the build tooling, and every module a script will never name.</p>
     */
    static String relativePathOf(String entryName) {
        String name = entryName.startsWith("/") ? entryName.substring(1) : entryName;
        int classes = name.indexOf(CLASSES);
        if (classes >= 0) {
            name = name.substring(classes + CLASSES.length());
        } else {
            int slash = name.indexOf('/');
            // A leading segment with a `.` in it is a module name -- a package segment is a Java
            // identifier and cannot contain one, so the single test covers both src.zip layouts.
            if (slash > 0 && name.lastIndexOf('.', slash) >= 0) name = name.substring(slash + 1);
        }
        for (String wanted : PACKAGES) {
            if (name.startsWith(wanted)) return name;
        }
        return null;
    }

    /** Visible for testing — the bytes of a zip built from one archive, without touching disk. */
    static byte[] buildToBytes(TarArchive archive, Progress progress) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            build(archive, out, progress);
        }
        return bytes.toByteArray();
    }
}
