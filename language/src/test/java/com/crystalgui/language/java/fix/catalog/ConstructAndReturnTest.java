package com.crystalgui.language.java.fix.catalog;

import com.crystalgui.language.java.fix.catalog.CastCorrections;
import com.crystalgui.language.java.fix.catalog.CreateCorrections;
import com.crystalgui.language.java.fix.catalog.ValueCorrections;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;
import com.crystalgui.language.java.FixFixture;

/**
 * Batch E — the four errors left with no answer once the measured coverage was closed.
 *
 * <p>All are reported with no configuration, so there is no severity table to keep in step. What each one
 * is really about is a refusal: which signature may be edited, which value may be thrown away, and when a
 * {@code final} field may be given one.</p>
 */
public class ConstructAndReturnTest extends FixFixture {

    // ── Create constructor ──────────────────────────────────────────────────────────────────────

    @Test
    public void theUndefinedConstructorIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    static class Box { }\n"
                        + "    void go() { Box b = new Box(1, \"a\"); System.out.println(b); }\n"
                        + "}\n",
                IProblem.UndefinedConstructor);
    }

    /** Parameters from the argument types, and it lands above the methods rather than below them. */
    @Test
    public void aConstructorIsGeneratedFromTheCall() {
        assertFix(""
                        + "public class Script {\n"
                        + "    static class Box {\n"
                        + "        int size;\n"
                        + "    }\n"
                        + "    void go() { Box b = new Box(1, \"a\"); System.out.println(b); }\n"
                        + "}\n",
                "new Box(1, \"a\")", CreateCorrections.CREATE_CONSTRUCTOR, ""
                        + "public class Script {\n"
                        + "    static class Box {\n"
                        + "        int size;\n"
                        + "\n"
                        + "        public Box(int i, String string) {\n"
                        + "        }\n"
                        + "    }\n"
                        + "    void go() { Box b = new Box(1, \"a\"); System.out.println(b); }\n"
                        + "}\n");
    }

    @Test
    public void creatingTheConstructorResolvesIt() {
        assertResolves(""
                        + "public class Script {\n"
                        + "    static class Box { }\n"
                        + "    void go() { Box b = new Box(1, \"a\"); System.out.println(b); }\n"
                        + "}\n",
                "new Box(1, \"a\")", CreateCorrections.CREATE_CONSTRUCTOR, IProblem.UndefinedConstructor);
    }

    /** A type from a jar is a second file, which is the one thing a {@code ChangeSet} cannot be. */
    @Test
    public void aTypeFromElsewhereIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    void go() { String s = new String(1, 2, 3); System.out.println(s); }\n"
                        + "}\n",
                "new String(1, 2, 3)", CreateCorrections.CREATE_CONSTRUCTOR,
                "String is not declared in this file");
    }

    /**
     * <b>A lambda argument has no type of its own</b> — it takes one from the parameter it is passed to, and
     * that parameter is exactly what does not exist yet. The same refusal create-method already makes.
     */
    @Test
    public void aLambdaArgumentIsRefused() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    static class Box { }\n"
                        + "    void go() { Box b = new Box(() -> { }); System.out.println(b); }\n"
                        + "}\n",
                "new Box(", CreateCorrections.CREATE_CONSTRUCTOR,
                "nothing here says what functional interface that lambda is");
    }

    // ── Change return type / remove the value ───────────────────────────────────────────────────

    @Test
    public void aVoidMethodReturningAValueIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    void go() { return 5; }\n"
                        + "}\n",
                IProblem.VoidMethodReturnsValue);
    }

    /**
     * <b>A return type may be changed where a parameter type may not.</b> Widening {@code void} is
     * source-compatible for every existing call — a call whose result is discarded is a legal statement
     * whatever the method returns — so nothing that compiled stops compiling.
     */
    @Test
    public void theReturnTypeIsWidenedToTheValue() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { return 5; }\n"
                        + "}\n",
                "return 5", CastCorrections.CHANGE_RETURN_TYPE, ""
                        + "public class Script {\n"
                        + "    int go() { return 5; }\n"
                        + "}\n");
    }

    /** And the other answer, because the code cannot say which was meant. */
    @Test
    public void theValueCanBeDroppedInstead() {
        assertFix(""
                        + "public class Script {\n"
                        + "    void go() { return 5; }\n"
                        + "}\n",
                "return 5", CastCorrections.DROP_RETURNED_VALUE, ""
                        + "public class Script {\n"
                        + "    void go() { return; }\n"
                        + "}\n");
    }

    /**
     * <b>A call is not a value to throw away.</b> {@code return compute();} discards the result and keeps
     * the work; deleting the invocation deletes the work too — the same rule the unused-assignment fix is
     * refused under.
     */
    @Test
    public void aReturnedCallIsNotDropped() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    static int compute() { return 1; }\n"
                        + "    void go() { return compute(); }\n"
                        + "}\n",
                "return compute()", CastCorrections.DROP_RETURNED_VALUE,
                "deleting the call would delete its side effect");
    }

    // ── Initialize a blank final field ──────────────────────────────────────────────────────────

    @Test
    public void theBlankFinalFieldIsReported() {
        assertReported(""
                        + "public class Script {\n"
                        + "    final int size;\n"
                        + "    Script() { }\n"
                        + "}\n",
                IProblem.UninitializedBlankFinalField);
    }

    /**
     * <b>Reported on the constructor, answered at the declaration.</b> A type with three constructors
     * reports three problems, and one value at the declaration answers all of them.
     */
    @Test
    public void aBlankFinalFieldGetsADefault() {
        assertFix(""
                        + "public class Script {\n"
                        + "    final int size;\n"
                        + "    Script() { }\n"
                        + "}\n",
                "Script()", ValueCorrections.INITIALISE_FIELD, ""
                        + "public class Script {\n"
                        + "    final int size = 0;\n"
                        + "    Script() { }\n"
                        + "}\n");
    }

    /**
     * <b>Refused when some constructor already assigns it.</b> A {@code final} field may be assigned once,
     * so initialising at the declaration would turn "may not have been initialized" into "may already have
     * been assigned" — a different error, in the constructor that was previously correct.
     */
    @Test
    public void aFieldAnotherConstructorAssignsIsLeftAlone() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    final int size;\n"
                        + "    Script() { }\n"
                        + "    Script(int n) { size = n; }\n"
                        + "}\n",
                "Script() {", ValueCorrections.INITIALISE_FIELD,
                "the other constructor already assigns it exactly once");
    }

    /**
     * <b>And a LOCAL of the same name is not that constructor.</b> The refusal above was asked by name over
     * the whole type, so any method with a variable spelled like the field answered for it — and the fix
     * went missing for a field nothing had ever assigned. The name is the one thing the two have in common
     * and is exactly what does not identify a field.
     */
    @Test
    public void aLocalSharingTheFieldsNameDoesNotCountAsAssigningIt() {
        assertFix(""
                        + "public class Script {\n"
                        + "    final int size;\n"
                        + "    Script() { }\n"
                        + "    void go() {\n"
                        + "        int size = 3;\n"
                        + "        System.out.println(size);\n"
                        + "    }\n"
                        + "}\n",
                "Script()", ValueCorrections.INITIALISE_FIELD, ""
                        + "public class Script {\n"
                        + "    final int size = 0;\n"
                        + "    Script() { }\n"
                        + "    void go() {\n"
                        + "        int size = 3;\n"
                        + "        System.out.println(size);\n"
                        + "    }\n"
                        + "}\n");
    }
}
