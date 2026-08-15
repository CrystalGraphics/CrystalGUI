package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.Change;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.CodeAction;

import org.junit.Assume;
import org.junit.BeforeClass;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The base every correction's tests are written on — so that adding a quick fix costs three lines.
 *
 * <p>The catalogue this supports is about a hundred corrections long, and a test that is expensive to
 * write is a test that gets skipped. Everything a family test should need is here, and the shape it wants
 * is a table of {@code (before, needle, id, after)}.</p>
 *
 * <h3>One engine for the whole test JVM</h3>
 *
 * <p>Opened lazily and never closed. Not sloppiness: an engine is a classloader over a dozen jars, its
 * lifetime here is the process's, and building one per test — which is what a {@code @Before} does — put
 * a fifth of a second on every method before there were ten of them. {@code @BeforeClass} would still be
 * once per family file, and there will be fifteen of those.</p>
 *
 * <h3>Assertions are on the resulting TEXT and are keyed on the correction ID</h3>
 *
 * <p>Text, because a title tells you an action was offered and nothing about whether its range was right —
 * a fix one character out leaves {@code import ;} behind and satisfies every assertion that only counts
 * actions. And keyed on {@link CodeAction#id()} rather than the title, because a title carries the
 * offending symbol, gets reworded, and would be translated; see that record for the argument in full.</p>
 */
public abstract class FixFixture {

    /** The release level every fixture is analysed at — the floor, so nothing depends on a newer band. */
    private static final int RELEASE_LEVEL = 8;

    /** Version stamps are arbitrary here; the apply-time gate is {@code CodeActionApplyTest}'s subject. */
    private static final long VERSION = 7L;

    /**
     * A stand-in classpath index.
     *
     * <p>The real one is built from jars and is not any correction's subject — what a fix does with a
     * candidate is, and that is testable only if the candidates are fixed.</p>
     */
    protected static final java.util.function.Function<String, List<String>> CANDIDATES = name ->
            "List".equals(name) ? List.of("java.util.List", "java.awt.List") : List.of();

    private static JavaEngine engine;
    private static SourceAnalyzer analyzer;

    // THROUGH JavaEngine, never by constructing EcjSourceAnalyzer here: JDT lives behind the band loader,
    // so a direct `new` compiles and then dies on NoClassDefFoundError for a class the file imports.
    @BeforeClass
    public static void openEngine() throws Exception {
        if (analyzer != null) return;
        EngineBand band = EngineBand.detect();
        EngineSource source = EngineSource.ofPathList(
                System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion()));
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        analyzer = engine.analyzer();
    }

    // ── Asking ──────────────────────────────────────────────────────────────────────────────────

    /** Every action offered over {@code needle}, which must appear in {@code source}. */
    protected static List<CodeAction> actionsIn(String source, String needle) {
        int at = indexOf(source, needle);
        try (SourceAnalyzer.Analysis analysis = analyse(source)) {
            return analysis.codeActionsIn(at, at + needle.length(), CANDIDATES);
        }
    }

    /** The first action with {@code id}, or null. */
    protected static CodeAction offered(String source, String needle, String id) {
        for (CodeAction action : actionsIn(source, needle)) {
            if (id.equals(action.id())) return action;
        }
        return null;
    }

    /**
     * The action with {@code title}, or null — for the case where an id cannot pick one out.
     *
     * <p><b>An id names a correction, not an action</b>, and one correction may answer with several: every
     * candidate of "Import 'x'" is the same correction offering a different qualified name, so they share
     * an id and only the title separates them. That is the one place a title is load-bearing, and it is
     * why this exists beside {@link #offered} rather than instead of it. Prefer the id everywhere it
     * identifies what you mean.</p>
     */
    protected static CodeAction offeredTitled(String source, String needle, String title) {
        for (CodeAction action : actionsIn(source, needle)) {
            if (title.equals(action.title())) return action;
        }
        return null;
    }

    // ── Asserting ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The first thing a new correction should assert, and the one nothing else can stand in for.</b>
     *
     * <p>Exactly one problem severity is configured in {@code EcjSourceAnalyzer}; everything else runs on
     * JDT's defaults, and a large part of the optional set defaults to {@code ignore}. A fix keyed on a
     * problem the compiler was never asked to report is invisible from its own code — it compiles, its
     * unit test passes if the test builds the problem itself, and the popup simply never offers it.</p>
     */
    protected static void assertReported(String source, int problemId) {
        List<Diagnostic> found = diagnosticsOf(source);
        assertTrue("problem " + problemId + " is not reported for this fixture — either the source does "
                        + "not trigger it or its severity is 'ignore' in EcjSourceAnalyzer. Reported: "
                        + codesOf(found),
                occurrences(found, problemId) > 0);
    }

    /** Offers {@code id} over {@code needle}, and applying it yields exactly {@code expected}. */
    protected static void assertFix(String source, String needle, String id, String expected) {
        assertEquals("the fix produced the wrong text", expected, applied(source, require(source, needle, id)));
    }

    /**
     * Offers nothing with {@code id} over {@code needle}.
     *
     * <p>Refusals are contracts as much as fixes are — a correction that stops refusing the case it was
     * written to refuse is a regression no positive test can see.</p>
     */
    protected static void assertNoFix(String source, String needle, String id, String why) {
        assertNull(why, offered(source, needle, id));
    }

    /**
     * <b>Applies the fix, re-analyses the result, and asserts the problem went away without new ones.</b>
     *
     * <p>The oracle, and the assertion that catches what comparing text cannot. A fix is written against
     * one fixture and read by its author, so "the output looks right" is exactly the judgement most likely
     * to be wrong in the same way twice — the compiler is the only reader with no stake in it. This is
     * what would catch a removed import that leaves the file uncompilable, a {@code throws} added to the
     * wrong method, or a cast removed where it was changing overload resolution.</p>
     *
     * <p>Counted rather than located: after an edit the problem's offsets describe a document that no
     * longer exists, so "gone from where it was" would need the ranges mapped through the change to mean
     * anything. Occurrences falling, with errors not rising, says the same thing and cannot go stale.</p>
     */
    protected static void assertResolves(String source, String needle, String id, int problemId) {
        String after = applied(source, require(source, needle, id));
        List<Diagnostic> was = diagnosticsOf(source);
        List<Diagnostic> now = diagnosticsOf(after);

        assertTrue("the fix did not remove problem " + problemId + "; it was reported "
                        + occurrences(was, problemId) + " time(s) before and "
                        + occurrences(now, problemId) + " after:\n" + after,
                occurrences(now, problemId) < occurrences(was, problemId));
        assertTrue("the fix introduced errors — " + errors(was) + " before, " + errors(now)
                        + " after:\n" + codesOf(now) + "\n" + after,
                errors(now) <= errors(was));
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    private static CodeAction require(String source, String needle, String id) {
        CodeAction action = offered(source, needle, id);
        assertNotNull("no action <" + id + "> over <" + needle + ">; offered: " + idsIn(source, needle),
                action);
        assertNotNull("action <" + id + "> carries no edit", action.edit());
        return action;
    }

    /** What {@code action}'s edit does to {@code source}. */
    protected static String applied(String source, CodeAction action) {
        StringBuilder out = new StringBuilder(source);
        List<Change> changes = action.edit().changes();
        for (int i = changes.size() - 1; i >= 0; i--) {          // back to front, so offsets stay valid
            Change change = changes.get(i);
            out.replace(change.from(), change.to(), change.insert());
        }
        return out.toString();
    }

    protected static List<Diagnostic> diagnosticsOf(String source) {
        try (SourceAnalyzer.Analysis analysis = analyse(source)) {
            return analysis.diagnostics();
        }
    }

    private static SourceAnalyzer.Analysis analyse(String source) {
        return analyzer.analyze("Script", source, List.of(), RELEASE_LEVEL, VERSION);
    }

    private static int occurrences(List<Diagnostic> found, int problemId) {
        String code = Integer.toString(problemId);
        int seen = 0;
        for (Diagnostic each : found) {
            if (code.equals(each.code())) seen++;
        }
        return seen;
    }

    private static int errors(List<Diagnostic> found) {
        int seen = 0;
        for (Diagnostic each : found) {
            if (each.severity() == DiagnosticSeverity.ERROR) seen++;
        }
        return seen;
    }

    private static List<String> codesOf(List<Diagnostic> found) {
        List<String> codes = new ArrayList<>();
        for (Diagnostic each : found) codes.add(each.code() + " " + each.message());
        return codes;
    }

    private static List<String> idsIn(String source, String needle) {
        List<String> ids = new ArrayList<>();
        for (CodeAction action : actionsIn(source, needle)) ids.add(action.id());
        return ids;
    }

    private static int indexOf(String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) throw new IllegalArgumentException("no '" + needle + "' in the fixture");
        return at;
    }
}
