package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.grammar.TreeSitterLanguages;
import com.crystalgui.language.run.ScriptHost;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>M7a: the engine is reachable from an application, not only from a test.</b>
 *
 * <h3>Why this test exists at all</h3>
 *
 * <p>Everything M5–M7 built passed its own tests while being <em>unreachable</em>: nothing registered a
 * {@code LanguageServices.Factory}, so {@code entry.newServices()} returned null, every consumer took its
 * degraded path, and the harness showed syntax colouring and nothing else. No test failed, because each
 * one constructed the engine itself.</p>
 *
 * <p>So this one goes through the front door — {@link JavaLanguage#register()} with no arguments, the
 * same call an application makes, reading the same system property a deployment sets — and then asks the
 * <b>registry</b> for services rather than building any. That is the only shape that can catch "it works
 * but nobody can get to it", which has now been the failure twice: at M3 the harness had no tree-sitter
 * on its classpath at all.</p>
 */
public class JavaLanguageRegistrationTest {

    @BeforeClass
    public static void registerAsAnApplicationWould() {
        // The grammars first, exactly as a host does -- so this also covers the ORDER question: two
        // registrations for `.java` must not discard each other.
        TreeSitterLanguages.register(null);

        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                JavaLanguage.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no jars for this band",
                JavaLanguage.register(null, JavaLanguage.defaultSource()));
    }

    private static LanguageRegistry.Entry javaEntry() {
        return LanguageRegistry.forFileName("Main.java");
    }

    @Test
    public void theStagedDirectoryIsWhatTheApplicationReads() {
        // The deployment shape, not the test's path list. `EngineSource.directory` reads one
        // subdirectory per band and that is what `stageEngines` writes -- so the dev run and a shipped
        // jar take the same code path rather than the dev run taking a second one that only works here.
        assertNotNull(System.getProperty(JavaLanguage.ENGINES_DIRECTORY_PROPERTY));
        assertTrue(JavaLanguage.isAvailable());
        assertNotNull(JavaLanguage.engine());
    }

    @Test
    public void aJavaFileNowResolvesToBOTHATokenizerAndServices() {
        // The exact assertion that was false before M7a. Registering services must ADD to the entry
        // rather than replace it: a second registration that dropped the tokenizer would trade
        // semantic colouring for no colouring at all.
        LanguageRegistry.Entry entry = javaEntry();

        SyntaxTokenizer tokenizer = entry.newTokenizer();
        assertFalse("the tree-sitter tokenizer was lost when services were registered",
                tokenizer == SyntaxTokenizer.NONE);

        LanguageServices services = entry.newServices(
                new TextBuffer("public class Main { }\n"), Resource.of("project", "src/Main.java"));
        assertNotNull("a .java file still gets no services — M7a is not wired", services);
        try {
            assertEquals("java", services.id());
        } finally {
            services.close();
        }
    }

    @Test
    public void theUnitNameComesFromTheFileName() {
        // ECJ reports "The public type Main must be defined in its own file" when they disagree -- a
        // diagnostic about the analyser's own bookkeeping, on the author's first line, saying nothing
        // they can act on.
        assertEquals("Main", JavaLanguage.classNameFor(Resource.of("project", "src/Main.java")));
        assertEquals("Script", JavaLanguage.classNameFor(null));
    }

    @Test
    public void aRealJavaFileGetsDiagnosticsThroughTheRegistry() {
        String source = ""
                + "public class Main {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(nothingHere());\n"
                + "    }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = javaEntry().newServices(
                buffer, Resource.of("project", "src/Main.java"));
        assertNotNull(services);
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(announced::add);

            assertFalse("nothing was announced — the analysis never ran", announced.isEmpty());
            List<Diagnostic> problems = announced.get(announced.size() - 1);
            assertFalse("a call to a method that does not exist reported nothing", problems.isEmpty());
            assertEquals(DiagnosticSeverity.ERROR, problems.get(0).severity());
            assertEquals("the squiggle is not on the line the author wrote it on",
                    2, problems.get(0).start().row());
        } finally {
            services.close();
        }
    }

    @Test
    public void aRealJavaFileGetsSemanticColouringThroughTheRegistry() {
        String source = ""
                + "public class Main {\n"
                + "    int fieldName = 1;\n"
                + "    int method(int paramName) { int localName = paramName; return localName; }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = javaEntry().newServices(
                buffer, Resource.of("project", "src/Main.java"));
        assertNotNull(services);
        try {
            List<SyntaxToken> tokens = services.semanticTokens().tokensIn(0, source.length());
            assertEquals("variable.parameter", captureAt(tokens, source, "paramName"));
            assertEquals("variable", captureAt(tokens, source, "localName"));
            assertEquals("variable.member", captureAt(tokens, source, "fieldName"));
        } finally {
            services.close();
        }
    }

    private static String captureAt(List<SyntaxToken> tokens, String source, String needle) {
        int at = source.indexOf(needle);
        for (SyntaxToken token : tokens) {
            if (token.start() == at && token.end() == at + needle.length()) return token.name();
        }
        return null;
    }

    // ── And the thing the user actually asked for ───────────────────────────────────────────────

    @Test
    public void anOrdinaryJavaFileRUNS() {
        // THE POINT OF M7a. `workspace/src/Main.java` is not a script body -- it declares a class and a
        // `static void main`. It is compiled as a compilation unit, not prelude-wrapped (a top-level
        // `public class` inside a method body is a local class, and a local class may not be public),
        // and started through `main` rather than the prelude's `run`.
        ScriptHost host = ScriptHost.of(JavaLanguage.engine());
        try {
            String source = ""
                    + "public class Main {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        " + Ran.class.getCanonicalName() + ".record(\"main ran\");\n"
                    + "    }\n"
                    + "}\n";
            Ran.SEEN.clear();

            ScriptHost.Compiled compiled = host.compileSource("Main", source, Map.of());
            assertTrue("an ordinary .java file did not compile: " + compiled.messages(),
                    compiled.successful());

            host.run(compiled, Map.of());
            assertEquals(List.of("main ran"), Ran.SEEN);
        } catch (Throwable failed) {
            throw new AssertionError(failed);
        } finally {
            host.close();
        }
    }

    @Test
    public void aBareSnippetIsWrappedInsteadOfCompiledAsAUnit() {
        // The other half of the same decision, so the two shapes are known to be told apart rather than
        // one of them happening to work.
        ScriptHost host = ScriptHost.of(JavaLanguage.engine());
        try {
            Ran.SEEN.clear();
            ScriptHost.Compiled compiled = host.compileSource("Snippet",
                    Ran.class.getCanonicalName() + ".record(\"snippet ran\");\n", Map.of());
            assertTrue("a bare snippet did not compile: " + compiled.messages(), compiled.successful());

            host.run(compiled, Map.of());
            assertEquals(List.of("snippet ran"), Ran.SEEN);
        } catch (Throwable failed) {
            throw new AssertionError(failed);
        } finally {
            host.close();
        }
    }

    @Test
    public void aFileDeclaringAPackageAnalysesAndRunsCleanly() {
        // The scratch workspace's own Main.java declares a package, because it was copied out of this
        // repository -- which is exactly what people do with scratch files. Naming the unit from the
        // file stem makes ECJ report "the declared package does not match the expected package" on
        // line 1: an error about the tool's own bookkeeping, on a file that compiles perfectly well.
        String source = ""
                + "package com.example.scratch;\n"
                + "public class Packaged {\n"
                + "    public static void main(String[] args) {\n"
                + "        " + Ran.class.getCanonicalName() + ".record(\"packaged ran\");\n"
                + "    }\n"
                + "}\n";

        LanguageServices services = javaEntry().newServices(
                new TextBuffer(source), Resource.of("project", "src/Packaged.java"));
        assertNotNull(services);
        try {
            List<List<Diagnostic>> announced = new ArrayList<>();
            services.onDiagnostics(announced::add);
            for (Diagnostic problem : announced.get(announced.size() - 1)) {
                assertFalse("a packaged file did not analyse clean: " + problem,
                        problem.severity() == DiagnosticSeverity.ERROR);
            }
        } finally {
            services.close();
        }

        // AND IT RUNS -- which needs the BINARY name, not the stem: asking a loader for `Packaged`
        // when the class is `com.example.scratch.Packaged` throws ClassNotFoundException for something
        // that is plainly in the compiler's output.
        ScriptHost host = ScriptHost.of(JavaLanguage.engine());
        try {
            Ran.SEEN.clear();
            ScriptHost.Compiled compiled = host.compileSource("Packaged", source, Map.of());
            assertTrue("a packaged file did not compile: " + compiled.messages(), compiled.successful());
            host.run(compiled, Map.of());
            assertEquals(List.of("packaged ran"), Ran.SEEN);
        } catch (Throwable failed) {
            throw new AssertionError(failed);
        } finally {
            host.close();
        }
    }

    /** Where the two scripts above land, so "it ran" is an assertion rather than a look at stdout. */
    public static final class Ran {
        public static final List<String> SEEN = new ArrayList<>();

        public static void record(String what) {
            SEEN.add(what);
        }

        private Ran() {
        }
    }
}
