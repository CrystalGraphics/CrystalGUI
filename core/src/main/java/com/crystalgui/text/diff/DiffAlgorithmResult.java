package com.crystalgui.text.diff;

import java.util.Collections;
import java.util.List;

/**
 * What a diff algorithm answers with: the differing spans, and whether it ran out of time.
 *
 * <p>Ported from {@code DiffAlgorithmResult} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT.</p>
 *
 * <p><b>{@code hitTimeout} is not an error flag.</b> The answer is still usable — it is the whole file
 * reported as one change, which is true, just uninformative. Modelling it as a failure would make a caller
 * choose between showing nothing and pretending the approximation is exact; modelling it as a fact lets the
 * view say "this file was too large to diff precisely", which is what a person needs to know.</p>
 */
public record DiffAlgorithmResult(List<DiffRange> diffs, boolean hitTimeout) {

    public DiffAlgorithmResult {
        diffs = Collections.unmodifiableList(diffs);
    }

    /** Everything differs — the answer for an empty side, and the degraded answer after a timeout. */
    public static DiffAlgorithmResult trivial(Sequence seq1, Sequence seq2, boolean hitTimeout) {
        if (seq1.length() == 0 && seq2.length() == 0) {
            return new DiffAlgorithmResult(List.of(), hitTimeout);
        }
        return new DiffAlgorithmResult(
                List.of(new DiffRange(0, seq1.length(), 0, seq2.length())), hitTimeout);
    }
}
