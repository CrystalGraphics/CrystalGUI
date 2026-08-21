package com.crystalgui.language.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a band's jars are called, what they should hash to, and where to fetch them.
 *
 * <p>Shipped as {@code assets/crystalgui/engines/<band>/manifest.txt}, one line per artifact:</p>
 *
 * <pre>{@code name|md5|url}</pre>
 *
 * <h3>Why a manifest exists for bands the jar does not carry</h3>
 *
 * <p>A 1.7.10 client on Java 17 — which lwjgl3ify and GTNH make ordinary — selects band 17 and finds
 * nothing bundled. Shipping all three bands is 41 MB; shipping three manifests is a few hundred bytes and
 * lets the client fetch the one it actually needs. {@link EngineBundle}'s {@code index.txt} is the
 * bundled-jar counterpart and stays separate: one says "these are here", the other "these can be had".</p>
 *
 * <h3>The digest, and what it is honestly for</h3>
 *
 * <p>Written at build time by hashing the artifact <b>Gradle resolved</b> — so it pins the exact bytes the
 * build was tested against, needs no network to produce, and is checkable offline. It is MD5, matching
 * {@code CacheFiles}, and it is a <b>corruption-and-drift check</b>: a truncated transfer, a mirror
 * serving something else, a half-written cache entry. It is not a security boundary and must not be
 * described as one — authenticity is HTTPS's job.</p>
 */
public record EngineManifest(String fileName, String md5, String url) {

    static final String MANIFEST = "manifest.txt";

    /**
     * Reads the manifest under {@code prefix}, or an empty list.
     *
     * <p>A malformed line is <b>skipped rather than fatal</b>. The alternative is a jar that ships one bad
     * row and acquires no engine at all, which trades a missing artifact for a missing feature.</p>
     */
    static List<EngineManifest> listing(ClassLoader loader, String prefix) throws IOException {
        InputStream manifest = loader.getResourceAsStream(prefix + MANIFEST);
        if (manifest == null) return Collections.emptyList();
        List<EngineManifest> rows = new ArrayList<>();
        try (BufferedReader lines =
                     new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\|");
                if (parts.length != 3) continue;
                String name = parts[0].trim();
                // The same guard EngineBundle applies, and for the same reason: a name out of a shipped
                // text file becomes a path, so it may not climb out of the directory it belongs in.
                if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                        || name.contains("..")) {
                    continue;
                }
                rows.add(new EngineManifest(name, parts[1].trim(), parts[2].trim()));
            }
        }
        return rows;
    }
}
