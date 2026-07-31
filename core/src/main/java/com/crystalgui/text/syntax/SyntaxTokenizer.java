package com.crystalgui.text.syntax;

import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;

import java.util.List;

/**
 * Turns text into named spans. The seam between the editor and any particular language.
 *
 * <p><b>Nothing in {@code core/} may know what GLSL or Java is</b>, and nothing in {@code core/} may
 * require a native library: a dedicated server builds and edits documents with no GL and no natives.
 * That is why this is an interface here and why the tree-sitter implementation lives in its own module.
 * </p>
 *
 * <h3>The range argument is the point</h3>
 * <p>{@link #tokenize} is given a range, not just a document, and implementations are expected to honour
 * it. The editor only renders the rows it has realised, so it only ever asks about those — highlighting
 * cost becomes proportional to the viewport rather than to the file, which is the same argument the
 * virtualised list is built on. Zed does exactly this, capping a single query at 16KB.</p>
 *
 * <h3>Edits are announced separately from queries</h3>
 * <p>{@link #edited} exists so an incremental implementation can update what it holds before the next
 * query arrives. A tree-sitter backend applies the edit to its tree here and reparses; a stateless lexer
 * ignores it entirely, which is why it has a default and costs nothing to not implement.</p>
 *
 * <p>The split matters more than it looks. Zed keeps these two apart deliberately — applying the edit is
 * cheap and must happen synchronously so highlights stay attached to the right text the instant a key
 * lands, while reparsing is expensive and can lag. Collapsing them into "just re-query after every edit"
 * gives up that distinction before it can be used.</p>
 */
public interface SyntaxTokenizer {

    /** A tokenizer that highlights nothing — the honest default, and what a plain text document uses. */
    SyntaxTokenizer NONE = (document, from, to) -> List.of();

    /**
     * Every token overlapping {@code [from, to)}, in UTF-16 offsets into the whole document.
     *
     * <p>Tokens may extend outside the range — a block comment spanning the viewport is one token — and
     * callers must cope. Clipping them here would report a comment as starting at the top of the screen.
     * </p>
     */
    List<SyntaxToken> tokenize(Rope document, int from, int to);

    /**
     * Tells a stateful tokenizer that the document changed, before the next {@link #tokenize}.
     *
     * @param before the document as it was, which a tree-based implementation needs to convert offsets
     * @param change what changed
     */
    default void edited(Rope before, ChangeSet change) {
        // Stateless by default. A lexer re-reads the text it is given and has nothing to update.
    }

    /** Releases anything native. Called when the editor goes away; a no-op for pure-Java tokenizers. */
    default void close() {
    }
}
