package com.crystalgui.text.cursor;

import com.crystalgui.text.Change;
import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.Language;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Whole-line edits — move, duplicate, delete, join, comment, indent.
 *
 * <p>Modelled on VS Code's line-command set ({@code moveLinesCommand}, {@code copyLinesCommand},
 * {@code trimTrailingWhitespaceCommand}, {@code lineCommentCommand}), reduced to the operations this
 * editor exposes. Each returns {@link Change}s rather than performing them, so the caller decides whether
 * the result is one undo step, and so each rule can be asserted without a document to mutate.</p>
 */
public final class LineOperations {

    private LineOperations() {
    }

    /** The whole of a row including its trailing newline, clamped at the document end. */
    private static int lineStart(Rope document, int row) {
        return document.lineStartOffset(row);
    }

    private static int lineEndInclusive(Rope document, int row) {
        return Math.min(document.length(), document.lineEndOffset(row) + 1);
    }

    static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /**
     * Deletes each row.
     *
     * <p>The last row has no trailing newline to take with it, so it swallows the one <em>before</em> it —
     * otherwise deleting it leaves a blank line where the text was.</p>
     */
    public static List<Change> delete(Rope document, List<Integer> rows) {
        List<Change> changes = new ArrayList<>();
        for (int row : rows) {
            int start = lineStart(document, row);
            int end = lineEndInclusive(document, row);
            // A row that took its trailing newline with it removes a whole line. The LAST row has none to
            // take -- the document simply ends -- so it must swallow the newline BEFORE it instead, or
            // deleting it leaves a blank line exactly where the text was.
            boolean tookTrailingNewline = end > document.lineEndOffset(row);
            if (!tookTrailingNewline && row > 0) start = document.lineEndOffset(row - 1);
            changes.add(Change.delete(start, end));
        }
        return mergeAdjacent(changes);
    }

    /** Copies the block of rows above or below itself. */
    public static List<Change> duplicate(Rope document, List<Integer> rows, int direction) {
        if (rows.isEmpty()) return List.of();
        int first = rows.get(0);
        int last = rows.get(rows.size() - 1);
        int start = lineStart(document, first);
        int end = lineEndInclusive(document, last);
        String block = document.slice(start, end).toString();
        if (!block.endsWith("\n")) block = block + "\n";
        return new ArrayList<>(List.of(Change.insert(direction < 0 ? start : end, block)));
    }

    /** A move of the block by one row, and how far the selections must shift with it. */
    public record Move(Change change, int shift) {
    }

    /**
     * Swaps a block of rows with the line above or below.
     *
     * <p>Expressed as a <b>single replacement</b> of the span covering both, rather than two edits. Two
     * would be two undo steps, and the second would be described against a document the first had already
     * changed.</p>
     */
    public static Move move(Rope document, List<Integer> rows, int direction) {
        if (rows.isEmpty()) return null;
        int first = rows.get(0);
        int last = rows.get(rows.size() - 1);
        int swapWith = direction < 0 ? first - 1 : last + 1;
        if (swapWith < 0 || swapWith >= document.lineCount()) return null;

        int spanFirst = Math.min(first, swapWith);
        int spanLast = Math.max(last, swapWith);
        int start = lineStart(document, spanFirst);
        int end = lineEndInclusive(document, spanLast);

        List<String> lines = new ArrayList<>();
        for (int row = spanFirst; row <= spanLast; row++) lines.add(document.line(row));
        if (direction < 0) lines.add(lines.remove(0));
        else lines.add(0, lines.remove(lines.size() - 1));

        StringBuilder replacement = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            replacement.append(lines.get(i));
            if (i < lines.size() - 1 || end < document.length()) replacement.append('\n');
        }
        int shift = (document.line(swapWith).length() + 1) * (direction < 0 ? -1 : 1);
        return new Move(new Change(start, end, replacement.toString()), shift);
    }

    /** Joins each row with the one after it, collapsing the whitespace between to a single space. */
    public static List<Change> join(Rope document, List<Integer> rows) {
        List<Change> changes = new ArrayList<>();
        for (int row : rows) {
            if (row + 1 >= document.lineCount()) continue;
            int end = document.lineEndOffset(row);
            int nextStart = document.lineStartOffset(row + 1);
            int afterIndent = nextStart + leadingWhitespace(document.line(row + 1)).length();
            // No glue onto an empty line -- joining onto nothing should not leave a trailing space.
            String glue = document.line(row + 1).isBlank() ? "" : " ";
            changes.add(new Change(end, afterIndent, glue));
        }
        return mergeAdjacent(changes);
    }

    /** Opens a line below (or above) each caret, carrying the row's indentation. */
    public static List<Change> insertLine(Rope document, List<Integer> rows, int direction) {
        List<Change> changes = new ArrayList<>();
        for (int row : rows) {
            int at = direction < 0 ? lineStart(document, row)
                    : Math.min(document.length(), document.lineEndOffset(row));
            String indent = leadingWhitespace(document.line(row));
            changes.add(Change.insert(at, direction < 0 ? indent + "\n" : "\n" + indent));
        }
        return mergeAdjacent(changes);
    }

    public static List<Change> indent(Rope document, List<Integer> rows, int width) {
        List<Change> changes = new ArrayList<>();
        for (int row : rows) changes.add(Change.insert(lineStart(document, row), spaces(width)));
        return changes;
    }

    public static List<Change> outdent(Rope document, List<Integer> rows, int width) {
        List<Change> changes = new ArrayList<>();
        for (int row : rows) {
            int start = lineStart(document, row);
            String line = document.line(row);
            int remove = 0;
            while (remove < width && remove < line.length() && line.charAt(remove) == ' ') remove++;
            if (remove == 0 && !line.isEmpty() && line.charAt(0) == '\t') remove = 1;
            if (remove > 0) changes.add(Change.delete(start, start + remove));
        }
        return changes;
    }

    /**
     * Toggles the line comment across a block.
     *
     * <p><b>Comments out unless every non-blank line is already commented.</b> Every editor uses this
     * rule, and it is the only one that behaves sensibly on a mixed block: a selection where one line is
     * commented should become fully commented rather than half-toggled.</p>
     */
    public static List<Change> toggleLineComment(Rope document, List<Integer> rows, Language language) {
        if (!language.hasLineComment() || rows.isEmpty()) return List.of();
        String token = language.lineComment();

        boolean allCommented = true;
        for (int row : rows) {
            String text = document.line(row);
            if (text.isBlank()) continue;
            if (!text.stripLeading().startsWith(token)) {
                allCommented = false;
                break;
            }
        }

        List<Change> changes = new ArrayList<>();
        for (int row : rows) {
            String text = document.line(row);
            if (text.isBlank()) continue;
            int start = lineStart(document, row);
            int indent = leadingWhitespace(text).length();
            if (allCommented) {
                int at = start + indent;
                int end = at + token.length();
                // Take one following space with it, since that is what commenting added.
                if (end < document.length() && document.charAt(end) == ' ') end++;
                changes.add(Change.delete(at, end));
            } else {
                changes.add(Change.insert(start + indent, token + " "));
            }
        }
        return changes;
    }

    private static String spaces(int howMany) {
        StringBuilder out = new StringBuilder(howMany);
        for (int i = 0; i < howMany; i++) out.append(' ');
        return out.toString();
    }

    /**
     * Merges changes that touch, so a set built per line satisfies {@code ChangeSet}'s no-overlap rule.
     *
     * <p>Deleting consecutive lines produces ranges that meet exactly at a boundary, and two carets on one
     * line can produce genuinely overlapping ones. {@code ChangeSet.of} refuses both, correctly — the
     * merge belongs here, where the intent is known.</p>
     */
    public static List<Change> mergeAdjacent(List<Change> changes) {
        List<Change> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingInt(Change::from));
        List<Change> merged = new ArrayList<>(sorted.size());
        for (Change change : sorted) {
            if (!merged.isEmpty()) {
                Change last = merged.get(merged.size() - 1);
                if (change.from() <= last.to()) {
                    merged.set(merged.size() - 1, new Change(last.from(),
                            Math.max(last.to(), change.to()), last.insert() + change.insert()));
                    continue;
                }
            }
            merged.add(change);
        }
        return merged;
    }
}
