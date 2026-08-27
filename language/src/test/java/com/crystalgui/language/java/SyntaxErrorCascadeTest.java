package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A syntax error does not blame the line below it.
 *
 * <h3>Recovery DELETES the offending token, and the blame lands on innocent code</h3>
 *
 * <p>Reported as "why is this erroring when it has a semicolon above it". ECJ answers
 * {@code System.out.;} with two errors: the syntax error on the {@code ;}, which is right, and
 * <em>"System cannot be resolved or is not a field"</em> on the NEXT line, which is not — recovery
 * deletes the token, the two statements join into {@code System.out.System.out.println("fa")}, and a
 * perfectly correct line is underlined.</p>
 *
 * <p>Nothing failed to produce that. Every step did its job on a reading of the text nobody wrote, which
 * is the same shape as the empty completion list this milestone spent four rounds on.</p>
 */
public class SyntaxErrorCascadeTest {

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

    private List<Diagnostic> diagnosticsFor(String source) {
        TextBuffer buffer = new TextBuffer(source);
        JavaLanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            AtomicReference<List<Diagnostic>> reported = new AtomicReference<>(List.of());
            services.onDiagnostics(versioned -> reported.set(versioned.orElse(List.of())));
            services.environmentChanged();
            return new ArrayList<>(reported.get());
        } finally {
            services.close();
        }
    }

    private static String messages(List<Diagnostic> found) {
        List<String> lines = new ArrayList<>(found.size());
        for (Diagnostic d : found) lines.add("line " + (d.start().row() + 1) + ": " + d.message());
        return lines.toString();
    }

    /**
     * <b>Only the syntax error is reported, and it is on the line that has it.</b>
     *
     * <p>The exact text from the report. Asserted as "exactly one", not "the second one is absent":
     * a rule that suppressed everything would satisfy the weaker form, and the syntax error is the one
     * thing that must survive — it is what tells you where to look.</p>
     */
    @Test
    public void aSyntaxErrorDoesNotBlameTheLineBelowIt() {
        List<Diagnostic> found = diagnosticsFor(""
                + "public class Demo {\n"
                + "    public static void peeposo(){\n"
                + "        System.out.;\n"
                + "        System.out.println(\"fa\");\n"
                + "    }\n"
                + "}\n");

        assertEquals("the cascade is still reported: " + messages(found), 1, found.size());
        assertEquals("the mark moved off the line that actually has the error",
                2, found.get(0).start().row());
        assertTrue("the surviving error is not the syntax one: " + found.get(0).message(),
                found.get(0).message().contains("Syntax error"));
    }

    /**
     * <b>A recovery that reached across a line break is marked on the line it started on.</b>
     *
     * <p>{@code sfafafas} alone is not a statement — it is half a declaration — so the parser keeps looking
     * for a name, takes {@code System} off the line below, and reports <em>"Syntax error on token '.', ';'
     * expected"</em> against the {@code .} of a line nobody touched.</p>
     *
     * <p><b>It is the odd-token case, which is why it read as intermittent.</b> Write two words and the
     * declaration is complete: ECJ reports {@code InsertToComplete} at the end of that line, correctly, and
     * the line below is untouched. One word and it reaches. Reported as "when it's just one run without
     * spaces it still breaks the line under it", after the two-word form had been confirmed fixed.</p>
     */
    @Test
    public void anOmissionTheRecoveryReachedAcrossALineForIsMarkedWhereItBelongs() {
        List<Diagnostic> found = diagnosticsFor(""
                + "public class Demo {\n"
                + "    public static void peeposo(){\n"
                + "        sfafafas\n"
                + "        System.out.println(\"fa\");\n"
                + "    }\n"
                + "}\n");

        assertEquals("more than the one syntax error survived: " + messages(found), 1, found.size());
        assertEquals("the mark stayed on the line the recovery reached INTO: " + messages(found),
                2, found.get(0).start().row());
    }

    /**
     * <b>...and a statement that genuinely spans lines keeps ECJ's own mark.</b>
     *
     * <p>The half that makes the rule safe rather than merely effective. A mark is only ever moved BACK
     * onto the line an omission is on, never sideways and never off a construct that really does run to
     * the line it is marked on. Measured against five cross-line shapes — a parenthesised expression, an
     * argument list, an {@code if} condition, an array initialiser and a generic call — and not one of
     * them reports {@code ParsingError}, which is what makes the id load-bearing rather than incidental.
     * This is the nearest miss of the five.</p>
     */
    @Test
    public void aStatementThatGenuinelySpansLinesKeepsItsOwnMark() {
        List<Diagnostic> found = diagnosticsFor(""
                + "public class Demo {\n"
                + "    void m(){\n"
                + "        int a = foo(1,\n"
                + "            2 3);\n"
                + "    }\n"
                + "}\n");

        assertEquals("the syntax error went missing: " + messages(found), 1, found.size());
        assertEquals("a mark was dragged off the line it belonged on: " + messages(found),
                3, found.get(0).start().row());
    }

    /**
     * <b>A file that parses reports its semantic errors as before.</b>
     *
     * <p>The counter-assertion, and it is not a formality: a suppression written as "report nothing when
     * anything is wrong" passes the test above and makes the editor blind. Errors are hidden only while
     * the file does not PARSE — which is what {@code optionalProblemsAnalysed} already keys warnings on.</p>
     */
    @Test
    public void aFileThatParsesStillReportsItsSemanticErrors() {
        List<Diagnostic> found = diagnosticsFor(""
                + "public class Demo {\n"
                + "    void run() {\n"
                + "        NoSuchType thing = null;\n"
                + "    }\n"
                + "}\n");

        assertTrue("a resolution error in a file that parses was suppressed: " + messages(found),
                found.stream().anyMatch(d -> d.message().contains("NoSuchType")));
    }
}
