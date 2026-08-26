package com.crystalgui.language.grammar;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.ui.text.SyntaxHighlighting;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A code sample in a doc comment is coloured, and coloured about <em>itself</em>.</b>
 *
 * <p>{@link SyntaxHighlighting#tokenize} serves text that is not a document — a {@code <pre>} block in a
 * javadoc, a snippet in a tooltip — and its caller has one string and no second chance. It must answer on
 * the thread that asked.</p>
 *
 * <h3>Why an empty answer was invisible</h3>
 *
 * <p>It asked for the tokenizer a DOCUMENT gets, and for a tree-sitter backend that means a scheduler: the
 * first parse of a real file is far past a frame budget, so it goes to a worker and the query answers
 * nothing until it lands. Right for a file, whose view is told to ask again; wrong here. Every {@code <pre>}
 * block in the application drew as plain text — and drew, so nothing looked broken — while costing ~19ms
 * per block to arrive at nothing, because building the tokenizer compiles the grammar's whole
 * {@code highlights.scm} natively.</p>
 *
 * <p>Neither half of that is reachable from a unit test of either side. {@code DocCommentPipelineTest}
 * proves the grammar reports what the refinement looks for; {@code MarkupTest} proves a {@code <pre>} block
 * becomes a code span. Both pass while the thing between them returns an empty list.</p>
 */
public class StaticHighlightPipelineTest {

    /**
     * <b>Registered WITH a scheduler, which is what makes any of this reachable.</b>
     *
     * <p>Registering without one is the obvious spelling and it cannot see the defect: with no scheduler a
     * tree-sitter tokenizer has nowhere to defer to, so it parses on the calling thread and the document
     * tokenizer and the static one behave identically. A test written that way goes green against the
     * broken build — measured, not assumed: reverting the fix left all three assertions passing.</p>
     *
     * <p>And its executor NEVER RUNS ANYTHING, which is the half that makes this deterministic. An ordinary
     * {@code new JobScheduler()} hands the work to a real thread pool, and these samples are short enough
     * that the parse frequently lands before the assertion reads the result — so the test passes against
     * the broken build most of the time and is a race the rest. Measured: with a live pool, reverting the
     * fix left all three green.</p>
     *
     * <p>An executor that drops the job models the only thing the caller actually cares about — that the
     * answer is needed NOW, and a deferred one is no answer. The real editor's scheduler does run the job,
     * a frame or several later, which for a document is right and for a popover being built is too late.</p>
     */
    @BeforeClass
    public static void grammarsRegistered() {
        try {
            JobScheduler neverRuns = new JobScheduler(job -> {
            }, () -> 0L, 1);
            Assume.assumeTrue("no tree-sitter grammar on this platform",
                    TreeSitterLanguages.register(neverRuns));
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
        }
    }

    /**
     * Everything {@link com.crystalgui.text.syntax.KeywordTokenizer} can say, and nothing else.
     *
     * <p>Six bare names. A word list can tell a keyword from a string from a number and cannot tell a
     * method DECLARATION from a CALL, or a type from a builtin one — so any dotted or unlisted capture is
     * proof a parser answered.</p>
     */
    private static final Set<String> WHAT_A_WORD_LIST_CAN_SAY =
            Set.of("comment", "function", "keyword", "number", "string", "type");

    /**
     * <b>The grammar answers, on the calling thread.</b>
     *
     * <h3>Non-empty is not the assertion — which TIER answered is</h3>
     *
     * <p>Tier three catches an empty grammar answer with a lexer, deliberately, so the sample is coloured
     * either way and {@code isEmpty()} is false in both worlds. Measured in the harness: with the fix the
     * real 527-character sample reports {@code 57 tokens (grammar)}; with the document tokenizer restored
     * it reports {@code 21 tokens (keywords)}. A test that only checks for tokens goes green on the second
     * one, and the whole application quietly colours every code sample from a word list.</p>
     *
     * <p>So it asserts a capture outside {@link #WHAT_A_WORD_LIST_CAN_SAY}. The overlap is what makes the
     * obvious assertion useless — the lexer really does report {@code type} for {@code void} and
     * {@code function} for {@code run()}, so the first version of this test checked for exactly those and
     * passed against the bug.</p>
     */
    @Test
    public void aJavaSampleIsTokenizedByTheGrammarOnTheCallingThread() {
        List<SyntaxToken> got = SyntaxHighlighting.tokenize("class W { void run() { greet(); } }",
                Language.JAVA);

        assertFalse("a code sample must be coloured by the thread that asked for it", got.isEmpty());
        assertTrue("a word list answered this, not a parser -- got " + names(got),
                got.stream().anyMatch(token -> !WHAT_A_WORD_LIST_CAN_SAY.contains(token.name())));
    }

    /**
     * <b>The second sample is coloured about the second sample.</b>
     *
     * <p>The tokenizer is kept per language rather than built per call — the native query compile is
     * otherwise paid once per {@code <pre>} block — and a kept tree-sitter tokenizer is STATEFUL: it holds
     * the tree for the last text it saw and re-parses only when told the text changed. Reused without
     * announcing the change it answers the PREVIOUS sample's tokens, at the previous sample's offsets.</p>
     *
     * <p>Which is worse than colouring nothing: a doc comment with two samples would colour the first
     * correctly and paint the first one's spans over the second one's text. Non-empty is what a broken
     * version returns.</p>
     */
    @Test
    public void aSecondSampleIsNotColouredWithTheFirstOnesTokens() {
        List<SyntaxToken> first = SyntaxHighlighting.tokenize("class Alpha {}", Language.JAVA);
        List<SyntaxToken> second = SyntaxHighlighting.tokenize(
                "int total = 40 + 2; String label = \"done\";", Language.JAVA);

        assertFalse(first.isEmpty());
        assertFalse(second.isEmpty());
        assertNotEquals("the kept tokenizer answered about the previous sample",
                spans(first), spans(second));
        assertTrue("expected the second sample's own literals, got " + names(second),
                second.stream().anyMatch(token -> token.name().startsWith("string"))
                        && second.stream().anyMatch(token -> token.name().startsWith("number")));
    }

    /** Asking twice must give the same answer — the state is a cache, not a one-shot. */
    @Test
    public void theSameSampleAnswersTheSameTwice() {
        String source = "class Alpha { int n = 1; }";
        assertNotEquals(List.of(), spans(SyntaxHighlighting.tokenize(source, Language.JAVA)));
        assertEqualsSpans(SyntaxHighlighting.tokenize(source, Language.JAVA),
                SyntaxHighlighting.tokenize(source, Language.JAVA));
    }

    private static void assertEqualsSpans(List<SyntaxToken> a, List<SyntaxToken> b) {
        assertEquals(spans(a), spans(b));
    }

    private static List<String> spans(List<SyntaxToken> tokens) {
        return tokens.stream()
                .map(token -> token.name() + "@" + token.start() + ".." + token.end())
                .collect(Collectors.toList());
    }

    private static String names(List<SyntaxToken> tokens) {
        return tokens.stream().map(SyntaxToken::name).distinct().collect(Collectors.joining(", "));
    }
}
