package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A Java symbol reads the same in a {@code .js} file as in a {@code .java} one.</b>
 *
 * <p>Which is the whole point of the interop tier, and two places were not keeping it. Both were found
 * by putting the same three lines side by side in the two editors.</p>
 */
public class JsInteropColourTest {

    @BeforeClass
    public static void openBothEngines() {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue(JavaLanguage.register(null, EngineHost.defaultSource()));
        Assume.assumeTrue(JsLanguage.register(null, EngineHost.defaultSource()));
    }

    /** Every capture over the {@code occurrence}-th appearance of {@code text}. */
    private static List<String> capturesOver(String source, String text, int occurrence) {
        int at = -1;
        for (int n = 0; n <= occurrence; n++) at = source.indexOf(text, at + 1);
        assertTrue("fixture has no occurrence " + occurrence + " of " + text, at >= 0);
        List<String> found = new ArrayList<>();
        for (SyntaxToken token : JsLanguage.analyzer().analyze("Probe.js", source, 1L).semanticTokens()) {
            if (token.start() == at && token.end() == at + text.length()) found.add(token.name());
        }
        return found;
    }

    /**
     * <b>An imported Java class is a type wherever it appears</b>, not only on the import line.
     *
     * <p>Imports are merged into the host's bindings on purpose — both are names the file never
     * declared, and everything asking "what is in scope" should see them alike. Colour is where they
     * part: a host binding is an <em>instance</em> the application handed over, an import is a
     * <em>class</em>. Left to the merge, the same word was a type on line 1 and a local on line 2.</p>
     */
    @Test
    public void anImportedClassIsATypeWhereverItIsUsed() {
        String source = "import java.util.Collections;\nvar empty = Collections.EMPTY_LIST;\n";
        assertEquals("the import line", List.of("type"), capturesOver(source, "Collections", 0));
        assertTrue("the USE fell back to a global, which paints as a local: "
                        + capturesOver(source, "Collections", 1),
                capturesOver(source, "Collections", 1).contains("type"));
    }

    /**
     * <b>A {@code static final} Java field is a constant, not a property.</b>
     *
     * <p>M10 §12a deferred this as "a bridge crossing per member access, on every keystroke". The price
     * was over-estimated: semantic tokens are built once per analysis, and {@code InteropResolver}
     * caches a class's member list, so it costs one lookup per file per Java type. What it buys is the
     * thing the hover already knew and the editor did not — the popup renders
     * {@code public static final CgMaterial TEXT_MATERIAL} while the line behind it drew the name as an
     * ordinary property.</p>
     */
    @Test
    public void aStaticFinalJavaFieldReadsAsAConstant() {
        String source = "import java.util.Collections;\nvar empty = Collections.EMPTY_LIST;\n";
        List<String> captures = capturesOver(source, "EMPTY_LIST", 0);
        assertTrue("a static final field is still drawn as a plain property: " + captures,
                captures.contains("constant"));
    }

    /**
     * <b>A variable initialised from a Java MEMBER carries that member's type.</b>
     *
     * <p>{@code var list = new java.util.ArrayList()} typed correctly and
     * {@code var text = CgTextRenderer.TEXT_MATERIAL} typed to nothing, two lines apart — which reads
     * as the engine being arbitrary rather than as one tier not covering a shape. The syntactic tier
     * settles {@code new X()}, {@code Java.type(\"\")} and a package chain on its own and cannot read a
     * member; {@link com.crystalgui.language.js.rhino.resolve.RhinoResolution} has always known how
     * (a property read's type is the member's type) and the declaration path never asked it.</p>
     */
    @Test
    public void aVarInitialisedFromAJavaMemberKnowsItsType() {
        String source = "import java.util.Collections;\nvar empty = Collections.EMPTY_LIST;\n";
        SymbolInfo declared = JsLanguage.analyzer().analyze("Probe.js", source, 1L)
                .resolveAt(source.indexOf("empty") + 1);
        assertNotNull("the declaration did not resolve at all", declared);
        assertNotNull("the hover shows `var empty` with no type, which is what this fixes",
                declared.type());
        assertTrue("typed as <" + declared.type().displayName() + ">",
                declared.type().displayName().contains("List"));
    }

    /**
     * And a declaration that names itself answers nothing rather than recursing forever.
     *
     * <p>{@code var a = a.b;} is legal to write. The syntactic tier could not loop because it never
     * resolved a name; asking the resolver instead makes the cycle reachable, so it is cut.</p>
     */
    @Test
    public void aSelfReferentialDeclarationDoesNotRecurse() {
        String source = "var a = a.b;\n";
        SymbolInfo declared = JsLanguage.analyzer().analyze("Probe.js", source, 1L)
                .resolveAt(source.indexOf("a ="));
        assertNotNull("resolving a self-referential declaration threw or answered nothing", declared);
    }

    /**
     * <b>A Java type is shown by its simple name, from ONE implementation.</b>
     *
     * <p>There were two, answering the same question from different inputs: the syntactic tier built a
     * display name from a binary name, and the reflection tier called {@code Class.getSimpleName()}. So
     * one popup could read {@code var list: java.util.ArrayList} directly above {@code var text:
     * CgMaterial}, decided by which tier happened to answer — and the two would also have disagreed
     * about a nested class, where {@code getSimpleName()} says {@code Entry} and an author writes
     * {@code Map.Entry}.</p>
     *
     * <p>Asserted through the hover, which is where a reader meets it.</p>
     */
    @Test
    public void aJavaTypeIsShownByItsSimpleName() {
        String source = "var list = new java.util.ArrayList();\n";
        SymbolInfo declared = JsLanguage.analyzer().analyze("Probe.js", source, 1L)
                .resolveAt(source.indexOf("list") + 1);
        assertNotNull(declared);
        assertNotNull(declared.type());
        assertEquals("the display carries the package the owner band already shows",
                "ArrayList", declared.type().displayName());
        assertEquals("and the QUALIFIED name must not shorten -- every lookup is keyed on it",
                "java.util.ArrayList", declared.type().qualifiedName());
    }

    /** And an ordinary object's property keeps the grammar's answer, which is already right for it. */
    @Test
    public void aPlainObjectPropertyIsLeftToTheGrammar() {
        String source = "var o = { alpha: 1 };\nvar b = o.alpha;\n";
        assertEquals("the semantic pass second-guessed a plain property",
                List.of(), capturesOver(source, "alpha", 1));
    }
}
