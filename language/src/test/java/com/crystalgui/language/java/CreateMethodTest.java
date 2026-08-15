package com.crystalgui.language.java;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * "Create method" — a declaration generated from the shape of a use.
 *
 * <p>Every case is asserted on the exact declaration and then handed to the compiler, because a generated
 * method with one wrong parameter type is a new error where there was one before — and it looks finished.</p>
 */
public class CreateMethodTest extends FixFixture {

    @Test
    public void aBareCallCreatesAPrivateVoidMethodWithTypedNamedParameters() {
        String source = ""
                + "public class Script {\n"
                + "    void go(String label) {\n"
                + "        helper(1, label, 2.5);\n"
                + "    }\n"
                + "}\n";
        assertReported(source, IProblem.UndefinedMethod);
        assertEquals("Create method 'helper(int, String, double)'",
                offered(source, "helper(", CreateCorrections.CREATE_METHOD).title());
        assertFix(source, "helper(", CreateCorrections.CREATE_METHOD, ""
                + "public class Script {\n"
                + "    void go(String label) {\n"
                + "        helper(1, label, 2.5);\n"
                + "    }\n"
                + "\n"
                + "    private void helper(int i, String label, double d) {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "helper(", CreateCorrections.CREATE_METHOD, IProblem.UndefinedMethod);
    }

    /** Into another type declared in this file: package-private, and the return type from the initialiser. */
    @Test
    public void aCallOnAnotherTypeInThisFileCreatesIntoItWithTheAssignedReturnType() {
        String source = ""
                + "public class Script {\n"
                + "    int go(Helper h, int seed) {\n"
                + "        int r = h.compute(seed);\n"
                + "        return r;\n"
                + "    }\n"
                + "}\n"
                + "class Helper {\n"
                + "}\n";
        assertFix(source, "compute(", CreateCorrections.CREATE_METHOD, ""
                + "public class Script {\n"
                + "    int go(Helper h, int seed) {\n"
                + "        int r = h.compute(seed);\n"
                + "        return r;\n"
                + "    }\n"
                + "}\n"
                + "class Helper {\n"
                + "\n"
                + "    int compute(int seed) {\n"
                + "        return 0;\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "compute(", CreateCorrections.CREATE_METHOD, IProblem.UndefinedMethod);
    }

    @Test
    public void aCallOnATypeNameCreatesAStaticMethod() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        Helper.make();\n"
                + "    }\n"
                + "}\n"
                + "class Helper {\n"
                + "}\n";
        assertFix(source, "make(", CreateCorrections.CREATE_METHOD, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        Helper.make();\n"
                + "    }\n"
                + "}\n"
                + "class Helper {\n"
                + "\n"
                + "    static void make() {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "make(", CreateCorrections.CREATE_METHOD, IProblem.UndefinedMethod);
    }

    @Test
    public void aCallUnderAConditionReturnsBoolean() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        if (ready()) { }\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "ready(", CreateCorrections.CREATE_METHOD, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        if (ready()) { }\n"
                + "    }\n"
                + "\n"
                + "    private boolean ready() {\n"
                + "        return false;\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "ready(", CreateCorrections.CREATE_METHOD, IProblem.UndefinedMethod);
    }

    /** A parameter type from outside the file is imported; a generic one keeps its arguments. */
    @Test
    public void parameterTypesAreImportedAndGenericsAreKept() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void go(List<String> names, java.util.Map<String, Integer> counts) {\n"
                + "        take(names, counts);\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "take(", CreateCorrections.CREATE_METHOD, ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    void go(List<String> names, java.util.Map<String, Integer> counts) {\n"
                + "        take(names, counts);\n"
                + "    }\n"
                + "\n"
                + "    private void take(List<String> names, Map<String, Integer> counts) {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "take(", CreateCorrections.CREATE_METHOD, IProblem.UndefinedMethod);
    }

    /** <b>Refused for a type from a jar</b> — that would be a second file, which the carrier cannot express. */
    @Test
    public void aCallOnALibraryTypeIsRefused() {
        String source = "public class Script { void go(String s) { s.frobnicate(); } }\n";
        assertNull(offered(source, "frobnicate", CreateCorrections.CREATE_METHOD));
    }
}
