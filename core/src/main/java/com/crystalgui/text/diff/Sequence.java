package com.crystalgui.text.diff;

/**
 * What a diff algorithm actually compares: a list of opaque integers.
 *
 * <p>Ported from {@code ISequence} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code src/vs/editor/common/diff/defaultLinesDiffComputer/algorithms/diffAlgorithm.ts}), MIT.</p>
 *
 * <p>The algorithms never see text. Lines and characters are hashed to ints first, so the inner loop is an
 * integer compare rather than a string compare — which matters because Myers touches the same elements
 * repeatedly. It is also what lets one implementation serve lines and characters both.</p>
 */
public interface Sequence {

    int length();

    /** The comparison key at this offset. Equality of these is what the algorithm optimises over. */
    int elementAt(int offset);

    /**
     * Whether the two offsets are equal in a <b>stronger</b> sense than {@link #elementAt}.
     *
     * <p>Exists because {@link #elementAt} for lines is the hash of the <em>trimmed</em> line, so two lines
     * differing only in indentation compare equal — which is what makes the diff readable. When a heuristic
     * then wants to slide a diff sideways it must not slide it onto a line that is only weakly equal, or a
     * whitespace-only difference silently becomes the match and the real one is reported as changed.</p>
     */
    boolean stronglyEqual(int offset1, int offset2);

    /**
     * How good a place this offset is to split the sequence — higher is better, never negative.
     *
     * <p>Used to move an insertion to a boundary a person would have chosen. Inserting a function is
     * reported as starting at the blank line before it rather than at its first brace, because the blank
     * line scores higher. Zero for a sequence with no opinion.</p>
     */
    default int boundaryScore(int offset) {
        return 0;
    }
}
