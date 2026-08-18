package com.crystalgui.language.platform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which mapping artifact an environment needs — <b>data, not behaviour</b>.
 *
 * <p>A platform states the coordinates; {@code language/} does the fetching, verifying, caching and
 * parsing. That split is the whole point of {@link ScriptPlatform}: a second loader contributes one of
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

    /** Runtime already speaks the readable namespace, so there is nothing to fetch. */
    public static final MappingCoordinates NONE =
            new MappingCoordinates("", "", "", "", Collections.<String, String>emptyMap());

    private final String minecraftVersion;
    private final String channel;
    private final String version;
    private final String baseUrl;
    private final Map<String, String> digests;

    private MappingCoordinates(String minecraftVersion, String channel, String version, String baseUrl,
                               Map<String, String> digests) {
        this.minecraftVersion = minecraftVersion;
        this.channel = channel;
        this.version = version;
        this.baseUrl = baseUrl;
        this.digests = digests;
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
                Collections.<String, String>emptyMap());
    }

    /** The expected MD5 of one file, e.g. {@code withDigest("methods.csv", "a1b2…")}. */
    public MappingCoordinates withDigest(String fileName, String md5) {
        Map<String, String> next = new LinkedHashMap<String, String>(digests);
        next.put(fileName, md5.toLowerCase());
        return new MappingCoordinates(minecraftVersion, channel, version, baseUrl,
                Collections.unmodifiableMap(next));
    }

    /** Whether there is anything to fetch at all. */
    public boolean isNone() {
        return channel.isEmpty() || version.isEmpty();
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

    /** Absolute URL for one file. */
    public String urlOf(String fileName) {
        return baseUrl + fileName;
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
