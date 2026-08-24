package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * What "equal" means when two lines are compared.
 *
 * <p>Ported from {@code com.intellij.diff.comparison.ComparisonPolicy} and the equality half of
 * {@code ComparisonUtil.isEquals} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0.
 * <b>Modified:</b> the three cases are given a {@link #normalise} method so a caller can build a comparison
 * key once per line instead of re-deciding per comparison, which is what {@code ByLineRt.convertMode} does
 * by hand there.</p>
 *
 * <h3>Not a view option</h3>
 *
 * <p>The temptation is to treat this as a checkbox that hides some marks. It is not: it changes which lines
 * count as equal, so it changes the <b>anchors the diff is built on</b> and therefore the regions
 * themselves. Under {@link #IGNORE_WHITESPACES} a reindented block is not a change at all and cannot be
 * shown as one, because the differ never produced it.</p>
 *
 * <p>The consequence for a merge is larger still. A region only one side touched resolves itself, so
 * changing what "touched" means changes <b>how many conflicts a person is asked about</b> — a file
 * reindented on one side and edited on the other is every-line-conflicts under {@link #DEFAULT} and a
 * handful under {@link #IGNORE_WHITESPACES}.</p>
 */
public enum ComparisonPolicy {

    /** Exact contents. Two lines are equal when their characters are. */
    DEFAULT {
        @Override
        public String normalise(String line) {
            return line;
        }
    },

    /**
     * Leading and trailing whitespace ignored; interior whitespace still counts.
     *
     * <p>The middle setting, and the one that matches how most people actually think about a line: an edit
     * that only changed a line's indentation did not change the line, but an edit that respaced its
     * operators did.</p>
     */
    TRIM_WHITESPACES {
        @Override
        public String normalise(String line) {
            return line.strip();
        }
    },

    /**
     * All whitespace ignored, wherever it falls.
     *
     * <p>Also used <em>internally</em> as the rough pass under every policy — see {@code ByLineRt}, where
     * even a DEFAULT comparison first runs whitespace-insensitively and recovers exactness afterwards.
     * Reindentation is the commonest edit in real code, and a whitespace-sensitive first pass anchors on
     * lines that only moved sideways, producing a technically minimal diff nobody can read.</p>
     */
    IGNORE_WHITESPACES {
        @Override
        public String normalise(String line) {
            StringBuilder out = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (!Character.isWhitespace(c)) out.append(c);
            }
            return out.toString();
        }
    };

    /** The comparison key for one line: two lines are equal under this policy iff their keys are. */
    public abstract String normalise(String line);

    /** Keys for a whole text, in order. Indices are preserved, so a range over one indexes the other. */
    public List<String> normaliseAll(List<String> lines) {
        if (this == DEFAULT) return lines;
        List<String> keys = new ArrayList<>(lines.size());
        for (String line : lines) keys.add(normalise(line));
        return keys;
    }

    public boolean linesEqual(String a, String b) {
        return a.equals(b) || normalise(a).equals(normalise(b));
    }

    /** Whether two runs of lines are equal under this policy. */
    public boolean equal(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!linesEqual(a.get(i), b.get(i))) return false;
        }
        return true;
    }
}
