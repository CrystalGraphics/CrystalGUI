package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One JavaScript script importing another — M15 S6.
 *
 * <h3>Same statement, one more tier</h3>
 *
 * <p>{@code import a.b.C;} already bound a <b>Java type</b>. It now binds a <b>project script's exports</b>
 * first and falls back to the Java type, which is the ordering the whole stack uses: a workspace file
 * outranks a jar publishing the same name, exactly as {@code ScriptNameEnvironment} decides it for Java.
 * Nothing about the authored syntax changed, so nothing about blanking, colouring or offsets changed
 * either.</p>
 *
 * <h3>Nothing is ever saved here</h3>
 *
 * <p>The workspace below is a map of strings and writes no file. That is the point rather than a
 * convenience: modules are read through {@code ProjectSources}, which answers from an open editor's
 * buffer before it answers from disk, so what runs is what the author can see.</p>
 */
public class JsProjectImportTest {

    /** What a module's side effects land on. Public, because the script reaches it by name. */
    public static final class Sink {
        public static final List<String> WRITTEN = Collections.synchronizedList(new ArrayList<>());

        public static void write(String value) {
            WRITTEN.add(value);
        }
    }

    private static final String SINK = "Java.type('" + Sink.class.getName() + "')";

    /** An unsaved workspace: qualified name to text, edited in place. */
    private static final class Buffers implements ProjectSources {
        private final Map<String, String> files = new LinkedHashMap<>();
        private final Map<String, String> extensions = new LinkedHashMap<>();

        Buffers edit(String qualifiedName, String source) {
            return edit(qualifiedName, source, ".js");
        }

        Buffers edit(String qualifiedName, String source, String extension) {
            files.put(qualifiedName, source);
            extensions.put(qualifiedName, extension);
            return this;
        }

        @Override
        public String sourceOf(String qualifiedName) {
            return files.get(qualifiedName);
        }

        /**
         * Where the file would live — the only thing that says which LANGUAGE wrote it.
         *
         * <p>The real index derives this from the path the file was crawled at. A stand-in answering null
         * is trusted by both engines, which is the documented behaviour for a provider with no paths to
         * offer and would leave the language guard with nothing to test.</p>
         */
        @Override
        public String pathOf(String qualifiedName) {
            String extension = extensions.get(qualifiedName);
            if (extension == null) return null;
            String root = ".js".equals(extension) ? "src/main/js" : "src/main/java";
            return "proj:" + root + "/" + qualifiedName.replace('.', '/') + extension;
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

    private JsHost host;
    private Buffers workspace;

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @Before
    public void openHost() {
        host = new JsHost(JsLanguage.executor());
        Sink.WRITTEN.clear();
        ProjectSourcesRegistry.resetForTesting();
        workspace = new Buffers();
        ProjectSourcesRegistry.contribute(workspace);
    }

    @After
    public void clearRegistry() {
        ProjectSourcesRegistry.resetForTesting();
    }

    private void run(String source) throws Throwable {
        ScriptRuntime.Compiled compiled = host.compileScript("Main.js", source, Map.of());
        assertTrue("did not compile: " + compiled.messages(), compiled.successful());
        host.run(compiled, Map.of());
    }

    // ── The headline ────────────────────────────────────────────────────────────────────────────

    /** <b>A script imports another and gets its exports.</b> S6's exit criterion, first half. */
    @Test
    public void aScriptImportsAnotherAndGetsItsExports() throws Throwable {
        workspace.edit("util.Greeter", "exports.hi = function () { return 'hi'; };\n");

        run("import util.Greeter;\n" + SINK + ".write(Greeter.hi());\n");

        assertEquals(List.of("hi"), List.copyOf(Sink.WRITTEN));
    }

    /**
     * <b>A module that REPLACES {@code module.exports} is honoured.</b>
     *
     * <p>{@code module.exports = function () {}} is the commonest single-export shape there is, and it is
     * the one a naive implementation gets wrong: the object handed to the module at the start is not the
     * one it ends up exporting, so the importer has to read {@code module.exports} back afterwards rather
     * than keep the object it created.</p>
     */
    @Test
    public void aModuleMayReplaceItsExportsOutright() throws Throwable {
        workspace.edit("util.Shout", "module.exports = function (word) { return word.toUpperCase(); };\n");

        run("import util.Shout;\n" + SINK + ".write(Shout('loud'));\n");

        assertEquals(List.of("LOUD"), List.copyOf(Sink.WRITTEN));
    }

    // ── Nesting, which is why this is not Rhino's Require ───────────────────────────────────────

    /**
     * <b>A module's OWN imports are bound — including another module.</b>
     *
     * <p>The case that decided the implementation. Rhino's {@code Require} builds each module's scope
     * internally and offers no hook to inject a binding into it, so a module that itself imported
     * anything could not be served at all — and a two-file script whose second file also imports
     * something is the ordinary shape rather than an edge case.</p>
     */
    @Test
    public void aModuleCanImportAnotherModule() throws Throwable {
        workspace.edit("util.Inner", "exports.word = function () { return 'inner'; };\n");
        workspace.edit("util.Outer",
                "import util.Inner;\n"
                + "exports.say = function () { return 'outer+' + Inner.word(); };\n");

        run("import util.Outer;\n" + SINK + ".write(Outer.say());\n");

        assertEquals(List.of("outer+inner"), List.copyOf(Sink.WRITTEN));
    }

    /** <b>...and a module may import a JAVA type</b>, which is the other half of the same hook. */
    @Test
    public void aModuleCanImportAJavaType() throws Throwable {
        workspace.edit("util.Teller",
                "import " + Sink.class.getName() + ";\n"
                + "exports.tell = function (word) { Sink.write(word); };\n");

        run("import util.Teller;\nTeller.tell('from inside a module');\n");

        assertEquals(List.of("from inside a module"), List.copyOf(Sink.WRITTEN));
    }

    /**
     * <b>A cycle terminates.</b>
     *
     * <p>A gets B, B gets A. The entry is written before the module is evaluated, so the second import
     * receives A's exports as they stand at that moment instead of recursing until the stack ends. Node
     * behaves the same way; the caveat both share is that a module which <em>replaces</em>
     * {@code module.exports} leaves a cyclic importer holding the original object.</p>
     */
    @Test
    public void aCycleBetweenTwoModulesTerminates() throws Throwable {
        workspace.edit("util.A",
                "import util.B;\n"
                + "exports.name = 'A';\n"
                + "exports.viaB = function () { return B.name; };\n");
        workspace.edit("util.B",
                "import util.A;\n"
                + "exports.name = 'B';\n");

        run("import util.A;\n" + SINK + ".write(A.name + '/' + A.viaB());\n");

        assertEquals(List.of("A/B"), List.copyOf(Sink.WRITTEN));
    }

    // ── The tiers, and the sandbox ──────────────────────────────────────────────────────────────

    /**
     * <b>A name the workspace does not declare is still a Java type.</b>
     *
     * <p>The regression guard. The project tier is added <em>in front of</em> the Java one, and the whole
     * existing behaviour of this statement depends on falling through to it.</p>
     */
    @Test
    public void aNameTheWorkspaceDoesNotDeclareStillBindsAJavaType() throws Throwable {
        run("import " + Sink.class.getName() + ";\nSink.write('still java');\n");

        assertEquals(List.of("still java"), List.copyOf(Sink.WRITTEN));
    }

    /**
     * <b>A workspace file outranks a classpath type of the same name.</b>
     *
     * <p>The ordering, asserted rather than assumed. Classpath-first would mean adding a dependency can
     * quietly take over a name the project itself declares — which is the failure {@code AttachedSources}
     * and {@code ScriptNameEnvironment} both order against.</p>
     */
    @Test
    public void aProjectScriptOutranksAClasspathTypeOfTheSameName() throws Throwable {
        workspace.edit(Sink.class.getName(), "exports.write = function () { };\nexports.mine = true;\n");

        run("import " + Sink.class.getName() + ";\n"
                + "if (!Sink.mine) { throw new Error('the classpath type won'); }\n");

        assertTrue("the project script should have shadowed the class, so nothing was written",
                Sink.WRITTEN.isEmpty());
    }

    /**
     * <b>A refused module is refused.</b> S6's exit criterion, second half.
     *
     * <p>Through the same gate the Java tier uses and <em>before</em> either tier is consulted: an import
     * is a reach for a name, and asking per tier would let one of them answer for a name the other was
     * refused. Skipped rather than thrown, which is what this statement has always done — the script is
     * left to fail on the name itself if it actually uses it.</p>
     */
    @Test
    public void aRefusedModuleIsRefused() throws Throwable {
        workspace.edit("util.Secret", "exports.value = 'should never be reachable';\n");
        JsLanguage.restrictTo(com.crystalgui.language.run.ScriptPolicy.denying(List.of("util")));
        try {
            // ASSERTED FROM INSIDE THE SCRIPT, not through the Sink. `ScriptPolicy.ALWAYS_REFUSED` covers
            // `com.crystalgui.language` and this test class lives there, so the moment ANY policy is in
            // force the Sink is refused too -- correctly, and the floor doing exactly what it exists for.
            // A script that throws when the name is bound needs no host object at all.
            run("import util.Secret;\n"
                    + "if (typeof Secret !== 'undefined') {\n"
                    + "    throw new Error('a refused module was bound anyway');\n"
                    + "}\n");
        } finally {
            JsLanguage.restrictTo(com.crystalgui.language.run.ScriptPolicy.allowAll());
        }
    }

    // ── No save ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Editing the imported script — without saving it — changes the next run.</b>
     *
     * <p>The JavaScript statement of what M15 S5 asserts for Java. There is no compiled-script cache in
     * the way here, so this is really a claim about {@code JsModules}' own cache: it lives for one
     * execution and no longer, because a module held from a previous run would pin text the author has
     * already changed.</p>
     */
    @Test
    public void editingAnImportedScriptChangesTheNextRun() throws Throwable {
        workspace.edit("util.Greeter", "exports.hi = function () { return 'first'; };\n");
        String main = "import util.Greeter;\n" + SINK + ".write(Greeter.hi());\n";
        run(main);

        workspace.edit("util.Greeter", "exports.hi = function () { return 'second'; };\n");
        run(main);

        assertEquals(List.of("first", "second"), List.copyOf(Sink.WRITTEN));
    }

    // ── A file nobody has open ────────────────────────────────────────────

    /** A workspace that answers null the FIRST time, as the real index does for an unopened file. */
    private static final class NotYet implements ProjectSources {
        private final Map<String, String> files = new LinkedHashMap<>();
        private final java.util.Set<String> asked = new java.util.HashSet<>();

        NotYet holding(String qualifiedName, String source) {
            files.put(qualifiedName, source);
            return this;
        }

        @Override
        public String sourceOf(String qualifiedName) {
            // FIRST ASK SCHEDULES A READ AND ANSWERS NOTHING -- the editor's contract, and exactly what
            // ProjectIndex does for a file that is neither open nor cached.
            if (!files.containsKey(qualifiedName)) return null;
            return asked.add(qualifiedName) ? null : files.get(qualifiedName);
        }

        @Override
        public String awaitSourceOf(String qualifiedName) {
            asked.add(qualifiedName);
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

    /**
     * <b>A module nobody has opened is waited for, not skipped.</b>
     *
     * <p>The defect the harness fixture found on its first run, and it is worth stating precisely because
     * every part of it behaves correctly. {@code sourceOf} answers null for a file that is neither open
     * nor cached and schedules a read — right on a keystroke, where being one analysis late is invisible.
     * A run has no next time: the import is skipped, and the script dies on
     * {@code "Formatter" is not defined} for a name declared two files away.</p>
     *
     * <p>It presented as an <b>asymmetry</b>, which is what made it legible: {@code Greeter} resolved and
     * {@code Formatter} did not, because the editor had already asked for the first while analysing the
     * file that imports it and nothing had ever asked for the second.</p>
     *
     * <p>The second-run-works shape is the worst a bug can have, so this asserts on the FIRST run.</p>
     */
    @Test
    public void aModuleNobodyHasOpenIsWaitedForRatherThanSkipped() throws Throwable {
        ProjectSourcesRegistry.resetForTesting();
        ProjectSourcesRegistry.contribute(
                new NotYet().holding("util.Cold", "exports.word = function () { return 'cold'; };\n"));

        run("import util.Cold;\n" + SINK + ".write(Cold.word());\n");

        assertEquals("the first run skipped an import that was only one read away",
                List.of("cold"), List.copyOf(Sink.WRITTEN));
    }

    /**
     * <b>A name belonging to the OTHER language is not treated as a module.</b>
     *
     * <p>{@code SourceRoots} names any file under a declared root whatever its extension, and both
     * {@code src/main/java} and {@code src/main/js} are declared — so one index holds both languages'
     * names with nothing in a name to tell them apart. Evaluating a {@code .java} file as a script would
     * report a syntax error about a file the author never opened; falling through to the Java tier is the
     * answer, and it is what makes the harness workspace able to hold both fixtures at once.</p>
     */
    @Test
    public void aJavaFileInTheWorkspaceIsNotLoadedAsAModule() throws Throwable {
        workspace.edit("util.Sibling", "package util;\npublic class Sibling { }\n", ".java");

        // The workspace declares the NAME, so without the guard the module tier would happily evaluate
        // Java as JavaScript. With it, the tier declines and the Java tier finds no class of that name --
        // leaving the binding absent, which is the honest answer and not a syntax error about a file the
        // author never opened.
        run("import util.Sibling;\n"
                + "if (typeof Sibling !== 'undefined') {\n"
                + "    throw new Error('a .java file was bound as a module');\n"
                + "}\n");
    }

    // ── Exporting without saying so ────────────────────────────────────────────

    /**
     * <b>A module that says nothing exports its top-level declarations.</b>
     *
     * <p>The default, and it is the same argument that made the import statement ours rather than ES's:
     * an author writing {@code import util.Greeter;} should not then have to write Node's
     * {@code exports.greet = …} on the other side. A plain {@code function} is a declaration and reads
     * like one.</p>
     */
    @Test
    public void aModuleWithNoExportsOffersItsTopLevelDeclarations() throws Throwable {
        workspace.edit("util.Plain",
                "function hi() { return 'hi'; }\n"
                + "var name = 'plain';\n");

        run("import util.Plain;\n" + SINK + ".write(Plain.hi() + '/' + Plain.name);\n");

        assertEquals(List.of("hi/plain"), List.copyOf(Sink.WRITTEN));
    }

    /**
     * <b>...and a module that DOES say so is taken at its word.</b>
     *
     * <p>The reason the explicit form stays. With nothing else in a file to go on, "top-level" and
     * "exported" are the same set — so assigning to {@code exports} is the only way to keep a helper
     * private, and adding the top-level names on top would export exactly what the author was being
     * explicit in order to hide.</p>
     */
    @Test
    public void anExplicitExportKeepsTheRestPrivate() throws Throwable {
        workspace.edit("util.Guarded",
                "function secret() { return 'secret'; }\n"
                + "exports.open = function () { return 'open'; };\n");

        run("import util.Guarded;\n"
                + SINK + ".write(typeof Guarded.secret);\n"
                + SINK + ".write(Guarded.open());\n");

        assertEquals(List.of("undefined", "open"), List.copyOf(Sink.WRITTEN));
    }

    /** <b>A module's own imports stay out of its exports</b> — they are bindings, not declarations. */
    @Test
    public void anImportedNameIsNotItselfExported() throws Throwable {
        workspace.edit("util.Leaf", "function word() { return 'leaf'; }\n");
        workspace.edit("util.Branch",
                "import util.Leaf;\n"
                + "function say() { return Leaf.word(); }\n");

        run("import util.Branch;\n"
                + SINK + ".write(Branch.say() + '/' + typeof Branch.Leaf);\n");

        assertEquals(List.of("leaf/undefined"), List.copyOf(Sink.WRITTEN));
    }
}
