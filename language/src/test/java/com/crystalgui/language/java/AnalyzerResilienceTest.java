package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.language.engine.bridge.Analysis;

/**
 * A parse that JDT cannot complete must degrade, never take the service down.
 *
 * <h3>Where this came from</h3>
 *
 * <p>The corpus pass found it on the first run: {@code CompletionList.java}, a record whose component
 * types were unresolvable at an empty classpath, threw an {@code AssertionError} straight out of JDT's
 * binding layer — <em>"the constructor is wrongly tagged as containing missing types"</em>. Not a
 * correction's defect, so the corpus recorded it and moved on, and its own comment noted the part that
 * matters here: <b>production did not guard {@code createAST} against an {@code Error} either.</b></p>
 *
 * <p>Which makes it a real crash path rather than a test artefact. An analysis runs on a scheduler lane,
 * and a script declaring a record over a type that is not on the classpath — a mod class, on a server
 * without it — would kill that job rather than report anything. The document would then sit there with
 * no diagnostics, no colouring and no completions, and nothing on screen to say why.</p>
 */
public class AnalyzerResilienceTest extends FixFixture {

    /**
     * The shape the corpus tripped on, reduced but not simplified — every part of it earned its place by
     * being needed to reproduce.
     *
     * <p>A record with a missing component type is <b>not enough on its own</b>: JDT only tags the
     * canonical constructor as containing missing types once something asks it to resolve that
     * constructor, so the {@code new Probe(…)} calls are load-bearing, and so is the compact constructor
     * that reassigns the component. The first attempt at this fixture had the record and the missing type
     * and passed against the unguarded build.</p>
     */
    private static final String RECORD_OVER_MISSING_TYPES = """
            package com.crystalgui.text.lang;

            import java.util.List;

            public record Probe(List<CompletionItem> items, boolean incomplete) {

                public static final Probe EMPTY = new Probe(List.of(), false);

                public Probe {
                    items = items == null || items.isEmpty() ? List.of() : List.copyOf(items);
                }

                public static Probe complete(List<CompletionItem> items) {
                    return new Probe(items, false);
                }

                public static Probe partial(List<CompletionItem> items) {
                    return new Probe(items, true);
                }
            }
            """;

    /**
     * <b>It answers at all.</b> Before the guard this threw, and an {@code Error} on a worker lane is not
     * something a caller can be expected to have planned for.
     */
    @Test
    public void aParseJdtCannotCompleteStillReturnsAnAnalysis() {
        if (newestLevel() < 16) return;   // records need 16; an older band has nothing to reproduce
        try (SourceAnalyzer.Analysis analysis = analyse("Probe", RECORD_OVER_MISSING_TYPES, newestLevel())) {
            assertNotNull("the analyzer must not hand back null for a file JDT choked on", analysis);
            assertNotNull("and the degraded analysis must still answer its own queries",
                    analysis.diagnostics());
        }
    }

    /**
     * <b>And an ordinary file is untouched by the guard.</b> A retry that quietly became the normal path
     * would cost every file its bindings — no semantic colouring, no completions, no resolution — while
     * every test that only asks "did it answer" still passed.
     *
     * <p>Asserted on {@code resolveAt}, which is the query that cannot be answered without a binding.
     * Two nearer-to-hand probes are both wrong here and were both tried: {@code optionalProblemsAnalysed()}
     * reports whether a <em>syntax</em> error stopped the optional passes, so it answers true for a
     * binding-free parse of valid code; and {@code semanticTokens()} is <b>not</b> empty without bindings
     * either, because a declaration's own name is known to be a type or a method from the tree's shape
     * alone. Each would have passed against a build that had silently lost resolution entirely.</p>
     */
    @Test
    public void anOrdinaryFileStillResolvesItsBindings() {
        try (SourceAnalyzer.Analysis analysis = analyse("Script", HEALTHY, newestLevel())) {
            assertNotNull("a healthy parse must keep the resolution the fallback drops",
                    analysis.resolveAt(HEALTHY.indexOf("text.length") + 1));
        }
    }

    /**
     * <b>And the degraded parse is degraded, not merely alive.</b> The two above would both pass against
     * a guard that swallowed the failure and handed back an empty shell — this is what says the fallback
     * produced a real tree, with the grammar-level answer intact and only resolution missing.
     */
    @Test
    public void theDegradedParseKeepsItsTreeAndLosesOnlyResolution() {
        if (newestLevel() < 16) return;
        try (SourceAnalyzer.Analysis analysis = analyse("Probe", RECORD_OVER_MISSING_TYPES, newestLevel())) {
            assertTrue("a file with no syntax error must still report as fully parsed",
                    analysis.optionalProblemsAnalysed());
            assertTrue("and the tree survives, so the grammar-level answer does too",
                    !analysis.semanticTokens().isEmpty());
            assertNull("resolution is what failed, so resolution is what must be absent",
                    analysis.resolveAt(RECORD_OVER_MISSING_TYPES.indexOf("items.isEmpty") + 1));
        }
    }

    private static final String HEALTHY = """
            public class Script {
                void go() { String text = "x"; System.out.println(text.length()); }
            }
            """;
}
