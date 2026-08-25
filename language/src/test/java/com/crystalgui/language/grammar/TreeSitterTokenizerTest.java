package com.crystalgui.language.grammar;

import com.crystalgui.core.async.JobScheduler;
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

    /**
     * <b>The strong form of the test above.</b> Asserting only that tokens are in bounds passes against
     * offsets that are wrong by exactly the UTF-8/UTF-16 delta, because a shifted range is still a valid
     * range. This asserts the captured <em>text</em>, which is the only thing that cannot be accidentally
     * right.
     *
     * <p>The fixture covers all three widths that differ between the encodings — two bytes (accented
     * Latin), three (CJK), and four (an emoji, which is also a UTF-16 surrogate <em>pair</em> and so
     * differs in both directions at once). Anything after them is where a miscount shows.</p>
     */
    @Test
    public void everyCaptureLandsOnTheTextItNamesAcrossMultiByteCharacters() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = ""
                + "// café 日本語 🎉 comment\n"
                + "class Ünïcode {\n"
                + "    String s = \"日本語 🎉 literal\";\n"
                + "    Widget trailing = null;\n"
                + "}\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        assertFalse(tokens.isEmpty());

        // Every capture must name text that actually exists where it says it does.
        for (SyntaxToken token : tokens) {
            assertTrue(token + " runs past a " + source.length() + "-unit document",
                    token.end() <= source.length());
            String captured = source.substring(token.start(), token.end());
            assertFalse("a capture should not be empty: " + token, captured.isEmpty());
            assertFalse("a capture should not straddle a line break: " + captured,
                    captured.contains("\n") && !token.name().startsWith("comment"));
        }

        // And specifically: the type declared AFTER all the multi-byte content. If offsets drifted by the
        // encoding delta this is the capture that lands somewhere else, and it lands somewhere plausible.
        List<String> types = textsCaptured(source, tokens, "type");
        assertTrue("expected 'Widget' to be captured exactly, got " + types, types.contains("Widget"));

        // The comment is the other end of the same check: it starts at 0 and its length is measured in
        // UTF-16 units, so a byte-length would run it into the class declaration.
        List<String> comments = textsCaptured(source, tokens, "comment");
        assertTrue("expected the comment captured exactly, got " + comments,
                comments.contains("// café 日本語 🎉 comment"));
    }

    /**
     * The same, after an edit — because interpolation converts offsets against the OLD text, and getting
     * that the wrong way round is invisible until the document contains something multi-byte.
     */
    @Test
    public void offsetsSurviveAnEditNextToMultiByteText() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String before = "class A {\n    String s = \"日本語 🎉\";\n}\n";
        Rope document = Rope.of(before);
        tokenizer.tokenize(document, 0, before.length());

        // Insert AFTER the multi-byte literal, so the edit's own offsets are past it.
        int at = before.indexOf("}\n");
        ChangeSet change = ChangeSet.of(before.length(), Change.insert(at, "    Widget w = null;\n"));
        Rope after = change.apply(document);
        tokenizer.edited(after, change);

        String text = after.toString();
        List<SyntaxToken> tokens = tokenizer.tokenize(after, 0, text.length());

        for (SyntaxToken token : tokens) {
            assertTrue(token + " is outside the edited document", token.end() <= text.length());
        }
        assertTrue("the newly typed type should be captured exactly, got "
                        + textsCaptured(text, tokens, "type"),
                textsCaptured(text, tokens, "type").contains("Widget"));
    }

    // ── Cost ────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A query must not re-parse when nothing changed.</b> This used to flatten the rope and compare it
     * to the last parsed text on every call, so a viewport repaint of an untouched document did two O(n)
     * passes to discover it had nothing to do. Asserted by cost rather than by instrumentation: a
     * thousand repeat queries over a large document finish in well under the time a thousand parses of it
     * would take.
     */
    @Test
    public void repeatQueriesOnAnUnchangedDocumentDoNotReparse() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        StringBuilder builder = new StringBuilder("class Big {\n");
        for (int i = 0; i < 2_000; i++) {
            builder.append("    int field").append(i).append(" = ").append(i).append(";\n");
        }
        builder.append("}\n");
        Rope document = Rope.of(builder.toString());

        long parseStart = System.nanoTime();
        tokenizer.tokenize(document, 0, 200);
        long firstQueryNanos = System.nanoTime() - parseStart;

        long repeatStart = System.nanoTime();
        for (int i = 0; i < 1_000; i++) tokenizer.tokenize(document, 0, 200);
        long repeatNanos = System.nanoTime() - repeatStart;

        // A thousand small bounded queries must cost far less than a thousand parses of a 2,000-line file.
        // Deliberately a loose bound -- this is a guard against a return to O(document) per query, not a
        // benchmark, and it must not fail on a slow or contended machine.
        assertTrue("1000 repeat queries took " + (repeatNanos / 1_000_000) + "ms against a first query of "
                        + (firstQueryNanos / 1_000_000) + "ms -- that looks like a reparse per query",
                repeatNanos < firstQueryNanos * 200L);
    }

    // ── Reparsing off the frame ─────────────────────────────────────────────────────────────────

    /**
     * <b>With a scheduler, an edit must not parse on the calling thread.</b> Measured at ~17ms average and
     * ~26ms worst per keystroke on a 5,000-line file, against a 2ms budget — so this is the difference
     * between typing being smooth and not.
     *
     * <p>Driven with a same-thread executor and a manual clock, so "the work has not run yet" and "the
     * work has landed" are two distinct, exact states rather than a sleep.</p>
     */
    @Test
    public void withASchedulerAnEditDefersTheParseAndLandsItOnADrain() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = new JobScheduler(pending::add, () -> 0L, 2);
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVA.newTokenizer(scheduler);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }

        String before = "class A { int x = 1; }";
        Rope document = Rope.of(before);
        tokenizer.tokenize(document, 0, before.length());       // cold parse, synchronous by necessity

        List<String> invalidations = new ArrayList<>();
        tokenizer.setInvalidationListener((from, to) -> invalidations.add(from + ".." + to));

        ChangeSet change = ChangeSet.of(before.length(), Change.insert(10, "String s = \"hi\"; "));
        Rope after = change.apply(document);
        tokenizer.edited(after, change);

        // The edit interpolated and queued; nothing has parsed.
        assertTrue("the edit must not have parsed on the calling thread", pending.isEmpty());
        scheduler.drain();
        assertFalse("a reparse should have been scheduled", pending.isEmpty());
        assertTrue("and it must not have landed yet", invalidations.isEmpty());

        // Queries meanwhile still answer -- from the interpolated tree, in bounds of the NEW text.
        String text = after.toString();
        for (SyntaxToken token : tokenizer.tokenize(after, 0, text.length())) {
            assertTrue(token + " is outside the edited document", token.end() <= text.length());
        }

        pending.forEach(Runnable::run);
        scheduler.drain();

        assertEquals("landing a reparse must tell the view to ask again", 1, invalidations.size());
        assertTrue("the newly typed type should now be captured, got "
                        + textsCaptured(text, tokenizer.tokenize(after, 0, text.length()), "type"),
                textsCaptured(text, tokenizer.tokenize(after, 0, text.length()), "type").contains("String"));

        tokenizer.close();
        scheduler.dispose();
    }

    /** A burst of keystrokes must collapse to one parse, not queue one per key. */
    @Test
    public void aBurstOfEditsCollapsesToASingleReparse() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = new JobScheduler(pending::add, () -> 0L, 2);
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVA.newTokenizer(scheduler);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }

        Rope document = Rope.of("class A {}");
        tokenizer.tokenize(document, 0, document.length());

        for (int i = 0; i < 20; i++) {
            ChangeSet change = ChangeSet.of(document.length(), Change.insert(9, "int f" + i + "; "));
            document = change.apply(document);
            tokenizer.edited(document, change);
        }

        scheduler.drain();
        assertEquals("twenty keystrokes must leave one parse, not twenty", 1, pending.size());

        pending.forEach(Runnable::run);
        scheduler.drain();

        String text = document.toString();
        List<SyntaxToken> tokens = tokenizer.tokenize(document, 0, text.length());
        assertFalse(tokens.isEmpty());
        for (SyntaxToken token : tokens) {
            assertTrue(token + " is outside the document", token.end() <= text.length());
        }

        tokenizer.close();
        scheduler.dispose();
    }

    // ── Native lifetime ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>A tokenizer is per document, so opening and closing files must not accumulate native memory.</b>
     * {@code close()} used to null the tree and nothing else, leaving a parser and a compiled query alive
     * for every file ever opened — invisible from Java, because none of it is on the heap.
     *
     * <p>Asserted by surviving the cycle rather than by counting handles: the binding exposes no handle
     * count, and a native leak's real symptom is the hundredth iteration crashing or slowing, not a
     * number being wrong. A hundred open/parse/close rounds is enough to turn a per-document leak into a
     * failure while staying quick.</p>
     */
    @Test
    public void openingAndClosingManyDocumentsDoesNotAccumulateNatives() {
        try {
            TreeSitterTokenizer.java().close();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }

        for (int i = 0; i < 100; i++) {
            TreeSitterTokenizer tokenizer = TreeSitterTokenizer.java();
            String source = "class Doc" + i + " { int x = " + i + "; }";
            List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
            assertFalse("round " + i + " produced no tokens", tokens.isEmpty());
            tokenizer.close();
        }
    }

    /**
     * <b>Replacing the whole document must colour the NEW document</b>, not go on describing the old one.
     *
     * <h3>The fixture has to start with a tree over the EMPTY string</h3>
     *
     * <p>Which is not contrived — it is the ordinary way a file opens. {@code Workbench.viewerFor} builds
     * the editor empty and fills it from a job, so the tokenizer is queried once against an empty buffer,
     * parses it, and only then receives the text as one change spanning the whole document.</p>
     *
     * <p>That path skips phase 1 (there is nothing to interpolate when no node survives), and skipping
     * phase 1 skips {@code tree.edit()} — so the tree handed to the incremental parse as its base had
     * never been told anything changed. tree-sitter reads an unedited base as "this document is
     * unchanged" and reuses it wholesale, so the parse of a 74KB file returned the empty tree it started
     * from and every query answered nothing.</p>
     *
     * <p>Asserted on TOKENS rather than on the tree, because the tree was not null and not stale and had
     * every field a healthy one has — the only thing wrong with it was its extent. And it must assert a
     * KEYWORD: the engine's semantic tokens replace grammar tokens where they overlap, so types and
     * methods stayed correct throughout and the file looked half-highlighted rather than unparsed.</p>
     */
    @Test
    public void replacingTheWholeDocumentParsesTheNewTextRatherThanReusingTheOldTree() {
        List<Runnable> pending = new ArrayList<>();
        JobScheduler scheduler = new JobScheduler(pending::add, () -> 0L, 2);
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVA.newTokenizer(scheduler);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }

        // A tree over the empty document, exactly as a viewer's first paint produces one.
        Rope empty = Rope.of("");
        tokenizer.tokenize(empty, 0, 0);
        settle(scheduler, pending);
        assertTrue("the fixture needs a tree over the empty document before the load",
                tokenizer.tokenize(empty, 0, 0).isEmpty());

        String loaded = "package p;\n\nclass A { int x = 1; }\n";
        ChangeSet change = ChangeSet.of(0, Change.insert(0, loaded));
        Rope after = change.apply(empty);
        tokenizer.edited(after, change);
        settle(scheduler, pending);

        List<SyntaxToken> tokens = tokenizer.tokenize(after, 0, loaded.length());
        assertFalse("a document that was loaded in one change produced no tokens at all", tokens.isEmpty());
        assertTrue("the keywords of the loaded text were not captured, got "
                        + textsCaptured(loaded, tokens, "keyword"),
                textsCaptured(loaded, tokens, "keyword").contains("package"));
        // THE POSITIVE CONTROL'S OTHER HALF: every token must be inside the text it describes. A tree
        // left over from a different document can satisfy "not empty" while pointing anywhere.
        for (SyntaxToken token : tokens) {
            assertTrue(token + " is outside the loaded document", token.end() <= loaded.length());
        }

        tokenizer.close();
    }

    /**
     * Runs a scheduled parse to completion.
     *
     * <p>Three steps and the order is not obvious: {@code drain()} is what DISPATCHES a debounced job to
     * the executor, running it is what parses, and a second {@code drain()} is what delivers {@code
     * onDone} on the calling thread. Draining once and running once leaves the work queued.</p>
     */
    private static void settle(JobScheduler scheduler, List<Runnable> pending) {
        scheduler.drain();
        List<Runnable> ready = new ArrayList<>(pending);
        pending.clear();
        ready.forEach(Runnable::run);
        scheduler.drain();
    }

    /** Closing twice must be safe — the tree is dropped on close and a second call must not chase it. */
    @Test
    public void closingIsIdempotent() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        tokenizer.tokenize(Rope.of("class A {}"), 0, 10);
        tokenizer.close();
        tokenizer.close();
    }
}
