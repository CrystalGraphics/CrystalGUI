package com.crystalgui.ui.dom;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javax.annotation.Nullable;

/**
 * <b>The seam.</b> A tree, as everything above the engine sees it — identity, structure, contract, and
 * a change stream. {@code plan_ui_rewrite.md} §0.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Two rewrites have to happen and each wanted to be first. The networking rewrite's first step is a
 * document mirror with stable ids; the engine rewrite's ninth is that same mirror over a new node tree.
 * Taken literally the mirror gets written twice, and the second time is months later — so either the
 * live networking defects wait for the engine, or the first mirror is thrown away.</p>
 *
 * <p>This is the resolution: <b>the mirror observes a contract, not a class.</b> Today
 * {@link ElementTreeSource} implements it over {@code UIElement}; at M5 the new {@code ui.dom} node tree
 * implements the same interface natively. The mirror is written once and the engine swap underneath it
 * is a port of <em>this file's implementation</em>, not of the mirror.</p>
 *
 * <h3>Why it is generic in the node type</h3>
 *
 * <p>{@code N} is {@code UIElement} today and a {@code ui.dom} node later, and the two share no
 * supertype — deliberately, since the whole point of the strangler (D2) is that the new tree is not
 * grown out of the old one. Making the node type a parameter is what lets both satisfy one interface
 * without either knowing about the other, and it costs nothing: every consumer is generic in {@code N}
 * too, because a mirror never needs to know what a node <em>is</em>.</p>
 *
 * <h3>Identity is allocated here, and is never positional</h3>
 *
 * <p>The defect that started the whole rewrite is that a networked element's identity <em>was</em> its
 * position in the described tree, so any client-side reparent silently stopped deltas landing.
 * {@link #idOf} allocates on first sight and the number then belongs to that node for as long as the
 * source holds it — through a reparent, a re-describe, or a sibling being inserted before it.</p>
 *
 * <p><b>Ids are per-source, not global.</b> Two sources over two trees both start at their own first
 * id, which is what makes a source the unit a session owns.</p>
 *
 * @param <N> whatever a node is on this side of the seam
 */
public interface TreeSource<N> {

    /** No node has this id, and no allocation ever returns it. */
    int NO_ID = -1;

    // ── Identity ─────────────────────────────────────────────────────────────

    /**
     * This node's stable id, allocating one if it has none.
     *
     * <p>Stable for the life of the source: a node keeps its id across reparenting, across siblings
     * being inserted before it, and across the subtree being re-described. That is the entire
     * difference between this and the positional scheme it replaces.</p>
     */
    int idOf(N node);

    /** This node's id, or {@link #NO_ID} if it has never been allocated one. Allocates nothing. */
    int peekId(N node);

    /** The node holding {@code id}, or null if nothing does. */
    @Nullable
    N byId(int id);

    // ── Identity's LIFECYCLE ─────────────────────────────────────────────────
    //
    // Reading an id and MANAGING one are different halves, and for one milestone this interface
    // declared only the first. That was not a small omission: a mirror's whole job is to allocate a
    // number when a subtree arrives and let it go when the subtree leaves, so with the lifecycle
    // missing there was nowhere for the mirror to stand except on the concrete implementation -- which
    // is exactly what happened, and it quietly voided this seam's reason to exist (§0: "the mirror is
    // written once; the engine swap underneath it is a port of the seam's implementation, not of the
    // mirror"). The four primitives below are what a mirror actually needs.

    /**
     * Numbers a subtree that has just joined, as one contiguous block, and answers its base.
     *
     * <p>Contiguous is what lets one {@code insert} op name a whole subtree as {@code base}+{@code
     * count} rather than carrying an id per node.</p>
     *
     * <p><b>Allocation happens when the op is ENCODED, not when the node is inserted.</b> A caller
     * relying on the opposite has a subtle bug available to it: a subtree added and removed inside one
     * tick has no id at the moment it is removed, so an "is this described?" test asked too early
     * answers no for something that is about to be described.</p>
     */
    int allocate(N subtreeRoot);

    /** Forgets a subtree's ids. An id is never reissued, so a stale reference resolves to nothing. */
    void release(N subtreeRoot);

    /**
     * Records {@code node} under an id the OTHER side chose.
     *
     * <p>The receiving half of a block allocation: an insert carries its base and the receiver numbers
     * its own decoded copy the same way, rather than allocating from a counter of its own. That is what
     * makes two sides agree on ids without either renumbering anything.</p>
     */
    void assignAt(N node, int id);

    /** Drops every id. Used when a numbering is about to be replaced wholesale. */
    void resetIds();

    /**
     * Numbers {@code from}'s subtree in document order from zero, and answers how many it numbered.
     *
     * <p>What a PRISTINE description is numbered by: it carries no ids, so both sides run this same
     * walk and arrive at the same answer -- which is what keeps such a description content-addressed
     * and shareable between windows showing the same thing. From the first structural change onward
     * the numbering is the server's to state, not to re-derive.</p>
     */
    default int assignInDocumentOrder(N from) {
        resetIds();
        int next = 0;
        Deque<N> pending = new ArrayDeque<>();
        pending.push(from);
        while (!pending.isEmpty()) {
            N node = pending.pop();
            assignAt(node, next++);
            List<N> children = childrenOf(node);
            // Pushed in reverse so they pop in order: a pre-order walk, which is what "document order"
            // means and what the other side is going to reproduce.
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
        return next;
    }

    /**
     * How many described nodes {@code from}'s subtree holds, <b>numbering nothing</b>.
     *
     * <p>The integrity check an {@code insert} carries: the sender states what it described and the
     * receiver counts what it decoded. It answers for a subtree that is not in this source at all,
     * which is the case that matters -- a client counts a subtree it has just decoded and not yet
     * attached.</p>
     */
    default int describedCount(N from) {
        int total = 1;
        for (N child : childrenOf(from)) total += describedCount(child);
        return total;
    }

    // ── Structure ────────────────────────────────────────────────────────────

    /** This node's parent within the observed tree, or null at its root. */
    @Nullable
    N parentOf(N node);

    /**
     * The children a peer is told about — the <b>light</b> tree.
     *
     * <p>Not every child: a composite's own scaffolding is rebuilt by its constructor on the far side
     * and never travels, so it is not here. What decides that is {@link #contractOf}, not this method,
     * which is the point — "which children are content" is a fact about a kind of node, and the seam
     * asks the contract for it rather than each node.</p>
     */
    List<N> childrenOf(N node);

    /** Whether {@code node} is in the tree this source was rooted at. */
    boolean contains(N node);

    /** The root this source observes. */
    N root();

    // ── Contract ─────────────────────────────────────────────────────────────

    /**
     * What kind of node this is — its registered name, what it reports, and how its state travels.
     *
     * <p>A contract belongs to a <em>kind</em> and not to an instance, so implementations are expected
     * to cache per class. M1 replaces the body of this with real {@code WidgetContract} constants; its
     * position on the seam is fixed now so that replacement is not also a re-plumbing.</p>
     */
    NodeContract contractOf(N node);

    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Starts reporting changes to {@code observer}, replacing any previous one.
     *
     * <p>One observer per source, not a list: two mirrors over one tree would each renumber it, and
     * they would disagree. A consumer that needs to fan out does so on its own side, where it can say
     * what happens when one of them refuses.</p>
     */
    void observe(@Nullable TreeObserver<N> observer);

    /** The observer currently installed, or null. */
    @Nullable
    TreeObserver<N> observer();

    /** Stops observing and releases the identity table. The source is unusable afterwards. */
    void close();
}
