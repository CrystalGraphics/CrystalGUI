package com.crystalgui.language.java;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.text.TextBuffer;
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
}
