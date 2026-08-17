package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.exec.ScriptPrelude;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

import org.junit.Assume;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The script wrapper, and the arithmetic that gets back out of it.
 *
 * <p>Mostly pure — no engine needed, so it runs everywhere and fast. The last two tests do open an
 * engine, because "the offsets are right" is only worth anything if a real compiler's real diagnostic
 * lands on the line the author is looking at.</p>
 */
public class ScriptPreludeTest {

    private static ScriptPrelude.Wrapped wrap(String script) {
        return ScriptPrelude.forClass("Script").build().wrap(script);
    }

    @Test
    public void theScriptBodyAppearsVerbatimAfterAFixedPrefix() {
        ScriptPrelude.Wrapped wrapped = wrap("int x = 1;\n");
        String unit = wrapped.unitSource();

        assertTrue(unit.contains("public class Script {"));
        assertTrue(unit.contains("int x = 1;"));
        assertEquals("the body must start exactly at prefixLength",
                "int x = 1;", unit.substring(wrapped.prefixLength(), wrapped.prefixLength() + 10));
    }

    @Test
    public void hostBindingsBecomeFieldsInScope() {
        ScriptPrelude.Wrapped wrapped = ScriptPrelude.forClass("Script")
                .binding("graph", "java.util.List")
                .binding("count", "int")
                .build()
                .wrap("");
        String unit = wrapped.unitSource();
        assertTrue(unit, unit.contains("public java.util.List graph;"));
        assertTrue(unit, unit.contains("public int count;"));
    }

    @Test
    public void offsetsMapBothWaysAndAreAConstant() {
        ScriptPrelude.Wrapped wrapped = wrap("int alpha = 1;\nint beta = 2;\n");
        for (int offset : new int[]{0, 4, 15, 20}) {
            assertEquals(offset, wrapped.toScriptOffset(wrapped.toUnitOffset(offset)));
        }
        // The definition of "constant": the difference is the same everywhere.
        assertEquals(wrapped.toUnitOffset(0) - 0, wrapped.toUnitOffset(20) - 20);
    }

    @Test
    public void anOffsetInsideThePreludeMapsToNothingRatherThanToZero() {
        // A problem in code the author never wrote cannot be fixed by them. Clamping to 0 would put a
        // squiggle on their first character and blame them for it; -1 lets the caller drop it.
        ScriptPrelude.Wrapped wrapped = wrap("int x = 1;\n");
        assertEquals(-1, wrapped.toScriptOffset(0));
        assertEquals(-1, wrapped.toScriptOffset(wrapped.prefixLength() - 1));
        assertEquals(0, wrapped.toScriptOffset(wrapped.prefixLength()));
    }

    @Test
    public void rowsMapByTheSameConstant() {
        ScriptPrelude.Wrapped wrapped = wrap("int a = 1;\nint b = 2;\nint c = 3;\n");
        assertEquals(new TextPoint(0, 4),
                wrapped.toScriptPoint(new TextPoint(wrapped.prefixRows(), 4)));
        assertEquals(new TextPoint(2, 0),
                wrapped.toScriptPoint(new TextPoint(wrapped.prefixRows() + 2, 0)));
        assertNull("a row inside the prelude is not the author's",
                wrapped.toScriptPoint(new TextPoint(0, 0)));
    }

    // ── Import hoisting, which must not move anything ────────────────────────────────────────────

    @Test
    public void anImportIsHoistedIntoThePreludeAndBlankedInPlace() {
        String script = "import java.util.List;\nList<String> names = null;\n";
        ScriptPrelude.Wrapped wrapped = wrap(script);
        String unit = wrapped.unitSource();

        // Hoisted: it appears BEFORE the class declaration, which is where Java requires it.
        assertTrue(unit.indexOf("import java.util.List;") < unit.indexOf("public class Script"));

        // Blanked in place: the body still occupies exactly the same span, so every offset after it
        // is unchanged. THIS is what keeps the mapping a constant rather than a table.
        String body = unit.substring(wrapped.prefixLength());
        assertTrue("the import was removed rather than blanked, so offsets shifted",
                body.startsWith("                      \n"));
        assertEquals("the author's second line must still be their second line",
                new TextPoint(1, 0), wrapped.toScriptPoint(new TextPoint(wrapped.prefixRows() + 1, 0)));
    }

    @Test
    public void severalImportsAnywhereInTheScriptAreAllHoisted() {
        String script = ""
                + "import java.util.List;\n"
                + "int x = 1;\n"
                + "import java.util.Map;\n"
                + "int y = 2;\n";
        String unit = wrap(script).unitSource();
        int classAt = unit.indexOf("public class Script");
        assertTrue(unit.indexOf("import java.util.List;") < classAt);
        assertTrue("an import below the first statement was not hoisted",
                unit.indexOf("import java.util.Map;") < classAt);
    }

    @Test
    public void aStaticImportIsHoistedToo() {
        String unit = wrap("import static java.util.Arrays.asList;\nasList(1);\n").unitSource();
        assertTrue(unit.indexOf("import static java.util.Arrays.asList;")
                < unit.indexOf("public class Script"));
    }

    @Test
    public void thewordImportInsideAStringIsNotHoisted() {
        // The pattern is anchored to a line start for exactly this. Hoisting text out of a string
        // would change what the script DOES, silently.
        String script = "String s = \"import java.util.List;\";\n";
        String unit = wrap(script).unitSource();
        assertTrue("an import inside a string literal was hoisted",
                unit.indexOf("import java.util.List;") > unit.indexOf("public class Script"));
    }

    // ── Against a real compiler ─────────────────────────────────────────────────────────────────

    private static JavaEngine openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        return JavaEngine.open(band, source);
    }

    @Test
    public void awrappedScriptCompilesAndItsDiagnosticNamesTheAuthorsLine() throws Exception {
        // THE POINT OF THE WHOLE FILE. A diagnostic that names a line inside an invisible wrapper is
        // undebuggable, and it is the failure this arithmetic exists to prevent.
        JavaEngine engine = openEngine();
        try {
            String script = ""
                    + "int a = 1;\n"
                    + "int b = 2;\n"
                    + "int c = nope();\n";        // the author's line 2 (0-based)
            ScriptPrelude.Wrapped wrapped = wrap(script);

            SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                    wrapped.className(), wrapped.unitSource(), List.of(), engine.releaseLevel(), 1L);
            try {
                List<Diagnostic> unitProblems = analysis.diagnostics();
                assertFalse("the broken call reported nothing", unitProblems.isEmpty());

                Diagnostic mapped = wrapped.toScriptDiagnostic(unitProblems.get(0));
                assertNotNull("the problem was dropped as prelude-only", mapped);
                assertEquals(DiagnosticSeverity.ERROR, mapped.severity());
                assertEquals("the squiggle is not on the line the author wrote it on",
                        2, mapped.start().row());
                assertTrue(mapped.message(), mapped.message().contains("nope"));
            } finally {
                analysis.close();
            }
        } finally {
            engine.close();
        }
    }

    @Test
    public void aScriptUsingAHostBindingAndAHoistedImportResolves() throws Exception {
        // Everything at once: a binding the author never declared, an import written mid-script, and
        // resolution landing on the author's own offsets.
        JavaEngine engine = openEngine();
        try {
            String script = ""
                    + "import java.util.List;\n"
                    + "int size = names.size();\n";
            ScriptPrelude.Wrapped wrapped = ScriptPrelude.forClass("Script")
                    .binding("names", "java.util.List<String>")
                    .build()
                    .wrap(script);

            SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                    wrapped.className(), wrapped.unitSource(), List.of(), engine.releaseLevel(), 1L);
            try {
                // NO ERRORS, rather than no diagnostics. The compiler warns that the import is unused
                // and that the local is never read, and both are true of this fixture -- a script is a
                // fragment and warnings about fragments are a policy question (which warnings a script
                // context should suppress) rather than evidence the wrapper is wrong.
                for (Diagnostic problem : analysis.diagnostics()) {
                    assertFalse("a script using its host binding did not compile: " + problem,
                            problem.severity() == DiagnosticSeverity.ERROR);
                }

                int inScript = script.indexOf("names.size") + 2;
                com.crystalgui.text.lang.SymbolInfo symbol =
                        analysis.resolveAt(wrapped.toUnitOffset(inScript));
                assertNotNull("the host binding resolved to nothing", symbol);
                assertEquals("names", symbol.name());
                assertEquals("List<String>", symbol.type().displayName());
            } finally {
                analysis.close();
            }
        } finally {
            engine.close();
        }
    }
}
