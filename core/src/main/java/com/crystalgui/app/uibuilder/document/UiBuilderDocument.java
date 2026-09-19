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

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.undo.CompositeEdit;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.document.AbstractDocumentModel;
import com.crystalgui.net.mirror.DocumentExtras;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.template.UiTemplate;
import com.crystalgui.template.UiTemplateException;
import com.crystalgui.template.UiTemplates;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;

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

    /** Everything following the tree — one per pane showing it. @see #watch */
    private final List<TreeObserver<UIElement>> watchers = new ArrayList<>();

    /** The tree's one observer slot, handing every report to each watcher. */
    private final TreeObserver<UIElement> fanOut = new TreeObserver<>() {
        @Override public void inserted(UIElement node, UIElement parent, int index) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.inserted(node, parent, index);
        }
        @Override public void removed(UIElement node, UIElement parent) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.removed(node, parent);
        }
        @Override public void moved(UIElement node, UIElement parent, int index) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.moved(node, parent, index);
        }
        @Override public void attributeChanged(UIElement node) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.attributeChanged(node);
        }
        @Override public void inlineStyleChanged(UIElement node) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.inlineStyleChanged(node);
        }
        @Override public void stateChanged(UIElement node) {
            for (TreeObserver<UIElement> each : List.copyOf(watchers)) each.stateChanged(node);
        }
    };

    @Nullable
    private UIElementTreeSource observed;

    public UiBuilderDocument(byte[] bytes, String origin) {
        this.origin = origin;
        // No merge window: a value edit merges only inside a held gesture. @see #mergeable
        history().setMergeWindowMillis(0L);
        // AN UNDO OR A REDO touches what its edit names, and says so. @see #announce
        history().onDidStep.connect(this::announce);
        adopt(bytes);
    }

    /** What the document is called in a refusal — a workspace path, or an asset id. */
    public String origin() {
        return origin;
    }

    /**
     * The live tree. Read it freely; change it only through {@link #apply}. <b>Never on screen</b>: each pane shows a
     * copy of it, so it has no boxes and no computed style — ask a pane for where a node is drawn.
     */
    public UIElement root() {
        return root;
    }

    /**
     * Reports every change to the tree to {@code observer}, until the connection is ended — how a pane's copy
     * follows. The reports arrive during the change; an observer records them and acts after, since the engine
     * refuses a mutation from inside a notification.
     */
    public Connection watch(TreeObserver<UIElement> observer) {
        watchers.add(observer);
        return () -> watchers.remove(observer);
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

    /**
     * Applies an edit and records it. The only way anything in this document changes.
     *
     * <p>An edit may name a node as a pane draws it — the drawing a gesture previewed on, the drawn node the inspector
     * reads — and is resolved to the document's own first. @see #resolve</p>
     */
    public void apply(BuilderEdit edit) {
        BuilderEdit resolved = edit.resolvedIn(this::resolve);
        // BEFORE THE CHANGE, which announces itself: a pane follows on that, and reads the node as it then is.
        announce(resolved);
        super.apply(resolved);
    }

    /**
     * Tells every watcher what {@code edit} touched, whether or not the tree reported it. An edit is often recorded
     * after its caller already wrote the value — a gesture's preview — and applying it then changes nothing, so no
     * notification comes; a pane following the tree would keep its old drawing.
     */
    private void announce(Edit edit) {
        if (edit instanceof CompositeEdit composite) {
            for (Edit each : composite.edits()) announce(each);
            return;
        }
        if (!(edit instanceof BuilderEdit change) || change.node() == null) return;
        UIElement node = change.node();
        if (edit instanceof BuilderEdit.SetInlineStyle) fanOut.inlineStyleChanged(node);
        else if (edit instanceof BuilderEdit.SetState) fanOut.stateChanged(node);
        else if (edit instanceof BuilderEdit.SetId || edit instanceof BuilderEdit.SetClasses
                || edit instanceof BuilderEdit.SetAttribute<?>) fanOut.attributeChanged(node);
    }

    /**
     * The document node {@code node} is or stands for: itself when it is one of the document's, else what a pane
     * drawing it says it stands for, else itself — a node not yet in any tree, about to be inserted.
     */
    public UIElement resolve(UIElement node) {
        if (node == root || root.contains(node)) return node;
        for (TreeObserver<UIElement> watcher : watchers) {
            if (!(watcher instanceof DocumentDrawing drawing)) continue;
            UIElement source = drawing.source(node);
            if (source != null) return source;
        }
        return node;
    }

    /**
     * A detached copy of {@code node} and everything under it, carrying their design values — what a
     * duplicate inserts.
     *
     * <pre>{@code
     * UIElement copy = document.copyOf(button);
     * document.apply(new BuilderEdit.Insert(button.parentElement(), copy, index));
     * }</pre>
     *
     * <p>Through the same codec the file is written with, so the copy is exactly what saving and reopening
     * the original would produce. Ids are copied as they are; a caller inserting beside the original frees
     * them first.</p>
     */
    public UIElement copyOf(UIElement node) {
        return MIRROR.decode(MIRROR.describe(node, extras), extras);
    }

    /** Several changes as one undo step — a gesture, a paste, a wrap. */
    public void applyAll(String label, List<? extends BuilderEdit> edits) {
        if (edits.isEmpty()) return;
        history().beginTransaction(label);
        try {
            for (BuilderEdit edit : edits) {
                BuilderEdit resolved = edit.resolvedIn(this::resolve);
                announce(resolved);
                super.apply(resolved);
            }
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

    /**
     * Reads the file, or opens empty and says why.
     *
     * <p>A document that will not parse still opens: a tab that refuses to appear leaves nowhere to see
     * what is wrong with the file, and the Problems row is where that belongs. A blank file is a NEW
     * file — which is what the explorer's New File makes — and is the empty document rather than an
     * error.</p>
     */
    @Override
    public void adopt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        extras.clear();
        try {
            UiTemplate parsed = UiTemplates.parse(text.trim().isEmpty() ? EMPTY : text, origin);
            root = parsed.inflateForEditing(extras);
            header = headerOf(parsed);
            problems.changeOne(PARSE, List.of());
        } catch (UiTemplateException broken) {
            UiTemplate empty = UiTemplates.parse(EMPTY, origin);
            root = empty.inflateForEditing(extras);
            header = headerOf(empty);
            problems.changeOne(PARSE,
                    List.of(Diagnostic.onRow(0, DiagnosticSeverity.ERROR, broken.getMessage())));
        }
        // THE OBSERVER SLOT FOLLOWS THE ROOT, which a reopen replaces.
        if (observed != null) observed.close();
        observed = new UIElementTreeSource(root);
        observed.observe(fanOut);
        adopted();
    }

    /** Who owns the parse diagnostics in {@link #problems}. */
    private static final String PARSE = "cgui.parse";

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
