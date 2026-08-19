package com.crystalgui.language.java.assist;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.SymbolInfo;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * M13 §25.2 — the header transform.
 *
 * <h3>Two halves, and the second one is the exit criterion</h3>
 *
 * <p>The scanner cases below run everywhere and pin the four places a brace can appear. But a transform
 * whose contract is <i>"the output is still Java"</i> cannot be proved by reading the output: the
 * assertion has to be that <b>a compiler accepts it</b> and that a declaration quoted out of the result is
 * character-for-character the one quoted out of the original. Those need an engine, so they are
 * {@code Assume}-gated exactly as every other engine test here is.</p>
 *
 * <p>{@code optionalProblemsAnalysed()} is the published signal for "this file parsed" — ECJ marks a unit
 * with a syntax error {@code ignoreFurtherInvestigation} and skips the passes that produce unused-import
 * and unused-local warnings, so its own answer about whether it ran them is the honest question. A
 * stripped unit still carries <em>semantic</em> errors (a non-void method whose body has gone has no
 * return), and that is the distinction the transform is designed around.</p>
 */
public class SourceHeadersTest {

    private JavaEngine engine;

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    // ── The scanner ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMethodBodyIsEmptiedAndItsJavadocIsKept() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    /** Adds. */\n"
                + "    int add(int a, int b) {\n"
                + "        int sum = a + b;\n"
                + "        return sum;\n"
                + "    }\n"
                + "}\n");
        assertTrue("the javadoc is the payload; it must survive", stripped.contains("/** Adds. */"));
        assertTrue("the declaration is quoted from this", stripped.contains("int add(int a, int b) {}"));
        assertFalse("the body is what we came for", stripped.contains("return sum"));
        assertTrue("the type body is descended into, not emptied", stripped.contains("class A {"));
    }

    /**
     * <b>An annotation's array argument is not a body.</b>
     *
     * <p>{@code @Target(&#123;METHOD, FIELD&#125;)} is the one place a brace appears inside a header, which
     * is why {@code JavaSignatures.quotedHeaderOf} starts its own scan after the modifier list. Emptied
     * here it would silently retarget the annotation.</p>
     */
    @Test
    public void anAnnotationArrayArgumentSurvives() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    @SafeVarargs\n"
                + "    @Target({ElementType.METHOD, ElementType.FIELD})\n"
                + "    void f() { g(); }\n"
                + "}\n");
        assertTrue(stripped.contains("@Target({ElementType.METHOD, ElementType.FIELD})"));
        assertTrue(stripped.contains("void f() {}"));
    }

    /** {@code int[] x = &#123;&#125;} is legal, so the array initializer needs no rule of its own. */
    @Test
    public void anArrayInitializerIsEmptiedAndStaysLegal() {
        String stripped = SourceHeaders.strip("class A { static final int[] X = {1, 2, 3}; }\n");
        assertTrue(stripped, stripped.contains("static final int[] X = {};"));
    }

    /** {@code A &#123;&#125;} is an enum constant with an empty body — same rule, no special case. */
    @Test
    public void anEnumConstantBodyIsEmptied() {
        String stripped = SourceHeaders.strip(""
                + "enum E {\n"
                + "    A { void f() { one(); } },\n"
                + "    B;\n"
                + "    void f() { two(); }\n"
                + "}\n");
        assertTrue(stripped, stripped.contains("A {},"));
        assertTrue(stripped, stripped.contains("void f() {}"));
        assertFalse(stripped, stripped.contains("one()"));
    }

    @Test
    public void anAnonymousClassBodyIsEmptied() {
        String stripped = SourceHeaders.strip(
                "class A { Runnable r = new Runnable() { public void run() { go(); } }; }\n");
        assertTrue(stripped, stripped.contains("new Runnable() {};"));
    }

    /**
     * <b>A parameter named {@code record} must not open a type body.</b>
     *
     * <p>The load-bearing case, and the reason a keyword is only believed when a name follows it.
     * Believed unconditionally, the method body is kept and then scanned as if its statements were
     * declarations — so {@code if (x) &#123; … &#125;} becomes {@code if (x) &#123;&#125;} and the output
     * is neither the original nor a header.</p>
     */
    @Test
    public void aContextualKeywordUsedAsAnIdentifierIsNotADeclaration() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    void f(Object record) {\n"
                + "        if (record != null) { use(record); }\n"
                + "    }\n"
                + "}\n");
        assertTrue(stripped, stripped.contains("void f(Object record) {}"));
        assertFalse("the body was scanned as declarations", stripped.contains("if (record"));
    }

    /** {@code String.class} puts the keyword where no declaration is; the next token settles it. */
    @Test
    public void aClassLiteralDoesNotOpenATypeBody() {
        String stripped = SourceHeaders.strip(
                "class A { void f() { Class<?> c = String.class; log(c); } }\n");
        assertTrue(stripped, stripped.contains("void f() {}"));
        assertFalse(stripped, stripped.contains("String.class"));
    }

    /** A brace inside a literal is text. All three walks share one literal scanner so they agree. */
    @Test
    public void bracesInsideLiteralsAndCommentsAreNotStructure() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    static final String S = \"} not a brace {\";\n"
                + "    // } not a brace either\n"
                + "    void f() { emit(\"}\"); }\n"
                + "    int g() { return 1; }\n"
                + "}\n");
        assertTrue(stripped, stripped.contains("\"} not a brace {\""));
        assertTrue("the scan stayed in step", stripped.contains("int g() {}"));
    }

    @Test
    public void aTextBlockIsOneLiteralAndNotAWallOfStructure() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    static final String S = \"\"\"\n"
                + "        { still text }\n"
                + "        \"\"\";\n"
                + "    int g() { return 1; }\n"
                + "}\n");
        assertTrue(stripped, stripped.contains("{ still text }"));
        assertTrue("the scan stayed in step past the text block", stripped.contains("int g() {}"));
    }

    /**
     * <b>An empty string beside another quote is not a text block opener.</b>
     *
     * <p>Without the JLS's own rule — the delimiter is followed by whitespace and a line terminator —
     * {@code "" + ""} scans as an unterminated text block and the rest of the file disappears into it.</p>
     */
    @Test
    public void anEmptyStringIsNotATextBlock() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    static final String S = \"\" + \"\";\n"
                + "    int g() { return 1; }\n"
                + "}\n");
        assertTrue(stripped, stripped.contains("int g() {}"));
    }

    /** Text this cannot make sense of comes back with what preceded it intact. */
    @Test
    public void anUnbalancedBraceKeepsWhatCameBeforeIt() {
        String stripped = SourceHeaders.strip(""
                + "class A {\n"
                + "    /** Kept. */\n"
                + "    int add(int a) { return a;\n");
        assertTrue(stripped, stripped.contains("/** Kept. */"));
        assertTrue(stripped, stripped.contains("int add(int a) {}"));
    }

    @Test
    public void packageAndImportsSurvive() {
        String stripped = SourceHeaders.strip(""
                + "package java.util;\n"
                + "import java.io.Serializable;\n"
                + "public interface List<E> extends Collection<E> {\n"
                + "    boolean add(E e);\n"
                + "    default void clear() { removeAll(this); }\n"
                + "}\n");
        assertTrue(stripped.contains("package java.util;"));
        assertTrue(stripped.contains("import java.io.Serializable;"));
        assertTrue(stripped.contains("public interface List<E> extends Collection<E> {"));
        assertTrue("an abstract method has no body to cut", stripped.contains("boolean add(E e);"));
        assertTrue(stripped.contains("default void clear() {}"));
    }

    // ── The exit criterion: it still compiles, and it still quotes the same ──────────────────────

    /**
     * <b>Stripped source parses, and the declaration it quotes is unchanged.</b>
     *
     * <p>Java 8 constructs only, so this runs on every band. {@code aModernFixtureAlsoSurvives} is the
     * same assertion for the syntax §25.2 was written about and is gated on a band that can read it.</p>
     */
    @Test
    public void strippedSourceStillParsesAndStillQuotesTheSameDeclaration() throws Exception {
        String source = ""
                + "public class Script {\n"
                + "    /** Picks whichever is bigger. */\n"
                + "    public static <T extends Comparable<T>> T larger(T left, T right) {\n"
                + "        return left.compareTo(right) >= 0 ? left : right;\n"
                + "    }\n"
                + "}\n";
        assertQuotesIdentically(source, "larger", 8);
    }

    /** The record and the sealed interface §25.2 names — on a band whose JDT can read them. */
    @Test
    public void aModernFixtureAlsoSurvives() throws Exception {
        Assume.assumeTrue("needs a band whose JDT knows records and sealed types",
                EngineBand.detect().minimumFeatureVersion() >= 17);
        String source = ""
                + "public class Script {\n"
                + "    /** A point. */\n"
                + "    public record Point(int x, int y) {\n"
                + "        public Point {\n"
                + "            if (x < 0) throw new IllegalArgumentException();\n"
                + "        }\n"
                + "        int sum() { return x + y; }\n"
                + "    }\n"
                + "    public sealed interface Shape permits Round {}\n"
                + "    public record Round(double r) implements Shape {}\n"
                + "}\n";
        assertQuotesIdentically(source, "Point", 17);
    }

    private void assertQuotesIdentically(String source, String needle, int level) throws Exception {
        SourceAnalyzer analyzer = openAnalyzer();
        Assume.assumeNotNull(analyzer);

        String stripped = SourceHeaders.strip(source);
        SourceAnalyzer.Analysis before = analyzer.analyze("Script", source, List.of(), level, 1L);
        SourceAnalyzer.Analysis after = analyzer.analyze("Script", stripped, List.of(), level, 2L);

        // "IT PARSED" ASKED OF THE COMPILER. A unit with a syntax error is marked
        // ignoreFurtherInvestigation and never reaches the optional passes, so this is the one answer
        // that cannot be confused with "it parsed and then resolved badly" -- which a stripped unit
        // legitimately does, because a non-void method with no body has no return.
        assertTrue("the stripped source did not parse:\n" + stripped, after.optionalProblemsAnalysed());

        SymbolInfo original = before.resolveAt(source.indexOf(needle));
        SymbolInfo quoted = after.resolveAt(stripped.indexOf(needle));
        assertNotNull("nothing resolved at " + needle + " in the original", original);
        assertNotNull("nothing resolved at " + needle + " in the stripped copy", quoted);
        assertNotNull(original.signature());
        assertNotNull(quoted.signature());
        assertEquals("the quoted declaration changed",
                original.signature().text(), quoted.signature().text());
    }

    /** Null when no engine jars were staged, which {@code Assume} then turns into a skip. */
    private SourceAnalyzer openAnalyzer() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        if (source.jarsFor(band).isEmpty()) return null;
        engine = JavaEngine.open(band, source);
        return engine.analyzer();
    }
}
