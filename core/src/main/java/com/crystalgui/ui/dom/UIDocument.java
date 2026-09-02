package com.crystalgui.ui.dom;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.async.UiThread;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.BoxTree;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.service.Dismiss;
import com.crystalgui.ui.service.Focus;
import com.crystalgui.ui.service.Input;
import com.crystalgui.ui.service.Lifecycle;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * The root of a tree, and the owner of what a tree has exactly one of: the frame thread, the id
 * index, and the lifecycle queue.
 *
 * <h3>The frame thread owns the tree</h3>
 *
 * <p>Every mutation entry asks {@link #require} first, keyed on the thread that
 * {@link #markFrameThread() claimed} this document — per tree, so a document nothing paints is free
 * (every headless test, every server) while one that is being painted refuses everyone but its
 * painter. The old engine had the marker and asserted nowhere; row 235 of the invariants is what that
 * permits, and this is the one line per entry point the audit (§9) asked for, at the boundary rather
 * than in two hundred setters.</p>
 *
 * <h3>Callbacks run after the mutation, never during it</h3>
 *
 * <p>{@code connected}, {@code disconnected} and {@code slotChanged} are queued while a mutation is
 * in progress and dispatched, in the order they were queued, once the outermost mutation has
 * finished — with slot assignment settled first, since a slot's callback is about its assignment.
 * A callback may mutate the tree; that is a new mutation whose own callbacks join the same queue and
 * run in the same drain. What is refused is a mutation from inside an <em>observer notification</em>:
 * the mirror is being told about a change that is still being made, and a second change under it
 * would put the edit script out of order.</p>
 */
public final class UIDocument extends UINode {

    /**
     * Widget state that outlives its widget, or null when the host is not persisting any.
     *
     * <p>Held here rather than by whoever is saving, because the two moments a widget's state can be
     * read are attaching and detaching, and only the document sees those. A host installs one and
     * the tree seeds and harvests itself.</p>
     */
    @Nullable
    private SessionState<?> sessionState;

    public void setSessionState(@Nullable SessionState<?> state) {
        this.sessionState = state;
    }

    @Nullable
    public SessionState<?> sessionState() {
        return sessionState;
    }

    /**
     * This surface's own commands.
     *
     * <p>The counterpart of {@code UIWindow.getCommands()}, and the second half of the seam that kept
     * {@code CommandPalette} out of two batches: a palette lists what the SURFACE can do, and asking
     * the global registry instead would show one window's verbs in another's picker.</p>
     *
     * <p>Its own registry rather than a view of the global one, exactly as the old engine has it —
     * {@code CommandRegistry.global()} is where an application registers what is always true, and
     * this is where a surface adds what is true of itself.</p>
     */
    @Getter
    private final CommandRegistry commands = new CommandRegistry();

    @Nullable
    private volatile Thread frameThread;

    private final Map<String, UINode> byId = new HashMap<>();

    /** The cascade over this tree. Sheets are installed here, for the whole tree or for a subtree. */
    private final StyleEngine styles = new StyleEngine(this::allNodes);

    private int depth;
    private boolean notifying;
    private boolean draining;
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private final Set<ShadowRoot> dirtyShadowRoots = new LinkedHashSet<>();
    private final List<Runnable> structureListeners = new ArrayList<>();
    /** The root of a tree, which owns the frame thread, the id index and the observer. */
    public static final Name NAME = Name.of("document");

    @Nullable
    private BoxTree boxes;
    @Nullable
    private Input input;
    @Nullable
    private Focus focus;
    @Nullable
    private Animation animation;
    @Nullable
    private Lifecycle lifecycle;
    @Nullable
    private Dismiss dismiss;

    public UIDocument() {
        super(NAME);
        this.document = this;
    }

    // ── The frame thread ─────────────────────────────────────────────────────

    /** Claims the current thread as the one that runs frames for this tree. */
    public UIDocument markFrameThread() {
        frameThread = Thread.currentThread();
        return this;
    }

    @Nullable
    public Thread frameThread() {
        return frameThread;
    }

    /** Refuses a caller on any thread but the frame thread, once one has been claimed. */
    public void require(String what) {
        UiThread.require(what, frameThread);
    }

    // ── Style ────────────────────────────────────────────────────────────────

    public StyleEngine styles() {
        return styles;
    }

    /** The document's box tree, built on first use: layout, world matrices, hit-testing (5.3). */
    public BoxTree boxes() {
        if (boxes == null) boxes = new BoxTree(this);
        return boxes;
    }

    // ── Services (5.5) ───────────────────────────────────────────────────────

    /** Platform events in, tree events out: the hit test, the dispatch, the mode stack. */
    public Input input() {
        if (input == null) input = new Input(this);
        return input;
    }

    /** One focus owner, one traversal, one inertness predicate — over focus navigation scopes. */
    public Focus focus() {
        if (focus == null) focus = new Focus(this);
        return focus;
    }

    /** Timelines and the per-frame hooks a tree is allowed to have. */
    public Animation animation() {
        if (animation == null) animation = new Animation();
        return animation;
    }

    /** Freeze, thaw, destroy. */
    // ── The top layer ────────────────────────────────────────────────────────

    /** @see #topLayer() */
    public static final Name TOP_LAYER = Name.of("top-layer");

    @Nullable
    private UINode topLayerNode;

    /** Nodes promoted to the top layer, in the order they were promoted. */
    private final LinkedHashSet<UINode> promoted = new LinkedHashSet<>();

    /**
     * Promotes {@code node} into the top layer — or RAISES it if already promoted.
     *
     * <p><b>Promotion is recorded on the NODE, not written onto its box</b>, and that is the whole
     * reason this method exists beside {@link Box#setHost}. A box is destroyed and rebuilt whenever
     * its subtree is hidden, frozen or restructured, so a host written onto one is lost on the next
     * sync: a popup hidden and reshown would silently come back UNPROMOTED — clipped by its
     * scroller again, and only for a popup that had been closed once, which is the shape of bug that
     * reads as intermittent. The box tree re-applies this set on every sync instead.</p>
     *
     * <p>Imperative rather than a declaration, exactly as the web promotes with
     * {@code showPopover()} and {@code showModal()}: CSS's own {@code overlay} property is set by the
     * UA as a side effect so transitions can observe promotion, and is not the trigger. Re-promoting
     * removes and re-appends, which is the spec's own add algorithm — so "raise this popup" is one
     * idempotent call rather than a remove/add dance every caller has to get right.</p>
     */
    public void promote(UINode node) {
        Objects.requireNonNull(node, "node");
        if (node == this) throw new IllegalArgumentException("the document cannot be promoted");
        topLayerNode();
        promoted.remove(node);
        promoted.add(node);
        fireStructureChanged();
    }

    /** Takes {@code node} out of the top layer, restoring ordinary layout, paint and hit-testing. */
    public void demote(UINode node) {
        if (promoted.remove(node)) fireStructureChanged();
    }

    public boolean isPromoted(UINode node) {
        return promoted.contains(node);
    }

    /** What is promoted, bottom-most first. The box tree's, on every sync. */
    public Collection<UINode> promotedNodes() {
        return Collections.unmodifiableCollection(promoted);
    }

    /** The layer's node if one has been built, never building it. The box tree's, per sync. */
    @Nullable
    public UINode topLayerNodeIfPresent() {
        return topLayerNode;
    }

    /** The layer's node, built on first use. The box tree resolves its box. */
    public UINode topLayerNode() {
        if (topLayerNode == null) {
            topLayerNode = new UINode(TOP_LAYER);
            // THE SIZE OF THE VIEWPORT, because a promoted element's percentages resolve against
            // whatever HOSTS it -- and on this engine that is this node rather than the root.
            //
            // The old engine had no such node: `TopLayer.reparentTaffyNodeToRoot` made a promoted
            // element a child of the ROOT, which is where "a promoted element's containing block is
            // the root" comes from and why `width: 100%` there meant the screen. Introducing a layer
            // between the two quietly broke that: this node had no size of its own, so it shrank to
            // its content, and every promoted element sizing itself as a fraction of the screen got a
            // fraction of ITSELF instead. The window switcher is the clearest case -- it is a
            // full-screen overlay that centres its panel with flexbox precisely so it never has to
            // measure anything, and it collapsed onto its own content in the top-left corner.
            //
            // WRITTEN THROUGH THE NODE, which is an INLINE write, and after the append: a style set on
            // a DETACHED node is a candidate nothing resolves, so the first version of this stayed at
            // `auto` and measured nothing. It is the layer's own geometry rather than a look, so
            // there is nothing here a theme should be overriding.
            append(topLayerNode);
            topLayerNode.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                    .left(0f)
                    .top(0f)
                    .widthPercent(100f)
                    .heightPercent(100f));
        }
        return topLayerNode;
    }

    /**
     * The <b>top layer</b> — CSS Position 4's, and what lets a tooltip escape an
     * {@code overflow: hidden} ancestor.
     *
     * <p>Promote by hosting: {@code node.box().setHost(document.topLayer())}. It is imperative rather
     * than a declaration for the same reason the web promotes with {@code showPopover()} and
     * {@code showModal()} rather than a property — CSS's own {@code overlay} is set by the UA as a
     * side effect so transitions can observe promotion, not as the trigger. Demote with
     * {@code setHost(null)}.</p>
     *
     * <p><b>Order is the whole stacking model here</b>: the box stacks what it hosts by INSERTION and
     * ignores {@code z-index}, per spec, so re-hosting something already promoted RAISES it and
     * "raise this popup" is one idempotent call. @see Box#setStacksByInsertion</p>
     *
     * <p>The layer is a light child of the document and takes NO space — {@code ua/core.css}'s
     * {@code top-layer} rule makes it a zero-sized out-of-flow box. That is not a detail: a full-size
     * layer over the document hit-tests across the whole surface and eats every click that misses a
     * popup, which is the compositor's own rule one level up and this codebase's most-repeated
     * failure. Built on first use, so a document that never promotes anything pays a null field.</p>
     *
     * @return the host box, or null before the first layout has given the layer one
     */
    @Nullable
    public Box topLayer() {
        Box box = boxes().boxOf(topLayerNode());
        if (box != null) box.setStacksByInsertion(true);
        return box;
    }

    /** Whether anything is promoted. Never BUILDS the layer, so a query cannot create one. */
    public boolean hasTopLayerContent() {
        if (topLayerNode == null) return false;
        Box box = boxes().boxOf(topLayerNode);
        return box != null && !box.children().isEmpty();
    }

    /**
     * Where an overlay belongs in the NODE tree — the nearest ancestor of {@code near} that accepts
     * children, or the document.
     *
     * <p>Hosting decides where a promoted thing is DRAWN; this decides where it LIVES, and the two
     * are different questions with different answers. The node parent still settles cascade
     * inheritance (an overlay inherits the colours of the panel it belongs to) and lifetime (it goes
     * away when that panel does) — which is why a context menu passes the thing that was clicked and
     * a command palette passes null.</p>
     */
    public UINode overlayHost(@Nullable UINode near) {
        for (UINode node = near; node != null; node = node.parent()) {
            if (UINodeRegistry.contractFor(node.name()).acceptsDescribedChildren()) return node;
        }
        return this;
    }

    /** Parents an overlay somewhere legal and returns it. Use this rather than a bare append. */
    public <T extends UINode> T addOverlay(T overlay, @Nullable UINode near) {
        if (overlay.parent() == null) overlayHost(near).append(overlay);
        return overlay;
    }

    /** How a thing on top goes away: Escape's stack and light dismiss's. Created on first use. */
    public Dismiss dismiss() {
        if (dismiss == null) dismiss = new Dismiss(this);
        return dismiss;
    }

    public Lifecycle lifecycle() {
        if (lifecycle == null) lifecycle = new Lifecycle(this);
        return lifecycle;
    }

    // ── Document-level data providers ───────────────────────────────────────────────────────────

    private final List<DataProvider> scopeProviders = new ArrayList<>();

    /**
     * Registers a provider {@code DataContext} asks once nothing in the element chain has answered.
     *
     * <p><b>This is the LAST resort by construction, and that is the whole design.</b> A command
     * invoked from inside a window resolves its subject by walking outward from the focused element
     * and finds the frame; one invoked from a taskbar entry finds the entry's own answer; one invoked
     * from the palette with nothing focused finds neither, and the desktop's "the active window" is
     * the only sensible answer left. Registering it here rather than on an element is what keeps it
     * last: an element that answers still wins, so two open windows never both resolve to whatever
     * the desktop named.</p>
     *
     * <p>Document-level rather than a node's own because the consumer is not an ancestor of the
     * things that ask. {@code DataContext} records the case at length — a workbench is a DESCENDANT
     * of the root, so with nothing focused the outward walk never reaches it.</p>
     */
    public void addDataProvider(DataProvider provider) {
        if (provider != null && !scopeProviders.contains(provider)) scopeProviders.add(provider);
    }

    /** Drops a provider. A provider whose owner has left the tree must go, or it answers for a corpse. */
    public void removeDataProvider(DataProvider provider) {
        scopeProviders.remove(provider);
    }

    @Override
    public List<DataProvider> scopeProviders() {
        return Collections.unmodifiableList(scopeProviders);
    }

    /**
     * A whole frame with nothing drawn: the hover is invalidated, motion advances, the cascade
     * settles, layout runs once, and the pointer is diffed against the layout that just ran.
     *
     * <p>Paint sits between {@link #layout} and the input diff for a host that draws; the order here
     * is what makes hover correct on a frame where a reflow moved something under a still pointer.</p>
     */
    public void frame(float deltaSeconds, float width, float height) {
        if (JobScheduler.hasShared()) {
            FrameProfile.count("jobs-busy", JobScheduler.shared().runningCount());
            JobScheduler.shared().drain();
        }
        input().beginFrame();
        animation().tick(deltaSeconds);
        calculateStyle(deltaSeconds);
        layout(width, height);
        // AFTER layout, for the hooks that READ geometry -- see Animation.afterLayout. Before
        // endFrame, so a placement made here is what the hover diff and the paint both see.
        settleAfterLayout(width, height, deltaSeconds);
        input().endFrame();
    }

    /**
     * How many times layout may be re-run for what a post-layout hook wrote. @see #settleAfterLayout
     *
     * <p>Two, because that is what the worst real case needs and it is a fixed point rather than a
     * convergence: pass one creates the box a hook is waiting for, pass two carries the position that
     * hook then writes. A third would find nothing left to do. The bound is there so a hook that
     * answers differently every pass costs a constant rather than the frame.</p>
     */
    public static final int MAX_SETTLE_PASSES = 2;

    /**
     * Re-runs style and layout for whatever the post-layout hooks just wrote.
     *
     * <p><b>The one place in the engine where layout is allowed to feed back into itself, and the
     * reason it has to exist.</b> A node whose position depends on its own measured size cannot be
     * placed before it has been measured — a popup flips and clamps against its own width, a window
     * steps its cascade offset from a measured caption, a dialog centres on half its own height. So
     * they all place themselves in an {@code afterLayout} hook, and with layout running exactly once
     * that write landed on the NEXT frame: for one frame each of them was drawn at its containing
     * block's origin, at full opacity, before jumping to where it belonged.</p>
     *
     * <p>It reached four widgets before it was fixed here, and every one of them worked around it the
     * same way — parking itself off-screen until placed, which is a hack each had to remember, could
     * not be shared, and left the thing invisible for a frame instead of misplaced for one. The old
     * engine had no such problem: {@code calculateLayout} ran {@code while (isLayoutDirty())}, so a
     * placement written from {@code onLayoutChanged} settled inside the same pass. This is that loop,
     * bounded, and run only when something actually asked for it.</p>
     *
     * <p><b>Style is re-run, not only layout.</b> A hook writes a position through the cascade, so the
     * candidate has to be resolved before Taffy can hear about it — and it is re-run with a delta of
     * zero, because transitions have already been ticked for this frame and advancing them again would
     * make an animation run at the number of settle passes times its proper speed.</p>
     *
     * <p><b>The hooks themselves are NOT re-run.</b> They have had their frame; a placer that ran once
     * against measured geometry has its answer, and running it again against the geometry its own write
     * produced is how a fixed point turns into an oscillation.</p>
     */
    private void settleAfterLayout(float width, float height, float deltaSeconds) {
        for (int pass = 0; pass < MAX_SETTLE_PASSES; pass++) {
            // THE HOOKS ARE INSIDE THE LOOP, which is the half that took longest to get right. Running
            // them once and then settling is not enough: a hook reads a box, and the pass that CREATES
            // a box is itself a layout. A modal shown this frame has no box when the hooks first run --
            // its `display` is resolved but the box tree syncs during layout -- so a placer that ran
            // only once found nothing to measure, declined, and placed on the following frame. Which is
            // the bug, one layer further in.
            //
            // ZERO DELTA on every pass but the first. A hook is free to be an animation tick, and time
            // passes once per frame however many times layout runs.
            if (!animation().tickAfterLayout(pass == 0 ? deltaSeconds : 0f)) break;
            int before = boxes().layoutPasses();
            calculateStyle(0f);
            layout(width, height);
            // NOTHING RECOMPUTED, so another round of hooks would be handed the tree they have already
            // seen. Exact rather than a heuristic -- the counter moves only when Taffy actually ran --
            // and it is what keeps a settled frame at ONE extra style pass instead of the full bound.
            if (boxes().layoutPasses() == before) break;
        }
        // AND COMPOSE, for a hook that moved a box without dirtying layout at all -- a compositor
        // transform is layout-free by design, so no pass above would have run for it. Free when
        // nothing moved.
        boxes().composeIfDirty();
    }

    /** Lays the document out at the viewport size -- the box tree's one pass. Run style first. */
    public void layout(float width, float height) {
        boxes().layout(width, height);
    }

    /** A frame's worth of work with nothing drawn: style, then layout. */
    public void update(float width, float height) {
        calculateStyle(0f);
        layout(width, height);
    }

    /** Paints the laid-out document through the shared paint context (5.4). */
    public void paint(CgUiPaintContext ctx) {
        boxes().paint(ctx);
    }

    /**
     * Hears every change to the COMPOSED structure -- an insert, a remove, a move, a shadow root
     * attached, a slot reassigned, a {@code display} toggled -- so a consumer that derives a tree
     * from this one (the box tree) walks it only on frames where something moved.
     */
    public void addStructureListener(Runnable listener) {
        structureListeners.add(listener);
    }

    void fireStructureChanged() {
        for (Runnable listener : structureListeners) listener.run();
    }

    /** Runs the style pass: re-matches what is dirty, ticks transitions. */
    public void calculateStyle(float deltaSeconds) {
        require("the style pass");
        styles.calculateStyle(deltaSeconds);
    }

    /** Every connected node, light and shadow — what a sheet change has to re-match. */
    public List<UINode> allNodes() {
        List<UINode> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(UINode at, List<UINode> into) {
        if (at.isFrozen()) return;   // frozen is not live: it matches nothing
        into.add(at);
        for (UINode child : at.children()) collect(child, into);
        ShadowRoot shadow = at.shadowRoot();
        if (shadow != null) collect(shadow, into);
    }

    // ── Ids ──────────────────────────────────────────────────────────────────

    /**
     * The connected node with this id, or null. The first to claim an id keeps it.
     *
     * <p>Overrides {@link UINode#getElementById} with the INDEX rather than the walk — every node
     * that joins registers its id here, so this is O(1) where a subtree query is O(n), and a document
     * is where the question is nearly always asked. It also reaches nodes inside shadow trees, which
     * the light-tree walk deliberately does not: the index is the engine's own bookkeeping rather
     * than a query an author makes, and the mount paths use it to find what they just built.</p>
     */
    @Override
    @Nullable
    public UINode getElementById(String id) {
        return byId.get(id);
    }

    void index(UINode node) {
        if (!node.id().isEmpty()) byId.putIfAbsent(node.id(), node);
    }

    void unindex(UINode node) {
        if (!node.id().isEmpty() && byId.get(node.id()) == node) byId.remove(node.id());
    }

    // ── Mutation bookkeeping ─────────────────────────────────────────────────

    void enter() {
        if (notifying) {
            throw new IllegalStateException("The tree cannot be mutated from inside an observer notification: "
                    + "the change being reported is still being made, and a second change under it would put "
                    + "the edit script out of order. Mutate from a lifecycle callback instead, which runs after.");
        }
        depth++;
    }

    void exit() {
        if (--depth == 0 && !draining) settle();
    }

    void notifyObserver(Runnable notification) {
        boolean was = notifying;
        notifying = true;
        try {
            notification.run();
        } finally {
            notifying = was;
        }
    }

    void queue(Runnable callback) {
        callbacks.add(callback);
    }

    void slotsDirty(ShadowRoot root) {
        dirtyShadowRoots.add(root);
    }

    /** Whether a mutation is in progress — a callback runs with this false. */
    public boolean isMutating() {
        return depth > 0;
    }

    private void settle() {
        draining = true;
        try {
            while (true) {
                if (!dirtyShadowRoots.isEmpty()) {
                    List<ShadowRoot> roots = new ArrayList<>(dirtyShadowRoots);
                    dirtyShadowRoots.clear();
                    for (ShadowRoot root : roots) root.ensureAssigned();
                    continue;
                }
                Runnable next = callbacks.poll();
                if (next == null) break;
                next.run();
            }
        } finally {
            draining = false;
        }
    }

    @Override
    public String toString() {
        return "<document>";
    }
}
