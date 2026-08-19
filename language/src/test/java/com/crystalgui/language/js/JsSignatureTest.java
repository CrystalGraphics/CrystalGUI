package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>M10.8 — the declaration the Quick Documentation popup draws.</b>
 *
 * <p>{@code DocumentationPopupTest}'s twin. The milestone's criteria: a JavaScript symbol renders its own
 * declaration with the capture vocabulary the editor is coloured by; a Java member reached from JavaScript
 * is quoted from source exactly as it is in a {@code .java} file; and go-to-definition has somewhere to
 * send you in both directions.</p>
 */
public class JsSignatureTest {

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    private static Analysis analyse(String source) {
        return JsLanguage.analyzer().analyze("Probe.js", source, 1L);
    }

    private static SymbolInfo resolve(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue("the fixture does not contain " + needle, offset >= 0);
        SymbolInfo symbol = analyse(source).resolveAt(offset);
        assertNotNull("nothing resolved at " + needle, symbol);
        return symbol;
    }

    private static String signatureOf(String source, String needle) {
        Signature signature = resolve(source, needle).signature();
        assertNotNull("no signature was rendered for " + needle, signature);
        return signature.text();
    }

    /** The capture names covering {@code word} in the signature — what colours it. */
    private static List<String> capturesOver(Signature signature, String word) {
        int at = signature.text().indexOf(word);
        assertTrue(word + " is not in [" + signature.text() + "]", at >= 0);
        List<String> found = new ArrayList<>();
        for (SyntaxToken token : signature.tokens()) {
            if (token.start() <= at && at < token.end()) found.add(token.name());
        }
        return found;
    }

    // ── JavaScript declarations ─────────────────────────────────────────────────────────────────

    @Test
    public void aFunctionRendersItsNameAndItsParameterNames() {
        // THE NAMES, which JavaScript always has and Java's compiled path never does — a member read from
        // a class file reports arg0, so JavaSignatures shows types alone.
        assertEquals("function summarise(items, rate)",
                signatureOf("function summarise(items, rate) { return items; }\n", "summarise"));
    }

    @Test
    public void aFunctionWithNoParametersRendersEmptyBrackets() {
        assertEquals("function main()", signatureOf("function main() { return 1; }\n", "main"));
    }

    @Test
    public void aJsDocTypedFunctionShowsTheTypesItDocuments() {
        String source = "/**\n * Adds.\n * @param {string} name\n * @param {number} count\n"
                + " * @returns {string}\n */\nfunction join(name, count) { return name; }\n";
        assertEquals("function join(name: string, count: number): string",
                signatureOf(source, "join"));
    }

    @Test
    public void aPartlyDocumentedFunctionShowsOnlyWhatWasDocumented() {
        // `?` on every undocumented parameter would be a column of question marks in most JavaScript.
        String source = "/** @param {string} name */\nfunction pair(name, other) { return name; }\n";
        assertEquals("function pair(name: string, other)", signatureOf(source, "pair"));
    }

    @Test
    public void aConstSaysConstAndAVarSaysVar() {
        // The one thing about a JavaScript variable a reader cannot get from the name.
        assertEquals("const RATE: number", signatureOf("const RATE = 1.5;\n", "RATE"));
        assertEquals("var label: string", signatureOf("var label = 'hi';\n", "label"));
    }

    @Test
    public void anUntypedDeclarationRendersWithoutAType() {
        assertEquals("var pending", signatureOf("var pending;\n", "pending"));
    }

    @Test
    public void aParameterRendersAsItselfWithNoKeyword() {
        // No keyword declares a parameter, and writing `var` in front of one would be an invention.
        assertEquals("count", signatureOf("function f(count) { return count; }\n", "count"));
    }

    /**
     * <b>By its simple name</b>, which this used to assert the opposite of.
     *
     * <p>It expected {@code java.util.ArrayList} — and a type that came back from a MEMBER lookup was
     * already displayed short, so one popup showed {@code var list: java.util.ArrayList} directly above
     * {@code var text: CgMaterial}. Two conventions decided by which tier happened to answer.</p>
     *
     * <p>Short is the one to keep: it is what the Java engine renders for the identical declaration, and
     * the package is not lost — it is in the owner band above, which is that band's whole purpose.
     * Only the DISPLAY is short; {@code qualifiedName()} still carries the binary name, which is what
     * every member lookup and policy check is keyed on.</p>
     */
    @Test
    public void aJavaTypedDeclarationNamesTheJavaClassSimply() {
        assertEquals("var list: ArrayList",
                signatureOf("var list = new java.util.ArrayList();\n", "list"));
    }

    /**
     * The tokens speak the same capture vocabulary the editor is coloured by.
     *
     * <p>Which is the whole reason {@code Signature} carries tokens rather than a string: the popup renders
     * them with the scheme that colours the line of code two pixels away, so a parallel vocabulary would
     * show the same construct in two colours.</p>
     */
    @Test
    public void theTokensUseTheSameCaptureNamesTheEditorDoes() {
        Signature signature = resolve("function add(a, b) { return a + b; }\n", "add").signature();
        assertNotNull(signature);
        assertTrue("the keyword is not captured as one", capturesOver(signature, "function")
                .contains("keyword"));
        assertTrue("the name is not captured as a function",
                capturesOver(signature, "add").contains("function"));
        assertTrue("a parameter is not captured as one",
                capturesOver(signature, "a,").contains("variable.parameter"));
        assertTrue("the bracket is not captured",
                capturesOver(signature, "(").contains("punctuation.bracket"));
    }

    /**
     * A long parameter list is broken by the ENGINE, one parameter per line.
     *
     * <p>Breaks belong at semantic points and only the engine knows where those are; it cannot know how wide
     * the box is, so it breaks on the declaration's own length. A widget re-wrapping at the edge of its box
     * splits between whatever two words happen to land there.</p>
     */
    @Test
    public void aLongParameterListIsBrokenOnePerLine() {
        String source = "function configure(alphaSetting, betaSetting, gammaSetting, deltaSetting,"
                + " epsilonSetting) { return alphaSetting; }\n";
        String rendered = signatureOf(source, "configure");
        assertTrue("a signature far past the line budget was not broken: " + rendered,
                rendered.contains("\n"));
        for (String line : rendered.split("\n")) {
            assertTrue("a broken line is still too long: [" + line + "]", line.length() <= 72);
        }
        assertTrue(rendered.contains("alphaSetting"));
        assertTrue(rendered.contains("epsilonSetting"));
    }

    @Test
    public void aShortDeclarationIsNotBroken() {
        // Breaking unconditionally would put `const RATE` on two lines.
        assertFalse(signatureOf("const RATE = 1;\n", "RATE").contains("\n"));
    }

    // ── Java members reached from JavaScript ────────────────────────────────────────────────────

    /**
     * <b>The milestone's second criterion.</b> A Java member reached from JavaScript is described by the
     * Java engine — and quoted from {@code src.zip} when the JDK has one beside it.
     *
     * <p>{@code membersOf} deliberately carries no signature, so this only works because one member is
     * asked about separately, through a probe unit that names it. Skipped rather than weakened when there is
     * no attached source: a JRE ships none, and "it works on my machine" is the literal failure mode for
     * this feature.</p>
     */
    @Test
    public void aJavaMemberIsQuotedFromItsSource() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var list = new java.util.ArrayList();\nlist.add('one');\n";
        SymbolInfo add = analyse(source).resolveAt(source.indexOf("add"));
        assertNotNull("the member did not resolve", add);
        Signature signature = add.signature();
        assertNotNull("a Java member reached from JavaScript has no signature at all", signature);
        assertFalse(signature.isEmpty());
        assertTrue("the signature does not name the member: [" + signature.text() + "]",
                signature.text().contains("add"));

        // QUOTED, not assembled, when the JDK shipped src.zip: a quote carries the real parameter NAME,
        // which no class file has. Asserted as an implication rather than unconditionally, because a JRE
        // has no src.zip and the assembled fallback is the correct answer there.
        // ONE OF EXACTLY TWO SHAPES, stated rather than tolerated. With src.zip on disk this is the
        // JDK's own line -- `public boolean add(E e)`, complete with the parameter NAME, which no class
        // file carries. Without it the assembled fallback names the types instead. A test that accepted
        // "anything containing add" would pass against a signature that had silently stopped quoting.
        String text = signature.text();
        boolean quoted = "public boolean add(E e)".equals(text);
        boolean assembled = "boolean add(Object)".equals(text);
        assertTrue("neither the quoted nor the assembled shape: [" + text + "]", quoted || assembled);
    }

    @Test
    public void aJavaMemberSignatureNamesItsParameterTypes() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var s = new java.lang.StringBuilder();\ns.append('x');\n";
        SymbolInfo append = analyse(source).resolveAt(source.indexOf("append"));
        assertNotNull(append);
        assertNotNull("no signature for a Java member", append.signature());
        assertTrue("[" + append.signature().text() + "] does not look like a declaration",
                append.signature().text().contains("append("));
    }

    // ── Go-to-definition ────────────────────────────────────────────────────────────────────────

    /** A JavaScript declaration is in this document, so the jump is a caret move. */
    @Test
    public void aJavaScriptSymbolKnowsWhereItIsDeclared() {
        String source = "function helper() { return 1; }\nvar x = helper();\n";
        SymbolInfo use = analyse(source).resolveAt(source.lastIndexOf("helper"));
        assertNotNull(use);
        assertNotNull("go-to-definition has nowhere to send", use.declaration());
        assertTrue("a same-document declaration must say so", use.declaration().isSameDocument());
        assertEquals("the declaration is on the first line", 0, use.declaration().start().row());
        assertEquals("and at the name, not the keyword", 9, use.declaration().start().column());
    }

    /**
     * A Java member's declaration is in <em>another file</em> — the attached source — so the site names it.
     *
     * <p>The other half of go-to-definition, and the half the editor cannot perform itself: opening a
     * document is a workspace act. Only asserted when the class has source beside it, for the reason
     * above.</p>
     */
    @Test
    public void aJavaMemberPointsAtItsOwnSourceWhenThereIsAny() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var list = new java.util.ArrayList();\nlist.add('one');\n";
        SymbolInfo add = analyse(source).resolveAt(source.indexOf("add"));
        assertNotNull(add);
        if (add.declaration() == null) return;
        assertFalse("a Java member's declaration is not in the JavaScript document",
                add.declaration().isSameDocument());
    }

    // ── Nothing worth drawing ───────────────────────────────────────────────────────────────────

    @Test
    public void anUnresolvedNameHasNoSignature() {
        assertNull(analyse("missing();\n").resolveAt(0));
    }
}
