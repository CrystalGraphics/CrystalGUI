package com.crystalgui.language.js;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;

import org.mozilla.javascript.ast.AstNode;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole edit substrate for the JavaScript fix catalog — replace, insert, delete, wrap.
 *
 * <h3>Why this is a hundred lines where Java's is a rewriter</h3>
 *
 * <p>{@code Rewrites} drives JDT's {@code ASTRewrite}: it describes a change to the <em>tree</em> and lets
 * JDT re-print the affected region, because a Java edit routinely needs the printer — inserting an import
 * in the right group, adding a modifier in the canonical order, materialising a method body with the
 * project's formatting. Rhino has no rewriter and needs none: every node reports an absolute position and
 * a length, so a JavaScript fix is a substring replacement at coordinates the parser already gave us.</p>
 *
 * <p>That is not a shortcut. Text edits are what the document takes ({@code ChangeSet}), so producing them
 * directly means the fix does not round-trip through a printer that would re-format code the author wrote
 * — which is the one thing an unasked-for edit must never do.</p>
 *
 * <h3>Every edit is one {@code ChangeSet}, stamped with the analysis version</h3>
 *
 * <p>One set rather than several, so applying a fix is <b>one undo step</b> however many places it touches
 * — the same rule the Java catalog and the completion list's additional edits both keep. The version comes
 * from the analysis the offsets were computed against; a document that has moved on refuses it rather than
 * applying an edit at coordinates that now mean something else.</p>
 */
final class JsRewrites {

    private final String source;
    private final long version;

    JsRewrites(String source, long version) {
        this.source = source == null ? "" : source;
        this.version = version;
    }

    long version() {
        return version;
    }

    /** The text a node covers, or "" — what a fix quotes when it is moving code rather than writing it. */
    String textOf(@Nullable AstNode node) {
        if (node == null) return "";
        int from = clamp(node.getAbsolutePosition());
        int to = clamp(from + Math.max(0, node.getLength()));
        return source.substring(from, to);
    }

    String textIn(int from, int to) {
        int start = clamp(from);
        return source.substring(start, clamp(Math.max(start, to)));
    }

    // ── The four primitives ─────────────────────────────────────────────────────────────────────

    ChangeSet replace(int from, int to, String insert) {
        return set(new Change(clamp(from), clamp(Math.max(from, to)), insert == null ? "" : insert));
    }

    ChangeSet replaceNode(AstNode node, String insert) {
        int from = node.getAbsolutePosition();
        return replace(from, from + node.getLength(), insert);
    }

    ChangeSet insertAt(int offset, String insert) {
        return replace(offset, offset, insert);
    }

    /**
     * Deletes a range and the whitespace that would be left behind.
     *
     * <p>Removing {@code var unused = 1;} and leaving its blank line is a fix that produces a second thing
     * to tidy, which is how an automated edit teaches people not to trust it. The trailing newline goes
     * with the statement, and the leading indentation with it, so the surrounding lines close up.</p>
     */
    ChangeSet deleteStatement(int from, int to) {
        int start = clamp(from);
        int end = clamp(Math.max(start, to));
        // BACK OVER THE INDENT, but never past the line break above -- taking that would join this
        // statement's line to the one before it.
        while (start > 0 && isSpaceOrTab(source.charAt(start - 1))) start--;
        // AND FORWARD OVER THE LINE BREAK, so the line disappears rather than becoming an empty one.
        if (end < source.length() && source.charAt(end) == '\r') end++;
        if (end < source.length() && source.charAt(end) == '\n') end++;
        else if (start > 0 && source.charAt(start - 1) == '\n') start--;
        return replace(start, end, "");
    }

    /** Wraps a range in {@code before}/{@code after} — two changes, one undo step. */
    ChangeSet wrap(int from, int to, String before, String after) {
        int start = clamp(from);
        int end = clamp(Math.max(start, to));
        List<Change> changes = new ArrayList<>(2);
        changes.add(new Change(start, start, before));
        changes.add(new Change(end, end, after));
        return ChangeSet.of(source.length(), changes);
    }

    // ── Reading the text around an offset ───────────────────────────────────────────────────────

    /** The indentation of the line {@code offset} is on — what an inserted statement should match. */
    String indentAt(int offset) {
        int at = clamp(offset);
        int lineStart = at;
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') lineStart--;
        int end = lineStart;
        while (end < source.length() && isSpaceOrTab(source.charAt(end))) end++;
        return source.substring(lineStart, end);
    }

    /** Where the line containing {@code offset} begins. */
    int lineStartAt(int offset) {
        int at = clamp(offset);
        while (at > 0 && source.charAt(at - 1) != '\n') at--;
        return at;
    }

    private ChangeSet set(Change change) {
        return ChangeSet.of(source.length(), change);
    }

    private int clamp(int offset) {
        return Math.max(0, Math.min(offset, source.length()));
    }

    private static boolean isSpaceOrTab(char c) {
        return c == ' ' || c == '\t';
    }
}
