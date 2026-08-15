package com.crystalgui.language.java;

import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The unhandled-exception pair — the first corrections that generate code, so the first place the
 * substrate's formatting is what the user reads.
 *
 * <p>Every fixture here is asserted on the exact text and then handed back to the compiler, because a
 * generated {@code try} that is one brace out compiles as nothing at all.</p>
 */
public class ExceptionCorrectionsTest extends FixFixture {

    private static final String SIMPLE = ""
            + "import java.io.FileReader;\n"
            + "public class Script {\n"
            + "    void go() {\n"
            + "        new FileReader(\"x\");\n"
            + "    }\n"
            + "}\n";

    @Test
    public void bothAreOfferedAndNeitherIsPreferred() {
        assertReported(SIMPLE, IProblem.UnhandledException);
        CodeAction throwsFix = offered(SIMPLE, "new FileReader", ExceptionCorrections.ADD_THROWS);
        CodeAction tryFix = offered(SIMPLE, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH);
        assertNotNull(throwsFix);
        assertNotNull(tryFix);
        assertEquals("two answers, so neither is the default", false, throwsFix.preferred() || tryFix.preferred());
    }

    // ── throws ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void addThrowsNamesTheTypeAndImportsIt() {
        assertEquals("Add 'FileNotFoundException' to throws",
                offered(SIMPLE, "new FileReader", ExceptionCorrections.ADD_THROWS).title());
        assertFix(SIMPLE, "new FileReader", ExceptionCorrections.ADD_THROWS, ""
                + "import java.io.FileReader;\n"
                + "import java.io.FileNotFoundException;\n"
                + "public class Script {\n"
                + "    void go() throws FileNotFoundException {\n"
                + "        new FileReader(\"x\");\n"
                + "    }\n"
                + "}\n");
        assertResolves(SIMPLE, "new FileReader", ExceptionCorrections.ADD_THROWS, IProblem.UnhandledException);
    }

    @Test
    public void addThrowsAppendsToAnExistingClause() {
        String source = ""
                + "import java.io.FileReader;\n"
                + "public class Script {\n"
                + "    void go() throws InterruptedException {\n"
                + "        new FileReader(\"x\");\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "new FileReader", ExceptionCorrections.ADD_THROWS, ""
                + "import java.io.FileReader;\n"
                + "import java.io.FileNotFoundException;\n"
                + "public class Script {\n"
                + "    void go() throws InterruptedException, FileNotFoundException {\n"
                + "        new FileReader(\"x\");\n"
                + "    }\n"
                + "}\n");
    }

    /**
     * <b>Refused inside a lambda.</b> The enclosing method is not the callable that throws; a
     * {@code throws} added to it compiles and lies. Try/catch is still offered — the statement is real.
     */
    @Test
    public void addThrowsIsRefusedInsideALambdaButTryCatchIsNot() {
        String source = ""
                + "import java.io.FileReader;\n"
                + "public class Script {\n"
                + "    void go() {\n"
                + "        Runnable r = () -> {\n"
                + "            new FileReader(\"x\");\n"
                + "        };\n"
                + "    }\n"
                + "}\n";
        assertNull(offered(source, "new FileReader", ExceptionCorrections.ADD_THROWS));
        assertNotNull(offered(source, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH));
    }

    // ── try/catch ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void surroundWrapsTheStatementWithIntelliJsTemplate() {
        assertFix(SIMPLE, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH, ""
                + "import java.io.FileReader;\n"
                + "import java.io.FileNotFoundException;\n"
                + "public class Script {\n"
                + "    void go() {\n"
                + "        try {\n"
                + "            new FileReader(\"x\");\n"
                + "        } catch (FileNotFoundException e) {\n"
                + "            throw new RuntimeException(e);\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        assertResolves(SIMPLE, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH,
                IProblem.UnhandledException);
    }

    /**
     * <b>A declaration used below is split, not wrapped.</b> Wrapping would take {@code r} out of scope
     * for the line that reads it; the split keeps definite assignment satisfied because the catch
     * completes abruptly.
     */
    @Test
    public void aDeclarationUsedBelowIsSplitSoTheVariableStaysInScope() {
        String source = ""
                + "import java.io.FileReader;\n"
                + "public class Script {\n"
                + "    int go() {\n"
                + "        FileReader r = new FileReader(\"x\");\n"
                + "        return r.hashCode();\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH, ""
                + "import java.io.FileReader;\n"
                + "import java.io.FileNotFoundException;\n"
                + "public class Script {\n"
                + "    int go() {\n"
                + "        FileReader r;\n"
                + "        try {\n"
                + "            r = new FileReader(\"x\");\n"
                + "        } catch (FileNotFoundException e) {\n"
                + "            throw new RuntimeException(e);\n"
                + "        }\n"
                + "        return r.hashCode();\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH,
                IProblem.UnhandledException);
    }

    /**
     * <b>Several exceptions in one statement give one action with a multi-catch, reduced by subtyping.</b>
     * {@code new FileReader(f).read()} throws {@code FileNotFoundException} (the constructor) and
     * {@code IOException} (the read); naming both in one catch is a compile error, so only the supertype
     * is caught — and there is one "Surround" row, not two.
     */
    @Test
    public void severalExceptionsInOneStatementReduceToOneCatch() {
        String source = ""
                + "import java.io.FileReader;\n"
                + "public class Script {\n"
                + "    void go() {\n"
                + "        new FileReader(\"x\").read();\n"
                + "    }\n"
                + "}\n";
        List<CodeAction> actions = actionsIn(source, "read()");
        long surrounds = actions.stream().filter(a -> ExceptionCorrections.SURROUND_TRY_CATCH.equals(a.id())).count();
        assertEquals("one statement, one surround", 1, surrounds);
        assertFix(source, "read()", ExceptionCorrections.SURROUND_TRY_CATCH, ""
                + "import java.io.FileReader;\n"
                + "import java.io.IOException;\n"
                + "public class Script {\n"
                + "    void go() {\n"
                + "        try {\n"
                + "            new FileReader(\"x\").read();\n"
                + "        } catch (IOException e) {\n"
                + "            throw new RuntimeException(e);\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "read()", ExceptionCorrections.SURROUND_TRY_CATCH, IProblem.UnhandledException);
    }

    @Test
    public void unrelatedExceptionsBecomeAMultiCatch() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        Class.forName(\"x\").newInstance();\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "forName", ExceptionCorrections.SURROUND_TRY_CATCH, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        try {\n"
                + "            Class.forName(\"x\").newInstance();\n"
                + "        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {\n"
                + "            throw new RuntimeException(e);\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "forName", ExceptionCorrections.SURROUND_TRY_CATCH, IProblem.UnhandledException);
    }

    /** {@code e} is taken, so the catch parameter is {@code ex}. */
    @Test
    public void theCatchVariableAvoidsANameAlreadyInScope() {
        String source = ""
                + "import java.io.FileReader;\n"
                + "public class Script {\n"
                + "    void go(int e) {\n"
                + "        new FileReader(\"x\");\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "new FileReader", ExceptionCorrections.SURROUND_TRY_CATCH, ""
                + "import java.io.FileReader;\n"
                + "import java.io.FileNotFoundException;\n"
                + "public class Script {\n"
                + "    void go(int e) {\n"
                + "        try {\n"
                + "            new FileReader(\"x\");\n"
                + "        } catch (FileNotFoundException ex) {\n"
                + "            throw new RuntimeException(ex);\n"
                + "        }\n"
                + "    }\n"
                + "}\n");
    }
}
