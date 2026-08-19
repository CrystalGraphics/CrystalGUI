package com.crystalgui.language.grammar;

import com.crystalgui.text.Rope;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.fold.IndentRangeProvider;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>M11 §24.3 and §24.5 — folds and scopes, from the tree rather than from the shape of the text.</b>
 *
 * <p>Both families were deferred at M3 with a stated reason and both are answered by the same parse the
 * highlighter already runs. What is asserted here is the difference each makes, not that a file loads: a
 * fold at a real block boundary rather than at an indentation change, and a parameter told from a field
 * with no engine anywhere in the picture.</p>
 */
public class QueryFamiliesTest {

    /** Skips cleanly where a native will not load — the same guard every grammar test here uses. */
    private static TreeSitterTokenizer tokenizerFor(Grammar grammar) {
        try {
            return grammar.newTokenizer(null);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException("tree-sitter native unavailable on this platform", nativeUnavailable);
            return null;
        }
    }

    private static List<int[]> foldsOf(Grammar grammar, String source) {
        TreeSitterTokenizer tokenizer = tokenizerFor(grammar);
        FoldingRegions regions = tokenizer.compute(Rope.of(source), 4);
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < regions.length(); i++) {
            found.add(new int[]{regions.getStartLineNumber(i), regions.getEndLineNumber(i)});
        }
        tokenizer.close();
        return found;
    }

    private static boolean hasFold(List<int[]> folds, int start, int end) {
        for (int[] fold : folds) {
            if (fold[0] == start && fold[1] == end) return true;
        }
        return false;
    }

    // ── 24.3 folds ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The criterion.</b> A Java fixture folds at its block boundaries rather than at its indentation.
     *
     * <p>The distinction the indentation provider cannot make is on line 3: a continuation line that is
     * indented further carries no block and must not be foldable, while the method's braces are one
     * region whatever the body is indented to.</p>
     */
    @Test
    public void aJavaFileFoldsAtItsBlocksRatherThanItsIndentation() {
        String source = "class A {\n"
                + "    void run() {\n"
                + "        int total = one\n"
                + "                + two;\n"
                + "    }\n"
                + "}\n";
        List<int[]> folds = foldsOf(Grammar.JAVA, source);
        assertFalse("no regions at all", folds.isEmpty());
        assertTrue("the class body is not foldable: " + describe(folds), hasFold(folds, 0, 5));
        assertTrue("the method body is not foldable: " + describe(folds), hasFold(folds, 1, 4));
        for (int[] fold : folds) {
            assertFalse("a wrapped EXPRESSION was treated as a foldable block, which is the whole "
                    + "difference from indentation folding: " + describe(folds), fold[0] == 2);
        }
    }

    /** And GLSL, which is the engineless half of the criterion. */
    @Test
    public void aGlslFileFoldsAtItsBlocks() {
        String source = "void main() {\n"
                + "    if (true) {\n"
                + "        gl_FragColor = vec4(1.0);\n"
                + "    }\n"
                + "}\n";
        List<int[]> folds = foldsOf(Grammar.GLSL, source);
        assertFalse("no regions at all", folds.isEmpty());
        assertTrue("the function is not foldable: " + describe(folds), hasFold(folds, 0, 4));
        assertTrue("the if is not foldable: " + describe(folds), hasFold(folds, 1, 3));
    }

    /**
     * A one-line construct is not a fold, whatever the query captured.
     *
     * <p>There is nothing to hide, so an arrow beside it is an affordance that does nothing when clicked
     * — which is worse than no arrow, since a reader learns to distrust the gutter.</p>
     */
    @Test
    public void aSingleLineBlockIsNotFoldable() {
        List<int[]> folds = foldsOf(Grammar.JAVA, "class A { void run() { return; } }\n");
        for (int[] fold : folds) {
            assertTrue("a region that hides nothing was offered: " + describe(folds), fold[1] > fold[0]);
        }
    }

    /** Regions come back sorted and strictly nested, which {@code FoldingRegions} assumes rather than checks. */
    @Test
    public void regionsAreSortedAndNested() {
        String source = "class A {\n"
                + "    void one() {\n"
                + "        if (x) {\n"
                + "            y();\n"
                + "        }\n"
                + "    }\n"
                + "    void two() {\n"
                + "        z();\n"
                + "    }\n"
                + "}\n";
        List<int[]> folds = foldsOf(Grammar.JAVA, source);
        for (int i = 1; i < folds.size(); i++) {
            assertTrue("not sorted by start row: " + describe(folds),
                    folds.get(i)[0] >= folds.get(i - 1)[0]);
            assertTrue("two regions claim the same start row, so one arrow has two meanings: "
                    + describe(folds), folds.get(i)[0] != folds.get(i - 1)[0]);
        }
        // Strict nesting: a later region either sits inside an earlier one or entirely after it.
        for (int i = 0; i < folds.size(); i++) {
            for (int j = i + 1; j < folds.size(); j++) {
                int[] a = folds.get(i);
                int[] b = folds.get(j);
                boolean nested = b[0] >= a[0] && b[1] <= a[1];
                boolean after = b[0] > a[1];
                assertTrue("regions overlap without nesting: " + describe(folds), nested || after);
            }
        }
    }

    /**
     * A language with no {@code folds.scm} keeps the indentation provider, which is the whole rider.
     *
     * <p>Asserted through a tokenizer built from a bare query string, which is what an injected child and
     * every test that supplies its own query is — it has no grammar directory and therefore no families.</p>
     */
    @Test
    public void aTokenizerWithNoFamilyDirectoryFoldsNothingAndDefersToIndentation() {
        TreeSitterTokenizer bare;
        try {
            bare = new TreeSitterTokenizer(Grammar.JAVA.newParser(), "(program) @none");
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException("tree-sitter native unavailable", nativeUnavailable);
            return;
        }
        Rope document = Rope.of("class A {\n    void run() {\n        x();\n    }\n}\n");
        assertEquals("a tokenizer with no vendored directory invented regions", 0,
                bare.compute(document, 4).length());
        bare.close();

        // And the fallback still answers for it, which is what makes this additive rather than a swap.
        FoldingRangeProvider indentation = IndentRangeProvider.plain();
        assertTrue("the indentation provider stopped answering", indentation.compute(document, 4).length() > 0);
    }

    // ── 24.5 locals ─────────────────────────────────────────────────────────────────────────────

    private static List<SyntaxToken> tokensOf(Grammar grammar, String source) {
        TreeSitterTokenizer tokenizer = tokenizerFor(grammar);
        List<SyntaxToken> tokens = new ArrayList<>(
                tokenizer.tokenize(Rope.of(source), 0, source.length()));
        tokenizer.close();
        return tokens;
    }

    /** The capture names covering {@code needle}'s first occurrence, in the order they were emitted. */
    private static List<String> capturesAt(List<SyntaxToken> tokens, String source, String needle) {
        int at = source.indexOf(needle);
        assertTrue("the fixture does not contain " + needle, at >= 0);
        List<String> names = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.start() <= at && at < token.end()) names.add(token.name());
        }
        return names;
    }

    /** The LAST capture wins, which is the rule the editor's own merge applies. */
    private static String colourOf(List<SyntaxToken> tokens, String source, String needle) {
        List<String> names = capturesAt(tokens, source, needle);
        assertFalse("nothing at all captured " + needle, names.isEmpty());
        return names.get(names.size() - 1);
    }

    /**
     * <b>The criterion.</b> A GLSL parameter and a plain local colour differently, with no engine loaded.
     *
     * <p>This is the case §24.5 was justified by: GLSL has no engine and never will, so a grammar's
     * blanket {@code @variable} is the only thing that ever reaches these names — and it says the same
     * word for both.</p>
     */
    @Test
    public void aGlslParameterAndALocalColourDifferently() {
        String source = "vec4 shade(float intensity) {\n"
                + "    float scaled = intensity;\n"
                + "    return vec4(scaled);\n"
                + "}\n";
        List<SyntaxToken> tokens = tokensOf(Grammar.GLSL, source);
        assertEquals("a parameter is not drawn as one", "variable.parameter",
                colourOf(tokens, source, "intensity"));
        assertEquals("a local is not drawn as one", "variable",
                colourOf(tokens, source, "scaled"));
    }

    /** And a REFERENCE takes its definition's colour, which is the whole mechanism. */
    @Test
    public void aReferenceIsColouredAsWhateverDefinedIt() {
        String source = "vec4 shade(float intensity) {\n"
                + "    return vec4(intensity);\n"
                + "}\n";
        List<SyntaxToken> tokens = tokensOf(Grammar.GLSL, source);
        int use = source.lastIndexOf("intensity");
        String colour = null;
        for (SyntaxToken token : tokens) {
            if (token.start() <= use && use < token.end()) colour = token.name();
        }
        assertEquals("a use of a parameter is not drawn as a parameter", "variable.parameter", colour);
    }

    /** JavaScript is the other engineless case the scheme names, and it gets the same separation. */
    @Test
    public void aJavaScriptParameterIsToldFromALocal() {
        String source = "function total(rate) {\n"
                + "    var sum = rate;\n"
                + "    return sum;\n"
                + "}\n";
        List<SyntaxToken> tokens = tokensOf(Grammar.JAVASCRIPT, source);
        assertEquals("variable.parameter", colourOf(tokens, source, "rate"));
        assertEquals("variable", colourOf(tokens, source, "sum"));
    }

    /**
     * A name nothing declares keeps the grammar's own answer rather than being guessed at.
     *
     * <p>A free name in JavaScript is a global and the grammar has already coloured it; replacing that
     * with {@code variable} would be losing information in order to look busy.</p>
     */
    @Test
    public void anUnresolvedReferenceIsLeftAlone() {
        String source = "function f() {\n    return Math.max(1, 2);\n}\n";
        List<SyntaxToken> tokens = tokensOf(Grammar.JAVASCRIPT, source);
        // THE GRAMMAR'S OWN ANSWER STANDS -- it already calls this `variable`, and that is not this
        // family's doing. What must not happen is a SCOPE-DERIVED colour on a name no scope declares.
        for (String name : capturesAt(tokens, source, "Math")) {
            assertFalse("an unresolved name was given a scope-derived colour",
                    "variable.parameter".equals(name) || "variable.member".equals(name));
        }
    }

    // ── 24.4 indents ──────────────────────────────────────────────────────────

    /** What Enter would write after {@code row}, as an indent level. */
    private static int levelsAfter(Grammar grammar, String source, int row) {
        TreeSitterTokenizer tokenizer = tokenizerFor(grammar);
        int levels = tokenizer.levelsAfterRow(Rope.of(source), row);
        tokenizer.close();
        return levels;
    }

    /**
     * <b>The case the character-based rule cannot see.</b> A wrapped expression is still inside its
     * statement, and the line after it is not one level deeper for having ended in an operator.
     */
    @Test
    public void aWrappedExpressionDoesNotOpenALevel() {
        String source = "class A {\n"
                + "    void run() {\n"
                + "        int total = one\n"
                + "                + two;\n"
                + "    }\n"
                + "}\n";
        // Inside class + method = the same depth on the wrapped line and on the one before it.
        assertEquals("a wrapped expression was read as opening a block",
                levelsAfter(Grammar.JAVA, source, 2), levelsAfter(Grammar.JAVA, source, 3));
    }

    /** And the case it does see, which must keep working: a line ending in an opener is one deeper. */
    @Test
    public void aBlockOpensALevel() {
        String source = "class A {\n    void run() {\n        x();\n    }\n}\n";
        int atClass = levelsAfter(Grammar.JAVA, source, 0);
        int inMethod = levelsAfter(Grammar.JAVA, source, 1);
        assertTrue("a class body is not deeper than the file: " + atClass, atClass >= 1);
        assertTrue("a method body is not deeper than the class body: " + inMethod + " vs " + atClass,
                inMethod > atClass);
    }

    /** A grammar with no {@code indents.scm} says {@code -1}, which is what keeps the old rule. */
    @Test
    public void aTokenizerWithNoFamilyDirectoryHasNoIndentOpinion() {
        TreeSitterTokenizer bare;
        try {
            bare = new TreeSitterTokenizer(Grammar.JAVA.newParser(), "(program) @none");
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException("tree-sitter native unavailable", nativeUnavailable);
            return;
        }
        assertEquals("a tokenizer with no vendored directory invented an indent level", -1,
                bare.levelsAfterRow(Rope.of("class A {\n    void run() {\n"), 0));
        bare.close();
    }

    /** Every shipped grammar's families compile — the one thing a vendored file can get wrong silently. */
    @Test
    public void everyVendoredFamilyCompilesAgainstItsGrammar() {
        for (Grammar grammar : Grammar.values()) {
            TreeSitterTokenizer tokenizer;
            try {
                tokenizer = grammar.newTokenizer(null);
            } catch (Throwable nativeUnavailable) {
                Assume.assumeNoException("tree-sitter native unavailable", nativeUnavailable);
                return;
            }
            // A family that fails to compile is silently dropped -- which is the right behaviour at run
            // time and the wrong thing for a vendored file to do unnoticed, so it is asserted here.
            for (String family : List.of("folds", "indents", "locals")) {
                String text = Queries.loadFamily(grammar.directory(), family);
                if (text == null) continue;
                assertNotNull(grammar.directory() + "/" + family + ".scm did not compile",
                        tokenizer.compileFamilyForTesting(family));
            }
            tokenizer.close();
        }
    }

    private static String describe(List<int[]> folds) {
        StringBuilder out = new StringBuilder("[");
        for (int[] fold : folds) {
            if (out.length() > 1) out.append(", ");
            out.append(fold[0]).append("..").append(fold[1]);
        }
        return out.append(']').toString();
    }
}
