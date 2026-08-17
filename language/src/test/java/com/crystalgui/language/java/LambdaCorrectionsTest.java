package com.crystalgui.language.java;

import com.crystalgui.language.java.fix.catalog.LambdaCorrections;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * "Replace with lambda", and the far longer list of things that must not be.
 *
 * <h3>Why the refusals outnumber the conversions here</h3>
 *
 * <p>Every other correction in this package is gated by ECJ having already said something is wrong. No
 * compiler says anything about a convertible anonymous class, so this one is gated <b>only by its own
 * judgement</b> — and the analyser reports the site from that same judgement, so a mistake here is a
 * squiggle promising a conversion the popup then declines to make. The refusals are the specification;
 * the conversions are the easy half.</p>
 */
public class LambdaCorrectionsTest extends FixFixture {

    /** The caret goes on the header — `new Comparator<String>()` — which is where this is offered. */
    private static final String HEADER = "new Comparator<String>()";

    private static String comparator(String body) {
        return ""
                + "import java.util.Comparator;\n"
                + "public class Script {\n"
                + "    static Comparator<String> make() {\n"
                + "        return new Comparator<String>() {\n"
                + body
                + "        };\n"
                + "    }\n"
                + "}\n";
    }

    private static void refuses(String source, String why) {
        refuses(source, HEADER, why);
    }

    /** For the cases whose fixture is not a {@code Comparator} — the caret still goes on the header. */
    private static void refuses(String source, String header, String why) {
        assertNoFix(source, header, LambdaCorrections.FROM_ANONYMOUS, why);
    }

    // ── The conversion ──────────────────────────────────────────────────────────────────────────

    /** The shape that prompted this: two statements, so the braces stay. */
    @Test
    public void aMultiStatementBodyKeepsItsBlock() {
        assertFix(comparator(""
                        + "            @Override\n"
                        + "            public int compare(String left, String right) {\n"
                        + "                int byLength = Integer.compare(left.length(), right.length());\n"
                        + "                return byLength != 0 ? byLength : left.compareTo(right);\n"
                        + "            }\n"),
                HEADER, LambdaCorrections.FROM_ANONYMOUS, ""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        return (left, right) -> {\n"
                        + "                int byLength = Integer.compare(left.length(), right.length());\n"
                        + "                return byLength != 0 ? byLength : left.compareTo(right);\n"
                        + "            };\n"
                        + "    }\n"
                        + "}\n");
    }

    /** A single {@code return} collapses to the expression form. */
    @Test
    public void aSingleReturnCollapsesToAnExpression() {
        assertFix(comparator(""
                        + "            public int compare(String left, String right) {\n"
                        + "                return left.compareTo(right);\n"
                        + "            }\n"),
                HEADER, LambdaCorrections.FROM_ANONYMOUS, ""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        return (left, right) -> left.compareTo(right);\n"
                        + "    }\n"
                        + "}\n");
    }

    /** Titled as IntelliJ titles it, and a fix for the finding the analyser reports beside it. */
    @Test
    public void itIsOfferedAsAQuickFix() {
        CodeAction action = offered(comparator(""
                + "            public int compare(String left, String right) { return 0; }\n"),
                HEADER, LambdaCorrections.FROM_ANONYMOUS);
        assertNotNull("nothing was offered at all", action);
        assertEquals("Replace with lambda", action.title());
        // A QUICK_FIX, because the analyser reports the site: the kind is about what an action ANSWERS,
        // and the popup's inline slot is reserved for the fix to the message above it.
        assertEquals(CodeActionKind.QUICK_FIX, action.kind());
    }

    /**
     * <b>On the header, and not on the body.</b> An intention offered anywhere inside a long anonymous
     * class is in every popup that class contains, competing with the fixes for the real problems there.
     */
    @Test
    public void itIsNotOfferedFromInsideTheBody() {
        assertNoFix(comparator(""
                + "            public int compare(String left, String right) {\n"
                + "                return left.compareTo(right);\n"
                + "            }\n"),
                "left.compareTo(right)", LambdaCorrections.FROM_ANONYMOUS,
                "the body is where the fixes for real problems live");
    }

    // ── The repairs ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A clashing parameter is renamed, not refused.</b> Measured: a lambda's parameters live in the
     * enclosing scope, so keeping the name gives "cannot redeclare another local variable defined in an
     * enclosing scope" — on code that compiled perfectly well as an anonymous class.
     */
    @Test
    public void aParameterThatWouldShadowIsRenamed() {
        assertFix(""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        String left = \"outer\";\n"
                        + "        System.out.println(left);\n"
                        + "        return new Comparator<String>() {\n"
                        + "            public int compare(String left, String right) {\n"
                        + "                return left.compareTo(right);\n"
                        + "            }\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n",
                HEADER, LambdaCorrections.FROM_ANONYMOUS, ""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        String left = \"outer\";\n"
                        + "        System.out.println(left);\n"
                        + "        return (left1, right) -> left1.compareTo(right);\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>And a clashing body LOCAL is renamed too</b>, which neither reference's published list mentions
     * and which fails identically. One mechanism covers both: the declaration is resolved to its binding
     * and every name resolving to it is rewritten together.
     */
    @Test
    public void aBodyLocalThatWouldShadowIsRenamed() {
        assertFix(""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        int tally = 1;\n"
                        + "        System.out.println(tally);\n"
                        + "        return new Comparator<String>() {\n"
                        + "            public int compare(String a, String b) {\n"
                        + "                int tally = 2;\n"
                        + "                return tally;\n"
                        + "            }\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n",
                HEADER, LambdaCorrections.FROM_ANONYMOUS, ""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    static Comparator<String> make() {\n"
                        + "        int tally = 1;\n"
                        + "        System.out.println(tally);\n"
                        + "        return (a, b) -> {\n"
                        + "                int tally1 = 2;\n"
                        + "                return tally1;\n"
                        + "            };\n"
                        + "    }\n"
                        + "}\n");
    }

    /**
     * <b>An ambiguous argument gets the type written back in.</b> Two interfaces of the same shape leave
     * the bare lambda ambiguous — the anonymous form named its type and a lambda does not.
     */
    @Test
    public void anAmbiguousArgumentIsCast() {
        assertFix(""
                        + "public class Script {\n"
                        + "    interface F1 { int f(String a, String b); }\n"
                        + "    interface F2 { int g(String a, String b); }\n"
                        + "    static void take(F1 f) { }\n"
                        + "    static void take(F2 f) { }\n"
                        + "    static void go() {\n"
                        + "        take(new F1() {\n"
                        + "            public int f(String a, String b) { return 0; }\n"
                        + "        });\n"
                        + "    }\n"
                        + "}\n",
                "new F1()", LambdaCorrections.FROM_ANONYMOUS, ""
                        + "public class Script {\n"
                        + "    interface F1 { int f(String a, String b); }\n"
                        + "    interface F2 { int g(String a, String b); }\n"
                        + "    static void take(F1 f) { }\n"
                        + "    static void take(F2 f) { }\n"
                        + "    static void go() {\n"
                        + "        take((F1) (a, b) -> 0);\n"
                        + "    }\n"
                        + "}\n");
    }

    /** An argument with only one candidate keeps the lambda bare. */
    @Test
    public void anUnambiguousArgumentIsNotCast() {
        assertFix(""
                        + "public class Script {\n"
                        + "    interface F1 { int f(String a, String b); }\n"
                        + "    static void take(F1 f) { }\n"
                        + "    static void go() {\n"
                        + "        take(new F1() {\n"
                        + "            public int f(String a, String b) { return 0; }\n"
                        + "        });\n"
                        + "    }\n"
                        + "}\n",
                "new F1()", LambdaCorrections.FROM_ANONYMOUS, ""
                        + "public class Script {\n"
                        + "    interface F1 { int f(String a, String b); }\n"
                        + "    static void take(F1 f) { }\n"
                        + "    static void go() {\n"
                        + "        take((a, b) -> 0);\n"
                        + "    }\n"
                        + "}\n");
    }

    // ── The refusals ────────────────────────────────────────────────────────────────────────────

    @Test
    public void anInterfaceWithTwoAbstractMethodsIsRefused() {
        refuses(""
                + "public class Script {\n"
                + "    interface Two { void a(); void b(); }\n"
                + "    static Two make() {\n"
                + "        return new Two() {\n"
                + "            public void a() { }\n"
                + "            public void b() { }\n"
                + "        };\n"
                + "    }\n"
                + "}\n", "new Two()", "two abstract methods is not a functional interface");
    }

    /** Measured: the converted form is "Illegal lambda expression: Method make ... is generic". */
    @Test
    public void aGenericAbstractMethodIsRefused() {
        refuses(""
                + "public class Script {\n"
                + "    interface Maker { <T> T make(Class<T> type); }\n"
                + "    static Maker make() {\n"
                + "        return new Maker() {\n"
                + "            public <T> T make(Class<T> type) { return null; }\n"
                + "        };\n"
                + "    }\n"
                + "}\n", "new Maker()", "a lambda cannot declare type parameters");
    }

    @Test
    public void anExtraFieldIsRefused() {
        refuses(comparator(""
                + "            private int calls;\n"
                + "            public int compare(String a, String b) { return ++calls; }\n"),
                "a lambda has nowhere to keep state");
    }

    /** Unqualified {@code this} is the enclosing instance inside a lambda — a different object. */
    @Test
    public void anUnqualifiedThisIsRefused() {
        refuses(comparator(""
                + "            public int compare(String a, String b) { return this.hashCode(); }\n"),
                "`this` would stop meaning the anonymous object");
    }

    /** A lambda has no name, so it cannot call itself. */
    @Test
    public void aRecursiveCallIsRefused() {
        refuses(comparator(""
                + "            public int compare(String a, String b) {\n"
                + "                return a.isEmpty() ? 0 : compare(b, a);\n"
                + "            }\n"),
                "a lambda cannot call itself");
    }

    /** The doc would be dropped, and a lambda has nowhere to put one. */
    @Test
    public void aDocumentedMethodIsRefused() {
        refuses(comparator(""
                + "            /** Orders by length. */\n"
                + "            public int compare(String a, String b) { return 0; }\n"),
                "the javadoc would be lost");
    }

    /** {@code synchronized} cannot be expressed on a lambda. */
    @Test
    public void aSynchronizedMethodIsRefused() {
        refuses(comparator(""
                + "            public synchronized int compare(String a, String b) { return 0; }\n"),
                "a lambda cannot be synchronized");
    }

    /** Anything the class file keeps is lost; only {@code @Override} is safe to drop. */
    @Test
    public void aRetainedAnnotationIsRefused() {
        refuses(comparator(""
                + "            @Deprecated\n"
                + "            public int compare(String a, String b) { return 0; }\n"),
                "@Deprecated survives compilation and a lambda cannot carry it");
    }

    /**
     * <b>No target type, no lambda.</b> An anonymous class carries its own type and can stand anywhere; a
     * lambda is a poly expression, so a receiver position has nothing for it to be.
     */
    @Test
    public void aReceiverPositionIsRefused() {
        refuses(""
                + "public class Script {\n"
                + "    static void go() {\n"
                + "        new Runnable() { public void run() { } }.run();\n"
                + "    }\n"
                + "}\n", "new Runnable()", "a receiver has no target type");
    }

    /** A qualified {@code Outer.this} already meant the enclosing instance — measured to convert fine. */
    @Test
    public void aQualifiedOuterThisIsAllowed() {
        assertFix(""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    int rank = 1;\n"
                        + "    Comparator<String> make() {\n"
                        + "        return new Comparator<String>() {\n"
                        + "            public int compare(String a, String b) { return Script.this.rank; }\n"
                        + "        };\n"
                        + "    }\n"
                        + "}\n",
                HEADER, LambdaCorrections.FROM_ANONYMOUS, ""
                        + "import java.util.Comparator;\n"
                        + "public class Script {\n"
                        + "    int rank = 1;\n"
                        + "    Comparator<String> make() {\n"
                        + "        return (a, b) -> Script.this.rank;\n"
                        + "    }\n"
                        + "}\n");
    }
    // ── What the analyser reports ───────────────────────────────────────────────────────────────

    private static final String ONE_SITE = ""
            + "            public int compare(String left, String right) { return 0; }\n";

    /**
     * <b>The site is reported, not merely fixable.</b> This shipped as an intention with no diagnostic, on
     * the measurement that no compiler emits one — true, and the wrong conclusion drawn from it. IntelliJ
     * lists it as a warning beside "Class 'Inner' is never used", because a refactor nobody can see is a
     * refactor nobody applies.
     */
    @Test
    public void aConvertibleSiteIsReported() {
        List<Diagnostic> found = diagnosticsOf(comparator(ONE_SITE));
        assertEquals("one finding for one convertible anonymous class", 1, found.size());
        assertEquals("Anonymous new Comparator<String>() can be replaced with lambda",
                found.get(0).message());
    }

    /**
     * <b>Faded, not underlined</b> — the drawing the unused family already gets, and IntelliJ's own for
     * this inspection. A yellow squiggle under every anonymous class in a file would be the loudest thing
     * on screen for something nobody has to act on.
     */
    @Test
    public void theReportIsDrawnAsDeadWeightRatherThanADefect() {
        List<Diagnostic> found = diagnosticsOf(comparator(ONE_SITE));
        assertTrue("the header is ceremony the lambda does without, so it fades",
                found.get(0).hasTag(DiagnosticTag.UNNECESSARY));
    }

    /**
     * <b>And a refusal is not reported either.</b> The mark and the fix come from one call, which is what
     * stops a squiggle promising a conversion the popup then declines to make.
     */
    @Test
    public void aRefusedSiteIsNotReported() {
        assertTrue("nothing convertible here", diagnosticsOf(comparator(""
                + "            private int calls;\n"
                + "            public int compare(String a, String b) { return ++calls; }\n")).isEmpty());
    }
}
