package com.crystalgui.ui.dom;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The seam, implemented natively by the node tree.
 *
 * <p>What {@link ElementTreeSource} is to {@code UIElement}, this is to {@link UINode} — and it is the
 * whole of what the mirror needs from the second engine: ids in a table the source owns, light
 * children as {@link #childrenOf}, contracts by {@link Name}, and the observer installed on the
 * observed root so that only the subtree under it reports. Nothing in a shadow tree is ever handed
 * out, because nothing there is a light child of anything.</p>
 *
 * <p>Two sources over one tree number independently (each has its own table), as the contract
 * requires; but only one observer is installed per root, because that is what the node carries —
 * the same shape the old engine's propagated field has.</p>
 */
public final class UINodeTreeSource implements TreeSource<UINode> {

    private final UINode root;
    private final Map<UINode, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, UINode> byId = new HashMap<>();
    private int nextId;
    @Nullable
    private TreeObserver<UINode> observer;
    private boolean closed;

    public UINodeTreeSource(UINode root) {
        this.root = root;
    }

    @Override
    public int idOf(UINode node) {
        requireOpen();
        Integer existing = ids.get(node);
        if (existing != null) return existing;
        int allocated = nextId++;
        ids.put(node, allocated);
        byId.put(allocated, node);
        return allocated;
    }

    @Override
    public int peekId(UINode node) {
        Integer existing = ids.get(node);
        return existing == null ? NO_ID : existing;
    }

    @Override
    @Nullable
    public UINode byId(int id) {
        return byId.get(id);
    }

    @Override
    public int allocate(UINode subtreeRoot) {
        requireOpen();
        int base = nextId;
        assignFrom(subtreeRoot);
        return base;
    }

    private void assignFrom(UINode node) {
        int allocated = nextId++;
        ids.put(node, allocated);
        byId.put(allocated, node);
        for (UINode child : node.children()) assignFrom(child);
    }

    @Override
    public void release(UINode subtreeRoot) {
        Integer id = ids.remove(subtreeRoot);
        if (id != null) byId.remove(id);
        for (UINode child : subtreeRoot.children()) release(child);
    }

    @Override
    public void assignAt(UINode node, int id) {
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
    public UINode root() {
        return root;
    }

    @Override
    @Nullable
    public UINode parentOf(UINode node) {
        return node == root ? null : node.parent();
    }

    /** The light children — the described tree. A shadow tree is not part of it. */
    @Override
    public List<UINode> childrenOf(UINode node) {
        return node.describedChildren();
    }

    @Override
    public boolean contains(UINode node) {
        return root.contains(node);
    }

    @Override
    public NodeContract contractOf(UINode node) {
        return UINodeRegistry.contractFor(node.name());
    }

    @Override
    public void observe(@Nullable TreeObserver<UINode> observer) {
        requireOpen();
        this.observer = observer;
        root.setObserver(observer);
    }

    @Override
    @Nullable
    public TreeObserver<UINode> observer() {
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
