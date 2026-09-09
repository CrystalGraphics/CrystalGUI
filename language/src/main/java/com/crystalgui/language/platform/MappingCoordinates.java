package com.crystalgui.language.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which mapping artifact an environment needs — <b>data, not behaviour</b>.
 *
 * <p>A platform states the coordinates; {@code language/} does the fetching, verifying, caching and
 * parsing. That split is the whole point of {@link ScriptService}: a second loader contributes one of
 * these rather than a second copy of the acquisition logic.</p>
 *
 * <h3>The version is pinned here, never discovered</h3>
 *
 * <p>A published mapping version is immutable — {@code mcp_stable} 12 for 1.7.10 is frozen and will
 * never change content under that name. That is what lets the cache be <em>version-addressed</em> and
 * therefore have nothing to invalidate: a different requirement is a different directory.</p>
 *
 * <p>The property only holds while the coordinates are stated by the mod. A version read out of the
 * running environment is a version that can differ between dev and production, which is the one thing
 * this whole phase exists to prevent.</p>
 *
 * <h3>Digests are part of the coordinates</h3>
 *
 * <p>Upstream publishes an {@code .md5} beside each file, but trusting the digest a server hands over
 * with the bytes only proves the download was self-consistent. Pinning the expected digest here means a
 * corrupted <em>download</em> and a corrupted <em>cache</em> fail the same check, and a mirror serving
 * something unexpected is rejected rather than quietly accepted.</p>
 *
 * <p>A file with no pinned digest is still fetched and used — {@link #digestOf} answers null and the
 * caller may only check that it parses. That is a deliberate allowance for bringing a platform up before
 * its digests are known, not a permanent state.</p>
 */
public final class MappingCoordinates {

    /**
     * Which half of a join a file is — both halves keyed on the namespace they share.
     *
     * <p>1.7.10 needs neither: MCP's CSVs map runtime→readable outright, so they are {@link #READABLE}
     * and there is no second half. Every 1.20.x loader needs both, because no published artifact maps
     * out of a namespace a runtime actually speaks — see {@link com.crystalgui.language.map.MappingSet#then}.</p>
     */
    public enum Side {
        /** Maps the shared namespace to the READABLE one — Mojang's {@code client.txt}. */
        READABLE,
        /** Maps the shared namespace to what the RUNTIME speaks — MCPConfig's {@code joined.tsrg}. */
        RUNTIME
    }

    /**
     * Where one file comes from, answered when it is FETCHED rather than when it is declared.
     *
     * <p>Deferred because some addresses cannot be written down. Mojang's mappings live at a
     * content-addressed URL discoverable only through its version manifest, so the platform resolves
     * both the URL and the digest from upstream at fetch time — nothing is pinned to a number nobody
     * could verify, and the manifest is immutable per Minecraft version.</p>
     *
     * <pre>{@code
     * coordinates.readable("client.txt", MojangMappings1201.clientMappings("1.20.1"), null);
     * }</pre>
     *
     * <p>Called on the fetching thread, never on the caller of {@code mappings()} — so a resolver may do
     * network work, and a failure arrives as the ordinary "could not fetch" rather than as a stall on
     * whatever asked for the coordinates.</p>
     */
    public interface Source {

        /** Where to download from. */
        String url() throws IOException;

        /** The expected digest, tagged with its algorithm, or null when nothing is pinned. */
        default String digest() throws IOException {
            return null;
        }

        /** A URL and digest that were known up front. */
        static Source fixed(String url, String digest) {
            return new Source() {
                @Override
                public String url() {
                    return url;
                }

                @Override
                public String digest() {
                    return digest;
                }
            };
        }
    }

    /** One file: where it comes from, what it should hash to, and which half of the join it is. */
    private static final class Artifact {
        final Source source;
        final String archiveEntry;
        final Side side;

        Artifact(Source source, String archiveEntry, Side side) {
            this.source = source;
            this.archiveEntry = archiveEntry;
            this.side = side;
        }
    }


    /** Runtime already speaks the readable namespace, so there is nothing to fetch. */
    public static final MappingCoordinates NONE = new MappingCoordinates("", "", "", "",
            Collections.<String>emptyList(), Collections.<String, String>emptyMap());

    private final String minecraftVersion;
    private final String channel;
    private final String version;
    private final String baseUrl;
    /** The files to fetch, in the order a platform named them. @see #files */
    private final List<String> files;
    private final Map<String, String> digests;
    /** Per-file source and side, for coordinates that state them. @see #readable @see #runtime */
    private final Map<String, Artifact> artifacts;
    /** @see #runtimeKeepsReadableClassNames */
    private final boolean readableClassNames;

    private MappingCoordinates(String minecraftVersion, String channel, String version, String baseUrl,
                               List<String> files, Map<String, String> digests) {
        this(minecraftVersion, channel, version, baseUrl, files, digests,
                Collections.<String, Artifact>emptyMap());
    }

    private MappingCoordinates(String minecraftVersion, String channel, String version, String baseUrl,
                               List<String> files, Map<String, String> digests,
                               Map<String, Artifact> artifacts) {
        this(minecraftVersion, channel, version, baseUrl, files, digests, artifacts, false);
    }

    private MappingCoordinates(String minecraftVersion, String channel, String version, String baseUrl,
                               List<String> files, Map<String, String> digests,
                               Map<String, Artifact> artifacts, boolean readableClassNames) {
        this.readableClassNames = readableClassNames;
        this.minecraftVersion = minecraftVersion;
        this.channel = channel;
        this.version = version;
        this.baseUrl = baseUrl;
        this.files = files;
        this.digests = digests;
        this.artifacts = artifacts;
    }

    /**
     * @param minecraftVersion e.g. {@code 1.7.10} — the first level of the cache path
     * @param channel          e.g. {@code stable}
     * @param version          e.g. {@code 12}
     * @param baseUrl          where the files live, with or without a trailing slash
     */
    public static MappingCoordinates of(String minecraftVersion, String channel, String version,
                                        String baseUrl) {
        return new MappingCoordinates(minecraftVersion, channel, version,
                baseUrl.endsWith("/") ? baseUrl : baseUrl + "/",
                Collections.<String>emptyList(), Collections.<String, String>emptyMap());
    }

    /**
     * Coordinates whose files each state their own URL. @see #readable @see #runtime
     *
     * <p>For a target assembled from several publishers, where there is no {@code baseUrl} to share.</p>
     */
    public static MappingCoordinates of(String minecraftVersion, String channel, String version) {
        return new MappingCoordinates(minecraftVersion, channel, version, "",
                Collections.<String>emptyList(), Collections.<String, String>emptyMap());
    }

    /**
     * One file this artifact consists of, with no digest pinned yet.
     *
     * <p>Order is kept, because it is overlay order when the files are parsed: a later file's entry for
     * the same runtime name wins.</p>
     */
    public MappingCoordinates withFile(String fileName) {
        if (files.contains(fileName)) return this;
        List<String> next = new ArrayList<String>(files);
        next.add(fileName);
        return new MappingCoordinates(minecraftVersion, channel, version, baseUrl,
                Collections.unmodifiableList(next), digests);
    }

    /**
     * One file and its expected MD5, e.g. {@code withDigest("methods.csv", "a1b2…")}.
     *
     * <p>Names the file as well as pinning it, so a platform states its artifact once rather than in two
     * lists that can disagree about which files exist.</p>
     */
    public MappingCoordinates withDigest(String fileName, String md5) {
        Map<String, String> next = new LinkedHashMap<String, String>(digests);
        next.put(fileName, md5.toLowerCase());
        MappingCoordinates named = withFile(fileName);
        return new MappingCoordinates(minecraftVersion, channel, version, baseUrl,
                named.files, Collections.unmodifiableMap(next));
    }

    /**
     * One file mapping the shared namespace to the <b>readable</b> one, from its own URL.
     *
     * <pre>{@code
     * MappingCoordinates.of("1.20.1", "forge-srg", "47.2.0")
     *     .readable("client.txt", mojangClientTxtUrl, sha1)
     *     .runtime("joined.tsrg", mcpConfigZipUrl, md5, "config/joined.tsrg");
     * }</pre>
     *
     * <p>Per-file rather than under one {@code baseUrl}, because the two halves come from different
     * publishers — Mojang and Forge — and nothing sensible is shared between those URLs.</p>
     *
     * @param archiveEntry a path inside a zip or jar, or null when the URL is the file itself
     */
    public MappingCoordinates readable(String fileName, String url, String digest, String archiveEntry) {
        return with(fileName, Source.fixed(url, digest), digest, archiveEntry, Side.READABLE);
    }

    /** @see #readable(String, String, String, String) */
    public MappingCoordinates readable(String fileName, String url, String digest) {
        return with(fileName, Source.fixed(url, digest), digest, null, Side.READABLE);
    }

    /** The readable half, from an address only upstream can give. @see Source */
    public MappingCoordinates readable(String fileName, Source source, String archiveEntry) {
        return with(fileName, source, null, archiveEntry, Side.READABLE);
    }

    /**
     * One file mapping the shared namespace to what the <b>runtime</b> speaks.
     *
     * <p>Its presence is what makes these coordinates a join: the acquired halves are combined as
     * {@code runtime.invert().then(readable)} rather than overlaid. @see #isJoined</p>
     */
    public MappingCoordinates runtime(String fileName, String url, String digest, String archiveEntry) {
        return with(fileName, Source.fixed(url, digest), digest, archiveEntry, Side.RUNTIME);
    }

    /** @see #runtime(String, String, String, String) */
    public MappingCoordinates runtime(String fileName, String url, String digest) {
        return with(fileName, Source.fixed(url, digest), digest, null, Side.RUNTIME);
    }

    /** The runtime half, from an address only upstream can give. @see Source */
    public MappingCoordinates runtime(String fileName, Source source, String archiveEntry) {
        return with(fileName, source, null, archiveEntry, Side.RUNTIME);
    }

    private MappingCoordinates with(String fileName, Source source, String digest, String archiveEntry,
                                    Side side) {
        MappingCoordinates named = withFile(fileName);
        Map<String, String> nextDigests = new LinkedHashMap<String, String>(digests);
        if (digest != null && !digest.isEmpty()) nextDigests.put(fileName, digest.toLowerCase());
        Map<String, Artifact> next = new LinkedHashMap<String, Artifact>(artifacts);
        next.put(fileName, new Artifact(source, archiveEntry, side));
        return new MappingCoordinates(minecraftVersion, channel, version, baseUrl, named.files,
                Collections.unmodifiableMap(nextDigests), Collections.unmodifiableMap(next));
    }

    /**
     * Where {@code fileName} comes from — never null.
     *
     * <p>A file that stated nothing of its own is {@code baseUrl + name} with whatever digest was pinned,
     * which is how 1.7.10's coordinates have always been addressed.</p>
     */
    public Source sourceOf(String fileName) {
        Artifact artifact = artifacts.get(fileName);
        return artifact == null ? Source.fixed(urlOf(fileName), digestOf(fileName)) : artifact.source;
    }

    /**
     * Declares that the runtime keeps the READABLE class names and renames only members.
     *
     * <pre>{@code
     * MappingCoordinates.of("1.20.1", "srg", version)
     *     .readable("client.txt", mojang, null)
     *     .runtime("joined.tsrg", mcpConfigZip, null, "config/joined.tsrg")
     *     .runtimeKeepsReadableClassNames();   // Forge: official classes, SRG members
     * }</pre>
     *
     * <p>True of Forge from 1.17 on. NOT true of Fabric, whose runtime really does have
     * {@code net/minecraft/class_1937}. @see com.crystalgui.language.map.MappingSet#withoutClassRenames</p>
     */
    public MappingCoordinates runtimeKeepsReadableClassNames() {
        return new MappingCoordinates(minecraftVersion, channel, version, baseUrl, files, digests,
                artifacts, true);
    }

    /** @see #runtimeKeepsReadableClassNames */
    public boolean keepsReadableClassNames() {
        return readableClassNames;
    }

    /**
     * Whether these coordinates are a JOIN of two halves rather than a set of overlaid files.
     *
     * <p>True as soon as one file is declared {@link Side#RUNTIME}. 1.7.10 declares none and keeps the
     * overlay behaviour it has always had.</p>
     */
    public boolean isJoined() {
        for (Artifact artifact : artifacts.values()) {
            if (artifact.side == Side.RUNTIME) return true;
        }
        return false;
    }

    /** Which half {@code fileName} is — {@link Side#READABLE} for anything that did not say. */
    public Side sideOf(String fileName) {
        Artifact artifact = artifacts.get(fileName);
        return artifact == null ? Side.READABLE : artifact.side;
    }

    /** The path inside the downloaded archive, or null when the download IS the file. */
    public String archiveEntryOf(String fileName) {
        Artifact artifact = artifacts.get(fileName);
        return artifact == null ? null : artifact.archiveEntry;
    }

    /** The files to fetch, in the order they were named. Empty means there is nothing to acquire. */
    public List<String> files() {
        return files;
    }

    /** Whether there is anything to fetch at all. */
    public boolean isNone() {
        return channel.isEmpty() || version.isEmpty() || files.isEmpty();
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    /**
     * The directory name this artifact caches under, e.g. {@code stable-12}.
     *
     * <p>Version-addressed on purpose — see the class javadoc. Two artifacts never share a directory,
     * so an upgrade is a miss rather than an invalidation.</p>
     */
    public String cacheKey() {
        return channel + "-" + version;
    }

    /**
     * Absolute URL for one file — {@code baseUrl + name} unless it stated a source of its own.
     *
     * <p>Null for a file whose address has to be resolved upstream, since there is nothing to report
     * until it is. {@link #sourceOf} is what a fetcher asks.</p>
     */
    public String urlOf(String fileName) {
        Artifact artifact = artifacts.get(fileName);
        if (artifact == null) return baseUrl + fileName;
        try {
            return artifact.source.url();
        } catch (IOException unresolved) {
            return null;
        }
    }

    /** The pinned MD5 for one file, or null when none was stated. */
    public String digestOf(String fileName) {
        return digests.get(fileName);
    }

    @Override
    public String toString() {
        return isNone() ? "MappingCoordinates.NONE"
                : "MappingCoordinates[" + minecraftVersion + " " + cacheKey() + "]";
    }
}
