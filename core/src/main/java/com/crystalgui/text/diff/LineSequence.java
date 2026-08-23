package com.crystalgui.text.diff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines as a {@link Sequence}: hashed on the <b>trimmed</b> line, compared strongly on the whole one.
 *
 * <p>Ported from {@code LineSequence} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../defaultLinesDiffComputer/lineSequence.ts}), MIT. <b>Modified:</b> the perfect-hash map is
 * built here rather than by the caller, and a {@link ComparisonPolicy} chooses what "trimmed" means so the
 * three policies reach the algorithms.</p>
 *
 * <h3>Two notions of equality, deliberately</h3>
 *
 * <p>{@link #elementAt} hashes the line under the policy, so under the default two lines differing only in
 * indentation are the <em>same element</em> — which is what lets the algorithms find the correspondence
 * through a reindent. {@link #stronglyEqual} compares the real text, and the heuristics use it when they
 * want to slide a diff sideways: sliding onto a merely-weakly-equal line would silently make a
 * whitespace-only difference the match and report the real one as changed.</p>
 *
 * <p>This is the same two-pass idea as {@code TwoStepCompare}, expressed inside the sequence instead of as
 * a second pass over the result — which is why VS Code needs no correction step.</p>
 */
public final class LineSequence implements Sequence {

    private final int[] hashes;
    private final List<String> lines;

    private LineSequence(int[] hashes, List<String> lines) {
        this.hashes = hashes;
        this.lines = lines;
    }

    /** Builds one, interning comparison keys through a shared map so both sides agree on the numbering. */
    public static LineSequence of(List<String> lines, ComparisonPolicy policy, Map<String, Integer> interner) {
        int[] hashes = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            hashes[i] = interner.computeIfAbsent(policy.normalise(lines.get(i)), key -> interner.size());
        }
        return new LineSequence(hashes, lines);
    }

    /** A pair sharing one interner — the only correct way to build two sequences that will be compared. */
    public static LineSequence[] pair(List<String> lines1, List<String> lines2, ComparisonPolicy policy) {
        // ONE MAP FOR BOTH. Hashing each side independently gives two numberings, so equal lines get
        // different elements and the algorithms find no matches at all -- an empty diff over identical
        // files, which reads as the differ being broken rather than as two dictionaries.
        Map<String, Integer> interner = new HashMap<>();
        return new LineSequence[] {of(lines1, policy, interner), of(lines2, policy, interner)};
    }

    @Override
    public int length() {
        return hashes.length;
    }

    @Override
    public int elementAt(int offset) {
        return hashes[offset];
    }

    @Override
    public boolean stronglyEqual(int offset1, int offset2) {
        return lines.get(offset1).equals(lines.get(offset2));
    }

    /**
     * Prefers splitting where the surrounding code is <b>least indented</b>.
     *
     * <p>{@code 1000 - (indentBefore + indentAfter)}, which is upstream's formula. An inserted method then
     * reads as starting at the blank line above it rather than partway through the previous method's
     * closing brace — the same edit, reported at the boundary a person would have drawn.</p>
     */
    @Override
    public int boundaryScore(int offset) {
        int before = offset == 0 ? 0 : indentationOf(lines.get(offset - 1));
        int after = offset == lines.size() ? 0 : indentationOf(lines.get(offset));
        return 1000 - (before + after);
    }

    public String lineAt(int offset) {
        return lines.get(offset);
    }

    private static int indentationOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }
}
