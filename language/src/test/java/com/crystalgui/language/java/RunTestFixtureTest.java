package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.run.ScriptHost;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The shipped run-testing fixture compiles, analyses clean, and actually runs.
 *
 * <h3>Why a fixture needs a test</h3>
 *
 * <p>{@code RunTest.java} exists to be opened in the harness and run by hand, which means the only thing
 * that would catch a mistake in it is a person noticing. That is exactly the arrangement that lets a
 * fixture rot: it gets edited, nobody runs it for a month, and the next person to press Run gets a
 * compile error and reasonably concludes the <em>engine</em> is broken.</p>
 *
 * <p>So the file is compiled and executed here, through the same {@link ScriptHost} the Run command
 * uses, and its output is captured and checked for every section it claims to cover. A section that
 * silently stops executing fails this rather than going unnoticed.</p>
 */
public class RunTestFixtureTest {

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

    /** The fixture as shipped, read from the resources it lives in. */
    private static String fixture() throws IOException {
        try (InputStream stream = RunTestFixtureTest.class
                .getResourceAsStream("/fixtures/RunTest.java")) {
            assertNotNull("fixtures/RunTest.java is not on the test resources", stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theFixtureIsRecognisedAsAWholeCompilationUnit() throws IOException {
        // It declares `public class RunTest`, so it must take the compile-as-is path. Prelude-wrapping
        // it would put a top-level public class inside a method body, where it is a local class -- and
        // a local class may not be public, so the error would name a modifier the author wrote correctly.
        assertTrue(ScriptPrelude.declaresType(fixture()));
    }

    @Test
    public void theFixtureAnalysesWithNoErrors() throws IOException {
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "RunTest", fixture(), HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            for (Diagnostic problem : analysis.diagnostics()) {
                assertFalse("the shipped fixture does not analyse clean: " + problem,
                        problem.severity() == DiagnosticSeverity.ERROR);
            }
        } finally {
            analysis.close();
        }
    }

    @Test(timeout = 120_000)
    public void theFixtureCompilesAndRunsAndReachesEverySection() throws Throwable {
        ScriptHost host = ScriptHost.of(engine);
        PrintStream realOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            ScriptHost.Compiled compiled = host.compileSource("RunTest", fixture(), Map.of());
            assertTrue("the fixture did not compile: " + compiled.messages(), compiled.successful());

            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                host.run(compiled, Map.of());
            } finally {
                System.setOut(realOut);
            }

            String output = captured.toString(StandardCharsets.UTF_8);
            assertTrue("the fixture produced no output", output.contains("RunTest starting"));
            assertTrue("the fixture did not reach the end — output was:\n" + output,
                    output.contains("RunTest finished"));

            // EVERY SECTION BY NAME. A section that stops executing -- because a feature stopped
            // compiling, or an exception ended the run early -- is the thing a person pressing Run
            // would have to notice, and this is what notices it instead.
            for (String section : List.of(
                    "primitives, overflow and bit operations",
                    "strings, text blocks and formatting",
                    "arrays, var and varargs",
                    "control flow, labels and switch",
                    "collections and iteration",
                    "generics and wildcards",
                    "records",
                    "sealed types and pattern matching",
                    "enums",
                    "inheritance and polymorphism",
                    "interfaces: default, static, functional",
                    "lambdas and method references",
                    "streams",
                    "optional",
                    "nested, anonymous and local classes",
                    "exceptions and try-with-resources",
                    "threads",
                    "reflection",
                    "time and math")) {
                assertTrue("the fixture never reached '" + section + "'", output.contains(section));
            }

            // "!!" IS THE FIXTURE'S ONE MARK FOR A DEFECT, and this asserts on the mark rather than on
            // any particular failure. The narrower version -- "no section threw" -- had a hole exactly
            // the size of a section that catches its own exception: the reflection section logged
            // "reflection failed" for a full run and the test passed, because nothing had propagated.
            assertFalse("a section reported a failure -- see the transcript:\n" + output,
                    output.contains("!!"));

            // A few values, so "it printed something" is not the whole assertion.
            assertTrue(output.contains("overflow wraps to"));
            assertTrue("reflective invocation did not happen", output.contains("reflective call"));
            assertTrue("threads did not all finish", output.contains("4000"));
            assertTrue("BigDecimal section did not run", output.contains("0.3"));
        } finally {
            System.setOut(realOut);
            host.close();
        }
    }
}
