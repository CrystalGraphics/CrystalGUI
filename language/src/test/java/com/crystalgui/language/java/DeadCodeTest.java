package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A branch that can never run — three shapes, three different repairs.
 *
 * <p>The diagnostic lands on the unreachable code, so "delete it" is the obvious reading and is wrong in
 * all three: what is being reported is a condition that is constant, and the repair is to collapse the
 * construct to the branch that survives.</p>
 */
public class DeadCodeTest extends FixFixture {

    /** The reported case: a ternary whose condition folds, so one branch is unreachable. */
    @Test
    public void aConstantTernaryCollapsesToTheBranchThatRuns() {
        String source = ""
                + "public class Script {\n"
                + "    String go() { return 5 > 3 ? \"yes\" : \"no\"; }\n"
                + "}\n";
        assertReported(source, IProblem.DeadCode);
        assertEquals("Simplify to '\"yes\"'",
                offered(source, "\"no\"", DeadCodeCorrections.SIMPLIFY_CONDITIONAL).title());
        assertFix(source, "\"no\"", DeadCodeCorrections.SIMPLIFY_CONDITIONAL, ""
                + "public class Script {\n"
                + "    String go() { return \"yes\"; }\n"
                + "}\n");
        assertResolves(source, "\"no\"", DeadCodeCorrections.SIMPLIFY_CONDITIONAL, IProblem.DeadCode);
    }

    /** {@code if (false)} with nothing else to run: the whole statement goes, not just its block. */
    @Test
    public void anIfThatCanNeverRunIsRemovedWhole() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        if (false) { int unreachable = 1; }\n"
                + "    }\n"
                + "}\n";
        assertEquals("Remove unreachable 'if'",
                offered(source, "int unreachable", DeadCodeCorrections.REMOVE_BRANCH).title());
        assertFix(source, "int unreachable", DeadCodeCorrections.REMOVE_BRANCH, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "int unreachable", DeadCodeCorrections.REMOVE_BRANCH, IProblem.DeadCode);
    }

    /**
     * <b>A branch with no braces, which is where this quietly did nothing.</b>
     *
     * <p>ECJ's span for {@code if (false) doThing();} covers the <em>expression</em> and stops before the
     * semicolon, so the node covering it exactly is the {@code MethodInvocation} — whose parent is the
     * {@code ExpressionStatement}, not the {@code if}. The correction asks what the dead node's parent is,
     * saw the wrong one, and declined. Every braced branch worked, because there the span is the
     * {@code Block} and a block is already a statement, so the whole suite passed over it.</p>
     */
    @Test
    public void aBracelessBranchIsFixedToo() {
        String source = """
                public class Script {
                    void go() {
                        if (false) System.out.println("never");
                    }
                }
                """;
        assertFix(source, "System.out.println(\"never\")", DeadCodeCorrections.REMOVE_BRANCH, """
                public class Script {
                    void go() {
                    }
                }
                """);
    }

    /** A dead {@code else} loses the else clause and leaves the {@code then} exactly where it was. */
    @Test
    public void aDeadElseLosesOnlyTheElse() {
        String source = ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        if (true) { return 1; } else { return 2; }\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "return 2", DeadCodeCorrections.REMOVE_BRANCH, ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        if (true) { return 1; }\n"
                + "    }\n"
                + "}\n");
    }

    /**
     * <b>The {@code if (DEBUG)} idiom is never reported, so nothing is offered for it.</b>
     *
     * <p>ECJ exempts a condition that is a {@code static final boolean} flag — the carve-out JLS 14.21
     * makes so that conditional compilation is legal. Pinned here because it is the one shape a
     * dead-code fix must never touch, and the exemption belongs to the compiler rather than to us.</p>
     */
    @Test
    public void theConditionalCompilationIdiomIsLeftAlone() {
        String source = ""
                + "public class Script {\n"
                + "    static final boolean DEBUG = false;\n"
                + "    void go() {\n"
                + "        if (DEBUG) { int trace = 1; }\n"
                + "    }\n"
                + "}\n";
        assertNull("a static final flag is a technique, not scaffolding",
                offered(source, "int trace", DeadCodeCorrections.REMOVE_BRANCH));
    }
}
