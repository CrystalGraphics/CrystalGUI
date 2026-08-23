package com.crystalgui.text.diff;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a line diff into the edit representation the rest of the engine already speaks — Phase 6.6.
 *
 * <p>{@link LineDiff} answers in <b>line ranges</b>, which is what a viewer wants: it needs to know that
 * lines 12–14 became lines 12–16 so it can draw a band and a connector. Everything else in this codebase
 * speaks <b>character offsets</b> — {@code writeDelta} sends a {@link ChangeSet}, {@code UndoStack} stores
 * one, and {@code Rope} applies one. So the two live side by side and this is the seam.</p>
 *
 * <p><b>One representation, not two.</b> The alternative — a diff type of its own that a viewer renders
 * and a separate path that syncs — is how a delta read and the diff on screen come to disagree about
 * what changed, which is precisely the bug a diff viewer exists to make impossible.</p>
 */
public final class TextDiff {

    private TextDiff() {
    }

    /**
     * The edits that turn {@code before} into {@code after}.
     *
     * <p>Offsets are into {@code before}, and the set is ordered, which is what {@code ChangeSet}
     * requires. Applying it reproduces {@code after} exactly — that is the invariant worth testing, and
     * the only one that cannot pass against a subtly wrong diff.</p>
     */
    public static ChangeSet changes(String before, String after) {
        List<String> beforeLines = LineDiff.lines(before);
        List<String> afterLines = LineDiff.lines(after);

        int[] beforeStarts = lineStarts(beforeLines, before);
        int[] afterStarts = lineStarts(afterLines, after);

        List<Change> changes = new ArrayList<>();
        for (LineDiff.Hunk hunk : LineDiff.diff(beforeLines, afterLines)) {
            int from = beforeStarts[hunk.fromLine()];
            int to = beforeStarts[hunk.toLine()];
            String insert = after.substring(afterStarts[hunk.newFromLine()],
                    afterStarts[hunk.newToLine()]);
            if (from == to && insert.isEmpty()) continue;
            changes.add(new Change(from, to, insert));
        }
        appendTerminatorChange(before, after, changes);
        return ChangeSet.of(before.length(), changes);
    }

    /**
     * The trailing newline, which a line diff cannot see.
     *
     * <p><b>A trailing newline is not a line.</b> {@code "a\nb\n"} and {@code "a\nb"} split to the same
     * two lines, so {@link LineDiff} correctly reports no difference between them — and yet the texts
     * differ by one character. That is not a bug in the line diff; it is the granularity being honest.
     * The offset seam is the only place with enough information to fix it, which is why it lives here.</p>
     *
     * <p>Found by the round-trip property rather than by reading: applying an empty change set to
     * {@code "a\nb\n"} gave back {@code "a\nb\n"} where {@code "a\nb"} was wanted. No test that counted
     * hunks would ever have noticed, because the hunk count was right.</p>
     *
     * <p>Skipped when a hunk already reaches the end of {@code before}: that hunk's replacement text runs
     * to {@code after.length()} and therefore already carries — or already omits — the terminator. Adding
     * another change there would overlap it.</p>
     */
    private static void appendTerminatorChange(String before, String after, List<Change> changes) {
        boolean beforeTerminated = before.endsWith("\n");
        boolean afterTerminated = after.endsWith("\n");
        if (beforeTerminated == afterTerminated) return;

        int covered = changes.isEmpty() ? -1 : changes.get(changes.size() - 1).to();
        if (covered >= before.length()) return;

        if (beforeTerminated) {
            changes.add(Change.delete(before.length() - 1, before.length()));
        } else {
            changes.add(Change.insert(before.length(), "\n"));
        }
    }

    /**
     * Where each line begins, plus one entry past the end.
     *
     * <p>The extra entry is what makes a half-open line range convert without a special case for the last
     * line — {@code starts[lineCount]} is the length of the text, so "to the end" needs no branch.</p>
     */
    private static int[] lineStarts(List<String> lines, String text) {
        int[] starts = new int[lines.size() + 1];
        int at = 0;
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = at;
            at += lines.get(i).length();
            // Step over the terminator, unless this line is the last and the text does not end in one.
            if (at < text.length() && text.charAt(at) == '\n') at++;
        }
        starts[lines.size()] = text.length();
        return starts;
    }
}
