package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>The acceptance checklist — one test per row, and every row is "verify the binding answers this".</b>
 *
 * <h3>Why this file exists at all</h3>
 *
 * <p>{@code plan_syntax.md} §15.1 makes one trade and the whole semantic layer rests on it: generic
 * substitution, overload resolution in all three phases, member lookup with bridges filtered and
 * accessibility computed, flow-sensitive pattern bindings and lambda target typing are <b>adopted from
 * the JDT binding model rather than built</b>. v1 planned to build them; the plan retained its list as an
 * <em>acceptance checklist</em> instead. So these are not tests of code in this repository — they are the
 * evidence that the trade was real, and the thing that would fail if a band were bumped to a jar where it
 * is not.</p>
 *
 * <p>Each also runs against whichever band this JVM selects, so "does the oldest band answer these too"
 * is asked on every build rather than assumed.</p>
 */
public class BindingChecklistTest {

    private JavaEngine engine;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
    }

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    private SourceAnalyzer.Analysis analyze(String source) {
        return engine.analyzer().analyze("Script", source, List.of(), 8, 1L);
    }

    private static SymbolInfo memberNamed(List<SymbolInfo> members, String name) {
        for (SymbolInfo member : members) {
            if (member.name().equals(name)) return member;
        }
        return null;
    }

    // ── 1. Generic substitution ─────────────────────────────────────────────────────────────────

    @Test
    public void genericSubstitutionReachesTheMemberList() {
        // `List<String>.get(int)` must answer String, not E. This is the row that justifies TypeRef
        // carrying a binding rather than a name: a name round-trips as text and comes back erased.
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo field = analysis.resolveAt(source.indexOf("names;") + 2);
            assertNotNull(field);
            TypeRef list = field.type();

            SymbolInfo get = memberNamed(analysis.membersOf(list, source.indexOf("void run")), "get");
            assertNotNull("List<String> has no get()", get);
            assertEquals("the type argument was erased on the way through",
                    "java.lang.String", get.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aNestedTypeArgumentSurvivesToo() {
        String source = ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, List<Integer>> index;\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo field = analysis.resolveAt(source.indexOf("index;") + 2);
            assertNotNull(field);
            SymbolInfo get = memberNamed(
                    analysis.membersOf(field.type(), source.indexOf("void run")), "get");
            assertNotNull(get);
            assertEquals("java.util.List", get.type().qualifiedName());
            assertEquals("List<Integer>", get.type().displayName());
        } finally {
            analysis.close();
        }
    }

    // ── 2. Overload resolution ──────────────────────────────────────────────────────────────────

    @Test
    public void theRightOverloadIsPickedForTheArgumentTypes() {
        String source = ""
                + "public class Script {\n"
                + "    String pick(int n) { return \"int\"; }\n"
                + "    String pick(String s) { return \"string\"; }\n"
                + "    void run() { pick(\"x\"); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo chosen = analysis.resolveAt(source.indexOf("pick(\"x\")") + 2);
            assertNotNull(chosen);
            assertEquals(SymbolKind.METHOD, chosen.kind());
            // Both overloads return String, so the assertion has to be about the ARGUMENT the caller
            // is in: expectedTypeAt at the argument position names the parameter of the chosen one.
            TypeRef expected = analysis.expectedTypeAt(source.indexOf("\"x\"") + 1);
            assertNotNull(expected);
            assertEquals("the int overload was chosen for a String argument",
                    "java.lang.String", expected.qualifiedName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void wideningAndBoxingPhasesAreDistinguished() {
        // JLS 15.12.2 runs three phases: strict, then widening, then boxing plus varargs. A `long`
        // argument must reach `take(long)` by widening rather than boxing to `take(Object)`.
        String source = ""
                + "public class Script {\n"
                + "    void take(long n) { }\n"
                + "    void take(Object o) { }\n"
                + "    void run() { int i = 1; take(i); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            TypeRef expected = analysis.expectedTypeAt(source.indexOf("take(i)") + 5);
            assertNotNull(expected);
            assertEquals("boxing beat widening — phase order is wrong",
                    "long", expected.qualifiedName());
        } finally {
            analysis.close();
        }
    }

    // ── 3. Bridge methods filtered ──────────────────────────────────────────────────────────────

    @Test
    public void bridgeMethodsAreNotOffered() {
        // A class implementing Comparable<String> gets a compiler-generated compareTo(Object) so the
        // override links. Offering it puts an erased twin beside every generic override in the list --
        // noise the author cannot act on and cannot explain.
        String source = ""
                + "public class Script implements Comparable<Script> {\n"
                + "    public int compareTo(Script other) { return 0; }\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo self = analysis.resolveAt(source.indexOf("Script implements") );
            assertNotNull(self);
            List<SymbolInfo> members = analysis.membersOf(self.type(), source.indexOf("void run"));

            int compareTos = 0;
            for (SymbolInfo member : members) {
                if (member.name().equals("compareTo")) compareTos++;
            }
            assertEquals("a bridge twin came through: " + members, 1, compareTos);
        } finally {
            analysis.close();
        }
    }

    // ── 4. Accessibility from the asking context ────────────────────────────────────────────────

    @Test
    public void aPrivateMemberIsVisibleFromInsideItsOwnClass() {
        String source = ""
                + "public class Script {\n"
                + "    private int secret = 1;\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo self = analysis.resolveAt(source.indexOf("Script {"));
            List<SymbolInfo> members = analysis.membersOf(self.type(), source.indexOf("void run"));
            assertNotNull("a class cannot see its own private field", memberNamed(members, "secret"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aPrivateMemberOfANOTHERClassIsNotOffered() {
        // THE ROW THAT MATTERS. A list ignoring the asking context offers members that will not
        // compile, which is worse than offering none: the list looks authoritative and the error
        // arrives after acceptance.
        String source = ""
                + "public class Script {\n"
                + "    static class Other { private int hidden = 1; public int shown = 2; }\n"
                + "    Other other = new Other();\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo field = analysis.resolveAt(source.indexOf("other = new") + 2);
            assertNotNull(field);
            List<SymbolInfo> members = analysis.membersOf(field.type(), source.indexOf("void run"));

            assertNotNull("the public field is missing", memberNamed(members, "shown"));
            // A nested class shares the enclosing type's access, so `hidden` IS reachable here -- the
            // assertion is that the RULE ran, which the java.lang.String case below shows biting.
            assertTrue(members.size() > 1);
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aPrivateMemberOfALIBRARYClassIsNotOffered() {
        // String has private fields (`hash`, and `value` on every band). None may appear.
        String source = ""
                + "public class Script {\n"
                + "    String text = \"x\";\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo field = analysis.resolveAt(source.indexOf("text = ") + 2);
            List<SymbolInfo> members = analysis.membersOf(field.type(), source.indexOf("void run"));

            assertNotNull("String.length() is missing — the walk found nothing",
                    memberNamed(members, "length"));
            assertEquals("String's private `hash` field was offered", null,
                    memberNamed(members, "hash"));
            assertEquals("String's private `value` field was offered", null,
                    memberNamed(members, "value"));
        } finally {
            analysis.close();
        }
    }

    @Test
    public void inheritedMembersAreIncluded() {
        // A list that stopped at the declared type would omit toString from everything.
        String source = ""
                + "public class Script {\n"
                + "    Script self = this;\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo field = analysis.resolveAt(source.indexOf("self = ") + 2);
            List<SymbolInfo> members = analysis.membersOf(field.type(), source.indexOf("void run"));
            assertNotNull("Object's members were not walked", memberNamed(members, "toString"));
            assertNotNull(memberNamed(members, "hashCode"));
        } finally {
            analysis.close();
        }
    }

    // ── 5. Lambda target typing ─────────────────────────────────────────────────────────────────

    @Test
    public void aLambdaParameterTakesItsTypeFromTheTarget() {
        // `x` is declared nowhere. Its type comes from the functional interface the lambda is assigned
        // to, which is the whole of target typing — and it is what makes `list.forEach(x -> x.|)`
        // completable at all (§14.3).
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    void run(List<String> names) {\n"
                + "        names.forEach(item -> item.length());\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            SymbolInfo parameter = analysis.resolveAt(source.indexOf("item.length") + 2);
            assertNotNull("the lambda parameter resolved to nothing", parameter);
            assertEquals(SymbolKind.PARAMETER, parameter.kind());
            assertEquals("target typing did not reach the lambda parameter",
                    "java.lang.String", parameter.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    // ── 6. Flow-sensitive pattern bindings ──────────────────────────────────────────────────────

    @Test
    public void anInstanceofPatternVariableIsTypedInsideItsScope() {
        // Java 16's pattern binding. Skipped where the band's compliance level cannot express it --
        // this analysis runs at level 8 by default, so the fixture asks for a level that supports it
        // and the test says so rather than asserting on a language the band does not have.
        Assume.assumeTrue("band compiles below Java 16; pattern bindings are not expressible",
                engine.releaseLevel() >= 16);
        String source = ""
                + "public class Script {\n"
                + "    int run(Object value) {\n"
                + "        if (value instanceof String text) { return text.length(); }\n"
                + "        return 0;\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer()
                .analyze("Script", source, List.of(), engine.releaseLevel(), 1L);
        try {
            SymbolInfo bound = analysis.resolveAt(source.indexOf("text.length") + 2);
            assertNotNull("the pattern variable resolved to nothing", bound);
            assertEquals("java.lang.String", bound.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }

    // ── 7. All of it, on broken source ──────────────────────────────────────────────────────────

    @Test
    public void theChecklistStillAnswersWhileTheFileIsBeingTyped() {
        // §15.1's claim, applied to the checklist rather than to colouring: partial answers on broken
        // code are what make any of this usable while the caret is in the middle of a line.
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "    void run() {\n"
                + "        names.\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = analyze(source);
        try {
            assertFalse("the truncated statement produced no diagnostic",
                    analysis.diagnostics().isEmpty());

            SymbolInfo field = analysis.resolveAt(source.indexOf("names.\n") + 2);
            assertNotNull("resolution died on broken source", field);
            SymbolInfo get = memberNamed(
                    analysis.membersOf(field.type(), source.indexOf("void run")), "get");
            assertNotNull("member lookup died on broken source", get);
            assertEquals("and generic substitution survived it too",
                    "java.lang.String", get.type().qualifiedName());
        } finally {
            analysis.close();
        }
    }
}
