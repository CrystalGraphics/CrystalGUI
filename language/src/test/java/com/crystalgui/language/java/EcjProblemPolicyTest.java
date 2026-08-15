package com.crystalgui.language.java;

import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticTag;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What this engine chooses to report, and how it asks for it to be drawn.
 *
 * <p>Both halves fail silently, which is why they are asserted rather than assumed. A problem left at
 * {@code ignore} makes any correction keyed on it dead code that looks alive; a tag never produced makes
 * every "nothing reads this" arrive as one more underline indistinguishable from a real defect — and in
 * both cases everything downstream is correct and nothing throws.</p>
 */
public class EcjProblemPolicyTest extends FixFixture {

    private static Diagnostic first(String source, int problemId) {
        String code = Integer.toString(problemId);
        for (Diagnostic each : diagnosticsOf(source)) {
            if (code.equals(each.code())) return each;
        }
        return null;
    }

    // ── Reported at all ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>ECJ leaves {@code emptyStatement} at {@code ignore}</b>, so this is reported only because the
     * policy switches it on — and the correction for it would otherwise never fire.
     */
    @Test
    public void aProblemTheDefaultsIgnoreIsReportedBecauseThePolicyEnablesIt() {
        String source = "public class Script { void go() { ; } }\n";
        assertNotNull("emptyStatement must be switched on or its correction is dead code",
                first(source, IProblem.SuperfluousSemicolon));
    }

    /**
     * A discarded {@code new} — enabled for the opposite reason to the semicolon above.
     *
     * <p>Not tidiness: an allocation nobody keeps is nearly always a forgotten assignment, which is why
     * it is <em>not</em> tagged as dead weight and deliberately has no quick fix. Offering to delete the
     * line would be offering to discard the evidence.</p>
     */
    @Test
    public void aDiscardedAllocationIsReportedAndIsNotTreatedAsDeadWeight() {
        String source = "public class Script { void go() { new Object(); } }\n";
        Diagnostic problem = first(source, IProblem.UnusedObjectAllocation);
        assertNotNull("unusedObjectAllocation must be switched on", problem);
        assertFalse("a discarded allocation is a defect, not dead weight — it keeps its underline",
                problem.hasTag(DiagnosticTag.UNNECESSARY));
    }

    /**
     * <b>The one thing turned DOWN from ECJ's default.</b>
     *
     * <p>{@code Throwable} implements {@code Serializable}, so every custom exception a script declares
     * was flagged for having no {@code serialVersionUID} — a warning about the binary compatibility of
     * serialized instances across builds, whose only achievable remedy is a magic constant nobody reads.
     * Asserted rather than assumed because switching a problem off is invisible from everywhere else:
     * nothing fails, a mark simply stops appearing.</p>
     */
    @Test
    public void aMissingSerialVersionUidIsNotReported() {
        String source = ""
                + "public class Script {\n"
                + "    static final class Failure extends Exception { }\n"
                + "}\n";
        assertNull("an exception class must not be nagged about serialVersionUID",
                first(source, IProblem.MissingSerialVersion));
    }

    /**
     * <b>A suppression must not report on the compiler's own configuration.</b>
     *
     * <p>{@code @SuppressWarnings("unused")} used to draw an info squiggle reading "At least one of the
     * problems in category 'unused' is not analysed due to a compiler option being ignored" — true, and
     * about this engine's option table rather than about the code it was written on.</p>
     */
    @Test
    public void aSuppressWarningsAnnotationIsNotReportedOn() {
        String source = ""
                + "public class Script {\n"
                + "    @SuppressWarnings(\"unused\")\n"
                + "    int go() { int attempts = 0; return attempts; }\n"
                + "}\n";
        assertNull("the author cannot act on our compiler options",
                first(source, IProblem.ProblemNotAnalysed));
        assertTrue("and nothing else should land on the annotation either",
                diagnosticsOf(source).isEmpty());
    }

    /**
     * <b>An unused method is marked on its NAME, not on its signature.</b>
     *
     * <p>Every other {@code unused} problem is already reported on the name alone; these two carry the
     * parameter list with them. Invisible while the mark was an underline and glaring once it became a
     * fade — the whole signature went grey, so {@code int unusedParameter} read as unused in its own
     * right when it is simply part of the thing that is unused.</p>
     */
    @Test
    public void anUnusedMemberIsMarkedOnItsNameAlone() {
        String source = ""
                + "public class Script {\n"
                + "    private void helper(int a, String b) { }\n"
                + "    private Script(int unusedParameter) { }\n"
                + "    Script() { }\n"
                + "}\n";
        assertEquals("the method's mark covers 'helper' and nothing more",
                "helper", marked(source, IProblem.UnusedPrivateMethod));
        assertEquals("the constructor's mark covers 'Script' and not its parameters",
                "Script", marked(source, IProblem.UnusedPrivateConstructor));
    }

    /** The text a problem's reported range actually covers. */
    private static String marked(String source, int problemId) {
        Diagnostic problem = first(source, problemId);
        assertNotNull("problem " + problemId + " is not reported", problem);
        String[] lines = source.split("\n", -1);
        StringBuilder text = new StringBuilder();
        for (int row = problem.start().row(); row <= problem.end().row() && row < lines.length; row++) {
            int from = row == problem.start().row() ? problem.start().column() : 0;
            int to = row == problem.end().row() ? problem.end().column() : lines[row].length();
            text.append(lines[row], Math.min(from, lines[row].length()), Math.min(to, lines[row].length()));
        }
        return text.toString();
    }

    // ── Drawn as dead weight ────────────────────────────────────────────────────────────────────

    @Test
    public void everythingNothingReadsIsTaggedUnnecessary() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    private int field;\n"
                + "    private void method() { }\n"
                + "    void go() {\n"
                + "        String local = \"x\";\n"
                + "        ;\n"
                + "    }\n"
                + "}\n";
        for (int problem : new int[] {
                IProblem.UnusedImport,
                IProblem.UnusedPrivateField,
                IProblem.UnusedPrivateMethod,
                IProblem.LocalVariableIsNeverUsed,
                IProblem.SuperfluousSemicolon}) {
            Diagnostic reported = first(source, problem);
            assertNotNull("problem " + problem + " is not reported at all", reported);
            assertTrue("problem " + problem + " should be drawn faded, not underlined",
                    reported.hasTag(DiagnosticTag.UNNECESSARY));
        }
    }

    /**
     * <b>A real error is never faded.</b>
     *
     * <p>The line the tag draws is between dead weight and a defect, and fading an unresolved type would
     * say "delete this" about the one thing in the file that most needs attention.</p>
     */
    @Test
    public void anErrorIsNotFaded() {
        String source = "public class Script { Nonexistent field; }\n";
        List<Diagnostic> reported = diagnosticsOf(source);
        assertFalse("the fixture must actually fail to resolve", reported.isEmpty());
        for (Diagnostic each : reported) {
            assertFalse("an error must keep its underline: " + each.message(),
                    each.hasTag(DiagnosticTag.UNNECESSARY));
        }
    }

    /**
     * Deprecation was this engine's one opinion before the table existed, and still carries its tag.
     *
     * <p><b>Two top-level types, and it has to be.</b> JLS 9.6.4.6 excuses a deprecation warning when the
     * use and the declaration share an outermost class, so the obvious one-class fixture reports nothing
     * at all and would have looked like the option being off.</p>
     */
    @Test
    public void aDeprecatedCallIsTaggedDeprecated() {
        String source = ""
                + "public class Script {\n"
                + "    void go() { new Helper().old(); }\n"
                + "}\n"
                + "class Helper {\n"
                + "    @Deprecated void old() { }\n"
                + "}\n";
        Diagnostic problem = first(source, IProblem.UsingDeprecatedMethod);
        assertNotNull("deprecation must stay switched on", problem);
        assertEquals("struck through, not faded — it still works",
                true, problem.hasTag(DiagnosticTag.DEPRECATED));
        assertFalse(problem.hasTag(DiagnosticTag.UNNECESSARY));
    }
}
