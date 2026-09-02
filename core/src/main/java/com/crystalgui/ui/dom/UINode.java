package com.crystalgui.ui.dom;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsScope;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.ComputedStyle;
import com.crystalgui.style.ElementStyle;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Overflow;
import dev.vfyjxf.taffy.style.TaffyPosition;
import com.crystalgui.style.property.visual.ScrollBehavior;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.ui.EventListenerGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.EventTarget;
import com.crystalgui.ui.event.UIEvent;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.input.keymap.KeymapScope;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.joml.Vector2f;

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
 * {@link UISlot}s. The <b>composed tree</b> — {@link #composedParent()}, {@link #composedChildren()} —
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
public class UINode implements EventTarget, Styleable, KeymapScope, SettingsScope {

    private final Name name;

    @Nullable
    private UINode parent;
    private final List<UINode> children = new ArrayList<>();
    private final List<UINode> childrenView = Collections.unmodifiableList(children);

    @Nullable
    private ShadowRoot shadowRoot;
    private boolean structural;
    @Nullable
    UISlot assignedSlot;

    private String id = "";

    /** The box tree's hook: this node's own box, or null when it has none (5.3). */
    @Nullable
    private Box box;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> classesView = Collections.unmodifiableSet(classes);
    private final Map<Attribute<?>, Object> attributes = new HashMap<>();

    /** The document this node is connected to, or null while detached. A document is its own. */
    @Nullable
    UIDocument document;
    /** Whether an ancestor link crosses into a shadow tree — such a node is never observed. */
    boolean inShadow;
    /** An observer installed on THIS node by a source over it. */
    @Nullable
    private TreeObserver<UINode> ownObserver;
    /** The observer this node reports to: its own, or the nearest ancestor's. Null in shadow. */
    @Nullable
    TreeObserver<UINode> observer;

    private boolean frozen;
    private float scrollLeft;
    private float scrollTop;

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
    public final EventListenerGroup.Map<UINode> events = new EventListenerGroup.Map<>(this);

    // ── The pre-bound groups ─────────────────────────────────────────────────
    //
    // The same sixteen fields UIElement declares, with the same names, because 82 call sites across
    // the widget layer read them (`.onMouseDown` alone is 53) and every one kept is a site the M6
    // codemod never has to touch. A field is also how a reader finds out what a node can be told:
    // `events.getGroup(MouseEvent.Down.class)` is discoverable only if you already know the answer.

    public final EventListenerGroup<UINode, MouseEvent.Down> onMouseDown = events.getGroup(MouseEvent.Down.class);
    public final EventListenerGroup<UINode, MouseEvent.Up> onMouseUp = events.getGroup(MouseEvent.Up.class);
    public final EventListenerGroup<UINode, MouseEvent.Scroll> onMouseScroll = events.getGroup(MouseEvent.Scroll.class);
    public final EventListenerGroup<UINode, MouseEvent.Move> onMouseMove = events.getGroup(MouseEvent.Move.class);
    public final EventListenerGroup<UINode, MouseEvent.Enter> onMouseEnter = events.getGroup(MouseEvent.Enter.class);
    public final EventListenerGroup<UINode, MouseEvent.Leave> onMouseLeave = events.getGroup(MouseEvent.Leave.class);

    public final EventListenerGroup<UINode, KeyboardEvent.Down> onKeyDown = events.getGroup(KeyboardEvent.Down.class);
    public final EventListenerGroup<UINode, KeyboardEvent.Up> onKeyUp = events.getGroup(KeyboardEvent.Up.class);

    public final EventListenerGroup<UINode, DragEvent.Enter> onDragEnter = events.getGroup(DragEvent.Enter.class);
    public final EventListenerGroup<UINode, DragEvent.Leave> onDragLeave = events.getGroup(DragEvent.Leave.class);
    public final EventListenerGroup<UINode, DragEvent.Over> onDragOver = events.getGroup(DragEvent.Over.class);
    public final EventListenerGroup<UINode, DragEvent.Drop> onDrop = events.getGroup(DragEvent.Drop.class);
    public final EventListenerGroup<UINode, DragEvent.Cancel> onDragCancel = events.getGroup(DragEvent.Cancel.class);

    public final EventListenerGroup<UINode, FocusEvent.Focus> onFocus = events.getGroup(FocusEvent.Focus.class);
    public final EventListenerGroup<UINode, FocusEvent.Blur> onBlur = events.getGroup(FocusEvent.Blur.class);

    /**
     * A plain container — the {@code <div>} of this engine, and what the no-argument constructor
     * makes.
     *
     * <p>Named {@code element} rather than {@code node} because it is what a <em>stylesheet</em>
     * writes: this is the tag a type selector matches, and {@code element { }} is the rule an author
     * means. The Java type is a node because a document and a shadow root are ones too.</p>
     */
    public static final Name NAME = Name.of("element");

    public UINode() {
        this(NAME);
    }

    public UINode(Name name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    public final Name name() {
        return name;
    }

    public final String id() {
        return id;
    }

    public UINode setId(String id) {
        String value = id == null ? "" : id;
        if (value.equals(this.id)) return this;
        Mutation m = beginMutation("setting an id");
        try {
            UIDocument doc = document;
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

    // ── CommandTarget / KeymapScope: how a command finds its subject (M6.3) ─────

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

    private @Nullable Keymap keymap;
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

    // ── SettingsScope: a value resolves by walking OUT through the tree ─────────

    @Nullable
    private Settings settings;

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

    /**
     * The node a command or a data lookup was invoked from, or {@code null} when it was not a node.
     *
     * <p>{@code CommandContext.source()} is an {@code Object} on purpose — {@code core.command} may
     * name no UI type — so every consumer that wants to walk the tree has to narrow it. Narrowed once
     * here rather than as an {@code instanceof} chain per command file, which is what the old engine
     * ended up with before it did the same.</p>
     */
    @Nullable
    public static UINode sourceOf(@Nullable CommandContext context) {
        return context != null && context.source() instanceof UINode node ? node : null;
    }

    /** @see #sourceOf(CommandContext) */
    @Nullable
    public static UINode sourceOf(@Nullable DataContext context) {
        return context != null && context.source() instanceof UINode node ? node : null;
    }

    public final Set<String> classes() {
        return classesView;
    }

    public final boolean hasClass(String className) {
        return classes.contains(className);
    }

    public UINode addClass(String className) {
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

    public UINode removeClass(String className) {
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

    public UINode toggleClass(String className, boolean on) {
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
    public <T> UINode set(Attribute<T> key, T value) {
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
            // HIDDEN decides whether a box EXISTS, so it is a structural fact in exactly the way
            // `display: none` is -- and the box tree only walks the composed tree on frames the node
            // tree reported a change, so without this a hidden node keeps its box until something
            // unrelated dirties the structure.
            if (key == Attribute.HIDDEN) structureChanged();
            // SCROLL_EXEMPT is read while world matrices are composed, and changes none of the
            // geometry -- so it is a transform invalidation and not a relayout.
            if (key == Attribute.SCROLL_EXEMPT && box != null) box.tree().transformsChanged();
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
    public List<UINode> describedChildren() {
        return children();
    }

    /** @see #describedChildren() */
    public void adoptDescribedChild(UINode child) {
        append(child);
    }

    /** The light children — what authors, the codec and the mirror see. Read-only. */
    public final List<UINode> children() {
        return childrenView;
    }

    public final int childCount() {
        return children.size();
    }

    public final int indexOf(UINode child) {
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
        for (UINode at = composedParent(); at != null; at = at.composedParent()) depth++;
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

    public final UINode append(UINode child) {
        return insertAt(children.size(), child);
    }

    public final UINode append(UINode... nodes) {
        for (UINode child : nodes) append(child);
        return this;
    }

    /**
     * Inserts, or — for a child that already has a parent — {@linkplain #moveTo moves}, which is what
     * the DOM's {@code insertBefore} does and what keeps the observer's stream one {@code moved}
     * rather than a {@code removed} followed by an {@code inserted}.
     */
    public UINode insertAt(int index, UINode child) {
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
    public boolean remove(UINode child) {
        if (child == null || child.parent != this) return false;
        Mutation m = beginMutation("removing <" + child.name + ">");
        try {
            // REPORTED BEFORE THE LINK IS CLEARED: the receiver anchors the change on the parent, which
            // has to be nameable while the change is being reported.
            TreeObserver<UINode> to = child.observer;
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
    public UINode moveTo(UINode newParent, int index) {
        Objects.requireNonNull(newParent, "parent");
        if (parent == null) {
            newParent.insertAt(index, this);
            return this;
        }
        newParent.refuseAsChild(this);
        Mutation m = beginMutation("moving <" + name + ">");
        try {
            UINode old = parent;
            TreeObserver<UINode> before = observer;
            UIDocument oldDocument = document;
            old.children.remove(this);
            int at = clampIndex(index, newParent.children.size());
            newParent.children.add(at, this);
            parent = newParent;
            UIDocument newDocument = newParent.document();
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
            TreeObserver<UINode> after = observer;
            UINode self = this;
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
    private static void reportInserted(UINode node, UINode parent, int index, Mutation m) {
        TreeObserver<UINode> to = node.observer;
        if (to != null) m.observe(() -> TreeObserver.Dispatch.inserted(to, node, parent, index));
        List<UINode> kids = node.children;
        for (int i = 0; i < kids.size(); i++) reportInserted(kids.get(i), node, i, m);
    }

    private void refuseAsChild(UINode child) {
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

    private static int clampIndex(int index, int size) {
        return index < 0 || index > size ? size : index;
    }

    // ── Shadow tree ──────────────────────────────────────────────────────────

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

    private boolean refusesPublicChildren;

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
    final UINode appendAmbient(UINode child) {
        return insertStructuralAt(children.size(), child);
    }

    /** Appends a part the WIDGET owns, past its own refusal of public children. */
    protected final UINode appendStructural(UINode child) {
        return insertStructuralAt(children.size(), child);
    }

    /**
     * Inserts a part the WIDGET owns, past its own refusal of public children -- this engine's
     * {@code addInternalChild}, minus the flag: what makes a part a part here is that the widget
     * put it there, not a bit stored on it.
     */
    protected final UINode insertStructuralAt(int index, UINode child) {
        boolean was = structural;
        structural = true;
        try {
            return insertAt(index, child);
        } finally {
            structural = was;
        }
    }

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

    // ── Composed tree ────────────────────────────────────────────────────────

    /**
     * The parent in the flat tree: the slot this node is assigned to; the host, for a shadow root's
     * child; null for a light child that its parent's shadow tree slots nowhere (it is not rendered).
     */
    @Nullable
    public UINode composedParent() {
        UISlot slot = assignedSlot();
        if (slot != null) return slot;
        if (parent == null) return null;
        if (parent instanceof ShadowRoot) return ((ShadowRoot) parent).host();
        if (parent.shadowRoot != null) return null;
        return parent;
    }

    /**
     * The children in the flat tree: the shadow tree's children when this node has one (the shadow
     * root itself is transparent), otherwise the light children. A {@link UISlot} answers its assigned
     * nodes, or its fallback.
     */
    public List<UINode> composedChildren() {
        if (shadowRoot != null) return shadowRoot.children();
        return childrenView;
    }

    /** This node and every composed descendant, depth-first, parents before children. */
    public final Iterable<UINode> composedSubtree() {
        return () -> new Iterator<UINode>() {
            private final Deque<UINode> pending = new ArrayDeque<>();

            {
                pending.push(UINode.this);
            }

            private void push(UINode node) {
                List<UINode> kids = node.composedChildren();
                for (int i = kids.size() - 1; i >= 0; i--) pending.push(kids.get(i));
            }

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public UINode next() {
                if (pending.isEmpty()) throw new NoSuchElementException();
                UINode next = pending.pop();
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
    public static UINode retarget(UINode target, @Nullable UINode relativeTo) {
        UINode at = target;
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
        UIDocument doc = document;
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
            for (UINode node : composedSubtree()) node.invalidateStyleMatch();
        }
        // display: none is a structural fact -- a box exists or it does not.
        if (property == LayoutProperties.DISPLAY) structureChanged();
        // RESIZE IS AMBIENT, like overflow making any element a scroll container. The engine says WHEN
        // the handles should exist; the widget layer supplies them. @see ResizeHandles
        if (property == StylePropertyRegistry.RESIZE) {
            ResizeHandles.apply(this, (Resize) newValue);
        }
    }

    /**
     * A press landed inside this scope and a modal ate it — this node decides what that means.
     *
     * <p>Called on the scope the modal BLOCKS, not on the modal, because the interesting answers
     * belong to the scope: a window raises itself and flashes the dialog responsible, which is what
     * Windows does and the only way a modally blocked window is distinguishable from a hung one. The
     * engine cannot do either itself — it may not name a compositor or a dialog — so it reports the
     * press and the modal that absorbed it, and layers above decide.</p>
     *
     * <p>Default is to do nothing, which is right for a scope with nothing to raise.</p>
     *
     * @param modal the modal that absorbed the press
     */
    public void pressBlocked(UINode modal) {
    }

    /**
     * Asks the engine to re-run selector matching for this node on the next style pass.
     *
     * <p>A FROZEN node matches nothing: it is not live, so re-matching is work for a subtree nobody
     * can see, and its state cannot have moved in a way anyone reads.</p>
     */
    public final void invalidateStyleMatch() {
        if (frozen) return;
        StyleEngine engine = styleEngine();
        if (engine == null) return;
        markSubtreeDirty(engine);
    }

    /**
     * Marks this node and everything a rule could reach THROUGH it.
     *
     * <p><b>Descendants too, and the old engine said why.</b> A descendant selector can key off this
     * node's own state or classes — {@code checkbox:checked .__mark__},
     * {@code .__configurator-group__.__collapsed__ > .__content__} — so a change here decides
     * which rules apply further down. Marking only this node leaves every descendant holding the
     * match it made under the PREVIOUS state, permanently.</p>
     *
     * <p>It is not a subtle failure. A {@code ConfiguratorGroup} folds by adding a class and letting
     * the sheet set {@code display: none} on its content; without the walk the class went on, the
     * group re-matched, and the content kept {@code display: flex} — a foldout that would not
     * fold, with every observable correct. M6.1 added the SHADOW half of this walk for {@code ::part}
     * rules and it REPLACED the light half rather than joining it, which is why this was latent from
     * 5.2 until the first widget whose LAYOUT depended on such a rule.</p>
     *
     * <p>Frozen subtrees are skipped, as {@link #invalidateStyleMatch} skips a frozen node: a frozen
     * subtree matches nothing until it thaws, and thawing re-matches it.</p>
     */
    private void markSubtreeDirty(StyleEngine engine) {
        engine.markDirty(this);
        invalidateExposedParts(engine);
        for (UINode child : children) {
            if (!child.frozen) child.markSubtreeDirty(engine);
        }
    }

    /**
     * Re-matches this node's own shadow parts, because a {@code ::part} rule is indexed under the
     * <b>host</b>.
     *
     * <p>{@code checkbox:checked::part(mark)} is a rule about the mark whose every selectable input —
     * the type, the classes, the state — belongs to the checkbox. So when the host re-matches, the
     * parts have to as well: nothing about the mark itself changed, and without this the mark keeps
     * the styles it matched when the host was last in some other state.</p>
     *
     * <p>It presents as the widget being unstyled in exactly one state, which is the failure the old
     * engine records three times over — once each for {@code :checked}, {@code :disabled} and
     * {@code :hover}, once per widget that met it, and each time repaired with a class the widget
     * flipped itself. Here it is the engine's business: a part is not something a widget should have
     * to remember to invalidate.</p>
     *
     * <p>Only nodes carrying a {@code part} name are marked — an unexposed node in a shadow tree is
     * unreachable from outside by construction, so nothing about the host can have changed what
     * matches it. A nested shadow root ends the walk: reaching into one needs {@code exportparts},
     * which does not exist yet, so there is nothing there for an outer rule to match.</p>
     */
    private void invalidateExposedParts(StyleEngine engine) {
        if (shadowRoot == null) return;
        markExposedParts(shadowRoot, engine);
    }

    private static void markExposedParts(UINode at, StyleEngine engine) {
        for (UINode child : at.children) {
            if (!child.frozen && !child.get(Attribute.PART).isEmpty()) engine.markDirty(child);
            if (child.shadowRoot == null) markExposedParts(child, engine);
        }
    }

    // ── Interaction state: the services write it, the cascade reads it (5.5) ─

    /** The input service's. A change is a pseudo-class change, so it re-matches. */
    public final void setHovered(boolean value) {
        if (hovered != value) { hovered = value; invalidateStyleMatch(); }
    }

    /** The input service's. */
    public final void setPressed(boolean value) {
        if (pressed != value) { pressed = value; invalidateStyleMatch(); }
    }

    /**
     * The focus service's. {@code visible} is what {@code :focus-visible} reads: a ring, which
     * keyboard and programmatic focus earn and a click does not — unless the node takes typing,
     * where a caret alone is a weak affordance.
     */
    public final void setFocused(boolean value, boolean visible) {
        setFocused(value);
        setFocusVisible(value && visible);
    }

    /**
     * Focus, and with it the ring -- a bare {@code setFocused} is PROGRAMMATIC by definition, and
     * programmatic focus rings, exactly as {@code requestFocus} does. Only the pointer path suppresses
     * it, and that goes through {@link #setFocused(boolean, boolean)}.
     *
     * <p><b>The ring cannot outlive the focus.</b> Leaving {@code focusVisible} alone here let a blurred
     * node keep {@code :focus-visible}, so an outline stayed on something that no longer had focus --
     * and nothing would clear it until that node was focused and blurred again through the service.</p>
     */
    public final void setFocused(boolean value) {
        if (focused != value) { focused = value; invalidateStyleMatch(); }
        setFocusVisible(value);
    }

    public final void setFocusVisible(boolean value) {
        if (focusVisible != value) { focusVisible = value; invalidateStyleMatch(); }
    }

    /** The focus service's: an ancestor of the focus owner. */
    public final void setFocusWithin(boolean value) {
        if (focusWithin != value) { focusWithin = value; invalidateStyleMatch(); }
    }

    // ── Focus policy, typing, chords ─────────────────────────────────────────

    /** Whether and how this node takes focus. */
    public final FocusPolicy focusPolicy() {
        return get(Attribute.FOCUS_POLICY);
    }

    public final UINode setFocusPolicy(FocusPolicy policy) {
        return set(Attribute.FOCUS_POLICY, policy);
    }

    /**
     * Whether typing goes into this node — a text field, an editor. Space is then a character
     * rather than an activation, and focusing it rings however it was focused.
     */
    public boolean consumesTextInput() {
        return false;
    }

    /**
     * Whether this node wants a MODIFIED chord for itself, before the keymap sees it.
     *
     * <p>The inverse of the old engine's yield lists: a widget stated which chords it would GIVE UP
     * ({@code TextEditor}'s own comment says the native keys must yield to a modified chord) and
     * forgetting one — Tab — made {@code Ctrl+Tab} indent a line while the window switcher never
     * heard the chord, with nothing failing, because an indent is a perfectly good thing for Tab to
     * do. A widget knows what it wants; it cannot know what every host might bind.</p>
     */
    public boolean claimsChord(int key, int modifiers) {
        return false;
    }

    // ── The attribute-backed state, under the names the widget layer already uses ──
    //
    // Each is one line over `set`, and each keeps a family of call sites mechanical for M6: 171 for
    // hit-test, 74 for `setDisplayed`, and every `setEnabled` in the layer. The attribute is the
    // storage and the method is the door -- a widget should not have to know which key it is.

    /** {@code :disabled} when false, and the input service refuses it. */
    public UINode setEnabled(boolean enabled) {
        return set(Attribute.ENABLED, enabled);
    }

    /** Whether hit-testing may land here. Subtree-wide, like {@code pointer-events: none}. */
    public UINode setHitTest(boolean hitTest) {
        return set(Attribute.HIT_TEST, hitTest);
    }

    public final boolean isHitTest() {
        return get(Attribute.HIT_TEST);
    }

    /** The HTML {@code inert} attribute: keeps its box, stops being interactive. Subtree-wide. */
    public UINode setInert(boolean inert) {
        return set(Attribute.INERT, inert);
    }

    public final boolean isInertAttribute() {
        return get(Attribute.INERT);
    }

    /**
     * Shown or not — the old engine's {@code setDisplayed}, which wrote {@code display} at
     * {@code IMPORTANT} from 74 sites. @see Attribute#HIDDEN
     */
    public UINode setDisplayed(boolean displayed) {
        return set(Attribute.HIDDEN, !displayed);
    }

    public final boolean isDisplayed() {
        return !get(Attribute.HIDDEN);
    }

    /** @see Attribute#SCROLL_EXEMPT */
    public UINode setScrollExempt(boolean exempt) {
        return set(Attribute.SCROLL_EXEMPT, exempt);
    }

    public final boolean isScrollExempt() {
        return get(Attribute.SCROLL_EXEMPT);
    }

    // ── Class helpers ────────────────────────────────────────────────────────

    /**
     * Swaps whichever class starting with {@code prefix} is present for {@code next} (or none).
     *
     * <p>The recycled-row rule as a method: a template is a different row every time a view reuses
     * it, so ADDING {@code filetype-java} without removing {@code filetype-md} leaves both on the
     * node and the cascade resolves whichever happens to win — which reads as a random colour rather
     * than a stale class.</p>
     */
    public final UINode swapPrefixedClass(String prefix, @Nullable String next) {
        List<String> stale = new ArrayList<>(1);
        for (String c : classes()) {
            if (c.startsWith(prefix) && !c.equals(next)) stale.add(c);
        }
        for (String c : stale) removeClass(c);
        if (next != null) addClass(next);
        return this;
    }

    /**
     * Makes {@code wanted} this node's only light child, doing nothing if it already is.
     *
     * <p>The point is the no-op: a container that rebuilds its content on every refresh would
     * otherwise detach and re-attach the same node, which is a removal and an insertion on the wire
     * and a lifecycle round trip for a tree nothing changed about.</p>
     */
    public final UINode setOnlyChild(@Nullable UINode wanted) {
        if (childCount() == 1 && children().get(0) == wanted) return this;
        removeAll();
        if (wanted != null) append(wanted);
        return this;
    }

    // ── Querying: the light tree, as on the web ──────────────────────────────

    /** The first light descendant matching {@code selector}, in document order, or null. */
    @Nullable
    public final UINode querySelector(String selector) {
        return NodeQueries.querySelector(this, selector, false);
    }

    /** Every light descendant matching {@code selector}, in document order. */
    public final List<UINode> querySelectorAll(String selector) {
        return NodeQueries.querySelectorAll(this, selector, false);
    }

    /**
     * The first light descendant with this exact id, or null.
     *
     * <p>Not final: {@link UIDocument} answers it from its id INDEX instead, which is a map lookup
     * where this is a walk — and a document is where the question is nearly always asked.</p>
     */
    @Nullable
    public UINode getElementById(String id) {
        return NodeQueries.getElementById(this, id, false);
    }

    public final List<UINode> getElementsByClassName(String className) {
        return NodeQueries.getElementsByClassName(this, className, false);
    }

    /** {@link #querySelector} typed, or null when nothing matched or the match is another kind. */
    @Nullable
    public final <T extends UINode> T find(String selector, Class<T> type) {
        UINode found = querySelector(selector);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    /** {@link #find}, but a miss is a programming error rather than a null to carry around. */
    public final <T extends UINode> T require(String selector, Class<T> type) {
        T found = find(selector, type);
        if (found == null) {
            throw new IllegalStateException("No " + type.getSimpleName() + " matches '" + selector + "' under " + this);
        }
        return found;
    }

    // ── Commands and keys ────────────────────────────────────────────────────

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
    private static final Set<Class<?>> COMMANDS_REGISTERED = ConcurrentHashMap.newKeySet();

    void runCommandHooks() {
        if (COMMANDS_REGISTERED.add(getClass())) registerCommands(CommandRegistry.global());
        bindKeys();
    }

    // ── Default actions ──────────────────────────────────────────────────────

    /**
     * A widget's OWN behaviour for an event — what {@code preventDefault()} suppresses.
     *
     * <p>Fires in the TARGET phase only, and only when nothing called {@code preventDefault()}. That
     * is the whole distinction from an ordinary listener: a caller who attaches to {@code onMouseUp}
     * is watching, while a widget's activation IS the event's default action, and a listener that
     * cancels it must be able to.</p>
     */
    protected final <T extends UIEvent> void attachDefaultListener(
            EventListenerGroup<UINode, T> group, UIEvent.Listener<UINode, T> defaultAction) {
        group.attachDefaultListener(defaultAction);
    }

    // ── Dismissal ────────────────────────────────────────────────────────────

    /**
     * The <b>close-watcher</b> hook: something asked this node to close. Returns whether it did.
     *
     * <p>A general node hook rather than something wired only to a dialog, because the web's
     * {@code CloseWatcher} is a general primitive — a modal, a popover, a window frame and a mode all
     * answer it, and the cascade in {@link com.crystalgui.ui.service.Dismiss} asks whichever is on
     * top. Returning false means "not mine", and Escape carries on down the stack.</p>
     */
    public boolean requestClose() {
        return false;
    }

    /**
     * What opened this popover, if anything — read by light dismiss.
     *
     * <p>On the node rather than on a widget class because light dismiss has to consult it for ANY
     * promoted node, and it is the one piece of popover-ness that genuinely must be node-level: the
     * invoker counts as part of its popover, or a dropdown button dies on its own press.</p>
     */
    @Nullable
    public final UINode popoverInvoker() {
        return popoverInvoker;
    }

    public final UINode setPopoverInvoker(@Nullable UINode invoker) {
        this.popoverInvoker = invoker;
        return this;
    }

    @Nullable
    private UINode popoverInvoker;

    // ── Scroll: per NODE, not per box ────────────────────────────────────────

    /**
     * How far this node's content is scrolled. On the node rather than on its box, deliberately: a
     * box is dropped when the subtree is frozen and rebuilt when it comes back, and an offset that
     * died with it would have to be captured and restored — which is the machinery freezing exists
     * to remove. A mirror of this subtree shows the same offset, which is right.
     */
    /**
     * Whether this node is a SCROLL CONTAINER -- CSS's own predicate, which {@code hidden}
     * satisfies and {@code clip} does not.
     *
     * <p>That pair is the whole reason both values exist: {@code overflow: hidden} establishes a
     * container and {@code scrollTop} works on it, it simply shows no bars, while {@code clip} is
     * the value that genuinely refuses to scroll.</p>
     *
     * <p><b>On the node rather than the box</b>, because it is a fact about the STYLE and nothing
     * about the layout -- so it can be asked of a node that has never been laid out, and of one
     * that is hidden and therefore has no box at all. {@code Box} delegates here.</p>
     */
    public final boolean isScrollContainer() {
        Overflow overflow = computedStyle().get(StylePropertyRegistry.OVERFLOW);
        return overflow != null && overflow.isScrollContainer();
    }

    /** Whether the USER may scroll it. {@code hidden} is a container that only code moves. */
    public final boolean allowsUserScrolling() {
        Overflow overflow = computedStyle().get(StylePropertyRegistry.OVERFLOW);
        return overflow != null && overflow.allowsUserScrolling();
    }

    public final float scrollLeft() {
        return scrollLeft;
    }

    public final float scrollTop() {
        return scrollTop;
    }

    /** The box's, once it has clamped against the content it laid out. */
    public final void setScrollOffsets(float left, float top) {
        this.scrollLeft = left;
        this.scrollTop = top;
    }

    /**
     * How far this node's content extends, when the LAID-OUT content is not the answer — a negative
     * return means "read the box", which is the default and what every ordinary node wants.
     *
     * <p>This exists for one shape and it is not an optimisation: a VIRTUALISED view realises a
     * dozen rows of ten thousand, so the boxes under it describe the window rather than the content.
     * A list overrides this with {@code model.size() * rowHeight}, and its scrollbar thumb, its
     * maximum offset and its wheel travel are all correct with nothing else to wire — the old engine
     * spelled the same thing as a {@code getScrollHeight} override and its javadoc says exactly why
     * the children cannot be asked.</p>
     *
     * @param horizontal the width when true, the height when false
     */
    public float scrollExtent(boolean horizontal) {
        return -1f;
    }

    /**
     * Scrolls this node's content, honouring {@code scroll-behavior}.
     *
     * <p>The behaviour is why this is on the node and {@link Box#setScroll} is not: {@code smooth}
     * means a timeline, a timeline needs the document's animation service, and a box should not have
     * to reach for one. A wheel notch and a keyboard page come through here; a scrollbar thumb drag
     * calls the box directly, because easing toward a thumb the hand is already holding makes it lag
     * by the animation's duration.</p>
     */
    public final void scrollTo(float left, float top) {
        Box b = box();
        if (b == null) return;
        UIDocument doc = document();
        ScrollBehavior behaviour = computedStyle().get(StylePropertyRegistry.SCROLL_BEHAVIOR);
        if (doc == null || behaviour != ScrollBehavior.SMOOTH) {
            b.setScroll(left, top);
            return;
        }
        float targetLeft = Math.max(0f, Math.min(b.maxScrollLeft(), left));
        float targetTop = Math.max(0f, Math.min(b.maxScrollTop(), top));
        float fromLeft = scrollLeft;
        float fromTop = scrollTop;
        if (targetLeft == fromLeft && targetTop == fromTop) return;
        float duration = Math.max(0.01f, computedStyle().get(StylePropertyRegistry.SCROLL_DURATION));
        doc.animation().start(duration, ProgressFunctions.Premade.LINEAR, t -> {
            Box live = box();
            if (live != null) {
                live.setScroll(fromLeft + (targetLeft - fromLeft) * t, fromTop + (targetTop - fromTop) * t);
            }
        }, null);
    }

    // ── The fluent style writers ─────────────────────────────────────────────

    /**
     * Configures this node's layout group, writing at whatever origin the group is currently on
     * (INLINE unless a pipeline says otherwise) — {@code node.layout(l -> l.width(100f))}.
     *
     * <p>Kept from {@code UIElement} with the same name and the same shape because 185 call sites in
     * the widget layer read it, and because it is the ordinary way a widget states geometry it owns.
     * It is <b>not</b> a way for a widget to state its own SIZE from measured content — that is what
     * {@link com.crystalgui.ui.box.Measurable} is for, and writing a measured height back into the
     * cascade is exactly the feedback loop the three-tree design removes.</p>
     */
    public UINode layout(Consumer<LayoutGroup> configurator) {
        configurator.accept(getStyle().getLayoutGroup());
        return this;
    }

    /** As {@link #layout}, for the visual group — {@code background}, {@code opacity}, {@code color}. */
    public UINode generalStyle(Consumer<GeneralGroup> configurator) {
        configurator.accept(getStyle().getGeneralGroup());
        return this;
    }

    /** As {@link #layout}, for the whole store, when a caller needs both groups. */
    public UINode style(Consumer<ElementStyle> configurator) {
        configurator.accept(getStyle());
        return this;
    }

    // ── Coordinates ──────────────────────────────────────────────────────────

    /**
     * Converts a point in <b>surface</b> pixels — what the platform reports and what a
     * {@code MouseEvent} carries — into this node's own space, with <b>this box's origin at
     * {@code (0, 0)}</b>.
     *
     * <p>Through the box's world matrix, so every transform and every ancestor's scroll is applied.
     * Returns the point unchanged when the node has no box yet, which is the honest answer for a node
     * nothing has laid out: the alternative is a plausible number computed from an identity matrix.</p>
     *
     * <h3>The origin is at zero, and the old engine's was not</h3>
     *
     * <p>{@code screenToLocal} converted out of surface pixels into the element's layout space and did
     * <em>not</em> subtract the element's own origin, so its answer was an absolute layout coordinate
     * comparable to {@code getRuntimeCache().getX()}. That is a genuinely useful thing and it is also
     * the single most reliably-misread method in the old engine: read as "relative to the element" — as
     * its own name says — it invites adding the origin back, which is what {@code snapZoneAt} did, so
     * the snap zone a drag reported was displaced by however far along the desktop the window was and
     * armed at roughly double speed. It never presents as a conversion, because it is wrong by a
     * different amount every time.</p>
     *
     * <p>Putting the origin at zero removes the question. A caller wanting the absolute coordinate adds
     * {@code box().x()} deliberately, and one wanting the offset within the node — which is nearly
     * everybody: a caret index, a slider fraction, a drag delta — needs nothing.</p>
     */
    public final Vector2f toLocal(float surfaceX, float surfaceY) {
        Box b = box;
        if (b == null) return new Vector2f(surfaceX, surfaceY);
        return Transform2D.apply(b.worldToLocal(), surfaceX, surfaceY);
    }

    /**
     * Whether a point in <b>surface</b> pixels lands inside this node's border box.
     *
     * <p>Geometry only: it says nothing about whether a press would actually be <em>delivered</em>
     * here — {@code hit-test: none}, an inert subtree, a modal in another scope and anything painted
     * over the top all make this true and the hit false. That is what it is for. A slider discarding
     * a synthesized keyboard press whose coordinates are wherever the mouse happens to be is asking
     * "is the pointer over me", not "would the pointer reach me".</p>
     *
     * <p>False for a node with no box, which is a node nothing has laid out — no point is over it.</p>
     */
    public final boolean containsSurfacePoint(float surfaceX, float surfaceY) {
        Box b = box;
        if (b == null) return false;
        Vector2f local = Transform2D.apply(b.worldToLocal(), surfaceX, surfaceY);
        // HALF-OPEN, exactly as `Box.hitTest` is. Two predicates that disagree about a point on the
        // far edge is the engine's own worst failure wearing a smaller hat: a box at x=0 of width 200
        // covers pixels 0..199, and an inclusive bound makes it and its neighbour both claim 200 --
        // so `containsSurfacePoint` says yes where a real click resolves to the other one.
        return local.x() >= 0f && local.y() >= 0f && local.x() < b.width() && local.y() < b.height();
    }

    // ── Reporting a state change ─────────────────────────────────────────────

    /**
     * Reports that this widget's serializable state changed — re-read at flush time.
     *
     * <p><b>Attributed to the nearest node the far side has heard of</b>, which on this engine is the
     * nearest node <em>outside</em> every enclosing shadow tree. A {@code Button}'s label is a
     * {@link com.crystalgui.ui.box.UIText} in the button's shadow root and never travels as a node
     * of its own, so {@code button.setText(...)} has to dirty the <em>Button</em>, whose contract
     * carries the text. The old engine walked out of internal children for exactly this and said so;
     * the shadow boundary is the same boundary, spelled by the engine instead of by a flag.</p>
     *
     * <p>The walk is a loop rather than one hop because shadow trees nest: a widget built out of
     * other widgets puts its parts inside <em>their</em> shadow roots, and stopping at the first host
     * would dirty a node that is itself invisible to a peer.</p>
     *
     * <h3>Why this is not optional, and why nothing would have said so</h3>
     *
     * <p>A shadow subtree inherits a {@code null} observer (see {@link #attachedTo}), which is right
     * for structure and for attributes — a part being inserted or gaining a class is genuinely not
     * something a peer can act on. State is the exception, and without this walk the report is
     * dropped at the boundary: the widget is correct locally, the server's tree is correct, the dirty
     * set is simply never filled, and a viewer keeps whatever the description said forever. That is
     * one step earlier than the defect {@code AGENTS.md} already records as <i>"a dirty set that is
     * cleared without being encoded is indistinguishable from one that was never filled"</i>, and it
     * is silent in the same way — no error at any layer.</p>
     */
    protected final void notifyStateChanged() {
        UINode target = this;
        for (ShadowRoot root = target.containingShadowRoot(); root != null;
                root = target.containingShadowRoot()) {
            target = root.host();
        }
        TreeObserver.Dispatch.stateChanged(target.observer, target);
    }

    // ── Wiring: document, observer, shadow flag ──────────────────────────────

    /** This node was linked under {@code parent}: take its document, its shadowness, its observer. */
    void attachedTo(UINode parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<UINode> inherited = shadow ? null : parent.observer;
        propagate(parent.document(), shadow, inherited);
    }

    /** This node moved within one document: re-derive shadowness and observer, no lifecycle. */
    void relinked(UINode parent) {
        boolean shadow = parent.inShadow || parent instanceof ShadowRoot;
        TreeObserver<UINode> inherited = shadow ? null : parent.observer;
        rewire(shadow, inherited);
    }

    void rewire(boolean shadow, @Nullable TreeObserver<UINode> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        for (UINode child : children) child.rewire(shadow, observer);
        if (shadowRoot != null) shadowRoot.rewire(true, null);
    }

    void propagate(@Nullable UIDocument doc, boolean shadow, @Nullable TreeObserver<UINode> inherited) {
        inShadow = shadow;
        observer = ownObserver != null && !shadow ? ownObserver : inherited;
        boolean joining = doc != null && document == null;
        document = doc;
        if (doc != null && !id.isEmpty()) doc.index(this);
        if (joining) {
            doc.styles().markDirty(this);
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
                if (session != null) session.applyTo(this);
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
        for (UINode child : children) child.propagate(doc, shadow, observer);
        if (shadowRoot != null) shadowRoot.propagate(doc, true, null);
    }

    /** This node left the tree: children first, then this one. */
    void detached() {
        detachedKeepingParent();
        rewire(false, ownObserver);
    }

    void detachedKeepingParent() {
        for (UINode child : children) child.detachedKeepingParent();
        if (shadowRoot != null) shadowRoot.detachedKeepingParent();
        UIDocument doc = document;
        if (doc != null) {
            // HARVESTED HERE AND NOWHERE ELSE. A hidden tool window is DETACHED, so a save afterwards
            // walks a tree this widget is no longer in and writes nothing -- drag the Run panel's
            // divider, close the panel, quit, and the width is gone. This is the last moment the
            // value exists to be read.
            SessionState<?> session = doc.sessionState();
            if (session != null) session.captureFrom(this);
            doc.unindex(this);
            doc.styles().onElementDetached(this);
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
            doc.input().forget(this);
            doc.focus().forget(this);
            doc.animation().forget(this);
            doc.dismiss().forget(this);
            doc.demote(this);
            doc.queue(this::disconnected);
        }
        document = null;
    }

    /** Installs the observer a source over this node reports to; propagates down the light tree. */
    void setObserver(@Nullable TreeObserver<UINode> observer) {
        this.ownObserver = observer;
        TreeObserver<UINode> inherited = parent == null || inShadow ? null : parent.observer;
        rewire(inShadow, inherited);
    }

    // ── Mutation bookkeeping ─────────────────────────────────────────────────

    /** The shadow context whose slot assignment a change under {@code where} may have moved. */
    private static void slotsChanged(@Nullable UINode where) {
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
        return "<" + name + (id.isEmpty() ? "" : " #" + id) + (classes.isEmpty() ? "" : " ." + String.join(".", classes)) + ">";
    }

    // ── Being resized ────────────────────────────────────────────────────────
    //
    // AMBIENT, like scrolling. `resize` is a CSS property that applies to elements generally, so a node
    // grows handles because a sheet says so -- see ResizeHandles -- and everything a drag needs to ask
    // it is answered here, correctly, by default. A widget that positions itself some other way
    // overrides one method; nothing has to be implemented, registered or looked up to be resizable.

    /** On a node whose WIDTH the reader has taken. @see #markUserSized */
    public static final String USER_SIZED_WIDTH_CLASS = "__user-sized-width__";

    /** On a node whose HEIGHT the reader has taken. @see #markUserSized */
    public static final String USER_SIZED_HEIGHT_CLASS = "__user-sized-height__";

    /**
     * The box a resize is kept inside, or {@code null} for no clamp.
     *
     * <p>Only meaningful for an out-of-flow node, which is the same set that has leading handles at
     * all — for anything in flow, {@code left}/{@code top} are a relative nudge rather than a position
     * and there is nothing to clamp against.</p>
     */
    @Nullable
    public UINode resizeContainingBlock() {
        return parent();
    }

    /**
     * Whether a leading edge may move this node's origin — i.e. whether it is out of flow.
     *
     * <p>{@code false} keeps the trailing three handles — bottom, right and the corner — which is
     * CSS's own default grabber, and drops the five that would have to reposition anything. A top or
     * left handle has to shift the origin as it resizes, or the opposite edge would travel instead of
     * staying put, and that only works where {@code left}/{@code top} genuinely place the box. On an
     * in-flow node they are a <em>relative offset</em>: the box slides but its flow position does not,
     * so it silently overlaps the sibling above while everything below carries on as if nothing moved.
     * Refusing the handle is the faithful answer, because <b>CSS {@code resize} never moves a box at
     * all</b> — eight handles are our extension, and it applies where it is meaningful.</p>
     *
     * <p><b>It also decides whether a resize is CLAMPED to the containing block</b>, which is the half
     * that is silent when this answers wrongly. Returning {@code true} unconditionally — as the port
     * first did — bounds an in-flow box by its own parent, and a parent sized BY THAT BOX is exactly as
     * tall as it already is: {@code height = min(desired, parent.height() - y)} is then a no-op, so the
     * bottom edge is dead while the right edge, in a full-width row, still works. It reads as one axis
     * of the widget being unwired rather than as a clamp that should never have run.</p>
     */
    public boolean canMoveResizeOrigin() {
        UIDocument document = document();
        if (document != null && document.isPromoted(this)) return true;
        return computedStyle().get(LayoutProperties.POSITION) == TaffyPosition.ABSOLUTE;
    }

    /**
     * Where this node's left edge sits within its containing block.
     *
     * <p><b>The box, never a written inset.</b> The old engine read the {@code left} inset and answered
     * {@code 0} for {@code auto}, which is the teleport-to-the-corner bug: {@code auto} means "wherever
     * the static position put it", and that is only zero for a box with no inset on that axis at all —
     * a panel anchored by {@code right}/{@code bottom} has an {@code auto} {@code left} and is nowhere
     * near it. {@code Box.x()} is the offset from the host's border-box origin, which is exactly the
     * {@code left} a leading drag has to write back.</p>
     */
    public float resizeOriginLeft() {
        Box box = box();
        return box == null ? 0f : box.x();
    }

    /** @see #resizeOriginLeft */
    public float resizeOriginTop() {
        Box box = box();
        return box == null ? 0f : box.y();
    }

    /**
     * Moves the origin, so a leading edge pins the opposite one.
     *
     * <p><b>The one method a widget overrides.</b> Written at INLINE, the origin CSS gives a user
     * resize, so an author's {@code !important} still wins — and that is right for any node that
     * positions itself with {@code left}/{@code top}. A node whose position is owned by something else
     * says so here instead: an anchored popup hands it to its own move, so placement stops competing
     * for the property, and a window clamps it to the work area.</p>
     */
    public void applyResizeOrigin(float left, float top) {
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.left(left).top(top));
    }

    /**
     * A drag has claimed an axis, and anything that sizes this node itself must stand down.
     *
     * <p><b>Records only; it never clears anything.</b> A resize writes at INLINE, per spec, and a
     * widget that sizes itself writes higher — so without this the handle would look dead on that axis:
     * the drag writes a width and the widget writes over it on the next frame. Withdrawing the widget's
     * own declarations instead would beat an AUTHOR's {@code !important} in the same stroke, because
     * those share one origin bucket.</p>
     *
     * <p><b>The classes ARE the state</b>, which is why this needs no field. A sheet can see it —
     * that is what lets a documentation popup release its width floor and ceiling the moment the
     * reader takes hold of an edge — and state a node flips from its own code belongs on a class
     * rather than a pseudo-class.</p>
     */
    public void markUserSized(boolean width, boolean height) {
        if (width) addClass(USER_SIZED_WIDTH_CLASS);
        if (height) addClass(USER_SIZED_HEIGHT_CLASS);
    }

    /** @see #markUserSized */
    public boolean isUserSizedWidth() {
        return hasClass(USER_SIZED_WIDTH_CLASS);
    }

    /** @see #markUserSized */
    public boolean isUserSizedHeight() {
        return hasClass(USER_SIZED_HEIGHT_CLASS);
    }

    /**
     * Forgets both, for a node that has been given genuinely new content to size to.
     *
     * <p><b>And the size itself, which is the whole of what "clear" means.</b> A resize writes
     * {@code width}/{@code height} at INLINE, so dropping the classes alone leaves that size winning
     * every cascade for the rest of the node's life: the sheet's floor and ceiling come back and
     * neither can be seen underneath it. On screen the node reopens at whatever it was last dragged to
     * — for a documentation popup, the previous SYMBOL's size.</p>
     *
     * <p>Withdrawn rather than written back: writing the resting value would be a second INLINE
     * candidate outranking the sheet for good.</p>
     */
    public void clearUserSizing() {
        if (!isUserSizedWidth() && !isUserSizedHeight()) return;
        removeClass(USER_SIZED_WIDTH_CLASS);
        removeClass(USER_SIZED_HEIGHT_CLASS);
        getStyle().removeCandidates(LayoutProperties.WIDTH, slot -> slot.origin() == StyleOrigin.INLINE);
        getStyle().removeCandidates(LayoutProperties.HEIGHT, slot -> slot.origin() == StyleOrigin.INLINE);
    }

    /**
     * The drag settled on this geometry — called LAST, so an override sees the size the resize
     * actually achieved rather than the one it asked for.
     */
    public void onUserResize(int handleDx, int handleDy, float width, float height) {
    }
}
