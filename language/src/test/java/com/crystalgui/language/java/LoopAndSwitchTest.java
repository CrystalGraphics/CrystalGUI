package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.CodeAction;

import org.junit.Assume;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

    /**
     * <b>A real conversion outranks moving a brace.</b> Both apply to the same braced loop, and they used
     * to compete on <em>insertion order</em> — which family happened to be registered first in
     * {@code JavaQuickFixes} — so the popup offered "Remove braces" as the thing to press. Braces can be
     * added to or removed from every {@code if} and every loop in a file, which is exactly why they cannot
     * be allowed to win a tie.
     */
    @Test
    public void theConversionOutranksRemovingBracesOnALoop() {
        assertEquals("Convert to enhanced for", firstTitleIn(""
                + "public class Script {\n"
                + "    void go(String[] names) {\n"
                + "        for (int i = 0; i < names.length; i++) {\n"
                + "            System.out.println(names[i]);\n"
                + "        }\n"
                + "    }\n"
                + "}\n", "for (int i"));
    }

    /** And the same on a chain, which is where it was reported the second time. */
    @Test
    public void theConversionOutranksRemovingBracesOnAChain() {
        assertEquals("Replace if chain with switch", firstTitleIn(""
                + "public class Script {\n"
                + "    void go(int n) {\n"
                + "        if (n == 1) {\n"
                + "            System.out.println(1);\n"
                + "        } else if (n == 2) {\n"
                + "            System.out.println(2);\n"
                + "        } else if (n == 3) {\n"
                + "            System.out.println(3);\n"
                + "        }\n"
                + "    }\n"
                + "}\n", "if (n == 1)"));
    }

    /** The title of whatever the popup would put in its inline slot. */
    private static String firstTitleIn(String source, String needle) {
        List<CodeAction> offered = actionsIn(source, needle);
        assertFalse("nothing was offered at all", offered.isEmpty());
        return offered.get(0).title();
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

    // ── Colon switch to arrow switch ────────────────────────────────────────────────────────────

    private static final String COLON_SWITCH = ""
            + "public class Script {\n"
            + "    void go(int n) {\n"
            + "        switch (n) {\n"
            + "            case 1:\n"
            + "                System.out.println(1);\n"
            + "                break;\n"
            + "            case 2:\n"
            + "                System.out.println(2);\n"
            + "                break;\n"
            + "            default:\n"
            + "                System.out.println(0);\n"
            + "                break;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    /**
     * <b>The gate is the feature.</b> Arrow labels are Java 14; writing one into a file compiled at 8 turns
     * working code into a syntax error, which is worse than offering nothing because the offer looked like
     * an improvement. The engine already runs in bands and the level is part of the analysis request, so
     * this is a fact rather than a guess.
     */
    @Test
    public void anArrowSwitchIsNotOfferedBelowJava14() {
        assertNull("an arrow label does not parse at 8",
                withId(actionsAtLevel(COLON_SWITCH, "switch (n)", 8), SwitchIntentions.TO_ARROW));
    }

    @Test
    public void aColonSwitchBecomesAnArrowSwitch() {
        Assume.assumeTrue("this band cannot parse arrow labels", newestLevel() >= 14);
        CodeAction offered = withId(actionsAtLevel(COLON_SWITCH, "switch (n)", newestLevel()),
                SwitchIntentions.TO_ARROW);
        assertNotNull("nothing offered to convert the switch", offered);
        assertEquals(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        switch (n) {\n"
                        + "            case 1 -> System.out.println(1);\n"
                        + "            case 2 -> System.out.println(2);\n"
                        + "            default -> System.out.println(0);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                applied(COLON_SWITCH, offered));
    }

    /**
     * <b>Stacked labels are the one legal fall-through</b>, and the arrow form has a spelling for it —
     * {@code case 1, 2 ->}. Handled rather than refused, because it is common and unambiguous.
     */
    @Test
    public void stackedLabelsBecomeOneArrowLabel() {
        Assume.assumeTrue("this band cannot parse arrow labels", newestLevel() >= 14);
        String source = ""
                + "public class Script {\n"
                + "    void go(int n) {\n"
                + "        switch (n) {\n"
                + "            case 1:\n"
                + "            case 2:\n"
                + "                System.out.println(12);\n"
                + "                break;\n"
                + "            default:\n"
                + "                System.out.println(0);\n"
                + "                break;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        CodeAction offered = withId(actionsAtLevel(source, "switch (n)", newestLevel()),
                SwitchIntentions.TO_ARROW);
        assertNotNull("nothing offered", offered);
        assertEquals(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        switch (n) {\n"
                        + "            case 1, 2 -> System.out.println(12);\n"
                        + "            default -> System.out.println(0);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                applied(source, offered));
    }

    /**
     * <b>A branch that returns keeps its block, on one line.</b> The braces are not optional —
     * {@code case "a" -> return "first";} does not parse — but their layout is, and three lines per branch
     * defeats the reason to convert: the arrow form earns its keep by reading as a table.
     */
    @Test
    public void aReturningBranchGetsAOneLineBlock() {
        Assume.assumeTrue("this band cannot parse arrow labels", newestLevel() >= 14);
        String source = ""
                + "public class Script {\n"
                + "    String go(String s) {\n"
                + "        switch (s) {\n"
                + "            case \"a\":\n"
                + "                return \"first\";\n"
                + "            case \"b\":\n"
                + "                return \"second\";\n"
                + "        }\n"
                + "        return \"none\";\n"
                + "    }\n"
                + "}\n";
        CodeAction offered = withId(actionsAtLevel(source, "switch (s)", newestLevel()),
                SwitchIntentions.TO_ARROW);
        assertNotNull("nothing offered", offered);
        assertEquals(""
                        + "public class Script {\n"
                        + "    String go(String s) {\n"
                        + "        switch (s) {\n"
                        + "            case \"a\" -> { return \"first\"; }\n"
                        + "            case \"b\" -> { return \"second\"; }\n"
                        + "        }\n"
                        + "        return \"none\";\n"
                        + "    }\n"
                        + "}\n",
                applied(source, offered));
    }

    /** More than one statement opens the block up, because a table of one row is not a table. */
    @Test
    public void aMultiStatementBranchOpensItsBlock() {
        Assume.assumeTrue("this band cannot parse arrow labels", newestLevel() >= 14);
        String source = ""
                + "public class Script {\n"
                + "    void go(int n) {\n"
                + "        switch (n) {\n"
                + "            case 1:\n"
                + "                System.out.println(1);\n"
                + "                System.out.println(2);\n"
                + "                break;\n"
                + "            default:\n"
                + "                System.out.println(0);\n"
                + "                break;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        CodeAction offered = withId(actionsAtLevel(source, "switch (n)", newestLevel()),
                SwitchIntentions.TO_ARROW);
        assertNotNull("nothing offered", offered);
        assertEquals(""
                        + "public class Script {\n"
                        + "    void go(int n) {\n"
                        + "        switch (n) {\n"
                        + "            case 1 -> {\n"
                        + "                System.out.println(1);\n"
                        + "                System.out.println(2);\n"
                        + "            }\n"
                        + "            default -> System.out.println(0);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                applied(source, offered));
    }

    /**
     * <b>A group that genuinely falls through is refused</b>, and that is the point rather than a
     * limitation: it has no arrow form, and it is the defect the arrow form exists to prevent — so guessing
     * at it would be guessing at code that may well be a bug.
     */
    @Test
    public void aFallingThroughGroupIsRefused() {
        Assume.assumeTrue("this band cannot parse arrow labels", newestLevel() >= 14);
        String source = ""
                + "public class Script {\n"
                + "    void go(int n) {\n"
                + "        switch (n) {\n"
                + "            case 1:\n"
                + "                System.out.println(1);\n"
                + "            case 2:\n"
                + "                System.out.println(2);\n"
                + "                break;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        assertNull("this switch relies on falling through",
                withId(actionsAtLevel(source, "switch (n)", newestLevel()), SwitchIntentions.TO_ARROW));
    }

    private static List<CodeAction> actionsAtLevel(String source, String needle, int level) {
        int at = source.indexOf(needle);
        try (SourceAnalyzer.Analysis analysis = analyse("Script", source, level)) {
            return analysis.codeActionsIn(at, at + needle.length(), HOST);
        }
    }

    private static CodeAction withId(List<CodeAction> actions, String id) {
        for (CodeAction action : actions) {
            if (id.equals(action.id())) return action;
        }
        return null;
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
