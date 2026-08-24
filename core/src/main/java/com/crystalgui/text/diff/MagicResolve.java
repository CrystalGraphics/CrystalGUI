package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Resolves a line-level conflict by looking at it one granularity down.
 *
 * <p>Ported in <em>behaviour</em> from {@code MergeResolveUtil.tryResolve} in
 * <a href="https://github.com/JetBrains/intellij-community">JetBrains/intellij-community</a>, Apache 2.0.
 * <b>Modified — substantially:</b> where upstream hand-walks a three-way character comparison with its own
 * append/conflict helpers, this reuses {@link MergeRanges} over character sequences. Magic resolve <em>is</em>
 * the three-way merge run again on characters, so implementing it a second way would be two chances to be
 * wrong about the same thing.</p>
 *
 * <h3>Why a line conflict is often not a conflict</h3>
 *
 * <p>Line granularity is deliberately coarse — a merge that resolved two people's edits to different words
 * of one line would produce a line neither of them wrote. But the same coarseness reports a conflict when
 * one side changed the start of a line and the other changed its end, which is not a disagreement at all,
 * merely two edits that happen to share a row.</p>
 *
 * <p>So the answer is not to lower the granularity of the merge — that would silently produce
 * nobody's-line — but to <b>ask a second question about a conflict that has already been found</b>, and to
 * offer rather than impose the answer. The line-level conflict remains a conflict; this supplies the text a
 * person would most likely have typed.</p>
 *
 * <h3>What cannot be resolved</h3>
 *
 * <p>Upstream's rules, which fall out of this implementation rather than being coded:</p>
 *
 * <ul>
 *   <li><b>insertion against insertion</b> — unresolvable, and this is the important one. Two different
 *       blocks inserted at the same point have no knowable order; sorting them by length or alphabetically
 *       would be inventing an answer. Here they come out as a character-level {@code CONFLICT} region and
 *       the whole attempt is abandoned.</li>
 *   <li><b>deletion against insertion</b> → both apply</li>
 *   <li><b>deletion against deletion</b> → the deleted spans merge</li>
 *   <li>a <b>modification</b> is an insertion plus a deletion, and resolves accordingly</li>
 * </ul>
 */
public final class MagicResolve {

    private MagicResolve() {
    }

    /**
     * The text a conflicting region would resolve to, or {@code null} when the two sides genuinely clash.
     *
     * <p>{@code null} rather than a partial answer: half a resolution is worse than none, because it looks
     * like a decision somebody made.</p>
     */
    @Nullable
    public static List<String> tryResolve(List<String> baseLines, List<String> mineLines,
            List<String> theirsLines) {
        CharSequenceSlice base = CharSequenceSlice.of(baseLines, 0, baseLines.size());
        CharSequenceSlice mine = CharSequenceSlice.of(mineLines, 0, mineLines.size());
        CharSequenceSlice theirs = CharSequenceSlice.of(theirsLines, 0, theirsLines.size());

        String baseText = join(baseLines);
        String mineText = join(mineLines);
        String theirsText = join(theirsLines);

        DiffIterable mineDiff = charDiff(base, mine);
        DiffIterable theirsDiff = charDiff(base, theirs);

        StringBuilder out = new StringBuilder();
        int at = 0;
        for (MergeRange range : MergeRanges.build(mineDiff, theirsDiff)) {
            out.append(baseText, at, range.baseFrom());

            String baseSlice = baseText.substring(range.baseFrom(), range.baseTo());
            String mineSlice = mineText.substring(range.mineFrom(), range.mineTo());
            String theirsSlice = theirsText.substring(range.theirsFrom(), range.theirsTo());

            boolean mineChanged = !mineSlice.equals(baseSlice);
            boolean theirsChanged = !theirsSlice.equals(baseSlice);

            if (!theirsChanged) {
                out.append(mineSlice);
            } else if (!mineChanged) {
                out.append(theirsSlice);
            } else if (mineSlice.equals(theirsSlice)) {
                out.append(mineSlice);
            } else {
                // A real clash at character level. Refusing the WHOLE region rather than resolving the
                // parts around it: a partly-merged line reads as something a person approved.
                return null;
            }
            at = range.baseTo();
        }
        out.append(baseText, at, baseText.length());

        return splitLines(out.toString());
    }

    private static DiffIterable charDiff(CharSequenceSlice a, CharSequenceSlice b) {
        DiffAlgorithmResult raw = a.length() + b.length() < LinesDiff.CHAR_EXACT_LIMIT
                ? DynamicProgrammingDiff.compute(a, b)
                : MyersDiff.compute(a, b);
        return DiffIterable.fromChanged(a.length(), b.length(),
                SequenceOptimizations.optimize(a, b, raw.diffs()));
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /**
     * Splits back into lines <b>without</b> {@link LineDiff#lines}' trailing-newline rule.
     *
     * <p>The text here was joined with {@code "\n"} as a separator and has no terminator, so a trailing
     * empty line is a real empty line the merge produced — not a terminator to swallow. Using the document
     * splitter would quietly drop it.</p>
     */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        if (lines.size() == 1 && lines.get(0).isEmpty()) return List.of();
        return lines;
    }
}
