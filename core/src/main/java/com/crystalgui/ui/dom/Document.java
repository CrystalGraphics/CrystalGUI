package com.crystalgui.ui.dom;

import com.crystalgui.core.async.UiThread;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.ui.box.BoxTree;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

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
public final class Document extends Node {

    @Nullable
    private volatile Thread frameThread;

    private final Map<String, Node> byId = new HashMap<>();

    /** The cascade over this tree. Sheets are installed here, for the whole tree or for a subtree. */
    private final StyleEngine styles = new StyleEngine(this::allNodes);

    private int depth;
    private boolean notifying;
    private boolean draining;
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private final Set<ShadowRoot> dirtyShadowRoots = new LinkedHashSet<>();
    private final List<Runnable> structureListeners = new ArrayList<>();
    @Nullable
    private BoxTree boxes;

    public Document() {
        super(Name.DOCUMENT);
        this.document = this;
    }

    // ── The frame thread ─────────────────────────────────────────────────────

    /** Claims the current thread as the one that runs frames for this tree. */
    public Document markFrameThread() {
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
    public List<Node> allNodes() {
        List<Node> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(Node at, List<Node> into) {
        into.add(at);
        for (Node child : at.children()) collect(child, into);
        ShadowRoot shadow = at.shadowRoot();
        if (shadow != null) collect(shadow, into);
    }

    // ── Ids ──────────────────────────────────────────────────────────────────

    /** The connected node with this id, or null. The first to claim an id keeps it. */
    @Nullable
    public Node getElementById(String id) {
        return byId.get(id);
    }

    void index(Node node) {
        if (!node.id().isEmpty()) byId.putIfAbsent(node.id(), node);
    }

    void unindex(Node node) {
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
