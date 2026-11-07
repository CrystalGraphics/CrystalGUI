package com.crystalgui.language.map;

import com.crystalgui.core.storage.CrystalGuiDownloads;
import com.crystalgraphics.storage.cache.CgCacheFiles;
import com.crystalgraphics.storage.download.CgDownload;
import com.crystalgraphics.storage.download.CgDownloads;
import com.crystalgui.language.platform.MappingCoordinates;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Where Mojang's official mappings for a Minecraft version live.
 *
 * <pre>{@code
 * MappingCoordinates.of("1.20.1", "srg", "1.20.1")
 *     .readable("client.txt", MojangMappings.clientMappings("1.20.1"), null)
 *     .runtime("joined.tsrg", Source.located("forge/mcp-config/1.20.1"), "config/joined.tsrg");
 * }</pre>
 *
 * <ul>
 *   <li>A version {@code download/locations.json} pins — {@code mojang/<version>/client.txt} — is read from
 *       its entry there, digest included.</li>
 *   <li>Any other is asked of Mojang. {@code client.txt} lives at a content-addressed URL that contains its
 *       own SHA-1, and the version manifest yields <b>both the URL and the digest from the authority that
 *       published the bytes</b>; a version's entry never changes once released. Two hops — the manifest,
 *       then that version's own JSON — resolved once, on the fetching thread.</li>
 * </ul>
 */
public final class MojangMappings {

    private MojangMappings() {
    }

    /** The client mappings for {@code minecraftVersion}: pinned when the locations file pins them. */
    public static MappingCoordinates.Source clientMappings(String minecraftVersion) {
        String pinned = "mojang/" + minecraftVersion + "/client.txt";
        if (CrystalGuiDownloads.LOCATIONS.lists(pinned)) return MappingCoordinates.Source.located(pinned);
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
                Download download = clientMappingsEntry(minecraftVersion);
                url = download.url();
                // Tagged, because a digest without its algorithm is not a digest. @see CgCacheFiles
                digest = download.sha1() == null ? null : "sha1:" + download.sha1();
            }
        };
    }

    /** The {@code downloads.client_mappings} object for one version. */
    private static Download clientMappingsEntry(String minecraftVersion) throws IOException {
        Manifest manifest = read(CrystalGuiDownloads.located("mojang/version-manifest"), MANIFEST);
        String versionUrl = null;
        for (Listed version : manifest.versions()) {
            if (minecraftVersion.equals(version.id())) {
                versionUrl = version.url();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Mojang's manifest lists no Minecraft " + minecraftVersion);
        }
        Download mappings = read(CgDownloads.from(versionUrl), VERSION).downloads().get("client_mappings");
        if (mappings == null) {
            // True of every version before 1.14.4, and the message has to say so: "no mappings" and "the
            // download failed" produce the same runtime names and are entirely different things.
            throw new IOException("Minecraft " + minecraftVersion + " publishes no client mappings");
        }
        return mappings;
    }

    private static <T> T read(CgDownloads.Request request, CgCodec<T> codec) throws IOException {
        try (CgDownload download = request.named("Minecraft version manifest").open()) {
            return CgFiles.JSON.fromText(codec, new String(download.stream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (CgCodecException unreadable) {
            throw new IOException("Mojang's version JSON could not be read: " + unreadable.getMessage(), unreadable);
        }
    }

    // The keys read from Mojang's JSON; the rest are ignored
    private record Listed(String id, String url) {
    }

    private record Manifest(List<Listed> versions) {
    }

    private record Download(String url, String sha1) {
    }

    private record Version(Map<String, Download> downloads) {
    }

    private static final CgCodec<Listed> LISTED = CgCodecs.record(
            CgCodecs.field("id", CgCodecs.STRING, Listed::id),
            CgCodecs.field("url", CgCodecs.STRING, Listed::url), Listed::new);
    private static final CgCodec<Manifest> MANIFEST = CgCodecs.record(
            CgCodecs.field("versions", CgCodecs.listOf(LISTED), Manifest::versions), Manifest::new);
    private static final CgCodec<Download> DOWNLOAD = CgCodecs.record(
            CgCodecs.field("url", CgCodecs.STRING, Download::url),
            CgCodecs.optionalField("sha1", CgCodecs.STRING, Download::sha1, null), Download::new);
    private static final CgCodec<Version> VERSION = CgCodecs.record(
            CgCodecs.fieldOr("downloads", CgCodecs.mapOf(CgCodecs.STRING, DOWNLOAD), Version::downloads,
                    Collections.emptyMap()), Version::new);
}
