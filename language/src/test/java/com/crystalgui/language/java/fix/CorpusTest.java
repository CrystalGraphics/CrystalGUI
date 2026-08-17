package com.crystalgui.language.java.fix;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.fix.JavaQuickFixes;
import com.crystalgui.text.Change;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.CodeAction;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.FixFixture;

/**
 * Every correction over every {@code .java} file in this repository — the fixes against code they were
 * not written for.
 *
 * <h3>Why this exists beside the fixtures</h3>
 *
 * <p>A fixture string is what the author imagined; a real file is what the author did not. This walks
 * {@code core/} and {@code language/}, analyses each file with an <b>empty classpath</b> — which makes
 * nearly every one report unresolved types, unused imports and the rest, a free and rich source of exactly
 * the problems the corrections key on — asks for every action, applies each, and re-parses the result.</p>
 *
 * <h3>What it asserts, and deliberately no finer</h3>
 *
 * <ol>
 *   <li>No correction throws. {@code JavaQuickFixes} swallows a throwing correction so one bug cannot
 *       cost the popup, which would make this blind to the very thing it hunts — so the strict property is
 *       set for the run and a throw becomes a failure naming the correction.</li>
 *   <li>Every edit applies — {@code ChangeSet} refused nothing and every offset was inside the file.</li>
 *   <li><b>A file that parsed still parses.</b> A fix may leave a semantic problem it could not know
 *       about; it may never break the parse. Semantic regressions are counted and printed for a human to
 *       read, not asserted, because "did you mean" choosing an out-of-scope local is a known and accepted
 *       imprecision (see {@code DidYouMeanCorrections}) and asserting on it would fail the suite for a
 *       judgement call.</li>
 * </ol>
 *
 * <p>Planned as opt-in on the assumption that it would be minutes; measured at twelve seconds for 652
 * files and 942 applied actions, so it runs with the suite. {@code -PnoCorpus} skips it for a quick local
 * loop.</p>
 */
public class CorpusTest extends FixFixture {

    @Test
    public void everyCorrectionSurvivesEveryFileInTheRepository() throws IOException {
        Assume.assumeTrue("corpus pass skipped with -PnoCorpus", Boolean.getBoolean("cgui.test.corpus"));
        List<Path> files = corpus();
        assertTrue("no corpus files found", !files.isEmpty());

        System.setProperty(JavaQuickFixes.STRICT_PROPERTY, "true");
        try {
            run(files);
        } finally {
            System.clearProperty(JavaQuickFixes.STRICT_PROPERTY);
        }
    }

    private static void run(List<Path> files) throws IOException {
        int level = newestLevel();
        int problems = 0, actions = 0, applied = 0, semanticRegressions = 0;
        List<String> failures = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        List<String> engineErrors = new ArrayList<>();
        long started = System.nanoTime();

        for (Path file : files) {
            String className = file.getFileName().toString().replace(".java", "");
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<CodeAction> offered;
            boolean parsedBefore;
            int errorsBefore;
            // ECJ ITSELF CAN FAIL on a real file with an empty classpath -- an AssertionError out of its
            // binding layer on a record whose component types are missing was the first thing this pass
            // found. That is the engine's defect, not a correction's, so it is recorded and the file
            // skipped rather than counted against the fixes. It is also worth knowing about in its own
            // right: production does not guard createAST against an Error either.
            try (SourceAnalyzer.Analysis analysis = analyse(className, source, level)) {
                problems += analysis.diagnostics().size();
                parsedBefore = analysis.optionalProblemsAnalysed();
                errorsBefore = errors(analysis.diagnostics());
                try {
                    offered = analysis.codeActionsIn(0, source.length(), HOST);
                } catch (RuntimeException thrown) {
                    failures.add(file + ": " + rootCause(thrown));
                    continue;
                }
            } catch (Throwable engine) {
                engineErrors.add(file.getFileName() + ": " + engine);
                continue;
            }
            actions += offered.size();

            for (CodeAction action : offered) {
                if (action.edit() == null) continue;
                String after;
                try {
                    after = applyStrictly(source, action);
                } catch (RuntimeException broken) {
                    failures.add(file + " <" + action.id() + " / " + action.title() + ">: " + broken);
                    continue;
                }
                applied++;
                try (SourceAnalyzer.Analysis reparsed = analyse(className, after, level)) {
                    if (parsedBefore && !reparsed.optionalProblemsAnalysed()) {
                        failures.add(file + " <" + action.id() + " / " + action.title()
                                + ">: the file parsed before the fix and does not after");
                    } else if (errors(reparsed.diagnostics()) > errorsBefore) {
                        semanticRegressions++;
                        if (regressions.size() < 40) {
                            regressions.add(file.getFileName() + " <" + action.title() + ">: "
                                    + errorsBefore + " -> " + errors(reparsed.diagnostics()) + " errors");
                        }
                    }
                } catch (Throwable engine) {
                    engineErrors.add(file.getFileName() + " after <" + action.title() + ">: " + engine);
                }
            }
        }

        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.println("corpus: " + files.size() + " files, " + problems + " problems, " + actions
                + " actions offered, " + applied + " applied and re-parsed, " + semanticRegressions
                + " with more errors after than before, " + engineErrors.size()
                + " engine crashes, in " + seconds + "s");
        for (String each : regressions) System.out.println("  regression: " + each);
        for (String each : engineErrors) System.out.println("  engine: " + each);
        // PRINTED AS WELL AS ASSERTED. The assertion message reaches the XML report and nothing else, and
        // the one thing this test exists to tell you is which action broke which file.
        for (String each : failures) System.out.println("  FAILURE: " + each);
        assertTrue("corpus failures:\n  " + String.join("\n  ", failures), failures.isEmpty());
    }

    /** Applies through the same offsets the editor would, refusing anything outside the document. */
    private static String applyStrictly(String source, CodeAction action) {
        StringBuilder out = new StringBuilder(source);
        List<Change> changes = action.edit().changes();
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i);
            if (change.from() < 0 || change.to() > source.length()) {
                throw new IllegalStateException("change " + change.from() + ".." + change.to()
                        + " outside a document of " + source.length());
            }
            out.replace(change.from(), change.to(), change.insert());
        }
        return out.toString();
    }

    private static int errors(List<Diagnostic> found) {
        int seen = 0;
        for (Diagnostic each : found) {
            if (each.severity() == DiagnosticSeverity.ERROR) seen++;
        }
        return seen;
    }

    private static String rootCause(Throwable thrown) {
        Throwable at = thrown;
        while (at.getCause() != null) at = at.getCause();
        StackTraceElement[] trace = at.getStackTrace();
        return thrown.getMessage() + " <- " + at + (trace.length > 0 ? " at " + trace[0] : "");
    }

    /** Every {@code .java} under the two modules' main sources, found from the repository root. */
    private static List<Path> corpus() throws IOException {
        String root = System.getProperty("cgui.test.repoRoot");
        if (root == null) return List.of();
        List<Path> files = new ArrayList<>();
        for (String module : new String[] {"core/src/main/java", "language/src/main/java"}) {
            Path base = Paths.get(root, module);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
            }
        }
        return files;
    }
}
