package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * {@link TreeSource} over today's {@code UIElement} tree. {@code plan_ui_rewrite.md} M0.
 *
 * <p>Half of the seam's purpose is served by this class existing: everything above it is written
 * against {@link TreeSource}, so when {@code ui.dom}'s node tree lands at M5 it implements the same
 * interface and <b>this file is what gets replaced</b> — not the mirror, not the sessions, not the
 * window layer.</p>
 *
 * <h3>Identity is allocated here, and that is the whole change</h3>
 *
 * <p>An element's network id used to be a field on the element, written by a depth-first walk — so it
 * <em>was</em> the element's position. Inserting a sibling renumbered everything after it; a
 * client-side reparent moved the element out from under the number the server was still addressing it
 * by. That is the defect the entire rewrite came out of.</p>
 *
 * <p>Here the number lives in a table the source owns, keyed by element identity. It is allocated on
 * first sight and then belongs to that element for the life of the source — through a reparent, a
 * sibling insert, or a re-describe. Two further things fall out of moving it, and both are
 * improvements rather than side effects:</p>
 *
 * <ul>
 *   <li><b>{@link #byId} is a map lookup.</b> {@code NetworkIds.find} walked the tree comparing a field
 *       on every element, per packet.</li>
 *   <li><b>Ids are per-source.</b> Two sessions over one tree each keep their own numbering instead of
 *       fighting over one field — which is why {@code UIElement.setObserver} could only ever hold one
 *       observer, a limitation its own callers document.</li>
 * </ul>
 *
 * <h3>{@link IdentityHashMap}, deliberately</h3>
 *
 * <p>{@code UIElement} does not override {@code equals}, so a hash map would behave identically today —
 * and would stop doing so the moment any widget ever did, silently collapsing two elements onto one id.
 * The key here is the object, and saying so costs nothing.</p>
 *
 * <p><b>Strong references, and the table is bounded by {@link #close()}.</b> A weak map is the reflex and
 * is wrong: an id has to survive an element being detached — that is precisely what makes a
 * hide-then-show keep its instance — so the table must hold what the tree has let go of. The source is
 * owned by whatever opened it and dies with the window.</p>
 */
public final class ElementTreeSource implements TreeSource<UIElement> {

    private final UIElement root;

    private final Map<UIElement, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, UIElement> byId = new HashMap<>();
    private int nextId;

    /**
     * Per-class, because a contract describes a <b>kind</b>. Only the parts that genuinely are per-class
     * are cached here; see {@link #contractOf}.
     */
    private final Map<Class<?>, NodeContract> contracts = new HashMap<>();

    @Nullable
    private TreeObserver<UIElement> observer;

    private boolean closed;

    public ElementTreeSource(UIElement root) {
        this.root = root;
    }

    // ── Identity ─────────────────────────────────────────────────────────────

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

    /**
     * Numbers {@code from}'s whole subtree in document order, including internal children.
     *
     * <p><b>Legacy, and M2 deletes it.</b> It exists so the wire's numbering does not change on the same
     * commit the storage moves: the protocol still expects both sides to derive the same ids from the
     * same walk, and changing the policy and the storage together would leave a failure attributable to
     * either. After M2 nothing calls this and ids are allocated by {@link #idOf} on first sight, which
     * is the point of the exercise.</p>
     *
     * @return how many elements were numbered
     */
    public int assignInDocumentOrder(UIElement from) {
        requireOpen();
        return assignFrom(from);
    }

    private int assignFrom(UIElement element) {
        int allocated = nextId++;
        ids.put(element, allocated);
        byId.put(allocated, element);
        int total = 1;
        for (UIElement child : element.getChildren()) total += assignFrom(child);
        return total;
    }

    /** Drops every allocation, so the next walk starts at zero. What a re-describe needs. */
    public void resetIds() {
        ids.clear();
        byId.clear();
        nextId = 0;
    }

    /** How many ids have been handed out. */
    public int allocatedCount() {
        return ids.size();
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Override
    public UIElement root() {
        return root;
    }

    @Override
    @Nullable
    public UIElement parentOf(UIElement node) {
        return node == root ? null : node.getParent();
    }

    /**
     * The <b>described</b> children — a composite's own scaffolding is not here, because the far side
     * rebuilds it from the constructor and describing it would duplicate the whole structure.
     *
     * <p>Delegates to the element's existing hook rather than reimplementing the rule. Which children
     * are content is a fact about a kind of widget, so at M1 it moves onto {@link NodeContract} and this
     * method reads it from there; the delegation is what keeps that a one-file change.</p>
     */
    @Override
    public List<UIElement> childrenOf(UIElement node) {
        return node.describedChildrenFor();
    }

    @Override
    public boolean contains(UIElement node) {
        for (UIElement at = node; at != null; at = at.getParent()) {
            if (at == root) return true;
        }
        return false;
    }

    // ── Contract ─────────────────────────────────────────────────────────────

    /**
     * <p>Two halves with different lifetimes, and the split is temporary. The <b>name</b> and whether
     * children may be described are per-class and cached. <b>Reported events are still per-instance</b>
     * today — {@code ServerUiSession.on} adds them to individual elements and the client reads them back
     * off the wire per element — so they are read from the element and merged. M1 makes them a
     * declaration on the widget class and this method stops merging anything.</p>
     */
    @Override
    public NodeContract contractOf(UIElement node) {
        NodeContract base = contracts.get(node.getClass());
        if (base == null) {
            base = new NodeContract(node.tagName(), java.util.Set.of(), node.acceptsDescribedChildrenFor());
            contracts.put(node.getClass(), base);
        }

        java.util.Set<String> reported = node.getReportedEvents();
        if (reported.isEmpty()) return base;
        return new NodeContract(base.name(), reported, base.acceptsDescribedChildren());
    }

    // ── Observation ──────────────────────────────────────────────────────────

    @Override
    public void observe(@Nullable TreeObserver<UIElement> observer) {
        requireOpen();
        this.observer = observer;
        root.setDomObserver(observer);
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
        root.setDomObserver(null);
        observer = null;
        ids.clear();
        byId.clear();
        contracts.clear();
    }

    /**
     * Every element the source currently holds an id for, in allocation order.
     *
     * <p>For a flush that has to walk what it has told the far side about, rather than what is in the
     * tree — the two differ exactly when something has been detached, which is the case that matters.
     */
    public List<UIElement> numbered() {
        List<UIElement> out = new ArrayList<>(ids.size());
        for (int id = 0; id < nextId; id++) {
            UIElement element = byId.get(id);
            if (element != null) out.add(element);
        }
        return out;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("This TreeSource is closed");
    }
}
