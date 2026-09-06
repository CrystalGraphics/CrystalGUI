package com.crystalgui.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * A {@code .cgui} document loaded for use at runtime: the tree it describes, the sheets it names and
 * what it declares about itself.
 *
 * <p>Get one from {@link UiTemplates#load}; call {@link #inflate} for a fresh tree. Nothing here needs a
 * display — a dedicated server may inflate a template into a panel it is about to describe, which is why
 * sheets are held as <b>ids</b> and installed separately by whoever has a window.</p>
 *
 * <pre>{@code
 * UiTemplate plate = UiTemplates.load("mymod:ui/parts/plate");
 * UIElement root = plate.inflate();                       // design values stripped
 * window.append(root);
 * plate.installSheets(window);                            // client side only
 * }</pre>
 *
 * <p>Inflating twice gives two independent trees; a template holds no element of its own. Design-time
 * values, {@code bind} and {@code on} are document data and never reach an inflated tree — the builder
 * reads them from {@link #root()} instead.</p>
 */
public final class UiTemplate {

    /** The format major this reader understands. A document declaring a higher one is refused. */
    public static final int FORMAT = 1;

    private final String origin;
    private final int formatVersion;
    private final List<String> stylesheets;

    @Nullable
    private final String modelClass;

    @Nullable
    private final String packageName;

    @Nullable
    private final Name kindName;

    @Nullable
    private final JsonObject preview;

    private final JsonObject root;

    @Nullable
    private String contentHash;

    UiTemplate(String origin, int formatVersion, List<String> stylesheets, @Nullable String modelClass,
            @Nullable String packageName, @Nullable Name kindName, @Nullable JsonObject preview,
            JsonObject root) {
        this.origin = origin;
        this.formatVersion = formatVersion;
        this.stylesheets = List.copyOf(stylesheets);
        this.modelClass = modelClass;
        this.packageName = packageName;
        this.kindName = kindName;
        this.preview = preview;
        this.root = root;
    }

    // ── What it says about itself ────────────────────────────────────────────

    /** Where it came from — an asset id like {@code mymod:ui/status}, or a workspace path. */
    public String origin() {
        return origin;
    }

    public int formatVersion() {
        return formatVersion;
    }

    /** Sheet ids in cascade order. Ids, never parsed sheets. @see #installSheets */
    public List<String> stylesheets() {
        return stylesheets;
    }

    /** The class a generated binding would be written against, or null. Read by the exporter. */
    @Nullable
    public String modelClass() {
        return modelClass;
    }

    /** The package a generated class goes in, or null. */
    @Nullable
    public String packageName() {
        return packageName;
    }

    /** The kind this document registers itself as, or null. @see UiTemplates#register */
    @Nullable
    public Name kindName() {
        return kindName;
    }

    /** Canvas sizes, {@code uiScale}, theme, locale — the builder's, ignored at runtime. */
    @Nullable
    public JsonObject preview() {
        return preview;
    }

    /** The root node, in the document dialect. What the builder edits and the exporter reads. */
    public JsonObject root() {
        return root;
    }

    /**
     * The hash of the tree this describes, over the wire dialect.
     *
     * <p>The same string {@code ServerWindows} sends for a window built from this template, so a client
     * holding the document already has the description. Computed once, on first ask.</p>
     */
    public String contentHash() {
        if (contentHash == null) {
            UIElement tree = inflate();
            contentHash = ContentHash.of(JsonOps.INSTANCE,
                    new UIElementMirror<JsonElement>(JsonOps.INSTANCE).describe(tree));
        }
        return contentHash;
    }

    // ── Building ────────────────────────────────────────────────────────────

    /**
     * A fresh tree, with {@code design}, {@code bind} and {@code on} stripped.
     *
     * <p>Headless: registry lookups, contract reads and appends, exactly what a server does when it
     * builds a panel by hand.</p>
     */
    public UIElement inflate() {
        return mirror().decode(root, null);
    }

    /**
     * A fresh tree with per-id state applied after inflation — an instance's overrides.
     *
     * <pre>{@code
     * plate.inflate(Map.of("title", Map.of("text", "Status")));
     * }</pre>
     *
     * @param overridesById element id to state key to value; an id the tree has not got is refused
     */
    public UIElement inflate(Map<String, Map<String, Object>> overridesById) {
        UIElement tree = inflate();
        TemplateOverrides.apply(this, tree, overridesById);
        return tree;
    }

    /**
     * Fills the tree the builder edits, collecting {@code design}, {@code bind} and {@code on} into
     * {@code extras} instead of dropping them.
     */
    public UIElement inflateForEditing(DocumentExtras<JsonElement> extras) {
        return mirror().decode(root, extras);
    }

    /**
     * Adds this document's sheets to {@code window}, in order, skipping any already there.
     *
     * <p><b>Client side only.</b> Resolving a sheet id reads a file through CrystalGraphics, which a
     * dedicated server has not got — which is why a template holds ids and this is a separate call
     * rather than something {@link #inflate} does.</p>
     */
    public void installSheets(UIDocument window) {
        if (window == null) return;
        for (String id : stylesheets) TemplateSheets.install(window, id, null);
    }

    private static UIElementMirror<JsonElement> mirror() {
        return new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);
    }

    /** Every node in the document, root first, paired with its path — what validation walks. */
    List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        collect(root, "root", out);
        return out;
    }

    private static void collect(JsonObject node, String path, List<Node> out) {
        out.add(new Node(node, path));
        JsonElement children = node.get(UIElementMirror.Keys.DOCUMENT.children());
        if (children == null || !children.isJsonArray()) return;
        JsonArray array = children.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement child = array.get(i);
            if (child.isJsonObject()) collect(child.getAsJsonObject(), path + ".children[" + i + "]", out);
        }
    }

    /** One described node and where it sits, so a refusal can say which one. */
    record Node(JsonObject json, String path) {
    }

    @Override
    public String toString() {
        return "UiTemplate[" + origin + "]";
    }
}
