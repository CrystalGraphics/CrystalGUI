package com.crystalgui.language.run;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.exec.ScriptHost;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Running a script that depends on another project file — M15 S5.
 *
 * <h3>The whole milestone is the cache, not the compile</h3>
 *
 * <p>The compile half arrives free: {@code ScriptNameEnvironment} already answers a project type with a
 * source unit, and ECJ emits a class file for every unit it pulled in — so {@code Result.classes()} is
 * the closure and {@code ScriptClassLoader} already defines any of them on demand.</p>
 *
 * <p>What did not work is the second run. A compiled script is keyed on the text of the file the author
 * ran, so a sibling it resolved through is invisible to that key: editing the sibling changes nothing the
 * key can see, the cache hits, and the run behaves exactly as it did before the edit. That reads as the
 * edit not being picked up — not as a caching decision — and it is the one failure mode a cache must not
 * have.</p>
 *
 * <h3>And none of it requires a save</h3>
 *
 * <p>{@code ProjectSources.sourceOf} answers from an open editor's buffer before it answers from disk, so
 * both the compile and the fingerprint see what the author can see. The workspace here never writes a
 * file at all, which is the point: every "edit" below is a buffer that was never saved.</p>
 */
public class ScriptCrossFileRunTest {

    private static final String GREETER = "com.example.util.Greeter";

    /** A workspace whose files can be edited without being saved — an open buffer, in other words. */
    private static final class Buffers implements ProjectSources {
        private final Map<String, String> files = new LinkedHashMap<>();
        private final Map<String, String> extensions = new LinkedHashMap<>();

        Buffers edit(String qualifiedName, String source) {
            return edit(qualifiedName, source, ".java");
        }

        Buffers edit(String qualifiedName, String source, String extension) {
            files.put(qualifiedName, source);
            extensions.put(qualifiedName, extension);
            return this;
        }

        /**
         * Where the file would live \u2014 the only thing that says which LANGUAGE wrote it.
         *
         * <p>{@code SourceRoots} names any file under a declared root whatever its extension, and both
         * {@code src/main/java} and {@code src/main/js} are declared, so one index holds both languages'
         * names with nothing in the NAME to tell them apart.</p>
         */
        @Override
        public String pathOf(String qualifiedName) {
            String extension = extensions.get(qualifiedName);
            if (extension == null) return null;
            String root = ".js".equals(extension) ? "src/main/js" : "src/main/java";
            return "proj:" + root + "/" + qualifiedName.replace('.', '/') + extension;
        }

        @Override
        public String sourceOf(String qualifiedName) {
            return files.get(qualifiedName);
        }

        @Override
        public boolean declaresPackage(String packageName) {
            if (packageName == null || packageName.isEmpty()) return false;
            String prefix = packageName + ".";
            for (String name : files.keySet()) {
                if (name.startsWith(prefix)) return true;
            }
            return false;
        }

        @Override
        public List<String> declaredTypes() {
            return List.copyOf(files.keySet());
        }
    }

    private JavaEngine engine;
    private ScriptHost host;
    private Buffers workspace;

    private static String greeterSaying(String word) {
        return "package com.example.util;\n"
                + "public class Greeter {\n"
                + "    public static String hi() { return \"" + word + "\"; }\n"
                + "}\n";
    }

/** What the script says out loud, so a run can be observed without a console. */
    public static final class Heard {
        static final java.util.List<String> WORDS =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        public static void say(String word) {
            WORDS.add(word);
        }
    }

    /** Uses the sibling, and never changes across this test — only the sibling does. */
    private static final String MAIN = "package com.example;\n"
            + "import com.example.util.Greeter;\n"
            + "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        com.crystalgui.language.run.ScriptCrossFileRunTest.Heard.say(Greeter.hi());\n"
            + "    }\n"
            + "}\n";

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        host = ScriptHost.of(engine);

        ProjectSourcesRegistry.resetForTesting();
        workspace = new Buffers().edit(GREETER, greeterSaying("hi"));
        ProjectSourcesRegistry.contribute(workspace);
        Heard.WORDS.clear();
    }

    @After
    public void closeEngine() throws IOException {
        ProjectSourcesRegistry.resetForTesting();
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    private ScriptHost.Compiled compileMain() {
        return host.compileSource("com.example.Main", MAIN, Map.of());
    }

    // ── The closure ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Compiling a script compiles the project files it uses, and both class files come back.</b>
     *
     * <p>The half §24.7 predicted would arrive free. Asserted anyway, because "free" here means "a
     * consequence of how ECJ treats a source unit handed to it by the name environment" — which is a
     * property of somebody else's compiler, not a decision of ours, and exactly the kind that changes
     * under a band upgrade without anything else noticing.</p>
     */
    @Test
    public void compilingAScriptCompilesTheProjectFilesItUses() {
        ScriptHost.Compiled compiled = compileMain();

        assertTrue("did not compile: " + compiled.messages(), compiled.successful());
        assertNotNull("the script's own class is missing", compiled.classes().get("com.example.Main"));
        assertNotNull("the sibling was resolved but never emitted, so the run would fail to link",
                compiled.classes().get(GREETER));
    }

    // ── The cache ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Re-running an untouched script hits the cache.</b>
     *
     * <p>The control for the test below. Without it, "the edit was picked up" passes trivially against a
     * cache that never hits at all — which would be correct and would also mean the whole mechanism could
     * be deleted.</p>
     */
    @Test
    public void anUntouchedScriptIsServedFromTheCache() {
        assertTrue(compileMain().successful());

        ScriptHost.Compiled again = compileMain();
        assertTrue("nothing changed, so this should not have recompiled", again.fromCache());
    }

    /**
     * <b>Editing the OTHER file — without saving it — recompiles this one.</b>
     *
     * <p>The milestone in one assertion, and the failure it replaces was silent: the cache hit, the script
     * ran, and it behaved as it did before the edit. Nothing reported anything, because from the key's
     * point of view nothing had happened.</p>
     *
     * <p>{@code MAIN} is a constant here on purpose — the file the author "ran" is byte-for-byte what it
     * was, and only the sibling moved.</p>
     */
    @Test
    public void editingASiblingRecompilesTheScriptThatUsesIt() {
        assertTrue(compileMain().successful());

        workspace.edit(GREETER, greeterSaying("hello"));

        ScriptHost.Compiled after = compileMain();
        assertTrue("still served from the cache, so the edit is invisible to the run", !after.fromCache());
        assertTrue("did not compile: " + after.messages(), after.successful());
    }

    /**
     * <b>...and the recompiled bytes are the NEW sibling's.</b>
     *
     * <p>Missing the cache is necessary and not sufficient: a recompile that still resolved the old text
     * would look identical from outside. Asserted on the emitted class file rather than by running it,
     * because a string constant is in the constant pool and that needs no loader, no bindings and no
     * thread — and what is being tested is the compile, not the runtime.</p>
     */
    @Test
    public void theRecompiledClassCarriesTheNewSiblingsBehaviour() {
        assertTrue(compileMain().successful());
        workspace.edit(GREETER, greeterSaying("hello"));

        byte[] greeter = compileMain().classes().get(GREETER);
        assertNotNull("the sibling was not recompiled at all", greeter);
        assertTrue("the class file still holds the old text",
                new String(greeter, java.nio.charset.StandardCharsets.ISO_8859_1).contains("hello"));
    }

    /**
     * <b>A script that reaches no workspace at all is cached on its own text, exactly as before.</b>
     *
     * <p>The regression guard. The dependency component is empty for such a script, so its key is the
     * three-component one this scheme has always used — which is what keeps every existing cached script,
     * and every host with no workspace open, behaving as it did.</p>
     */
    @Test
    public void aScriptWithNoProjectDependenciesIsUnaffected() {
        String alone = "public class Alone { public static int answer() { return 42; } }";

        assertTrue(host.compileSource("Alone", alone, Map.of()).successful());
        assertTrue("a script depending on nothing should still hit",
                host.compileSource("Alone", alone, Map.of()).fromCache());

        // ...and an unrelated workspace edit must not disturb it.
        workspace.edit(GREETER, greeterSaying("something else entirely"));
        assertTrue("an edit to a file this script never mentions invalidated it",
                host.compileSource("Alone", alone, Map.of()).fromCache());
    }

    // ── The whole point, end to end ─────────────────────────────────────────────────────────────

    /**
     * <b>Run it, edit the OTHER file without saving, run it again — and the change is there.</b>
     *
     * <p>Everything above tests a link in this chain; this is the chain. It is also the exact sentence
     * §24.7 gives S5 as its exit criterion ("running {@code Main.java} that uses {@code Viewer} works, and
     * re-running after editing {@code Viewer} picks the change up") and the behaviour a person actually
     * asks for: no save, no restart, no touching the file being run.</p>
     *
     * <p>Worth having as well as the compile-level tests, because a run exercises two things they cannot:
     * that the sibling's class file is DEFINED by the script's loader rather than merely emitted, and that
     * the second run does not hand back the first run's already-loaded classes.</p>
     */
    @Test
    public void runningAgainAfterEditingASiblingSeesTheEdit() throws Throwable {
        host.run(compileMain(), Map.of());
        assertEquals("the first run did not reach the sibling", List.of("hi"), List.copyOf(Heard.WORDS));

        // The author edits Greeter.java in another tab and does NOT save it.
        workspace.edit(GREETER, greeterSaying("hello"));

        host.run(compileMain(), Map.of());
        assertEquals("the second run replayed the old sibling -- an unsaved edit was invisible to it",
                List.of("hi", "hello"), List.copyOf(Heard.WORDS));
    }

    // ── Cycles ──────────────────────────────────────────────────────────────────

    /**
     * <b>Two project files that reference EACH OTHER compile and run.</b>
     *
     * <p>Mutual reference is ordinary Java and needs no cycle guard — a compiler resolves a batch of
     * units together rather than one at a time. What is <em>not</em> ordinary is that these units arrive
     * one at a time, pulled in by {@code ScriptNameEnvironment} as ECJ asks for them, so the second file
     * is requested from inside the resolution of the first. Nothing in that arrangement is guaranteed by
     * Java; it is a property of how JDT treats a source unit handed back by an environment, which is the
     * same reason {@code compilingAScriptCompilesTheProjectFilesItUses} is asserted rather than assumed.</p>
     */
    @Test
    public void twoProjectFilesThatReferenceEachOtherCompileAndRun() throws Throwable {
        workspace.edit("com.example.util.Ping",
                "package com.example.util;\n"
                + "public class Ping {\n"
                + "    public static String go() { return \"ping-\" + Pong.name(); }\n"
                + "    public static String name() { return \"ping\"; }\n"
                + "}\n");
        workspace.edit("com.example.util.Pong",
                "package com.example.util;\n"
                + "public class Pong {\n"
                + "    public static String name() { return \"pong\"; }\n"
                + "    public static String back() { return Ping.name(); }\n"
                + "}\n");

        ScriptHost.Compiled compiled = host.compileSource("com.example.Cycle",
                "package com.example;\n"
                + "import com.example.util.Ping;\n"
                + "public class Cycle {\n"
                + "    public static void main(String[] args) {\n"
                + "        " + Heard.class.getName().replace('$', '.') + ".say(Ping.go());\n"
                + "    }\n"
                + "}\n", Map.of());

        assertTrue("did not compile: " + compiled.messages(), compiled.successful());
        host.run(compiled, Map.of());
        assertEquals(List.of("ping-pong"), List.copyOf(Heard.WORDS));
    }

    /**
     * <b>A Java file cannot name a JavaScript one \u2014 which is what makes a cross-language cycle
     * impossible rather than merely unlikely.</b>
     *
     * <p>One index holds both languages, so {@code util.Greeter} is a name this environment is asked
     * about exactly as {@code com.example.util.Greeter} is. Handing JavaScript to a Java compiler produces
     * a page of syntax errors about a file the author never opened, instead of the one true thing: there
     * is no such type. The path is what decides, because the name cannot.</p>
     *
     * <p>And with the reverse already guarded on {@code .js}, neither language can reach into the other's
     * files \u2014 so the only cycles that exist are the same-language ones the tests above cover.</p>
     */
    @Test
    public void aJavaFileCannotResolveAJavaScriptProjectFile() {
        workspace.edit("util.Greeter", "function hi() { return 'hi'; }\n", ".js");

        ScriptHost.Compiled compiled = host.compileSource("com.example.Mixed",
                "package com.example;\n"
                + "import util.Greeter;\n"
                + "public class Mixed { }\n", Map.of());

        assertTrue("a JavaScript file must not resolve as a Java type", !compiled.successful());
        // THE ORDINARY MESSAGE, not a pile of syntax errors from inside somebody else's file.
        String first = compiled.messages().get(0);
        assertTrue("expected an unresolved import, got: " + compiled.messages(),
                first.contains("cannot be resolved"));
    }

    /**
     * <b>...including a cycle that runs through the SCRIPT ITSELF.</b>
     *
     * <p>The case with something real to break. The unit being compiled is deliberately excluded from the
     * project index — answering for it there is how a file comes to be declared twice, with the error
     * landing on the author's own class — so when the sibling reaches back and names it, the environment
     * says <em>no such project type</em> and ECJ has to find it in its own work list instead. That it does
     * is the whole of this test: exclude one line too much and the sibling reports the script as
     * unresolvable, which reads as the project tier not working at all.</p>
     */
    @Test
    public void aCycleThroughTheScriptItselfCompilesAndRuns() throws Throwable {
        workspace.edit("com.example.util.Echo",
                "package com.example.util;\n"
                + "import com.example.Loop;\n"
                + "public class Echo {\n"
                + "    public static String twice() { return Loop.word() + \"-\" + Loop.word(); }\n"
                + "}\n");
        String loop = "package com.example;\n"
                + "import com.example.util.Echo;\n"
                + "public class Loop {\n"
                + "    public static String word() { return \"loop\"; }\n"
                + "    public static void main(String[] args) {\n"
                + "        " + Heard.class.getName().replace('$', '.') + ".say(Echo.twice());\n"
                + "    }\n"
                + "}\n";
        // IN THE INDEX AS WELL AS COMPILED, which is what makes this a cycle rather than a one-way
        // reference: the sibling can only name the script if the workspace declares it.
        workspace.edit("com.example.Loop", loop);

        ScriptHost.Compiled compiled = host.compileSource("com.example.Loop", loop, Map.of());

        assertTrue("did not compile: " + compiled.messages(), compiled.successful());
        host.run(compiled, Map.of());
        assertEquals(List.of("loop-loop"), List.copyOf(Heard.WORDS));
    }
}
