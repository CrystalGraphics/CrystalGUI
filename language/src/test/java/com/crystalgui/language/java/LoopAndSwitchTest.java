package com.crystalgui.language.java;

import org.junit.Test;

/**
 * Batch G — the last three: enhanced for, if-chain to switch, and the lambda inverse.
 *
 * <p>All three are conversions between two forms of the same thing, so every test here is really about the
 * condition under which the two forms are <em>not</em> the same thing.</p>
 */
public class LoopAndSwitchTest extends FixFixture {

    // ── Enhanced for ────────────────────────────────────────────────────────────────────────────

    @Test
    public void anArrayIndexLoopBecomesAnEnhancedFor() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(String[] names) {\n"
                        + "        for (int i = 0; i < names.length; i++) {\n"
                        + "            System.out.println(names[i]);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "for (int i", LoopIntentions.ENHANCED_FOR, ""
                        + "public class Script {\n"
                        + "    void go(String[] names) {\n"
                        + "        for (String string : names) {\n"
                        + "            System.out.println(string);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    @Test
    public void aListIndexLoopBecomesAnEnhancedFor() {
        assertFix(""
                        + "import java.util.List;\n"
                        + "public class Script {\n"
                        + "    void go(List<String> names) {\n"
                        + "        for (int i = 0; i < names.size(); i++) {\n"
                        + "            System.out.println(names.get(i));\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "for (int i", LoopIntentions.ENHANCED_FOR, ""
                        + "import java.util.List;\n"
                        + "public class Script {\n"
                        + "    void go(List<String> names) {\n"
                        + "        for (String string : names) {\n"
                        + "            System.out.println(string);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>The whole safety condition.</b> One use of the index for anything but fetching, and the enhanced
     * form cannot express the loop — it has no index to offer.
     */
    @Test
    public void anIndexUsedForAnythingElseIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(String[] names) {\n"
                        + "        for (int i = 0; i < names.length; i++) {\n"
                        + "            System.out.println(i + \": \" + names[i]);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "for (int i", LoopIntentions.ENHANCED_FOR,
                "the enhanced form has no index to give back");
    }

    /** A loop that does not start at zero is a different loop. */
    @Test
    public void aLoopStartingElsewhereIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(String[] names) {\n"
                        + "        for (int i = 1; i < names.length; i++) {\n"
                        + "            System.out.println(names[i]);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "for (int i", LoopIntentions.ENHANCED_FOR,
                "it skips the first element");
    }

    /**
     * <b>The sequence has to be repeatable.</b> {@code list().size()} is called every iteration and the
     * enhanced form calls it once, which is usually what was wanted and is not the same program.
     */
    @Test
    public void aComputedSequenceIsRefused() {
        assertNoFix(""
                        + "import java.util.List;\n"
                        + "public class Script {\n"
                        + "    List<String> names() { return null; }\n"
                        + "    void go() {\n"
                        + "        for (int i = 0; i < names().size(); i++) {\n"
                        + "            System.out.println(names().get(i));\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "for (int i", LoopIntentions.ENHANCED_FOR,
                "names() is evaluated once instead of every iteration");
    }

    // ── If chain to switch ──────────────────────────────────────────────────────────────────────

    @Test
    public void aChainOfIntegerTestsBecomesASwitch() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        if (n == 1) {\n"
                        + "            System.out.println(\"one\");\n"
                        + "        } else if (n == 2) {\n"
                        + "            System.out.println(\"two\");\n"
                        + "        } else if (n == 3) {\n"
                        + "            System.out.println(\"three\");\n"
                        + "        } else {\n"
                        + "            System.out.println(\"many\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (n == 1)", SwitchIntentions.TO_SWITCH, ""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        switch (n) {\n"
                        + "            case 1:\n"
                        + "                System.out.println(\"one\");\n"
                        + "                break;\n"
                        + "            case 2:\n"
                        + "                System.out.println(\"two\");\n"
                        + "                break;\n"
                        + "            case 3:\n"
                        + "                System.out.println(\"three\");\n"
                        + "                break;\n"
                        + "            default:\n"
                        + "                System.out.println(\"many\");\n"
                        + "                break;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>A branch that already leaves gets no {@code break}.</b> Fall-through is the one difference between
     * the two forms and it is silent, so every branch that does not leave gets one and none that does.
     */
    @Test
    public void aReturningBranchIsNotGivenABreak() {
        assertFix(""
                        + "public class Script {\n"
                        + "    String go(String s) {\n"
                        + "        if (s.equals(\"a\")) {\n"
                        + "            return \"first\";\n"
                        + "        } else if (s.equals(\"b\")) {\n"
                        + "            return \"second\";\n"
                        + "        } else if (s.equals(\"c\")) {\n"
                        + "            return \"third\";\n"
                        + "        }\n"
                        + "        return \"none\";\n"
                        + "    }\n"
                        + "}\n",
                "if (s.equals", SwitchIntentions.TO_SWITCH, ""
                        + "public class Script {\n"
                        + "    String go(String s) {\n"
                        + "        switch (s) {\n"
                        + "            case \"a\":\n"
                        + "                return \"first\";\n"
                        + "            case \"b\":\n"
                        + "                return \"second\";\n"
                        + "            case \"c\":\n"
                        + "                return \"third\";\n"
                        + "        }\n"
                        + "        return \"none\";\n"
                        + "    }\n"
                        + "}\n");
    }

    /** A chain that changes subject halfway is a chain, not a switch. */
    @Test
    public void aChainTestingTwoVariablesIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go(int n, int m) {\n"
                        + "        if (n == 1) {\n"
                        + "            System.out.println(1);\n"
                        + "        } else if (m == 2) {\n"
                        + "            System.out.println(2);\n"
                        + "        } else if (n == 3) {\n"
                        + "            System.out.println(3);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (n == 1)", SwitchIntentions.TO_SWITCH,
                "the branches do not all test the same value");
    }

    /**
     * <b>Enum constants are deliberately out.</b> A switch needs the label unqualified — {@code case RED},
     * never {@code case Colour.RED} — and the {@code if} it came from usually writes the qualified form.
     */
    @Test
    public void anEnumChainIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    enum Colour { RED, GREEN, BLUE }\n"
                        + "    void go(Colour c) {\n"
                        + "        if (c == Colour.RED) {\n"
                        + "            System.out.println(1);\n"
                        + "        } else if (c == Colour.GREEN) {\n"
                        + "            System.out.println(2);\n"
                        + "        } else if (c == Colour.BLUE) {\n"
                        + "            System.out.println(3);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "if (c == Colour.RED)", SwitchIntentions.TO_SWITCH,
                "a case label may not carry the qualifier the condition does");
    }

    /** Two branches are not worth a switch — the result is longer than what it replaces. */
    @Test
    public void aTwoBranchChainIsRefused() {
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
                "if (n == 1)", SwitchIntentions.TO_SWITCH,
                "a switch here is longer than the chain it replaces");
    }

    // ── Lambda to anonymous class ───────────────────────────────────────────────────────────────

    @Test
    public void aLambdaBecomesAnAnonymousClass() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        Runnable r = () -> System.out.println(1);\n"
                        + "        r.run();\n"
                        + "    }\n"
                        + "}\n",
                "() ->", LambdaCorrections.TO_ANONYMOUS, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        Runnable r = new Runnable() {\n"
                        + "            @Override\n"
                        + "            public void run() {\n"
                        + "                System.out.println(1);\n"
                        + "            }\n"
                        + "        };\n"
                        + "        r.run();\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>An unqualified {@code this} means something different on each side.</b> In a lambda it is the
     * enclosing instance; inside an anonymous class body it would be the anonymous one. The forward
     * conversion refuses on exactly this, from the other direction.
     */
    @Test
    public void aLambdaUsingThisIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        Runnable r = () -> System.out.println(this);\n"
                        + "        r.run();\n"
                        + "    }\n"
                        + "}\n",
                "() ->", LambdaCorrections.TO_ANONYMOUS,
                "`this` would stop meaning the enclosing instance");
    }
}
