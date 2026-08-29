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
 *   <li><b>{@link #byId} is a map lookup.</b> {@code NetworkIds.find} — the class this replaced, now
 *       deleted — walked the tree comparing a field on every element, per packet.</li>
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

    private int assignFrom(UIElement element) {
        int allocated = nextId++;
        ids.put(element, allocated);
        byId.put(allocated, element);
        int total = 1;
        for (UIElement child : element.describedChildrenFor()) total += assignFrom(child);
        return total;
    }

    /**
     * Numbers a subtree that has just joined, as one contiguous block from the counter.
     *
     * <p>The other half of "identity is not position". An insert carries its <b>base</b> id and its
     * described count, and the far side numbers its own decoded copy the same way — so both sides
     * agree without either renumbering anything that was already there, which is the entire point.</p>
     *
     * @return the base id; the subtree occupies {@code base .. base + describedCount - 1}
     */
    @Override
    public int allocate(UIElement subtreeRoot) {
        requireOpen();
        return assignFrom(subtreeRoot) == 0 ? NO_ID : ids.get(subtreeRoot);
    }

    /**
     * Forgets every id in {@code subtreeRoot}'s described subtree.
     *
     * <p>Called when a subtree leaves. An element the server keeps a reference to and re-adds later is
     * then a fresh insert with a fresh block, which is what the DOM does with a node you hold and
     * re-append — and is why a re-attach in a LATER tick is not a move.</p>
     */
    @Override
    public void release(UIElement subtreeRoot) {
        Integer id = ids.remove(subtreeRoot);
        if (id != null) byId.remove(id);
        for (UIElement child : subtreeRoot.describedChildrenFor()) release(child);
    }

    /**
     * Records {@code node} under an id the OTHER side chose.
     *
     * <p>The client half of a block allocation: an insert carries its base, and the receiver numbers
     * its own decoded copy the same way rather than allocating from a counter of its own. That is what
     * makes two sides agree on ids without either renumbering anything.</p>
     */
    @Override
    public void assignAt(UIElement node, int id) {
        requireOpen();
        ids.put(node, id);
        byId.put(id, node);
        if (id >= nextId) nextId = id + 1;
    }

    /**
     * Drops every allocation, so the next walk starts at zero.
     *
     * <p>Only {@code open()} does this. After a window is open, ids are STABLE -- an element keeps its
     * number through inserts, moves and removals of its siblings -- so anything calling this on a live
     * window would invalidate every id the far side is holding.</p>
     */
    @Override
    public void resetIds() {
        ids.clear();
        byId.clear();
        nextId = 0;
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
     * The widget's own {@link com.crystalgui.ui.contract.WidgetContract}, or a minimal stand-in for an
     * element that has none.
     *
     * <p>M0 built this by hand from the element -- a name, an empty event set, and a per-instance merge
     * of whatever {@code addReportedEvent} had been called with. M1 moved the answer to where a widget
     * can declare it, so this is now a lookup, and the fallback below is what an <b>uncontracted</b>
     * element gets: a plain {@code UIElement} used as a container, which is most of a tree and has
     * nothing to say.</p>
     */
    @Override
    public NodeContract contractOf(UIElement node) {
        NodeContract declared = com.crystalgui.ui.contract.WidgetContracts.of(node);
        if (declared != null) return declared;
        return contracts.computeIfAbsent(node.getClass(), type -> new PlainNode(
                node.tagName(), node.acceptsDescribedChildrenFor()));
    }

    /** What an element with no declared contract is, structurally. */
    private static final class PlainNode implements NodeContract {
        private final String name;
        private final boolean acceptsChildren;

        private PlainNode(String name, boolean acceptsChildren) {
            this.name = name;
            this.acceptsChildren = acceptsChildren;
        }

        @Override public String name() {
            return name;
        }
        @Override public java.util.Set<String> eventKinds() {
            return java.util.Set.of();
        }
        @Override public boolean acceptsDescribedChildren() {
            return acceptsChildren;
        }
        @Override public boolean carriesState() {
            return false;
        }
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

    private void requireOpen() {
        if (closed) throw new IllegalStateException("This TreeSource is closed");
    }
}
