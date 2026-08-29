package com.crystalgui.net.mirror;

/**
 * The vocabulary of {@code ui/treeOps} — an <b>edit script</b> for a described tree.
 * {@code plan_ui_rewrite.md} M2, network audit Appendix A.
 *
 * <h3>What this replaces, and why the old one could not be fixed in place</h3>
 *
 * <p>{@code ui/treeDelta} sent, per changed place, <em>the whole child list of an anchor, re-described
 * from scratch</em>. Its javadoc justified that by saying a minimal edit script "would have to be
 * computed against what the client has, which the server does not keep" — which was true and beside
 * the point: <b>nothing has to be computed</b>. The tree observer reports every change as it happens,
 * with the parent and the index, which is already an edit script. It just was not being used as one.</p>
 *
 * <p>Three things followed from re-describing, and all three are gone:</p>
 *
 * <ul>
 *   <li><b>Adding one row destroyed every sibling.</b> The client cleared the anchor's children and
 *       decoded the list again, so each sibling came back as a <em>different object</em> — losing its
 *       local state, its listeners, and anything a nested panel was holding.</li>
 *   <li><b>Every delta renumbered the whole tree</b>, on both sides, because ids were positions.</li>
 *   <li><b>A reparent was a destroy and a rebuild</b>, so a subtree that merely moved could not keep
 *       its instance even in principle.</li>
 * </ul>
 *
 * <h3>The ops</h3>
 *
 * <table>
 *   <tr><th>Op</th><th>Carries</th><th>The client</th></tr>
 *   <tr><td>{@link #INSERT}</td>
 *       <td>{@link #PARENT}, {@link #INDEX}, {@link #BASE}, {@link #COUNT}, {@link #NODE}</td>
 *       <td>decodes the subtree, places it, numbers it {@code base .. base+count-1} in described
 *           document order, wires its events and binds any nested panels</td></tr>
 *   <tr><td>{@link #REMOVE}</td><td>{@link #NID}</td>
 *       <td>removes that element wherever it is, and forgets its subtree's ids</td></tr>
 *   <tr><td>{@link #MOVE}</td><td>{@link #NID}, {@link #PARENT}, {@link #INDEX}</td>
 *       <td>reparents the <b>existing instance</b> — the DOM's adoption semantic, so local state and
 *           listeners survive</td></tr>
 * </table>
 *
 * <p>There is deliberately no {@code update}: an identity or inline-style change is not structure, and
 * travels with the state delta instead.</p>
 *
 * <p><b>Order matters and is preserved.</b> Ops are applied in the order they were recorded, because a
 * move can name a parent that an earlier insert in the same batch created.</p>
 */
public final class TreeOps {

    private TreeOps() {
    }

    /** The list of ops, on a {@code ui/treeOps} message. */
    public static final String OPS = "ops";

    /** Which op this is: {@link #INSERT}, {@link #REMOVE} or {@link #MOVE}. */
    public static final String OP = "op";

    public static final String INSERT = "i";
    public static final String REMOVE = "r";
    public static final String MOVE = "m";

    /** The element this op is about — {@code remove} and {@code move}. */
    public static final String NID = "n";

    /** The element it goes under — {@code insert} and {@code move}. */
    public static final String PARENT = "p";

    /** Where among the parent's <b>described</b> children. */
    public static final String INDEX = "x";

    /**
     * The first id of an inserted subtree's block.
     *
     * <p>The subtree occupies {@code base .. base + count - 1}, numbered in described document order —
     * so the far side derives the same ids from its own decoded copy without either side renumbering
     * anything that was already there.</p>
     */
    public static final String BASE = "b";

    /**
     * How many <b>described</b> elements the inserted subtree holds.
     *
     * <p>The integrity check, and it is now <em>per insert</em> rather than a count of the whole tree.
     * A client that decodes a different number is building different structure and is refused — but a
     * composite whose constructor differs by an internal child no longer trips it, because internals
     * are not numbered. That skew used to poison every id after the divergence while being invisible
     * in the description, since internals are never serialized.</p>
     */
    public static final String COUNT = "c";

    /** The pristine description of an inserted subtree. */
    public static final String NODE = "d";
}
