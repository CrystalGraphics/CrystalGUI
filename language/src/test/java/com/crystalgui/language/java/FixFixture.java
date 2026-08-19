package com.crystalgui.language.java;

import com.crystalgui.text.SimilarNames;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.CodeActionContext;
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
import com.crystalgui.language.engine.bridge.Analysis;

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
     * A stand-in host.
     *
     * <p>The real classpath index is built from jars and is not any correction's subject — what a fix
     * <em>does</em> with a candidate is, and that is testable only if the candidates are fixed. Two for
     * {@code List} on purpose: one candidate and several are different cases, and the several-candidate
     * one is what the "More actions…" list exists for.</p>
     */
    protected static final CodeActionContext HOST = new CodeActionContext() {
        @Override public List<String> importCandidates(String simpleName) {
            return "List".equals(simpleName) ? List.of("java.util.List", "java.awt.List") : List.of();
        }

        /**
         * A stand-in for the index's distance walk. The ranking itself is {@code SimilarNames}' and is
         * tested there; what a correction does with a near miss -- rename, import, disambiguate -- is
         * what these fixtures are about, and that needs the near misses fixed. Deliberately includes a
         * {@code java.lang} type (no import needed) and a name two packages spell (must disambiguate).
         */
        @Override public List<String> similarTypeNames(String simpleName) {
            switch (simpleName) {
                case "Strin": case "Strng": case "Sting":
                    return List.of("java.lang.String");
                case "Lst": case "Lsit":
                    return List.of("java.util.List", "java.awt.List");
                default:
                    return List.of();
            }
        }
    };

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
        return actionsOver("Script", source, at, at + needle.length());
    }

    /**
     * Every action offered over {@code [from, to)} in a file called {@code className}.
     *
     * <p>The name matters for a fixture read off disk: a {@code public class Unused} analysed as
     * {@code Script.java} is a {@code PublicClassMustMatchFileName} error, and that one error poisons
     * resolution for the whole unit — so the file would report nothing the fixture was written to show.</p>
     */
    protected static List<CodeAction> actionsOver(String className, String source, int from, int to) {
        try (SourceAnalyzer.Analysis analysis = analyse(className, source)) {
            return analysis.codeActionsIn(from, to, HOST);
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

    /**
     * <b>Applying it leaves the file no more broken than it was</b> — the oracle for an intention.
     *
     * <p>{@link #assertResolves} is the same idea for a fix and cannot stand in here: an intention answers
     * no problem, so there is nothing for its error count to fall <em>from</em>. What is left is the half
     * that matters most — a conversion offered on code that compiles must produce code that compiles.</p>
     *
     * <p>Both of the errors this would have caught were shapes nobody would have written a fixture for, and
     * both were found by a probe: two branches of a chain each declaring {@code int a} (legal as two
     * {@code if} bodies, a duplicate as one switch scope), and a chain on a {@code long} (a comparison the
     * chain does fine and {@code switch} cannot take at all). Neither changes the text in a way an
     * expected-output test would have looked twice at.</p>
     */
    protected static void assertSameSemantics(String source, String needle, String id) {
        String after = applied(source, require(source, needle, id));
        int was = errors(diagnosticsOf(source));
        List<Diagnostic> now = diagnosticsOf(after);
        assertTrue("applying <" + id + "> introduced errors — " + was + " before, " + errors(now)
                        + " after:\n" + codesOf(now) + "\n" + after,
                errors(now) <= was);
    }

    /**
     * <b>The fix is offered over the range the diagnostic marks</b> — for any family whose mark is not
     * ECJ's own.
     *
     * <p>The invariant rather than a coordinate: whatever {@code ProblemSpans} decides to underline, the
     * caret sitting on it must get the answer. It is stated here because it is not a property of any one
     * correction — it is the seam between where a problem is <em>drawn</em> and where it is <em>routed</em>,
     * and those were two independent readings until a cast fix went unreachable from its own squiggle.</p>
     *
     * <p>Asking near the mark is a different question and every family's other tests ask it: a needle
     * spanning a whole call or a whole signature covers ECJ's range too, which is why seventeen cast tests
     * passed over exactly this.</p>
     */
    protected static void assertOfferedWhereMarked(String source, int problemId, String id) {
        int[] span = markedSpan(source, problemId);
        for (CodeAction action : actionsOver("Script", source, span[0], span[1])) {
            if (id.equals(action.id())) return;
        }
        throw new AssertionError(id + " is not offered over the range problem " + problemId
                + " marks — '" + source.substring(span[0], span[1]) + "'");
    }

    /** A problem's reported range, in the source's own offsets. */
    protected static int[] markedSpan(String source, int problemId) {
        String code = Integer.toString(problemId);
        for (Diagnostic problem : diagnosticsOf(source)) {
            if (!code.equals(problem.code())) continue;
            return new int[] {offsetOf(source, problem.start().row(), problem.start().column()),
                    offsetOf(source, problem.end().row(), problem.end().column())};
        }
        throw new AssertionError("problem " + problemId + " is not reported");
    }

    /** The text a problem's reported range actually covers — what the reader sees underlined. */
    protected static String marked(String source, int problemId) {
        int[] span = markedSpan(source, problemId);
        return source.substring(span[0], span[1]);
    }

    private static int offsetOf(String source, int row, int column) {
        int at = 0;
        for (int line = 0; line < row; line++) at = source.indexOf('\n', at) + 1;
        return at + column;
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    protected static CodeAction require(String source, String needle, String id) {
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
        return analyse("Script", source);
    }

    private static SourceAnalyzer.Analysis analyse(String className, String source) {
        return analyse(className, source, RELEASE_LEVEL);
    }

    /**
     * An analysis at an explicit language level — for the corpus, which is this repository's own sources
     * and needs the newest level the engine offers rather than the floor the fixtures are pinned to.
     * The caller closes it.
     */
    protected static SourceAnalyzer.Analysis analyse(String className, String source, int level) {
        return analyse(className, source, List.of(), level);
    }

    /**
     * The same, with a classpath — for the coverage probe, which asks what a file reports when its types
     * actually resolve as well as when they do not. The two answers are different populations of problem,
     * and only measuring both says which of them a fix would be for.
     */
    protected static SourceAnalyzer.Analysis analyse(String className, String source,
                                                     List<String> classpath, int level) {
        return analyzer.analyze(className, source, classpath, level, VERSION);
    }

    /** The newest language level this band's engine parses. */
    protected static int newestLevel() {
        return engine.releaseLevel();
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
