package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.DocComments;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DocComments} — reading the inside of a documentation comment.
 *
 * <h3>Headless on purpose</h3>
 *
 * <p>It is a lexing pass over text: no grammar, no native, no font, no GL. That it can be tested here at
 * all is the argument for it living in {@code core} rather than beside a grammar — a dedicated server
 * holds documents and a grammar module is the thing it must not load.</p>
 *
 * <h3>What is worth asserting</h3>
 *
 * <p>The <b>refusals</b>, mostly. Finding {@code <p>} is easy and would work by accident; not finding one
 * in {@code a < b} or {@code List<String>} is the part that decides whether a doc comment full of prose
 * about generics lights up like markup. Same for the {@code @} in an email address.</p>
 */
public class DocCommentsTest {

    private static List<SyntaxToken> refine(String source) {
        Rope document = Rope.of(source);
        List<SyntaxToken> comment = List.of(new SyntaxToken(0, source.length(), "comment"));
        return DocComments.refine(document, comment);
    }

    private static List<String> textsOf(String source, String name) {
        List<String> out = new ArrayList<>();
        for (SyntaxToken token : refine(source)) {
            if (token.name().equals(name)) out.add(source.substring(token.start(), token.end()));
        }
        return out;
    }

    /** The comment as a whole is re-named, so a scheme can colour it apart from an ordinary one. */
    @Test
    public void aDocCommentIsRenamedAndAnOrdinaryOneIsNot() {
        assertEquals(List.of("/** Hi. */"), textsOf("/** Hi. */", DocComments.DOC));

        String plain = "/* Hi. */";
        List<SyntaxToken> tokens = refine(plain);
        assertEquals("an ordinary block comment was refined", 1, tokens.size());
        assertEquals("comment", tokens.get(0).name());
    }

    /**
     * <b>The whole comment is emitted BEFORE the pieces inside it.</b>
     *
     * <p>A character belongs to whichever name was written last, so the reverse order would let the
     * comment's own colour overwrite every tag and every element in it — and the result looks exactly
     * like the feature not being wired up, rather than like an ordering mistake.</p>
     */
    @Test
    public void theWholeCommentIsEmittedBeforeItsContents() {
        List<SyntaxToken> tokens = refine("/** <p>text</p> */");
        assertEquals(DocComments.DOC, tokens.get(0).name());
        assertTrue("nothing was found inside the comment", tokens.size() > 1);
    }

    @Test
    public void htmlElementsAreMarkup() {
        assertEquals(List.of("<p>", "</p>"), textsOf("/** <p>hi</p> */", DocComments.MARKUP));
        assertEquals(List.of("<ol>", "<li>", "</li>", "</ol>"),
                textsOf("/** <ol><li>one</li></ol> */", DocComments.MARKUP));
        assertEquals("an element with attributes was not recognised",
                List.of("<a href=\"x\">"), textsOf("/** <a href=\"x\">link</a> */", DocComments.MARKUP)
                        .subList(0, 1));
    }

    /**
     * <b>Prose about generics and comparisons is not markup.</b>
     *
     * <p>A doc comment is exactly where {@code List<String>} and {@code a < b} get written, and a naive
     * scan to the next {@code >} paints the first as an element and swallows half a sentence for the
     * second. Both are the reason {@code isMarkupName} exists.</p>
     */
    @Test
    public void genericsAndComparisonsAreNotMarkup() {
        assertEquals(List.of(), textsOf("/** a < b and b > a */", DocComments.MARKUP));
        assertEquals(List.of(), textsOf("/** Returns a List<String> of rows. */", DocComments.MARKUP));
    }

    /** A `<` with no `>` before the line ends is prose, not an element with a very long name. */
    @Test
    public void anUnclosedAngleDoesNotRunToTheNextLine() {
        assertEquals(List.of(), textsOf("/**\n * a < b\n * <p>real</p>\n */", DocComments.MARKUP)
                .subList(0, 0));
        assertEquals(List.of("<p>", "</p>"),
                textsOf("/**\n * a < b\n * <p>real</p>\n */", DocComments.MARKUP));
    }

    @Test
    public void blockAndInlineTagsAreTags() {
        assertEquals(List.of("@param"), textsOf("/**\n * @param x the row\n */", DocComments.TAG));
        assertEquals(List.of("@code"), textsOf("/** Use {@code null} here. */", DocComments.TAG));
        assertEquals(List.of("@author", "@see"),
                textsOf("/**\n * @author nobody\n * @see Stream\n */", DocComments.TAG));
    }

    /**
     * <b>An {@code @} inside a word is not a tag.</b>
     *
     * <p>An email address in an {@code @author} line is the case that matters, and it is common enough
     * that getting it wrong would be visible in most real files.</p>
     */
    @Test
    public void anAtInsideAWordIsNotATag() {
        assertEquals(List.of("@author"),
                textsOf("/**\n * @author nobody@example.com\n */", DocComments.TAG));
    }

    /**
     * <b>Only the tags whose next word is a NAME get a value.</b>
     *
     * <p>{@code @param count} names a parameter; {@code @since 1.2} is prose about a version. Colouring
     * the second as an identifier is a claim rather than a decoration, and IntelliJ's own
     * {@code DOC_COMMENT_TAG_VALUE} is used for the first kind only.</p>
     */
    @Test
    public void onlyNamingTagsCarryAValue() {
        assertEquals(List.of("x"), textsOf("/**\n * @param x the row\n */", DocComments.VALUE));
        assertEquals(List.of("IOException"),
                textsOf("/**\n * @throws IOException when closed\n */", DocComments.VALUE));
        assertEquals(List.of(), textsOf("/**\n * @since 1.2\n */", DocComments.VALUE));
        assertEquals(List.of(), textsOf("/**\n * @author nobody\n */", DocComments.VALUE));
    }

    /** An inline tag's value stops at the closing brace rather than taking it. */
    @Test
    public void anInlineTagsValueStopsAtItsBrace() {
        assertEquals(List.of("List#add"), textsOf("/** See {@link List#add} for more. */",
                DocComments.VALUE));
    }
}
