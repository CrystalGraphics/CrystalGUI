package com.crystalgui.text.diff;

/**
 * One span of all three texts that at least one side changed.
 *
 * <p>Ported from {@code com.intellij.diff.util.MergeRange} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0.
 * <b>Modified:</b> rendered as a record and given names rather than indices — JetBrains' fields are
 * {@code start1/2/3}, where 1 is the left side, 2 is the <em>base</em> and 3 is the right. The numbering is
 * a genuine trap: base sits in the middle, so "the first one" is not the ancestor.</p>
 *
 * <p>All six bounds are half-open line indices, each in its own text. A range is the one place the three
 * coordinate systems are lined up against each other, which is what a three-pane view needs in order to
 * paint panes that agree.</p>
 */
public record MergeRange(int mineFrom, int mineTo, int baseFrom, int baseTo, int theirsFrom, int theirsTo) {

    public boolean isEmpty() {
        return mineFrom == mineTo && baseFrom == baseTo && theirsFrom == theirsTo;
    }

    public int mineLength() {
        return mineTo - mineFrom;
    }

    public int baseLength() {
        return baseTo - baseFrom;
    }

    public int theirsLength() {
        return theirsTo - theirsFrom;
    }
}
