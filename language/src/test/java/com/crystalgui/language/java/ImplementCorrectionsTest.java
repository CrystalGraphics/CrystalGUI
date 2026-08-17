package com.crystalgui.language.java;

import com.crystalgui.language.java.fix.catalog.ImplementCorrections;
import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * "Implement methods" — the gap the coverage probe promoted by growing when the classpath resolved.
 *
 * <p>An error reported with no configuration, and reported <b>once per missing method</b> — which is what
 * most of these assertions are really about: one action for the type, not one per problem, and either every
 * method or none.</p>
 */
public class ImplementCorrectionsTest extends FixFixture {

    private static final String GREETER = ""
            + "public class Script {\n"
            + "    interface Greeter { String greet(String name); int count(); }\n";

    @Test
    public void theProblemIsReported() {
        assertReported(GREETER + "    static class Impl implements Greeter { }\n}\n",
                IProblem.AbstractMethodMustBeImplemented);
    }

    /**
     * <b>Every missing method, in one action, with the interface's own parameter names.</b> A binding
     * carries no parameter names — they are not in bytecode unless the class was compiled with
     * {@code -parameters} — but a script's interfaces are declared beside it, where the real names are
     * sitting in the tree.
     */
    @Test
    public void everyMissingMethodIsWrittenAtOnce() {
        assertFix(GREETER
                        + "    static class Impl implements Greeter {\n"
                        + "    }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT, GREETER
                        + "    static class Impl implements Greeter {\n"
                        + "\n"
                        + "        @Override\n"
                        + "        public String greet(String name) {\n"
                        + "            return null;\n"
                        + "        }\n"
                        + "\n"
                        + "        @Override\n"
                        + "        public int count() {\n"
                        + "            return 0;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>One action, not one per problem.</b> ECJ reports this once per missing method, so without the
     * claim the popup would offer the same edit twice for a class missing two methods.
     */
    @Test
    public void twoMissingMethodsStillOfferOneAction() {
        int offered = 0;
        for (CodeAction action : actionsIn(GREETER
                + "    static class Impl implements Greeter { }\n"
                + "}\n", "class Impl")) {
            if (ImplementCorrections.IMPLEMENT.equals(action.id())) offered++;
        }
        assertEquals("two problems must not become two identical rows", 1, offered);
    }

    /** Only what is actually missing — a method already written is not written again. */
    @Test
    public void anAlreadyImplementedMethodIsLeftAlone() {
        assertFix(GREETER
                        + "    static class Impl implements Greeter {\n"
                        + "        @Override public int count() { return 1; }\n"
                        + "    }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT, GREETER
                        + "    static class Impl implements Greeter {\n"
                        + "        @Override public int count() { return 1; }\n"
                        + "\n"
                        + "        @Override\n"
                        + "        public String greet(String name) {\n"
                        + "            return null;\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    /** <b>The oracle:</b> the class compiles afterwards. */
    @Test
    public void implementingThemResolvesIt() {
        assertResolves(GREETER
                        + "    static class Impl implements Greeter { }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT,
                IProblem.AbstractMethodMustBeImplemented);
    }

    /**
     * <b>A generic SUPERTYPE is not a problem.</b> {@code Comparator<String>} hands over a method binding
     * whose types are already substituted, so the signature is spellable and the fix fires. Worth pinning
     * beside the refusal below, because the two look alike and only one of them is unwritable.
     */
    @Test
    public void aParameterisedSupertypeIsImplemented() {
        assertResolves(""
                        + "public class Script {\n"
                        + "    interface Box<T> { T get(); }\n"
                        + "    static class Strings implements Box<String> { }\n"
                        + "}\n",
                "class Strings", ImplementCorrections.IMPLEMENT,
                IProblem.AbstractMethodMustBeImplemented);
    }

    /**
     * <b>Refused whole when one method cannot be written.</b> A method with its <em>own</em> type
     * parameters declares the variable itself, and there is no source form to write it as. Implementing the
     * other one and stopping would leave a class that still does not compile and now looks finished.
     */
    @Test
    public void aGenericMethodRefusesTheWholeAction() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    interface Factory { <T> T make(); int count(); }\n"
                        + "    static class Impl implements Factory { }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT,
                "<T> T make() has no source form, and half a class is not a fix");
    }

    /**
     * <b>Nothing is generated from a type the compiler could not see</b> — and the corpus is what found it.
     *
     * <p>A <em>recovered</em> binding is JDT's stand-in for a name it failed to resolve, and it still
     * answers {@code getQualifiedName()} — so a stub built from one is written against a type that does not
     * exist, trading "must implement these methods" for one unresolvable reference per parameter. Five
     * files gained between six and sixteen errors each from a fix that was correct in every fixture,
     * because a fixture is written by someone who knows what the types are.</p>
     */
    @Test
    public void anUnresolvableTypeInTheSignatureRefusesTheWholeAction() {
        assertNoFix(""
                        + "public class Script {\n"
                        + "    interface Greeter { NoSuchType greet(); int count(); }\n"
                        + "    static class Impl implements Greeter { }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT,
                "a stub written against a type the compiler could not resolve resolves to nothing either");
    }

    /** And the same, one level in: {@code List<NoSuchType>} resolves as a List and is no more writable. */
    @Test
    public void anUnresolvableTypeArgumentIsRefusedToo() {
        assertNoFix(""
                        + "import java.util.List;\n"
                        + "public class Script {\n"
                        + "    interface Greeter { List<NoSuchType> all(); }\n"
                        + "    static class Impl implements Greeter { }\n"
                        + "}\n",
                "class Impl", ImplementCorrections.IMPLEMENT,
                "the outer type resolving says nothing about its arguments");
    }
}
