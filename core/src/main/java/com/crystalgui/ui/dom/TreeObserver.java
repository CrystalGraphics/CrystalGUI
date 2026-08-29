package com.crystalgui.ui.dom;

import javax.annotation.Nullable;

/**
 * The change stream a {@link TreeSource} produces — <b>an edit script, not a dirty set</b>.
 * {@code plan_ui_rewrite.md} M0.
 *
 * <h3>What changed from {@code UITreeObserver}, and why</h3>
 *
 * <p>{@code UITreeObserver} has four callbacks: {@code onAttached}, {@code onDetached},
 * {@code onStateDirty}, {@code onIdentityDirty}. Three of the four problems the network audit found in
 * the mirror are visible in that list:</p>
 *
 * <ul>
 *   <li><b>No move.</b> A reparent is an attach and a detach, so a mirror cannot tell "this subtree
 *       moved" from "this subtree was destroyed and an identical one was built". The far side
 *       therefore rebuilds it — losing the client's instance, its scroll position, and anything
 *       half-typed in it. {@link #moved} is the whole reason identity had to become stable first.</li>
 *   <li><b>No index.</b> {@code onAttached} says a node joined and not <em>where</em>, so the receiver
 *       has to be re-told the parent's whole child list to place it. That is what makes adding one row
 *       cost a re-describe of every sibling.</li>
 *   <li><b>{@code onIdentityDirty} was never sent.</b> It fired, the session collected it, and nothing
 *       ever flushed it — so disabling a button after the window opened did nothing on the far side.
 *       Split here into {@link #attributeChanged} and {@link #inlineStyleChanged}, because they are
 *       different payloads and were being conflated into one flag that carried neither.</li>
 * </ul>
 *
 * <h3>State still carries no value</h3>
 *
 * <p>{@link #stateChanged} is deliberately unchanged in that respect, and the reasoning survives: state
 * is re-read at flush time, so ten mutations in a tick collapse to one entry holding the final value
 * rather than ten describing a journey nobody needs.</p>
 *
 * <p>Single-threaded, like everything else in the engine. Implementations must not block, and must not
 * mutate the tree they are being told about — a source is free to be walking it.</p>
 *
 * @param <N> the node type of the source being observed
 */
public interface TreeObserver<N> {

    /**
     * {@code node} joined {@code parent}'s described children at {@code index}.
     *
     * <p>Fires for a whole grafted subtree, parents first, so a receiver can place each node against a
     * parent it has already heard of.</p>
     */
    void inserted(N node, N parent, int index);

    /**
     * {@code node} left {@code parent}.
     *
     * <p>Fires <b>before</b> the parent link is cleared, so an observer can still ask where it was.
     * Reported for the subtree's root only — a receiver removing a node removes what is under it, and
     * saying so per descendant is a message per node for one deletion.</p>
     */
    void removed(N node, N parent);

    /**
     * {@code node} moved to {@code index} under {@code parent} — the same node, still alive.
     *
     * <p>A reparent between two parents is also a move, which is why {@code parent} is stated rather
     * than implied: the receiver has to be able to act on it without re-deriving where the node was.
     * <b>The node keeps its id</b>, and that is what a receiver uses to keep its own instance rather
     * than building a new one.</p>
     */
    void moved(N node, N parent, int index);

    /**
     * An identity attribute changed — id, classes, enabled, focus policy: the inputs to the far side's
     * cascade.
     *
     * <p>Carries no value for the same reason {@link #stateChanged} does not. What it does carry that
     * its predecessor did not is that it is <em>distinct from</em> inline style, so a receiver can send
     * one without re-encoding the other.</p>
     */
    void attributeChanged(N node);

    /** An inline style candidate changed — what a peer cannot re-derive from a stylesheet. */
    void inlineStyleChanged(N node);

    /**
     * A widget's serializable state changed. Re-read it at flush time.
     *
     * <p>Attributed to the nearest node the far side has actually heard of, so a composite's internal
     * label dirties the composite rather than a child that never travels.</p>
     */
    void stateChanged(N node);

    /** Does nothing, for a consumer that wants a subset. */
    class Adapter<N> implements TreeObserver<N> {
        @Override public void inserted(N node, N parent, int index) { }
        @Override public void removed(N node, N parent) { }
        @Override public void moved(N node, N parent, int index) { }
        @Override public void attributeChanged(N node) { }
        @Override public void inlineStyleChanged(N node) { }
        @Override public void stateChanged(N node) { }
    }

    /** Null-safe dispatch, so a source need not branch on having an observer at every call site. */
    final class Dispatch {
        private Dispatch() { }

        public static <N> void inserted(@Nullable TreeObserver<N> to, N node, N parent, int index) {
            if (to != null) to.inserted(node, parent, index);
        }

        public static <N> void removed(@Nullable TreeObserver<N> to, N node, N parent) {
            if (to != null) to.removed(node, parent);
        }

        public static <N> void moved(@Nullable TreeObserver<N> to, N node, N parent, int index) {
            if (to != null) to.moved(node, parent, index);
        }

        public static <N> void attributeChanged(@Nullable TreeObserver<N> to, N node) {
            if (to != null) to.attributeChanged(node);
        }

        public static <N> void inlineStyleChanged(@Nullable TreeObserver<N> to, N node) {
            if (to != null) to.inlineStyleChanged(node);
        }

        public static <N> void stateChanged(@Nullable TreeObserver<N> to, N node) {
            if (to != null) to.stateChanged(node);
        }
    }
}
