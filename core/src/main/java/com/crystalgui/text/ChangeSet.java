package com.crystalgui.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A described edit: what was replaced, and with what. Data, never a closure.
 *
 * <p>This is the second half of P6.1.6 and the piece that turns a {@link Rope} into a buffer. Three
 * things that look unrelated are all the same operation once edits are described rather than performed:
 * </p>
 *
 * <ul>
 *   <li><b>Anchors</b> — a caret, a selection, a diagnostic marker. An anchor is not a stored identity
 *       here; it is a position plus a rule for mapping it, and {@link #mapPos} is the rule.</li>
 *   <li><b>Undo</b> — {@link #invert} produces the opposite edit, so the undo stack stores changes rather
 *       than snapshots and cannot drift from the document the way a stack of closures can.</li>
 *   <li><b>Highlight ranges</b> — {@code ui/text/TextRange} from 6.1.1 is a pair of positions, so a
 *       highlight survives an edit by being mapped rather than recomputed.</li>
 * </ul>
 *
 * <h3>Why not Zed's anchors</h3>
 * <p>Zed's {@code Anchor} identifies the <em>insertion</em> that produced the surrounding text — a
 * Lamport timestamp plus an offset into it — which is why its positions survive concurrent edits from
 * other machines. That property is a consequence of its buffer being a CRDT. We are not building one, so
 * the identity has nowhere to come from, and CodeMirror's mapping model is the right shape instead: less
 * powerful, and exactly as powerful as a single-writer editor needs.</p>
 *
 * <h3>Coordinates</h3>
 * <p><b>Every offset in {@link #changes()} is in the document this set applies to</b>, never the one it
 * produces. Mixing those two up is the classic way to write a change set that composes correctly in tests
 * with one edit and wrongly with two, so it is stated once here and relied on everywhere.</p>
 */
public final class ChangeSet {

    /** What a mapped position should do when the text around it is deleted. */
    public enum MapMode {
        /** Always produce a valid position, collapsing to the edit point. */
        SIMPLE,
        /** Report that the position is gone, for anything that should disappear with its text. */
        TRACK_DELETION
    }

    /** Returned by {@link #mapPos} under {@link MapMode#TRACK_DELETION} when the position was deleted. */
    public static final int DELETED = -1;

    private final List<Change> changes;
    private final int lengthBefore;
    private final int lengthAfter;

    private ChangeSet(List<Change> changes, int lengthBefore) {
        this.changes = Collections.unmodifiableList(changes);
        this.lengthBefore = lengthBefore;
        int delta = 0;
        for (Change change : changes) delta += change.delta();
        this.lengthAfter = lengthBefore + delta;
    }

    // ── Construction ────────────────────────────────────────────────────────────────────────────

    /** An edit that changes nothing, against a document of the given length. */
    public static ChangeSet empty(int documentLength) {
        return new ChangeSet(new ArrayList<>(), documentLength);
    }

    public static ChangeSet of(int documentLength, Change change) {
        List<Change> one = new ArrayList<>(1);
        if (!change.isEmpty()) one.add(change);
        return new ChangeSet(one, documentLength);
    }

    public static ChangeSet replace(int documentLength, int from, int to, String insert) {
        return of(documentLength, new Change(from, to, insert));
    }

    /**
     * Several edits at once, all in the coordinates of the same original document.
     *
     * <p>Sorted and non-overlapping is <b>required, not normalised</b>. Two changes that overlap have no
     * defined combined meaning — whichever the implementation happened to apply second would win — so
     * accepting them would make the result depend on iteration order. A caller that wants sequential
     * edits wants {@link #compose}, which is a different and well-defined thing.</p>
     */
    public static ChangeSet of(int documentLength, List<Change> changes) {
        List<Change> kept = new ArrayList<>(changes.size());
        int previousEnd = 0;
        for (Change change : changes) {
            if (change.from() < previousEnd) {
                throw new IllegalArgumentException(
                        "changes must be sorted and non-overlapping; " + change.from() + " < " + previousEnd
                                + ". For sequential edits use compose().");
            }
            if (change.to() > documentLength) {
                throw new IllegalArgumentException(
                        "change ends at " + change.to() + " past the document length " + documentLength);
            }
            previousEnd = change.to();
            if (!change.isEmpty()) kept.add(change);
        }
        return new ChangeSet(kept, documentLength);
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    public List<Change> changes() {
        return changes;
    }

    public int lengthBefore() {
        return lengthBefore;
    }

    public int lengthAfter() {
        return lengthAfter;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    // ── Applying and inverting ──────────────────────────────────────────────────────────────────

    /**
     * The document this edit produces.
     *
     * <p>Applied back to front, so each change's offsets are still valid when its turn comes — the
     * alternative is tracking a running delta, which is the same arithmetic with somewhere to make a
     * mistake.</p>
     */
    public Rope apply(Rope document) {
        Rope out = document;
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            out = out.replace(change.from(), change.to(), change.insert());
        }
        return out;
    }

    /**
     * The edit that undoes this one — expressed against the document this one <em>produces</em>.
     *
     * <p>Needs the original document, because a change set records what was inserted but not what was
     * removed. That asymmetry is deliberate: carrying the removed text in every change would double the
     * memory of a large deletion for the benefit of the minority of change sets that are ever inverted.
     * </p>
     */
    public ChangeSet invert(Rope documentBefore) {
        List<Change> inverted = new ArrayList<>(changes.size());
        int delta = 0;
        for (Change change : changes) {
            int from = change.from() + delta;
            inverted.add(new Change(from, from + change.inserted(),
                    documentBefore.slice(change.from(), change.to()).toString()));
            delta += change.delta();
        }
        return new ChangeSet(inverted, lengthAfter);
    }

    // ── Mapping positions ───────────────────────────────────────────────────────────────────────

    /** {@link #mapPos(int, int, MapMode)} with {@link MapMode#SIMPLE}. */
    public int mapPos(int position, int assoc) {
        return mapPos(position, assoc, MapMode.SIMPLE);
    }

    /**
     * Where a position in the old document ends up in the new one.
     *
     * <p>{@code assoc} is the tie-break that a position alone cannot express: text inserted <em>at</em>
     * a position could reasonably land before it or after it, and which one is right depends on what the
     * position means. A caret typing forward associates with the character before it, so it stays after
     * what it just typed ({@code assoc < 0} keeps it before the insertion; {@code assoc >= 0} pushes it
     * after). The start and end of a selection want opposite biases, which is the clearest case that this
     * cannot be a global policy.</p>
     *
     * @return the mapped position, or {@link #DELETED} under {@link MapMode#TRACK_DELETION} when the
     *         position's surrounding text was removed
     */
    public int mapPos(int position, int assoc, MapMode mode) {
        int delta = 0;
        for (Change change : changes) {
            if (position > change.to()) {
                delta += change.delta();
                continue;
            }
            if (position < change.from()) break;

            // from <= position <= to.
            if (position == change.from() && assoc < 0) return change.from() + delta;
            if (position == change.to() || position == change.from()) {
                return change.from() + delta + change.inserted();
            }
            // Strictly inside text that no longer exists.
            if (mode == MapMode.TRACK_DELETION) return DELETED;
            return change.from() + delta + (assoc < 0 ? 0 : change.inserted());
        }
        return position + delta;
    }

    /** Maps a whole range, biasing its ends outward so it keeps covering what it covered. */
    public int[] mapRange(int from, int to, MapMode mode) {
        int mappedFrom = mapPos(from, -1, mode);
        int mappedTo = mapPos(to, 1, mode);
        if (mappedFrom == DELETED || mappedTo == DELETED) return null;
        return new int[] { mappedFrom, Math.max(mappedFrom, mappedTo) };
    }

    // ── Composition ─────────────────────────────────────────────────────────────────────────────

    /**
     * This edit followed by {@code next}, as a single edit against the original document.
     *
     * <p>{@code next} must be expressed against the document <em>this</em> one produces. Composition is
     * what makes undo coalescing a property of the data rather than a per-command merge rule: a run of
     * keystrokes composes into one change set, and one change set is one undo step.</p>
     *
     * <h4>How</h4>
     * <p>Directly merging two lists of ranges in two different coordinate systems is where this normally
     * goes wrong. So it goes through an intermediate that has no coordinate system at all: this edit is
     * expanded into the <b>segments</b> that make up the middle document — each either a surviving span of
     * the original or a literal insertion. {@code next} is then applied to that segment list, which needs
     * only offsets within the middle document. Whatever survives is still in original-document order, so
     * reading the result back out as changes is a single left-to-right walk.</p>
     */
    public ChangeSet compose(ChangeSet next) {
        if (next.lengthBefore != lengthAfter) {
            throw new IllegalArgumentException(
                    "next applies to a document of length " + next.lengthBefore
                            + ", but this produces one of length " + lengthAfter);
        }
        if (isEmpty()) return new ChangeSet(new ArrayList<>(next.changes), lengthBefore);
        if (next.isEmpty()) return this;
        return fromSegments(applyToSegments(segments(), next.changes), lengthBefore);
    }

    /**
     * A span of the middle document: either kept from the original ({@code text == null}) or inserted.
     */
    private static final class Segment {
        final int from;
        final int to;
        final String text;

        Segment(int from, int to) {
            this.from = from;
            this.to = to;
            this.text = null;
        }

        Segment(String text) {
            this.from = 0;
            this.to = 0;
            this.text = text;
        }

        int length() {
            return text != null ? text.length() : to - from;
        }

        Segment slice(int start, int end) {
            return text != null ? new Segment(text.substring(start, end)) : new Segment(from + start, from + end);
        }
    }

    /** The middle document, as surviving spans of the original interleaved with insertions. */
    private List<Segment> segments() {
        List<Segment> out = new ArrayList<>(changes.size() * 2 + 1);
        int cursor = 0;
        for (Change change : changes) {
            if (change.from() > cursor) out.add(new Segment(cursor, change.from()));
            if (change.inserted() > 0) out.add(new Segment(change.insert()));
            cursor = change.to();
        }
        if (cursor < lengthBefore) out.add(new Segment(cursor, lengthBefore));
        return out;
    }

    /** Applies edits expressed in middle-document coordinates to the segment list. */
    private static List<Segment> applyToSegments(List<Segment> segments, List<Change> edits) {
        List<Segment> out = new ArrayList<>(segments.size() + edits.size() * 2);
        int index = 0;
        int consumed = 0; // how much of segments.get(index) is already dealt with
        int position = 0; // middle-document offset

        for (Change edit : edits) {
            // Carry across everything before this edit.
            while (position < edit.from() && index < segments.size()) {
                Segment segment = segments.get(index);
                int available = segment.length() - consumed;
                int take = Math.min(available, edit.from() - position);
                if (take > 0) out.add(segment.slice(consumed, consumed + take));
                consumed += take;
                position += take;
                if (consumed == segment.length()) {
                    index++;
                    consumed = 0;
                }
            }
            // Drop what the edit replaces.
            int remaining = edit.removed();
            while (remaining > 0 && index < segments.size()) {
                Segment segment = segments.get(index);
                int available = segment.length() - consumed;
                int drop = Math.min(available, remaining);
                consumed += drop;
                remaining -= drop;
                position += drop;
                if (consumed == segment.length()) {
                    index++;
                    consumed = 0;
                }
            }
            if (edit.inserted() > 0) out.add(new Segment(edit.insert()));
        }

        while (index < segments.size()) {
            Segment segment = segments.get(index);
            out.add(segment.slice(consumed, segment.length()));
            index++;
            consumed = 0;
        }
        return out;
    }

    /**
     * Reads a segment list back out as changes against the original document.
     *
     * <p>Kept spans arrive in increasing original-document order, so any gap between one span's end and
     * the next one's start is text that did not survive, and any insertion sitting in that gap is what
     * replaced it. Both fall out of a single walk.</p>
     */
    private static ChangeSet fromSegments(List<Segment> segments, int lengthBefore) {
        List<Change> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        int cursor = 0;

        for (Segment segment : segments) {
            if (segment.text != null) {
                pending.append(segment.text);
                continue;
            }
            if (segment.from > cursor || pending.length() > 0) {
                out.add(new Change(cursor, segment.from, pending.toString()));
                pending.setLength(0);
            }
            cursor = segment.to;
        }
        if (cursor < lengthBefore || pending.length() > 0) {
            out.add(new Change(cursor, lengthBefore, pending.toString()));
        }

        List<Change> kept = new ArrayList<>(out.size());
        for (Change change : out) if (!change.isEmpty()) kept.add(change);
        return new ChangeSet(kept, lengthBefore);
    }

    @Override
    public String toString() {
        return "ChangeSet" + changes + " " + lengthBefore + "->" + lengthAfter;
    }
}
