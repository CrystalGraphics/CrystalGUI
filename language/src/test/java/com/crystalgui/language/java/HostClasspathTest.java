package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The classpath probe — what a script actually compiles against.
 *
 * <p>The load-bearing test is the last one: it is not enough for the probe to return a plausible list,
 * the list has to be one ECJ can resolve a <em>host</em> class through. That is the only assertion that
 * would fail if the probe returned paths that exist and contain nothing relevant.</p>
 */
public class HostClasspathTest {

    @Test
    public void theProbeFindsSomething() {
        List<String> entries = HostClasspath.detect();
        assertFalse("no classpath at all — a script could resolve nothing", entries.isEmpty());
    }

    @Test
    public void everyEntryExists() {
        // A launcher routinely names things that are not there, and handing one to the compiler
        // produces a warning about a file the author has never heard of, on every single analysis.
        for (String entry : HostClasspath.detect()) {
            assertTrue(entry + " does not exist", new File(entry).exists());
        }
    }

    @Test
    public void itIncludesWhereThisTestItselfWasLoadedFrom() {
        // The proof that it describes the RUNNING process rather than a declared one.
        String own = new File(HostClasspathTest.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath().replace("%20", " ")).getAbsolutePath();
        boolean found = false;
        for (String entry : HostClasspath.detect()) {
            if (new File(entry).getAbsolutePath().equals(own)) found = true;
        }
        assertTrue("the probe missed the directory this very class came from", found);
    }

    @Test
    public void aUrlClassLoadersEntriesAreIncluded() throws Exception {
        // The harness's case, and every plain JVM before 9. A synthetic loader so the assertion is
        // about the mechanism rather than about whatever this JVM happens to be.
        File temporary = File.createTempFile("cgui-classpath", "");
        assertTrue(temporary.delete());
        assertTrue(temporary.mkdirs());
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{temporary.toURI().toURL()}, getClass().getClassLoader());
            try {
                assertTrue("a URLClassLoader's own entry was not collected",
                        HostClasspath.detect(loader).contains(temporary.getAbsolutePath()));
            } finally {
                loader.close();
            }
        } finally {
            temporary.delete();
        }
    }

    @Test
    public void aScriptCompilesAgainstAHostClassThroughTheProbedClasspath() throws Exception {
        // THE ONE THAT MATTERS. A list of real paths that happens to contain nothing useful passes
        // every assertion above and fails here.
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());

        JavaEngine engine = JavaEngine.open(band, source);
        try {
            String script = ""
                    + "public class Script {\n"
                    + "    " + Marker.class.getCanonicalName() + " held;\n"
                    + "    String run() { return held.value(); }\n"
                    + "}\n";
            SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                    "Script", script, HostClasspath.detect(), engine.releaseLevel(), 1L);
            try {
                for (Diagnostic problem : analysis.diagnostics()) {
                    assertFalse("a host class did not resolve through the probed classpath: " + problem,
                            problem.severity() == DiagnosticSeverity.ERROR);
                }
                SymbolInfo call = analysis.resolveAt(script.indexOf("held.value") + 6);
                assertNotNull("the host method resolved to nothing", call);
                assertTrue("java.lang.String".equals(call.type().qualifiedName()));
            } finally {
                analysis.close();
            }
        } finally {
            engine.close();
        }
    }

    /** Stands in for the application API a real script would compile against. */
    public static final class Marker {
        public String value() {
            return "from the host";
        }
    }
}
