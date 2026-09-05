package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Change;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.SemanticTokenProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The engine, attached to a live document and driven by the scheduler.
 *
 * <h3>Deterministic, because the scheduler was built to be</h3>
 *
 * <p>A same-thread executor and a manual clock, which is the arrangement {@code JobScheduler} exists to
 * make possible (§7.2). So "typing debounces to one analysis" is an integer assertion rather than a
 * sleep — and integers are not flaky on somebody else's machine.</p>
 */
public class JavaLanguageServicesTest {

    private JavaEngine engine;
    private JobScheduler scheduler;
    private final AtomicLong clock = new AtomicLong();

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        scheduler = new JobScheduler(Runnable::run, clock::get, 1);
    }

    @After
    public void closeEngine() throws IOException {
        if (scheduler != null) scheduler.dispose();
        if (engine != null) engine.close();
    }

    private JavaLanguageServices servicesFor(TextBuffer buffer) {
        return new JavaLanguageServices(buffer, engine, scheduler, "Script", List.of());
    }

    /** Advances past the debounce window and drains, which is what a frame does. */
    private void settle() {
        clock.addAndGet(1_000);
        for (int i = 0; i < 4; i++) scheduler.drain();
    }

    @Test
    public void aDocumentIsAnalysedWhenTheServicesAreCreated() {
        // Not on the first keystroke. A file that is opened and never typed in is the state a document
        // spends most of its life in, and it would otherwise have no colours and no problems at all.
        TextBuffer buffer = new TextBuffer("public class Script { int f = 1; }\n");
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            assertFalse("a listener attached after the first analysis heard nothing",
                    announced.isEmpty());
            assertTrue("well-formed source reported problems: " + announced,
                    announced.get(0).isEmpty());
            assertFalse("no semantic tokens for a document with a field",
                    services.semanticTokens().tokensIn(0, buffer.length()).isEmpty());
        } finally {
            services.close();
        }
    }

    @Test
    public void anEditProducesNewDiagnosticsThroughTheScheduler() {
        TextBuffer buffer = new TextBuffer("public class Script { int run() { return 1; } }\n");
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            announced.clear();

            // Break it: replace the literal with a call to something that does not exist.
            int at = buffer.document().toString().indexOf("return 1") + 7;
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(at, at + 1, "nope()")));
            settle();

            assertFalse("the edit produced no announcement", announced.isEmpty());
            List<Diagnostic> latest = announced.get(announced.size() - 1);
            assertFalse("a call to a missing method reported nothing", latest.isEmpty());
            assertTrue(latest.get(0).message(), latest.get(0).message().contains("nope"));
        } finally {
            services.close();
        }
    }

    @Test
    public void aBurstOfTypingCollapsesToOneAnalysis() {
        // The reason the job is debounced AND keyed. Without the key each keystroke would queue its own
        // job; without the debounce each would start one. Both, and a run of typing costs one analysis.
        TextBuffer buffer = new TextBuffer("public class Script { }\n");
        JavaLanguageServices services = servicesFor(buffer);
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            announced.clear();

            for (int i = 0; i < 20; i++) {
                int end = buffer.length() - 1;
                buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                        new com.crystalgui.text.Change(end, end, " ")));
                clock.addAndGet(10);       // inside the window, so the deadline keeps moving out
                scheduler.drain();
            }
            settle();

            assertEquals("twenty keystrokes should produce one analysis, not twenty",
                    1, announced.size());
        } finally {
            services.close();
        }
    }

    @Test
    public void theEditorIsToldToRequeryWhenAnAnalysisLands() {
        // Nothing about the document changed when the compile finished, so no existing signal would
        // prompt a re-query -- the colours would sit one analysis behind until an unrelated repaint.
        TextBuffer buffer = new TextBuffer("public class Script { int f = 1; }\n");
        JavaLanguageServices services = servicesFor(buffer);
        try {
            AtomicReference<int[]> invalidated = new AtomicReference<>();
            services.semanticTokens().setInvalidationListener(
                    (from, to) -> invalidated.set(new int[]{from, to}));

            int end = buffer.length() - 1;
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(end, end, " ")));
            settle();

            assertNotNull("the landing analysis did not invalidate anything", invalidated.get());
            assertEquals(0, invalidated.get()[0]);
            assertEquals("the claim must be EVERYTHING -- a compile can recolour any line",
                    SyntaxTokenizer.InvalidationListener.EVERYTHING, invalidated.get()[1]);
        } finally {
            services.close();
        }
    }

    @Test
    public void semanticTokensAreBoundedToTheRangeAskedFor() {
        String text = "public class Script {\n"
                + "    int alpha = 1;\n"
                + "    int beta = 2;\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(text);
        JavaLanguageServices services = servicesFor(buffer);
        try {
            SemanticTokenProvider provider = services.semanticTokens();
            int betaAt = text.indexOf("beta");
            List<SyntaxToken> justBeta = provider.tokensIn(betaAt, betaAt + 4);

            assertEquals(1, justBeta.size());
            assertEquals("beta", text.substring(justBeta.get(0).start(), justBeta.get(0).end()));
        } finally {
            services.close();
        }
    }

    @Test
    public void resolutionAnswersFromTheHeldAnalysisAndCarriesItsVersion() {
        String text = "public class Script { String label = \"x\"; int n() { return label.length(); } }\n";
        TextBuffer buffer = new TextBuffer(text);
        JavaLanguageServices services = servicesFor(buffer);
        try {
            AtomicReference<Versioned<SymbolInfo>> answer = new AtomicReference<>();
            services.resolver().resolveAt(text.indexOf("label.length") + 2, answer::set);
            // A RESOLVE IS SCHEDULED NOW, because answering can mean parsing an attached source file --
            // measured at 159ms for one class, which is not a thing to do on a frame. The Resolver
            // contract always said the callback may fire later; this fixture simply never had to wait.
            settle();

            assertNotNull("resolveAt never called back", answer.get());
            SymbolInfo symbol = answer.get().value();
            assertNotNull(symbol);
            assertEquals("label", symbol.name());
            assertEquals(SymbolKind.FIELD, symbol.kind());
            assertEquals("java.lang.String", symbol.type().qualifiedName());
            assertTrue("the answer does not describe the current document",
                    answer.get().isFresh(buffer.version()));
        } finally {
            services.close();
        }
    }

    @Test
    public void aStaleAnswerIsMarkedStaleRatherThanWithheld() {
        // The keep-per-line policy, visible. After an edit and before the next analysis lands, the
        // service still answers -- from the previous snapshot -- and says which version that was. A
        // consumer that wants freshness checks; one that wants continuity does not.
        String text = "public class Script { String label = \"x\"; }\n";
        TextBuffer buffer = new TextBuffer(text);
        JavaLanguageServices services = servicesFor(buffer);
        try {
            long before = buffer.version();
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(0, 0, "\n")));
            // NO settle() -- the analysis is queued and has not run.

            AtomicReference<Versioned<SymbolInfo>> answer = new AtomicReference<>();
            services.resolver().resolveAt(text.indexOf("label") + 2, answer::set);
            // Drains the RESOLVE without advancing the clock, so the debounced analysis above stays
            // queued -- which is the whole point of this test. @see #settle
            for (int i = 0; i < 4; i++) scheduler.drain();

            assertNotNull(answer.get());
            assertEquals("the held analysis describes the version before the edit",
                    before, answer.get().version());
            assertFalse("and it must say so", answer.get().isFresh(buffer.version()));
        } finally {
            services.close();
        }
    }

    @Test
    public void closingDropsTheSubscriptionAndStopsAnnouncing() {
        TextBuffer buffer = new TextBuffer("public class Script { }\n");
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        Connection connection = services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
        announced.clear();

        services.close();
        // The caller's handle stays valid and disconnecting it again is a no-op -- a caller tearing
        // down in its own order must not have to know whether the service got there first.
        connection.disconnect();

        int end = buffer.length() - 1;
        buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                new com.crystalgui.text.Change(end, end, " ")));
        settle();

        assertTrue("a closed service is still listening to its document", announced.isEmpty());
    }

    @Test
    public void aServiceWithNoSchedulerWorksSynchronously() {
        // The harness, a headless caller, and every test that does not want to think about draining.
        TextBuffer buffer = new TextBuffer("public class Script { int run() { return bad(); } }\n");
        JavaLanguageServices services =
                new JavaLanguageServices(buffer, engine, null, "Script", List.of());
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            assertFalse(announced.isEmpty());
            assertFalse("a broken document reported nothing", announced.get(0).isEmpty());
        } finally {
            services.close();
        }
    }

    // ── Warnings survive a syntax error ─────────────────────────────────────────────────────────

    /** A file whose only fault is an unused import — one warning, no errors. */
    private static final String WITH_WARNING = ""
            + "import java.util.List;\n"
            + "public class Script {\n"
            + "    void run() {\n"
            + "        int x = 1;\n"
            + "        x = x + 1;\n"
            + "    }\n"
            + "}\n";

    private static List<Diagnostic> lastOf(List<List<Diagnostic>> announced) {
        return announced.isEmpty() ? List.of() : announced.get(announced.size() - 1);
    }

    private static long errorsIn(List<Diagnostic> found) {
        return found.stream().filter(d -> d.severity() == com.crystalgui.text.diagnostic
                .DiagnosticSeverity.ERROR).count();
    }

    private static long warningsIn(List<Diagnostic> found) {
        return found.stream().filter(d -> d.severity() != com.crystalgui.text.diagnostic
                .DiagnosticSeverity.ERROR).count();
    }

    /** Types {@code text} at the end of the buffer. */
    private static void append(TextBuffer buffer, String text) {
        int at = buffer.length();
        buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                new com.crystalgui.text.Change(at, at, text)));
    }

    /**
     * <b>A syntax error no longer swallows the warnings.</b>
     *
     * <p>ECJ skips every optional problem for a unit that does not parse, so the panel appeared to show
     * errors <em>or</em> warnings and never both — fix the one syntax error and four warnings "appeared"
     * that had been there all along. They are held from the last analysis whose optional pass actually
     * ran and re-announced beside the errors.</p>
     */
    @Test
    public void warningsSurviveASyntaxError() {
        TextBuffer buffer = new TextBuffer(WITH_WARNING);
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            assertEquals("the fixture is meant to warn about its unused import: " + lastOf(announced),
                    1, warningsIn(lastOf(announced)));
            assertEquals(0, errorsIn(lastOf(announced)));

            // Break the parse, exactly as typing a trailing dot does.
            int at = buffer.document().toString().indexOf("x = x + 1;") + "x = x + 1;".length();
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(at, at, "\n        x.")));
            settle();

            List<Diagnostic> now = lastOf(announced);
            assertTrue("the broken file should still report its syntax error: " + now,
                    errorsIn(now) > 0);
            assertEquals("the warning was dropped the moment the file stopped parsing: " + now,
                    1, warningsIn(now));
        } finally {
            services.close();
        }
    }

    /**
     * <b>A retained warning says where its text is NOW.</b>
     *
     * <p>The reason they are held in a decoration lane rather than in a plain list: the file goes on being
     * edited while it is broken, so a warning re-announced at the row it was first reported on would
     * point at whatever has since moved there — and the Problems row navigates to it.</p>
     */
    @Test
    public void aRetainedWarningFollowsTheEditsMadeWhileTheFileIsBroken() {
        TextBuffer buffer = new TextBuffer(WITH_WARNING);
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            int reportedRow = lastOf(announced).stream()
                    .filter(d -> d.severity() != com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR)
                    .findFirst().orElseThrow().start().row();

            // A blank line at the very top, then break the parse. The import moves down by one.
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(0, 0, "\n")));
            append(buffer, "class Broken {\n");
            settle();

            List<Diagnostic> now = lastOf(announced);
            assertTrue("the file should be broken: " + now, errorsIn(now) > 0);
            Diagnostic retained = now.stream()
                    .filter(d -> d.severity() != com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR)
                    .findFirst().orElseThrow();
            assertEquals("the retained warning did not follow the line inserted above it",
                    reportedRow + 1, retained.start().row());
        } finally {
            services.close();
        }
    }

    /**
     * <b>Deleting the warned-about text drops the warning, without waiting for the file to parse.</b>
     *
     * <p>This is what keeps retention honest rather than merely persistent. The range holding the warning
     * collapses when its text goes, and a range that collapsed <em>because of an edit</em> is not the same
     * thing as one that was born empty — which is the distinction {@code TrackedRange.collapsedByEdit}
     * exists to make.</p>
     */
    @Test
    public void fixingTheWarningWhileBrokenRemovesIt() {
        TextBuffer buffer = new TextBuffer(WITH_WARNING);
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            assertEquals(1, warningsIn(lastOf(announced)));

            // Break the parse first, so the retained set is what is being asked about.
            append(buffer, "class Broken {\n");
            settle();
            assertEquals("precondition: the warning is being retained", 1, warningsIn(lastOf(announced)));

            // Now delete the unused import while the file is still broken.
            String text = buffer.document().toString();
            int from = text.indexOf("import java.util.List;\n");
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(), new com.crystalgui.text.Change(
                    from, from + "import java.util.List;\n".length(), "")));
            settle();

            List<Diagnostic> now = lastOf(announced);
            assertTrue("the file should still be broken: " + now, errorsIn(now) > 0);
            assertEquals("the warning outlived the import it was about: " + now, 0, warningsIn(now));
        } finally {
            services.close();
        }
    }

    /**
     * <b>Attaching a second listener does not disturb the retained warnings.</b>
     *
     * <p>{@code announcement} has a side effect — it replaces the retained lane — and its inputs are
     * row/column positions that only mean anything against the document the analysis saw. Recomputing it
     * when a listener attaches would map those positions against a buffer that has since been edited and
     * overwrite correctly-tracked ranges with wrong offsets, so the list is computed once per analysis
     * and replayed. Silent if broken: the second listener gets a plausible list that points at the wrong
     * text, and the first listener never sees anything change.</p>
     */
    @Test
    public void aLateListenerDoesNotCorruptTheRetainedPositions() {
        TextBuffer buffer = new TextBuffer(WITH_WARNING);
        List<List<Diagnostic>> first = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> first.add(v.orElse(List.of())));
            int reportedRow = lastOf(first).stream()
                    .filter(d -> d.severity() != com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR)
                    .findFirst().orElseThrow().start().row();

            // A line at the very top, and NO settle: the lane tracks the edit, so the warning has moved,
            // while the held analysis still describes the document before it. This is the window in which
            // recomputing would map the analysis's original row/column against the newer buffer.
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(),
                    new com.crystalgui.text.Change(0, 0, "\n")));
            services.onDiagnostics(v -> { });

            // Now break the parse, so the retained warning is what gets re-announced.
            append(buffer, "class Broken {\n");
            settle();

            Diagnostic retained = lastOf(first).stream()
                    .filter(d -> d.severity() != com.crystalgui.text.diagnostic.DiagnosticSeverity.ERROR)
                    .findFirst().orElseThrow();
            assertEquals("attaching a listener re-derived the retained range from positions describing an"
                            + " older document, so the warning points a line above its own text",
                    reportedRow + 1, retained.start().row());
        } finally {
            services.close();
        }
    }

    /**
     * <b>A file that parses has the last word, even when it has errors and no warnings.</b>
     *
     * <p>The case that makes {@code optionalProblemsAnalysed} worth asking the compiler rather than
     * inferring. "Errors and no warnings" is the shape of a suppressed analysis <em>and</em> the shape of
     * a file that resolves badly but parses fine — and treating the second as the first resurrects
     * warnings the user has already fixed, which is worse than the bug this feature fixes.</p>
     */
    @Test
    public void aSemanticErrorDoesNotResurrectFixedWarnings() {
        TextBuffer buffer = new TextBuffer(WITH_WARNING);
        List<List<Diagnostic>> announced = new ArrayList<>();
        JavaLanguageServices services = servicesFor(buffer);
        try {
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            assertEquals("precondition: one warning to forget", 1, warningsIn(lastOf(announced)));

            // Remove the unused import AND introduce a name that does not resolve. The file still parses,
            // so ECJ's answer -- one error, no warnings -- is complete.
            buffer.edit(com.crystalgui.text.ChangeSet.of(buffer.length(), new com.crystalgui.text.Change(
                    0, buffer.length(),
                    "public class Script {\n"
                            + "    void run() {\n"
                            + "        int x = nope();\n"
                            + "        x = x + 1;\n"
                            + "    }\n"
                            + "}\n")));
            settle();

            List<Diagnostic> now = lastOf(announced);
            assertTrue("the fixture is meant to have an unresolved name: " + now, errorsIn(now) > 0);
            assertEquals("a parsing file's answer is complete — retention must not add to it: " + now,
                    0, warningsIn(now));
        } finally {
            services.close();
        }
    }

    /**
     * <b>A file that stops PARSING still reports its error.</b>
     *
     * <p>The sibling above breaks the source semantically — a call to a method that does not exist —
     * which still parses, so the analysis produces both its errors and its optional warnings. This one
     * breaks it so it cannot parse at all, which is a different branch: ECJ marks such a unit
     * {@code ignoreFurtherInvestigation} and skips the passes that produce unused-import and unused-local
     * warnings, so the services fall back to the retained lane for those.</p>
     *
     * <p>Reported from the desktop scene as <em>0 errors, 2 warnings</em> on a file that plainly did not
     * compile, with both warnings describing a version of the text that no longer existed. That is what
     * this branch looks like when the analysis's own errors do not survive the merge.</p>
     */
    private static final String N = System.lineSeparator();

    @Test
    public void sourceThatCannotParseStillAnnouncesItsError() {
        // ONE LINE, like its siblings: what matters is that the edit below leaves a declaration the
        // parser cannot finish, not how many lines the file has.
        TextBuffer buffer = new TextBuffer("public class Script { void f() { int a = 1; } }" + N);
        JavaLanguageServices services = servicesFor(buffer);
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            settle();
            announced.clear();

            // A COMMENT THAT SWALLOWS THE REST OF THE DECLARATION, which is exactly how it was reported:
            // `CgTextRenderer REN//DER = ...` leaves a declaration with no terminator and no initialiser.
            int at = buffer.document().toString().indexOf("int a = 1;") + 5;
            buffer.edit(ChangeSet.of(buffer.length(), new Change(at, at + 1, "//")));
            settle();

            assertFalse("the edit produced no announcement at all", announced.isEmpty());
            List<Diagnostic> latest = announced.get(announced.size() - 1);
            assertTrue("a file that cannot parse announced no error -- announced: " + latest,
                    latest.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
        } finally {
            services.close();
        }
    }

    /**
     * <b>An environment change must not be able to starve the analysis.</b>
     *
     * <p>The scheduler's debounce restarts on every submit, so a trigger that fires faster than the
     * window is a job that never runs. The workbench announces "the world outside this document moved"
     * to every open editor, and a feedback loop had it announcing every frame: measured in the desktop
     * scene at <b>937 schedules and zero completions</b>, so the Problems panel kept the analysis from
     * the moment the file was opened and nothing typed afterwards was ever looked at.</p>
     *
     * <p>The loop is fixed at its source; this is the guard that makes the starvation unreachable
     * whatever announces. Safe because an environment change does not alter the source: the job already
     * queued reads the new environment when it runs. An <em>edit</em> still always re-submits, which is
     * the case the sibling above covers.</p>
     */
    @Test
    public void repeatedEnvironmentChangesStillLetAnAnalysisLand() {
        TextBuffer buffer = new TextBuffer("public class Script { int run() { return nope(); } }" + N);
        JavaLanguageServices services = servicesFor(buffer);
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(v -> announced.add(v.orElse(List.of())));
            settle();
            announced.clear();

            // FASTER THAN THE WINDOW, which is the whole point: each step advances the clock by less
            // than DEBOUNCE_MILLIS, so a trigger that re-submits every time can never come due.
            for (int i = 0; i < 20; i++) {
                services.environmentChanged();
                clock.addAndGet(100);
                scheduler.drain();
            }

            assertFalse("twenty environment changes produced no analysis at all -- the debounce was "
                    + "being reset faster than it could elapse", announced.isEmpty());
        } finally {
            services.close();
        }
    }
}
