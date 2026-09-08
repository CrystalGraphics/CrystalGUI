package com.crystalgui.language.map;

import com.crystalgui.language.cache.CacheFiles;
import com.crystalgui.language.cache.Downloads;
import com.crystalgui.language.map.format.MappingFiles;
import com.crystalgui.language.platform.MappingCoordinates;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mapping data on disk: present it, verify it, fetch it if it is not there — then parse it once.
 *
 * <h3>Downloaded rather than bundled, and that is a licensing distinction rather than caution</h3>
 *
 * <p>Two acts get conflated. <b>Building a mod with MCP mappings</b> puts SRG names in the bytecode and
 * no mapping data in the jar; every Forge mod since 2011 does it. <b>Putting the CSVs in the jar as
 * runtime data</b> redistributes the mapping data itself, which classic MCP terms prohibited — and
 * 1.7.10-era {@code mcp_stable} predates the 2020 relicensing, so the old terms apply. Fetching from the
 * canonical source sidesteps the question: nothing is redistributed, and the user's machine gets the
 * files from where Gradle already gets them. The opposite call from the engine bands, deliberately.</p>
 *
 * <h3>Staleness is designed out rather than managed</h3>
 *
 * <p>A published mapping version is immutable: {@code mcp_stable} 12 for 1.7.10 is frozen and will never
 * change content under that name. So the version <em>is</em> the cache key and <b>there is nothing to
 * invalidate</b> — a different requirement is a different directory, an upgrade is a miss, and the old
 * directory simply goes inert. No TTL, no revalidation, no invalidation logic anywhere.</p>
 *
 * <p>What still needs handling is not staleness but <b>partial and damaged state</b>, and that is
 * {@link CacheFiles}: present-and-valid checked on every launch, missing treated identically to invalid,
 * verified against a pinned digest rather than a size, installed atomically, deleted on failure so the
 * next launch retries.</p>
 *
 * <h3>Absent is a supported state, and which absence it is gets said out loud</h3>
 *
 * <p>No mappings means the runtime namespace is presented as-is: the editor opens, colours, compiles and
 * runs scripts, and shows {@code func_147439_a} instead of {@code getBlock}. The same degradation
 * {@code EngineHost} applies to an absent band. But "nothing was configured" and "the download failed"
 * are different things to somebody offline on purpose, and the line that distinguishes them is the
 * difference between a bug report and a shrug — so {@link Result#state} carries which, and the caller
 * reports it once.</p>
 *
 * <h3>Never on a thread that renders</h3>
 *
 * <p>{@link #load} does network I/O on the calling thread. It is a caller's job to run it on a worker;
 * a fetch inside {@code initGui} would stall the client for as long as the network takes to fail.</p>
 */
public final class MappingCache {

    /** What happened, so a caller can say which absence this is. */
    public enum State {
        /** No coordinates — a platform whose runtime is already readable. Not an error. */
        NOT_CONFIGURED,
        /** Everything was already cached and valid. The ordinary launch. */
        CACHED,
        /** At least one file was fetched and installed. The first launch, or after a repair. */
        FETCHED,
        /** Wanted, and could not be had. Offline, a mirror that moved, a digest that did not match. */
        UNAVAILABLE
    }

    /** The mapping, and how it was arrived at. {@code mappings} is never null. */
    public static final class Result {
        private final State state;
        private final MappingSet mappings;
        private final String detail;

        private Result(State state, MappingSet mappings, String detail) {
            this.state = state;
            this.mappings = mappings;
            this.detail = detail;
        }

        public State state() {
            return state;
        }

        /** {@link MappingSet#IDENTITY} for every state but {@link State#CACHED} and {@link State#FETCHED}. */
        public MappingSet mappings() {
            return mappings;
        }

        /** One line naming what happened — the thing a caller logs. */
        public String detail() {
            return detail;
        }
    }

    /** Long enough for a slow connection, short enough that an unreachable host does not hang a launch. */
    private MappingCache() {
    }

    /**
     * The mapping for {@code coordinates}, fetching into {@code cacheRoot} if it is not already there.
     *
     * <p>Layout, decided by the core rather than by a platform, so every target caches the same shape:</p>
     *
     * <pre>
     * &lt;cacheRoot&gt;/mappings/&lt;mcVersion&gt;/&lt;channel&gt;-&lt;version&gt;/methods.csv
     * </pre>
     */
    public static Result load(MappingCoordinates coordinates, Path cacheRoot) {
        return load(coordinates, cacheRoot, () -> false);
    }

    /**
     * The same, stoppable partway through the network half.
     *
     * <p>The two-argument form is for the <b>cached</b> path, which is a parse and no network — there is
     * nothing there worth interrupting, and giving it a flag would suggest otherwise.</p>
     */
    public static Result load(MappingCoordinates coordinates, Path cacheRoot,
                              BooleanSupplier cancelled) {
        if (coordinates == null || coordinates.isNone() || cacheRoot == null) {
            return new Result(State.NOT_CONFIGURED, MappingSet.IDENTITY,
                    "no mapping coordinates; runtime names will be shown as they are");
        }

        Path directory = cacheRoot.resolve("mappings")
                .resolve(coordinates.minecraftVersion())
                .resolve(coordinates.cacheKey());

        List<Path> present = new ArrayList<>();
        List<String> fetched = new ArrayList<>();
        for (String fileName : coordinates.files()) {
            Path target = directory.resolve(fileName);
            String archiveEntry = coordinates.archiveEntryOf(fileName);
            // AN EXTRACTED ENTRY CANNOT BE CHECKED AGAINST THE ARCHIVE'S DIGEST. What upstream publishes
            // is a hash of the zip, so verification happens on the download and presence is the check on
            // what came out of it. Comparing the two would fail every time and re-download every launch.
            String cachedDigest = archiveEntry == null ? coordinates.digestOf(fileName) : null;
            if (CacheFiles.isValid(target, cachedDigest)) {
                present.add(target);
                continue;
            }
            try {
                // RESOLVED HERE, on the fetching thread. An address that has to be looked up upstream is
                // network work, and doing it when the coordinates were declared would put it on whatever
                // asked for them -- which is the frame thread. @see MappingCoordinates.Source
                MappingCoordinates.Source source = coordinates.sourceOf(fileName);
                String url = source.url();
                String digest = source.digest();
                // NO `reporting` HERE ON PURPOSE. PlatformMappings has already announced this as a
                // SWEEP -- the two CSVs are small and their host declares no length worth trusting, so a
                // bar would be invented rather than measured -- and a second announce from inside would
                // retarget the very thing that decided a sweep was the honest answer.
                boolean arrived = archiveEntry == null
                        ? Downloads.from(url).verifying(digest).cancelledWhen(cancelled).into(target)
                        : extract(url, digest, archiveEntry, target, cancelled);
                if (!arrived) {
                    return new Result(State.UNAVAILABLE, MappingSet.IDENTITY,
                            fileName + " did not match its expected digest and was discarded; "
                                    + "runtime names will be shown as they are");
                }
                present.add(target);
                fetched.add(fileName);
            } catch (IOException unreachable) {
                return new Result(State.UNAVAILABLE, MappingSet.IDENTITY,
                        "could not fetch " + fileName + " (" + unreachable + "); "
                                + "runtime names will be shown as they are");
            }
        }

        try {
            MappingSet mappings = coordinates.isJoined()
                    ? join(coordinates, present)
                    : MappingFiles.load(present);
            if (mappings.isIdentity()) {
                // EVERY FILE ARRIVED AND NOTHING PARSED. That is a format nobody here knows rather than a
                // missing download, and it is worth distinguishing: the fix is a MappingFormat, not a
                // network. Reported as UNAVAILABLE because the OUTCOME is the same -- runtime names --
                // and the detail is what says why.
                return new Result(State.UNAVAILABLE, MappingSet.IDENTITY,
                        "the mapping files at " + directory + " parsed to nothing; no known format "
                                + "recognised them, so runtime names will be shown as they are");
            }
            String where = " from " + directory;
            return fetched.isEmpty()
                    ? new Result(State.CACHED, mappings, "mappings ready" + where)
                    : new Result(State.FETCHED, mappings,
                    "fetched " + fetched + where);
        } catch (IOException unreadable) {
            return new Result(State.UNAVAILABLE, MappingSet.IDENTITY,
                    "the mapping files could not be read (" + unreadable + "); "
                            + "runtime names will be shown as they are");
        }
    }

    /**
     * Downloads an archive and takes ONE entry out of it.
     *
     * <p>The mapping data a modern loader needs is published inside a zip — MCPConfig's
     * {@code joined.tsrg} and Fabric's {@code mappings.tiny} both — and the rest of the archive is of no
     * interest. The archive is fetched beside its target, opened, the one entry written, and the archive
     * deleted: what stays cached is the mapping file, so a second launch parses it directly.</p>
     *
     * @return false when the download did not match its digest, exactly as a plain fetch reports it
     */
    private static boolean extract(String url, String digest, String entryName, Path target,
                                   BooleanSupplier cancelled) throws IOException {
        Path archive = target.resolveSibling(target.getFileName() + ".archive");
        try {
            if (!Downloads.from(url).verifying(digest).cancelledWhen(cancelled).into(archive)) {
                return false;
            }
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    throw new IOException("no entry '" + entryName + "' in " + url);
                }
                try (InputStream contents = zip.getInputStream(entry)) {
                    // No digest: what upstream pinned was the archive, and that has been checked already.
                    CacheFiles.install(target, contents, null);
                }
            }
            return true;
        } finally {
            // The archive is a download artifact, not cache content -- MCPConfig's is megabytes and only
            // one file in it is ever read.
            Files.deleteIfExists(archive);
        }
    }

    /**
     * The two halves of a join, composed into the mapping the runtime needs.
     *
     * <p>No published artifact maps out of a namespace a runtime speaks — Mojang's is obf→official,
     * MCPConfig's is obf→srg, Fabric's is obf→intermediary — so every 1.20.x mapping is
     * {@code runtime.invert().then(readable)} over a shared obfuscated namespace.</p>
     *
     * <p>Each half is parsed on its own and overlaid within itself, so a half made of several files
     * behaves exactly as an unjoined artifact does.</p>
     *
     * <p>A half with no files at all composes to {@link MappingSet#IDENTITY}, which is the honest
     * answer: half a join is not a mapping, and {@code then} declines to invent one from it.</p>
     */
    private static MappingSet join(MappingCoordinates coordinates, List<Path> present)
            throws IOException {
        List<Path> readable = new ArrayList<>();
        List<Path> runtime = new ArrayList<>();
        for (Path file : present) {
            String name = file.getFileName().toString();
            if (coordinates.sideOf(name) == MappingCoordinates.Side.RUNTIME) {
                runtime.add(file);
            } else {
                readable.add(file);
            }
        }
        if (readable.isEmpty() || runtime.isEmpty()) return MappingSet.IDENTITY;
        // WITH THE UNQUALIFIED TIER, or everything that scans TEXT is blind to this mapping -- the Remap
        // command and ReadableSource have a name and a dot and no owner to key on.
        return MappingFiles.load(runtime).invert()
                .then(MappingFiles.load(readable))
                .withUnqualifiedMembers();
    }

    /**
     * A stream over one URL, with timeouts and redirects followed.
     *
     * <p>Redirects matter here: the canonical source is a {@code raw.githubusercontent.com} URL, which
     * redirects, and a connection that does not follow one reports a 302 body as the file. That failure
     * arrives as a digest mismatch, which reads as corruption rather than as a redirect.</p>
     */
    /** Where {@code load} caches — exposed so a caller can report or clear it. */
    public static Path directoryFor(MappingCoordinates coordinates, Path cacheRoot) {
        if (coordinates == null || coordinates.isNone() || cacheRoot == null) return null;
        return cacheRoot.resolve("mappings")
                .resolve(coordinates.minecraftVersion())
                .resolve(coordinates.cacheKey());
    }

    /** Whether every file is already cached and valid — a check with no network in it. */
    public static boolean isComplete(MappingCoordinates coordinates, Path cacheRoot) {
        Path directory = directoryFor(coordinates, cacheRoot);
        if (directory == null || !Files.isDirectory(directory)) return false;
        for (String fileName : coordinates.files()) {
            if (!CacheFiles.isValid(directory.resolve(fileName), coordinates.digestOf(fileName))) {
                return false;
            }
        }
        return true;
    }
}
