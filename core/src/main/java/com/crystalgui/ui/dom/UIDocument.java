package com.crystalgui.ui.dom;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgraphics.job.CgHostThread;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.core.async.UiThread;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.signal.Connection;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.box.BoxTree;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.service.Dismiss;
import com.crystalgui.ui.service.Focus;
import com.crystalgui.ui.service.PlatformPort;
import com.crystalgui.ui.service.Input;
import com.crystalgui.ui.service.Lifecycle;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * The root of a tree, and the owner of what a tree has exactly one of: the frame thread, the id index, the cascade,
 * the box tree, the services and the lifecycle queue.
 *
 * <pre>{@code
 * UIDocument document = new UIDocument();
 * document.append(root);
 * document.frame(delta, width, height);   // animation, style, layout, input: nothing drawn
 * document.paint(ctx);                    // into a frame its owner opened
 * }</pre>
 *
 * <ul>
 *   <li>The thread that runs the first frame owns the tree: every mutation entry calls {@link #require}, and any
 *       other thread is refused. A tree nothing frames (a test, a server) is free.</li>
 *   <li>{@code connected}, {@code disconnected} and {@code slotChanged} run after the outermost mutation, in queued
 *       order, with slots assigned first. A callback may mutate; an observer notification may not.</li>
 *   <li>A host normally runs a document through a {@link DocumentDriver}, not by calling {@link #frame} itself.</li>
 * </ul>
 */
public final class UIDocument extends UIElement {

    /**
     * Widget state that outlives its widget, or null when the host persists none. Held here because attaching and
     * detaching, the moments a widget's state is read, are only seen by the document.
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
     * This surface's own commands, beside {@code CommandRegistry.global()}: what a palette lists for this surface, so
     * one window's verbs never show in another's picker.
     */
    @Getter
    private final CommandRegistry commands = new CommandRegistry();

    @Nullable
    private volatile Thread frameThread;

    private final Map<String, UIElement> byId = new HashMap<>();

    /** The cascade over this tree. Sheets are installed here, for the whole tree or for a subtree. */
    private final StyleEngine styles = new StyleEngine(this::allNodes);

    private int depth;
    private boolean notifying;
    private boolean draining;
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private final Set<ShadowRoot> dirtyShadowRoots = new LinkedHashSet<>();
    private final List<StructureListener> structureListeners = new ArrayList<>();
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
        // Before any node is styled: services register more than names (a resize handle), and a tree built in
        // process decodes nothing that would bootstrap them. Idempotent.
        UIElementRegistry.bootstrap();
    }

    // ── The frame thread ─────────────────────────────────────────────────────

    /**
     * Claims the current thread as the one that runs frames for this tree. {@link #frame} claims it on the first
     * frame; a test that mutates before any frame calls this to arm the check.
     */
    public UIDocument markFrameThread() {
        frameThread = Thread.currentThread();
        return this;
    }

    /**
     * Gives this tree to {@code sequence}: from now on it is touched only from the sequence's tasks, on whichever pool
     * thread runs them, and {@link #require} asks the sequence rather than a thread. Null hands it back to whichever
     * thread next runs a frame.
     *
     * <pre>{@code
     * UiSequence sequence = UiSequence.create("desktop");
     * document.runOn(sequence);
     * sequence.execute(() -> document.frame(delta, w, h));
     * }</pre>
     */
    public UIDocument runOn(@Nullable UiSequence sequence) {
        this.sequence = sequence;
        frameThread = null;
        return this;
    }

    /** The sequence this tree runs on, or null when a thread owns it. */
    @Nullable
    public UiSequence sequence() {
        return sequence;
    }

    @Nullable
    private volatile UiSequence sequence;

    @Nullable
    private volatile DocumentDriver<?> driver;

    /** What runs this document for its host, or null when nothing was attached. @see DocumentDriver#attach */
    @Nullable
    public DocumentDriver<?> driver() {
        return driver;
    }

    void useDriver(DocumentDriver<?> driver) {
        this.driver = driver;
    }

    // ── Reading the host ─────────────────────────────────────────────────────

    private final Extracts extracts = new Extracts(this);
    /** Answers from other threads, run on this document at the start of its next frame. */
    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();

    /** Registers an extract; the public form is {@link UINode#extract}, which ties it to a node's time in the tree. */
    <T> Connection addExtract(CgHostThread thread, Supplier<T> read, Consumer<T> use) {
        return extracts.add(Objects.requireNonNull(thread, "thread"), Objects.requireNonNull(read, "read"),
                Objects.requireNonNull(use, "use"));
    }

    /** Runs {@code work} on this document at the start of its next frame. From any thread. */
    void post(Runnable work) {
        inbox.add(work);
    }

    /** The extracts' readings for this frame, taken on the calling thread; null when none is registered. */
    @Nullable
    Runnable readExtracts() {
        return extracts.read();
    }

    /** The first frame claims its thread, as does a frame after the claiming thread died. */
    private void claimFrameThread() {
        if (sequence != null) {
            require("A frame");
            return;
        }
        Thread owner = frameThread;
        if (owner == Thread.currentThread()) return;
        if (owner == null || !owner.isAlive()) {
            markFrameThread();
            return;
        }
        require("A frame");
    }

    @Nullable
    public Thread frameThread() {
        return frameThread;
    }

    /** Refuses a caller on any thread but the frame thread, once one has been claimed. */
    public void require(String what) {
        UiSequence owner = sequence;
        if (owner != null) UiThread.require(what, owner);
        else UiThread.require(what, frameThread);
    }

    // ── The running document ─────────────────────────────────────────────────

    private static final ThreadLocal<UIDocument> RUNNING = new ThreadLocal<>();

    /** The document whose frame, input event or task is running on this thread, or null. */
    @Nullable
    public static UIDocument current() {
        return RUNNING.get();
    }

    /** A scope in which this document is {@link #current()}; closing restores the previous one. */
    public interface Running extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Makes this document {@link #current()} on this thread until the scope closes. Scopes nest.
     *
     * <pre>{@code
     * try (UIDocument.Running ignored = document.makeCurrent()) {
     *     document.input().consumeMouseEvent(event);
     * }
     * }</pre>
     */
    public Running makeCurrent() {
        UIDocument previous = RUNNING.get();
        RUNNING.set(this);
        UiThread.enter();
        return () -> {
            UiThread.exit();
            RUNNING.set(previous);
        };
    }

    // ── The platform ─────────────────────────────────────────────────────────

    private PlatformPort platform = PlatformPort.INLINE;

    /** What this document asks of the platform: modifiers, keys, clipboard, sound, cursor. */
    public PlatformPort platform() {
        return platform;
    }

    /** Installs the port a host routes this document's platform calls through. */
    public void usePlatform(PlatformPort port) {
        this.platform = port == null ? PlatformPort.INLINE : port;
    }

    // ── Style ────────────────────────────────────────────────────────────────

    public StyleEngine styles() {
        return styles;
    }

    /** The document's box tree, built on first use: layout, world matrices, hit-testing. */
    public BoxTree boxes() {
        if (boxes == null) boxes = new BoxTree(this);
        return boxes;
    }

    // ── Services ─────────────────────────────────────────────────────────────

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
        if (animation == null) animation = new Animation(() -> driver != null && driver.compositesMotion());
        return animation;
    }

    // ── The top layer ────────────────────────────────────────────────────────

    /** @see #topLayer() */
    public static final Name TOP_LAYER = Name.of("top-layer");

    @Nullable
    private UIElement topLayerNode;

    /**
     * Promoted nodes in the order they were promoted, each against the host it asked for — or {@code this} for the
     * top layer, which may not be built yet when the promotion is made.
     */
    private final LinkedHashMap<UIElement, UIElement> promoted = new LinkedHashMap<>();

    /**
     * Promotes {@code node} into the top layer, or raises it if already promoted: the web's {@code showPopover()}, a
     * call rather than a property.
     *
     * <pre>{@code
     * document.promote(popup);     // above everything, out of every overflow clip
     * document.promote(popup);     // again: raised to the top
     * document.demote(popup);      // back in flow
     * }</pre>
     *
     * <p>Recorded on the node, never on its box: a box is rebuilt whenever its subtree is hidden or restructured, and
     * the box tree re-applies this set on every sync.</p>
     */
    public void promote(UIElement node) {
        promote(node, null);
    }

    /**
     * Promotes {@code node} into {@code host} rather than the top layer: for what belongs above its panel but under a
     * compositor's chrome, as a dialog under the taskbar. Hosting decides the containing block and the paint order
     * together.
     *
     * <pre>{@code
     * document.promote(dialog, document.workArea());
     * }</pre>
     *
     * @param host where to host it, or null (or the document) for the top layer
     */
    public void promote(UIElement node, @Nullable UIElement host) {
        Objects.requireNonNull(node, "node");
        if (node == this) throw new IllegalArgumentException("the document cannot be promoted");
        // The document means the top layer, which is what makes `this` safe as the stored sentinel.
        UIElement into = host == null ? this : host;
        if (into == this) topLayerNode();
        promoted.remove(node);
        promoted.put(node, into);
        fireStructureChanged();
    }

    /** Where {@code node} was promoted to; null for the top layer, and when it is not promoted. */
    @Nullable
    public UIElement promotionHost(UIElement node) {
        UIElement host = promoted.get(node);
        return host == this ? null : host;
    }

    /** Takes {@code node} out of the top layer, restoring ordinary layout, paint and hit-testing. */
    public void demote(UIElement node) {
        if (promoted.remove(node) != null) fireStructureChanged();
    }

    public boolean isPromoted(UIElement node) {
        return promoted.containsKey(node);
    }

    /** What is promoted, bottom-most first. The box tree's, on every sync. */
    public Collection<UIElement> promotedNodes() {
        return Collections.unmodifiableCollection(promoted.keySet());
    }

    /** The layer's node if one has been built, never building it. The box tree's, per sync. */
    @Nullable
    public UIElement topLayerNodeIfPresent() {
        return topLayerNode;
    }

    /**
     * Keeps the top layer the last child, whatever is appended after it: paint and hit-test order read sibling order,
     * and a desktop appended after the layer would draw over every popup and take its clicks. {@code append} funnels
     * through here.
     */
    @Override
    public UIElement insertAt(int index, UIElement child) {
        super.insertAt(index, child);
        if (topLayerNode != null && child != topLayerNode && topLayerNode.parent() == this) {
            List<UIElement> kids = children();
            // moveTo reads its index AFTER the node is taken out, so the post-removal end is size() - 1.
            if (kids.get(kids.size() - 1) != topLayerNode) topLayerNode.moveTo(this, kids.size() - 1);
        }
        return this;
    }

    /** The layer's node, built on first use. The box tree resolves its box. */
    public UIElement topLayerNode() {
        if (topLayerNode == null) {
            topLayerNode = new UIElement(TOP_LAYER);
            // The viewport's size: a promoted element's percentages resolve against this node, its host. Written
            // inline and after the append, since a style set on a detached node resolves nothing.
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
     * The top layer's box: CSS Position 4's, where a promoted node escapes every {@code overflow: hidden} ancestor.
     * Promote with {@link #promote}.
     *
     * <ul>
     *   <li>It stacks what it hosts by insertion and ignores {@code z-index}, per spec, so promoting again raises.</li>
     *   <li>It takes no hit-test space ({@code ua/core.css}'s {@code top-layer} rule): only what is promoted into it
     *       takes clicks.</li>
     * </ul>
     *
     * @return the host box, or null before the first layout has given the layer one
     */
    @Nullable
    public Box topLayer() {
        Box box = boxes().boxOf(topLayerNode());
        if (box != null) box.setStacksByInsertion(true);
        return box;
    }

    /** Whether anything is promoted. Never builds the layer. */
    public boolean hasTopLayerContent() {
        if (topLayerNode == null) return false;
        Box box = boxes().boxOf(topLayerNode);
        return box != null && !box.children().isEmpty();
    }

    /**
     * Where an overlay lives in the node tree: the nearest ancestor of {@code near} that accepts children, or the
     * document. Promotion decides where it draws; this decides what it inherits from and dies with.
     *
     * <pre>{@code
     * document.overlayHost(clicked);   // a context menu: inherits the clicked panel's colours
     * document.overlayHost(null);      // a command palette: the document's
     * }</pre>
     */
    public UIElement overlayHost(@Nullable UIElement near) {
        for (UIElement node = near; node != null; node = node.parentElement()) {
            if (UIElementRegistry.contractFor(node.name()).acceptsDescribedChildren()) return node;
        }
        return this;
    }

    /**
     * The area a floating overlay may travel in: a compositor's work area when one is declared and still in this
     * tree, else the document. A dialog paints above every window but is not dragged over the taskbar.
     */
    public UIElement workArea() {
        return workArea != null && workArea.document() == this ? workArea : this;
    }

    /** Declared by a compositor about its own work area. @see #workArea() */
    public void setWorkArea(@Nullable UIElement area) {
        this.workArea = area;
    }

    @Nullable
    private UIElement workArea;

    /** Parents an overlay somewhere legal and returns it. Use this rather than a bare append. */
    public <T extends UIElement> T addOverlay(T overlay, @Nullable UIElement near) {
        if (overlay.parent() == null) overlayHost(near).append(overlay);
        return overlay;
    }

    /** How a thing on top goes away: Escape's stack and light dismiss's. Created on first use. */
    public Dismiss dismiss() {
        if (dismiss == null) dismiss = new Dismiss(this);
        return dismiss;
    }

    /** Freeze, thaw, destroy. */
    public Lifecycle lifecycle() {
        if (lifecycle == null) lifecycle = new Lifecycle(this);
        return lifecycle;
    }

    // ── Document-level data providers ───────────────────────────────────────────────────────────

    private final List<DataProvider> scopeProviders = new ArrayList<>();

    /**
     * Registers a provider {@code DataContext} asks last, once nothing in the element chain has answered: for an
     * answer whose owner is not an ancestor of what asks, as the desktop's active window for a command run from the
     * palette.
     *
     * <pre>{@code
     * document.addDataProvider(key -> key == WindowFrame.WINDOW_FRAME ? activeWindow : null);   // as Desktop does
     * }</pre>
     *
     * Remove it when its owner leaves the tree, or it answers for something gone.
     */
    public void addDataProvider(DataProvider provider) {
        if (provider != null && !scopeProviders.contains(provider)) scopeProviders.add(provider);
    }

    /** Drops a provider. */
    public void removeDataProvider(DataProvider provider) {
        scopeProviders.remove(provider);
    }

    @Override
    public List<DataProvider> scopeProviders() {
        return Collections.unmodifiableList(scopeProviders);
    }

    /**
     * A whole frame with nothing drawn: the hover is invalidated, motion advances, the cascade settles, layout runs
     * once, and the pointer is diffed against the layout that just ran. When that diff moves the hover, style and
     * layout run once more, so {@code :hover} lands on the right element this frame. The host paints after.
     */
    public void frame(float deltaSeconds, float width, float height) {
        try (Running ignored = makeCurrent()) {
            // On a sequence this is work inside the host's frame, which alone opens one in the trace.
            if (sequence == null) {
                UiTrace.frameBegin();
                runFrame(deltaSeconds, width, height);
            } else {
                long timed = CgTrace.stamp(UiTrace.FRAME);
                runFrame(deltaSeconds, width, height);
                CgTrace.zoneDone(UiTrace.FRAME, "doc:frame", timed);
            }
        }
    }

    private void runFrame(float deltaSeconds, float width, float height) {
        claimFrameThread();
        for (Runnable arrived; (arrived = inbox.poll()) != null; ) arrived.run();
        // With no driver the host calls this on its own thread, which is where an extract reads; a driver reads them itself.
        if (driver == null) {
            Runnable delivery = extracts.read();
            if (delivery != null) delivery.run();
        }
        if (JobScheduler.hasShared()) {
            CgTrace.add(UiTrace.FRAME, "jobs-busy", JobScheduler.shared().pendingCount());
            long drained = CgTrace.stamp(UiTrace.FRAME);
            JobScheduler.shared().drain();
            CgTrace.zoneDone(UiTrace.FRAME, "frame:jobs", drained);
        }
        input().beginFrame();
        // Readings, not counts: a hook re-registered every frame without its twin dropped shows as growth here.
        CgTrace.counter(UiTrace.FRAME, "anim-hooks", animation().hookCount());
        CgTrace.counter(UiTrace.FRAME, "anim-after-layout-hooks", animation().afterLayoutCount());
        long timed = CgTrace.stamp(UiTrace.FRAME);
        animation().tick(deltaSeconds);
        CgTrace.zoneDone(UiTrace.FRAME, "frame:hooks", timed);
        calculateStyle(deltaSeconds);
        timed = CgTrace.stamp(UiTrace.FRAME);
        layout(width, height);
        CgTrace.zoneDone(UiTrace.FRAME, "frame:layout", timed);
        // Before endFrame, so a placement made here is what the hover diff and the paint both see.
        timed = CgTrace.stamp(UiTrace.FRAME);
        settleAfterLayout(width, height, deltaSeconds);
        CgTrace.zoneDone(UiTrace.FRAME, "frame:afterLayout", timed);
        timed = CgTrace.stamp(UiTrace.FRAME);
        boolean hoverMoved = input().endFrame();
        CgTrace.zoneDone(UiTrace.FRAME, "frame:input", timed);
        if (hoverMoved) {
            // The hover moved after the cascade ran, so `:hover` would sit on the element the pointer left for a
            // frame (a recycled list row). It settles too: an enter dispatched above can show a tooltip, and this is
            // the tip's first layout, which its placer needs.
            timed = CgTrace.stamp(UiTrace.FRAME);
            calculateStyle(0f);
            layout(width, height);
            settleAfterLayout(width, height, 0f);
            CgTrace.zoneDone(UiTrace.FRAME, "frame:hover", timed);
        }
    }

    /**
     * How many times layout may re-run for what a post-layout hook wrote: one pass makes the box a hook waits for,
     * the next carries the position it writes. Bounded so a hook that answers differently each pass costs a constant.
     */
    public static final int MAX_SETTLE_PASSES = 2;

    /**
     * Re-runs style and layout for what the post-layout hooks wrote: the one place layout feeds back into itself. A
     * node placed from its own measured size (a popup flipping against its width, a dialog centring on its height)
     * places itself in an {@code afterLayout} hook, and this lands that placement in the same frame.
     *
     * <ul>
     *   <li>Style is re-run at zero delta: transitions were ticked once this frame.</li>
     *   <li>The hooks run on every pass, since the pass that creates a box is itself a layout. A placer is a fixed
     *       point and an unchanged candidate dirties nothing, so the loop ends on its own.</li>
     * </ul>
     */
    private void settleAfterLayout(float width, float height, float deltaSeconds) {
        for (int pass = 0; pass < MAX_SETTLE_PASSES; pass++) {
            // Time passes once a frame, however many passes run.
            if (!animation().tickAfterLayout(pass == 0 ? deltaSeconds : 0f)) break;
            int before = boxes().layoutPasses();
            calculateStyle(0f);
            layout(width, height);
            // Nothing recomputed: another round of hooks would see the tree they already saw.
            if (boxes().layoutPasses() == before) break;
        }
        // For a hook that moved a box without dirtying layout (a compositor transform). Free when nothing moved.
        boxes().composeIfDirty();
    }

    /** Lays the document out at the viewport size: the box tree alone, no cascade. Run style first. */
    public void layout(float width, float height) {
        boxes().layout(width, height);
    }

    /** A frame's worth of work with nothing drawn: style, then layout. */
    public void update(float width, float height) {
        calculateStyle(0f);
        layout(width, height);
    }

    /**
     * Paints the laid-out document into {@code ctx}, a frame its owner opened: the document owns no paint context, and
     * a {@code DocumentDriver}'s view hands its painter one.
     */
    public void paint(CgUiPaintContext ctx) {
        boxes().paint(ctx);
        // Cursor art goes over everything, inside no scissor. @see CursorDecoration
        input().paintCursorDecoration(ctx);
    }

    /**
     * Hears every change to the composed structure (an insert, a remove, a move, a shadow root attached, a slot
     * reassigned, a {@code display} toggled), so a tree derived from this one is walked only on frames where
     * something moved.
     *
     * <pre>{@code
     * document.addStructureListener(where -> {
     *     if (where == null) rebuildEverything();
     *     else rebuildUnder(where);   // only where's own box may have appeared or gone
     * });
     * }</pre>
     */
    public void addStructureListener(StructureListener listener) {
        structureListeners.add(listener);
    }

    /** What {@link #addStructureListener} hears. */
    public interface StructureListener {
        /**
         * @param where the one node whose box may have appeared or gone, when nothing else in the composed tree
         *              moved -- a {@code hidden} or {@code display} toggle; null for any other change
         */
        void structureChanged(@Nullable UIElement where);
    }

    void fireStructureChanged() {
        fireStructureChanged(null);
    }

    void fireStructureChanged(@Nullable UIElement where) {
        for (StructureListener listener : structureListeners) listener.structureChanged(where);
    }

    /** Runs the style pass: re-matches what is dirty, ticks transitions. */
    public void calculateStyle(float deltaSeconds) {
        require("the style pass");
        styles.calculateStyle(deltaSeconds);
    }

    /**
     * Every connected element, light and shadow: what a sheet change re-matches. A shadow root is not an element and
     * is walked through, never collected; frozen subtrees are skipped.
     */
    public List<UIElement> allNodes() {
        List<UIElement> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(UINode at, List<UIElement> into) {
        if (at.isFrozen()) return;   // frozen is not live: it matches nothing
        UIElement self = at.asElement();
        if (self != null) into.add(self);
        for (UIElement child : at.children()) collect(child, into);
        if (self != null && self.shadowRoot() != null) collect(self.shadowRoot(), into);
    }

    // ── Ids ──────────────────────────────────────────────────────────────────

    /**
     * The connected node with this id, or null; the first to claim an id keeps it. From the index rather than a walk,
     * and unlike {@link UIElement#getElementById} it reaches inside shadow trees.
     */
    @Override
    @Nullable
    public UIElement getElementById(String id) {
        return byId.get(id);
    }

    void index(UIElement node) {
        if (!node.id().isEmpty()) byId.putIfAbsent(node.id(), node);
    }

    void unindex(UIElement node) {
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
