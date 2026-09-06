package com.crystalgui.template;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.Attribute;
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

    /**
     * The document a {@code Networked} panel is laid out by.
     *
     * <pre>{@code
     * @UiTemplate.Source("mymod:ui/status")
     * public final class StatusPanel extends UIElement implements Networked<StatusModel> {
     *     @Bound UIText title;
     *     public void build(StatusModel model) { }    // the template is the layout
     * }
     * }</pre>
     *
     * <p>{@code UiType.build} inflates it into the panel and fills the {@link Bound} fields before
     * {@code build} runs, so the tree exists by the time the panel is asked to arrange anything.</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Source {

        /** The document id — {@code namespace:path}, without the extension. */
        String value();
    }

    private final String origin;
    private final int formatVersion;
    private final List<String> stylesheets;

    @Nullable
    private final String modelClass;

    @Nullable
    private final String packageName;

    @Nullable
    private final Name kindName;

    private final Map<String, TemplateParams.Param> params;

    @Nullable
    private final JsonObject preview;

    private final JsonObject root;

    @Nullable
    private String contentHash;

    UiTemplate(String origin, int formatVersion, List<String> stylesheets, @Nullable String modelClass,
            @Nullable String packageName, @Nullable Name kindName,
            Map<String, TemplateParams.Param> params, @Nullable JsonObject preview, JsonObject root) {
        this.origin = origin;
        this.formatVersion = formatVersion;
        this.stylesheets = List.copyOf(stylesheets);
        this.modelClass = modelClass;
        this.packageName = packageName;
        this.kindName = kindName;
        this.params = Map.copyOf(params);
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

    /**
     * What this document takes from whoever places it — {@code $name} in any value.
     *
     * <pre>{@code
     * "params": { "title": { "type": "string", "default": "Untitled" } }
     * }</pre>
     */
    public Map<String, TemplateParams.Param> params() {
        return params;
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
        return inflate(Map.of(), Map.of());
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
        return inflate(Map.of(), overridesById);
    }

    /**
     * A fresh tree with parameters filled in and per-id state applied.
     *
     * @param parameters      values for what {@link #params} declares; a declared default fills the rest
     * @param overridesById   element id to state key to value; an id the tree has not got is refused
     */
    public UIElement inflate(Map<String, Object> parameters,
            Map<String, Map<String, Object>> overridesById) {
        JsonObject filled = TemplateParams.substitute(this, root, parameters);
        DocumentExtras<JsonElement> extras = new DocumentExtras<>();
        UIElement tree;
        try {
            // WITH a table, and then thrown away: params and overrides on an instance node are the
            // loader's, and design, bind and on are stripped by simply not being applied.
            tree = mirror().decode(filled, extras);
        } catch (UiTemplateException already) {
            throw already;
        } catch (RuntimeException refused) {
            throw new UiTemplateException(origin, null, refused.getMessage(), refused);
        }
        configureInstances(tree, extras);
        TemplateOverrides.apply(this, tree, overridesById);
        return tree;
    }

    /** Hands each placed instance the {@code params} and {@code overrides} the document wrote for it. */
    private void configureInstances(UIElement node, DocumentExtras<JsonElement> extras) {
        if (node instanceof TemplateInstance instance) {
            instance.configureFrom(extras.get(node, DocumentExtras.PARAMS),
                    extras.get(node, DocumentExtras.OVERRIDES));
            checkSlots(instance);
        }
        for (UIElement child : node.children()) configureInstances(child, extras);
    }

    /** A slotted child with nowhere to land is in no composed tree at all, and says nothing about it. */
    private void checkSlots(TemplateInstance instance) {
        List<String> offered = instance.slotNames();
        for (UIElement child : instance.children()) {
            String wanted = child.get(Attribute.SLOT);
            if (wanted == null || wanted.isEmpty() ? offered.contains("") : offered.contains(wanted)) {
                continue;
            }
            throw new UiTemplateException(origin, null, "<" + child.tagName() + "> asks for the slot \""
                    + wanted + "\", and " + instance.templateId() + " offers " + offered);
        }
    }

    /**
     * Inflates into {@code owner} and fills its {@link Bound} fields by id.
     *
     * <pre>{@code
     * UiTemplates.load("mymod:ui/status").inflateInto(panel);
     * }</pre>
     *
     * @return the root that was appended
     */
    public UIElement inflateInto(UIElement owner) {
        UIElement root = inflate();
        owner.append(root);
        TemplateBinder.bind(owner, owner);
        return root;
    }

    /**
     * Fills the tree the builder edits, collecting {@code design}, {@code bind} and {@code on} into
     * {@code extras} instead of dropping them.
     */
    public UIElement inflateForEditing(DocumentExtras<JsonElement> extras) {
        return mirror().decode(root, extras);
    }

    /** Refuses anything the document says that could not be built. Run once, at parse. */
    void validate() {
        TemplateValidation.check(this);
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
