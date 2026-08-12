package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.junit.Assume;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every grammar this module ships parses its language and produces the captures a scheme styles.
 *
 * <p>Deliberately shallow per language and broad across them: the deep assertions live beside the
 * feature they pin (predicates, precedence, numeric forms), while this is the one that fails when a jar
 * is vendored without its query, a query without its jar, or a registration without either.</p>
 */
public class EveryShippedGrammarTest {

    private void assumeNativeAvailable() {
        try {
            TreeSitterTokenizer.java().close();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
        }
    }

    private Set<String> captures(SyntaxTokenizer tokenizer, String source) {
        Set<String> names = new HashSet<>();
        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        for (SyntaxToken token : tokens) names.add(token.name());
        return names;
    }

    @Test
    public void cssParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.css(null);
        Set<String> names = captures(tokenizer, ".panel > a:hover { color: #FF0000; margin: 4px; }\n");
        assertFalse("the CSS grammar produced nothing", names.isEmpty());
        assertTrue("expected a property capture, got " + names,
                names.contains("property") || names.contains("attribute"));
        tokenizer.close();
    }

    @Test
    public void javascriptParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.javascript(null);
        Set<String> names = captures(tokenizer,
                "const x = 1;\nfunction go(a) { return `hi ${a}`; }\nclass K {}\n");
        assertFalse("the JavaScript grammar produced nothing", names.isEmpty());
        assertTrue("expected a keyword capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("keyword")));
        assertTrue("expected a string capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("string")));
        tokenizer.close();
    }

    @Test
    public void htmlParsesAndCaptures() {
        assumeNativeAvailable();
        TreeSitterTokenizer tokenizer = TreeSitterTokenizer.html(null);
        Set<String> names = captures(tokenizer,
                "<div class=\"a\"><p>text</p><!-- note --></div>\n");
        assertFalse("the HTML grammar produced nothing", names.isEmpty());
        assertTrue("expected a tag capture, got " + names,
                names.stream().anyMatch(n -> n.startsWith("tag")));
        tokenizer.close();
    }

    /**
     * <b>Registration is the half that fails silently.</b> A grammar can load perfectly and still never
     * reach an editor, which is exactly how the harness spent a whole session on the word-list lexer while
     * the real parser sat unused on the classpath.
     */
    @Test
    public void registrationPutsEachGrammarInFrontOfTheLexer() {
        assumeNativeAvailable();
        TreeSitterLanguages.register(null);
        for (String fileName : List.of("A.java", "a.css", "a.js", "a.html")) {
            SyntaxTokenizer tokenizer = LanguageRegistry.forFileName(fileName).newTokenizer();
            assertTrue(fileName + " still resolves to " + tokenizer.getClass().getSimpleName(),
                    tokenizer instanceof TreeSitterTokenizer);
            tokenizer.close();
        }
    }
}
