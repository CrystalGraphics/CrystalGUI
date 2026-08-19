package com.crystalgui.text;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable text document stored as a B+ tree of small chunks, with a {@link TextSummary} cached at
 * every node.
 *
 * <p>This is the storage half of P6.1.6. The summary at each node is what makes it more than a rope:
 * because summaries compose, a seek can decide which child to descend into by arithmetic on the summary
 * alone, so <b>offset&rarr;point, point&rarr;offset and "give me line 4000" are all O(log n) against one
 * structure</b>. That is the property the editor is built on — it renders through the virtualised list
 * from 6.1.3, whose hottest question is "which lines are on screen and how tall are they".</p>
 *
 * <h3>Immutable, with structural sharing</h3>
 * <p>Every edit returns a new {@code Rope} and the old one stays valid. Only the nodes along the edited
 * path are rebuilt — everything else is shared by reference, so keeping the previous document costs a
 * handful of nodes rather than a copy. Undo therefore needs no special support from this class: holding
 * the old {@code Rope} <em>is</em> the old document. (The undo stack still stores changes rather than
 * snapshots — see 6.1.9 — but that is a choice about coalescing and serialisation, not a constraint this
 * class imposes.)</p>
 *
 * <h3>Offsets are UTF-16 code units</h3>
 * <p>The unit {@code String.length()} and {@code charAt} already use, so an offset from here can be
 * handed to any {@code CharSequence} in the engine untranslated. See {@link TextSummary} for why the host
 * language decides this.</p>
 *
 * <p><b>A chunk boundary may fall between a surrogate pair only where the caller put it.</b> Bulk
 * construction never splits one — {@link #chunkBoundary} backs off by a character — because a chunk is
 * handed to the shaper as a unit and a lone surrogate is not text. Slicing at an arbitrary offset is the
 * caller's business: the offsets are code units, so cutting mid-pair is representable, and refusing it
 * here would mean this class silently moved a caret the caller had every right to place.</p>
 */
public final class Rope implements CharSequence {

    /**
     * Chunk sizes, in UTF-16 code units.
     *
     * <p>128 matches what Zed settled on (as bytes) and the reasoning carries: large enough that walking
     * within a chunk is a tight loop over a contiguous array rather than pointer-chasing, small enough
     * that an edit rewrites a trivial amount. {@code MIN} is the merge threshold that stops a long run of
     * small edits leaving a tree full of near-empty leaves.</p>
     */
    static final int MAX_CHUNK = 128;
    static final int MIN_CHUNK = 64;

    /** Children per internal node. Small keeps nodes cache-friendly; the tree is shallow regardless. */
    static final int MAX_CHILDREN = 8;

    public static final Rope EMPTY = new Rope(Leaf.EMPTY);

    private final Node root;

    private Rope(Node root) {
        this.root = root;
    }

    // ── Construction ────────────────────────────────────────────────────────────────────────────

    public static Rope of(CharSequence text) {
        if (text == null || text.length() == 0) return EMPTY;
        return new Rope(build(chunk(text)));
    }

    /** Splits text into chunks of at most {@link #MAX_CHUNK}, never through a surrogate pair. */
    private static List<Node> chunk(CharSequence text) {
        List<Node> leaves = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = chunkBoundary(text, i, Math.min(i + MAX_CHUNK, text.length()));
            leaves.add(new Leaf(text.subSequence(i, end).toString()));
            i = end;
        }
        return leaves;
    }

    /** Backs {@code end} off by one when it would cut a surrogate pair in half. */
    private static int chunkBoundary(CharSequence text, int start, int end) {
        if (end > start && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    /** Builds a balanced tree bottom-up from a level of nodes — every leaf ends at the same depth. */
    private static Node build(List<Node> level) {
        if (level.isEmpty()) return Leaf.EMPTY;
        while (level.size() > 1) {
            List<Node> parents = new ArrayList<>((level.size() + MAX_CHILDREN - 1) / MAX_CHILDREN);
            for (int i = 0; i < level.size(); i += MAX_CHILDREN) {
                parents.add(new Internal(level.subList(i, Math.min(i + MAX_CHILDREN, level.size()))));
            }
            level = parents;
        }
        return level.get(0);
    }

    // ── Basic queries ───────────────────────────────────────────────────────────────────────────

    @Override
    public int length() {
        return root.summary.chars();
    }

    /** Lines in the editor sense — a document with no trailing newline still ends in a line. */
    public int lineCount() {
        return root.summary.lineCount();
    }

    public TextSummary summary() {
        return root.summary;
    }

    public boolean isEmpty() {
        return length() == 0;
    }

    @Override
    public char charAt(int offset) {
        if (offset < 0 || offset >= length()) {
            throw new IndexOutOfBoundsException("offset " + offset + " of " + length());
        }
        Node node = root;
        while (node instanceof Internal internal) {
            for (Node child : internal.children) {
                int size = child.summary.chars();
                if (offset < size) {
                    node = child;
                    break;
                }
                offset -= size;
            }
        }
        return ((Leaf) node).text.charAt(offset);
    }

    @Override
    public Rope subSequence(int start, int end) {
        return slice(start, end);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(length());
        appendTo(root, out);
        return out.toString();
    }

    private static void appendTo(Node node, StringBuilder out) {
        if (node instanceof Leaf leaf) {
            out.append(leaf.text);
            return;
        }
        for (Node child : ((Internal) node).children) appendTo(child, out);
    }

    /**
     * The text between two offsets, as a {@code String}.
     *
     * <p><b>Reading a range is not the same operation as carving one out, and the difference is a
     * factor of thirty.</b> {@link #slice} returns a {@code Rope}, so it has to BUILD one: two
     * {@link #split}s, each rebuilding the spine either side of the cut through {@code concat}, and the
     * result is a whole tree of {@code Internal} nodes and {@code TextSummary} records that the caller
     * throws away the instant it calls {@code toString()}. This walks the existing tree and copies
     * characters. Nothing is allocated but the answer.</p>
     *
     * <p>Measured on the editor's scrolling frame: {@code Rope.split} was the single hottest method in
     * the whole application, above every layout and paint call, and it was reached only through
     * {@link #line}. A 20,000-row document scrolled at 30px a frame spent 18ms per frame; the same
     * frame costs about 3ms once line reads stop doing tree surgery. Anything that wants the CHARACTERS
     * of a range belongs here; {@code slice} is for when the answer is genuinely a document — a
     * selection to insert elsewhere, a fragment to concatenate.</p>
     */
    public String text(int start, int end) {
        int from = clampOffset(start);
        int to = Math.max(from, clampOffset(end));
        if (from == to) return "";
        StringBuilder out = new StringBuilder(to - from);
        appendRange(root, from, to, out);
        return out.toString();
    }

    /**
     * Appends {@code [from, to)} of this subtree to {@code out}.
     *
     * <p>Offsets are relative to {@code node}, which is what lets the descent subtract as it goes rather
     * than threading an absolute position. A child is visited only where its span overlaps the request,
     * so the walk touches {@code O(depth + chunks in range)} nodes and reads no character twice.</p>
     */
    private static void appendRange(Node node, int from, int to, StringBuilder out) {
        if (from >= to) return;
        if (node instanceof Leaf leaf) {
            out.append(leaf.text, from, to);
            return;
        }
        int offset = 0;
        for (Node child : ((Internal) node).children) {
            if (offset >= to) return;
            int chars = child.summary.chars();
            int start = Math.max(from - offset, 0);
            int end = Math.min(to - offset, chars);
            if (start < end) appendRange(child, start, end, out);
            offset += chars;
        }
    }

    // ── Coordinate conversion — the reason the summaries exist ───────────────────────────────────

    /**
     * The {@code (row, column)} of a UTF-16 offset, in O(log n).
     *
     * <p>The walk accumulates the summary of everything it skips, so by the time it reaches a leaf the
     * answer is already in that accumulator: {@code newlines} is the row and {@code lastLineChars} is the
     * column. Nothing is counted twice and no subtree is entered that does not contain the offset.</p>
     */
    public TextPoint offsetToPoint(int offset) {
        int target = clampOffset(offset);
        TextSummary before = TextSummary.EMPTY;
        Node node = root;
        while (node instanceof Internal internal) {
            for (Node child : internal.children) {
                int size = child.summary.chars();
                if (target < size) {
                    node = child;
                    break;
                }
                target -= size;
                before = before.add(child.summary);
            }
        }
        Leaf leaf = (Leaf) node;
        TextSummary prefix = before.add(TextSummary.of(leaf.text.substring(0, target)));
        return new TextPoint(prefix.newlines(), prefix.lastLineChars());
    }

    /**
     * The UTF-16 offset of a {@code (row, column)}, clamped into the document and onto the row.
     *
     * <p><b>The column is clamped as a WIDTH, never computed as {@code start + column}.</b>
     * {@link com.crystalgui.text.diagnostic.Diagnostic#onRow} deliberately says
     * {@code Integer.MAX_VALUE} for "to the end of the line, whatever its length is" — and
     * {@code start + Integer.MAX_VALUE} <em>overflows</em> to a negative offset, which the clamp then
     * pulled back to {@code start}. So a whole-line range became a zero-width point at the line's
     * indentation: drawn as a one-character squiggle in the leading whitespace, which reads as the mark
     * being misplaced rather than as the range having collapsed. Row 0 was exempt (its start is 0, so
     * nothing overflowed), which is the worst possible distribution — the first line of every fixture
     * worked.</p>
     */
    public int pointToOffset(TextPoint point) {
        int row = Math.max(0, Math.min(point.row(), lineCount() - 1));
        int start = lineStartOffset(row);
        int end = lineEndOffset(row);
        return start + Math.min(Math.max(0, point.column()), end - start);
    }

    /**
     * The offset just after the {@code row}-th newline — i.e. where that row's text begins.
     *
     * <p>Descends into the first child whose accumulated newline count reaches {@code row}. For row 0
     * that is always the first child, which is why the empty-accumulator case needs no special handling.
     * </p>
     */
    public int lineStartOffset(int row) {
        if (row <= 0) return 0;
        if (row >= lineCount()) return length();

        int remaining = row;
        int offset = 0;
        Node node = root;
        while (node instanceof Internal internal) {
            for (Node child : internal.children) {
                if (remaining <= child.summary.newlines()) {
                    node = child;
                    break;
                }
                remaining -= child.summary.newlines();
                offset += child.summary.chars();
            }
        }
        String text = ((Leaf) node).text;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            if (--remaining == 0) return offset + i + 1;
        }
        return offset + text.length();
    }

    /** The offset of the row's last character — the newline is not part of the row. */
    public int lineEndOffset(int row) {
        if (row < 0) return 0;
        if (row >= lineCount() - 1) return length();
        return lineStartOffset(row + 1) - 1;
    }

    /**
     * A row's text, without its trailing newline.
     *
     * <p>Through {@link #text}, deliberately — see its note. This used to be
     * {@code slice(start, end).toString()}, which allocated an entire rope per call to read one line,
     * and it is called per row per view part per frame.</p>
     */
    public String line(int row) {
        if (row < 0 || row >= lineCount()) return "";
        return text(lineStartOffset(row), lineEndOffset(row));
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    /** The text between two offsets, as its own document. */
    public Rope slice(int start, int end) {
        int from = clampOffset(start);
        int to = Math.max(from, clampOffset(end));
        if (from == 0 && to == length()) return this;
        if (from == to) return EMPTY;
        Node[] afterStart = split(root, from);
        Node[] beforeEnd = split(afterStart[1], to - from);
        return new Rope(beforeEnd[0]);
    }

    /** This document with {@code [start, end)} replaced by {@code text}. */
    public Rope replace(int start, int end, CharSequence text) {
        int from = clampOffset(start);
        int to = Math.max(from, clampOffset(end));
        Node[] atStart = split(root, from);
        Node[] atEnd = split(atStart[1], to - from);
        Node inserted = text == null || text.length() == 0 ? Leaf.EMPTY : build(chunk(text));
        return new Rope(concat(concat(atStart[0], inserted), atEnd[1]));
    }

    public Rope insert(int offset, CharSequence text) {
        return replace(offset, offset, text);
    }

    public Rope delete(int start, int end) {
        return replace(start, end, "");
    }

    public Rope concat(Rope other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        return new Rope(concat(root, other.root));
    }

    private int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, length()));
    }

    // ── Tree algebra ────────────────────────────────────────────────────────────────────────────

    /**
     * Joins two subtrees, keeping every leaf at the same depth.
     *
     * <p>Equal heights make a new parent. Otherwise the shorter side is pushed into the taller one's edge
     * child and the result is spliced back in — which either fits, or overflows and splits, growing the
     * tree by exactly one level. This is what keeps depth logarithmic under repeated editing rather than
     * merely on construction; {@code ropeStaysShallowUnderManyEdits} measures it, because "balanced" is a
     * claim that degrades silently.</p>
     */
    private static Node concat(Node left, Node right) {
        if (left.summary.chars() == 0) return right;
        if (right.summary.chars() == 0) return left;

        if (left.height == right.height) {
            if (left instanceof Leaf a && right instanceof Leaf b
                    && a.text.length() + b.text.length() <= MAX_CHUNK) {
                return new Leaf(a.text + b.text);
            }
            return new Internal(List.of(left, right));
        }

        if (left.height > right.height) {
            Internal parent = (Internal) left;
            Node last = parent.children[parent.children.length - 1];
            Node merged = concat(last, right);
            List<Node> kept = new ArrayList<>(parent.children.length + MAX_CHILDREN);
            for (int i = 0; i < parent.children.length - 1; i++) kept.add(parent.children[i]);
            if (merged.height == last.height) {
                kept.add(merged);
            } else {
                // The join grew a level, so its children belong at THIS level, not one below.
                kept.addAll(List.of(((Internal) merged).children));
            }
            return fromChildren(kept);
        }

        Internal parent = (Internal) right;
        Node first = parent.children[0];
        Node merged = concat(left, first);
        List<Node> kept = new ArrayList<>(parent.children.length + MAX_CHILDREN);
        if (merged.height == first.height) {
            kept.add(merged);
        } else {
            kept.addAll(List.of(((Internal) merged).children));
        }
        for (int i = 1; i < parent.children.length; i++) kept.add(parent.children[i]);
        return fromChildren(kept);
    }

    /**
     * One node from a child list, splitting into a new level when it overflows.
     *
     * <p><b>A single child is still wrapped, never returned bare.</b> Returning it would drop the rebuilt
     * subtree by exactly one level, and {@link #concat}'s two unequal-height branches both read
     * {@code merged.height != edge.height} as "the join grew a level" and cast {@code merged} to
     * {@code Internal}. A join that <em>shrank</em> a level fails that cast instead — a {@code Leaf}
     * cast to an {@code Internal}, thrown out of {@code slice} and so out of {@code Rope.line}.</p>
     *
     * <p>It was reachable from {@code new TextEditor(text)} for ordinary files. {@link #build} groups
     * leaves eight at a time, so any level whose node count is {@code ≡ 1 (mod MAX_CHILDREN)} ends in a
     * one-child {@code Internal} — which is every document of about 8.2 KB, 16.4 KB, and so on.
     * {@code RopeSingleChildLevelTest} pins the sizes that used to throw.</p>
     *
     * <p>The wrap keeps {@code concat}'s postcondition — the result is never shorter than either input —
     * which is what makes those casts safe by construction rather than by luck. It costs one node on a
     * level that had exactly one child, and adds no depth.</p>
     */
    private static Node fromChildren(List<Node> children) {
        if (children.size() <= MAX_CHILDREN) return new Internal(children);
        int half = children.size() / 2;
        return new Internal(List.of(
                new Internal(children.subList(0, half)),
                new Internal(children.subList(half, children.size()))));
    }

    /** {@code {before, after}} — the subtree split at a UTF-16 offset. */
    private static Node[] split(Node node, int offset) {
        if (offset <= 0) return new Node[] { Leaf.EMPTY, node };
        if (offset >= node.summary.chars()) return new Node[] { node, Leaf.EMPTY };

        if (node instanceof Leaf leaf) {
            return new Node[] {
                    new Leaf(leaf.text.substring(0, offset)),
                    new Leaf(leaf.text.substring(offset)) };
        }

        Internal internal = (Internal) node;
        int index = 0;
        int within = offset;
        while (within >= internal.children[index].summary.chars()) {
            within -= internal.children[index].summary.chars();
            index++;
        }
        Node[] parts = split(internal.children[index], within);

        Node before = parts[0];
        for (int i = index - 1; i >= 0; i--) before = concat(internal.children[i], before);
        Node after = parts[1];
        for (int i = index + 1; i < internal.children.length; i++) after = concat(after, internal.children[i]);
        return new Node[] { before, after };
    }

    /**
     * Distance from the root to a leaf.
     *
     * <p>Public only so a test can assert the tree stays shallow under sustained editing. A structure
     * that is balanced on construction and degrades under edits behaves correctly and gets slower, which
     * is the failure mode least likely to be noticed without measuring it.</p>
     */
    public int depth() {
        return root.height;
    }

    // ── Nodes ───────────────────────────────────────────────────────────────────────────────────

    private abstract static class Node {
        final TextSummary summary;
        final int height;

        Node(TextSummary summary, int height) {
            this.summary = summary;
            this.height = height;
        }
    }

    private static final class Leaf extends Node {
        static final Leaf EMPTY = new Leaf("");
        final String text;

        Leaf(String text) {
            super(TextSummary.of(text), 0);
            this.text = text;
        }
    }

    private static final class Internal extends Node {
        final Node[] children;

        Internal(List<Node> children) {
            super(summarise(children), children.get(0).height + 1);
            this.children = children.toArray(new Node[0]);
        }

        private static TextSummary summarise(List<Node> children) {
            TextSummary total = TextSummary.EMPTY;
            for (Node child : children) total = total.add(child.summary);
            return total;
        }
    }
}
