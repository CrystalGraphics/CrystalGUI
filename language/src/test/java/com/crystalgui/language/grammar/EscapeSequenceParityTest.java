package com.crystalgui.language.grammar;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.Test;
import org.treesitter.TSQuery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>An escape inside a string is banded, in every grammar that has one.</b>
 *
 * <h3>Found by comparing two editors on the same literal</h3>
 *
 * <p>{@code "tab:\t backslash:\\ unicode:\u00e9"} drew as one flat run in a {@code .js} file and
 * banded every escape in the {@code .java} file two tabs away — because the vendored java query carries
 * {@code (escape_sequence) @string.escape} and the javascript one stops at the string. One line, and
 * invisible until the two are side by side.</p>
 *
 * <h3>Which is why this is a PARITY test and not a javascript test</h3>
 *
 * <p>Asking the same question of every shipped grammar found the same gap in <b>css</b> and
 * <b>glsl</b>, which nobody had reported. The rule is stated once, over the whole table, so a seventh
 * grammar arrives held to it — the alternative is six tests that each pass while the set drifts.</p>
 */
public class EscapeSequenceParityTest {

    /**
     * Whether a grammar has the node at all — asked of the grammar, never assumed from the language.
     *
     * <p>{@code html} and {@code xml} have no {@code escape_sequence}: they escape with entities
     * ({@code &amp;amp;}), which is a different node and correctly captured elsewhere. So "has no
     * escape capture" is only a defect where the node exists to be captured, and this is what tells
     * the two apart rather than a hand-kept list of exemptions.</p>
     */
    private static boolean hasEscapeNode(Grammar grammar) {
        try {
            new TSQuery(grammar.newParser(), "(escape_sequence) @string.escape");
            return true;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    @Test
    public void everyGrammarWithEscapesCapturesThem() {
        List<String> missing = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        for (Grammar grammar : Grammar.values()) {
            String query;
            try {
                if (!hasEscapeNode(grammar)) continue;
                query = shippedHighlightsOf(grammar);
            } catch (Throwable unavailable) {
                Assume.assumeNoException(unavailable);
                return;
            }
            if (query.contains("@string.escape")) covered.add(grammar.toString());
            else missing.add(grammar.toString());
        }
        Assume.assumeFalse("no grammar loaded, so this proves nothing", covered.isEmpty() && missing.isEmpty());
        assertTrue("these grammars have an escape_sequence node and no rule capturing it, so an escape "
                + "draws as ordinary string text: " + missing, missing.isEmpty());
    }

    /**
     * And it reaches the tokens, which is the half a query text cannot show.
     *
     * <p>A rule can name a node the grammar has and still never fire — the string rule matching first
     * and swallowing the range would look identical from the query file.</p>
     */
    @Test
    public void anEscapeIsBandedSeparatelyFromTheStringAroundIt() {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = Grammar.JAVASCRIPT.newTokenizer(null);
        } catch (Throwable unavailable) {
            Assume.assumeNoException(unavailable);
            return;
        }
        try {
            String source = "const E = \"a\\tb\";\nconst T = `c\\nd`;\n";
            List<String> escapes = new ArrayList<>();
            for (SyntaxToken token : tokenizer.tokenize(Rope.of(source), 0, source.length())) {
                if ("string.escape".equals(token.name())) {
                    escapes.add(source.substring(token.start(), token.end()));
                }
            }
            assertTrue("no escape was banded at all: " + escapes, escapes.size() >= 2);
            assertFalse("the band covers the whole literal rather than the escape",
                    escapes.stream().anyMatch(each -> each.length() > 6));
        } finally {
            tokenizer.close();
        }
    }

    /** The shipped query text for a grammar, read the way the tokenizer reads it. */
    private static String shippedHighlightsOf(Grammar grammar) throws IOException {
        String path = "/assets/crystalgui/syntax/" + grammar.directory() + "/highlights.scm";
        try (InputStream in = EscapeSequenceParityTest.class.getResourceAsStream(path)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
