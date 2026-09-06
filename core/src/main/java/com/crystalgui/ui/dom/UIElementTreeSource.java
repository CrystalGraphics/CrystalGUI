package com.crystalgui.ui.dom;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The seam, implemented natively by the node tree.
 *
 * <p>What {@link ElementTreeSource} is to {@code UIElement}, this is to {@link UIElement} — and it is the
 * whole of what the mirror needs from the second engine: ids in a table the source owns, light
 * children as {@link #childrenOf}, contracts by {@link Name}, and the observer installed on the
 * observed root so that only the subtree under it reports. Nothing in a shadow tree is ever handed
 * out, because nothing there is a light child of anything.</p>
 *
 * <p>Two sources over one tree number independently (each has its own table), as the contract
 * requires; but only one observer is installed per root, because that is what the node carries —
 * the same shape the old engine's propagated field has.</p>
 */
public final class UIElementTreeSource implements TreeSource<UIElement> {

    private final UIElement root;
    private final Map<UIElement, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, UIElement> byId = new HashMap<>();
    private int nextId;
    @Nullable
    private TreeObserver<UIElement> observer;
    private boolean closed;

    public UIElementTreeSource(UIElement root) {
        this.root = root;
    }

    @Override
    public int idOf(UIElement node) {
        requireOpen();
        Integer existing = ids.get(node);
        if (existing != null) return existing;
        int allocated = nextId++;
        ids.put(node, allocated);
        byId.put(allocated, node);
        return allocated;
    }

    @Override
    public int peekId(UIElement node) {
        Integer existing = ids.get(node);
        return existing == null ? NO_ID : existing;
    }

    @Override
    @Nullable
    public UIElement byId(int id) {
        return byId.get(id);
    }

    @Override
    public int allocate(UIElement subtreeRoot) {
        requireOpen();
        int base = nextId;
        assignFrom(subtreeRoot);
        return base;
    }

    private void assignFrom(UIElement node) {
        int allocated = nextId++;
        ids.put(node, allocated);
        byId.put(allocated, node);
        for (UIElement child : node.children()) assignFrom(child);
    }

    @Override
    public void release(UIElement subtreeRoot) {
        Integer id = ids.remove(subtreeRoot);
        if (id != null) byId.remove(id);
        for (UIElement child : subtreeRoot.children()) release(child);
    }

    @Override
    public void assignAt(UIElement node, int id) {
        requireOpen();
        ids.put(node, id);
        byId.put(id, node);
        if (id >= nextId) nextId = id + 1;
    }

    @Override
    public void resetIds() {
        ids.clear();
        byId.clear();
        nextId = 0;
    }

    @Override
    public UIElement root() {
        return root;
    }

    @Override
    @Nullable
    public UIElement parentOf(UIElement node) {
        return node == root ? null : node.parentElement();
    }

    /** The light children — the described tree. A shadow tree is not part of it. */
    @Override
    public List<UIElement> childrenOf(UIElement node) {
        return node.describedChildren();
    }

    @Override
    public boolean contains(UIElement node) {
        return root.contains(node);
    }

    @Override
    public NodeContract contractOf(UIElement node) {
        return UIElementRegistry.contractFor(node.name());
    }

    @Override
    public void observe(@Nullable TreeObserver<UIElement> observer) {
        requireOpen();
        this.observer = observer;
        root.setObserver(observer);
    }

    @Override
    @Nullable
    public TreeObserver<UIElement> observer() {
        return observer;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        root.setObserver(null);
        observer = null;
        ids.clear();
        byId.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("This TreeSource is closed");
    }
}
