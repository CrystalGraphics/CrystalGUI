package com.crystalgui.ui.dom;

import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsScope;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeymapScope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A node of the tree: <b>identity, a place among children, and the walks out</b> — and nothing else.
 *
 * <p>The DOM's {@code Node}, and the reason it exists here is the DOM's own: not everything in a tree
 * is an element. {@link ShadowRoot} is a {@code DocumentFragment} — it has children, a document and a
 * lifecycle, and it has no id, no classes, no attributes, no style, no box and no events. Before the
 * split it inherited all of those, so {@code shadowRoot.addClass(...)} compiled and the cascade
 * dutifully matched what it wrote.</p>
 *
 * <p><b>Every child is an element.</b> {@code ShadowRoot} and {@link UIDocument} are the only nodes
 * that are not, and both are roots — either can be a parent, neither can ever be a child. That is why
 * {@link #children()} answers {@code UIElement} while {@link #parent()} answers {@code UINode}, and
 * why {@link #parentElement()} exists for the common case, exactly as {@code parentNode} and
 * {@code parentElement} differ on the web.</p>
 *
 * <h3>What is here and what is not</h3>
 *
 * <p>Here: the light tree, the composed tree, the lifecycle callbacks, the observer wiring the mirror
 * reads, and the two interfaces that walk <em>outward</em> — {@link KeymapScope} and
 * {@link SettingsScope}. A shadow root must be transparent to both, which is what
 * {@link ShadowRoot#commandParent()} answering its host is for: encapsulation governs which rules
 * match a node, never which questions it may ask outward.</p>
 *
 * <p>Not here: everything a stylesheet, a box or an event needs. That is {@link UIElement}.</p>
 */
public abstract class UINode implements KeymapScope, SettingsScope {

    protected UINode(Name name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    final Name name;

    @Nullable
    UINode parent;

    final List<UIElement> children = new ArrayList<>();

    final List<UIElement> childrenView = Collections.unmodifiableList(children);

    /**
     * The shadow tree this node hosts, or null.
     *
     * <p>The FIELD is here and {@code attachShadow()} is on {@link UIElement}, which is the split
     * doing its job rather than a leak: the composed tree is defined in terms of shadow roots — a
     * host's composed children ARE its shadow tree's, and {@code retarget} walks out through them —
     * so every walk that reads this is a node's. What a shadow root must not be able to do is HOST
     * one, and it cannot, because the entry point is an element's.</p>
     */
    @Nullable
    ShadowRoot shadowRoot;

    boolean structural;

    @Nullable
    UISlot assignedSlot;

    /** The document this node is connected to, or null while detached. A document is its own. */
    @Nullable
    UIDocument document;

    /** Whether an ancestor link crosses into a shadow tree — such a node is never observed. */
    boolean inShadow;

    /** An observer installed on THIS node by a source over it. */
    @Nullable
    TreeObserver<UIElement> ownObserver;

    /** The observer this node reports to: its own, or the nearest ancestor's. Null in shadow. */
    @Nullable
    TreeObserver<UIElement> observer;

    boolean frozen;

    // ── Identity ────────────────────────────────────────────────────────────────

    public final Name name() {
        return name;
    }

    // ── KeymapScope and SettingsScope: the two walks OUT ────────────────────────

    /**
     * This node's own keymap, created on first ask.
     *
     * <p><b>Lazy, and null until asked</b>, which is what {@link #keymapOrNull()} answers: a keymap
     * per node would be a map on every one of thousands, for the handful that bind anything. The
     * same reasoning {@code SettingsScope} records for its own store.</p>
     *
     * <p>The walk that reads these was already here -- {@code KeymapResolver} and {@code Keymap} both
     * climb {@code commandParent()} asking each scope for one -- and NOTHING implemented it, so every
     * scope answered null and a widget-scoped binding could never resolve. Live machinery with no
     * supplier: nothing failed, the shortcut simply did nothing, which reads as the binding being
     * wrong rather than as there being nowhere to put it.</p>
     */
    public Keymap keymap() {
        // THROUGH `keymapOrNull()`, so a widget that already keeps its own -- TextEditor, GraphView,
        // ProjectFileTree all do, and they override that one -- hands back the keymap its commands
        // are actually bound in. Creating a second one here instead would answer an EMPTY keymap to
        // anyone asking the widget for its bindings, while the real ones still resolved: the widget
        // works and every query about it lies.
        Keymap existing = keymapOrNull();
        if (existing != null) return existing;
        if (keymap == null) keymap = new Keymap();
        return keymap;
    }

    /** The keymap this node ALREADY has, without making one. @see #keymap() */
    @Override
    @Nullable
    public Keymap keymapOrNull() {
        return keymap;
    }

    @Nullable Keymap keymap;

    /**
     * {@inheritDoc}
     *
     * <p>The LIGHT parent, deliberately. A command's subject is what the author built, and a
     * widget's own parts are not subjects — a press on a button's label resolves to the button, not
     * to the label, which is what makes {@code DataContext} answer the same thing however deep the
     * gesture landed.</p>
     */
    @Override
    @Nullable
    public KeymapScope commandParent() {
        return parent;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>The DOCUMENT's, because that is where a document-level provider is registered and this
     * node is what gets asked.</b> {@code DataContext.fromWindow} calls this on the element a command
     * was invoked FROM, never on the document — so a node answering its own (empty) list means a
     * provider registered through {@link UIDocument#addDataProvider} is never consulted by anything,
     * and the two that exist ({@code Desktop}, {@code CrystalEditor}) were both silently inert.</p>
     *
     * <p>This javadoc used to say the new engine had no such consumer yet, which was true when it was
     * written and stopped being true without the sentence changing. The failure is the shape that
     * makes it: the provider registers, the command resolves, and it resolves to nothing.</p>
     *
     * <p>{@link UIDocument} overrides this with its own list, so there is no recursion — and a
     * detached node has no document and correctly answers with nothing.</p>
     */
    @Override
    public List<DataProvider> scopeProviders() {
        UIDocument host = document();
        return host == null || host == this ? List.of() : host.scopeProviders();
    }

    @Nullable
    Settings settings;

    /**
     * This node's own settings, created on first ask.
     *
     * <p>VS Code resolves a setting's scope by URI; this resolves it by the TREE, which is why the
     * scope chain is the parent chain and every node is a scope rather than only the few that
     * happen to own a store today.</p>
     */
    @Override
    public Settings settings() {
        if (settings == null) settings = new Settings();
        return settings;
    }

    /**
     * The store <b>if one exists</b>, without bringing one into being.
     *
     * <p>Load-bearing, not an optimisation: {@code resolveRaw} visits every ancestor on every read,
     * so a walk that went through {@link #settings()} would allocate an empty store for each one and
     * keep it — turning a read into a write and giving the whole tree a store per node.</p>
     */
    @Override
    @Nullable
    public Settings settingsOrNull() {
        return settings;
    }

    /** The enclosing scope is the LIGHT parent — the same chain {@link #commandParent()} walks. */
    @Override
    @Nullable
    public SettingsScope settingsParent() {
        return parent;
    }

    // ── Light tree ──────────────────────────────────────────────────────────────

    /**
     * This node as an element, or {@code null} for a {@link ShadowRoot}.
     *
     * <p>Used only where a mutation has to be REPORTED. {@code TreeObserver<N>} binds {@code N} to
     * {@code UIElement} and that is right: the mirror describes the light tree, and a shadow root is
     * never in one — its {@code observer} is null by construction. So the null branch here is exactly
     * the shadow case, where reporting is already a no-op.</p>
     */
    @Nullable
    final UIElement asElement() {
        return this instanceof UIElement element ? element : null;
    }

    /**
     * The parent as an ELEMENT, or {@code null} when there is none or it is a {@link ShadowRoot}.
     *
     * <p>The web's {@code parentElement} beside {@code parentNode}, and the reason both exist is the
     * same here: a shadow root is a parent and is not an element, so a walk that wants to ask its
     * parent something an element answers has to say which question it is asking. A null here means
     * "the walk reached a shadow boundary", which is nearly always the right place to stop.</p>
     */
    @Nullable
    public final UIElement parentElement() {
        return parent == null ? null : parent.asElement();
    }

    /** The parent NODE: an element, a shadow root, a document, or null. @see #parentElement() */
    @Nullable
    public final UINode parent() {
        return parent;
    }

    /**
     * What a peer is told about, and where a described child goes.
     *
     * <p>Usually the light children, and for most widgets that is the end of it: a composite's own
     * parts live in a shadow tree, which nothing here ever hands out because nothing there is a light
     * child of anything. That is the mechanism, and it covers 23 of the 44 widgets.</p>
     *
     * <p>The other 21 may not have a shadow tree — a sheet reaches through their structure and
     * {@code ::part()} cannot spell a part under a part — so their scaffolding IS light children, and
     * without these two hooks it is described: the far side rebuilds the parts in its constructor and
     * then appends a second copy of every one of them. {@code TabView} is the case that found it, and
     * it also needs the other half, because a described {@code Tab} must be PLACED (its button in the
     * rail, its content in the panes) rather than appended anywhere.</p>
     *
     * <p>Refusing here is how a composite says a child is not one of its kind, which is the whole of
     * what the old engine's {@code acceptsPublicChildren} did at this seam.</p>
     */
    public List<UIElement> describedChildren() {
        return children();
    }

    /** @see #describedChildren() */
    public void adoptDescribedChild(UIElement child) {
        append(child);
    }

    /** The light children — what authors, the codec and the mirror see. Read-only. */
    public final List<UIElement> children() {
        return childrenView;
    }

    public final int childCount() {
        return children.size();
    }

    public final int indexOf(UIElement child) {
        return children.indexOf(child);
    }

    /** The document this node is connected to, or null. */
    @Nullable
    public final UIDocument document() {
        return document;
    }

    /**
     * How many links up the COMPOSED tree the document is — the document itself is 0.
     *
     * <p>Composed rather than light, because it answers "which of these is innermost", and a part
     * inside a widget is innermost even though the light tree stops at the widget. The old engine
     * memoised this in a cache cell; the walk is a handful of pointer follows and the widgets that
     * ask do so once per frame at most.</p>
     */
    public final int depth() {
        int depth = 0;
        for (UIElement at = composedParent(); at != null; at = at.composedParent()) depth++;
        return depth;
    }

    public final boolean isConnected() {
        return document != null;
    }

    /** Whether {@code other} is this node or a light-tree descendant of it. */
    public final boolean contains(@Nullable UINode other) {
        for (UINode at = other; at != null; at = at.parent) {
            if (at == this) return true;
        }
        return false;
    }

    /** The top of the light-parent chain: a document, a shadow root, or a detached subtree's root. */
    public final UINode root() {
        UINode at = this;
        while (at.parent != null) at = at.parent;
        return at;
    }

    public UINode append(UIElement child) {
        return insertAt(children.size(), child);
    }

    public UINode append(UIElement... nodes) {
        for (UIElement child : nodes) append(child);
        return this;
    }

    /**
     * Inserts, or — for a child that already has a parent — {@linkplain #moveTo moves}, which is what
     * the DOM's {@code insertBefore} does and what keeps the observer's stream one {@code moved}
     * rather than a {@code removed} followed by an {@code inserted}.
     */
    public UINode insertAt(int index, UIElement child) {
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
            UIElement host = asElement();
            if (host != null) reportInserted(child, host, at, m);
        } finally {
            m.end();
        }
        return this;
    }

    /** Removes a light child. False if it was not one. */
    public boolean remove(UIElement child) {
        if (child == null || child.parent != this) return false;
        Mutation m = beginMutation("removing <" + child.name + ">");
        try {
            // REPORTED BEFORE THE LINK IS CLEARED: the receiver anchors the change on the parent, which
            // has to be nameable while the change is being reported.
            TreeObserver<UIElement> to = child.observer;
            UIElement host = asElement();
            if (host != null) m.observe(() -> TreeObserver.Dispatch.removed(to, child, host));
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


    public final void removeAll() {
        while (!children.isEmpty()) remove(children.get(children.size() - 1));
    }


    /**
     * AN INSERTION NAMES EVERY NODE, PARENTS FIRST; a removal names only the subtree root. Asymmetric
     * on purpose: the receiver has nothing yet, so each node has to arrive against a parent it has
     * already heard of. Each node reports to its own effective observer, which is the grafted root's
     * unless a source was installed lower down.
     */
    static void reportInserted(UIElement node, UIElement parent, int index, Mutation m) {
        TreeObserver<UIElement> to = node.observer;
        if (to != null) m.observe(() -> TreeObserver.Dispatch.inserted(to, node, parent, index));
        List<UIElement> kids = node.children;
        for (int i = 0; i < kids.size(); i++) reportInserted(kids.get(i), node, i, m);
    }

    void refuseAsChild(UIElement child) {
        if (child == this || child.contains(this)) {
            throw new IllegalArgumentException("A node cannot contain itself");
        }
        if (child instanceof UIDocument) throw new IllegalArgumentException("A document is a root, never a child");
        if (child instanceof ShadowRoot) {
            throw new IllegalArgumentException("A shadow root belongs to its host; attach one with attachShadow()");
        }
        // ONLY WHAT DECLARED ITSELF FIXED, never anything merely slotless. An unslotted light child
        // is the web's own state and three tests pin it; a widget that called
        // `refusePublicChildren()` has promised more than that. See that method.
        if (!structural && refusesPublicChildren) {
            throw new UnsupportedOperationException("<" + name + "> takes no public children, so <"
                    + child.name + "> would never be composed. Use the widget's own accessors.");
        }
    }

    static int clampIndex(int index, int size) {
        return index < 0 || index > size ? size : index;
    }

    /** Attaches a shadow root. A node has at most one; asking twice is an error rather than a second root. */
    /**
     * Whether a CALLER may append to this node.
     *
     * <p>Derived from the shadow tree by default, so it cannot be forgotten on a new widget: a host
     * with no DEFAULT slot has nowhere to put a light child, and that child would sit in the light
     * tree, in no composed tree, with no box, no paint and no promotion -- with nothing anywhere
     * reporting a problem.</p>
     *
     * <p><b>Override it where the derivation cannot see the answer.</b> A composite whose sheets
     * reach through its structure may not have a shadow tree at all (M6.1 measured 21 of 44 that
     * cannot), so its parts are ordinary light children and the default says yes. {@code SplitView}
     * and {@code TabView} are exactly that: fixed structure, no shadow root, typed accessors as the
     * way in. They declare it, as the old engine's {@code acceptsPublicChildren} did.</p>
     */
    public boolean acceptsPublicChildren() {
        if (refusesPublicChildren) return false;
        return shadowRoot == null || shadowRoot.slot("") != null;
    }

    /**
     * <b>A widget declares that its structure is fixed; it is not derived from the slots.</b>
     *
     * <p>Deriving it was the first attempt and it conflates two different things. A shadow host with
     * no default slot is the WEB's ordinary state -- the light child is in the light tree, out of the
     * composed tree, and legal -- and three tests pin that behaviour directly. A {@code TabView}
     * refusing a stray child is a stronger promise, made by the widget about itself, and the reason
     * it is worth making is that the alternative is silent: the child gets no box, no paint and no
     * promotion, with nothing anywhere reporting a problem, or -- worse, for a {@link
     * com.crystalgui.widget.scroll.ListView} -- is recycled out of existence on the next refresh.</p>
     *
     * <p>So it is said out loud, in the constructor of the widget that means it, exactly as the old
     * engine's overridable {@code acceptsPublicChildren} did. Give the widget a named accessor for
     * its content instead of opening the tree.</p>
     */
    protected final void refusePublicChildren() {
        refusesPublicChildren = true;
    }

    boolean refusesPublicChildren;

    /**
     * Appends AMBIENT engine furniture — resize handles — past a widget's refusal of public children.
     *
     * <p>Package-private and reached only through {@link ResizeHandles}, which is the seam that says
     * WHEN handles exist. {@code resize} is a CSS property that applies to elements generally, so the
     * cascade grows handles on whatever a sheet names — including a {@code Dialog}, which refuses
     * public children because its structure is fixed. Those handles are not a caller's children and
     * the refusal was never about them: it exists so a caller's content cannot vanish among a widget's
     * parts. Adding them through the public {@code append} threw, and the whole gallery died on the
     * first dialog it built.</p>
     */
    final UINode appendAmbient(UIElement child) {
        return insertStructuralAt(children.size(), child);
    }

    /** Appends a part the WIDGET owns, past its own refusal of public children. */
    protected final UINode appendStructural(UIElement child) {
        return insertStructuralAt(children.size(), child);
    }

    /**
     * Inserts a part the WIDGET owns, past its own refusal of public children -- this engine's
     * {@code addInternalChild}, minus the flag: what makes a part a part here is that the widget
     * put it there, not a bit stored on it.
     */
    protected final UINode insertStructuralAt(int index, UIElement child) {
        boolean was = structural;
        structural = true;
        try {
            return insertAt(index, child);
        } finally {
            structural = was;
        }
    }

    // ── Composed tree ───────────────────────────────────────────────────────────

    /** The slot this node is assigned to inside its parent's shadow tree, or null. */
    @Nullable
    public final UISlot assignedSlot() {
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
        for (UINode at = this; at != null; at = at.parent) {
            if (at instanceof ShadowRoot) return (ShadowRoot) at;
        }
        return null;
    }

    /**
     * The parent in the flat tree: the slot this node is assigned to; the host, for a shadow root's
     * child; null for a light child that its parent's shadow tree slots nowhere (it is not rendered).
     */
    @Nullable
    public UIElement composedParent() {
        UISlot slot = assignedSlot();
        if (slot != null) return slot;
        if (parent == null) return null;
        if (parent instanceof ShadowRoot) return ((ShadowRoot) parent).host();
        if (parent.shadowRoot != null) return null;
        return parentElement();
    }

    /**
     * The children in the flat tree: the shadow tree's children when this node has one (the shadow
     * root itself is transparent), otherwise the light children. A {@link UISlot} answers its assigned
     * nodes, or its fallback.
     */
    public List<UIElement> composedChildren() {
        if (shadowRoot != null) return shadowRoot.children();
        return childrenView;
    }


    /**
     * Retargets {@code target} for an observer at {@code relativeTo}: while the target's root is a
     * shadow root that {@code relativeTo} is not inside, the target is that root's host. What an
     * event's target and a focus query answer from outside a composite — the spec's algorithm.
     */
    public static UIElement retarget(UIElement target, @Nullable UINode relativeTo) {
        UIElement at = target;
        while (true) {
            UINode root = at.root();
            if (!(root instanceof ShadowRoot)) return at;
            if (relativeTo != null && isShadowIncludingInclusiveAncestor(root, relativeTo)) return at;
            at = ((ShadowRoot) root).host();
        }
    }

    /** Whether {@code ancestor} is {@code node} or above it, crossing from a shadow root to its host. */
    public static boolean isShadowIncludingInclusiveAncestor(UINode ancestor, UINode node) {
        for (UINode at = node; at != null; at = at.shadowIncludingParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    @Nullable
    private UINode shadowIncludingParent() {
        if (parent != null) return parent;
        return this instanceof ShadowRoot ? ((ShadowRoot) this).host() : null;
    }

    // ── Lifecycle hooks ─────────────────────────────────────────────────────────

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

    /** The lifecycle service's. Set across the composed subtree, so every reader is one field read. */
    public final void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /** The lifecycle service's: runs this node's {@link #frozen()} hook. */
    public final void fireFrozen() {
        frozen();
    }

    /** The lifecycle service's: runs this node's {@link #thawed()} hook. */
    public final void fireThawed() {
        thawed();
    }

    /** Says the composed structure moved — what the box tree listens for. */
    public final void markStructureChanged() {
        structureChanged();
    }

    /** Tells the document's box tree that the composed structure moved. */
    final void structureChanged() {
        UIDocument doc = document;
        if (doc != null) doc.fireStructureChanged();
    }

    /**
     * Makes {@code wanted} this node's only light child, doing nothing if it already is.
     *
     * <p>The point is the no-op: a container that rebuilds its content on every refresh would
     * otherwise detach and re-attach the same node, which is a removal and an insertion on the wire
     * and a lifecycle round trip for a tree nothing changed about.</p>
     */
    public UINode setOnlyChild(@Nullable UIElement wanted) {
        if (childCount() == 1 && children().get(0) == wanted) return this;
        removeAll();
        if (wanted != null) append(wanted);
        return this;
    }

    // ── Querying -- ParentNode's, so a shadow root can search itself ────────────

    /** The first light descendant matching {@code selector}, in document order, or null. */
    @Nullable
    public final UIElement querySelector(String selector) {
        return NodeQueries.querySelector(this, selector, false);
    }

    /** Every light descendant matching {@code selector}, in document order. */
    public final List<UIElement> querySelectorAll(String selector) {
        return NodeQueries.querySelectorAll(this, selector, false);
    }

    /**
     * The first light descendant with this exact id, or null.
     *
     * <p>Not final: {@link UIDocument} answers it from its id INDEX instead, which is a map lookup
     * where this is a walk — and a document is where the question is nearly always asked.</p>
     */
    @Nullable
    public UIElement getElementById(String id) {
        return NodeQueries.getElementById(this, id, false);
    }

    public final List<UIElement> getElementsByClassName(String className) {
        return NodeQueries.getElementsByClassName(this, className, false);
    }

    /** {@link #querySelector} typed, or null when nothing matched or the match is another kind. */
    @Nullable
    public final <T extends UIElement> T find(String selector, Class<T> type) {
        UIElement found = querySelector(selector);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    /** {@link #find}, but a miss is a programming error rather than a null to carry around. */
    public final <T extends UIElement> T require(String selector, Class<T> type) {
        T found = find(selector, type);
        if (found == null) {
            throw new IllegalStateException("No " + type.getSimpleName() + " matches '" + selector + "' under " + this);
        }
        return found;
    }

    // ── Commands and keys ───────────────────────────────────────────────────────

    /**
     * This KIND's named actions, registered once for the class the first time one joins a document.
     *
     * <p><b>Statics only.</b> The old engine ran this from {@code UIElement}'s instance initialiser,
     * where fields are not assigned yet — so a widget contributing a per-instance thing passed null
     * and the whole feature was dead on arrival with nothing logged, because "no provider" and "a
     * provider that knows nothing" look identical from outside. Running it from
     * {@link #connected()} instead means the node is built, but it is still once per CLASS: register
     * per-instance things in the constructor.</p>
     */
    protected void registerCommands(CommandRegistry registry) {
    }

    /** This INSTANCE's chords, element-scoped. Runs on the first attach, after {@link #registerCommands}. */
    protected void bindKeys() {
    }

    /** Every class that has had {@link #registerCommands} run for it. */

    void runCommandHooks() {
        // KEYED TO THE REGISTRY, never to a static set on this class. A static latch outlives
        // `CommandRegistry.resetForTesting()`: the reset empties the registry, the next node of an
        // already-seen class registers nothing, and the command is simply absent -- no throw, no log,
        // just a key that stopped working. `contribute` records the contributor ON the registry, so
        // clearing one clears the other.
        CommandRegistry.global().contribute(getClass(), this::registerCommands);
        bindKeys();
    }

    /** This node was linked under {@code parent}: take its document, its shadowness, its observer. */
    // ── Wiring: document, observer, shadow flag ─────────────────────────────────

    void attachedTo(UINode parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<UIElement> inherited = shadow ? null : parent.observer;
        propagate(parent.document(), shadow, inherited);
    }

    /** This node moved within one document: re-derive shadowness and observer, no lifecycle. */
    void relinked(UINode parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<UIElement> inherited = shadow ? null : parent.observer;
        rewire(shadow, inherited);
    }

    void rewire(boolean shadow, @Nullable TreeObserver<UIElement> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        for (UIElement child : children) child.rewire(shadow, observer);
        if (shadowRoot != null) shadowRoot.rewire(true, null);
    }

    void propagate(@Nullable UIDocument doc, boolean shadow, @Nullable TreeObserver<UIElement> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        boolean joining = doc != null && document == null;
        document = doc;
        UIElement self = asElement();
        if (doc != null && self != null && !self.id().isEmpty()) doc.index(self);
        if (joining) {
            if (self != null) doc.styles().markDirty(self);
            // BEFORE connected(), and queued rather than run here: a command's `enabledWhen` may be
            // asked the moment the node is on screen, and a chord bound after the first key press is
            // a chord that did nothing once. Both run in the same drain, so a widget's own
            // connected() sees its commands already registered.
            doc.queue(this::runCommandHooks);
            // SEEDED BEFORE connected(), so a widget's own hook sees the state it is being restored
            // with rather than the state it was constructed with. Queued like the rest: applying a
            // payload runs a widget's setters, and those may mutate.
            doc.queue(() -> {
                SessionState<?> session = doc.sessionState();
                if (session != null && self != null) session.applyTo(self);
            });
            // NOT DELIVERED IF IT HAS SINCE LEFT. These callbacks are queued and drained once the
            // outermost mutation finishes, so a node can be attached and detached again before its own
            // `connected` runs -- and a widget's hook reasonably assumes it has a document, because
            // being connected is what the callback MEANS. Eight of them dereference `document()` on
            // the first line and every one is an NPE out of the drain, which surfaces as a crash in
            // `UIDocument.settle` naming a widget nothing was doing anything to.
            //
            // The DOM's own rule: `connectedCallback` is not delivered to an element that is no longer
            // connected by the time the reactions queue is processed. `disconnected` below needs no
            // such guard -- it is the departure itself, and a node that has come BACK has already
            // queued a fresh `connected` behind it.
            doc.queue(() -> {
                if (isConnected()) connected();
            });
        }
        for (UIElement child : children) child.propagate(doc, shadow, observer);
        if (shadowRoot != null) shadowRoot.propagate(doc, true, null);
    }

    /** This node left the tree: children first, then this one. */
    void detached() {
        detachedKeepingParent();
        rewire(false, ownObserver);
    }

    void detachedKeepingParent() {
        for (UIElement child : children) child.detachedKeepingParent();
        if (shadowRoot != null) shadowRoot.detachedKeepingParent();
        UIDocument doc = document;
        if (doc != null) {
            // HARVESTED HERE AND NOWHERE ELSE. A hidden tool window is DETACHED, so a save afterwards
            // walks a tree this widget is no longer in and writes nothing -- drag the Run panel's
            // divider, close the panel, quit, and the width is gone. This is the last moment the
            // value exists to be read.
            UIElement self = asElement();
            SessionState<?> session = doc.sessionState();
            if (session != null && self != null) session.captureFrom(self);
            if (self != null) {
                doc.unindex(self);
                doc.styles().onElementDetached(self);
            }
            // ANYTHING HOLDING THIS NODE HAS TO BE TOLD, or the reference outlives the tree it made
            // sense in. The old engine's `unregisterElement` did all five and each has an invariant
            // behind it: hover left in a detached subtree makes the next diff walk two trees that
            // never converge; a press target or a pointer capture keeps routing events at something
            // nobody can see; a drag whose SOURCE went converts every coordinate through a transform
            // that no longer means anything; a detached modal leaves the whole document inert with
            // nothing left to interact with; a popover that left the tree goes on taking Escape.
            //
            // Demotion is the fifth and is this engine's own: promotion is recorded on the DOCUMENT,
            // so a node that leaves the tree while promoted stays in that set and is re-hosted the
            // moment it comes back -- which is right for a hide/show and wrong for a close.
            if (self != null) {
                doc.input().forget(self);
                doc.focus().forget(self);
                doc.animation().forget(self);
                doc.dismiss().forget(self);
                doc.demote(self);
            }
            doc.queue(this::disconnected);
        }
        document = null;
    }

    /** Installs the observer a source over this node reports to; propagates down the light tree. */
    void setObserver(@Nullable TreeObserver<UIElement> observer) {
        this.ownObserver = observer;
        TreeObserver<UIElement> inherited = parent == null || inShadow ? null : parent.observer;
        rewire(inShadow, inherited);
    }

    /** The shadow context whose slot assignment a change under {@code where} may have moved. */
    static void slotsChanged(@Nullable UINode where) {
        if (where == null) return;
        if (where.shadowRoot != null) where.shadowRoot.markSlotsDirty();
        ShadowRoot enclosing = where.containingShadowRoot();
        if (enclosing != null) enclosing.markSlotsDirty();
    }

    Mutation beginMutation(String what) {
        UIDocument doc = document;
        if (doc != null) {
            doc.require(what);
            doc.enter();
        }
        return new Mutation(doc);
    }

    /** One mutation: observer notifications run at once under the re-entrancy guard; callbacks after. */
    static final class Mutation {
        @Nullable
        private final UIDocument document;

        Mutation(@Nullable UIDocument document) {
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
        return "<" + name + ">";
    }
}
