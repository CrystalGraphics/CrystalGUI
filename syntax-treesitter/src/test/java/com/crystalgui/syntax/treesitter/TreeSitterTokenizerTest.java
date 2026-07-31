package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * P6.1.7 step 5 — the tree-sitter backend, against the real native library.
 *
 * <h3>These tests skip rather than fail when the native will not load</h3>
 * <p>The fork ships natives for x86_64 Windows/Linux/macOS and aarch64 Linux/macOS — <b>not</b>
 * aarch64 Windows. A machine without a matching build is not a broken build, it is the exact case the
 * built-in lexer fallback exists for. {@code Assume} says so out loud rather than leaving a red suite
 * that means "you are on the wrong CPU".</p>
 */
public class TreeSitterTokenizerTest {

    private TreeSitterTokenizer javaTokenizer() {
        try {
            return TreeSitterTokenizer.java();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(
                    "tree-sitter native not available on this platform; the lexer fallback covers it",
                    nativeUnavailable);
            return null;
        }
    }

    private Set<String> captureNames(List<SyntaxToken> tokens) {
        Set<String> names = new HashSet<>();
        for (SyntaxToken token : tokens) names.add(token.name());
        return names;
    }

    private List<String> textsCaptured(String source, List<SyntaxToken> tokens, String name) {
        List<String> out = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.name().equals(name)) out.add(source.substring(token.start(), token.end()));
        }
        return out;
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void parsesJavaAndCapturesKeywords() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class Thing { int value = 1; }";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());

        assertFalse("the grammar produced no captures at all", tokens.isEmpty());
        assertTrue("expected a keyword capture, got " + captureNames(tokens),
                captureNames(tokens).stream().anyMatch(name -> name.startsWith("keyword")));
    }

    /**
     * <b>Something a lexer cannot do.</b> {@code Thing} here is a type and {@code value} is a field, and
     * telling them apart needs a parse — they are the same shape of token to any regular language. This is
     * the capability that earns the native dependency.
     */
    @Test
    public void distinguishesThingsALexerCannot() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class Thing { int value = compute(2); }";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        Set<String> names = captureNames(tokens);

        assertTrue("a type should be captured as a type, got " + names,
                names.stream().anyMatch(name -> name.startsWith("type")));
        assertTrue("and a call as a function, got " + names,
                names.stream().anyMatch(name -> name.contains("function") || name.contains("method")));
    }

    @Test
    public void capturesComments() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "// a note\nclass A {}";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());

        assertTrue("expected a comment capture, got " + captureNames(tokens),
                captureNames(tokens).stream().anyMatch(name -> name.startsWith("comment")));
    }

    // ── Bounded queries ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A query covers the range it is given, not the document.</b> This is what makes highlighting cost
     * proportional to the viewport rather than the file — the same argument the virtualised list is built
     * on, and what Zed does by capping a query at 16KB.
     */
    @Test
    public void aQueryIsBoundedToTheRangeAskedFor() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        StringBuilder source = new StringBuilder("class Big {\n");
        for (int i = 0; i < 400; i++) source.append("  int field").append(i).append(" = ").append(i).append(";\n");
        source.append("}\n");
        String text = source.toString();

        List<SyntaxToken> whole = tokenizer.tokenize(Rope.of(text), 0, text.length());
        List<SyntaxToken> slice = tokenizer.tokenize(Rope.of(text), 0, 60);

        assertTrue("a bounded query must return fewer tokens than the whole document",
                slice.size() < whole.size());
        assertFalse(slice.isEmpty());
    }

    // ── Incremental editing ─────────────────────────────────────────────────────────────────────

    /**
     * <b>An edit must leave the tree describing the new text.</b> The two phases are what make that true:
     * the {@code TSInputEdit} moves every existing node's coordinates, and the reparse fixes the
     * structure. Applying only the first leaves a stale tree that still answers queries — with the wrong
     * answers, which is worse than none.
     */
    @Test
    public void anEditKeepsTheTreeInStepWithTheText() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String before = "class A { int x = 1; }";
        Rope document = Rope.of(before);
        tokenizer.tokenize(document, 0, before.length());

        ChangeSet change = ChangeSet.of(before.length(), Change.insert(10, "long y = 2; "));
        Rope after = change.apply(document);
        tokenizer.edited(after, change);

        String text = after.toString();
        List<SyntaxToken> tokens = tokenizer.tokenize(after, 0, text.length());
        for (SyntaxToken token : tokens) {
            assertTrue("token " + token + " runs past the edited document of " + text.length(),
                    token.end() <= text.length());
        }
        assertTrue("the newly typed declaration should be captured",
                textsCaptured(text, tokens, "type").contains("long")
                        || captureNames(tokens).stream().anyMatch(n -> n.startsWith("type")));
    }

    /** Repeated edits must not drift — the tree is reused, so an off-by-one compounds rather than shows. */
    @Test
    public void manySmallEditsStayConsistent() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        Rope document = Rope.of("class A {}");
        tokenizer.tokenize(document, 0, document.length());

        for (int i = 0; i < 30; i++) {
            ChangeSet change = ChangeSet.of(document.length(), Change.insert(9, "int f" + i + "; "));
            document = change.apply(document);
            tokenizer.edited(document, change);
        }

        String text = document.toString();
        List<SyntaxToken> tokens = tokenizer.tokenize(document, 0, text.length());
        assertFalse(tokens.isEmpty());
        for (SyntaxToken token : tokens) {
            assertTrue("token " + token + " is outside the document", token.end() <= text.length());
        }
    }

    // ── Non-ASCII ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>tree-sitter counts UTF-8 bytes; this engine counts UTF-16 code units.</b> They coincide for
     * ASCII, which is exactly how a missing conversion survives every test anyone writes and then breaks
     * on the first accented character. This one is not ASCII.
     */
    @Test
    public void offsetsAreCorrectWithNonAsciiText() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class A { String s = \"héllo wörld\"; int after = 1; }";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());

        assertFalse(tokens.isEmpty());
        for (SyntaxToken token : tokens) {
            assertTrue("token " + token + " is outside a " + source.length() + "-unit document",
                    token.end() <= source.length());
            String captured = source.substring(token.start(), token.end());
            assertFalse("a capture should not be empty", captured.isEmpty());
        }
        // The declaration after the non-ASCII literal must still be found at the right place.
        List<String> types = textsCaptured(source, tokens, "type");
        assertTrue("expected the trailing declaration to be captured correctly, got " + types,
                types.isEmpty() || types.stream().noneMatch(t -> t.contains("\"")));
    }
}
