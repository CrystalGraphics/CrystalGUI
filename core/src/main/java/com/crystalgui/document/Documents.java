package com.crystalgui.document;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * <b>Every open document, by resource</b> — one per resource, held by reference count.
 *
 * <h3>Headless, and keyed by {@link Resource}</h3>
 *
 * <p>{@code plan_fs_rewrite.md} N1, D1. {@code OpenDocuments} was keyed by {@code CgPath}, so a
 * {@code library://} class or a generated shader source could not be in it at all — and the workbench
 * grew a parallel lane of roughly four hundred lines re-deriving open, adopt and presentation for a
 * second key type, with its own javadoc calling it a stopgap "until a second non-file document kind
 * turns up". Two did.</p>
 *
 * <h3>Case folding is the server's rule, passed in</h3>
 *
 * <p>Whether {@code Main.java} and {@code main.java} are one document is a property of the host's
 * filesystem, which only the server knows — it arrives in the protocol's greeting. {@link Resource}
 * equality stays strict, exactly as VS Code keeps {@code URI} strict and folds in {@code extUri}: a
 * key that folded would make two genuinely different files on a case-sensitive host collide.</p>
 */
public final class Documents {

    private final Map<Object, Document> byKey = new LinkedHashMap<>();
    private UnaryOperator<Resource> keyOf = UnaryOperator.identity();

    /** A document was opened for the first time — its first reference was taken. */
    public final Signal.Value<Document> onDidOpen = new Signal.Value<>();

    /** Its last reference was released and its model disposed. */
    public final Signal.Value<Document> onDidClose = new Signal.Value<>();

    /**
     * How two resources are decided to be one document.
     *
     * <p>Identity by default, which is right for a case-sensitive host and for a test. A client sets a
     * case-folding operator when the server says its filesystem folds.</p>
     */
    public Documents setKeyStrategy(@Nullable UnaryOperator<Resource> strategy) {
        this.keyOf = strategy == null ? UnaryOperator.identity() : strategy;
        return this;
    }

    /**
     * Takes a reference, opening the document if this is the first.
     *
     * <p>The factory runs only when the document is new, so a second caller for one resource joins the
     * document already open rather than building a second model over the same file — which is what
     * makes two split panes one document, and what makes the Problems panel's hold and the tab's hold
     * the same object.</p>
     */
    public DocumentReference open(Resource resource, DocumentFactory factory) {
        Objects.requireNonNull(resource, "resource");
        Object key = keyOf.apply(resource);
        Document existing = byKey.get(key);
        if (existing != null) return existing.acquire(() -> forget(key, existing));

        Document created = Objects.requireNonNull(factory.create(resource), "factory returned null");
        byKey.put(key, created);
        // BEFORE the signal, so a listener that opens something else does not race the map.
        DocumentReference reference = created.acquire(() -> forget(key, created));
        onDidOpen.emit(created);
        return reference;
    }

    /** Takes another reference on a document already open, or null if it is not. */
    @Nullable
    public DocumentReference reference(Resource resource) {
        Document document = get(resource);
        if (document == null) return null;
        Object key = keyOf.apply(resource);
        return document.acquire(() -> forget(key, document));
    }

    /** The document for this resource, or null. Does <b>not</b> take a reference. */
    @Nullable
    public Document get(Resource resource) {
        return resource == null ? null : byKey.get(keyOf.apply(resource));
    }

    public boolean isOpen(Resource resource) {
        return get(resource) != null;
    }

    /** Every open document. In the health readout, because a count that only grows is a leak. */
    public List<Document> all() {
        return List.copyOf(byKey.values());
    }

    /** Every document with unsaved work — what a close prompt and a backup sweep both ask. */
    public List<Document> dirty() {
        List<Document> out = new ArrayList<>();
        for (Document document : byKey.values()) {
            if (document.isDirty()) out.add(document);
        }
        return out;
    }

    public int size() {
        return byKey.size();
    }

    /**
     * Moves a document to a new address, rekeying it here and announcing it there.
     *
     * <p>The map's key and the document's own {@code resource} move together, which is the whole point
     * of the document being the identity: four other stores hear it from the document rather than each
     * being walked by whoever performed the rename.</p>
     */
    public void retarget(Resource from, Resource to) {
        Document document = get(from);
        if (document == null) return;
        byKey.remove(keyOf.apply(from));
        byKey.put(keyOf.apply(to), document);
        document.retarget(to);
    }

    private void forget(Object key, Document document) {
        // Only if it is still THIS document: a resource closed and reopened inside one frame would
        // otherwise have the old document's release evict the new one.
        if (byKey.get(key) == document) byKey.remove(key);
        onDidClose.emit(document);
    }

    /** Builds a document for a resource nothing has open yet. */
    @FunctionalInterface
    public interface DocumentFactory {
        Document create(Resource resource);
    }
}
