package com.crystalgui.core.cache;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Where every runtime download can be found</b>: {@code download/locations.json} at the root of the
 * repository. The jar ships a copy and re-reads the one on master, so a dead link is fixed for every
 * released jar by editing that one file. {@code download/README.md} is the guide to editing it.
 *
 * <pre>{@code
 * // the usual way, through Downloads: every URL the file lists, checked against the jar's pin
 * Downloads.located("fabric/intermediary/1.20.1").into(target);
 *
 * DownloadLocations.Location where = DownloadLocations.get().find("jdk-sources/17");
 * where.urls();     // the override file's, then master's, then the jar's own
 * where.digest();   // the jar's pin, or null for an artifact nobody can pin
 * }</pre>
 *
 * <p>The file names each download by an id, and gives its URLs, or its Maven coordinates and the named
 * repositories to find them in:</p>
 *
 * <pre>{@code
 * "repositories": { "fabric": ["https://maven.fabricmc.net/{path}"],
 *                   "mirror": ["https://github.com/CrystalGraphics/CrystalGUI/releases/download/download-mirror/{file}"] },
 * "files": {
 *   "mojang/version-manifest": { "urls": ["https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"] },
 *   "fabric/intermediary/1.20.1": { "maven": "net.fabricmc:intermediary:1.20.1:v2",
 *                                   "from": ["fabric", "mirror"], "digest": "sha1:20ba…" }
 * },
 * "engines": { "from": ["maven-central", "mirror"],
 *              "bands": { "8": { "org.benf:cfr:0.152": "sha1:48ef…" } } }   // id engine/8/cfr-0.152.jar
 * }</pre>
 *
 * <p>Three copies of the one file, merged per id, their repositories merged by name:</p>
 * <ul>
 *   <li><b>The jar's</b>: the ids it knows and their digests. The only copy that pins anything.</li>
 *   <li><b>Master's</b>: fetched before a session's first download when the copy on disk is a day old or
 *       missing, and again when every known URL has failed. It adds URLs, and addresses for a repository,
 *       for ids the jar lists; its digests and any id the jar lacks are ignored, so it can move an
 *       artifact but never change what a jar accepts.</li>
 *   <li><b>An override file</b> named by {@code -Dcrystalgui.download.locations}, for a pack's own mirror
 *       or an offline machine. The same rule, tried first.</li>
 * </ul>
 *
 * <ul>
 *   <li>{@link #find} answers null for an id the jar does not list. A caller reports that as not
 *       configured; it never guesses a URL.</li>
 *   <li>A copy that is not JSON, or declares a {@code format} other than {@value #FORMAT}, is ignored
 *       whole: an old jar keeps what it shipped with rather than misread a newer layout.</li>
 *   <li>A host calls {@link #useCacheRoot} at startup; without it master's copy is fetched every session.</li>
 *   <li>{@code -Dcrystalgui.download.remote=false} never reads master's copy. The tests run that way.</li>
 * </ul>
 */
public final class DownloadLocations {

    /** {@code false} never reads master's copy of the file. */
    public static final String REMOTE_PROPERTY = "crystalgui.download.remote";

    /** A file of further locations, tried before any other: a pack's own mirror, an offline machine. */
    public static final String OVERRIDE_PROPERTY = "crystalgui.download.locations";

    /** Where the jar carries its copy. */
    public static final String RESOURCE = "/assets/crystalgui/download/locations.json";

    /** The layout this jar reads. */
    static final int FORMAT = 1;

    /** How old master's copy on disk may be before a download fetches it again. */
    static final long STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000;

    /** More than this is not the file but something else that answered. */
    private static final int MAX_BYTES = 1 << 20;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)\\}");

    /** One artifact: the jar's pin, null when nothing can be pinned, and every URL, best first. */
    public record Location(String id, @Nullable String digest, List<String> urls) {
    }

    /** One way to an artifact: a URL, or Maven coordinates looked up in a named repository. */
    private record Address(@Nullable String url, @Nullable String repository, @Nullable String coordinates) {
    }

    /** What one copy says about one id. */
    private record Entry(String id, @Nullable String digest, List<Address> addresses) {

        boolean isTemplate() {
            return id.indexOf('{') >= 0;
        }
    }

    /** One copy of the file: where it lives, each repository's addresses, and its entries. */
    private record Copy(List<String> self, Map<String, List<String>> repositories, List<Entry> entries) {
    }

    private static final Copy EMPTY = new Copy(Collections.<String>emptyList(),
            Collections.<String, List<String>>emptyMap(), Collections.<Entry>emptyList());

    /** An id resolved against one entry: the entry, and what each placeholder stands for. */
    private record Match(Entry entry, Map<String, String> values) {
    }

    private static volatile DownloadLocations shared;

    /** Where this installation keeps its cache; until a host says, master's copy is held in memory only. */
    private static volatile Path cacheRoot;

    private final Copy bundled;
    private final Copy override;
    private final Supplier<Path> remoteFile;
    private final boolean remoteAllowed;
    private final AtomicBoolean fetched = new AtomicBoolean();
    private volatile Copy remote;

    private DownloadLocations(Copy bundled, Copy override, Supplier<Path> remoteFile, boolean remoteAllowed) {
        this.bundled = bundled;
        this.override = override;
        this.remoteFile = remoteFile;
        this.remoteAllowed = remoteAllowed;
    }

    /** The process's locations: the jar's copy, the override file, and master's under the cache root. */
    public static DownloadLocations get() {
        DownloadLocations local = shared;
        if (local == null) {
            synchronized (DownloadLocations.class) {
                local = shared;
                if (local == null) {
                    shared = local = new DownloadLocations(readable(resourceText(), RESOURCE),
                            readable(overrideText(), OVERRIDE_PROPERTY), DownloadLocations::underCacheRoot,
                            !"false".equalsIgnoreCase(System.getProperty(REMOTE_PROPERTY)));
                }
            }
        }
        return local;
    }

    /**
     * Where this installation keeps its cache, so master's copy of the file survives a restart.
     *
     * <pre>{@code
     * DownloadLocations.useCacheRoot(StorageLayout.cacheIn(gameDirectory));   // a host, at startup
     * }</pre>
     *
     * <p>Until one is given, master's copy is still fetched and used, and kept in memory only. A null root
     * changes nothing, so a caller that does not know one need not check.</p>
     */
    public static void useCacheRoot(@Nullable Path root) {
        if (root != null) cacheRoot = root;
    }

    /**
     * These copies of the file, with master's kept at {@code remoteFile}: for tests and tools.
     *
     * <pre>{@code
     * DownloadLocations locations = DownloadLocations.of(jarJson, null, temp.resolve("master.json"), true);
     * }</pre>
     */
    public static DownloadLocations of(String bundled, @Nullable String override, @Nullable Path remoteFile,
                                       boolean remoteAllowed) {
        return new DownloadLocations(readable(bundled, "the jar's copy"),
                readable(override, OVERRIDE_PROPERTY), () -> remoteFile, remoteAllowed);
    }

    /** Where {@code id} can be had, or null when the jar's copy does not list it. */
    public @Nullable Location find(String id) {
        Match known = match(bundled.entries(), id);
        if (known == null) return null;
        List<Copy> copies = copies();
        Map<String, List<String>> repositories = repositoriesOf(copies);
        Set<String> urls = new LinkedHashSet<>();
        for (Copy copy : copies) {
            for (Entry entry : copy.entries()) {
                // Its entry for this id, or for the template the id fits.
                if (entry.id().equals(id) || entry.id().equals(known.entry().id())) {
                    urls.addAll(expand(entry, known.values(), repositories));
                }
            }
        }
        String digest = known.entry().isTemplate() ? null : known.entry().digest();
        return new Location(id, digest, Collections.unmodifiableList(new ArrayList<>(urls)));
    }

    /** Whether the jar's copy lists {@code id}. Reads nothing from disk or the network. */
    public boolean lists(String id) {
        return match(bundled.entries(), id) != null;
    }

    /** The jar's pin for {@code id}, or null. Reads nothing from disk or the network. */
    public @Nullable String pinOf(String id) {
        Match known = match(bundled.entries(), id);
        return known == null || known.entry().isTemplate() ? null : known.entry().digest();
    }

    /** Every id the jar lists under {@code prefix}, sorted: the jars an engine band is made of. */
    public List<String> idsUnder(String prefix) {
        List<String> ids = new ArrayList<>();
        for (Entry entry : bundled.entries()) {
            if (!entry.isTemplate() && entry.id().startsWith(prefix)) ids.add(entry.id());
        }
        Collections.sort(ids);
        return ids;
    }

    /** Fetches master's copy before a download when the one on disk is a day old or missing. */
    public void refreshIfStale() {
        if (!remoteAllowed || fetched.get()) return;
        Path file = remoteFile.get();
        try {
            if (file != null && Files.isRegularFile(file)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < STALE_AFTER_MILLIS) {
                return;
            }
        } catch (IOException unreadable) {
            // An age that cannot be read is fetched again rather than trusted.
        }
        refresh();
    }

    /**
     * Fetches master's copy, at most once per session. A caller arriving while it is being fetched waits
     * for it, then {@link #find} answers with whatever it added.
     *
     * @return whether this call fetched a copy
     */
    public synchronized boolean refresh() {
        if (!remoteAllowed || !fetched.compareAndSet(false, true)) return false;
        for (String url : selfUrls()) {
            try (Downloads.Body body = Downloads.fetch(url)) {
                byte[] bytes = readBounded(body.stream());
                Copy copy = parse(new String(bytes, StandardCharsets.UTF_8));
                // The file always names itself. An error page that answered 200 does not parse as one.
                if (copy == null || copy.self().isEmpty()) continue;
                remote = copy;
                Path file = remoteFile.get();
                if (file != null) CacheFiles.install(file, new ByteArrayInputStream(bytes), null);
                return true;
            } catch (IOException | RuntimeException unreachable) {
                // The file's next address, or the copy already held.
            }
        }
        return false;
    }

    /** Where the file itself lives: the override's word first, then master's, then the jar's. */
    private List<String> selfUrls() {
        Set<String> urls = new LinkedHashSet<>();
        for (Copy copy : copies()) urls.addAll(copy.self());
        return new ArrayList<>(urls);
    }

    /** The override, master's when it may be read, then the jar's: the order URLs are tried in. */
    private List<Copy> copies() {
        List<Copy> copies = new ArrayList<>(3);
        copies.add(override);
        if (remoteAllowed) copies.add(remote());
        copies.add(bundled);
        return copies;
    }

    private Copy remote() {
        Copy local = remote;
        if (local == null) {
            local = EMPTY;
            Path file = remoteFile.get();
            if (file != null && Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    Copy read = parse(new String(readBounded(in), StandardCharsets.UTF_8));
                    if (read != null) local = read;
                } catch (IOException unreadable) {
                    // Treated as absent; the next refresh replaces it.
                }
            }
            remote = local;
        }
        return local;
    }

    /** Every copy's addresses for each repository, merged by name in the order the copies are tried. */
    private static Map<String, List<String>> repositoriesOf(List<Copy> copies) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Copy copy : copies) {
            for (Map.Entry<String, List<String>> repository : copy.repositories().entrySet()) {
                List<String> layouts = merged.computeIfAbsent(repository.getKey(), name -> new ArrayList<>());
                for (String layout : repository.getValue()) {
                    if (!layouts.contains(layout)) layouts.add(layout);
                }
            }
        }
        return merged;
    }

    /** An entry's addresses as URLs: its own, then each repository it names, with {@code values} filled in. */
    private static List<String> expand(Entry entry, Map<String, String> values,
                                       Map<String, List<String>> repositories) {
        List<String> urls = new ArrayList<>();
        for (Address address : entry.addresses()) {
            if (address.url() != null) {
                urls.add(fill(address.url(), values));
                continue;
            }
            String coordinates = fill(address.coordinates(), values);
            List<String> layouts = repositories.get(address.repository());
            if (layouts == null || !isCoordinates(coordinates)) continue;
            for (String layout : layouts) {
                urls.add(layout.replace("{path}", mavenPath(coordinates)).replace("{file}", mavenFile(coordinates)));
            }
        }
        return urls;
    }

    /** The exact entry for {@code id}, else the first template it fits. */
    private static @Nullable Match match(List<Entry> entries, String id) {
        for (Entry entry : entries) {
            if (!entry.isTemplate() && entry.id().equals(id)) return new Match(entry, Collections.emptyMap());
        }
        for (Entry entry : entries) {
            if (!entry.isTemplate()) continue;
            Map<String, String> values = bind(entry.id(), id);
            if (values != null) return new Match(entry, values);
        }
        return null;
    }

    /** What each placeholder of {@code template} stands for in {@code id}, or null when it does not fit. */
    private static @Nullable Map<String, String> bind(String template, String id) {
        StringBuilder regex = new StringBuilder();
        List<String> names = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(template);
        int at = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(template.substring(at, placeholder.start())));
            // One path segment: a placeholder never swallows a slash.
            regex.append("([^/]+)");
            names.add(placeholder.group(1));
            at = placeholder.end();
        }
        regex.append(Pattern.quote(template.substring(at)));
        Matcher fitted = Pattern.compile(regex.toString()).matcher(id);
        if (!fitted.matches()) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (int group = 0; group < names.size(); group++) values.put(names.get(group), fitted.group(group + 1));
        return values;
    }

    private static String fill(String text, Map<String, String> values) {
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("{" + value.getKey() + "}", value.getValue());
        }
        return filled;
    }

    /**
     * The file Maven stores {@code coordinates} as: {@code org.benf:cfr:0.152} is {@code cfr-0.152.jar},
     * {@code net.fabricmc:intermediary:1.20.1:v2} is {@code intermediary-1.20.1-v2.jar}, and an
     * {@code @zip} suffix names the extension.
     */
    static String mavenFile(String coordinates) {
        String[] parts = partsOf(coordinates);
        return parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + extensionOf(coordinates);
    }

    /** Where a Maven repository keeps it: {@code org/benf/cfr/0.152/cfr-0.152.jar}. */
    static String mavenPath(String coordinates) {
        String[] parts = partsOf(coordinates);
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + mavenFile(coordinates);
    }

    /** {@code group:artifact:version}, then optionally {@code :classifier} and {@code @extension}. */
    private static boolean isCoordinates(String coordinates) {
        String[] parts = partsOf(coordinates);
        if (parts.length < 3 || parts.length > 4) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
        }
        return !extensionOf(coordinates).isEmpty();
    }

    private static String[] partsOf(String coordinates) {
        int at = coordinates.indexOf('@');
        return (at < 0 ? coordinates : coordinates.substring(0, at)).split(":", -1);
    }

    private static String extensionOf(String coordinates) {
        int at = coordinates.indexOf('@');
        return at < 0 ? "jar" : coordinates.substring(at + 1);
    }

    /** A copy somebody meant to be read: the jar's, or an override. Said once when it cannot be. */
    private static Copy readable(@Nullable String text, String what) {
        if (text == null) return EMPTY;
        Copy copy = parse(text);
        if (copy == null) {
            System.err.println("[crystalgui] " + what + " is not a locations file this jar can read; ignoring it");
            return EMPTY;
        }
        return copy;
    }

    /** One copy, or null when it is not JSON, not an object, or in a format this jar does not read. */
    private static @Nullable Copy parse(String text) {
        try {
            JsonElement root = new JsonParser().parse(text);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject file = root.getAsJsonObject();
            if (file.has("format") && file.get("format").getAsInt() != FORMAT) return null;

            Map<String, List<String>> repositories = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> repository : membersOf(file, "repositories")) {
                repositories.put(repository.getKey(), stringsOf(repository.getValue()));
            }

            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<String, JsonElement> member : membersOf(file, "files")) {
                if (!member.getValue().isJsonObject()) continue;
                JsonObject spec = member.getValue().getAsJsonObject();
                List<Address> addresses = new ArrayList<>();
                for (String url : stringsOf(spec.get("urls"))) addresses.add(new Address(url, null, null));
                String maven = stringOf(spec.get("maven"));
                if (maven != null && isCoordinates(maven)) {
                    for (String repository : stringsOf(spec.get("from"))) {
                        addresses.add(new Address(null, repository, maven));
                    }
                }
                entries.add(new Entry(member.getKey(), stringOf(spec.get("digest")), addresses));
            }

            JsonObject engines = objectOf(file, "engines");
            if (engines != null) {
                List<String> from = stringsOf(engines.get("from"));
                for (Map.Entry<String, JsonElement> band : membersOf(engines, "bands")) {
                    if (!band.getValue().isJsonObject()) continue;
                    for (Map.Entry<String, JsonElement> jar : band.getValue().getAsJsonObject().entrySet()) {
                        String coordinates = jar.getKey();
                        if (!isCoordinates(coordinates)) continue;
                        List<Address> addresses = new ArrayList<>();
                        for (String repository : from) addresses.add(new Address(null, repository, coordinates));
                        entries.add(new Entry("engine/" + band.getKey() + "/" + mavenFile(coordinates),
                                stringOf(jar.getValue()), addresses));
                    }
                }
            }
            return new Copy(stringsOf(file.get("self")), repositories, entries);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Set<Map.Entry<String, JsonElement>> membersOf(JsonObject parent, String key) {
        JsonObject child = objectOf(parent, key);
        return child == null ? Collections.<Map.Entry<String, JsonElement>>emptySet() : child.entrySet();
    }

    private static @Nullable JsonObject objectOf(JsonObject parent, String key) {
        JsonElement child = parent.get(key);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }

    private static @Nullable String stringOf(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** A string or an array of them, as a list; anything else is none. */
    private static List<String> stringsOf(@Nullable JsonElement element) {
        List<String> strings = new ArrayList<>();
        if (element == null) return strings;
        if (element.isJsonPrimitive()) {
            strings.add(element.getAsString());
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) strings.add(item.getAsString());
            }
        }
        return strings;
    }

    private static @Nullable String resourceText() {
        try (InputStream in = DownloadLocations.class.getResourceAsStream(RESOURCE)) {
            return in == null ? null : new String(readBounded(in), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static @Nullable String overrideText() {
        String path = System.getProperty(OVERRIDE_PROPERTY);
        if (path == null || path.trim().isEmpty()) return null;
        try (InputStream in = Files.newInputStream(Paths.get(path.trim()))) {
            return new String(readBounded(in), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[crystalgui] " + OVERRIDE_PROPERTY + " could not be read (" + unreadable + ")");
            return null;
        }
    }

    private static @Nullable Path underCacheRoot() {
        Path root = cacheRoot;
        return root == null ? null : root.resolve("download").resolve("locations.json");
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) throw new IOException("larger than the locations file ever is");
        }
        return out.toByteArray();
    }
}
