package com.crystalgui.language.grammar;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The grammar's SCREAMING_CASE test for a constant has to actually run.
 *
 * <h3>What broke</h3>
 * <p><b>A pattern carrying a predicate never yields a match through this binding.</b> Of the Java
 * grammar's 25 patterns the ones that fire are {@code [0,1,2,7,9,10,15,16,18,19,20,24]}; the five absent
 * are exactly those with {@code #match?} on them. So {@code @constant} — which is how a grammar
 * distinguishes {@code MAX_RETRIES} from {@code retries}, there being no other way to know — contributed
 * nothing at all, and every constant, enum constant and static field rendered as a plain identifier.</p>
 *
 * <p>{@code Queries} lifts such a predicate out of the query text so the pattern compiles bare, and the
 * tokenizer re-applies it. The lift is deliberately conservative: only a capture whose <em>every</em> use
 * is guarded by the same test is lifted, because the re-application can only match on the capture name.
 * Both halves are asserted below — the one that must be lifted, and the one that must not.</p>
 */
public class LiftedPredicateTest {

    private TreeSitterTokenizer javaTokenizer() {
        try {
            return TreeSitterTokenizer.java();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return null;
        }
    }

    private List<String> textsCaptured(String source, List<SyntaxToken> tokens, String name) {
        List<String> out = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.name().equals(name)) out.add(source.substring(token.start(), token.end()));
        }
        return out;
    }

    @Test
    public void screamingCaseIdentifiersAreConstantsAndOthersAreNot() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class A {\n"
                + "    static final int MAX_RETRIES = 5;\n"
                + "    private int retries = 0;\n"
                + "    enum E { TRACE, DEBUG }\n"
                + "}\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        List<String> constants = textsCaptured(source, tokens, "constant");

        assertTrue("MAX_RETRIES must be a constant, got " + constants, constants.contains("MAX_RETRIES"));
        assertTrue("enum constants too, got " + constants, constants.contains("TRACE"));
        // The predicate is the ONLY thing separating these; without it every identifier is a constant.
        assertFalse("an ordinary identifier must not be, got " + constants, constants.contains("retries"));
        assertFalse(constants.contains("A"));
        tokenizer.close();
    }

    /**
     * <b>An ambiguous predicate must NOT be lifted.</b> {@code @type} is captured by a dozen patterns and
     * guarded in only four of them, so re-applying {@code ^[A-Z]} to every {@code @type} would delete
     * colouring the grammar states outright. Under-reaching costs a colour; over-reaching removes one that
     * works, so the conservative side is the correct one.
     */
    /**
     * <b>A refining capture must come AFTER the blanket one.</b>
     *
     * <p>The Java grammar's first pattern is {@code (identifier) @variable} and it matches every
     * identifier in the file; {@code @constant}, {@code @type} and {@code @function.method} arrive later
     * to say what a given identifier actually <em>is</em>. A consumer resolves the two by taking the last,
     * so the order this method returns them in IS the precedence.</p>
     *
     * <p>The cursor's own order does not encode that — it yields matches in node order, which interleaves
     * patterns arbitrarily. Measured on one document before this was sorted: {@code label} came back as
     * {@code [function.method, variable]} and {@code TRACE} as {@code [variable, constant]}, so
     * last-wins made the constant purple and the method plain from the same rule. <b>Both halves are
     * asserted here because fixing one by hand broke the other, twice.</b></p>
     */
    @Test
    public void aRefiningCaptureIsOrderedAfterTheBlanketOne() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class A {\n"
                + "    static final int MAX_RETRIES = 5;\n"
                + "    public String label() { return null; }\n"
                + "}\n";
        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());

        assertTrue("a constant must outrank the blanket @variable on the same text",
                lastNameOf(source, tokens, "MAX_RETRIES").equals("constant"));
        assertTrue("and so must a method declaration",
                lastNameOf(source, tokens, "label").equals("function.method"));
        tokenizer.close();
    }

    /** The last capture name emitted for a given piece of text — i.e. the one that wins. */
    private String lastNameOf(String source, List<SyntaxToken> tokens, String text) {
        String last = "";
        for (SyntaxToken token : tokens) {
            if (source.substring(token.start(), token.end()).equals(text)) last = token.name();
        }
        return last;
    }

    @Test
    public void anAmbiguouslyGuardedCaptureKeepsWorkingUnfiltered() {
        TreeSitterTokenizer tokenizer = javaTokenizer();
        String source = "class A { String s; int i; }\n";

        List<String> types = textsCaptured(source,
                tokenizer.tokenize(Rope.of(source), 0, source.length()), "type");

        assertTrue("plain type identifiers must still be captured, got " + types, types.contains("String"));
        tokenizer.close();
    }
}
