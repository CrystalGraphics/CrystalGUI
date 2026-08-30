package com.crystalgui.ui.dom;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.EventListenerGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.event.EventTarget;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A node of the new engine's tree: <b>identity, attributes, children, a shadow root, events</b> — and
 * nothing else.
 *
 * <p>No geometry, no layout id, no world matrix, no scroll offset, no keymap, no settings, no network
 * field, no internal flag. What is about being on screen belongs to a {@code Box}; what is about
 * style belongs to the style pass; what a peer needs is read from outside through the seam. The old
 * {@code UIElement} carried all of it in 3,571 lines and 22 concern sections (audit §1), and the
 * three-tree design (§12) is the decision not to.</p>
 *
 * <h3>Two trees in one</h3>
 *
 * <p>The <b>light tree</b> is {@link #parent()} and {@link #children()}: what authors build, what the
 * codec describes and what the mirror observes. A composite builds its parts into a
 * {@link #attachShadow() shadow root} instead, and its content is placed in the shadow tree's
 * {@link Slot}s. The <b>composed tree</b> — {@link #composedParent()}, {@link #composedChildren()} —
 * is light and shadow flattened through the slots, and is what layout, paint and hit-testing walk.
 * Nothing inside a shadow tree is ever described or observed; nothing outside it can reach in with a
 * selector. {@code describedChildren}, {@code markAsInternal} and the rest of the internal-child
 * machinery have no counterpart.</p>
 *
 * <h3>Mutation</h3>
 *
 * <p>{@link #append}, {@link #insertAt}, {@link #remove}, {@link #moveTo} and {@link #attachShadow} are
 * the structural entries; {@link #setId}, the class methods and {@link #set} are the attribute
 * entries. Every one asserts the document's frame thread, reports to the observer synchronously, and
 * queues lifecycle callbacks on the document to run <em>after</em> the mutation completes — never
 * during it. A mutation attempted from inside an observer notification is refused; one from inside a
 * lifecycle callback is an ordinary new mutation. A reparent is one {@code moved}, which the old tree
 * could not spell (M2).</p>
 */
public class Node implements EventTarget, Styleable {

    private final Name name;

    @Nullable
    private Node parent;
    private final List<Node> children = new ArrayList<>();
    private final List<Node> childrenView = Collections.unmodifiableList(children);

    @Nullable
    private ShadowRoot shadowRoot;
    @Nullable
    Slot assignedSlot;

    private String id = "";

    /** The box tree's hook: this node's own box, or null when it has none (5.3). */
    @Nullable
    private Box box;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> classesView = Collections.unmodifiableSet(classes);
    private final Map<Attribute<?>, Object> attributes = new HashMap<>();

    /** The document this node is connected to, or null while detached. A document is its own. */
    @Nullable
    Document document;
    /** Whether an ancestor link crosses into a shadow tree — such a node is never observed. */
    boolean inShadow;
    /** An observer installed on THIS node by a source over it. */
    @Nullable
    private TreeObserver<Node> ownObserver;
    /** The observer this node reports to: its own, or the nearest ancestor's. Null in shadow. */
    @Nullable
    TreeObserver<Node> observer;

    private boolean frozen;

    /** The cascade's store for this node, created on first use. */
    @Nullable
    private ElementStyle style;
    /** Interaction state the services set and the pseudo-classes read. Not attributes: nobody authors these. */
    private boolean hovered;
    private boolean pressed;
    private boolean focused;
    private boolean focusVisible;
    private boolean focusWithin;
    private boolean fontRelativeStyles;

    /** The listener groups, one per event type, created on first use. */
    public final EventListenerGroup.Map<Node> events = new EventListenerGroup.Map<>(this);

    public Node() {
        this(Name.ELEMENT);
    }

    public Node(Name name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public final Name name() {
        return name;
    }

    public final String id() {
        return id;
    }

    public Node setId(String id) {
        String value = id == null ? "" : id;
        if (value.equals(this.id)) return this;
        Mutation m = beginMutation("setting an id");
        try {
            Document doc = document;
            if (doc != null) doc.unindex(this);
            this.id = value;
            if (doc != null) doc.index(this);
            invalidateStyleMatch();
            m.observe(() -> TreeObserver.Dispatch.attributeChanged(observer, this));
        } finally {
            m.end();
        }
        return this;
    }

    public final Set<String> classes() {
        return classesView;
    }

    public final boolean hasClass(String className) {
        return classes.contains(className);
    }

    public Node addClass(String className) {
        if (className == null || className.isEmpty() || classes.contains(className)) return this;
        Mutation m = beginMutation("adding a class");
        try {
            classes.add(className);
            invalidateStyleMatch();
            m.observe(() -> TreeObserver.Dispatch.attributeChanged(observer, this));
        } finally {
            m.end();
        }
        return this;
    }

    public Node removeClass(String className) {
        if (!classes.contains(className)) return this;
        Mutation m = beginMutation("removing a class");
        try {
            classes.remove(className);
            invalidateStyleMatch();
            m.observe(() -> TreeObserver.Dispatch.attributeChanged(observer, this));
        } finally {
            m.end();
        }
        return this;
    }

    public Node toggleClass(String className, boolean on) {
        return on ? addClass(className) : removeClass(className);
    }

    // ── Attributes ───────────────────────────────────────────────────────────

    /** The value, or the key's initial when nothing has set it. Never null for a non-null initial. */
    public final <T> T get(Attribute<T> key) {
        Object value = attributes.get(key);
        return value == null ? key.initial() : key.type().cast(value);
    }

    /** Whether something has set this key, as opposed to the node answering the initial. */
    public final boolean has(Attribute<?> key) {
        return attributes.containsKey(key);
    }

    /** Sets, reporting one {@code attributeChanged}; a value equal to the current one reports nothing. */
    public <T> Node set(Attribute<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Object current = attributes.containsKey(key) ? attributes.get(key) : key.initial();
        if (Objects.equals(current, value)) return this;
        Mutation m = beginMutation("setting attribute '" + key.name() + "'");
        try {
            if (Objects.equals(value, key.initial())) attributes.remove(key);
            else attributes.put(key, value);
            if (key == Attribute.SLOT) {
                slotsChanged(parent);
                structureChanged();
            }
            invalidateStyleMatch();
            m.observe(() -> TreeObserver.Dispatch.attributeChanged(observer, this));
        } finally {
            m.end();
        }
        return this;
    }

    /** Every key something has set on this node, for the codec. */
    public final Set<Attribute<?>> setAttributes() {
        return Collections.unmodifiableSet(attributes.keySet());
    }

    // ── Light tree ───────────────────────────────────────────────────────────

    @Nullable
    public final Node parent() {
        return parent;
    }

    /** The light children — what authors, the codec and the mirror see. Read-only. */
    public final List<Node> children() {
        return childrenView;
    }

    public final int childCount() {
        return children.size();
    }

    public final int indexOf(Node child) {
        return children.indexOf(child);
    }

    /** The document this node is connected to, or null. */
    @Nullable
    public final Document document() {
        return document;
    }

    public final boolean isConnected() {
        return document != null;
    }

    /** Whether {@code other} is this node or a light-tree descendant of it. */
    public final boolean contains(@Nullable Node other) {
        for (Node at = other; at != null; at = at.parent) {
            if (at == this) return true;
        }
        return false;
    }

    /** The top of the light-parent chain: a document, a shadow root, or a detached subtree's root. */
    public final Node root() {
        Node at = this;
        while (at.parent != null) at = at.parent;
        return at;
    }

    public final Node append(Node child) {
        return insertAt(children.size(), child);
    }

    public final Node append(Node... nodes) {
        for (Node child : nodes) append(child);
        return this;
    }

    /**
     * Inserts, or — for a child that already has a parent — {@linkplain #moveTo moves}, which is what
     * the DOM's {@code insertBefore} does and what keeps the observer's stream one {@code moved}
     * rather than a {@code removed} followed by an {@code inserted}.
     */
    public Node insertAt(int index, Node child) {
        Objects.requireNonNull(child, "child");
        if (child.parent != null) {
            child.moveTo(this, index);
            return this;
        }
        refuseAsChild(child);
        Mutation m = beginMutation("inserting <" + child.name + ">");
        try {
            int at = clampIndex(index, children.size());
            children.add(at, child);
            child.parent = this;
            child.attachedTo(this);
            slotsChanged(this);
            structureChanged();
            reportInserted(child, this, at, m);
        } finally {
            m.end();
        }
        return this;
    }

    /** Removes a light child. False if it was not one. */
    public boolean remove(Node child) {
        if (child == null || child.parent != this) return false;
        Mutation m = beginMutation("removing <" + child.name + ">");
        try {
            // REPORTED BEFORE THE LINK IS CLEARED: the receiver anchors the change on the parent, which
            // has to be nameable while the change is being reported.
            TreeObserver<Node> to = child.observer;
            m.observe(() -> TreeObserver.Dispatch.removed(to, child, this));
            children.remove(child);
            child.parent = null;
            child.detached();
            slotsChanged(this);
            structureChanged();
        } finally {
            m.end();
        }
        return true;
    }

    public final void removeSelf() {
        if (parent != null) parent.remove(this);
    }

    public final void removeAll() {
        while (!children.isEmpty()) remove(children.get(children.size() - 1));
    }

    /**
     * Moves this node under {@code newParent} at {@code index} — one {@code moved} on the wire, the
     * instance and everything it holds intact.
     *
     * <p>The index is read <b>after</b> this node is taken out of its current position, which is the
     * only reading under which "move it to position 2" means the same thing from either side. A node
     * with no parent is inserted rather than moved. Crossing a shadow boundary is a {@code removed} or
     * an {@code inserted} as seen from the light tree, because that is what the light tree saw.</p>
     */
    public Node moveTo(Node newParent, int index) {
        Objects.requireNonNull(newParent, "parent");
        if (parent == null) {
            newParent.insertAt(index, this);
            return this;
        }
        newParent.refuseAsChild(this);
        Mutation m = beginMutation("moving <" + name + ">");
        try {
            Node old = parent;
            TreeObserver<Node> before = observer;
            Document oldDocument = document;
            old.children.remove(this);
            int at = clampIndex(index, newParent.children.size());
            newParent.children.add(at, this);
            parent = newParent;
            Document newDocument = newParent.document();
            if (oldDocument != newDocument) {
                if (oldDocument != null) detachedKeepingParent();
                attachedTo(newParent);
            } else {
                relinked(newParent);
            }
            slotsChanged(old);
            slotsChanged(newParent);
            if (oldDocument != null && oldDocument != document) oldDocument.fireStructureChanged();
            structureChanged();
            TreeObserver<Node> after = observer;
            Node self = this;
            if (before != null && after == before) {
                m.observe(() -> TreeObserver.Dispatch.moved(before, self, newParent, at));
            } else {
                if (before != null) m.observe(() -> TreeObserver.Dispatch.removed(before, self, old));
                if (after != null) reportInserted(self, newParent, at, m);
            }
        } finally {
            m.end();
        }
        return this;
    }

    /**
     * AN INSERTION NAMES EVERY NODE, PARENTS FIRST; a removal names only the subtree root. Asymmetric
     * on purpose: the receiver has nothing yet, so each node has to arrive against a parent it has
     * already heard of. Each node reports to its own effective observer, which is the grafted root's
     * unless a source was installed lower down.
     */
    private static void reportInserted(Node node, Node parent, int index, Mutation m) {
        TreeObserver<Node> to = node.observer;
        if (to != null) m.observe(() -> TreeObserver.Dispatch.inserted(to, node, parent, index));
        List<Node> kids = node.children;
        for (int i = 0; i < kids.size(); i++) reportInserted(kids.get(i), node, i, m);
    }

    private void refuseAsChild(Node child) {
        if (child == this || child.contains(this)) {
            throw new IllegalArgumentException("A node cannot contain itself");
        }
        if (child instanceof Document) throw new IllegalArgumentException("A document is a root, never a child");
        if (child instanceof ShadowRoot) {
            throw new IllegalArgumentException("A shadow root belongs to its host; attach one with attachShadow()");
        }
    }

    private static int clampIndex(int index, int size) {
        return index < 0 || index > size ? size : index;
    }

    // ── Shadow tree ──────────────────────────────────────────────────────────

    /** Attaches a shadow root. A node has at most one; asking twice is an error rather than a second root. */
    public ShadowRoot attachShadow() {
        return attachShadow(false);
    }

    /**
     * @param delegatesFocus whether focusing this host focuses the first focusable thing inside its
     *                       shadow tree — the composite's answer to "a focusable container is a wall"
     */
    public ShadowRoot attachShadow(boolean delegatesFocus) {
        if (shadowRoot != null) throw new IllegalStateException("<" + name + "> already has a shadow root");
        Mutation m = beginMutation("attaching a shadow root to <" + name + ">");
        try {
            ShadowRoot root = new ShadowRoot(this, delegatesFocus);
            shadowRoot = root;
            root.propagate(document, true, null);
            root.markSlotsDirty();
            structureChanged();
        } finally {
            m.end();
        }
        return shadowRoot;
    }

    @Nullable
    public final ShadowRoot shadowRoot() {
        return shadowRoot;
    }

    /** The slot this node is assigned to inside its parent's shadow tree, or null. */
    @Nullable
    public final Slot assignedSlot() {
        if (parent != null && parent.shadowRoot != null) parent.shadowRoot.ensureAssigned();
        return assignedSlot;
    }

    /** Whether this node is inside some shadow tree, at any depth. */
    public final boolean isInShadowTree() {
        return inShadow;
    }

    /** The shadow root this node is inside, or null. */
    @Nullable
    public final ShadowRoot containingShadowRoot() {
        for (Node at = this; at != null; at = at.parent) {
            if (at instanceof ShadowRoot) return (ShadowRoot) at;
        }
        return null;
    }

    // ── Composed tree ────────────────────────────────────────────────────────

    /**
     * The parent in the flat tree: the slot this node is assigned to; the host, for a shadow root's
     * child; null for a light child that its parent's shadow tree slots nowhere (it is not rendered).
     */
    @Nullable
    public Node composedParent() {
        Slot slot = assignedSlot();
        if (slot != null) return slot;
        if (parent == null) return null;
        if (parent instanceof ShadowRoot) return ((ShadowRoot) parent).host();
        if (parent.shadowRoot != null) return null;
        return parent;
    }

    /**
     * The children in the flat tree: the shadow tree's children when this node has one (the shadow
     * root itself is transparent), otherwise the light children. A {@link Slot} answers its assigned
     * nodes, or its fallback.
     */
    public List<Node> composedChildren() {
        if (shadowRoot != null) return shadowRoot.children();
        return childrenView;
    }

    /** This node and every composed descendant, depth-first, parents before children. */
    public final Iterable<Node> composedSubtree() {
        return () -> new Iterator<Node>() {
            private final Deque<Node> pending = new ArrayDeque<>();

            {
                pending.push(Node.this);
            }

            private void push(Node node) {
                List<Node> kids = node.composedChildren();
                for (int i = kids.size() - 1; i >= 0; i--) pending.push(kids.get(i));
            }

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public Node next() {
                if (pending.isEmpty()) throw new NoSuchElementException();
                Node next = pending.pop();
                push(next);
                return next;
            }
        };
    }

    /**
     * Retargets {@code target} for an observer at {@code relativeTo}: while the target's root is a
     * shadow root that {@code relativeTo} is not inside, the target is that root's host. What an
     * event's target and a focus query answer from outside a composite — the spec's algorithm.
     */
    public static Node retarget(Node target, @Nullable Node relativeTo) {
        Node at = target;
        while (true) {
            Node root = at.root();
            if (!(root instanceof ShadowRoot)) return at;
            if (relativeTo != null && isShadowIncludingInclusiveAncestor(root, relativeTo)) return at;
            at = ((ShadowRoot) root).host();
        }
    }

    /** Whether {@code ancestor} is {@code node} or above it, crossing from a shadow root to its host. */
    public static boolean isShadowIncludingInclusiveAncestor(Node ancestor, Node node) {
        for (Node at = node; at != null; at = at.shadowIncludingParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    @Nullable
    private Node shadowIncludingParent() {
        if (parent != null) return parent;
        return this instanceof ShadowRoot ? ((ShadowRoot) this).host() : null;
    }

    // ── Lifecycle hooks ──────────────────────────────────────────────────────

    /** Runs after this node joined a document, after the mutation that joined it; parents first. */
    protected void connected() {
    }

    /** Runs after this node left its document, after the mutation that removed it; children first. */
    protected void disconnected() {
    }

    /** Runs when a retained subtree is frozen in place: boxes dropped, hooks stopped, tree intact. */
    protected void frozen() {
    }

    /** Runs when a frozen subtree is brought back. */
    protected void thawed() {
    }

    public final boolean isFrozen() {
        return frozen;
    }

    void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    // ── Styleable: what the cascade asks (plan_m5.md D5.2) ───────────────────

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final Collection<String> getClasses() {
        return classesView;
    }

    /** The qualified name, {@code namespace:local}. */
    @Override
    public final String tagName() {
        return name.toString();
    }

    /** A type selector may spell a default-namespace kind bare: {@code button} matches {@code crystalgui:button}. */
    @Override
    public final boolean matchesType(String identity) {
        return name.toString().equals(identity)
                || (name.namespace().equals(Name.DEFAULT_NAMESPACE) && name.local().equals(identity));
    }

    @Override
    public final Collection<String> typeKeys() {
        return name.namespace().equals(Name.DEFAULT_NAMESPACE)
                ? List.of(name.toString(), name.local()) : List.of(name.toString());
    }

    /** The light parent: what a combinator walks. Null at a document and at a shadow root. */
    @Override
    @Nullable
    public final Styleable getParent() {
        return parent;
    }

    /** The composed parent: what an inherited value comes from, which crosses into a shadow tree. */
    @Override
    @Nullable
    public Styleable inheritsFrom() {
        return composedParent();
    }

    @Override
    @Nullable
    public final Styleable shadowHost() {
        ShadowRoot root = containingShadowRoot();
        return root == null ? null : root.host();
    }

    @Override
    @Nullable
    public final String partName() {
        String part = get(Attribute.PART);
        return part.isEmpty() ? null : part;
    }

    @Override
    public boolean isEnabled() {
        return get(Attribute.ENABLED);
    }

    /** A widget's own; a plain node is never checked. */
    @Override
    public boolean isChecked() {
        return false;
    }

    @Override
    public boolean isBlank() {
        return false;
    }

    @Override
    public boolean isInvalid() {
        return false;
    }

    @Override
    public final boolean isHovered() {
        return hovered;
    }

    @Override
    public final boolean isPressed() {
        return pressed;
    }

    @Override
    public final boolean isFocused() {
        return focused;
    }

    @Override
    public final boolean isFocusVisible() {
        return focusVisible;
    }

    @Override
    public final boolean isFocusWithin() {
        return focusWithin;
    }

    /** The store: every candidate at every origin. An author writes inline through it; the engine never writes. */
    @Override
    public final ElementStyle getStyle() {
        if (style == null) style = new ElementStyle(this);
        return style;
    }

    /** The cascade's frozen answer for this node — what the box tree and the paint pass read. */
    public final ComputedStyle computedStyle() {
        return getStyle().computed();
    }

    @Override
    @Nullable
    public final StyleEngine styleEngine() {
        return document == null ? null : document.styles();
    }

    @Override
    public void onStyleChanged() {
    }

    /**
     * This node's own box, or null when it has none: off the tree, or {@code display: none}. Set by
     * the document's box tree during its sync; a mirror (a thumbnail's second box) is never it.
     */
    @Nullable
    public final Box box() {
        return box;
    }

    /** The box tree's, and nobody else's. */
    public final void setBox(@Nullable Box box) {
        this.box = box;
    }

    /**
     * Custom drawing beyond the box model, called by the box painter after this node's background
     * and before its children -- what a text run, an icon or a canvas overrides. The box model
     * itself (background, border, overlay, outline, clip, opacity) is the PAINTER's and needs no
     * override; geometry comes from {@code box}, in the box's own space (the origin is this box's
     * top-left corner).
     */
    public void paintContent(CgUiPaintContext ctx, Box box) {
    }

    /** As {@link #paintContent}, after the children and before {@code overlay}/{@code outline}. */
    public void paintDecoration(CgUiPaintContext ctx, Box box) {
    }

    /** Tells the document's box tree that the composed structure moved. */
    final void structureChanged() {
        Document doc = document;
        if (doc != null) doc.fireStructureChanged();
    }

    /** A layout-affecting value changed: the box under it must be laid out again. */
    @Override
    public void markTreeDirty() {
        if (box != null) box.markLayoutDirty();
    }

    @Override
    public final void setHasFontRelativeStyles(boolean value) {
        fontRelativeStyles = value;
    }

    /**
     * A font-size change re-matches this subtree, because an {@code em} anywhere under it was
     * resolved against a size that has moved. The old engine does this with a listener on the property;
     * here it is the host's own business.
     */
    @Override
    public void computedChanged(StyleProperty<?> property, @Nullable Object oldValue, @Nullable Object newValue) {
        if (property == StylePropertyRegistry.FONT_SIZE) {
            for (Node node : composedSubtree()) node.invalidateStyleMatch();
        }
        // display: none is a structural fact -- a box exists or it does not.
        if (property == LayoutProperties.DISPLAY) structureChanged();
    }

    /** Asks the engine to re-run selector matching for this node on the next style pass. */
    protected final void invalidateStyleMatch() {
        StyleEngine engine = styleEngine();
        if (engine != null) engine.markDirty(this);
    }

    // the services set these (5.5); a change is a pseudo-class change, so it re-matches
    void setHovered(boolean value) {
        if (hovered != value) { hovered = value; invalidateStyleMatch(); }
    }

    void setPressed(boolean value) {
        if (pressed != value) { pressed = value; invalidateStyleMatch(); }
    }

    void setFocused(boolean value) {
        if (focused != value) { focused = value; invalidateStyleMatch(); }
    }

    void setFocusVisible(boolean value) {
        if (focusVisible != value) { focusVisible = value; invalidateStyleMatch(); }
    }

    void setFocusWithin(boolean value) {
        if (focusWithin != value) { focusWithin = value; invalidateStyleMatch(); }
    }

    // ── Wiring: document, observer, shadow flag ──────────────────────────────

    /** This node was linked under {@code parent}: take its document, its shadowness, its observer. */
    void attachedTo(Node parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<Node> inherited = shadow ? null : parent.observer;
        propagate(parent.document(), shadow, inherited);
    }

    /** This node moved within one document: re-derive shadowness and observer, no lifecycle. */
    void relinked(Node parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<Node> inherited = shadow ? null : parent.observer;
        rewire(shadow, inherited);
    }

    void rewire(boolean shadow, @Nullable TreeObserver<Node> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        for (Node child : children) child.rewire(shadow, observer);
        if (shadowRoot != null) shadowRoot.rewire(true, null);
    }

    void propagate(@Nullable Document doc, boolean shadow, @Nullable TreeObserver<Node> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        boolean joining = doc != null && document == null;
        document = doc;
        if (doc != null && !id.isEmpty()) doc.index(this);
        if (joining) {
            doc.styles().markDirty(this);
            doc.queue(this::connected);
        }
        for (Node child : children) child.propagate(doc, shadow, observer);
        if (shadowRoot != null) shadowRoot.propagate(doc, true, null);
    }

    /** This node left the tree: children first, then this one. */
    void detached() {
        detachedKeepingParent();
        rewire(false, ownObserver);
    }

    void detachedKeepingParent() {
        for (Node child : children) child.detachedKeepingParent();
        if (shadowRoot != null) shadowRoot.detachedKeepingParent();
        Document doc = document;
        if (doc != null) {
            doc.unindex(this);
            doc.styles().onElementDetached(this);
            doc.queue(this::disconnected);
        }
        document = null;
    }

    /** Installs the observer a source over this node reports to; propagates down the light tree. */
    void setObserver(@Nullable TreeObserver<Node> observer) {
        this.ownObserver = observer;
        TreeObserver<Node> inherited = parent == null || inShadow ? null : parent.observer;
        rewire(inShadow, inherited);
    }

    // ── Mutation bookkeeping ─────────────────────────────────────────────────

    /** The shadow context whose slot assignment a change under {@code where} may have moved. */
    private static void slotsChanged(@Nullable Node where) {
        if (where == null) return;
        if (where.shadowRoot != null) where.shadowRoot.markSlotsDirty();
        ShadowRoot enclosing = where.containingShadowRoot();
        if (enclosing != null) enclosing.markSlotsDirty();
    }

    Mutation beginMutation(String what) {
        Document doc = document;
        if (doc != null) {
            doc.require(what);
            doc.enter();
        }
        return new Mutation(doc);
    }

    /** One mutation: observer notifications run at once under the re-entrancy guard; callbacks after. */
    static final class Mutation {
        @Nullable
        private final Document document;

        Mutation(@Nullable Document document) {
            this.document = document;
        }

        void observe(Runnable notification) {
            if (document != null) document.notifyObserver(notification);
            else notification.run();
        }

        void end() {
            if (document != null) document.exit();
        }
    }

    @Override
    public String toString() {
        return "<" + name + (id.isEmpty() ? "" : " #" + id) + (classes.isEmpty() ? "" : " ." + String.join(".", classes)) + ">";
    }
}
