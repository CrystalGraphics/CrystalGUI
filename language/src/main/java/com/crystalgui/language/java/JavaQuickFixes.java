package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.CodeActionContext;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry every {@link Correction} is reached through — the error → fix table, indexed rather than
 * switched.
 *
 * <h3>Keyed on the problem id, which is the only thing that can key it</h3>
 *
 * <p>Eclipse's own {@code IQuickFixProcessor} is a switch on {@code IProblem.getID()}, and the reason is
 * that a problem's <em>identity</em> is the only durable statement of what it means — its message is prose
 * that changes between releases and is localised. {@code EcjSourceAnalyzer} already puts that id into
 * {@code Diagnostic.code}, so nothing had to be threaded through the analyzer to make this possible.</p>
 *
 * <p><b>Named constants, never numeric literals and never ranges.</b> {@code IProblem} is published API in
 * a jar this module already imports; the ID <em>ranges</em> are internal, which is the distinction
 * {@code optionalProblemsAnalysed} already records for {@code CategorizedProblem}. Java inlines a
 * {@code static final int} at compile time, so these become literals and are safe on the oldest band even
 * though that also means a missing constant could not be detected — which is why corrections stick to
 * problems that have existed since JDT 3.x.</p>
 *
 * <h3>What this class is, and what it deliberately is not</h3>
 *
 * <p>It is an index and a loop. It holds no correction logic at all, and that is the point: the previous
 * version was a chain of {@code else if} that every new fix had to edit, which is how a shared file
 * becomes the thing that breaks when two people add unrelated corrections. Adding a fix now means adding
 * it to a family file and naming it in {@link #ALL}.</p>
 *
 * <h3>An unknown id returns nothing, and that is the answer rather than a gap</h3>
 *
 * <p>ECJ reports on the order of a thousand distinct problems. Covering them is not a goal: the popup
 * still shows the message and whatever the shape-derived contributors offer, and treating an empty result
 * as a hole to be filled is precisely how a table of (problems × fixes) gets built by accident.</p>
 */
final class JavaQuickFixes {

    /**
     * Every correction this engine has, named once.
     *
     * <p>The one list a new family has to be added to, and the only shared edit adding a fix requires.
     * Order is not significant — {@code CodeAction.ORDER} ranks the results and its tie-break is
     * insertion order, so a correction's place here decides nothing but the order of two otherwise equal
     * actions.</p>
     */
    private static final List<Correction> ALL = concat(
            UnusedCorrections.all(),
            ImportCorrections.all(),
            DidYouMeanCorrections.all(),
            ExceptionCorrections.all(),
            CreateCorrections.all(),
            DeadCodeCorrections.all(),
            ExpressionCorrections.all(),
            ModifierCorrections.all(),
            LambdaCorrections.all(),
            CastCorrections.all());

    /** {@code IProblem} id → the corrections that answer for it. Built once. */
    private static final Map<Integer, List<Correction>> BY_PROBLEM = index(ALL);

    /** The corrections that answer for no problem — asked once per request about the range. */
    private static final List<Correction> INTENTIONS = intentions(ALL);

    private JavaQuickFixes() {
    }

    /**
     * Everything offered for the problems overlapping {@code [from, to)}, and for the range itself.
     *
     * <p>In the unit's own coordinates. The caller stamps the answer with the analysis version and the
     * apply path refuses it if the buffer has moved, so these offsets are either exactly right or unused.</p>
     */
    static List<CodeAction> in(CompilationUnit unit, String source, long version, int from, int to,
                              CodeActionContext host) {
        if (unit == null || source == null) return List.of();
        FixContext context = new FixContext(unit, source, version, host, from, to);
        List<CodeAction> actions = new ArrayList<>();

        for (IProblem problem : unit.getProblems()) {
            // ASKED OF THE SAME PLACE THE UNDERLINE IS DRAWN. @see ProblemSpans -- these used to be two
            // independent readings of one problem, and a mark moved on one side went unreachable on the
            // other.
            if (!ProblemSpans.reaches(unit, problem, from, to)) continue;
            for (Correction correction : BY_PROBLEM.getOrDefault(problem.getID(), List.of())) {
                contribute(correction, context, problem, actions);
            }
        }
        // AFTER the problems, so on a tie an intention sorts behind a fix for what is actually wrong
        // here -- the sort is stable and its last key is insertion order.
        for (Correction intention : INTENTIONS) contribute(intention, context, null, actions);

        actions.sort(CodeAction.ORDER);
        return actions;
    }

    /**
     * When set, a correction that throws takes the request down with it instead of being swallowed.
     *
     * <p>A system property rather than a field, because it has to be visible on <b>both sides of the
     * classloader boundary</b>: this class runs in the engine's loader and a test runs in the host's, so a
     * static set from a test would land on a different copy of this class. Never set in production; set by
     * the corpus test, whose whole purpose is to find the exception this would otherwise hide.</p>
     */
    static final String STRICT_PROPERTY = "cgui.quickfix.strict";

    private static void contribute(Correction correction, FixContext context, IProblem problem,
                                   List<CodeAction> actions) {
        try {
            correction.contribute(context, problem, actions);
        } catch (RuntimeException broken) {
            // ONE CORRECTION MUST NOT COST THE OTHERS. A request computes every action for a range at
            // once and is answered on the UI thread, so a throw here would turn one buggy correction into
            // no popup at all -- and into an exception on an input path. A correction that throws is a
            // bug rather than a condition: the expected failure modes (no node, nothing to offer, a
            // rewrite that cannot be expressed) all return quietly on their own.
            if (Boolean.getBoolean(STRICT_PROPERTY)) {
                throw new IllegalStateException(correction.id() + " threw", broken);
            }
        }
    }

    private static Map<Integer, List<Correction>> index(List<Correction> corrections) {
        Map<Integer, List<Correction>> byProblem = new HashMap<>();
        for (Correction correction : corrections) {
            for (int problem : correction.problems()) {
                byProblem.computeIfAbsent(problem, any -> new ArrayList<>()).add(correction);
            }
        }
        return byProblem;
    }

    private static List<Correction> intentions(List<Correction> corrections) {
        List<Correction> found = new ArrayList<>();
        for (Correction correction : corrections) {
            if (correction.problems().length == 0) found.add(correction);
        }
        return found;
    }

    @SafeVarargs
    private static List<Correction> concat(List<Correction>... families) {
        List<Correction> all = new ArrayList<>();
        for (List<Correction> family : families) all.addAll(family);
        return all;
    }
}
