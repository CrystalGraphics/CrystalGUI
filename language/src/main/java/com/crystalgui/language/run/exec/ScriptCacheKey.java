package com.crystalgui.language.run.exec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * What makes a compiled script reusable — §15.5 D.3's {@code (source hash, mappings hash, band)}.
 *
 * <h3>Three components, and each one is a real invalidation</h3>
 *
 * <ul>
 *   <li><b>Source</b> — obviously. Hashed rather than kept, because a world with fifty scripts should
 *       not hold fifty copies of their text to answer a question about identity.</li>
 *   <li><b>Mappings</b> — the same source compiled against a different mapping set produces different
 *       bytecode, because the out pass rewrote it. Two environments of the same Minecraft version can
 *       disagree here (a dev launch is identity, production is not), and a cache that ignored it would
 *       hand production a dev-compiled script whose every MC call links to nothing.</li>
 *   <li><b>Band</b> — a script compiled at Java 17 does not load on Java 8, and the failure is an
 *       {@code UnsupportedClassVersionError} naming a number rather than a script.</li>
 * </ul>
 *
 * <p><b>Invalidation is structural, not timed.</b> There is no expiry and no revalidation: any
 * component changes and the key is a different key, so the old entry is simply never asked for again.
 * That is the only invalidation scheme with no window in which a stale answer can be served.</p>
 *
 * <h3>The classpath is deliberately NOT part of the key</h3>
 *
 * <p>It is the one that looks like it belongs. It does not, for a practical reason and a principled
 * one: the classpath of a modded launch is thousands of entries and hashing it per compile would cost
 * more than the compile, and a script's <em>bytecode</em> does not embed what it was compiled against —
 * it embeds symbolic references, which are resolved at link time against whatever is actually there. A
 * classpath change that matters shows up as a linkage error, loudly, which is the correct place for it.</p>
 */
public final class ScriptCacheKey {

    private final String sourceHash;
    private final String mappingsHash;
    private final int band;
    private final String dependencyHash;

    private ScriptCacheKey(String sourceHash, String mappingsHash, int band, String dependencyHash) {
        this.sourceHash = sourceHash;
        this.mappingsHash = mappingsHash;
        this.band = band;
        this.dependencyHash = dependencyHash;
    }

    /**
     * @param source       the script text as the author wrote it
     * @param mappingsHash an identifier for the mapping set — {@code "identity"} where there is none
     * @param band         the band's minimum feature version, which is what the bytecode targets
     */
    public static ScriptCacheKey of(String source, String mappingsHash, int band) {
        return of(source, mappingsHash, band, "");
    }

    /**
     * As above, plus a fingerprint of the OTHER files this compile depended on.
     *
     * <p>The component M15 S5 added, and the one that keeps the scheme above honest. Everything else here
     * describes the file the author ran; once a script can resolve a sibling, that file's text is no
     * longer the whole input, so a key built from it alone would serve bytes compiled against a version
     * of the sibling that no longer exists. The author would edit the other file, run again, and see the
     * old behaviour — which reads as the edit not having been picked up rather than as a cache decision,
     * and is worse than a slow compile by a wide margin.</p>
     *
     * <p>It stays STRUCTURAL, which is the property the three components above are chosen for: a changed
     * dependency is a different key, not an entry somebody has to remember to evict. The caller computes
     * the fingerprint because only it knows how to read a workspace — and because this package is the
     * engine-neutral Run shell and may not name a language.</p>
     *
     * @param dependencyHash any stable digest of what the compile pulled in, or {@code ""} for a script
     *                       that reached nothing outside itself — which is every script with no workspace
     *                       behind it, and keeps such a key identical to the three-component form
     */
    public static ScriptCacheKey of(String source, String mappingsHash, int band, String dependencyHash) {
        return new ScriptCacheKey(sha256(source == null ? "" : source),
                mappingsHash == null ? "identity" : mappingsHash, band,
                dependencyHash == null ? "" : dependencyHash);
    }

    /** A digest of what this compile depended on beyond its own text, or {@code ""}. */
    public String dependencyHash() {
        return dependencyHash;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String mappingsHash() {
        return mappingsHash;
    }

    public int band() {
        return band;
    }

    /**
     * A filesystem-safe name for this key, for a directory-backed cache.
     *
     * <p>Every component appears, so two entries that differ in any of them cannot collide on disk —
     * and a human looking at the directory can tell which is which, which matters the first time
     * somebody has to work out why a cache is not hitting.</p>
     */
    public String fileName() {
        return sourceHash + "-" + sanitise(mappingsHash) + "-" + band
                + (dependencyHash.isEmpty() ? "" : "-" + sanitise(dependencyHash));
    }

    private static String sanitise(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            safe.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return safe.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            // SIXTEEN CHARACTERS of a SHA-256, not the whole thing. A collision needs two scripts whose
            // hashes agree in 64 bits, which is not a risk anybody will meet, and the short form is
            // what makes a cache directory readable.
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ScriptCacheKey)) return false;
        ScriptCacheKey key = (ScriptCacheKey) other;
        return band == key.band && sourceHash.equals(key.sourceHash)
                && mappingsHash.equals(key.mappingsHash)
                && dependencyHash.equals(key.dependencyHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceHash, mappingsHash, band, dependencyHash);
    }

    @Override
    public String toString() {
        return "ScriptCacheKey[" + fileName() + "]";
    }
}
