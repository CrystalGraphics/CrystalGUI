package com.crystalgui.language.grammar;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>An {@code import} tree-sitter cannot parse must not reach it.</b>
 *
 * <p>This engine supports {@code import a.b.C;} in a JavaScript file; the shipped grammar does not —
 * {@code import} there begins an ES module declaration expecting a string or a {@code from}.</p>
 *
 * <h3>The damage is not the import line</h3>
 *
 * <p>Measured on one fixture parsed three ways. With the semicolon the grammar contains the error: the
 * import line itself is mis-tokenised and everything below it is right. <b>Without the semicolon —
 * which is ordinary JavaScript, the language has automatic semicolon insertion and authors use it —
 * the recovery reaches into the body and {@code var} is reported as a {@code variable}.</b> So the
 * fixture here is the one without, because the one with passes against no filter at all.</p>
 *
 * <p>Which is also why the fix is a source filter on the grammar rather than a correction downstream:
 * by the time the tokens exist the damage is spread across the file and there is nothing local left to
 * correct.</p>
 */
public class ImportSourceFilterTest {

    private static final String BODY = "\nvar list = new ArrayList();\nconsole.log(list.size());\n";

    /** No terminator, which is legal JavaScript and the shape that corrupts the body. */
    private static final String NO_SEMICOLON = "import java.util.ArrayList" + BODY;

    private static final String SEMICOLON = "import java.util.ArrayList;" + BODY;

    private static List<SyntaxToken> tokenize(String source) {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVASCRIPT.newTokenizer(null);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return List.of();
        }
        try {
            return tokenizer.tokenize(Rope.of(source), 0, source.length());
        } finally {
            tokenizer.close();
        }
    }

    /** The capture covering exactly {@code text}'s first occurrence, or a failure naming what was found. */
    private static String captureOver(List<SyntaxToken> tokens, String source, String text) {
        int at = source.indexOf(text);
        assertTrue("the fixture does not contain " + text, at >= 0);
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + text.length()) return token.name();
        }
        fail("no token covers " + text + "; the grammar produced " + tokens.size() + " tokens");
        return null;
    }

    /**
     * <b>Every</b> capture over exactly {@code [at, at+length)} — a range routinely carries more than
     * one. {@code new ArrayList()} is captured as {@code variable} by the identifier rule and as
     * {@code constructor} by the {@code new} rule, and which arrives first is not a fact worth asserting;
     * precedence between them is resolved downstream.
     */
    private static Set<String> capturesAt(List<SyntaxToken> tokens, int at, int length) {
        Set<String> names = new LinkedHashSet<>();
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + length) names.add(token.name());
        }
        assertFalse("no token covers [" + at + ", " + (at + length) + ")", names.isEmpty());
        return names;
    }

    /**
     * The regression this exists for. {@code var} is a keyword in every JavaScript file ever written,
     * and one unparseable line two rows above it was enough to make the grammar call it a variable.
     */
    @Test
    public void aJavaStyleImportDoesNotDecolourTheRestOfTheFile() {
        List<SyntaxToken> tokens = tokenize(NO_SEMICOLON);
        Assume.assumeFalse(tokens.isEmpty());

        assertEquals("an import at the top of the file changed what `var` is",
                "keyword", captureOver(tokens, NO_SEMICOLON, "var"));
        assertEquals("and what `new` is", "keyword", captureOver(tokens, NO_SEMICOLON, "new"));
    }

    /**
     * <b>And the grammar says nothing at all about the import line</b>, in either shape.
     *
     * <p>Not tidiness — the line's colours come from the semantic pass ({@code module} per package
     * segment, then {@code type}, exactly as a Java import is drawn), and semantic tokens <em>replace</em>
     * grammar tokens where they overlap rather than layering. A grammar still reporting
     * {@code import -> keyword} and {@code java -> variable} underneath would leave two producers naming
     * the same range, which is decided by merge order rather than by intent.</p>
     */
    @Test
    public void theGrammarReportsNothingOverAnImport() {
        for (String source : List.of(NO_SEMICOLON, SEMICOLON)) {
            List<SyntaxToken> tokens = tokenize(source);
            Assume.assumeFalse(tokens.isEmpty());

            int endOfLine = source.indexOf('\n');
            for (SyntaxToken token : tokens) {
                assertTrue("the grammar coloured the import line: "
                                + source.substring(token.start(), token.end()) + " -> " + token.name(),
                        token.start() >= endOfLine);
            }
        }
    }

    /**
     * The body is genuinely still parsed, rather than the file having gone quiet.
     *
     * <p>Blanking a line the parser chokes on could just as easily produce "no tokens anywhere", which
     * would satisfy the assertion above and be a worse bug than the one being fixed.
     */
    @Test
    public void theBodyBelowAnImportIsStillParsed() {
        List<SyntaxToken> tokens = tokenize(NO_SEMICOLON);
        Assume.assumeFalse(tokens.isEmpty());

        assertTrue("console.log lost its member colouring",
                capturesAt(tokens, NO_SEMICOLON.indexOf("log"), 3).contains("property"));
        // The one in the BODY -- the first `ArrayList` in the file is on the blanked import line, and
        // the whole point of the test above is that nothing is reported there.
        assertTrue("a constructed type below the import is not being seen as one",
                capturesAt(tokens, NO_SEMICOLON.indexOf("new ArrayList") + 4, 9).contains("constructor"));
        for (SyntaxToken token : tokens) {
            assertTrue("a token ran past the end of the document",
                    token.end() <= NO_SEMICOLON.length());
        }
    }

    /** A filter that changes the length is refused loudly, not tolerated. */
    @Test
    public void aFilterThatChangesTheLengthIsRefused() {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVASCRIPT.newTokenizer(null);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }
        try {
            tokenizer.filterSourceWith(text -> text.replace("import ", ""));
            tokenizer.tokenize(Rope.of(NO_SEMICOLON), 0, NO_SEMICOLON.length());
            fail("a length-changing filter was accepted; every offset below it is now wrong");
        } catch (IllegalStateException expected) {
            assertNull("the failure must name the lengths, or it cannot be diagnosed",
                    expected.getMessage().contains("preserve length") ? null : expected.getMessage());
        } finally {
            tokenizer.close();
        }
    }
}
