package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Hover does not describe a construct the parser invented.
 *
 * <h3>The third surface of one cascade</h3>
 *
 * <p>An unterminated statement makes ECJ recover, and the recovery joins it with the line below. That one
 * misreading has now been met three times: as <em>diagnostics</em> blaming the next line, as <em>colours</em>
 * repainting it, and here as <em>resolution</em> describing it. Given</p>
 *
 * <pre>    sdasdsadassadasdasdaasdfg f gdfdsadas dsa
 *     System.out.println("fa");</pre>
 *
 * <p>the recovery reads {@code dsa System} as a local variable declaration, so hovering {@code System} —
 * on a line the user has not touched — reported <b>a local variable of type {@code dsa}</b> and quoted a
 * declaration running across the line break. Nothing failed. The popup opened, which is what makes it read
 * as the hover being wrong rather than as the parse being a different reading of the same text.</p>
 *
 * <h3>Why the scope is the STATEMENT</h3>
 *
 * <p>JDT offers two flags and only one of them is usable. {@code MALFORMED} sits on the whole enclosing
 * {@code MethodDeclaration}, so keying on it would silence hover for every symbol in a method for as long
 * as any one statement in it was unfinished — which is most of the time anyone is typing.
 * {@code RECOVERED} sits on the invented statement itself. Both halves are asserted below, because a fix
 * that declined too widely would pass the first test on its own.</p>
 */
public class RecoveredResolutionTest {

    private JavaEngine engine;

    @Before
    public void openEngine() throws IOException {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
    }

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    /**
     * What the editor's own hover would show — through {@link com.crystalgui.text.lang.Resolver}, not
     * through {@code Analysis} directly.
     *
     * <p>The distinction is the fix: {@code Analysis.resolveAt} still answers, because <b>completion</b>
     * calls it and completion is asked about incomplete text by definition. Only this seam declines.</p>
     */
    private SymbolInfo hoverAt(String source, String needle) {
        TextBuffer buffer = new TextBuffer(source);
        JavaLanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            services.environmentChanged();
            AtomicReference<SymbolInfo> got = new AtomicReference<>();
            services.resolver().resolveAt(source.lastIndexOf(needle) + 1,
                    answer -> got.set(answer.orElse(null)));
            return got.get();
        } finally {
            services.close();
        }
    }

    private static final String BROKEN = ""
            + "public class Demo {\n"
            + "    public static void peeposo(){\n"
            + "        String innocent = \"q\";\n"
            // TWO declarations and no terminator, which is the reported text and not a flourish: with a
            // single one ECJ recovers cleanly and `System` below still resolves to java.lang.System. It is
            // the second declaration that leaves the parser mid-statement at the line break, so it reaches
            // across for a name -- and finds the one on the next line.
            + "        sdasdsadassadasdasdaasdfg f gdfdsadas dsa\n"
            + "        System.out.println(\"fa\");\n"
            + "    }\n"
            + "}\n";

    /** <b>The report:</b> the line below an unfinished one must not be described from the recovery. */
    @Test
    public void hoverDeclinesInsideARecoveredStatement() {
        SymbolInfo described = hoverAt(BROKEN, "System");
        assertNull("the recovery's invented declaration was described as if it were written: "
                + describe(described), described);
    }

    /**
     * <b>...and a statement the recovery did not touch still answers.</b>
     *
     * <p>The half that makes the first test mean something. Declining is trivial to do too widely, and
     * the natural over-reach here — JDT's {@code MALFORMED}, or "does this file parse" — is method-wide
     * and document-wide respectively, so either would leave hover dead across the whole file the moment a
     * single semicolon was missing, and both would pass the test above.</p>
     */
    @Test
    public void hoverStillAnswersForAValidStatementInTheSameBrokenMethod() {
        SymbolInfo described = hoverAt(BROKEN, "innocent");
        assertNotNull("a statement nobody broke stopped resolving", described);
        assertEquals("innocent", described.name());
        assertNotNull("the type went missing", described.type());
        assertEquals("String", described.type().displayName());
    }

    /** And an unbroken file is untouched by any of it. */
    @Test
    public void hoverAnswersNormallyWhenTheFileParses() {
        String whole = ""
                + "public class Demo {\n"
                + "    public static void peeposo(){\n"
                + "        String innocent = \"q\";\n"
                + "        System.out.println(\"fa\");\n"
                + "    }\n"
                + "}\n";
        SymbolInfo described = hoverAt(whole, "innocent");
        assertNotNull("a file that parses stopped resolving", described);
        assertEquals("innocent", described.name());
    }

    private static String describe(SymbolInfo symbol) {
        return symbol == null ? "<null>"
                : symbol.name() + " : " + symbol.kind() + " of " + symbol.type();
    }
}
