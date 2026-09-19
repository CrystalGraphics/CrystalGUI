package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.document.DocumentDrawing;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.ui.dom.TreeObserver;
import com.crystalgui.ui.dom.UIElement;
import com.google.gson.JsonElement;

/**
 * One pane's copy of a {@link UiBuilderDocument}'s tree — what its canvas shows and lays out. The document's own tree
 * is never on screen, so two panes onto one file each have a real, laid-out, hit-testable tree of their own, and a
 * change to the document reaches both.
 *
 * <pre>{@code
 * ShownTree shown = new ShownTree(document);
 * artboard.show(shown.root());
 * document.apply(new BuilderEdit.SetId(node, "title"));
 * shown.sync();                              // the copy follows
 * UIElement onScreen = shown.shown(node);   // where a document node is drawn in this pane
 * UIElement picked = shown.source(hit);     // which document node a hit in this pane is
 * }</pre>
 *
 * <ul>
 *   <li>Follows by <b>recording</b> what the document's observer reports and <b>reconciling</b> on {@link #sync()}:
 *       the engine refuses a mutation from inside an observer notification, and reconciling a parent's children
 *       against the document's does not depend on replaying edits in order.</li>
 *   <li>Copied through the codec the file is saved with, so a copy is exactly what reopening the file would build.</li>
 *   <li>{@link #close()} when the pane goes, or the document goes on reporting to it.</li>
 * </ul>
 */
public final class ShownTree implements TreeObserver<UIElement>, DocumentDrawing {

    private static final UIElementMirror<JsonElement> MIRROR =
            new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);

    private final UiBuilderDocument document;
    private final Connection watching;

    private final Map<UIElement, UIElement> shownOf = new IdentityHashMap<>();
    private final Map<UIElement, UIElement> sourceOf = new IdentityHashMap<>();

    /** The document root this copy was built from; a different one means the file was reopened. */
    @Nullable
    private UIElement sourceRoot;
    private UIElement root = new UIElement();

    private final Set<UIElement> dirtyParents = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<UIElement> dirtyNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    /** A node moved: its old parent is only known from where its copy still sits. */
    private final Set<UIElement> moved = Collections.newSetFromMap(new IdentityHashMap<>());

    public ShownTree(UiBuilderDocument document) {
        this.document = document;
        this.watching = document.watch(this);
        sync();
    }

    /** The copy's root, which the canvas shows. Replaced when the document is reopened. */
    public UIElement root() {
        return root;
    }

    /** Where {@code source} — a node of the document — is drawn in this pane, or null when it is not. */
    @Nullable
    public UIElement shown(@Nullable UIElement source) {
        return source == null ? null : shownOf.get(source);
    }

    /** The document node {@code shown} — a node of this pane's copy — stands for, or null. */
    @Nullable
    @Override
    public UIElement source(@Nullable UIElement shown) {
        return shown == null ? null : sourceOf.get(shown);
    }

    /** Whether {@code node} is part of this pane's copy. */
    public boolean isShown(@Nullable UIElement node) {
        return node != null && sourceOf.containsKey(node);
    }

    /**
     * Brings the copy up to date with the document.
     *
     * @return whether the ROOT was replaced — the document was reopened — so the caller shows the new one
     */
    public boolean sync() {
        if (document.root() != sourceRoot) {
            rebuild();
            return true;
        }
        if (dirtyParents.isEmpty() && dirtyNodes.isEmpty() && moved.isEmpty()) return false;
        for (UIElement node : moved) {
            UIElement copy = shownOf.get(node);
            UIElement oldParent = copy == null ? null : sourceOf.get(copy.parentElement());
            if (oldParent != null) dirtyParents.add(oldParent);
        }
        moved.clear();
        boolean structural = !dirtyParents.isEmpty();
        for (UIElement parent : new ArrayList<>(dirtyParents)) reconcile(parent);
        dirtyParents.clear();
        for (UIElement node : new ArrayList<>(dirtyNodes)) refresh(node);
        dirtyNodes.clear();
        if (structural) forgetDetached();
        return false;
    }

    /** Stops following the document. */
    public void close() {
        watching.disconnect();
    }

    // ── Following ───────────────────────────────────────────────────────────────────────────────

    @Override
    public void inserted(UIElement node, UIElement parent, int index) {
        dirtyParents.add(parent);
    }

    @Override
    public void removed(UIElement node, UIElement parent) {
        dirtyParents.add(parent);
    }

    @Override
    public void moved(UIElement node, UIElement parent, int index) {
        dirtyParents.add(parent);
        moved.add(node);
    }

    @Override
    public void attributeChanged(UIElement node) {
        dirtyNodes.add(node);
    }

    @Override
    public void inlineStyleChanged(UIElement node) {
        dirtyNodes.add(node);
    }

    @Override
    public void stateChanged(UIElement node) {
        dirtyNodes.add(node);
    }

    // ── Reconciling ─────────────────────────────────────────────────────────────────────────────

    private void rebuild() {
        shownOf.clear();
        sourceOf.clear();
        dirtyParents.clear();
        dirtyNodes.clear();
        moved.clear();
        sourceRoot = document.root();
        root = copyOf(sourceRoot);
    }

    /** Makes the copy of {@code source}'s children be copies of its children, in order — reusing every copy there is. */
    private void reconcile(UIElement source) {
        UIElement shown = shownOf.get(source);
        // NOT SHOWN: an ancestor was copied whole since, or the node has left the document.
        if (shown == null) return;
        List<UIElement> wanted = new ArrayList<>();
        for (UIElement child : source.describedChildren()) {
            UIElement copy = shownOf.get(child);
            wanted.add(copy != null ? copy : copyOf(child));
        }
        for (UIElement have : new ArrayList<>(shown.describedChildren())) {
            if (!wanted.contains(have)) MIRROR.removeChild(shown, have);
        }
        for (int i = 0; i < wanted.size(); i++) {
            List<UIElement> now = shown.describedChildren();
            if (i < now.size() && now.get(i) == wanted.get(i)) continue;
            // A MOVE when the copy sits somewhere already, which keeps its widget state and the box under it.
            MIRROR.insertChild(shown, wanted.get(i), i);
        }
    }

    /** Carries {@code source}'s identity, state and inline style onto its copy. */
    private void refresh(UIElement source) {
        UIElement shown = shownOf.get(source);
        if (shown == null) return;
        MIRROR.applyAttributes(MIRROR.encodeAttributes(source), shown);
        JsonElement state = MIRROR.encodeState(source);
        if (state != null) MIRROR.applyState(state, shown);
        // REPLACED, never merged: a declaration the document dropped has to leave the copy too, or an undo of a
        // `position: absolute` leaves every drawing absolute.
        InlineStyleCodec.replaceInto(JsonOps.INSTANCE, MIRROR.encodeInlineStyle(source), shown);
    }

    /** A copy of {@code source} and everything under it, mapped node for node. */
    private UIElement copyOf(UIElement source) {
        UIElement copy = MIRROR.decode(MIRROR.describe(source));
        map(source, copy);
        return copy;
    }

    private void map(UIElement source, UIElement copy) {
        shownOf.put(source, copy);
        sourceOf.put(copy, source);
        List<UIElement> sources = source.describedChildren();
        List<UIElement> copies = copy.describedChildren();
        for (int i = 0; i < Math.min(sources.size(), copies.size()); i++) map(sources.get(i), copies.get(i));
    }

    /** Forgets every node that has left the document, and its copy with it. */
    private void forgetDetached() {
        for (UIElement source : new ArrayList<>(shownOf.keySet())) {
            if (source == sourceRoot || sourceRoot.contains(source)) continue;
            UIElement copy = shownOf.remove(source);
            if (copy != null) sourceOf.remove(copy);
        }
    }
}
