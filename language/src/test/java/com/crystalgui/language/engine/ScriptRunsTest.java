package com.crystalgui.language.engine;

import com.crystalgui.language.engine.bridge.ScriptCompiler;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A script compiles and runs.</b> End to end, through every seam the stack has so far.
 *
 * <h3>What this actually exercises, in order</h3>
 *
 * <ol>
 *   <li>{@link EngineBand} picks a band and {@link EngineSource} finds its jars.</li>
 *   <li>{@link EngineClassLoader} loads them child-first, sharing only the JDK and the bridge package.</li>
 *   <li>{@link JavaEngine} loads the ECJ adapter <em>inside</em> that loader and casts it to
 *       {@link ScriptCompiler} — the cast that proves the bridge carve-out works.</li>
 *   <li>ECJ compiles real source to real bytecode.</li>
 *   <li>{@link ScriptClassLoader} — owned by the host, parented to the host — defines the classes.</li>
 *   <li>The method is invoked and its answer checked.</li>
 * </ol>
 *
 * <p>Every one of those was previously argued for and none had been run. This is the first test in the
 * repository where a language the user typed produces a value.</p>
 */
public class ScriptRunsTest {

    private static JavaEngine openEngine() throws IOException {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        return JavaEngine.open(band, source);
    }

    /** The host's own classpath, so a script can call back into things we hand it. */
    private static List<String> hostClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (!entry.trim().isEmpty()) entries.add(entry);
        }
        return entries;
    }

    private static Object runStatic(ScriptCompiler.Result result, String className, String method,
                                    ClassLoader parent) throws Exception {
        ScriptClassLoader loader = new ScriptClassLoader(result.classes(), parent);
        Class<?> loaded = Class.forName(className, true, loader);
        Method entry = loaded.getMethod(method);
        return entry.invoke(null);
    }

    @Test
    public void aScriptCompilesAndReturnsAValue() throws Exception {
        JavaEngine engine = openEngine();
        try {
            ScriptCompiler.Result result = engine.compiler().compile("Hello",
                    "public class Hello {\n"
                            + "    public static String run() {\n"
                            + "        StringBuilder greeting = new StringBuilder();\n"
                            + "        for (int i = 0; i < 3; i++) greeting.append('!');\n"
                            + "        return \"hello from a script\" + greeting;\n"
                            + "    }\n"
                            + "}\n",
                    hostClasspath(), engine.releaseLevel());

            assertTrue("compile failed: " + result.messages(), result.successful());
            assertTrue(result.classes().containsKey("Hello"));

            Object answer = runStatic(result, "Hello", "run", getClass().getClassLoader());
            assertEquals("hello from a script!!!", answer);
        } finally {
            engine.close();
        }
    }

    @Test
    public void aScriptCanUseTheJdkAndModernSyntax() throws Exception {
        // Generics, enhanced for, and String.join -- not exotic, but each needs the compiler to have
        // resolved against a real class library rather than an empty one.
        JavaEngine engine = openEngine();
        try {
            ScriptCompiler.Result result = engine.compiler().compile("Listy",
                    "import java.util.ArrayList;\n"
                            + "import java.util.List;\n"
                            + "public class Listy {\n"
                            + "    public static String run() {\n"
                            + "        List<String> names = new ArrayList<String>();\n"
                            + "        names.add(\"a\"); names.add(\"b\"); names.add(\"c\");\n"
                            + "        StringBuilder out = new StringBuilder();\n"
                            + "        for (String name : names) out.append(name.toUpperCase());\n"
                            + "        return out.toString();\n"
                            + "    }\n"
                            + "}\n",
                    hostClasspath(), engine.releaseLevel());

            assertTrue("compile failed: " + result.messages(), result.successful());
            assertEquals("ABC", runStatic(result, "Listy", "run", getClass().getClassLoader()));
        } finally {
            engine.close();
        }
    }

    @Test
    public void aScriptCallsBackIntoAHostClass() throws Exception {
        // THE POINT OF THE WHOLE STACK. A script is only interesting if it can reach the application
        // that is running it -- this compiles against the host's live classpath and invokes a type the
        // host defines, loaded by the HOST's loader rather than duplicated into the script's.
        JavaEngine engine = openEngine();
        try {
            // getCanonicalName, NOT getName. The binary name of a nested class is
            // `...ScriptRunsTest$HostApi`, and `$` is not how source spells it -- the import simply
            // does not resolve. Any host API surfaced to a script by name hits this.
            ScriptCompiler.Result result = engine.compiler().compile("Caller",
                    "import " + HostApi.class.getCanonicalName() + ";\n"
                            + "public class Caller {\n"
                            + "    public static String run() {\n"
                            + "        return HostApi.greet(\"world\");\n"
                            + "    }\n"
                            + "}\n",
                    hostClasspath(), engine.releaseLevel());

            assertTrue("compile failed: " + result.messages(), result.successful());
            assertEquals("host says hello to world",
                    runStatic(result, "Caller", "run", getClass().getClassLoader()));
        } finally {
            engine.close();
        }
    }

    /** Stands in for the application API a real script would be given. */
    public static final class HostApi {
        public static String greet(String who) {
            return "host says hello to " + who;
        }

        private HostApi() {
        }
    }

    @Test
    public void everyClassAScriptProducesComesBackNotJustTheNamedOne() throws Exception {
        // A nested class, an anonymous class and a lambda each emit their own class file. A loader
        // handed only the named one fails on first use with a NoClassDefFoundError naming something
        // the author never wrote.
        JavaEngine engine = openEngine();
        try {
            ScriptCompiler.Result result = engine.compiler().compile("Nested",
                    "import java.util.concurrent.Callable;\n"
                            + "public class Nested {\n"
                            + "    static class Inner { int value() { return 21; } }\n"
                            + "    public static String run() throws Exception {\n"
                            + "        Callable<Integer> doubler = new Callable<Integer>() {\n"
                            + "            public Integer call() { return new Inner().value() * 2; }\n"
                            + "        };\n"
                            + "        return String.valueOf(doubler.call());\n"
                            + "    }\n"
                            + "}\n",
                    hostClasspath(), engine.releaseLevel());

            assertTrue("compile failed: " + result.messages(), result.successful());
            assertTrue("only got " + result.classes().keySet(), result.classes().size() >= 3);
            assertTrue(result.classes().containsKey("Nested$Inner"));
            assertEquals("42", runStatic(result, "Nested", "run", getClass().getClassLoader()));
        } finally {
            engine.close();
        }
    }

    @Test
    public void abrokenScriptFailsWithSomethingToShowTheAuthor() throws Exception {
        JavaEngine engine = openEngine();
        try {
            ScriptCompiler.Result result = engine.compiler().compile("Broken",
                    "public class Broken {\n"
                            + "    public static String run() { return missing(); }\n"
                            + "}\n",
                    hostClasspath(), engine.releaseLevel());

            assertFalse("a call to a method that does not exist compiled", result.successful());
            assertFalse("failed with nothing to tell the author", result.messages().isEmpty());
            String joined = String.join("\n", result.messages());
            assertTrue("the message does not name the problem: " + joined, joined.contains("missing"));
        } finally {
            engine.close();
        }
    }

    @Test
    public void reRunningAScriptReplacesItRatherThanAccumulating() throws Exception {
        // The lifecycle M7 formalises, and the reason compiled bytes come back rather than Class
        // objects: each run gets its own loader, so the previous version becomes collectable and two
        // versions of one script are genuinely different types rather than a redefinition conflict.
        JavaEngine engine = openEngine();
        try {
            ScriptCompiler.Result first = engine.compiler().compile("Version",
                    "public class Version { public static String run() { return \"one\"; } }",
                    hostClasspath(), engine.releaseLevel());
            ScriptCompiler.Result second = engine.compiler().compile("Version",
                    "public class Version { public static String run() { return \"two\"; } }",
                    hostClasspath(), engine.releaseLevel());

            ClassLoader host = getClass().getClassLoader();
            ScriptClassLoader loaderOne = new ScriptClassLoader(first.classes(), host);
            ScriptClassLoader loaderTwo = new ScriptClassLoader(second.classes(), host);

            Class<?> one = Class.forName("Version", true, loaderOne);
            Class<?> two = Class.forName("Version", true, loaderTwo);

            assertNotSame("two runs must be two types, or the second could not replace the first",
                    one, two);
            assertEquals("one", one.getMethod("run").invoke(null));
            assertEquals("two", two.getMethod("run").invoke(null));
        } finally {
            engine.close();
        }
    }

    @Test
    public void theHostNeverSeesTheCompilerItIsUsing() throws Exception {
        // The isolation guarantee, asserted from the consuming side rather than the loader's. The host
        // holds a working ScriptCompiler and still cannot name a single ECJ type -- which is what keeps
        // a 13MB compiler off a dedicated server's classpath.
        JavaEngine engine = openEngine();
        try {
            assertNotNull(engine.compiler());
            try {
                Class.forName("org.eclipse.jdt.core.compiler.batch.BatchCompiler", false,
                        getClass().getClassLoader());
                org.junit.Assert.fail("ECJ is reachable from the application classloader");
            } catch (ClassNotFoundException expected) {
                // Correct: the compiler works and is invisible.
            }
        } finally {
            engine.close();
        }
    }
}
