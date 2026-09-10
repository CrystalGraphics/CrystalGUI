package com.crystalgui.mc.lang;

import com.crystalgui.language.cache.Download;
import com.crystalgui.language.cache.Downloads;
import com.crystalgui.language.platform.MappingCoordinates;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Where Mojang's official mappings for a Minecraft version live, asked of Mojang.
 *
 * <pre>{@code
 * MappingCoordinates.of("1.20.1", "forge-srg", forgeVersion)
 *     .readable("client.txt", MojangMappings1201.clientMappings("1.20.1"), null)
 *     .runtime("joined.tsrg", mcpConfigZipUrl, null, "config/joined.tsrg");
 * }</pre>
 *
 * <h3>Resolved, not pinned — and that is the honest option rather than the lazy one</h3>
 *
 * <p>{@code client.txt} is served from a content-addressed URL that contains its own SHA-1, and the only
 * way to learn it is the version manifest. Writing one into the source would mean recording a number
 * nobody here can verify; asking Mojang yields <b>both the URL and the digest from the same authority
 * that published the bytes</b>, and a version's entry never changes once released.</p>
 *
 * <p>Two hops — the manifest, then that version's own JSON — resolved once and remembered. It happens on
 * the fetching thread, which is what {@link MappingCoordinates.Source} exists to allow.</p>
 *
 * <h3>Gson, at the version Minecraft ships</h3>
 *
 * <p>{@code new JsonParser().parse(...)} rather than {@code JsonParser.parseString(...)}: the latter is
 * a static added in Gson 2.8.6, and 1.7.10 ships 2.2.4. This class only runs on 1.20.x, but the rule is
 * the project's and costs nothing to keep — the deprecated form exists in every version.</p>
 */
public final class MojangMappings1201 {

    private static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private MojangMappings1201() {
    }

    /** The client mappings for {@code minecraftVersion}, resolved when they are first fetched. */
    public static MappingCoordinates.Source clientMappings(String minecraftVersion) {
        return new MappingCoordinates.Source() {

            private String url;
            private String digest;

            @Override
            public String url() throws IOException {
                resolve();
                return url;
            }

            @Override
            public String digest() throws IOException {
                resolve();
                return digest;
            }

            private synchronized void resolve() throws IOException {
                if (url != null) return;
                JsonObject download = clientMappingsEntry(minecraftVersion);
                url = download.get("url").getAsString();
                // Tagged, because a digest without its algorithm is not a digest. @see CacheFiles
                digest = download.has("sha1") ? "sha1:" + download.get("sha1").getAsString() : null;
            }
        };
    }

    /** The {@code downloads.client_mappings} object for one version. */
    private static JsonObject clientMappingsEntry(String minecraftVersion) throws IOException {
        JsonObject manifest = fetch(VERSION_MANIFEST);
        String versionUrl = null;
        for (JsonElement entry : manifest.getAsJsonArray("versions")) {
            JsonObject version = entry.getAsJsonObject();
            if (minecraftVersion.equals(version.get("id").getAsString())) {
                versionUrl = version.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Mojang's manifest lists no Minecraft " + minecraftVersion);
        }
        JsonObject downloads = fetch(versionUrl).getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("client_mappings")) {
            // True of every version before 1.14.4, and the message has to say so: "no mappings" and "the
            // download failed" produce the same runtime names and are entirely different things.
            throw new IOException("Minecraft " + minecraftVersion + " publishes no client mappings");
        }
        return downloads.getAsJsonObject("client_mappings");
    }

    private static JsonObject fetch(String url) throws IOException {
        try (Download download = Downloads.from(url).named("Minecraft version manifest").open();
             Reader json = new InputStreamReader(download.stream(), StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(json);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("expected a JSON object from " + url);
            }
            return parsed.getAsJsonObject();
        }
    }
}
