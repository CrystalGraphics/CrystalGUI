package com.crystalgui.text.cursor;

import com.crystalgui.text.Change;
import com.crystalgui.text.Rope;
import com.crystalgui.text.Selection;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.syntax.Language;

import java.util.ArrayList;
import java.util.List;

/**
 * What a typed character does — ported from VS Code's {@code TypeOperations}.
 *
 * <p>Source: {@code src/vs/editor/common/cursor/cursorTypeOperations.ts}, microsoft/vscode, MIT licence.
 * </p>
 *
 * <p>The decisions are separated from the editing so each can be asserted on its own: whether a pair
 * should close is a question about the document and the language, and produces a yes or a no, not a
 * mutation.</p>
 */
public final class TypeOperations {

    private TypeOperations() {
    }

    /**
     * What may follow an auto-closed pair — VS Code's {@code autoCloseBefore} default.
     *
     * <p>An <b>allowlist</b>, not a denylist. "Suppress before a letter or digit" still opens a pair
     * before {@code $foo} or {@code #define} or a quote; listing what may follow is both stricter and
     * shorter.</p>
     */
    public static final String AUTO_CLOSE_BEFORE = ";:.,=}])> \n\t";

    /**
     * Whether typing {@code opener} should bring its partner.
     *
     * <p>Beyond the allowlist there are two rules, both about quotes, because a quote cannot be told from
     * its own closer by looking at it: not after a word character (so the apostrophe in {@code don't}
     * stays an apostrophe), and not after the same quote (so a third {@code "} does not produce five).</p>
     */
    public static boolean shouldAutoClose(Rope document, List<Selection> selections, char opener,
                                          Language language, WordClassifier classifier) {
        if (language.closerFor(opener) == null) return false;
        for (Selection selection : selections) {
            int at = selection.head();
            if (at < document.length() && AUTO_CLOSE_BEFORE.indexOf(document.charAt(at)) < 0) {
                return false;
            }
            if (language.isSelfClosing(opener) && at > 0) {
                char previous = document.charAt(at - 1);
                if (classifier.isWordPart(previous) || previous == opener) return false;
            }
        }
        return true;
    }

    /**
     * Whether every caret has {@code c} immediately after it — the condition for typing <em>over</em> a
     * closer rather than inserting a second one.
     *
     * <p>Without type-over, auto-closing is worse than not having it: you type {@code (}, get {@code ()},
     * type the {@code )} you expected to need, and end up with {@code ())}.</p>
     */
    public static boolean nextCharIs(Rope document, List<Selection> selections, char c) {
        for (Selection selection : selections) {
            int at = selection.head();
            if (at >= document.length() || document.charAt(at) != c) return false;
        }
        return true;
    }

    /** Wrapping every non-empty selection in a pair, rather than replacing it. */
    public static List<Change> surround(List<Selection> selections, char opener, char closer) {
        List<Change> changes = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection.isEmpty()) continue;
            changes.add(Change.insert(selection.start(), String.valueOf(opener)));
            changes.add(Change.insert(selection.end(), String.valueOf(closer)));
        }
        return changes;
    }

    /**
     * Where a plain Backspace should delete from.
     *
     * <p>Inside leading indentation it takes a whole level, so a file indented four spaces does not need
     * four presses per level. Anywhere else it is one character — including inside a line's text, where
     * eating four would be alarming.</p>
     *
     * <h3>In VISUAL columns, which is the whole of what was wrong with it</h3>
     *
     * <p>It counted characters. On a tab-indented file two tabs are two <em>characters</em>, so
     * {@code 2 % 4} said "take two" and one press deleted the entire indent and landed the caret at column
     * zero. Counted the way the line is drawn, two tabs are eight columns, the previous stop is four, and
     * one press takes one tab — which is what every editor does and what the tab was for.</p>
     *
     * <p>Reaching column zero then falls through to the line join above, so backspacing out of an indent
     * ends at the previous line rather than sitting at the left margin. Both halves were reported as one
     * bug.</p>
     */
    public static int backspaceFrom(Rope document, int head, int indentWidth) {
        int row = document.offsetToPoint(head).row();
        int lineStart = document.lineStartOffset(row);
        int column = head - lineStart;
        if (column == 0) return Math.max(0, head - 1);

        String text = document.line(row);
        if (!text.substring(0, column).isBlank()) return head - 1;

        int stops = Math.max(1, indentWidth);
        CursorColumns.Line drawn = CursorColumns.expand(text, stops);
        int visible = drawn.displayIndexOf(column);
        // THE PREVIOUS STOP, and never a whole level from a column that is not on one: an indent of six
        // spaces backspaces to four before it backspaces to zero.
        int wanted = visible % stops == 0 ? visible - stops : visible - (visible % stops);
        if (wanted < 0) wanted = 0;
        int target = drawn.columnOf(wanted);
        // A tab is one character occupying several columns, so landing INSIDE one means taking it whole.
        if (target >= column) target = column - 1;
        return Math.max(lineStart, lineStart + target);
    }

    /**
     * What Enter inserts at {@code at}, and where the caret ends up.
     *
     * <h3>The two characters AROUND the caret decide it, not the line</h3>
     *
     * <p>Ported from Monaco's {@code _enter} and its {@code IndentAction}. The rule this replaces asked
     * whether the line's last character was an opener, which is a different question and answers wrongly
     * for the shape people press Enter in most: with the caret between {@code &#123;} and {@code &#125;}
     * the line <em>ends</em> in the closer, so nothing indented and the closing brace came along onto the
     * new line beside the caret. Asking what is immediately before and immediately after gives the three
     * answers Monaco names:</p>
     *
     * <ul>
     *   <li><b>IndentOutdent</b> — an opener behind and its own closer ahead. Two lines: the caret's, one
     *       level in, and the closer's, back out. This is the one that was missing.</li>
     *   <li><b>Indent</b> — an opener behind and something else ahead. One line, one level in.</li>
     *   <li><b>None</b> — carry the indentation across and nothing more.</li>
     * </ul>
     *
     * <p>The pairs come from the {@link Language}, so a language that spells its blocks differently gets
     * this for free and no bracket is written down here.</p>
     */
    public static Enter enterAt(Rope document, int at, IndentStyle style, Language language) {
        int row = document.offsetToPoint(at).row();
        int lineStart = document.lineStartOffset(row);
        String line = document.line(row);
        int column = Math.max(0, Math.min(at - lineStart, line.length()));

        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        // NEVER PAST THE CARET. Splitting a line inside its own indentation carries only what is behind
        // the caret, or the new line would be indented by whitespace still sitting on the old one.
        String carried = line.substring(0, Math.min(indent, column));

        char before = beforeCaret(line, column);
        char after = column < line.length() ? line.charAt(column) : '\0';
        Character closer = before == 0 ? null : language.structuralCloserFor(before);

        if (closer != null && after == closer) {
            String head = "\n" + carried + style.oneLevel();
            return new Enter(head + "\n" + carried, at + head.length());
        }
        if (closer != null) {
            String inserted = "\n" + carried + style.oneLevel();
            return new Enter(inserted, at + inserted.length());
        }
        String inserted = "\n" + carried;
        return new Enter(inserted, at + inserted.length());
    }

    /** The last non-space character before the caret on this line, or {@code '\0'}. */
    private static char beforeCaret(String line, int column) {
        for (int at = Math.min(column, line.length()) - 1; at >= 0; at--) {
            char c = line.charAt(at);
            if (c != ' ' && c != '\t') return c;
        }
        return '\0';
    }

    /**
     * Pasted text re-indented to where it is landing.
     *
     * <h3>What gets moved, and what deliberately does not</h3>
     *
     * <p>The <b>first line goes in untouched</b> — it is being typed at the caret, and the caret is
     * already where the user put it. Every line after it is shifted by the difference between the block's
     * own base indent and the indent it is arriving at, so a method copied out of one class and dropped
     * into another arrives at the new class's depth with its internal shape intact.</p>
     *
     * <p>The shift is measured from the <b>minimum</b> indent of the lines after the first, which is what
     * makes it a shift rather than a reformat: nothing inside the block moves relative to anything else,
     * so a nested {@code if} stays nested and a continuation line stays hanging. That is also the
     * difference between this and "format on paste", which is a separate feature and is off by default in
     * both references for the good reason that it changes code you did not write.</p>
     *
     * <h3>Only into indentation</h3>
     *
     * <p>Pasting into the middle of a line joins the first pasted line onto existing text, and what the
     * rest of the block should line up with is then genuinely ambiguous — so the text goes in as it was
     * cut. Monaco draws the same line.</p>
     */
    public static String reindentForPaste(Rope document, int at, String pasted, IndentStyle style) {
        if (pasted == null || pasted.indexOf('\n') < 0) return pasted;
        int row = document.offsetToPoint(at).row();
        int lineStart = document.lineStartOffset(row);
        String line = document.line(row);
        int column = Math.max(0, Math.min(at - lineStart, line.length()));
        if (!line.substring(0, column).trim().isEmpty()) return pasted;

        String[] lines = pasted.split("\n", -1);
        int base = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            String each = lines[i];
            if (each.trim().isEmpty()) continue;
            base = Math.min(base, visualIndentOf(each, style.width()));
        }
        if (base == Integer.MAX_VALUE) return pasted;

        int target = visualIndentOf(line.substring(0, column) + "x", style.width());
        int shift = target - base;
        if (shift == 0) return pasted;

        StringBuilder built = new StringBuilder(pasted.length()).append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            built.append('\n');
            String each = lines[i];
            if (each.trim().isEmpty()) continue;                     // no trailing indent on a blank line
            int want = Math.max(0, visualIndentOf(each, style.width()) + shift);
            built.append(indentOf(want, style)).append(each.substring(leadingWhitespace(each)));
        }
        return built.toString();
    }

    /** Where a line's text begins, counted in the columns it is drawn at. */
    private static int visualIndentOf(String line, int tabSize) {
        return CursorColumns.visibleColumn(line, leadingWhitespace(line), Math.max(1, tabSize));
    }

    private static int leadingWhitespace(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) at++;
        return at;
    }

    /** {@code columns} worth of indentation, written the way this document indents. */
    private static String indentOf(int columns, IndentStyle style) {
        if (style.insertSpaces()) return spaces(columns);
        int width = Math.max(1, style.width());
        StringBuilder built = new StringBuilder();
        for (int at = 0; at + width <= columns; at += width) built.append('\t');
        return built.append(spaces(columns % width)).toString();
    }

    /**
     * What Tab inserts at {@code at} — <b>to the next stop</b>, not a fixed number of spaces.
     *
     * <p>A tab from column six with a width of four inserts two spaces and lands on eight; the version
     * this replaces inserted four and landed on ten, which is not a stop and is why a Tab-indented block
     * drifted. In tabs mode it is one tab, which is a stop by construction.</p>
     */
    public static String tabAt(Rope document, int at, IndentStyle style) {
        if (!style.insertSpaces()) return "\t";
        int row = document.offsetToPoint(at).row();
        int column = at - document.lineStartOffset(row);
        int stops = Math.max(1, style.width());
        int visible = CursorColumns.visibleColumn(document.line(row), Math.max(0, column), stops);
        return spaces(stops - (visible % stops));
    }

    private static String spaces(int howMany) {
        StringBuilder out = new StringBuilder(Math.max(0, howMany));
        for (int i = 0; i < howMany; i++) out.append(' ');
        return out.toString();
    }

    /** What Enter produces: the text to insert, and the absolute offset the caret lands on. */
    public record Enter(String text, int caret) {
    }

    /**
     * How a document indents — what one level is, and how wide it is drawn.
     *
     * <p>Two fields because they are two questions: a file indented with tabs still needs a width to know
     * where the stops are, and one indented with spaces needs the width to know how many. Keeping them
     * together is what stops a caller answering one and assuming the other.</p>
     */
    public record IndentStyle(boolean insertSpaces, int width) {

        public static IndentStyle spaces(int width) {
            return new IndentStyle(true, width);
        }

        public static IndentStyle tabs(int width) {
            return new IndentStyle(false, width);
        }

        /** The text of one level. */
        public String oneLevel() {
            return insertSpaces ? TypeOperations.spaces(Math.max(1, width)) : "\t";
        }
    }
}
