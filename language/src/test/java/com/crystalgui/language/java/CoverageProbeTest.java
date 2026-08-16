package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <b>What the engine reports and cannot answer</b> — the catalogue's next entries, measured rather than
 * ranked by hand.
 *
 * <p>Asserts nothing. It walks every {@code .java} in the repository, histograms the problems by id, and
 * for each id asks the engine for actions <em>at that problem's own range</em> — which is the question a
 * user's caret asks. An id with a large count and no offer is a gap; an id with a large count and an offer
 * is covered. Reading the two columns together is the whole point, because ranking candidate fixes by
 * imagination is how a catalogue ends up with entries nobody hits and gaps nobody noticed.</p>
 *
 * <p>Run with {@code -Dcgui.test.coverage=true}; off by default, because it is an instrument rather than a
 * check and its output is only useful to a person.</p>
 */
public class CoverageProbeTest extends FixFixture {

    private static final class Seen {
        int count;
        int files;
        int offered;
        String severity = "";
        String example = "";
        String action = "";
    }

    @Test
    public void whatIsReportedAndWhatIsAnswered() throws IOException {
        Assume.assumeTrue("coverage probe is opt-in", Boolean.getBoolean("cgui.test.coverage"));
        List<Path> files = corpus();
        Assume.assumeTrue("no corpus files found", !files.isEmpty());

        report("EMPTY CLASSPATH — a script whose types are not all resolvable", files, List.of());
        report("REPOSITORY CLASSPATH — code that compiles", files, repositoryClasspath());
    }

    private static void report(String title, List<Path> files, List<String> classpath) throws IOException {
        int level = newestLevel();
        Map<String, Seen> byId = new HashMap<>();
        int total = 0;

        for (Path file : files) {
            String className = file.getFileName().toString().replace(".java", "");
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            try (SourceAnalyzer.Analysis analysis = analyzerFor(className, source, classpath, level)) {
                List<Diagnostic> problems = analysis.diagnostics();
                total += problems.size();
                Set<String> askedHere = new HashSet<>();
                for (Diagnostic problem : problems) {
                    Seen seen = byId.computeIfAbsent(problem.code(), any -> new Seen());
                    seen.count++;
                    if (seen.example.isEmpty()) {
                        seen.example = problem.message();
                        seen.severity = problem.severity() == DiagnosticSeverity.ERROR ? "E"
                                : problem.severity() == DiagnosticSeverity.WARNING ? "W" : "i";
                    }
                    // ONE ASK PER (file, id). Asking per problem is quadratic and buys nothing: what is
                    // being measured is whether this id has an answer at all, not how many times over.
                    if (!askedHere.add(problem.code())) continue;
                    seen.files++;
                    int from = offsetOf(source, problem.start().row(), problem.start().column());
                    int to = offsetOf(source, problem.end().row(), problem.end().column());
                    if (from < 0 || to < from || to > source.length()) continue;
                    List<CodeAction> actions;
                    try {
                        actions = analysis.codeActionsIn(from, to, HOST);
                    } catch (RuntimeException broken) {
                        continue;
                    }
                    if (!actions.isEmpty()) {
                        seen.offered++;
                        if (seen.action.isEmpty()) seen.action = actions.get(0).id();
                    }
                }
            } catch (Throwable engine) {
                // The engine's own failures are CorpusTest's subject, not this one's.
            }
        }

        List<Map.Entry<String, Seen>> ranked = new ArrayList<>(byId.entrySet());
        ranked.sort((a, b) -> b.getValue().count - a.getValue().count);

        System.out.println();
        System.out.println("=== " + title + " — " + total + " problems, " + byId.size() + " distinct ids");
        System.out.printf("%-8s %-7s %-6s %-7s %-9s %-34s %s%n",
                "id", "count", "files", "answer", "severity", "first action", "example message");
        for (Map.Entry<String, Seen> entry : ranked) {
            Seen seen = entry.getValue();
            if (seen.count < 3) continue;
            String answered = seen.offered == 0 ? "NONE"
                    : seen.offered == seen.files ? "all" : seen.offered + "/" + seen.files;
            System.out.printf("%-8s %-7d %-6d %-7s %-9s %-34s %s%n",
                    entry.getKey(), seen.count, seen.files, answered, seen.severity,
                    seen.action.isEmpty() ? "-" : seen.action, trim(seen.example));
        }
    }

    private static String trim(String message) {
        String one = message.replace('\n', ' ');
        return one.length() > 92 ? one.substring(0, 92) + "…" : one;
    }

    private static SourceAnalyzer.Analysis analyzerFor(String className, String source,
                                                       List<String> classpath, int level) {
        return analyse(className, source, classpath, level);
    }

    private static int offsetOf(String source, int row, int column) {
        int at = 0;
        for (int line = 0; line < row; line++) {
            int next = source.indexOf('\n', at);
            if (next < 0) return -1;
            at = next + 1;
        }
        return at + column;
    }

    /** The repository's own compiled classes, so most of a corpus file actually resolves. */
    private static List<String> repositoryClasspath() {
        String root = System.getProperty("cgui.test.repoRoot");
        if (root == null) return List.of();
        List<String> entries = new ArrayList<>();
        for (String module : new String[] {"core", "language"}) {
            Path classes = Paths.get(root, module, "build", "classes", "java", "main");
            if (Files.isDirectory(classes)) entries.add(classes.toString());
        }
        return entries;
    }

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
