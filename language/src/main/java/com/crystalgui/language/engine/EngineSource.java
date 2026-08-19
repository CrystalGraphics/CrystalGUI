package com.crystalgui.language.engine;

import com.crystalgui.language.cache.Downloads;
import com.crystalgui.core.async.Progress;
import com.crystalgui.language.cache.CacheFiles;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where a band's jars come from.
 *
 * <h3>Why this is a seam and not a constant</h3>
 *
 * <p>{@link EngineBand} says which versions are correct; that answer is the same everywhere. <em>Where
 * the files are</em> is not, and the candidates are genuinely different in kind: bundled inside a mod
 * jar and extracted on first use, sitting in a loader's {@code libraries/} directory, handed over by a
 * Gradle configuration in a dev run, or absent entirely on a build that ships no engines. Baking any one
 * of those in would make the other three a special case of it.</p>
 *
 * <p>The distribution question is genuinely open — the three bands together are ~44MB of jars, and
 * whether a mod jar carries all of them, one of them, or none is a packaging decision that belongs with
 * the loader modules, none of which is in this build. What is <em>not</em> open is that the engine has
 * to be found through one interface, so that when the decision is made it is made in one place.</p>
 *
 * <h3>Nothing here is required</h3>
 *
 * <p>{@link #NONE} is a real deployment, not a failure: a dedicated server that never compiles, a build
 * that ships the editor without the engines, a platform whose band has no jars. It answers with an empty
 * list, {@code EngineClassLoader.over} refuses loudly, and the application falls back to grammar-only
 * colouring — the same three-tier degradation {@code LanguageServices} already describes.</p>
 */
public interface EngineSource {

    /** No engines anywhere. Not an error — see the class note. */
    EngineSource NONE = new EngineSource() {
        @Override
        public List<URL> jarsFor(EngineBand band) {
            return Collections.emptyList();
        }

        @Override
        public String toString() {
            return "EngineSource.NONE";
        }
    };

    /**
     * Every jar this band needs, in classpath order.
     *
     * <p>Empty means "not available here", which the caller is expected to handle. It does not mean the
     * band is wrong.</p>
     */
    List<URL> jarsFor(EngineBand band) throws IOException;

    /**
     * Jars laid out one directory per band — {@code <root>/8/}, {@code <root>/11/}, {@code <root>/17/}.
     *
     * <p>Named by the band's minimum feature version rather than by the enum constant, because the
     * directory is a deployment artifact somebody may lay out by hand and {@code 17} reads as a Java
     * version to everybody. A missing directory yields an empty list rather than throwing: a build that
     * ships only the band it targets is a legitimate build, and it must not fail on the two it omitted.</p>
     */
    static EngineSource directory(Path root) {
        return new EngineSource() {
            @Override
            public List<URL> jarsFor(EngineBand band) throws IOException {
                Path bandDirectory = root.resolve(Integer.toString(band.minimumFeatureVersion()));
                if (!Files.isDirectory(bandDirectory)) return Collections.emptyList();
                List<URL> urls = new ArrayList<>();
                try (Stream<Path> entries = Files.list(bandDirectory)) {
                    // SORTED, so the classpath order is the same on every machine. Files.list gives
                    // filesystem order, which differs between ext4 and NTFS -- and a duplicate class
                    // across two jars then resolves differently per platform, which is the kind of bug
                    // that reproduces for one person and nobody else.
                    for (Path jar : entries.sorted().toArray(Path[]::new)) {
                        if (jar.getFileName().toString().endsWith(".jar")) {
                            urls.add(jar.toUri().toURL());
                        }
                    }
                }
                return urls;
            }

            @Override
            public String toString() {
                return "EngineSource.directory(" + root + ")";
            }
        };
    }

    /**
     * An explicit list, the same for every band.
     *
     * <p>For a dev run and for tests, where a Gradle configuration has already resolved exactly the
     * right jars and asking a directory layout to rediscover them would be re-deriving a known answer.
     * Deliberately band-blind: the caller has already chosen, and pretending otherwise would let a test
     * pass while asking for the wrong band.</p>
     */
    static EngineSource of(Collection<Path> jars) {
        List<Path> copy = new ArrayList<>(jars);
        return new EngineSource() {
            @Override
            public List<URL> jarsFor(EngineBand band) throws IOException {
                List<URL> urls = new ArrayList<>(copy.size());
                for (Path jar : copy) urls.add(jar.toUri().toURL());
                return urls;
            }

            @Override
            public String toString() {
                return "EngineSource.of(" + copy.size() + " jars)";
            }
        };
    }

    /**
     * The jars named by a path-separated string — what a Gradle configuration hands over verbatim.
     *
     * <p>Entries that do not exist are dropped rather than refused. A path list assembled by a build is
     * occasionally stale, and one missing jar should surface as the engine failing to find a class it
     * needs, naming that class, rather than as a startup failure naming a file nobody was looking at.</p>
     */
    static EngineSource ofPathList(String pathList) {
        if (pathList == null || pathList.trim().isEmpty()) return NONE;
        List<Path> jars = new ArrayList<>();
        for (String entry : pathList.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            Path jar = new File(trimmed).toPath();
            if (Files.isRegularFile(jar)) jars.add(jar);
        }
        return jars.isEmpty() ? NONE : of(jars);
    }

    /**
     * The band's jars carried <b>inside</b> a jar, extracted to a cache directory on first use.
     *
     * <h3>Why extraction and not reading in place</h3>
     *
     * <p>A {@code URLClassLoader} cannot open a jar nested inside another one — there is no URL for it
     * — and {@code EngineClassLoader} is one. Reading the entries and defining classes by hand would
     * mean reimplementing jar loading, sealing and signing for the sake of avoiding a one-off copy of
     * 16 MB. So they are extracted once and the ordinary {@link #directory} source takes over.</p>
     *
     * <h3>What "on first use" costs, and what it does not</h3>
     *
     * <p>Second and later launches copy nothing: {@link CacheFiles#isValid} finds each jar present and
     * non-empty and the extraction is skipped entirely. The first launch pays one sequential copy, off
     * the client thread by virtue of when engines are opened.</p>
     *
     * <p>No digest is pinned, and that is a considered absence rather than an omission. These bytes come
     * out of our own jar, whose integrity is already the JVM's problem — there is no upstream to
     * disagree with and nothing to compare against that would not simply be a hash of ourselves. What
     * the check still catches is the case that actually happens: a copy interrupted half way.</p>
     *
     * @param loader       what holds the resources — the mod's own loader
     * @param resourceRoot where the bands live inside it, one directory per band, no trailing slash
     * @param into         the cache directory, normally {@code <cacheRoot>/engines}
     */
    static EngineSource extractedFrom(ClassLoader loader, String resourceRoot, Path into) {
        return new EngineSource() {
            @Override
            public List<URL> jarsFor(EngineBand band) throws IOException {
                String prefix = resourceRoot + "/" + band.minimumFeatureVersion() + "/";
                List<String> names = EngineBundle.listing(loader, prefix);
                if (names.isEmpty()) return Collections.emptyList();

                Path directory = into.resolve(String.valueOf(band.minimumFeatureVersion()));
                List<Path> extracted = new ArrayList<>(names.size());
                for (String name : names) {
                    Path target = directory.resolve(name);
                    if (!CacheFiles.isValid(target, null)) {
                        InputStream bytes = loader.getResourceAsStream(prefix + name);
                        // A name that came out of the listing and then cannot be opened is a broken jar,
                        // not a missing band -- skipped so the rest still extract, and it surfaces later
                        // as the engine failing to find a class rather than as an unexplained absence.
                        if (bytes == null) continue;
                        if (!CacheFiles.install(target, bytes, null)) continue;
                    }
                    extracted.add(target);
                }
                List<URL> urls = new ArrayList<>(extracted.size());
                for (Path jar : extracted) urls.add(jar.toUri().toURL());
                return urls;
            }

            @Override
            public String toString() {
                return "EngineSource.extractedFrom(" + resourceRoot + " -> " + into + ")";
            }
        };
    }

    /**
     * Fetches a band from the URLs in its shipped manifest, into {@code into}.
     *
     * <h3>The fallback, not the mechanism</h3>
     *
     * <p>Bundled first, this second. A jar carries the band its own platform runs — band 8 for 1.7.10 — so
     * the ordinary launch needs no network at all; this exists for the host whose band is not that one,
     * which on 1.7.10 means lwjgl3ify and GTNH. Offline, it answers empty and the editor colours without
     * analysing, exactly as it does when nothing is bundled.</p>
     *
     * <h3>Verified against the manifest's digest, and that is worth more than it looks</h3>
     *
     * <p>Unlike the mapping data — whose upstream publishes no checksums, so its digests are still an open
     * item — these are hashed at build time from the artifacts Gradle resolved. So a fetched jar is
     * checked against the exact bytes the build was tested with, and {@code CacheFiles.install} writes
     * through a {@code .part} and deletes on mismatch, so a bad transfer leaves nothing behind to be
     * mistaken for a good one next launch.</p>
     *
     * <p><b>All or nothing.</b> A band is a classpath: fifteen jars that resolve each other. A partial set
     * is worse than none, because the engine opens and then fails on whichever class was in the jar that
     * did not arrive — so one failure abandons the whole band and says so.</p>
     */
    static EngineSource downloadedFrom(ClassLoader loader, String resourceRoot, Path into,
                                       Progress progress) {
        return new EngineSource() {
            @Override
            public List<URL> jarsFor(EngineBand band) throws IOException {
                String prefix = resourceRoot + "/" + band.minimumFeatureVersion() + "/";
                List<EngineManifest> rows = EngineManifest.listing(loader, prefix);
                if (rows.isEmpty()) return Collections.emptyList();

                Path directory = into.resolve(String.valueOf(band.minimumFeatureVersion()));
                List<Path> present = new ArrayList<>(rows.size());
                List<EngineManifest> missing = new ArrayList<>();
                for (EngineManifest row : rows) {
                    Path target = directory.resolve(row.fileName());
                    if (CacheFiles.isValid(target, row.md5())) {
                        present.add(target);
                    } else {
                        missing.add(row);
                    }
                }
                if (missing.isEmpty()) return urlsOf(present);

                // TOTALLED FIRST, so the bar is determinate from its first frame rather than switching
                // from a sweep once the first HEAD comes back. A length nobody will give us is -1, and
                // -1 is exactly what begin() takes to mean indeterminate -- so it passes straight through.
                long total = 0;
                for (EngineManifest row : missing) {
                    long length = Downloads.lengthOf(row.url());
                    if (length < 0) {
                        total = -1;
                        break;
                    }
                    total += length;
                }
                progress.begin("Downloading Java engine (band "
                        + band.minimumFeatureVersion() + ")", total);

                long done = 0;
                for (EngineManifest row : missing) {
                    // PER FILE, not per chunk. Each report allocates a state so a reader sees a consistent
                    // one, and a report per 8 KB block is thousands of allocations feeding a bar that
                    // redraws sixty times a second.
                    progress.detail(row.fileName());
                    Path target = directory.resolve(row.fileName());
                    try (InputStream bytes = Downloads.open(row.url())) {
                        if (!CacheFiles.install(target, bytes, row.md5())) {
                            System.err.println("[crystalgui] " + row.fileName()
                                    + " did not match its expected digest; band "
                                    + band.minimumFeatureVersion() + " was not acquired");
                            return Collections.emptyList();
                        }
                    } catch (IOException unreachable) {
                        System.err.println("[crystalgui] could not fetch " + row.fileName() + " ("
                                + unreachable + "); band " + band.minimumFeatureVersion()
                                + " was not acquired and the editor will colour but not analyse");
                        return Collections.emptyList();
                    }
                    present.add(target);
                    if (total > 0) {
                        done += Math.max(0, target.toFile().length());
                        progress.advance(done);
                    }
                }
                return urlsOf(present);
            }

            private List<URL> urlsOf(List<Path> jars) throws IOException {
                List<URL> urls = new ArrayList<>(jars.size());
                for (Path jar : jars) urls.add(jar.toUri().toURL());
                return urls;
            }

            @Override
            public String toString() {
                return "EngineSource.downloadedFrom(" + resourceRoot + " -> " + into + ")";
            }
        };
    }

    /** Two sources tried in order — the first non-empty answer wins. */
    static EngineSource firstOf(EngineSource... candidates) {
        List<EngineSource> ordered = Arrays.asList(candidates);
        return new EngineSource() {
            @Override
            public List<URL> jarsFor(EngineBand band) throws IOException {
                for (EngineSource candidate : ordered) {
                    if (candidate == null) continue;
                    List<URL> found = candidate.jarsFor(band);
                    if (!found.isEmpty()) return found;
                }
                return Collections.emptyList();
            }

            @Override
            public String toString() {
                return "EngineSource.firstOf" + ordered;
            }
        };
    }
}
