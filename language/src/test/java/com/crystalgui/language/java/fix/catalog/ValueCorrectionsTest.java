package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.fix.catalog.ValueCorrections;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;
import com.crystalgui.language.java.FixFixture;

/**
 * The four repairs for something that has no value, or no declaration.
 *
 * <p>All four problems are <b>errors</b> reported with no configuration, so there is no severity table to
 * keep in step — which is what makes this family worth having despite the coverage probe never seeing one
 * of them. It walks a repository, and a repository holds only code somebody finished.</p>
 */
public class ValueCorrectionsTest extends FixFixture {

    // ── Add return statement ────────────────────────────────────────────────────────────────────

    @Test
    public void theMissingReturnIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    int total(int a) { int b = a + 1; }\n"
                        + "}\n",
                IProblem.ShouldReturnValue);
    }

    @Test
    public void aMissingReturnGetsADefaultOfTheReturnType() {
        assertFix(""
                        + "public class Script {\n"
                        + "    int total(int a) {\n"
                        + "        int b = a + 1;\n"
                        + "    }\n"
                        + "}\n",
                "int total", ValueCorrections.ADD_RETURN, ""
                        + "public class Script {\n"
                        + "    int total(int a) {\n"
                        + "        int b = a + 1;\n"
                        + "        return 0;\n"
                        + "    }\n"
                        + "}\n");
    }

    /** A reference type gets {@code null}, which is the same rule and the one people check first. */
    @Test
    public void aReferenceReturnGetsNull() {
        assertFix(""
                        + "public class Script {\n"
                        + "    String name(boolean flag) {\n"
                        + "        if (flag) { return \"a\"; }\n"
                        + "    }\n"
                        + "}\n",
                "String name", ValueCorrections.ADD_RETURN, ""
                        + "public class Script {\n"
                        + "    String name(boolean flag) {\n"
                        + "        if (flag) { return \"a\"; }\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n");
    }

    /** <b>The oracle:</b> the error is gone afterwards and no new one arrived. */
    @Test
    public void addingTheReturnResolvesIt() {
        assertResolves(""
                        + "public class Script {\n"
                        + "    long total(int a) {\n"
                        + "        int b = a + 1;\n"
                        + "    }\n"
                        + "}\n",
                "long total", ValueCorrections.ADD_RETURN, IProblem.ShouldReturnValue);
    }

    // ── Initialize variable ─────────────────────────────────────────────────────────────────────

    @Test
    public void anUninitialisedLocalIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    void go() { int a; System.out.println(a); }\n"
                        + "}\n",
                IProblem.UninitializedLocalVariable);
    }

    @Test
    public void anUninitialisedLocalGetsADefault() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { int a; System.out.println(a); }\n"
                        + "}\n",
                "println(a)", ValueCorrections.INITIALISE, ""
                        + "public class Script {\n"
                        + "    void go() { int a = 0; System.out.println(a); }\n"
                        + "}\n");
    }

    /**
     * <b>Found through the binding, not by position.</b> ECJ reports at whichever read it reached first,
     * which on a variable used several times is not the one nearest the declaration — so a fix that walked
     * backwards from the problem would edit the wrong statement, or none.
     */
    @Test
    public void theDeclarationIsFoundFromAReadThatIsNotTheFirstLine() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        String name;\n"
                        + "        System.out.println(1);\n"
                        + "        System.out.println(name);\n"
                        + "    }\n"
                        + "}\n",
                "println(name)", ValueCorrections.INITIALISE, ""
                        + "public class Script {\n"
                        + "    void go() {\n"
                        + "        String name = null;\n"
                        + "        System.out.println(1);\n"
                        + "        System.out.println(name);\n"
                        + "    }\n"
                        + "}\n");
    }

    // ── Create local variable / field ───────────────────────────────────────────────────────────

    @Test
    public void anAssignmentToAnUndeclaredNameIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    void go() { total = 5; System.out.println(total); }\n"
                        + "}\n",
                IProblem.UnresolvedVariable);
    }

    @Test
    public void anAssignmentBecomesADeclaration() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { total = 5; System.out.println(total); }\n"
                        + "}\n",
                "total = 5", ValueCorrections.CREATE_LOCAL, ""
                        + "public class Script {\n"
                        + "    void go() { int total = 5; System.out.println(total); }\n"
                        + "}\n");
    }

    /** The type comes from the right-hand side, whatever it is. */
    @Test
    public void theDeclaredTypeComesFromTheValue() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { label = \"hi\"; System.out.println(label); }\n"
                        + "}\n",
                "label = ", ValueCorrections.CREATE_LOCAL, ""
                        + "public class Script {\n"
                        + "    void go() { String label = \"hi\"; System.out.println(label); }\n"
                        + "}\n");
    }

    /** And the same assignment answers as a field, which is the other thing the author might have meant. */
    @Test
    public void theSameAssignmentAlsoOffersAField() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { total = 5; System.out.println(total); }\n"
                        + "}\n",
                "total = 5", ValueCorrections.CREATE_FIELD, ""
                        + "public class Script {\n"
                        + "    private int total;\n"
                        + "\n"
                        + "    void go() { total = 5; System.out.println(total); }\n"
                        + "}\n");
    }

    /**
     * <b>A field generated in a static method must be static.</b> Otherwise the fix trades "cannot be
     * resolved" for "cannot make a static reference to a non-static field" — a different error in the same
     * place, which reads as the fix simply not working.
     */
    @Test
    public void aFieldGeneratedFromAStaticMethodIsStatic() {
        assertFix(""
                        + "public class Script {\n"
                        + "    static void go() { total = 5; System.out.println(total); }\n"
                        + "}\n",
                "total = 5", ValueCorrections.CREATE_FIELD, ""
                        + "public class Script {\n"
                        + "    private static int total;\n"
                        + "\n"
                        + "    static void go() { total = 5; System.out.println(total); }\n"
                        + "}\n");
    }

    @Test
    public void creatingTheLocalResolvesIt() {
        assertResolves(""
                        + "public class Script {\n"
                        + "    void go() { total = 5; System.out.println(total); }\n"
                        + "}\n",
                "total = 5", ValueCorrections.CREATE_LOCAL, IProblem.UnresolvedVariable);
    }

    // ── Refusals ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A compound assignment reads before it writes</b>, so declaring the variable here would leave the
     * file just as broken — with a fix applied, which is worse than none.
     */
    @Test
    public void aCompoundAssignmentIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() { total += 5; System.out.println(total); }\n"
                        + "}\n",
                "total +=", ValueCorrections.CREATE_LOCAL,
                "`total += 5` reads total before assigning it");
    }

    /**
     * <b>A bare use carries no type.</b> It would have to be inferred from the parameter it is passed to,
     * which is the inference {@code CreateCorrections} already refuses for a lambda argument: a declaration
     * that looks finished and still does not fit is worse than no offer.
     */
    @Test
    public void aBareUseIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() { System.out.println(total); }\n"
                        + "}\n",
                "total", ValueCorrections.CREATE_LOCAL,
                "nothing here says what type total would be");
    }

    /** A {@code void} call has no type to declare, and {@code null} has none worth declaring. */
    @Test
    public void aVoidValueIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    static void nothing() { }\n"
                        + "    void go() { total = nothing(); System.out.println(total); }\n"
                        + "}\n",
                "total = nothing", ValueCorrections.CREATE_LOCAL,
                "void is not a type a variable can have");
    }
}
