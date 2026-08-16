package com.crystalgui.language.java;

import org.junit.Test;

/**
 * The four intentions — where nothing is wrong and something could be different.
 *
 * <p><b>No {@code assertReported} here, and that is the whole distinction.</b> Every other family in this
 * package opens by asserting the compiler was configured to report its problem, because a fix keyed on an
 * ignored problem is invisible from its own code. An intention answers for no problem at all, so what
 * replaces that assertion is the pair of trigger tests: it is offered where the caret is meant to be, and
 * not where it is not.</p>
 */
public class IntentionCorrectionsTest extends FixFixture {

    // ── Split and join ──────────────────────────────────────────────────────────────────────────

    @Test
    public void aDeclarationSplitsFromItsAssignment() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a = 1;\n"
                        + "        System.out.println(a);\n"
                        + "    }\n"
                        + "}\n",
                "int a", IntentionCorrections.SPLIT_DECLARATION, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a;\n"
                        + "        a = 1;\n"
                        + "        System.out.println(a);\n"
                        + "    }\n"
                        + "}\n");
    }

    /** The initialiser is never touched, so whatever is in it comes through as typed. */
    @Test
    public void theInitialiserSurvivesTheSplitExactly() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        String s = \"a\" + /* keep me */ \"b\";\n"
                        + "        System.out.println(s);\n"
                        + "    }\n"
                        + "}\n",
                "String s", IntentionCorrections.SPLIT_DECLARATION, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        String s;\n"
                        + "        s = \"a\" + /* keep me */ \"b\";\n"
                        + "        System.out.println(s);\n"
                        + "    }\n"
                        + "}\n");
    }

    @Test
    public void aDeclarationJoinsItsAssignment() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a;\n"
                        + "        a = 1;\n"
                        + "        System.out.println(a);\n"
                        + "    }\n"
                        + "}\n",
                "int a;", IntentionCorrections.JOIN_DECLARATION, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a = 1;\n"
                        + "        System.out.println(a);\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>Only the very next statement.</b> Anything in between may read the variable, and moving the
     * initialiser up past a read changes what the program does — invisibly, because the result compiles.
     */
    @Test
    public void anAssignmentThatIsNotNextIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a;\n"
                        + "        System.out.println(\"between\");\n"
                        + "        a = 1;\n"
                        + "    }\n"
                        + "}\n",
                "int a;", IntentionCorrections.JOIN_DECLARATION,
                "the statement in between may read a");
    }

    /**
     * <b>Not offered from inside the value.</b> A caret in an initialiser is asking about the expression it
     * is in; putting this in that popup competes with whatever the expression actually needs.
     */
    @Test
    public void theSplitIsNotOfferedFromInsideTheInitialiser() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        String s = \"a longish literal\".trim();\n"
                        + "        System.out.println(s);\n"
                        + "    }\n"
                        + "}\n",
                "longish", IntentionCorrections.SPLIT_DECLARATION,
                "a caret inside the value is asking about the value");
    }

    /** {@code int a = 1, b = 2;} has one type node for both, so either direction would rewrite both. */
    @Test
    public void aMultiFragmentDeclarationIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int a = 1, b = 2;\n"
                        + "        System.out.println(a + b);\n"
                        + "    }\n"
                        + "}\n",
                "int a", IntentionCorrections.SPLIT_DECLARATION,
                "one type node serves both fragments");
    }

    // ── Braces ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnbracedIfGetsBraces() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(boolean flag) {\n"
                        + "        if (flag) System.out.println(1);\n"
                        + "    }\n"
                        + "}\n",
                "if (flag)", IntentionCorrections.ADD_BRACES, ""
                        + "public class Script {\n"
                        + "    void go(boolean flag) {\n"
                        + "        if (flag) {\n"
                        + "            System.out.println(1);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    @Test
    public void anUnbracedLoopGetsBraces() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        while (n > 0) n--;\n"
                        + "    }\n"
                        + "}\n",
                "while (n > 0)", IntentionCorrections.ADD_BRACES, ""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        while (n > 0) {\n"
                        + "            n--;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    @Test
    public void aBracedIfLosesThem() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(boolean flag) {\n"
                        + "        if (flag) {\n"
                        + "            System.out.println(1);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (flag)", IntentionCorrections.REMOVE_BRACES, ""
                        + "public class Script {\n"
                        + "    void go(boolean flag) {\n"
                        + "        if (flag)\n"
                        + "            System.out.println(1);\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>A declaration is not a legal unbraced body.</b> {@code if (x) int a = 1;} does not compile, so
     * removing these braces breaks the file outright.
     */
    @Test
    public void bracesAroundADeclarationStay() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(boolean flag) {\n"
                        + "        if (flag) {\n"
                        + "            int a = 1;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (flag)", IntentionCorrections.REMOVE_BRACES,
                "`if (x) int a = 1;` is not legal Java");
    }

    /**
     * <b>The dangling else.</b> An inner {@code if} with no {@code else}, inside a braced then-branch of an
     * {@code if} that has one: take the braces away and the {@code else} re-binds to the inner {@code if}.
     * It compiles, and it means something else.
     */
    @Test
    public void bracesHoldingAnElseApartStay() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(boolean a, boolean b) {\n"
                        + "        if (a) {\n"
                        + "            if (b) System.out.println(1);\n"
                        + "        } else {\n"
                        + "            System.out.println(2);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (a)", IntentionCorrections.REMOVE_BRACES,
                "the else would re-bind to the inner if");
    }

    /**
     * <b>{@code else if} is not an unbraced else.</b> Bracing it produces {@code else { if (…) … }}, which
     * is legal, identical in meaning, and not what anybody writing a chain wants.
     */
    @Test
    public void anElseIfIsNotBraced() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        if (n == 1) {\n"
                        + "            System.out.println(1);\n"
                        + "        } else if (n == 2) {\n"
                        + "            System.out.println(2);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "} else if", IntentionCorrections.ADD_BRACES,
                "an else-if chain is not an unbraced else");
    }
}
