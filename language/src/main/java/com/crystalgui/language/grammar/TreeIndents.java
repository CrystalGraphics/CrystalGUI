package com.crystalgui.language.grammar;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

import javax.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * {@code indents.scm} — how deep a line is, read from the tree rather than from the character before it.
 *
 * <h3>The dialect, and why this one</h3>
 *
 * <p>{@code indents.scm} is not part of tree-sitter: it is an <em>editor</em> convention, and the two live
 * vocabularies are incompatible. Helix's is smaller and is written down as a specification
 * ({@code @indent}/{@code @outdent}/{@code @align}); Neovim's is larger and is not
 * ({@code @indent.begin}/{@code @indent.end}/{@code @indent.branch}/{@code @indent.dedent}/…).</p>
 *
 * <p><b>Neovim's, because of provenance rather than taste.</b> Upstream grammar repos ship neither file —
 * they ship {@code highlights.scm} and {@code tags.scm}, and the richer families live in editor runtime
 * repos — so this is not a case of matching the author. It is a licence question, and nvim-treesitter is
 * the only source of maintained files for all six of our languages under terms this repository already
 * satisfies (Apache-2.0, the same terms the IntelliJ file icons ship under). A hand-written query family
 * for six grammars is a maintenance line nobody would keep up. See {@code THIRD-PARTY.md}.</p>
 *
 * <h3>What of the dialect is implemented, and what is deliberately not</h3>
 *
 * <table>
 *   <caption>Neovim's indent captures</caption>
 *   <tr><td>{@code @indent.begin}</td><td>the node's children are one level deeper — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.end}</td><td>this row closes a level — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.dedent}</td><td>this row alone is one level out — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.branch}</td><td>{@code else}, {@code case} — one level out — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.zero}</td><td>column zero regardless — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.ignore}</td><td>no indent contribution — <b>implemented</b></td></tr>
 *   <tr><td>{@code @indent.align}</td><td>align under an opening delimiter — <b>not implemented</b></td></tr>
 * </table>
 *
 * <p>Alignment is the one omission and it is a real one: it needs a <em>column</em> rather than a level,
 * which is a different answer than {@link com.crystalgui.text.cursor.IndentationProvider} gives and would
 * mean the provider knowing about tab width and the document's own convention — the two things answering
 * in levels exists to avoid. It affects wrapped argument lists, which fall back to the level-based answer
 * and are indented one level in rather than aligned under the bracket. Stated here rather than left to be
 * discovered as a bug.</p>
 */
final class TreeIndents {

    private TreeIndents() {
    }

    /**
     * The indent level for a line inserted after {@code row}, or {@code -1}.
     *
     * <p>Counted by walking <b>up</b> from the deepest node covering the end of that row, which is the
     * shape Neovim's own algorithm has and is not an approximation of it: an ancestor that opened a level
     * before this row and closes after it is a level this row is inside, and there is no other way to be
     * inside one. Summing {@code @indent.begin} captures top-down would count a node twice whenever two
     * patterns describe the same construct, which several of the vendored files do.</p>
     */
    static int levelsAfterRow(TSTree tree, TSQuery query, String[] captureNames, int row,
                              int rowEndByte) {
        Captures captures = capturesOf(tree, query, captureNames);
        if (captures.isEmpty()) return -1;

        TSNode at = deepestAt(tree, rowEndByte);
        if (at == null || at.isNull()) return -1;

        int levels = 0;
        for (TSNode node = at; node != null && !node.isNull(); node = parentOf(node)) {
            String key = keyOf(node);
            if (captures.ignore.contains(key)) continue;
            // OPENED BEFORE THIS ROW AND STILL OPEN AFTER IT. A node that begins ON this row opens a level
            // the NEXT line is inside, which is exactly what is being asked -- so `<=` rather than `<`.
            if (captures.begin.contains(key)
                    && node.getStartPoint().getRow() <= row
                    && node.getEndPoint().getRow() > row) {
                levels++;
            }
        }
        return Math.max(0, levels);
    }

    /**
     * The indent level for the line at {@code row} itself, or {@code -1}.
     *
     * <p>One level out from {@link #levelsAfterRow} whenever this row <em>closes</em> or <em>branches</em>
     * — a {@code }}, an {@code else}, a {@code case}. That is the whole difference between the two
     * methods, and it is why a caller writing the closing half of a brace pair asks this one.</p>
     */
    static int levelsAtRow(TSTree tree, TSQuery query, String[] captureNames, int row, int rowStartByte) {
        Captures captures = capturesOf(tree, query, captureNames);
        if (captures.isEmpty()) return -1;

        TSNode at = deepestAt(tree, rowStartByte);
        if (at == null || at.isNull()) return -1;

        int levels = 0;
        boolean out = false;
        for (TSNode node = at; node != null && !node.isNull(); node = parentOf(node)) {
            String key = keyOf(node);
            if (captures.zero.contains(key)) return 0;
            if (captures.ignore.contains(key)) continue;
            if (node.getStartPoint().getRow() == row
                    && (captures.end.contains(key) || captures.dedent.contains(key)
                        || captures.branch.contains(key))) {
                out = true;
            }
            if (captures.begin.contains(key)
                    && node.getStartPoint().getRow() < row
                    && node.getEndPoint().getRow() >= row) {
                levels++;
            }
        }
        return Math.max(0, out ? levels - 1 : levels);
    }

    // ── Reading the query ───────────────────────────────────────────────────────────────────────

    /**
     * Every captured node, by kind — computed per call and deliberately not cached.
     *
     * <p>A cache would have to be invalidated on every reparse, and the thing it would save is one query
     * over a tree that is already in memory, run once per Enter keypress. That is a human-scale event; a
     * stale indent is not worth the invalidation path.</p>
     */
    private static Captures capturesOf(TSTree tree, TSQuery query, String[] captureNames) {
        Captures captures = new Captures();
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.setByteRange(0, Integer.MAX_VALUE);
        cursor.exec(query, tree.getRootNode());
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                String name = capture.getIndex() >= 0 && capture.getIndex() < captureNames.length
                        ? captureNames[capture.getIndex()] : null;
                TSNode node = capture.getNode();
                if (name == null || node == null || node.isNull()) continue;
                String key = keyOf(node);
                switch (name) {
                    case "indent.begin" -> captures.begin.add(key);
                    case "indent.end" -> captures.end.add(key);
                    case "indent.dedent" -> captures.dedent.add(key);
                    case "indent.branch" -> captures.branch.add(key);
                    case "indent.zero" -> captures.zero.add(key);
                    case "indent.ignore" -> captures.ignore.add(key);
                    default -> {
                        // `@indent.align` and anything a future dialect adds. Ignored rather than
                        // approximated -- see the class note on why alignment cannot be answered here.
                    }
                }
            }
        }
        return captures;
    }

    /** The smallest node covering {@code byteOffset}. */
    @Nullable
    private static TSNode deepestAt(TSTree tree, int byteOffset) {
        TSNode root = tree.getRootNode();
        if (root == null || root.isNull()) return null;
        int at = Math.max(0, Math.min(byteOffset, Math.max(0, root.getEndByte() - 1)));
        try {
            TSNode found = root.getDescendantForByteRange(at, at);
            return found == null || found.isNull() ? root : found;
        } catch (RuntimeException outOfRange) {
            return root;
        }
    }

    @Nullable
    private static TSNode parentOf(TSNode node) {
        try {
            TSNode parent = node.getParent();
            return parent == null || parent.isNull() ? null : parent;
        } catch (RuntimeException noParent) {
            return null;
        }
    }

    /**
     * A node's identity, as a string.
     *
     * <p>By byte range and type rather than by a node id: the binding exposes no stable id, and two
     * captures of one construct must land in the same bucket or a level is counted twice.</p>
     */
    private static String keyOf(TSNode node) {
        return node.getStartByte() + ":" + node.getEndByte() + ":" + node.getType();
    }

    /** The captured nodes, by what the query called them. */
    private static final class Captures {
        final Set<String> begin = new HashSet<>();
        final Set<String> end = new HashSet<>();
        final Set<String> dedent = new HashSet<>();
        final Set<String> branch = new HashSet<>();
        final Set<String> zero = new HashSet<>();
        final Set<String> ignore = new HashSet<>();

        boolean isEmpty() {
            return begin.isEmpty() && end.isEmpty() && dedent.isEmpty() && branch.isEmpty();
        }
    }
}
