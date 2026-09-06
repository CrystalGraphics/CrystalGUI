package com.crystalgui.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.crystalgraphics.util.io.CgIO;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * Where {@code .cgui} documents are loaded from, and cached.
 *
 * <pre>{@code
 * UiTemplate status = UiTemplates.load("mymod:ui/status");     // assets/mymod/ui/status.cgui
 * UIElement tree = status.inflate();
 * }</pre>
 *
 * <p>An id is {@code namespace:path}, read through {@code CgIO} as
 * {@code /assets/<namespace>/<path>.cgui} — so an override directory, a resource pack and the classpath
 * are all tried, in that order. Parsed once and cached; {@link #reloadAll} drops the cache, which is
 * what a resource reload and F3+T call.</p>
 *
 * <p><b>{@link #parse} is the headless half</b> and does no I/O: a dedicated server inflating a template
 * into a panel, and the workbench editing a workspace file, both already have the bytes.</p>
 */
public final class UiTemplates {

    private UiTemplates() {
    }

    private static final Map<String, UiTemplate> CACHE = new ConcurrentHashMap<>();

    /**
     * The document under {@code assetId}, parsed once.
     *
     * @param assetId {@code namespace:path}, without the {@code .cgui} extension
     * @throws UiTemplateException if there is no such document, or it will not load
     */
    public static UiTemplate load(String assetId) {
        UiTemplate cached = CACHE.get(assetId);
        if (cached != null) return cached;
        UiTemplate parsed = parse(read(assetId), assetId);
        // putIfAbsent, not put: two threads racing on one id must agree on the instance, since a
        // registered kind's factory closes over whichever they got.
        UiTemplate raced = CACHE.putIfAbsent(assetId, parsed);
        return raced != null ? raced : parsed;
    }

    /** The one already loaded under {@code assetId}, or null — no I/O. */
    @Nullable
    public static UiTemplate loaded(String assetId) {
        return CACHE.get(assetId);
    }

    /**
     * Parses {@code json} as a document.
     *
     * @param origin what to name in a refusal — an asset id, or a workspace path
     */
    public static UiTemplate parse(String json, String origin) {
        JsonObject header;
        try {
            // new JsonParser().parse, NOT the static parseString: 1.7.10 ships gson 2.2.4,
            // where the static one does not exist. core/build.gradle.kts records the trap.
            JsonElement parsed = new JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                throw new UiTemplateException(origin, null, "a document is a JSON object");
            }
            header = parsed.getAsJsonObject();
        } catch (JsonParseException malformed) {
            throw new UiTemplateException(origin, null, "this is not JSON", malformed);
        }
        return parse(header, origin);
    }

    /** As {@link #parse(String, String)}, for a caller that already has the tree. */
    public static UiTemplate parse(JsonObject document, String origin) {
        int format = intOr(document, "cgui", 0);
        if (format <= 0) {
            throw new UiTemplateException(origin, null,
                    "a document declares its format with \"cgui\": " + UiTemplate.FORMAT);
        }
        if (format > UiTemplate.FORMAT) {
            // REFUSED, never read on a best effort. A newer document may place kinds and keys this
            // reader would drop, and a tree silently missing half of itself is worse than not opening.
            throw new UiTemplateException(origin, null, "this document is format " + format
                    + " and this build reads " + UiTemplate.FORMAT + " -- update the mod");
        }

        JsonElement root = document.get("root");
        if (root == null || !root.isJsonObject()) {
            throw new UiTemplateException(origin, null, "a document has exactly one \"root\" node");
        }

        Name kindName = null;
        JsonElement declaredKind = document.get("kind-name");
        if (declaredKind != null && declaredKind.isJsonPrimitive()) {
            try {
                kindName = Name.parse(declaredKind.getAsString());
            } catch (RuntimeException bad) {
                throw new UiTemplateException(origin, null,
                        "\"kind-name\" is not a name: " + declaredKind.getAsString(), bad);
            }
        }

        UiTemplate template = new UiTemplate(origin, format, strings(document, "stylesheets"),
                stringOr(document, "model"), stringOr(document, "package"), kindName,
                TemplateParams.declare(document, origin), objectOr(document, "preview"),
                root.getAsJsonObject());
        template.validate();
        return template;
    }

    /**
     * Forgets every parsed document, so the next load reads the file again.
     *
     * <p>What a resource reload calls. Trees already inflated are unaffected — they are ordinary
     * elements and nothing here holds one.</p>
     */
    public static void reloadAll() {
        CACHE.clear();
    }

    /** Drops one document. */
    public static void reload(String assetId) {
        CACHE.remove(assetId);
    }

    /** Puts an already-parsed document in the cache under {@code assetId} — the builder's live copy. */
    public static void install(String assetId, UiTemplate template) {
        CACHE.put(assetId, template);
    }

    /**
     * Registers a document that declares {@code kind-name} as an element kind, so other documents place
     * it by tag and a sheet styles it — Unity's Project tab, and it needs no Java.
     *
     * <pre>{@code
     * UiTemplates.register(UiTemplates.load("mymod:ui/parts/plate"));   // "kind-name": "mymod:plate"
     * }</pre>
     *
     * @return whether it registered; a document with no {@code kind-name} declares no kind
     */
    public static boolean register(UiTemplate template) {
        Name kind = template.kindName();
        if (kind == null) return false;
        UIElementRegistry.register(kind, () -> new TemplateInstance(template.origin()),
                NodeContract.INERT);
        return true;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** {@code mymod:ui/status} to {@code /assets/mymod/ui/status.cgui}. */
    public static String pathOf(String assetId) {
        int colon = assetId.indexOf(':');
        if (colon <= 0 || colon == assetId.length() - 1) {
            throw new UiTemplateException(assetId, null,
                    "a document id is namespace:path, as in mymod:ui/status");
        }
        return "/assets/" + assetId.substring(0, colon) + "/" + assetId.substring(colon + 1) + ".cgui";
    }

    private static String read(String assetId) {
        String path = pathOf(assetId);
        // CgIO, like every other asset this project reads: it answers an override directory first, then
        // the resource manager, then the classpath -- so a resource pack can ship a document and a dev
        // run can point at a source tree, neither of which a raw classloader read would see.
        String source = CgIO.loadSource(path);
        if (source == null) throw new UiTemplateException(assetId, null, "no such document at " + path);
        return source;
    }

    // ── Header helpers ──────────────────────────────────────────────────────

    private static int intOr(JsonObject document, String key, int fallback) {
        JsonElement value = document.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    @Nullable
    private static String stringOr(JsonObject document, String key) {
        JsonElement value = document.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    @Nullable
    private static JsonObject objectOr(JsonObject document, String key) {
        JsonElement value = document.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static List<String> strings(JsonObject document, String key) {
        JsonElement value = document.get(key);
        if (value == null || !value.isJsonArray()) return List.of();
        JsonArray array = value.getAsJsonArray();
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement each : array) if (each.isJsonPrimitive()) out.add(each.getAsString());
        return out;
    }
}
