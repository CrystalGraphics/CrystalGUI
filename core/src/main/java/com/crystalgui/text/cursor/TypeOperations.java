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
     */
    public static int backspaceFrom(Rope document, int head, int indentWidth) {
        int row = document.offsetToPoint(head).row();
        int lineStart = document.lineStartOffset(row);
        int column = head - lineStart;
        if (column == 0) return Math.max(0, head - 1);

        String text = document.line(row);
        if (!text.substring(0, column).isBlank()) return head - 1;
        int back = column % Math.max(1, indentWidth);
        if (back == 0) back = Math.max(1, indentWidth);
        return Math.max(lineStart, head - back);
    }
}
