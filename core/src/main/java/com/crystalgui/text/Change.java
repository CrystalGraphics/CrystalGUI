package com.crystalgui.text;

/**
 * One replacement: {@code [from, to)} becomes {@code insert}.
 *
 * <p>Offsets are UTF-16 code units in the document the containing {@link ChangeSet} applies <b>to</b> —
 * never in the document it produces. That is what lets a change set be composed, inverted and applied
 * without carrying the document around with it.</p>
 *
 * <p>A pure insertion has {@code from == to}; a pure deletion has an empty {@code insert}. Both are
 * ordinary cases here rather than special ones, which is most of why this shape was chosen over a
 * separate insert/delete pair.</p>
 */
public record Change(int from, int to, String insert) {

    public Change {
        if (from < 0 || to < from) throw new IllegalArgumentException("bad range " + from + ".." + to);
        if (insert == null) insert = "";
    }

    public static Change insert(int at, String text) {
        return new Change(at, at, text);
    }

    public static Change delete(int from, int to) {
        return new Change(from, to, "");
    }

    /** How many code units this removes. */
    public int removed() {
        return to - from;
    }

    /** How many code units this adds. */
    public int inserted() {
        return insert.length();
    }

    /** The document's net length change. */
    public int delta() {
        return inserted() - removed();
    }

    public boolean isEmpty() {
        return removed() == 0 && inserted() == 0;
    }
}
