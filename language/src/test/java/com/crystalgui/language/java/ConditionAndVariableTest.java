package com.crystalgui.language.java;

import com.crystalgui.language.java.fix.catalog.IntentionCorrections;
import com.crystalgui.language.java.fix.catalog.VariableIntentions;
import org.junit.Test;

/**
 * Batch F — the condition pair and the variable pair.
 *
 * <p>Intentions, so there is no {@code assertReported} to open with. What replaces it is the refusals: a
 * flip that would rebind an {@code else}, an inline that would duplicate a call or rebind an operator, and
 * an introduce that would name something already named.</p>
 */
public class ConditionAndVariableTest extends FixFixture {

    // ── Flip if/else ────────────────────────────────────────────────────────────────────────────

    @Test
    public void theBranchesSwapAndTheConditionInverts() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        if (n == 0) {\n"
                        + "            System.out.println(\"zero\");\n"
                        + "        } else {\n"
                        + "            System.out.println(\"other\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (n == 0)", IntentionCorrections.FLIP_IF_ELSE, ""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        if (n != 0) {\n"
                        + "            System.out.println(\"other\");\n"
                        + "        } else {\n"
                        + "            System.out.println(\"zero\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /** A condition with no opposite operator is wrapped, and wrapped as a whole. */
    @Test
    public void aCompoundConditionIsWrappedWhenNegated() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(boolean a, boolean b) {\n"
                        + "        if (a && b) {\n"
                        + "            System.out.println(1);\n"
                        + "        } else {\n"
                        + "            System.out.println(2);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (a && b)", IntentionCorrections.FLIP_IF_ELSE, ""
                        + "public class Script {\n"
                        + "    void go(boolean a, boolean b) {\n"
                        + "        if (!(a && b)) {\n"
                        + "            System.out.println(2);\n"
                        + "        } else {\n"
                        + "            System.out.println(1);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /** {@code !ready} negates to {@code ready}, not to {@code !!ready}. */
    @Test
    public void anAlreadyNegatedConditionUnwraps() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(boolean ready) {\n"
                        + "        if (!ready) {\n"
                        + "            System.out.println(1);\n"
                        + "        } else {\n"
                        + "            System.out.println(2);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (!ready)", IntentionCorrections.FLIP_IF_ELSE, ""
                        + "public class Script {\n"
                        + "    void go(boolean ready) {\n"
                        + "        if (ready) {\n"
                        + "            System.out.println(2);\n"
                        + "        } else {\n"
                        + "            System.out.println(1);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>Refused on an {@code else if}.</b> The else branch of a chain is another {@code if}, so swapping
     * would hoist a whole tail into the then-position and leave the chain meaning something else.
     */
    @Test
    public void anElseIfChainIsRefused() {
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
                "if (n == 1)", IntentionCorrections.FLIP_IF_ELSE,
                "swapping a chain's tail into the then-position changes what it means");
    }

    /** With no else there is nothing to swap, and negating alone would change what runs. */
    @Test
    public void anIfWithNoElseIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        if (n == 1) {\n"
                        + "            System.out.println(1);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (n == 1)", IntentionCorrections.FLIP_IF_ELSE,
                "negating without swapping is a different program");
    }

    // ── Negate comparison ───────────────────────────────────────────────────────────────────────

    @Test
    public void aComparisonFlipsItsOperator() {
        assertFix(""
                        + "public class Script {\n"
                        + "    boolean go(int n) { return n < 10; }\n"
                        + "}\n",
                "n < 10", IntentionCorrections.NEGATE_COMPARISON, ""
                        + "public class Script {\n"
                        + "    boolean go(int n) { return n >= 10; }\n"
                        + "}\n");
    }

    /**
     * <b>Only a comparison.</b> This is the one intention here that changes what the program does, so it
     * has to be unmistakably an edit somebody asked for — a flipped {@code ==} is; wrapping an arbitrary
     * condition in {@code !} is not.
     */
    @Test
    public void aNonComparisonIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    boolean go(boolean a, boolean b) { return a && b; }\n"
                        + "}\n",
                "a && b", IntentionCorrections.NEGATE_COMPARISON,
                "negating a compound condition is not a single readable edit");
    }

    // ── Introduce variable ──────────────────────────────────────────────────────────────────────

    @Test
    public void anExpressionBecomesALocal() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(String s) {\n"
                        + "        System.out.println(s.trim());\n"
                        + "    }\n"
                        + "}\n",
                "s.trim()", VariableIntentions.INTRODUCE, ""
                        + "public class Script {\n"
                        + "    void go(String s) {\n"
                        + "        String trim = s.trim();\n"
                        + "        System.out.println(trim);\n"
                        + "    }\n"
                        + "}\n");
    }

    /** {@code getSize()} suggests {@code size} — a name worth keeping beats {@code x}. */
    @Test
    public void theNameComesFromTheCalledMethod() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        System.out.println(getSize() + 1);\n"
                        + "    }\n"
                        + "    static int getSize() { return 1; }\n"
                        + "}\n",
                "getSize()", VariableIntentions.INTRODUCE, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        int size = getSize();\n"
                        + "        System.out.println(size + 1);\n"
                        + "    }\n"
                        + "    static int getSize() { return 1; }\n"
                        + "}\n");
    }

    /** A bare name is already a variable, and naming it again names nothing. */
    @Test
    public void aBareNameIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(String s) { System.out.println(s); }\n"
                        + "}\n",
                "println(s)", VariableIntentions.INTRODUCE,
                "s is already a variable");
    }

    // ── Inline variable ─────────────────────────────────────────────────────────────────────────

    @Test
    public void aLocalIsReplacedByItsValue() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(String s) {\n"
                        + "        String trimmed = s.trim();\n"
                        + "        System.out.println(trimmed);\n"
                        + "    }\n"
                        + "}\n",
                "String trimmed", VariableIntentions.INLINE, ""
                        + "public class Script {\n"
                        + "    void go(String s) {\n"
                        + "        System.out.println(s.trim());\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>Parenthesised on the way in.</b> {@code int sum = a + b;} inlined into {@code sum * 2} gives
     * {@code a + b * 2}, which compiles and is a different number.
     */
    @Test
    public void anOperatorExpressionIsWrapped() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(int a, int b) {\n"
                        + "        int sum = a + b;\n"
                        + "        System.out.println(sum * 2);\n"
                        + "    }\n"
                        + "}\n",
                "int sum", VariableIntentions.INLINE, ""
                        + "public class Script {\n"
                        + "    void go(int a, int b) {\n"
                        + "        System.out.println((a + b) * 2);\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>A call read twice would become two calls.</b> That is a behaviour change the moment it does
     * anything, and a popup with no dialog cannot ask the way IntelliJ's warning does.
     */
    @Test
    public void aCallUsedTwiceIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    static int compute() { return 1; }\n"
                        + "    void go() {\n"
                        + "        int n = compute();\n"
                        + "        System.out.println(n + n);\n"
                        + "    }\n"
                        + "}\n",
                "int n", VariableIntentions.INLINE,
                "one call would become two");
    }

    /** An assigned variable's initialiser is not its value. */
    @Test
    public void aReassignedLocalIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(int a) {\n"
                        + "        int n = a;\n"
                        + "        n = a + 1;\n"
                        + "        System.out.println(n);\n"
                        + "    }\n"
                        + "}\n",
                "int n", VariableIntentions.INLINE,
                "its initialiser stopped being its value at the next line");
    }

    /**
     * <b>A same-named local in another scope is another variable.</b> The refusal above used to be asked
     * by NAME over the whole method, so an unrelated {@code n} being assigned in a sibling block refused an
     * inline that was perfectly safe — while missing the write it was really there to catch, since
     * {@code xs[i] = …} never assigns {@code xs} by name at all. One question, asked by binding, at the one
     * place the bindings are.
     */
    @Test
    public void aSameNamedLocalInAnotherScopeIsNotThisOne() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(int a) {\n"
                        + "        if (a > 0) {\n"
                        + "            int n = a;\n"
                        + "            System.out.println(n);\n"
                        + "        }\n"
                        + "        if (a < 0) {\n"
                        + "            int n = 2;\n"
                        + "            n = 3;\n"
                        + "            System.out.println(n);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "int n = a", VariableIntentions.INLINE, ""
                        + "public class Script {\n"
                        + "    void go(int a) {\n"
                        + "        if (a > 0) {\n"
                        + "            System.out.println(a);\n"
                        + "        }\n"
                        + "        if (a < 0) {\n"
                        + "            int n = 2;\n"
                        + "            n = 3;\n"
                        + "            System.out.println(n);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /** <b>The pair round-trips</b>, which is what makes each worth having beside the other. */
    @Test
    public void introduceAndInlineAreInverses() {
        String before = ""
                + "public class Script {\n"
                + "    void go(String s) {\n"
                + "        System.out.println(s.trim());\n"
                + "    }\n"
                + "}\n";
        String introduced = applied(before, require(before, "s.trim()", VariableIntentions.INTRODUCE));
        String inlined = applied(introduced,
                require(introduced, "String trim", VariableIntentions.INLINE));
        org.junit.Assert.assertEquals("the pair must round-trip", before, inlined);
    }
}
