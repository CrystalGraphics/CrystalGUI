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

        Buffers edit(String qualifiedName, String source) {
            files.put(qualifiedName, source);
            return this;
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
}
