package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.treesitter.TSInputEdit;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Syntax highlighting from a real parse tree.
 *
 * <p>The reason this is worth a native library, when a lexer would colour keywords perfectly well, is
 * that highlighting is not the only consumer of a tree: bracket matching, folding, indent rules and
 * structural selection all want one, and the shader graph will want a GLSL CST for validating node code
 * well outside the editor.</p>
 *
 * <h3>Edits are two phases, and this class keeps them apart</h3>
 * <p>Zed's {@code SyntaxMap} splits an edit into <em>interpolate</em> — apply a {@code TSInputEdit} so
 * every existing node's coordinates move with the text, no parsing — and <em>reparse</em>, which is the
 * expensive part and which it runs off the UI thread. Applying only one of the two is the trap:
 * reparse-only stutters while typing, interpolate-only leaves the tree structurally stale.</p>
 *
 * <p>{@link #edited} does both, synchronously, because our documents are shader snippets and single files
 * rather than repositories. They are kept as <b>separate statements</b> so the reparse can move to a
 * worker later without touching the edit path — which is the whole reason for noting it here rather than
 * writing one line that does both.</p>
 *
 * <h3>Everything is UTF-8 byte offsets</h3>
 * <p>tree-sitter counts bytes; this engine counts UTF-16 code units. Every crossing is converted. They
 * coincide for ASCII, which is exactly how a missing conversion survives every test anyone writes and
 * then breaks on the first accented character.</p>
 */
public final class TreeSitterTokenizer implements SyntaxTokenizer {

    /**
     * How much text a single query may cover.
     *
     * <p>Zed caps its own at 16KB for the same reason: a query over a whole large file is slow enough to
     * be felt, and the editor only ever renders a viewport's worth. The caller already asks for a bounded
     * range; this is the backstop for one that does not.</p>
     */
    private static final int MAX_BYTES_TO_QUERY = 16 * 1024;

    private final TSLanguage language;
    private final TSQuery query;
    private final TSParser parser;

    private TSTree tree;
    private String source = "";
    private byte[] sourceBytes = new byte[0];

    /** Reused across queries — cursors are not cheap and there is one per query per frame. */
    private final TSQueryCursor cursor = new TSQueryCursor();

    public TreeSitterTokenizer(TSLanguage language, String highlightQuery) {
        this.language = language;
        this.parser = new TSParser();
        this.parser.setLanguage(language);
        this.query = new TSQuery(language, highlightQuery);
    }

    /** Java, with the grammar's own {@code highlights.scm} vendored alongside. */
    public static TreeSitterTokenizer java() {
        return new TreeSitterTokenizer(new org.treesitter.TreeSitterJava(),
                Queries.load("assets/crystalgui/syntax/java/highlights.scm"));
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

    @Override
    public List<SyntaxToken> tokenize(Rope document, int from, int to) {
        String text = document.toString();
        if (tree == null || !text.equals(source)) reparse(text);
        if (tree == null) return List.of();

        int startByte = utf8Offset(text, Math.max(0, Math.min(from, text.length())));
        int endByte = utf8Offset(text, Math.max(0, Math.min(to, text.length())));
        endByte = Math.min(endByte, startByte + MAX_BYTES_TO_QUERY);

        cursor.setByteRange(startByte, endByte);
        cursor.exec(query, tree.getRootNode());

        List<SyntaxToken> tokens = new ArrayList<>();
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                TSNode node = capture.getNode();
                String name = query.getCaptureNameForId(capture.getIndex());
                if (name == null || name.isEmpty()) continue;
                int start = utf16Offset(node.getStartByte());
                int end = utf16Offset(node.getEndByte());
                if (end > start) tokens.add(new SyntaxToken(start, end, name));
            }
        }
        return tokens;
    }

    @Override
    public void edited(Rope after, ChangeSet change) {
        if (tree == null || change == null || change.isEmpty()) return;

        // PHASE 1 -- interpolate. Move every existing node's coordinates so the tree still describes the
        // text, without parsing anything. Cheap, and what keeps highlights attached to the right
        // characters the instant a key lands.
        for (Change one : change.changes()) {
            tree.edit(inputEditFor(one));
        }

        // PHASE 2 -- reparse. The expensive half, deliberately a separate statement: this is what moves to
        // a worker thread when documents get big enough to need it.
        String text = after.toString();
        reparse(text);
    }

    private void reparse(String text) {
        this.source = text;
        this.sourceBytes = text.getBytes(StandardCharsets.UTF_8);
        TSTree previous = tree;
        this.tree = previous == null
                ? parser.parseString(null, text)
                : parser.parseString(previous, text);
    }

    /** Converts one change from UTF-16 offsets into the byte offsets and points tree-sitter wants. */
    private TSInputEdit inputEditFor(Change change) {
        int startByte = utf8Offset(source, change.from());
        int oldEndByte = utf8Offset(source, change.to());
        int newEndByte = startByte + change.insert().getBytes(StandardCharsets.UTF_8).length;
        return new TSInputEdit(startByte, oldEndByte, newEndByte,
                pointAt(source, change.from()), pointAt(source, change.to()),
                pointAfterInsert(source, change.from(), change.insert()));
    }

    // ── Offsets ─────────────────────────────────────────────────────────────────────────────────

    /** UTF-16 index to UTF-8 byte offset. */
    private static int utf8Offset(String text, int utf16Index) {
        int limit = Math.max(0, Math.min(utf16Index, text.length()));
        return text.substring(0, limit).getBytes(StandardCharsets.UTF_8).length;
    }

    /** UTF-8 byte offset back to UTF-16 index, against the text last parsed. */
    private int utf16Offset(int byteOffset) {
        int limit = Math.max(0, Math.min(byteOffset, sourceBytes.length));
        return new String(sourceBytes, 0, limit, StandardCharsets.UTF_8).length();
    }

    private static TSPoint pointAt(String text, int utf16Index) {
        int limit = Math.max(0, Math.min(utf16Index, text.length()));
        int row = 0;
        int lastBreak = -1;
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                row++;
                lastBreak = i;
            }
        }
        // Points carry BYTE columns, not character columns -- the same trap as the offsets above.
        int columnBytes = text.substring(lastBreak + 1, limit).getBytes(StandardCharsets.UTF_8).length;
        return new TSPoint(row, columnBytes);
    }

    private static TSPoint pointAfterInsert(String text, int utf16Index, String inserted) {
        TSPoint start = pointAt(text, utf16Index);
        int newlines = 0;
        int lastBreak = -1;
        for (int i = 0; i < inserted.length(); i++) {
            if (inserted.charAt(i) == '\n') {
                newlines++;
                lastBreak = i;
            }
        }
        if (newlines == 0) {
            int extra = inserted.getBytes(StandardCharsets.UTF_8).length;
            return new TSPoint(start.getRow(), start.getColumn() + extra);
        }
        int tailBytes = inserted.substring(lastBreak + 1).getBytes(StandardCharsets.UTF_8).length;
        return new TSPoint(start.getRow() + newlines, tailBytes);
    }

    @Override
    public void close() {
        // The tree and parser hold native memory. Zed drops deep trees on a background thread because it
        // is slow enough to be felt; at our document sizes it is not, but the note belongs here for
        // whoever finds a frame spike on closing a large file and starts looking at the renderer.
        tree = null;
    }
}
