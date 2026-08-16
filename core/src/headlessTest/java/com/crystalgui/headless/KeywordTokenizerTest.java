package com.crystalgui.headless;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.KeywordTokenizer;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * P6.1.7 step 4 — the built-in lexer.
 *
 * <p>Headless because a dedicated server has no natives and must still be able to tokenize, and because
 * this is the fallback for any platform where tree-sitter's native library will not load.</p>
 */
public class KeywordTokenizerTest {

    private List<SyntaxToken> tokens(String text) {
        return new KeywordTokenizer(java.util.Set.of("if", "else", "return", "class"),
                java.util.Set.of("int", "void", "float"))
                .tokenize(Rope.of(text), 0, text.length());
    }

    private List<String> namesOf(String text) {
        List<String> names = new ArrayList<>();
        for (SyntaxToken token : tokens(text)) names.add(token.name());
        return names;
    }

    private String textOf(String source, SyntaxToken token) {
        return source.substring(token.start(), token.end());
    }

    // ── What it can recognise ───────────────────────────────────────────────────────────────────

    @Test
    public void findsKeywordsAndTypes() {
        String source = "int x; if (x) return;";
        List<SyntaxToken> found = tokens(source);

        assertEquals("int", textOf(source, found.get(0)));
        assertEquals("type", found.get(0).name());
        assertTrue(namesOf(source).contains("keyword"));
    }

    @Test
    public void findsLineComments() {
        String source = "int x; // trailing\nint y;";
        SyntaxToken comment = firstOfName(source, "comment");
        assertEquals("// trailing", textOf(source, comment));
    }

    @Test
    public void findsBlockCommentsAcrossLines() {
        String source = "a\n/* two\nlines */\nb";
        SyntaxToken comment = firstOfName(source, "comment");
        assertEquals("/* two\nlines */", textOf(source, comment));
    }

    @Test
    public void findsStringsAndNumbers() {
        String source = "x = \"hi\" + 42;";
        assertEquals("\"hi\"", textOf(source, firstOfName(source, "string")));
        assertEquals("42", textOf(source, firstOfName(source, "number")));
    }

    /** A backslash escapes the quote, or {@code "a\"b"} would end at the middle quote. */
    @Test
    public void anEscapedQuoteDoesNotEndTheString() {
        String source = "s = \"a\\\"b\";";
        assertEquals("\"a\\\"b\"", textOf(source, firstOfName(source, "string")));
    }

    /**
     * <b>An unterminated string ends at the line, not at the file.</b> A stray quote is a normal state
     * while typing, and painting the rest of the document as a string is a far worse answer than painting
     * the rest of one line.
     */
    @Test
    public void anUnterminatedStringStopsAtTheLineEnd() {
        String source = "s = \"oops\nint y;";
        SyntaxToken string = firstOfName(source, "string");
        assertTrue("the string must not swallow the next line", string.end() <= source.indexOf('\n') + 1);
        assertTrue("so the next line still lexes", namesOf(source).contains("type"));
    }

    @Test
    public void aNameFollowedByAParenthesisIsACall() {
        String source = "doThing(1);";
        assertEquals("doThing", textOf(source, firstOfName(source, "function")));
    }

    // ── The range argument ──────────────────────────────────────────────────────────────────────

    /**
     * <b>Scanning starts at a line boundary, never mid-line.</b> A lexer's state depends on what came
     * before it — starting inside a string literal would read its contents as code, which is precisely
     * what a viewport-bounded query would do if it began wherever the first visible row happened to start.
     */
    @Test
    public void aQueryStartingMidLineStillLexesThatLineCorrectly() {
        String source = "x = \"a keyword: return\";\nint y;";
        KeywordTokenizer tokenizer = new KeywordTokenizer(
                java.util.Set.of("return"), java.util.Set.of("int"));

        // Ask from the middle of the string literal.
        List<SyntaxToken> found = tokenizer.tokenize(Rope.of(source), 12, source.length());

        for (SyntaxToken token : found) {
            assertNotEquals("the word inside the string must not be lexed as a keyword",
                    "keyword", token.name());
        }
    }

    /**
     * <b>A block comment opened on an earlier line still colours the visible part.</b> Scrolling into the
     * middle of a long comment must not show it as code — the query has to look back past its own start.
     */
    @Test
    public void aBlockCommentOpenedAboveTheQueryStillApplies() {
        StringBuilder source = new StringBuilder("/* opened here\n");
        for (int i = 0; i < 50; i++) source.append("still inside the comment ").append(i).append('\n');
        source.append("*/\nint after;");
        String text = source.toString();

        KeywordTokenizer tokenizer = new KeywordTokenizer(java.util.Set.of(), java.util.Set.of("int"));
        int midway = text.indexOf("still inside the comment 30");
        List<SyntaxToken> found = tokenizer.tokenize(Rope.of(text), midway, midway + 40);

        assertFalse("the visible rows are inside a comment and must be reported as one", found.isEmpty());
        assertEquals("comment", found.get(0).name());
        assertTrue("and the token starts above the query", found.get(0).start() < midway);
    }

    @Test
    public void tokensEndingBeforeTheRangeAreNotReturned() {
        String source = "int a;\n\n\n\n\n\n\n\n\n\nint b;";
        KeywordTokenizer tokenizer = new KeywordTokenizer(java.util.Set.of(), java.util.Set.of("int"));
        int lastLine = source.lastIndexOf("int b;");

        List<SyntaxToken> found = tokenizer.tokenize(Rope.of(source), lastLine, source.length());

        for (SyntaxToken token : found) {
            assertTrue("a token entirely above the viewport is wasted work", token.end() > lastLine);
        }
    }

    // ── The shipped languages ───────────────────────────────────────────────────────────────────

    @Test
    public void theGlslTokenizerKnowsShaderTypes() {
        KeywordTokenizer glsl = KeywordTokenizer.glsl();
        String source = "uniform sampler2D tex; vec4 c = texture(tex, uv);";
        List<SyntaxToken> found = glsl.tokenize(Rope.of(source), 0, source.length());

        List<String> names = new ArrayList<>();
        for (SyntaxToken token : found) names.add(source.substring(token.start(), token.end()));
        assertTrue(names.contains("uniform"));
        assertTrue(names.contains("sampler2D"));
        assertTrue(names.contains("vec4"));
    }

    @Test
    public void theJavaTokenizerKnowsJavaKeywords() {
        KeywordTokenizer java = KeywordTokenizer.java();
        String source = "public final class Thing { }";
        List<SyntaxToken> found = java.tokenize(Rope.of(source), 0, source.length());
        assertFalse(found.isEmpty());
        assertEquals("keyword", found.get(0).name());
    }

    /**
     * The JavaScript tier, and the one thing it has to know that the C-family one does not.
     *
     * <h4>An unhandled quote character is not a missing colour</h4>
     *
     * <p>It is a lexer that walks <em>into</em> the literal and reads its contents as code. A template
     * literal containing a {@code "} — {@code `he said "hi"`} — would open a string at that quote and run
     * it to the end of the line, painting whatever followed. So the backtick is handled, and this asserts
     * the containment rather than the colour: whatever else happens, the tokens after the literal must be
     * the ones a reader expects.</p>
     */
    @Test
    public void theJavaScriptTokenizerTreatsATemplateLiteralAsAString() {
        KeywordTokenizer js = KeywordTokenizer.javascript();
        String source = "var s = `he said \"hi\"`; return 1;";
        List<SyntaxToken> found = js.tokenize(Rope.of(source), 0, source.length());

        SyntaxToken template = null;
        for (SyntaxToken token : found) {
            if ("string".equals(token.name()) && source.charAt(token.start()) == '`') template = token;
        }
        assertNotNull("the template literal was not lexed as a string: " + found, template);
        assertEquals("`he said \"hi\"`", source.substring(template.start(), template.end()));

        // AND THE CODE AFTER IT IS STILL CODE -- the containment half. With the backtick unhandled, the
        // inner quote opened a string that swallowed everything up to the newline, `return` included.
        boolean returnIsAKeyword = false;
        for (SyntaxToken token : found) {
            if ("keyword".equals(token.name())
                    && "return".equals(source.substring(token.start(), token.end()))) {
                returnIsAKeyword = true;
            }
        }
        assertTrue("the text after the template was swallowed: " + found, returnIsAKeyword);
    }

    /** {@code class} is coloured even on a band that refuses to run it. @see KeywordTokenizer#javascript */
    @Test
    public void theJavaScriptTokenizerColoursKeywordsTheEngineMayStillRefuse() {
        KeywordTokenizer js = KeywordTokenizer.javascript();
        String source = "class A {}";
        List<SyntaxToken> found = js.tokenize(Rope.of(source), 0, source.length());
        assertFalse(found.isEmpty());
        assertEquals("keyword", found.get(0).name());
    }

    // ── Names ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aDottedCaptureFallsBackToItsGeneralForm() {
        assertEquals("function", new SyntaxToken(0, 1, "function.builtin").generalName());
        assertNull("a plain name has no more general form", new SyntaxToken(0, 1, "keyword").generalName());
    }

    private SyntaxToken firstOfName(String source, String name) {
        for (SyntaxToken token : tokens(source)) {
            if (token.name().equals(name)) return token;
        }
        throw new AssertionError("no " + name + " token in: " + source);
    }
}
