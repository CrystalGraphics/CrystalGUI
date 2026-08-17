package com.crystalgui.language.js;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.AnalysedLanguageServices;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A thrown exception squiggles its line</b> — the M10.5 criterion the console alone cannot meet.
 *
 * <p>The run's verdict travels from the script's thread to the document's services and out through the
 * same {@code onDiagnostics} the analyser uses, filed beside the analysis's own problems and named as the
 * runtime's. Then the two things that make it honest rather than merely present: the next run withdraws
 * it, and an edit above it moves it.</p>
 */
public class JsRuntimeProblemsTest {

    private static final Resource FILE = Resource.of(Resource.SCHEME_PROJECT, "src/Boom.js");

    private JsHost host;
    private TextBuffer buffer;
    private JsLanguageServices services;
    private final List<List<Diagnostic>> announced = new ArrayList<>();

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @Before
    public void openDocument() {
        host = new JsHost(JsLanguage.executor());
        buffer = new TextBuffer("var a = 1;\nthrow new Error('boom');\nvar b = 2;\n");
        // NO SCHEDULER: analyses run inline and the runtime's report lands inline, which is what lets a
        // test read the announcement straight after the run.
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Boom.js", FILE);
        services.onDiagnostics(list -> announced.add(list.orElse(List.of())));
    }

    @After
    public void closeDocument() {
        if (services != null) services.close();
        if (host != null) host.close();
    }

    private void runIgnoringFailure(String source) {
        ScriptRuntime.Compiled compiled = host.compileScript("Boom.js", source, Map.of()).withSource(FILE);
        assertTrue(compiled.messages().toString(), compiled.successful());
        try {
            host.run(compiled, Map.of());
        } catch (Throwable expected) {
            // The point of the test.
        }
    }

    private List<Diagnostic> lastAnnounced() {
        assertFalse("nothing was announced", announced.isEmpty());
        return announced.get(announced.size() - 1);
    }

    private static Diagnostic runtimeProblemIn(List<Diagnostic> problems) {
        for (Diagnostic problem : problems) {
            if (JsHost.RUNTIME_SOURCE.equals(problem.source())) return problem;
        }
        return null;
    }

    @Test
    public void theRuntimeFindsTheDocumentByItsFile() {
        assertSame(services, AnalysedLanguageServices.attachedTo(FILE));
    }

    @Test
    public void aThrownErrorLandsOnItsLine() {
        assertNull("nothing had run yet", runtimeProblemIn(lastAnnounced()));
        runIgnoringFailure(buffer.document().toString());

        Diagnostic problem = runtimeProblemIn(lastAnnounced());
        assertNotNull("the run's failure never reached the document: " + lastAnnounced(), problem);
        assertEquals(DiagnosticSeverity.ERROR, problem.severity());
        assertEquals("on the throw, not the first line", 1, problem.start().row());
        assertEquals("Error: boom", problem.message());
        // AND IT SPANS THE STATEMENT, which is the half a row assertion cannot see. Rhino reports a line
        // and no column, so the mark is the whole line -- and it was a ONE-CHARACTER mark in the leading
        // whitespace, because `Rope.pointToOffset` overflowed on the Integer.MAX_VALUE end column for
        // every row but the first. Everything else about it was right: the row, the message, the owner,
        // the Problems entry. @see RopeTest#aWholeLineColumnClampsRatherThanOverflowing
        assertEquals("the mark does not reach the end of the line", 1, problem.end().row());
        assertEquals("the mark collapsed to a point rather than spanning the statement",
                buffer.line(1).length(), problem.end().column());
    }

    @Test
    public void theNextRunWithdrawsIt() {
        runIgnoringFailure(buffer.document().toString());
        assertNotNull(runtimeProblemIn(lastAnnounced()));

        runIgnoringFailure("var fine = true;\n");
        assertNull("the previous run's verdict survived a run that got past it",
                runtimeProblemIn(lastAnnounced()));
    }

    @Test
    public void anEditAboveItMovesIt() {
        runIgnoringFailure(buffer.document().toString());
        assertEquals(1, runtimeProblemIn(lastAnnounced()).start().row());

        // A line inserted at the top: the analysis re-runs (inline) and the runtime problem is
        // re-stated where its text is NOW -- the whole reason it lives in a tracked lane.
        buffer.replace(0, 0, "// a new first line\n");
        Diagnostic moved = runtimeProblemIn(lastAnnounced());
        assertNotNull("the runtime problem was lost on an edit", moved);
        assertEquals(2, moved.start().row());
    }

    /**
     * Commenting the line out withdraws it — the mark is evidence about that text, not about that row.
     *
     * <p>Reported from the harness: the statement was commented out and the error stayed, because the
     * range survived the insertion (it grew to include the {@code //}) and only a <em>run</em> withdrew
     * it. So the editor claimed a line that cannot execute was broken, until you ran the file again.</p>
     */
    @Test
    public void editingTheMarkedLineWithdrawsIt() {
        runIgnoringFailure(buffer.document().toString());
        assertNotNull(runtimeProblemIn(lastAnnounced()));

        // The user's gesture, exactly: toggle-comment on the throwing line.
        buffer.replace(11, 11, "// ");
        assertNull("the error survived its own line being commented out",
                runtimeProblemIn(lastAnnounced()));
    }

    /** And undoing that brings it back, because the text is the evidence and it is the text again. */
    @Test
    public void undoingTheEditRestoresIt() {
        runIgnoringFailure(buffer.document().toString());
        buffer.replace(11, 11, "// ");
        assertNull(runtimeProblemIn(lastAnnounced()));

        buffer.replace(11, 14, "");
        assertNotNull("the error did not come back when its line did",
                runtimeProblemIn(lastAnnounced()));
    }

    /** An edit somewhere else leaves it alone — it moves, which is the whole point of the lane. */
    @Test
    public void anEditElsewhereDoesNotWithdrawIt() {
        runIgnoringFailure(buffer.document().toString());
        buffer.replace(buffer.length(), buffer.length(), "var appended = 1;\n");
        assertNotNull("an unrelated edit withdrew the error",
                runtimeProblemIn(lastAnnounced()));
    }

    @Test
    public void closingTheDocumentForgetsIt() {
        services.close();
        assertNull(AnalysedLanguageServices.attachedTo(FILE));
        // And a run afterwards has nowhere to report and does not mind.
        runIgnoringFailure(buffer.document().toString());
    }
}
