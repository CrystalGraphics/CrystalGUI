package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.diff.DetailedDiff;
import com.crystalgui.text.diff.DiffRange;
import com.crystalgui.text.diff.InnerRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a {@link TextEditor} should mark up as a difference — the model {@code DiffBandsPart} draws.
 *
 * <p>Shaped after {@code diffEditorDecorations.ts} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT — the decorations are computed
 * from the diff and handed to the editor rather than the editor knowing what a diff is.</p>
 *
 * <h3>Why the editor is told rather than asked</h3>
 *
 * <p>A {@code TextEditor} shows one text. Which <em>other</em> text it is being compared against is the
 * business of whatever put the two side by side, and there may be no other text at all — the same widget
 * shows an ordinary file. So the decorations arrive as data with no back-reference, and an editor with
 * none behaves exactly as it always did.</p>
 *
 * <p>This is also what lets one diff drive two editors: {@link #forOriginal} and {@link #forModified} take
 * the <em>same</em> diff and produce each side's view of it, so the two panes cannot disagree about what
 * changed.</p>
 */
public final class DiffDecorations {

    /** What a mark means. Named from the reader's side, not the algorithm's. */
    public enum Kind {
        /** Present here, absent in the other text. */
        ADDED,
        /** Absent here, present in the other text — drawn as a thin marker between two lines. */
        REMOVED,
        /** Present in both, differing. */
        CHANGED
    }

    /** Whole lines. {@code fromLine == toLine} means a deletion marker sitting before {@code fromLine}. */
    public record Band(int fromLine, int toLine, Kind kind) {
    }

    /** A run of characters within one line. */
    public record Mark(int line, int fromColumn, int toColumn, Kind kind) {
    }

    public static final DiffDecorations NONE = new DiffDecorations(List.of(), List.of());

    private final List<Band> bands;
    private final List<Mark> marks;

    public DiffDecorations(List<Band> bands, List<Mark> marks) {
        this.bands = Collections.unmodifiableList(new ArrayList<>(bands));
        this.marks = Collections.unmodifiableList(new ArrayList<>(marks));
    }

    public List<Band> bands() {
        return bands;
    }

    public List<Mark> marks() {
        return marks;
    }

    public boolean isEmpty() {
        return bands.isEmpty() && marks.isEmpty();
    }

    /** The left-hand pane's view of a diff: what went, and what changed. */
    public static DiffDecorations forOriginal(List<DetailedDiff> diffs) {
        return build(diffs, true);
    }

    /** The right-hand pane's view of the same diff: what arrived, and what changed. */
    public static DiffDecorations forModified(List<DetailedDiff> diffs) {
        return build(diffs, false);
    }

    private static DiffDecorations build(List<DetailedDiff> diffs, boolean original) {
        List<Band> bands = new ArrayList<>();
        List<Mark> marks = new ArrayList<>();

        for (DetailedDiff detailed : diffs) {
            DiffRange range = detailed.lines();
            int from = original ? range.start1() : range.start2();
            int to = original ? range.end1() : range.end2();

            // THE KIND IS A FACT ABOUT THE CHANGE, not about the pane looking at it. An insertion is an
            // insertion in both panes -- it simply has no rows to band in the original, where it becomes a
            // zero-height marker at the boundary. Deriving the kind per pane instead makes the same change
            // read as two different things depending on which side the eye is on.
            Kind kind = range.isEmpty1() ? Kind.ADDED
                    : range.isEmpty2() ? Kind.REMOVED
                    : Kind.CHANGED;
            bands.add(new Band(from, to, kind));

            for (InnerRange inner : detailed.inner()) {
                int line = original ? inner.fromLine1() : inner.fromLine2();
                int endLine = original ? inner.toLine1() : inner.toLine2();
                int fromColumn = original ? inner.fromColumn1() : inner.fromColumn2();
                int toColumn = original ? inner.toColumn1() : inner.toColumn2();
                // Only single-line inner ranges become marks. A range spanning a line break has no single
                // row to sit on, and the band already covers every row it crosses.
                if (line == endLine && toColumn > fromColumn) {
                    marks.add(new Mark(line, fromColumn, toColumn, Kind.CHANGED));
                }
            }
        }
        return new DiffDecorations(bands, marks);
    }
}
