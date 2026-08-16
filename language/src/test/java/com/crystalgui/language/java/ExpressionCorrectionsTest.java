package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

/**
 * The three expressions ECJ has proved say nothing.
 *
 * <p>Each is asserted through {@code assertResolves}, so a row proves the whole round trip: the problem is
 * reported at all (all three are {@code ignore} in ECJ's defaults, so a missing option would leave the
 * correction alive-looking and dead), the correction keys on it, and the edit applies to give exactly the
 * text below.</p>
 */
public class ExpressionCorrectionsTest extends FixFixture {

    // ── Remove unnecessary cast ─────────────────────────────────────────────────────────────────

    @Test
    public void aCastToTheTypeItAlreadyIsIsRemoved() {
        assertFix("""
                        public class Script {
                            void go(String s) { String t = (String) s; System.out.println(t); }
                        }
                        """,
                "(String) s", ExpressionCorrections.REMOVE_CAST,
                """
                        public class Script {
                            void go(String s) { String t = s; System.out.println(t); }
                        }
                        """);
    }

    @Test
    public void theProblemIsReportedAndTheCorrectionKeysOnIt() {
        assertResolves("""
                        public class Script {
                            void go(String s) { String t = (String) s; System.out.println(t); }
                        }
                        """,
                "(String) s", ExpressionCorrections.REMOVE_CAST, IProblem.UnnecessaryCast);
    }

    /**
     * <b>The parentheses that existed FOR the cast go with it.</b> Replacing the cast alone is correct and
     * leaves {@code (s).length()}, which reads as a fix that stopped half way.
     */
    @Test
    public void theParenthesesAroundACastGoWithIt() {
        assertFix("""
                        public class Script {
                            void go(String s) { System.out.println(((String) s).length()); }
                        }
                        """,
                "(String) s", ExpressionCorrections.REMOVE_CAST,
                """
                        public class Script {
                            void go(String s) { System.out.println(s.length()); }
                        }
                        """);
    }

    /**
     * <b>And parentheses that are part of the OPERAND stay.</b> {@code (int) (a + b)} must not become
     * {@code a + b} anywhere the precedence would change — here it cannot, because the parentheses belong
     * to the operand and travel with it.
     */
    @Test
    public void anOperandKeepsItsOwnParentheses() {
        assertFix("""
                        public class Script {
                            void go(int a, int b) { int t = (int) (a + b); System.out.println(t); }
                        }
                        """,
                "(int) (a + b)", ExpressionCorrections.REMOVE_CAST,
                """
                        public class Script {
                            void go(int a, int b) { int t = (a + b); System.out.println(t); }
                        }
                        """);
    }

    /**
     * <b>A cast that decides an overload is never offered</b> — and the guard is ECJ's, not ours. With two
     * candidates present the warning is not reported at all, so a cast this engine offers to remove can
     * never be one that changes which method runs. Pinned because it is the entire safety argument for the
     * fix, and it lives in a compiler we do not control.
     */
    @Test
    public void aCastThatSelectsAnOverloadIsNotReported() {
        assertNoFix("""
                        public class Script {
                            void take(Object o) { }
                            void take(String s) { }
                            void go(String s) { take((Object) s); }
                        }
                        """,
                "(Object) s", ExpressionCorrections.REMOVE_CAST,
                "removing this cast would call take(String) instead");
    }

    // ── instanceof ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A null check, not {@code true}.</b> JLS 15.20.2 makes {@code x instanceof T} false when {@code x}
     * is null whatever the declared types say, so {@code true} would be wrong for exactly the operand ECJ
     * reports this on — a plain parameter, which can obviously be null.
     */
    @Test
    public void aDecidedInstanceofBecomesANullCheck() {
        assertFix("""
                        public class Script {
                            void go(String s) { boolean b = s instanceof Object; System.out.println(b); }
                        }
                        """,
                "s instanceof Object", ExpressionCorrections.REPLACE_INSTANCEOF,
                """
                        public class Script {
                            void go(String s) { boolean b = s != null; System.out.println(b); }
                        }
                        """);
    }

    // ── Redundant null check ────────────────────────────────────────────────────────────────────

    /** A check that cannot fail, with nothing else in the condition: the {@code if} is the noise. */
    @Test
    public void aCheckThatCannotFailLosesItsIf() {
        assertFix("""
                        public class Script {
                            void go() { Object o = new Object(); if (o != null) System.out.println("y"); }
                        }
                        """,
                "o != null", ExpressionCorrections.SIMPLIFY_NULL_CHECK,
                """
                        public class Script {
                            void go() { Object o = new Object(); System.out.println("y"); }
                        }
                        """);
    }

    /**
     * <b>The always-FAILS case belongs to the other correction, and this pins the division.</b> ECJ reports
     * it under a different id ({@code …ComparisonYieldsFalse}, not {@code RedundantNullCheckOn…}) and adds
     * a {@code DeadCode} on the branch that cannot run — so it is already answered, by the fix written for
     * dead code. Two corrections offering the same collapse on one problem is the duplication this test
     * exists to prevent.
     */
    @Test
    public void anAlwaysFailingCheckIsLeftToTheDeadCodeFix() {
        String source = """
                public class Script {
                    void go() { String s = null; if (s != null) System.out.println("y"); }
                }
                """;
        assertNoFix(source, "s != null", ExpressionCorrections.SIMPLIFY_NULL_CHECK,
                "a check that always fails is reported as dead code, not as a redundant check");
        assertFix(source, "System.out.println(\"y\")", DeadCodeCorrections.REMOVE_BRANCH,
                """
                        public class Script {
                            void go() { String s = null; }
                        }
                        """);
    }

    /**
     * <b>A compound condition is offered nothing.</b> ECJ reports one identifier, and it can sit inside a
     * condition that is otherwise perfectly live — collapsing {@code if (o != null && o.isEmpty())} would
     * delete a real test. Removing just the operand is a different fix and is not guessed at here.
     */
    @Test
    public void aCompoundConditionIsLeftAlone() {
        assertNoFix("""
                        public class Script {
                            void go() {
                                Object o = new Object();
                                if (o != null && o.hashCode() > 0) System.out.println("y");
                            }
                        }
                        """,
                "o != null", ExpressionCorrections.SIMPLIFY_NULL_CHECK,
                "the second operand is a real test and must survive");
    }

    /**
     * <b>A defensive check on a parameter is not reported at all</b>, so nothing is offered on the shape
     * people actually write. ECJ knows nothing about what a caller passes; only a local it has tracked can
     * be proved redundant. This is what keeps the diagnostic from being noise.
     */
    @Test
    public void aDefensiveCheckOnAParameterIsNotReported() {
        assertNoFix("""
                        public class Script {
                            void go(String s) { if (s != null) System.out.println(s); }
                        }
                        """,
                "s != null", ExpressionCorrections.SIMPLIFY_NULL_CHECK,
                "a parameter's nullness is the caller's business");
    }
}
