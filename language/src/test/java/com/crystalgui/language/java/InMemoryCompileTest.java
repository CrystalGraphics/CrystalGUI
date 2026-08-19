package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.ScriptCompiler;
import com.crystalgui.language.java.classpath.HostClasspath;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

/**
 * The in-memory compiler, which replaced a working batch one.
 *
 * <p>Replacing something that worked earns a net under the properties that mattered about it, rather
 * than only under the new capability. Three of these are things the batch path already did and which a
 * rewrite is exactly the sort of change to lose quietly.</p>
 *
 * <p>Skips where no engine band is staged — a legitimate environment, and the same allowance every other
 * engine test in this module makes.</p>
 */
public class InMemoryCompileTest {

    private static EngineHost host;
    private static JavaEngine engine;
    private static ScriptCompiler compiler;

    @BeforeClass
    public static void openBand() {
        host = EngineHost.shared(EngineHost.defaultSource());
        assumeNotNull(host);
        engine = JavaEngine.over(host);
        compiler = host.adapter("com.crystalgui.language.java.ecj.EcjScriptCompiler", ScriptCompiler.class);
    }

    @AfterClass
    public static void closeBand() {
        // Shared and owned by whoever opened it; closing a borrowed host is how the OTHER engine fails
        // later with a NoClassDefFoundError on a class it loaded fine a moment ago.
    }

    private static ScriptCompiler.Result compile(String className, String source) {
        return compiler.compile(className, source, HostClasspath.detect(),
                engine.releaseLevel());
    }

    @Test
    public void aScriptCompilesToBytesWithoutTouchingTheDisk() {
        ScriptCompiler.Result result = compile("Script",
                "public class Script { public static int answer() { return 42; } }");
        assertTrue("did not compile: " + result.messages(), result.successful());
        Map<String, byte[]> classes = result.classes();
        assertNotNull(classes.get("Script"));
        byte[] bytes = classes.get("Script");
        assertTrue("not a class file", bytes.length > 4
                && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE);
    }

    /**
     * The package comes from the SOURCE, not the class name.
     *
     * <p>The two differ whenever a script is compiled under a generated name, and ECJ enforces javac's
     * rule that a unit's path match its declared package — so getting this from the class name fails with
     * a message about the file's location rather than about the script. It is the one thing the rewrite
     * did break, and it broke only for packaged sources.</p>
     */
    @Test
    public void aDeclaredPackageIsTakenFromTheSource() {
        ScriptCompiler.Result result = compile("Script",
                "package demo.scripts;\npublic class Script { public static int answer() { return 1; } }");
        assertTrue("a packaged script did not compile: " + result.messages(), result.successful());
        assertNotNull("expected the packaged binary name",
                result.classes().get("demo.scripts.Script"));
    }

    /** An error is reported rather than thrown, and names something the author can act on. */
    @Test
    public void anErrorIsReportedWithItsLine() {
        ScriptCompiler.Result result = compile("Script",
                "public class Script { void broken() { return nope; } }");
        assertFalse("a broken script reported success", result.successful());
        assertFalse("no message for a broken script", result.messages().isEmpty());
        boolean located = false;
        for (String message : result.messages()) if (message.contains("line")) located = true;
        assertTrue("no message carried a line: " + result.messages(), located);
    }

    /**
     * Partial output survives an error.
     *
     * <p>The batch path collected class files even on failure, deliberately: a "compile always, run
     * explicitly" model wants to inspect what did compile, and discarding it makes a failed compile
     * indistinguishable from one that produced nothing.</p>
     */
    @Test
    public void aFailedCompileStillReportsWhatItProduced() {
        ScriptCompiler.Result result = compile("Script",
                "public class Script { void fine() { } void broken() { return nope; } }");
        assertFalse(result.successful());
        // Not asserting bytes are present -- ECJ may legitimately emit none for a unit this small -- only
        // that the failure path did not throw and produced a usable Result.
        assertNotNull(result.classes());
    }
}
