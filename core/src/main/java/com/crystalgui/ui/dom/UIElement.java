package com.crystalgui.ui.dom;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Resize;
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
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.ui.event.EventListenerGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.event.EventTarget;
import com.crystalgui.ui.event.UIEvent;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;

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
public class UIElement extends UINode implements EventTarget, Styleable {

    private String id = "";

    /** The box tree's hook: this node's own box, or null when it has none (5.3). */
    @Nullable
    private Box box;
    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> classesView = Collections.unmodifiableSet(classes);
    private final Map<Attribute<?>, Object> attributes = new HashMap<>();


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
    public final EventListenerGroup.Map<UIElement> events = new EventListenerGroup.Map<>(this);

    // ── The pre-bound groups ─────────────────────────────────────────────────
    //
    // The same sixteen fields UIElement declares, with the same names, because 82 call sites across
    // the widget layer read them (`.onMouseDown` alone is 53) and every one kept is a site the M6
    // codemod never has to touch. A field is also how a reader finds out what a node can be told:
    // `events.getGroup(MouseEvent.Down.class)` is discoverable only if you already know the answer.

    public final EventListenerGroup<UIElement, MouseEvent.Down> onMouseDown = events.getGroup(MouseEvent.Down.class);
    public final EventListenerGroup<UIElement, MouseEvent.Up> onMouseUp = events.getGroup(MouseEvent.Up.class);
    public final EventListenerGroup<UIElement, MouseEvent.Scroll> onMouseScroll = events.getGroup(MouseEvent.Scroll.class);
    public final EventListenerGroup<UIElement, MouseEvent.Move> onMouseMove = events.getGroup(MouseEvent.Move.class);
    public final EventListenerGroup<UIElement, MouseEvent.Enter> onMouseEnter = events.getGroup(MouseEvent.Enter.class);
    public final EventListenerGroup<UIElement, MouseEvent.Leave> onMouseLeave = events.getGroup(MouseEvent.Leave.class);

    public final EventListenerGroup<UIElement, KeyboardEvent.Down> onKeyDown = events.getGroup(KeyboardEvent.Down.class);
    public final EventListenerGroup<UIElement, KeyboardEvent.Up> onKeyUp = events.getGroup(KeyboardEvent.Up.class);

    public final EventListenerGroup<UIElement, DragEvent.Enter> onDragEnter = events.getGroup(DragEvent.Enter.class);
    public final EventListenerGroup<UIElement, DragEvent.Leave> onDragLeave = events.getGroup(DragEvent.Leave.class);
    public final EventListenerGroup<UIElement, DragEvent.Over> onDragOver = events.getGroup(DragEvent.Over.class);
    public final EventListenerGroup<UIElement, DragEvent.Drop> onDrop = events.getGroup(DragEvent.Drop.class);
    public final EventListenerGroup<UIElement, DragEvent.Cancel> onDragCancel = events.getGroup(DragEvent.Cancel.class);

    public final EventListenerGroup<UIElement, FocusEvent.Focus> onFocus = events.getGroup(FocusEvent.Focus.class);
    public final EventListenerGroup<UIElement, FocusEvent.Blur> onBlur = events.getGroup(FocusEvent.Blur.class);

    /**
     * A plain container — the {@code <div>} of this engine, and what the no-argument constructor
     * makes.
     *
     * <p>Named {@code element} rather than {@code node} because it is what a <em>stylesheet</em>
     * writes: this is the tag a type selector matches, and {@code element { }} is the rule an author
     * means. The Java type is a node because a document and a shadow root are ones too.</p>
     */
    public static final Name NAME = Name.of("element");

    public UIElement() {
        this(NAME);
    }

    public UIElement(Name name) {
        super(name);
    }

    // ── The fluent tree writers, narrowed ────────────────────────────────────
    //
    // COVARIANT OVERRIDES, and they exist for one reason: 198 call sites chain off `append`. The
    // base does the work and answers a node, because a shadow root appends too; an element's own
    // append answers an element, so `panel.append(a).append(b)` still reads as it always did. Java
    // dispatches the base's internal `insertAt` call virtually, so a widget that overrides insertAt
    // is still the one that runs.

    @Override
    public UIElement append(UIElement child) {
        super.append(child);
        return this;
    }

    @Override
    public UIElement append(UIElement... nodes) {
        super.append(nodes);
        return this;
    }

    @Override
    public UIElement insertAt(int index, UIElement child) {
        super.insertAt(index, child);
        return this;
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
    public UIElement moveTo(UINode newParent, int index) {
        Objects.requireNonNull(newParent, "parent");
        if (parent == null) {
            newParent.insertAt(index, this);
            return this;
        }
        newParent.refuseAsChild(this);
        Mutation m = beginMutation("moving <" + name + ">");
        try {
            UINode old = parent;
            TreeObserver<UIElement> before = observer;
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
            TreeObserver<UIElement> after = observer;
            UIElement self = this;
            UIElement into = newParent.asElement();
            if (before != null && after == before && into != null) {
                m.observe(() -> TreeObserver.Dispatch.moved(before, self, into, at));
            } else {
                UIElement from = old.asElement();
                if (before != null && from != null) {
                    m.observe(() -> TreeObserver.Dispatch.removed(before, self, from));
                }
                if (after != null && into != null) reportInserted(self, into, at, m);
            }
        } finally {
            m.end();
        }
        return this;
    }

    public final void removeSelf() {
        if (parent != null) parent.remove(this);
    }

    @Override
    public UIElement setOnlyChild(@Nullable UIElement wanted) {
        super.setOnlyChild(wanted);
        return this;
    }

    /** This node and every composed descendant, depth-first, parents before children. */
    public final Iterable<UIElement> composedSubtree() {
        return () -> new Iterator<UIElement>() {
            private final Deque<UIElement> pending = new ArrayDeque<>();

            {
                pending.push(UIElement.this);
            }

            private void push(UIElement node) {
                List<UIElement> kids = node.composedChildren();
                for (int i = kids.size() - 1; i >= 0; i--) pending.push(kids.get(i));
            }

            @Override
            public boolean hasNext() {
                return !pending.isEmpty();
            }

            @Override
            public UIElement next() {
                if (pending.isEmpty()) throw new NoSuchElementException();
                UIElement next = pending.pop();
                push(next);
                return next;
            }
        };
    }

    // ── Identity ─────────────────────────────────────────────────────────────


    public final String id() {
        return id;
    }

    public UIElement setId(String id) {
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





    // ── SettingsScope: a value resolves by walking OUT through the tree ─────────





    /**
     * The node a command or a data lookup was invoked from, or {@code null} when it was not a node.
     *
     * <p>{@code CommandContext.source()} is an {@code Object} on purpose — {@code core.command} may
     * name no UI type — so every consumer that wants to walk the tree has to narrow it. Narrowed once
     * here rather than as an {@code instanceof} chain per command file, which is what the old engine
     * ended up with before it did the same.</p>
     */
    @Nullable
    public static UIElement sourceOf(@Nullable CommandContext context) {
        return context != null && context.source() instanceof UIElement node ? node : null;
    }

    /** @see #sourceOf(CommandContext) */
    @Nullable
    public static UIElement sourceOf(@Nullable DataContext context) {
        return context != null && context.source() instanceof UIElement node ? node : null;
    }

    public final Set<String> classes() {
        return classesView;
    }

    public final boolean hasClass(String className) {
        return classes.contains(className);
    }

    public UIElement addClass(String className) {
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

    public UIElement removeClass(String className) {
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

    public UIElement toggleClass(String className, boolean on) {
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
    public <T> UIElement set(Attribute<T> key, T value) {
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
    
    // ── Shadow tree ──────────────────────────────────────────────────────────

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

    // ── Styleable: what the cascade asks (plan/engine-core.md D5.2) ───────────────────

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
        return parentElement();
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
            for (UIElement node : composedSubtree()) node.invalidateStyleMatch();
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
    public void pressBlocked(UIElement modal) {
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
        // A NODE THAT HAS LEFT THE TREE IS NOT RE-MATCHED, and the walk still descends past it: a
        // detach clears its children's document BEFORE the parent's, so the parent is the one still
        // holding a document while its subtree is already out.
        //
        // Not an optimisation. `Focus.forget` blurs on the way out, which invalidates the match on the
        // detaching frame and marks its whole subtree -- AFTER each of those nodes has already been
        // handed to `StyleEngine.onElementDetached`. They went back into `dirtyMatch` for a tree they
        // had left, the next cascade re-matched them, and `rematch` wrote them into
        // `highlightsByElement`, which is a STRONG map keyed by element and is cleaned only by the
        // detach notification that had already run. So every closed window's whole subtree stayed
        // pinned in the style engine for the life of the document -- invisibly, because re-matching
        // something nobody draws produces no wrong pixel and no error.
        if (document != null) engine.markDirty(this);
        invalidateExposedParts(engine);
        for (UIElement child : children) {
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
        for (UIElement child : at.children) {
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

    public final UIElement setFocusPolicy(FocusPolicy policy) {
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
    public UIElement setEnabled(boolean enabled) {
        return set(Attribute.ENABLED, enabled);
    }

    /** Whether hit-testing may land here. Subtree-wide, like {@code pointer-events: none}. */
    public UIElement setHitTest(boolean hitTest) {
        return set(Attribute.HIT_TEST, hitTest);
    }

    public final boolean isHitTest() {
        return get(Attribute.HIT_TEST);
    }

    /** The HTML {@code inert} attribute: keeps its box, stops being interactive. Subtree-wide. */
    public UIElement setInert(boolean inert) {
        return set(Attribute.INERT, inert);
    }

    public final boolean isInertAttribute() {
        return get(Attribute.INERT);
    }

    /**
     * Shown or not — the old engine's {@code setDisplayed}, which wrote {@code display} at
     * {@code IMPORTANT} from 74 sites. @see Attribute#HIDDEN
     */
    public UIElement setDisplayed(boolean displayed) {
        return set(Attribute.HIDDEN, !displayed);
    }

    public final boolean isDisplayed() {
        return !get(Attribute.HIDDEN);
    }

    /** @see Attribute#SCROLL_EXEMPT */
    public UIElement setScrollExempt(boolean exempt) {
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
    public final UIElement swapPrefixedClass(String prefix, @Nullable String next) {
        List<String> stale = new ArrayList<>(1);
        for (String c : classes()) {
            if (c.startsWith(prefix) && !c.equals(next)) stale.add(c);
        }
        for (String c : stale) removeClass(c);
        if (next != null) addClass(next);
        return this;
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
            EventListenerGroup<UIElement, T> group, UIEvent.Listener<UIElement, T> defaultAction) {
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
    public final UIElement popoverInvoker() {
        return popoverInvoker;
    }

    public final UIElement setPopoverInvoker(@Nullable UIElement invoker) {
        this.popoverInvoker = invoker;
        return this;
    }

    @Nullable
    private UIElement popoverInvoker;

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
    public UIElement layout(Consumer<LayoutGroup> configurator) {
        configurator.accept(getStyle().getLayoutGroup());
        return this;
    }

    /** As {@link #layout}, for the visual group — {@code background}, {@code opacity}, {@code color}. */
    public UIElement generalStyle(Consumer<GeneralGroup> configurator) {
        configurator.accept(getStyle().getGeneralGroup());
        return this;
    }

    /** As {@link #layout}, for the whole store, when a caller needs both groups. */
    public UIElement style(Consumer<ElementStyle> configurator) {
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
        UIElement target = this;
        for (ShadowRoot root = target.containingShadowRoot(); root != null;
                root = target.containingShadowRoot()) {
            target = root.host();
        }
        TreeObserver.Dispatch.stateChanged(target.observer, target);
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
    public UIElement resizeContainingBlock() {
        return parentElement();
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
