package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

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

    /**
     * <b>And the argument shape is not this correction's.</b> {@code take(a)} reports
     * {@code ParameterMismatch} on the method NAME, not {@code TypeMismatch} on the argument — so nothing
     * here fires, which is the honest answer until the row that redoes overload resolution is written.
     */
    @Test
    public void anArgumentIsLeftAlone() {
        assertNoFix(ANIMALS
                        + "    void take(Dog d) { }\n"
                        + "    void go(Animal a) { take(a); }\n"
                        + "}\n",
                "take(a)", CastCorrections.ADD_CAST,
                "an argument mismatch is a different problem and a different fix");
    }
}
