package com.crystalgui.app.uibuilder.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.crystalgui.document.AbstractDocumentModel;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.template.UiTemplate;
import com.crystalgui.template.UiTemplates;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.ui.dom.UIElement;

/**
 * A {@code .cgui} open for editing: the live tree, the header it came with, and one undo history over
 * both.
 *
 * <pre>{@code
 * UiBuilderDocument document = new UiBuilderDocument(bytes, "mymod.proj:ui/status.cgui");
 * document.apply(new BuilderEdit.SetId(node, "title"));
 * byte[] saved = document.encode();
 * }</pre>
 *
 * <p>The tree is the truth and the file is written from it, through the same codec the wire uses — so
 * what the builder saves is a description, and a window built from it hashes to the file. Design values,
 * bindings and hooks live in {@link #extras} beside the tree, because a node cannot hold them.</p>
 *
 * <p>Everything that changes anything goes through {@link #apply}: the tree is never edited directly, or
 * the change is not in the history and the file and the canvas disagree after one undo.</p>
 */
public final class UiBuilderDocument extends AbstractDocumentModel {

    private static final UIElementMirror<JsonElement> MIRROR =
            new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);

    /** The key the tree is written under, and the one header key this class owns. */
    private static final String ROOT = "root";

    private final String origin;

    private JsonObject header = new JsonObject();

    private UIElement root = new UIElement();

    private final DocumentExtras<JsonElement> extras = new DocumentExtras<>();

    private final DiagnosticSet problems = new DiagnosticSet();

    public UiBuilderDocument(byte[] bytes, String origin) {
        this.origin = origin;
        adopt(bytes);
    }

    /** What the document is called in a refusal — a workspace path, or an asset id. */
    public String origin() {
        return origin;
    }

    /** The live tree. Read it freely; change it only through {@link #apply}. */
    public UIElement root() {
        return root;
    }

    /** Design values, bindings and hooks, keyed by node. */
    public DocumentExtras<JsonElement> extras() {
        return extras;
    }

    /** The header as it will be written — sheets, model, package, preview, params. */
    public JsonObject header() {
        return header;
    }

    /** Sheet ids in cascade order, for whoever has a window to install them on. */
    public List<String> stylesheets() {
        List<String> sheets = new ArrayList<>();
        JsonElement declared = header.get("stylesheets");
        if (declared != null && declared.isJsonArray()) {
            for (JsonElement each : declared.getAsJsonArray()) {
                if (each.isJsonPrimitive()) sheets.add(each.getAsString());
            }
        }
        return sheets;
    }

    /** The hash a window built from this tree would send. @see UiTemplate#contentHash() */
    public String contentHash() {
        return ContentHash.of(JsonOps.INSTANCE,
                new UIElementMirror<JsonElement>(JsonOps.INSTANCE).describe(root));
    }

    // ── The one door ────────────────────────────────────────────────────────

    /** Applies an edit and records it. The only way anything in this document changes. */
    public void apply(BuilderEdit edit) {
        super.apply(edit);
    }

    /** Several changes as one undo step — a gesture, a paste, a wrap. */
    public void applyAll(String label, List<? extends BuilderEdit> edits) {
        if (edits.isEmpty()) return;
        history().beginTransaction(label);
        try {
            for (BuilderEdit edit : edits) super.apply(edit);
        } finally {
            history().endTransaction();
        }
    }

    // ── The file ────────────────────────────────────────────────────────────

    /**
     * The document as it would be written: the header it came with, then the tree.
     *
     * <p>Stable — the same tree encodes byte for byte the same way, which is what lets "undo returns the
     * file" be an assertion rather than a hope.</p>
     */
    @Override
    public byte[] encode() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : header.entrySet()) {
            if (ROOT.equals(entry.getKey())) continue;
            out.add(entry.getKey(), entry.getValue());
        }
        out.add(ROOT, MIRROR.describe(root, extras));
        return (new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void adopt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        UiTemplate parsed = UiTemplates.parse(text.isEmpty() ? EMPTY : text, origin);
        extras.clear();
        root = parsed.inflateForEditing(extras);
        header = headerOf(parsed);
        adopted();
    }

    /** What a new file is: a format line and an empty root, so a fresh document opens rather than fails. */
    public static final String EMPTY = "{\n  \"cgui\": 1,\n  \"root\": { \"kind\": \"element\" }\n}\n";

    /** Everything the document declared except the tree, in the order it was written. */
    private static JsonObject headerOf(UiTemplate parsed) {
        JsonObject out = new JsonObject();
        out.addProperty("cgui", parsed.formatVersion());
        if (!parsed.stylesheets().isEmpty()) {
            JsonArray sheets = new JsonArray();
            for (String id : parsed.stylesheets()) sheets.add(new JsonPrimitive(id));
            out.add("stylesheets", sheets);
        }
        if (parsed.modelClass() != null) out.addProperty("model", parsed.modelClass());
        if (parsed.packageName() != null) out.addProperty("package", parsed.packageName());
        if (parsed.kindName() != null) out.addProperty("kind-name", parsed.kindName().toString());
        if (parsed.preview() != null) out.add("preview", parsed.preview());
        return out;
    }

    // ── What the shell asks ─────────────────────────────────────────────────

    /**
     * Structural problems — an unknown class, a duplicate id, an unresolvable binding.
     *
     * <p>One set for the document, not one per view: two panes onto one file report the same problems.</p>
     */
    @Override
    public DiagnosticSet diagnostics() {
        return problems;
    }

    /**
     * Never merged.
     *
     * <p>A text buffer merges typing into one edit because a keystroke is not a change anybody wants to
     * undo separately; a builder's edits are each a deliberate act, and a drag that should be one step
     * says so by being one transaction.</p>
     */
    @Override
    public boolean mergeable() {
        return false;
    }

    @Override
    public String toString() {
        return "UiBuilderDocument[" + origin + "]";
    }
}
