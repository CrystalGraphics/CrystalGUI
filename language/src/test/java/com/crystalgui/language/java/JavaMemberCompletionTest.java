package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What {@code System.} actually offers — the list a person sees, asserted against a real compiler.
 *
 * <p>Written because the harness capture showed <b>no fields at all</b>: IntelliJ opens that list with
 * {@code out}, {@code err} and {@code in}, and ours had only methods. Static methods were present, so the
 * engine and the member walk were plainly working — which is the kind of partial failure a test asserting
 * "the list is non-empty" passes straight through.</p>
 *
 * <p>{@code System} rather than a fixture class of our own, deliberately: it is a <b>binary</b> type off the
 * classpath with no source attached, which is the case that behaves differently from a type in the file
 * being edited and the one every real completion is against.</p>
 */
public class JavaMemberCompletionTest {

    private JavaEngine engine;

    @Before
    public void openEngine() throws Exception {
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

    /** The source a person is looking at when the popup opens: a dot, and nothing after it yet. */
    private static final String AFTER_THE_DOT = ""
            + "class Demo {\n"
            + "    void run() {\n"
            + "        System.\n"
            + "    }\n"
            + "}\n";

    private List<CompletionItem> completeAfterTheDot(String source) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("System.") + "System.".length();
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            return answered.get().items();
        } finally {
            services.close();
        }
    }

    private static List<String> labelsOf(List<CompletionItem> items) {
        List<String> labels = new ArrayList<>();
        for (CompletionItem item : items) labels.add(item.label());
        return labels;
    }

    private static CompletionItem named(List<CompletionItem> items, String label) {
        for (CompletionItem item : items) {
            if (item.label().equals(label)) return item;
        }
        return null;
    }

    // ── The defect ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void systemOffersItsStaticFieldsAndNotOnlyItsMethods() {
        List<CompletionItem> items = completeAfterTheDot(AFTER_THE_DOT);
        List<String> labels = labelsOf(items);

        assertTrue("the list should not be empty at all: " + labels, !items.isEmpty());
        // Sanity: the half that already worked. If this fails the test is wrong, not the code.
        assertTrue("static methods were already present and must stay: " + labels,
                labels.contains("currentTimeMillis()"));

        assertTrue("System.out is missing -- IntelliJ opens this very list with it: " + labels,
                labels.contains("out"));
        assertTrue("System.err is missing: " + labels, labels.contains("err"));
        assertTrue("System.in is missing: " + labels, labels.contains("in"));
    }

    @Test
    public void aFieldIsReportedAsAFieldSoTheIconAndTheRankingAreRight() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull("System.out is missing", out);
        assertEquals(SymbolKind.FIELD, out.kind());
        assertEquals("the detail column shows the type", "PrintStream", out.detail());
    }

    /**
     * The declaring type's own members are reachable too.
     *
     * <p>A negative control for the fix: if {@code System} started answering because the walk began
     * returning <em>everything</em>, a type whose fields are instance fields rather than statics would
     * expose it.</p>
     */
    @Test
    public void anInstanceReceiverOffersItsInstanceMembers() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        String s = \"x\";\n"
                + "        s.\n"
                + "    }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("s.\n") + 2;
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            List<String> labels = labelsOf(answered.get().items());
            assertTrue("String's instance methods should be offered: " + labels,
                    labels.contains("substring(int)"));
        } finally {
            services.close();
        }
    }

    // ── Phase 1: signatures and overloads ───────────────────────────────────────────────────────

    @Test
    public void aMethodShowsItsSignatureAndStillFiltersOnItsBareName() {
        CompletionItem exit = named(completeAfterTheDot(AFTER_THE_DOT), "exit(int)");
        assertNotNull("the label must carry the parameter list", exit);
        assertEquals("typing the bare name must still match it", "exit", exit.filterKey());
        assertEquals("and accepting must insert the bare name", "exit", exit.textToInsert());
    }

    /**
     * Two overloads must be two DISTINGUISHABLE rows.
     *
     * <p>The whole point of the label/filter split. Before this they were two rows both reading
     * {@code getProperty}, with no way to tell which one Enter would take.</p>
     */
    @Test
    public void overloadsAreDistinguishable() {
        List<String> labels = labelsOf(completeAfterTheDot(AFTER_THE_DOT));
        assertTrue("one-argument getProperty is missing: " + labels, labels.contains("getProperty(String)"));
        assertTrue("two-argument getProperty is missing: " + labels,
                labels.contains("getProperty(String, String)"));
    }

    @Test
    public void aStaticMemberIsReportedAsStaticSoTheIconCanSaySo() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull(out);
        assertTrue("System.out is static and the icon's second axis reads this",
                out.is(com.crystalgui.text.lang.SymbolModifier.STATIC));
    }

    @Test
    public void aFieldCarriesNoParameterListAtAll() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull(out);
        assertEquals("a field labelled `out()` would be a lie about what it is", "out", out.label());
    }
}
