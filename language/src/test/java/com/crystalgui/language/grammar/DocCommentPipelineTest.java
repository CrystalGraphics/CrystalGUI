package com.crystalgui.language.grammar;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * <b>The seam between a grammar and {@link DocComments}</b>.
 *
 * <p>{@code DocCommentsTest} covers the refinement itself, headlessly, against a hand-made
 * {@code comment} token — which is the right shape for a pass that needs no grammar. It cannot
 * cover the <em>join</em>: that the Java grammar really does report a doc comment under the name the
 * refinement looks for, and that nothing between the two renames it.</p>
 *
 * <p>Both halves passing while the seam is wrong is exactly the shape that ships. One capture renamed in
 * a vendored query, or a backend that decided to call a doc comment {@code comment.documentation} on its
 * own, and every test here still passes while the editor colours nothing.</p>
 */
public class DocCommentPipelineTest {

    private TreeSitterTokenizer javaTokenizer() {
        try {
            return TreeSitterTokenizer.java();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return null;
        }
    }

    /**
     * <b>A doc comment's tags and markup survive the real pipeline.</b>
     *
     * <p>{@code DocComments} is unit-tested against a hand-made {@code comment} token; this asserts
     * the join — that the grammar really does report a doc comment under the name the refinement
     * looks for, and that nothing between them renames it. The two halves each passing while the seam
     * is wrong is exactly the shape that ships.</p>
     */
    @Test
    public void aDocCommentIsRefinedThroughTheRealTokenizer() {
        TreeSitterTokenizer grammar = javaTokenizer();
        SyntaxTokenizer tokenizer = DocComments.refining(grammar);
        String source = "/**\n"
                + " * <p>Text.</p>\n"
                + " * @param x the row\n"
                + " * @author nobody\n"
                + " */\n"
                + "class A { void m(int x) { } }\n";
        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());

        assertTrue("the comment was not renamed: " + names(tokens),
                textsCaptured(source, tokens, DocComments.DOC).size() == 1);
        assertTrue("block tags were not captured: " + names(tokens),
                textsCaptured(source, tokens, DocComments.TAG).contains("@author"));
        assertTrue("markup was not captured: " + names(tokens),
                textsCaptured(source, tokens, DocComments.MARKUP).contains("<p>"));
        assertTrue("the param name was not captured: " + names(tokens),
                textsCaptured(source, tokens, DocComments.VALUE).contains("x"));
    }

    private static java.util.Set<String> names(List<SyntaxToken> tokens) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (SyntaxToken token : tokens) out.add(token.name());
        return out;
    }

    private static List<String> textsCaptured(String source, List<SyntaxToken> tokens, String name) {
        List<String> out = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.name().equals(name)) out.add(source.substring(token.start(), token.end()));
        }
        return out;
    }
}
