package com.crystalgui.text.diff;

/**
 * One span paired across two texts — the unit both {@link DiffIterable} halves are made of.
 *
 * <p>Ported from {@code com.intellij.diff.util.Range} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0.
 * <b>Modified:</b> rendered as a record, renamed, and documented for this codebase.</p>
 *
 * <p>Ranges are half-open on both sides. The pairing is what makes the type useful: a range says
 * <em>these lines over here correspond to those lines over there</em>, which is a claim a pair of
 * independent intervals cannot make.</p>
 *
 * <p>For an <b>unchanged</b> range the two sides are necessarily the same length — that is what "unchanged"
 * means — and {@link DiffIterable#unchanged()} checks it rather than trusting it, because a diff that
 * violates it produces a merge that silently drops or duplicates lines.</p>
 */
public record DiffRange(int start1, int end1, int start2, int end2) {

    public int length1() {
        return end1 - start1;
    }

    public int length2() {
        return end2 - start2;
    }

    public boolean isEmpty() {
        return start1 == end1 && start2 == end2;
    }

    /** True when nothing was removed — i.e. this is a pure insertion into side 2. */
    public boolean isEmpty1() {
        return start1 == end1;
    }

    /** True when nothing was added — i.e. this is a pure deletion from side 1. */
    public boolean isEmpty2() {
        return start2 == end2;
    }

    /** The same span slid by {@code delta} on both sides. Used by the shifting heuristics. */
    public DiffRange delta(int delta) {
        return new DiffRange(start1 + delta, end1 + delta, start2 + delta, end2 + delta);
    }

    /** The smallest span covering both. Used when two diffs are judged to be really one edit. */
    public DiffRange join(DiffRange other) {
        return new DiffRange(Math.min(start1, other.start1()), Math.max(end1, other.end1()),
                Math.min(start2, other.start2()), Math.max(end2, other.end2()));
    }

    /** The two sides exchanged, so an algorithm written for one direction can serve both. */
    public DiffRange swap() {
        return new DiffRange(start2, end2, start1, end1);
    }
}
