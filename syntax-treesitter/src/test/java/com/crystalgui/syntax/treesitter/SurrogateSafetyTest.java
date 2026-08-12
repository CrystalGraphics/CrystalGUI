package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * No token may begin or end between the halves of a surrogate pair.
 *
 * <h3>What broke, and why the stack trace pointed somewhere else entirely</h3>
 * <p>A surrogate pair is four UTF-8 bytes and two UTF-16 units, and the byte-to-char walk counts the
 * pair's whole width against its high surrogate — so a byte offset can convert to an index sitting on the
 * low one. The substring that results starts or ends with half a character, which is legal Java and
 * survives every assertion about ranges.</p>
 *
 * <p>It fails four layers away. The shaper's cluster mapper asks {@code codePointAt} for each code point
 * and computes its UTF-8 width; for a lone surrogate that answers three, while
 * {@code String.getBytes(UTF_8)} writes a single {@code '?'} for malformed input. The running total then
 * walks past the end of the map, and the report is an {@code ArrayIndexOutOfBoundsException} inside text
 * layout on opening an HTML file — a stack trace with no offsets in it and no mention of emoji.</p>
 *
 * <p>Both ends are fixed: this asserts the tokenizer never emits such a boundary, and
 * {@code Utf8ClusterMapper} is bounds-guarded so a caller's off-by-one degrades instead of crashing.</p>
 */
public class SurrogateSafetyTest {

    private void assertNoSplitPairs(TreeSitterTokenizer tokenizer, String source) {
        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        assertFalse("no tokens at all — the fixture is not exercising anything", tokens.isEmpty());
        for (SyntaxToken token : tokens) {
            assertTrue(token + " starts on a low surrogate",
                    token.start() == 0 || !isSplitAt(source, token.start()));
            assertTrue(token + " ends on a low surrogate",
                    token.end() == source.length() || !isSplitAt(source, token.end()));
            // The real proof: the slice a text layer would take must round-trip through UTF-8. An
            // unpaired surrogate does not — the encoder writes '?' — so the lengths diverge, which is
            // exactly the disagreement that walked the cluster map off its end.
            String slice = source.substring(token.start(), token.end());
            String roundTripped = new String(
                    slice.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(token + " does not survive a UTF-8 round trip: " + slice,
                    roundTripped.equals(slice));
        }
    }

    private static boolean isSplitAt(String text, int index) {
        return index > 0 && index < text.length()
                && Character.isLowSurrogate(text.charAt(index))
                && Character.isHighSurrogate(text.charAt(index - 1));
    }

    @Test
    public void javaTokensNeverSplitAnEmoji() {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = TreeSitterTokenizer.java();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }
        assertNoSplitPairs(tokenizer,
                "class A {\n"
                        + "    // café 日本語 🎉 — a comment\n"
                        + "    static final String S = \"🎉 emoji 🚀 pair ✨\";\n"
                        + "    static final int AFTER = 1;\n"
                        + "}\n");
        tokenizer.close();
    }

    /**
     * HTML is the one that actually crashed, because injection adds a second conversion: the injected
     * range is converted from bytes, sliced, tokenized standalone, and its offsets shifted back.
     */
    @Test
    public void htmlWithInjectionsNeverSplitsAnEmoji() {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = TreeSitterTokenizer.html(null);
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }
        assertNoSplitPairs(tokenizer,
                "<html>\n"
                        + "<title>café 日本語 🎉</title>\n"
                        + "<style>\n"
                        + "  .a::after { content: \"🎉 ✨\"; }\n"
                        + "</style>\n"
                        + "<script>\n"
                        + "  const emoji = '🚀 café 日本語';\n"
                        + "</script>\n"
                        + "<p>trailing 🎯 text</p>\n"
                        + "</html>\n");
        tokenizer.close();
    }
}
