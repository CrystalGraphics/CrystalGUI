package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * "Remove '@Override'" and "Remove '@SafeVarargs'" — the annotation that is on a declaration and should
 * not be.
 *
 * <p>Both problems are <b>errors</b> reported with no configuration, so there is no severity table to keep
 * in step. What there is instead is the refusal: a removal fix that fires on a <em>correct</em> annotation
 * would be silently destructive, and nothing in the output would say so.</p>
 */
public class AnnotationCorrectionsTest extends FixFixture {

    private static final String GREETER = ""
            + "public class Script {\n"
            + "    interface Greeter { String greet(String name); }\n";

    // ── @Override ───────────────────────────────────────────────────────────────────────────────

    /** The first thing to assert: the compiler is configured to report this at all. */
    @Test
    public void theOverrideProblemIsReported() {
        assertReported(GREETER
                        + "    static class Drifted implements Greeter {\n"
                        + "        public String greet(String name) { return name; }\n"
                        + "        @Override public String greet(String a, String b) { return a + b; }\n"
                        + "    }\n"
                        + "}\n",
                IProblem.MethodMustOverrideOrImplement);
    }

    @Test
    public void anOverrideThatOverridesNothingIsRemoved() {
        assertFix(""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        @Override void alone() { }\n"
                        + "    }\n"
                        + "}\n",
                "void alone", AnnotationCorrections.REMOVE_OVERRIDE, ""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        void alone() { }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>A qualified annotation is the same annotation.</b> {@code @java.lang.Override} is legal and rare,
     * and is exactly the spelling a simple-name comparison misses — so the match is on the last segment.
     */
    @Test
    public void aQualifiedOverrideIsRecognised() {
        assertFix(""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        @java.lang.Override void alone() { }\n"
                        + "    }\n"
                        + "}\n",
                "void alone", AnnotationCorrections.REMOVE_OVERRIDE, ""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        void alone() { }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>The refusal that matters.</b> An {@code @Override} that genuinely overrides is not a problem, so
     * nothing routes here — and a removal fix firing on a correct annotation is the failure mode with
     * nothing in the output to notice it.
     */
    @Test
    public void aCorrectOverrideIsLeftAlone() {
        assertNoFix(GREETER
                        + "    static class Right implements Greeter {\n"
                        + "        @Override public String greet(String name) { return name; }\n"
                        + "    }\n"
                        + "}\n",
                "public String greet", AnnotationCorrections.REMOVE_OVERRIDE,
                "this annotation is correct and there is no problem to answer");
    }

    /**
     * <b>The mark is on the annotation, not the method name ECJ reports.</b> The method is not what is
     * wrong and not what anybody can change — the annotation above it is, and it is the only part this
     * family will ever touch. javac and IntelliJ both point there.
     */
    @Test
    public void theMarkIsOnTheAnnotation() {
        assertEquals("the underline covers the annotation alone", "@Override",
                marked(""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        @Override void alone() { }\n"
                        + "    }\n"
                        + "}\n", IProblem.MethodMustOverrideOrImplement));
    }

    /** And the fix is reachable from there — the seam that cost the cast family a round. */
    @Test
    public void theFixIsOfferedOverTheRangeItMarks() {
        assertOfferedWhereMarked(""
                        + "public class Script {\n"
                        + "    static class Standalone {\n"
                        + "        @Override void alone() { }\n"
                        + "    }\n"
                        + "}\n",
                IProblem.MethodMustOverrideOrImplement, AnnotationCorrections.REMOVE_OVERRIDE);
    }

    // ── @SafeVarargs ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Read the constant names the other way round.</b> Both {@code SafeVarargs} problems fire where the
     * annotation is <em>wrongly applied</em>, never where it is missing — the catalogue records this as a
     * trap and it is one: a family named for the annotation reads as "add it".
     */
    @Test
    public void theFixedArityProblemIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    @SafeVarargs static void notVarargs(String only) { }\n"
                        + "}\n",
                IProblem.SafeVarargsOnFixedArityMethod);
    }

    @Test
    public void safeVarargsOnAFixedArityMethodIsRemoved() {
        assertFix(""
                        + "public class Script {\n"
                        + "    @SafeVarargs static void notVarargs(String only) { }\n"
                        + "}\n",
                "notVarargs", AnnotationCorrections.REMOVE_SAFE_VARARGS, ""
                        + "public class Script {\n"
                        + "    static void notVarargs(String only) { }\n"
                        + "}\n");
    }

    /** A non-final instance method can be overridden by one that is not safe. */
    @Test
    public void safeVarargsOnAnOverridableMethodIsRemoved() {
        assertFix(""
                        + "public class Script {\n"
                        + "    @SafeVarargs void promise(String... items) { }\n"
                        + "}\n",
                "promise", AnnotationCorrections.REMOVE_SAFE_VARARGS, ""
                        + "public class Script {\n"
                        + "    void promise(String... items) { }\n"
                        + "}\n");
    }
}
