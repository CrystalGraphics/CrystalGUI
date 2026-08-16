package com.crystalgui.language.java;

import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * "Cast expression to 'Dog'" — and the case that looks identical and must be refused.
 *
 * <p>Both problems here are <b>errors</b> reported with no configuration, so there is no severity table to
 * keep in step. What there is instead is one guard doing all the work: the same {@code TypeMismatch} covers
 * a downcast that is exactly right and an assignment between unrelated types where a cast is a second
 * error rather than a repair.</p>
 */
public class CastCorrectionsTest extends FixFixture {

    private static final String ANIMALS = ""
            + "public class Script {\n"
            + "    static class Animal { }\n"
            + "    static class Dog extends Animal { }\n";

    // ── The conversion ──────────────────────────────────────────────────────────────────────────

    @Test
    public void anInitialiserGetsTheCast() {
        assertFix(ANIMALS
                        + "    void go(Animal a) { Dog d = a; System.out.println(d); }\n"
                        + "}\n",
                "= a", CastCorrections.ADD_CAST, ANIMALS
                        + "    void go(Animal a) { Dog d = (Dog) a; System.out.println(d); }\n"
                        + "}\n");
    }

    @Test
    public void theProblemIsReportedAndTheCorrectionKeysOnIt() {
        assertResolves(ANIMALS
                        + "    void go(Animal a) { Dog d = a; System.out.println(d); }\n"
                        + "}\n",
                "= a", CastCorrections.ADD_CAST, IProblem.TypeMismatch);
    }

    /** The shape in the report: {@code this} in a constructor, assigned to a subtype-typed local. */
    @Test
    public void thisAssignedToASubtypeGetsTheCast() {
        assertFix(""
                        + "public class Script {\n"
                        + "    static abstract class Animal {\n"
                        + "        Animal() { Dog d = this; System.out.println(d); }\n"
                        + "    }\n"
                        + "    static final class Dog extends Animal { }\n"
                        + "}\n",
                "= this", CastCorrections.ADD_CAST, ""
                        + "public class Script {\n"
                        + "    static abstract class Animal {\n"
                        + "        Animal() { Dog d = (Dog) this; System.out.println(d); }\n"
                        + "    }\n"
                        + "    static final class Dog extends Animal { }\n"
                        + "}\n");
    }

    /** A {@code return} is a second problem id and the same repair. */
    @Test
    public void aReturnGetsTheCast() {
        assertFix(ANIMALS
                        + "    Dog go(Animal a) { return a; }\n"
                        + "}\n",
                "return a", CastCorrections.ADD_CAST, ANIMALS
                        + "    Dog go(Animal a) { return (Dog) a; }\n"
                        + "}\n");
    }

    @Test
    public void theReturnProblemIsADifferentIdAndTheSameFix() {
        assertResolves(ANIMALS + "    Dog go(Animal a) { return a; }\n}\n",
                "return a", CastCorrections.ADD_CAST, IProblem.ReturnTypeMismatch);
    }

    @Test
    public void anAssignmentGetsTheCast() {
        assertFix(ANIMALS
                        + "    void go(Animal a) { Dog d = null; d = a; System.out.println(d); }\n"
                        + "}\n",
                "d = a", CastCorrections.ADD_CAST, ANIMALS
                        + "    void go(Animal a) { Dog d = null; d = (Dog) a; System.out.println(d); }\n"
                        + "}\n");
    }

    /**
     * <b>A looser-binding operand is wrapped.</b> A cast is a unary operator and the rewriter adds no
     * parentheses of its own, so {@code (Dog) a + b} would cast the first operand and leave the sum alone —
     * which compiles about half the time and means something else every time.
     */
    @Test
    public void aLooserOperandIsParenthesised() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(boolean flag) { byte b = flag ? 1 : 2; System.out.println(b); }\n"
                        + "}\n",
                "flag ? 1 : 2", CastCorrections.ADD_CAST, ""
                        + "public class Script {\n"
                        + "    void go(boolean flag) { byte b = (byte) (flag ? 1 : 2); System.out.println(b); }\n"
                        + "}\n");
    }

    // ── The refusal that carries the whole safety argument ──────────────────────────────────────

    /**
     * <b>Unrelated types get nothing.</b> ECJ reports the same {@code TypeMismatch} for
     * {@code Integer n = aString}, where a cast is not a repair — it is {@code IllegalCast} instead, one
     * error traded for another. Measured: the id really is the same, so the guard is the only thing
     * separating the two. IntelliJ refuses here too.
     */
    @Test
    public void unrelatedTypesAreRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(String s) { Integer n = s; System.out.println(n); }\n"
                        + "}\n",
                "= s", CastCorrections.ADD_CAST,
                "a cast between these two is a different error, not a fix");
    }

    // ── The argument shape ──────────────────────────────────────────────────────────────────────

    /** The reported case: a call whose argument needs narrowing. */
    @Test
    public void anArgumentGetsTheCast() {
        assertFix(ANIMALS
                        + "    void take(Dog d) { }\n"
                        + "    void go(Animal a) { take(a); }\n"
                        + "}\n",
                "take(a)", CastCorrections.CAST_ARGUMENT, ANIMALS
                        + "    void take(Dog d) { }\n"
                        + "    void go(Animal a) { take((Dog) a); }\n"
                        + "}\n");
    }

    @Test
    public void theArgumentProblemIsAThirdIdAndItsOwnCorrection() {
        assertResolves(ANIMALS
                        + "    void take(Dog d) { }\n"
                        + "    void go(Animal a) { take(a); }\n"
                        + "}\n",
                "take(a)", CastCorrections.CAST_ARGUMENT, IProblem.ParameterMismatch);
    }

    /** Only the argument that is wrong, wherever it sits in the list. */
    @Test
    public void theSecondArgumentIsTheOneCast() {
        assertFix(ANIMALS
                        + "    void take(int n, Dog d) { }\n"
                        + "    void go(Animal a) { take(1, a); }\n"
                        + "}\n",
                "take(1, a)", CastCorrections.CAST_ARGUMENT, ANIMALS
                        + "    void take(int n, Dog d) { }\n"
                        + "    void go(Animal a) { take(1, (Dog) a); }\n"
                        + "}\n");
    }

    /**
     * <b>Two same-arity overloads get nothing.</b> There is no way to know which was meant — ECJ names one
     * in its message, but that is its guess rendered for a person rather than an answer that can be read.
     * A cast to the wrong one compiles and calls the wrong method, which is worse than offering nothing.
     */
    @Test
    public void anOverloadedCallIsRefused() {
        assertNoFix(ANIMALS
                        + "    void take(Dog d) { }\n"
                        + "    void take(String s) { }\n"
                        + "    void go(Animal a) { take(a); }\n"
                        + "}\n",
                "take(a)", CastCorrections.CAST_ARGUMENT,
                "which overload was meant is not knowable from the problem");
    }

    /** And the same guard: an unrelated argument type is a different error, not a cast. */
    @Test
    public void anUnrelatedArgumentIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void take(Integer n) { }\n"
                        + "    void go(String s) { take(s); }\n"
                        + "}\n",
                "take(s)", CastCorrections.CAST_ARGUMENT,
                "a cast between these two is IllegalCast, not help");
    }
    // ── Where the mark goes ─────────────────────────────────────────────────────────────────────

    /**
     * <b>The argument is underlined, not the method.</b> ECJ marks the method name, which reads as "this
     * method is the problem" and points the eye away from the only thing anyone can change. The same walk
     * that finds the argument to cast finds the one to mark, so the two can never disagree.
     */
    @Test
    public void theMarkIsOnTheArgumentRatherThanTheMethod() {
        String source = ANIMALS
                + "    void take(Dog d) { }\n"
                + "    void go(Animal a) { take(a); }\n"
                + "}\n";
        assertEquals("the underline covers the argument alone", "a",
                marked(source, IProblem.ParameterMismatch));
    }

    /**
     * <b>And the fix is reachable from there.</b> The router matches the caret against ECJ's own range, so
     * moving the mark onto the argument moved it <em>off</em> the range that answers: the popup showed the
     * message with nothing to do, at the one offset a user is guaranteed to be at.
     *
     * <p>Every other test in this file asks over {@code "take(a)"} — the whole call, which still covers
     * ECJ's span — which is why all seventeen passed against it. Asking where the squiggle is is a
     * different question from asking near it.</p>
     */
    @Test
    public void theFixIsOfferedOverTheRangeItMarks() {
        String source = ANIMALS
                + "    void take(Dog d) { }\n"
                + "    void go(Animal a) { take(a); }\n"
                + "}\n";
        int[] span = markedSpan(source, IProblem.ParameterMismatch);
        assertNotNull("the fix must be offered where the underline is",
                withId(actionsOver("Script", source, span[0], span[1]), CastCorrections.CAST_ARGUMENT));
    }

    /** The text a problem's reported range actually covers. */
    private static String marked(String source, int problemId) {
        int[] span = markedSpan(source, problemId);
        return source.substring(span[0], span[1]);
    }

    /** A problem's reported range, in the source's own offsets. */
    private static int[] markedSpan(String source, int problemId) {
        String code = Integer.toString(problemId);
        for (Diagnostic problem : diagnosticsOf(source)) {
            if (!code.equals(problem.code())) continue;
            return new int[] {offsetOf(source, problem.start().row(), problem.start().column()),
                    offsetOf(source, problem.end().row(), problem.end().column())};
        }
        throw new AssertionError("problem " + problemId + " is not reported");
    }

    private static int offsetOf(String source, int row, int column) {
        int at = 0;
        for (int line = 0; line < row; line++) at = source.indexOf('\n', at) + 1;
        return at + column;
    }

    private static CodeAction withId(List<CodeAction> actions, String id) {
        for (CodeAction action : actions) {
            if (id.equals(action.id())) return action;
        }
        return null;
    }

    // ── When a cast cannot answer ───────────────────────────────────────────────────────────────

    /**
     * <b>The declaration is what is wrong when a cast is impossible.</b> {@code Integer n = s} reports the
     * same {@code TypeMismatch} a downcast does and a cast there is {@code IllegalCast} — so the cast
     * correctly refuses, and this is the repair that does exist.
     */
    @Test
    public void anImpossibleCastOffersTheTypeChangeInstead() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(String s) { Integer n = s; System.out.println(n); }\n"
                        + "}\n",
                "= s", CastCorrections.CHANGE_TYPE, ""
                        + "public class Script {\n"
                        + "    void go(String s) { String n = s; System.out.println(n); }\n"
                        + "}\n");
    }

    /**
     * <b>And not where a cast would do.</b> A downcast that is genuinely right does not want its variable
     * widened back to the type it already had, and two answers to one question is what the popup's single
     * inline slot cannot show.
     */
    @Test
    public void aCastableMismatchIsNotOfferedATypeChange() {
        assertNoFix(ANIMALS
                        + "    void go(Animal a) { Dog d = a; System.out.println(d); }\n"
                        + "}\n",
                "= a", CastCorrections.CHANGE_TYPE,
                "the cast is the answer here, and it is already offered");
    }

    /**
     * <b>A shared declaration is left alone.</b> {@code int a = 1, b = x;} has one type node for both
     * fragments, so re-typing it for {@code b} silently re-types {@code a} — a fix editing a declaration
     * the caret was never on.
     */
    @Test
    public void aMultiFragmentDeclarationIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(String s) { Integer a = 1, b = s; System.out.println(a + b); }\n"
                        + "}\n",
                "= s", CastCorrections.CHANGE_TYPE,
                "one type node serves both fragments");
    }
}
