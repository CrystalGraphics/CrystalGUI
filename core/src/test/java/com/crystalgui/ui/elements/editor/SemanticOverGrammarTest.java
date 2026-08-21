package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>A semantic token replaces a competing answer, and leaves a CONTAINER alone.</b>
 *
 * <p>The merge rule is "the engine's answer beats the grammar's", and that is a statement about two
 * sources describing <em>the same thing</em>: the grammar called {@code count} a {@code variable} from
 * its shape, the engine knows it is a {@code variable.parameter}. The better answer replaces the
 * worse.</p>
 *
 * <p>A token that CONTAINS the semantic one answers a different question — "what is this inside" — and
 * both are true at once. {@code DocComments} emits a coarse {@code comment.doc} over the whole comment
 * before the pieces within it, so once doc-tag references began resolving, one {@code {@link List}}
 * cleared that container for its entire row: the prose either side lost the comment's colour and its
 * italic, while a line whose reference happened not to resolve kept both. It was reported as the
 * highlighting being inconsistent from one line to the next, which is exactly how it looked.</p>
 *
 * <p>This calls {@link TextEditor}'s own predicates rather than restating them. A test that
 * re-implemented the rule would agree with itself forever and say nothing about the editor — which is
 * the shape of the "two copies drift" trap this codebase keeps paying for.</p>
 */
public class SemanticOverGrammarTest {

    private static SyntaxToken at(int start, int end, String name) {
        return new SyntaxToken(start, end, name);
    }

    /** The merge's own rule, asked exactly as {@code clearGrammarUnder} asks it. */
    private static boolean cleared(SyntaxToken existing, int from, int to) {
        return TextEditor.replacedBySemantic(existing, from, to);
    }

    /** <b>The defect.</b> A whole-comment token must survive a semantic token landing inside it. */
    @Test
    public void aContainingTokenSurvivesASemanticTokenInsideIt() {
        // `* Uses {@link List} here.` — the comment token covers the row, `List` is a piece of it.
        SyntaxToken comment = at(0, 40, "comment.doc");

        assertFalse("the comment's own token was cleared, so the prose either side of the link loses"
                        + " its colour and its italic", cleared(comment, 14, 18));
    }

    /** The rule this must not weaken: a same-span guess is still replaced. */
    @Test
    public void aCompetingAnswerOverTheSameSpanIsStillReplaced() {
        assertTrue("the grammar's guess must not survive the engine's answer about the same characters",
                cleared(at(4, 9, "variable"), 4, 9));
        assertTrue("the lexer's flat doc-value colour describes the same characters, so it goes too",
                cleared(at(14, 18, "comment.doc.value"), 14, 18));
    }

    /** A partial overlap is not a container either — neither one is inside the other. */
    @Test
    public void aPartialOverlapIsStillReplaced() {
        assertTrue("a token that merely straddles the semantic one is a competing answer, not a context",
                cleared(at(0, 10, "string"), 5, 15));
        assertTrue(cleared(at(10, 20, "string"), 5, 15));
    }

    /** And a token that shares no character with it is untouched, wherever it sits. */
    @Test
    public void aTokenElsewhereOnTheRowIsUntouched() {
        assertFalse(cleared(at(0, 5, "keyword"), 14, 18));
        assertFalse(cleared(at(30, 40, "keyword"), 14, 18));
    }
}
