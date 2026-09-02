package com.crystalgui.net.mirror;

import java.util.Set;
import com.crystalgui.ui.dom.TreeSource;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * <b>How one tree's nodes go on the wire and come back.</b> The per-tree half of the mirror.
 *
 * <p>{@link TreeSource} answers what a tree IS — identity, structure, contracts. It deliberately says
 * nothing about serialization, because a tree has no business knowing it is on a network. This is the
 * other half: given a node, produce bytes; given bytes, produce or update a node. Between the two,
 * {@link ServerTreeMirror} and {@link ClientTreeMirror} are written once and know neither.</p>
 *
 * <h3>Why both halves are on ONE interface</h3>
 *
 * <p>They could be split — the server only ever encodes and the client only ever applies, so two
 * interfaces would each be used in exactly one place. They are together because <b>the pairing is the
 * thing that must not drift</b>, and it has drifted once already: the server encoded an element's
 * attributes and inline style into every delta, and the client had no branch to apply either. So
 * {@code setEnabled(false)} on a live window was correct on the server, absent on the client, and
 * produced no error at any layer — for as long as identity deltas had existed. Every observable was
 * right; the halves simply did not meet.</p>
 *
 * <p>On one interface that omission is a compile error. Each {@code encodeX} below sits next to the
 * {@code applyX} that reads it, and adding one without the other does not build.</p>
 *
 * <h3>What an implementation is</h3>
 *
 * <p>One per tree kind, not one per window: {@link ElementNodeMirror} covers every {@code UIElement}
 * tree there is, and the {@code ui.dom} node tree gets a second when it lands. That second one, plus
 * its {@code TreeSource}, is the entire cost of moving the mirror to the new engine — which is what
 * this seam exists to make true.</p>
 *
 * @param <N> the tree's node type
 * @param <T> the serialization form (see {@link com.crystalgui.serialization.DynamicOps})
 */
public interface NodeMirror<N, T> {

    // ── Describing a subtree ─────────────────────────────────────────────────

    /**
     * The full description of {@code node} and everything described under it, <b>carrying no ids</b>.
     *
     * <p>What an {@code insert} op carries, and what {@code open()} sends. The absence of ids is what
     * makes it content-addressable: two windows showing the same thing hash the same, so re-opening
     * costs one small packet however large the tree.</p>
     */
    T describe(N node);

    /**
     * The same description with each described node's id <b>written into it</b>.
     *
     * <p>For a viewer joining a window that has already been reshaped. Ids stopped being derivable from
     * position, so a newcomer cannot compute the ones the existing viewers hold — it has to be told
     * them, or every id it derived names a different node and no message lands where it was meant to.
     * A live description hashes to something no pristine one matches, which is correct rather than
     * unfortunate: a reshaped window was never going to share another window's cache entry.</p>
     */
    T describeLive(N node, ToIntFunction<N> idOf);

    /** Rebuilds a subtree from {@link #describe}'s output. */
    N decode(T described);

    /**
     * Rebuilds a subtree from {@link #describeLive}'s output, reporting each node's id to {@code
     * idSink}.
     *
     * <p>Must also accept a PRISTINE description and simply report nothing — the receiver cannot know
     * which kind it was handed until it has looked, and "no ids came back" is how it finds out.</p>
     */
    N decodeLive(T described, ObjIntConsumer<N> idSink);

    // ── The three independent per-node changes ───────────────────────────────
    //
    // Three questions, not one: a widget's authored state, its identity, and its inline style are
    // re-read independently and change for unrelated reasons. They travel in one message keyed by id
    // -- three messages would be three packets for one tick of one panel -- but as separate fields.

    /** A node's authored state, or {@code null} if this kind carries none. */
    @Nullable T encodeState(N node);

    /** Applies {@link #encodeState}'s output. */
    void applyState(T value, N node);

    /** A node's identity: whatever a rule can match on, plus whether it is enabled and hit-testable. */
    @Nullable T encodeAttributes(N node);

    /** Applies {@link #encodeAttributes}'s output. */
    void applyAttributes(T value, N node);

    /**
     * A node's inline style.
     *
     * <p>May answer an EMPTY value but should not answer {@code null} merely because there is none:
     * "no inline style" is a real value that has to travel, since a candidate removed on the server
     * has to be removed here too. A delta carrying only what is present cannot say "this is gone".</p>
     */
    @Nullable T encodeInlineStyle(N node);

    /** Applies {@link #encodeInlineStyle}'s output. */
    void applyInlineStyle(T value, N node);

    // ── Structure, on the receiving side ─────────────────────────────────────

    /**
     * Puts {@code child} under {@code parent} at {@code index} among its DESCRIBED children.
     *
     * <p>Also how a {@code move} is applied — the same instance, reparented, which is the whole reason
     * a move is distinguishable from a remove followed by an insert. A receiver told "removed, and here
     * is an identical one" rebuilds the subtree, losing the instance and everything local to it.</p>
     */
    void insertChild(N parent, N child, int index);

    /** Detaches {@code child} from {@code parent}. */
    void removeChild(N parent, N child);

    /**
     * Which event kinds a session has asked this node to report.
     *
     * <p><b>Per instance, not per kind</b> — {@code NodeContract.reportableEvents} is what a KIND can
     * report, and this is the subset one session asked for. The two are different questions and
     * collapsing them makes every client attach a listener for everything its widgets are capable of
     * and report to nobody.</p>
     *
     * <p>Here rather than on the node because the sessions must not name either engine: the old tree
     * keeps a field, the new one keeps {@code Attribute.REPORTS}, and a mirror is the per-tree adapter
     * that knows which. It also puts the set where the description is written, which is the whole
     * reason it had to stay a field until M2 gave the mirror the description.</p>
     */
    Set<String> reportedEventsOf(N node);

    /** Records that a session wants {@code kind} reported from {@code node}. @see #reportedEventsOf */
    void addReportedEvent(N node, String kind);

    /**
     * Identity over a tree this mirror has just decoded.
     *
     * <p>A client rebuilding a window from a live description needs to number what came back, and
     * numbering is {@link TreeSource}'s job — but only the mirror knows which engine's tree it built.
     * So the factory lives here rather than being a fourth constructor argument every caller would
     * have to keep in step with the mirror it passes beside it.</p>
     */
    TreeSource<N> sourceOver(N root);
}