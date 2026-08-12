package com.crystalgui.syntax.treesitter;

import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Every numeric literal form Java has must be a number — including the one the vendored query forgets.
 *
 * <p>The grammar's literal list names hex, decimal, octal and both floating-point forms, and omits
 * {@code binary_integer_literal}, which Java has had since 7. The effect is narrow and odd-looking:
 * {@code 0b1010_1010} renders as plain text on a line where {@code 0755} two rows above is coloured, so
 * it reads as that one constant being broken rather than as a missing node type.</p>
 */
public class NumericLiteralsTest {

    private List<String> named(String source, List<SyntaxToken> tokens, String name) {
        List<String> out = new ArrayList<>();
        for (SyntaxToken token : tokens) {
            if (token.name().equals(name)) out.add(source.substring(token.start(), token.end()));
        }
        return out;
    }

    @Test
    public void everyNumericLiteralFormIsANumber() {
        TreeSitterTokenizer tokenizer;
        try {
            tokenizer = TreeSitterTokenizer.java();
        } catch (Throwable nativeUnavailable) {
            Assume.assumeNoException(nativeUnavailable);
            return;
        }
        String source = "class A {\n"
                + "    static final int OCTAL = 0755;\n"
                + "    static final int BINARY = 0b1010_1010;\n"
                + "    static final int HEX = 0xDEAD_BEEF;\n"
                + "    static final double SCI = 6.022e23;\n"
                + "}\n";

        List<SyntaxToken> tokens = tokenizer.tokenize(Rope.of(source), 0, source.length());
        List<String> numbers = named(source, tokens, "number");

        assertTrue("octal, got " + numbers, numbers.contains("0755"));
        assertTrue("binary — the one the vendored query omits, got " + numbers,
                numbers.contains("0b1010_1010"));
        assertTrue("hex, got " + numbers, numbers.contains("0xDEAD_BEEF"));
        assertTrue("scientific, got " + numbers, numbers.contains("6.022e23"));

        // The identifier beside it was never the problem, but it shares the line and so shares the blame.
        assertTrue("BINARY is still a constant, got " + named(source, tokens, "constant"),
                named(source, tokens, "constant").contains("BINARY"));
        tokenizer.close();
    }
}
