package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.ScriptClassLoader;
import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The reflection overlay — resolving types that are in no classpath because they are in no file.
 *
 * <p>The load-bearing test is {@link #aScriptCanBeWrittenAgainstATypeAPreviousScriptDefined}, which is
 * §16.3's whole question: what can be said about a script's world <em>after</em> it has run. Everything
 * else here is a property of the stub generator that that test would fail on obscurely.</p>
 */
public class ReflectionOverlayTest {

    private JavaEngine engine;
    private final List<ReflectionOverlay> overlays = new ArrayList<>();

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
        for (ReflectionOverlay overlay : overlays) overlay.delete();
        if (engine != null) engine.close();
    }

    private ReflectionOverlay overlay() throws IOException {
        ReflectionOverlay overlay = ReflectionOverlay.temporary();
        overlays.add(overlay);
        return overlay;
    }

    private List<String> classpathWith(ReflectionOverlay overlay) {
        List<String> entries = new ArrayList<>();
        // THE OVERLAY FIRST. §15.2's merge order: what is loaded is what will run, so where a type is
        // both on the classpath and live, the live view wins.
        entries.add(overlay.directory().toString());
        entries.addAll(HostClasspath.detect());
        return entries;
    }

    private static void assertNoErrors(SourceAnalyzer.Analysis analysis) {
        for (Diagnostic problem : analysis.diagnostics()) {
            assertFalse("did not compile: " + problem, problem.severity() == DiagnosticSeverity.ERROR);
        }
    }

    // ── The one that matters ────────────────────────────────────────────────────────────────────

    @Test
    public void aScriptCanBeWrittenAgainstATypeAPreviousScriptDefined() throws Exception {
        // Script one is compiled and DEFINED. Its class now exists, has no file anywhere, and appears
        // on no classpath -- so script two cannot name it without the overlay. This is exactly the
        // shape of "complete against the object the last script left behind".
        ScriptCompiler.Result first = engine.compiler().compile("Produced",
                "import java.util.ArrayList;\n"
                        + "import java.util.List;\n"
                        + "public class Produced {\n"
                        + "    public List<String> names() {\n"
                        + "        List<String> out = new ArrayList<String>();\n"
                        + "        out.add(\"from script one\");\n"
                        + "        return out;\n"
                        + "    }\n"
                        + "}\n",
                HostClasspath.detect(), engine.releaseLevel());
        assertTrue("script one failed: " + first.messages(), first.successful());

        ScriptClassLoader loader =
                new ScriptClassLoader(first.classes(), getClass().getClassLoader());
        Class<?> produced = Class.forName("Produced", true, loader);

        ReflectionOverlay overlay = overlay().add(produced);
        assertTrue("the produced class was treated as already resolvable",
                overlay.stubbed().contains("Produced"));

        String second = ""
                + "public class Consumer {\n"
                + "    public static String run(Produced source) {\n"
                + "        return source.names().get(0);\n"
                + "    }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Consumer", second, classpathWith(overlay), engine.releaseLevel(), 1L);
        try {
            assertNoErrors(analysis);

            // AND THE GENERIC ARGUMENT SURVIVED. Without a reconstructed Signature attribute this
            // would be a raw List and `get(0)` would answer Object -- so the script would still
            // compile and completion would be useless on exactly the types scripts define.
            SymbolInfo call = analysis.resolveAt(second.indexOf("names()") + 2);
            assertNotNull(call);
            assertEquals("List<String>", call.type().displayName());
        } finally {
            analysis.close();
        }
    }

    @Test
    public void andThatSecondScriptLinksAgainstTheRealClass() throws Exception {
        // A stub is a COMPILE-TIME view; the JVM links against the loaded class. If those two ever
        // disagreed the failure would be a NoSuchMethodError at run time from code that compiled
        // cleanly, so it is worth running rather than assuming.
        ScriptCompiler.Result first = engine.compiler().compile("Produced",
                "public class Produced { public String greeting() { return \"hello\"; } }\n",
                HostClasspath.detect(), engine.releaseLevel());
        assertTrue(first.successful());

        ScriptClassLoader firstLoader =
                new ScriptClassLoader(first.classes(), getClass().getClassLoader());
        Class<?> produced = Class.forName("Produced", true, firstLoader);
        ReflectionOverlay overlay = overlay().add(produced);

        ScriptCompiler.Result second = engine.compiler().compile("Consumer",
                "public class Consumer {\n"
                        + "    public static String run(Object source) throws Exception {\n"
                        + "        return ((Produced) source).greeting();\n"
                        + "    }\n"
                        + "}\n",
                classpathWith(overlay), engine.releaseLevel());
        assertTrue("script two failed: " + second.messages(), second.successful());

        // Defined in a loader whose PARENT is script one's, so `Produced` resolves to the real class.
        ScriptClassLoader secondLoader = new ScriptClassLoader(second.classes(), firstLoader);
        Object answer = Class.forName("Consumer", true, secondLoader)
                .getMethod("run", Object.class)
                .invoke(null, produced.getDeclaredConstructor().newInstance());
        assertEquals("hello", answer);
    }

    // ── Properties of the stub generator ────────────────────────────────────────────────────────

    @Test
    public void aClassAlreadyOnTheClasspathIsNotStubbed() {
        // Re-stating the JDK would be enormous, and re-stating an ordinary class is a second, possibly
        // disagreeing view of something the compiler can already see.
        try {
            ReflectionOverlay overlay = overlay().add(String.class).add(ReflectionOverlayTest.class);
            assertTrue("stubbed something the compiler can already resolve: " + overlay.stubbed(),
                    overlay.stubbed().isEmpty());
        } catch (IOException failed) {
            throw new AssertionError(failed);
        }
    }

    @Test
    public void aRuntimeProxyIsStubbedAlongWithItsInterface() throws Exception {
        // A proxy has no file at all, which is the other half of "exists only in memory".
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Runnable.class}, (p, m, a) -> null);
        ReflectionOverlay overlay = overlay().add(proxy.getClass());
        assertFalse("a runtime proxy was not stubbed", overlay.stubbed().isEmpty());
    }

    @Test
    public void supertypesAreStubbedTransitively() throws Exception {
        // A stub whose superclass cannot be found is worse than no stub: the compiler reports an error
        // about a class the author never mentioned.
        ScriptCompiler.Result compiled = engine.compiler().compile("Child",
                "public class Child extends Parent { }\n"
                        + "class Parent { public int depth() { return 1; } }\n",
                HostClasspath.detect(), engine.releaseLevel());
        assertTrue(compiled.successful());

        ScriptClassLoader loader =
                new ScriptClassLoader(compiled.classes(), getClass().getClassLoader());
        ReflectionOverlay overlay = overlay().add(Class.forName("Child", true, loader));

        assertTrue(overlay.stubbed().contains("Child"));
        assertTrue("the superclass was left unresolvable", overlay.stubbed().contains("Parent"));
    }

    @Test
    public void aScriptResolvesAnInheritedMemberThroughAStub() throws Exception {
        ScriptCompiler.Result compiled = engine.compiler().compile("Child",
                "public class Child extends Parent { }\n"
                        + "class Parent { public int depth() { return 7; } }\n",
                HostClasspath.detect(), engine.releaseLevel());
        assertTrue(compiled.successful());

        ScriptClassLoader loader =
                new ScriptClassLoader(compiled.classes(), getClass().getClassLoader());
        ReflectionOverlay overlay = overlay().add(Class.forName("Child", true, loader));

        String script = "public class Uses { int run(Child c) { return c.depth(); } }\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Uses", script, classpathWith(overlay), engine.releaseLevel(), 1L);
        try {
            assertNoErrors(analysis);
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aGenericTypeParameterOnTheClassSurvives() throws Exception {
        // The class-level formal, as opposed to the method-level one above. A malformed formal makes
        // the whole signature unparseable and the compiler rejects the STUB, with a message about a
        // signature rather than about anything the author did.
        ScriptCompiler.Result compiled = engine.compiler().compile("Box",
                "public class Box<T> {\n"
                        + "    private T held;\n"
                        + "    public T get() { return held; }\n"
                        + "    public void put(T value) { held = value; }\n"
                        + "}\n",
                HostClasspath.detect(), engine.releaseLevel());
        assertTrue(compiled.successful());

        ScriptClassLoader loader =
                new ScriptClassLoader(compiled.classes(), getClass().getClassLoader());
        ReflectionOverlay overlay = overlay().add(Class.forName("Box", true, loader));

        String script = "public class Uses { String run(Box<String> box) { return box.get(); } }\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Uses", script, classpathWith(overlay), engine.releaseLevel(), 1L);
        try {
            // If T were erased, `box.get()` would be Object and this would be an incompatible-types
            // error rather than clean.
            assertNoErrors(analysis);
        } finally {
            analysis.close();
        }
    }

    @Test
    public void aStubCarriesNoMethodBodiesAndThatIsFine() throws Exception {
        // Stated as a test because it is the property that makes the whole approach cheap: a stub is
        // only ever resolved against, never executed, so nothing has to generate code.
        ScriptCompiler.Result compiled = engine.compiler().compile("Tiny",
                "public class Tiny { public int value() { return 1; } }\n",
                HostClasspath.detect(), engine.releaseLevel());
        ScriptClassLoader loader =
                new ScriptClassLoader(compiled.classes(), getClass().getClassLoader());
        ReflectionOverlay overlay = overlay().add(Class.forName("Tiny", true, loader));

        java.nio.file.Path stub = overlay.directory().resolve("Tiny.class");
        assertTrue(java.nio.file.Files.exists(stub));
        Map<String, byte[]> real = compiled.classes();
        assertTrue("the stub is not smaller than the real class — it is carrying code",
                java.nio.file.Files.size(stub) < real.get("Tiny").length);
    }
}
