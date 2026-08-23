package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.ProjectSources;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * One project file resolving against another — M15 S4.
 *
 * <h3>What is at stake, and why the registry being shared is half of it</h3>
 *
 * <p>The compiler runs <b>inside the engine band</b>, behind a child-first class loader. It reads
 * {@link ProjectSourcesRegistry} as a static, which only works because {@code com.crystalgui.text.} is
 * parent-first — a registry one package over would be <em>redefined</em> in the band, so the provider
 * this test registers would be written to one set of statics and read from another. §15.5 A shipped
 * exactly that, and its symptom was not a failure: everything resolved from files and the feature was
 * quietly inert for a release.</p>
 *
 * <p>So every test here doubles as proof that the crossing works, because a registration that did not
 * reach the band would leave the type unresolved and every assertion would fail.</p>
 */
public class ProjectSourceResolutionTest extends FixFixture {

    /** A stand-in workspace: qualified name to source text, with no I/O anywhere. */
    private static final class Workspace implements ProjectSources {

        private final Map<String, String> files = new LinkedHashMap<>();

        Workspace declare(String qualifiedName, String source) {
            files.put(qualifiedName, source);
            return this;
        }

        /** Where the real index would say the file is — derived, so a test cannot forget to set it. */
        @Override
        public String pathOf(String qualifiedName) {
            if (!files.containsKey(qualifiedName)) return null;
            return "proj:src/main/java/" + qualifiedName.replace('.', '/') + ".java";
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
    }

    @After
    public void clearRegistry() {
        ProjectSourcesRegistry.resetForTesting();
    }

    private static Workspace workspace() {
        Workspace workspace = new Workspace();
        ProjectSourcesRegistry.contribute(workspace);
        return workspace;
    }

    private static List<String> errorsIn(String className, String source) {
        List<String> out = new ArrayList<>();
        try (SourceAnalyzer.Analysis analysis = analyse(className, source, newestLevel())) {
            for (Diagnostic d : analysis.diagnostics()) {
                if (d.severity() == DiagnosticSeverity.ERROR) out.add(d.message());
            }
        }
        return out;
    }

    // ── The headline ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A file uses a type declared in another project file, and it compiles.</b>
     *
     * <p>The whole milestone in one assertion. Before this, the analyser was handed one source string and
     * a classpath of jars, so the only possible answer was "cannot be resolved to a type".</p>
     */
    @Test
    public void aTypeDeclaredInAnotherProjectFileResolves() {
        workspace().declare("com.example.Helper",
                "package com.example;\n"
                + "public class Helper {\n"
                + "    public static String greet(String who) { return \"hi \" + who; }\n"
                + "}\n");

        String main = "package com.example;\n"
                + "public class Main {\n"
                + "    void go() { System.out.println(Helper.greet(\"world\")); }\n"
                + "}\n";

        assertEquals("the other file's type should resolve", List.of(), errorsIn("Main", main));
    }

    /**
     * <b>...and it resolves to the RIGHT thing, with its real signature.</b>
     *
     * <p>An empty error list alone would pass against a compiler that silently gave up and treated the
     * name as an unresolved-but-tolerated stub, which is what binding recovery does.</p>
     */
    @Test
    public void theResolvedTypeCarriesItsRealMembers() {
        workspace().declare("com.example.Helper",
                "package com.example;\n"
                + "public class Helper {\n"
                + "    public static String greet(String who) { return who; }\n"
                + "}\n");

        String main = "package com.example;\n"
                + "public class Main {\n"
                + "    void go() { String out = Helper.greet(\"x\"); }\n"
                + "}\n";

        try (SourceAnalyzer.Analysis analysis = analyse("Main", main, newestLevel())) {
            SymbolInfo greet = analysis.resolveAt(main.indexOf("greet(") + 1);
            assertNotNull("the member of another project file did not resolve", greet);
            assertEquals("greet", greet.name());
            assertTrue("its return type came back as " + greet.type(),
                    String.valueOf(greet.type()).contains("String"));
        }
    }

    /**
     * <b>An unresolved name is still unresolved.</b>
     *
     * <p>The control. Without it every assertion above passes against an environment that answers
     * something for everything — which is exactly what a too-eager stub tier would do.</p>
     */
    @Test
    public void aTypeNobodyDeclaresStillFails() {
        workspace();
        String main = "package com.example;\n"
                + "public class Main {\n"
                + "    void go() { Absent.thing(); }\n"
                + "}\n";

        assertTrue("an undeclared type must still be an error", !errorsIn("Main", main).isEmpty());
    }

    // ── The traps ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A file is never resolved against itself.</b>
     *
     * <p>The unit under analysis is already in ECJ's {@code unitsToProcess}. If the environment answers
     * for the same name as well, the type is declared twice and the duplicate error lands on the author's
     * own class — an error about their code, caused entirely by ours.</p>
     */
    @Test
    public void theFileBeingAnalysedIsNotAlsoSuppliedToItself() {
        String main = "package com.example;\n"
                + "public class Main {\n"
                + "    void go() { }\n"
                + "}\n";
        workspace().declare("com.example.Main", main);

        assertEquals("the unit under analysis was supplied to itself", List.of(), errorsIn("Main", main));
    }

    /**
     * <b>An intermediate package with no types of its own still resolves.</b>
     *
     * <p>ECJ asks about each segment of a qualified name <em>before</em> it looks the type up, so
     * {@code com} — which declares nothing — has to answer true or {@code com.example.deep.Helper} never
     * resolves at all. §15.5 records the same trap from the other direction.</p>
     */
    @Test
    public void anIntermediatePackageResolves() {
        workspace().declare("com.example.deep.Helper",
                "package com.example.deep;\npublic class Helper { public static int n() { return 1; } }\n");

        String main = "package other;\n"
                + "import com.example.deep.Helper;\n"
                + "public class Main {\n"
                + "    void go() { int n = Helper.n(); }\n"
                + "}\n";

        assertEquals(List.of(), errorsIn("Main", main));
    }

    /**
     * <b>The text is read fresh every time, never cached by the environment.</b>
     *
     * <p>Another editor's buffer changes on any keystroke, and an environment outlives the call that
     * built it. Caching a source unit here would pin a file as it was when some earlier analysis happened
     * to ask — so an author would fix a signature in one file and keep seeing the old error in another.</p>
     */
    @Test
    public void anEditInTheOtherFileIsSeenImmediately() {
        Workspace workspace = workspace();
        workspace.declare("com.example.Helper",
                "package com.example;\npublic class Helper { public static int n() { return 1; } }\n");

        String main = "package com.example;\n"
                + "public class Main {\n"
                + "    void go() { String s = Helper.text(); }\n"
                + "}\n";
        assertTrue("Helper.text() does not exist yet, so this must fail",
                !errorsIn("Main", main).isEmpty());

        workspace.declare("com.example.Helper",
                "package com.example;\npublic class Helper { public static String text() { return \"\"; } }\n");
        assertEquals("the edit in the other file was not seen", List.of(), errorsIn("Main", main));
    }

    // ── The harness's own shape ───────────────────────────────────────────

    /**
     * <b>An import of a SUB-package of the importing file's own package.</b>
     *
     * <p>{@code anIntermediatePackageResolves} puts the importer in {@code other}, which is unrelated to
     * what it imports. The harness fixture does the commoner thing — {@code com.example.Main} importing
     * {@code com.example.util.Greeter} — and that is a different question for the compiler: the prefix it
     * is asked about is the importing unit's OWN package, which is already known and which the unit under
     * analysis is deliberately excluded from answering for.</p>
     */
    @Test
    public void anImportOfASubPackageOfMyOwnResolves() {
        workspace().declare("com.example.util.Greeter",
                "package com.example.util;\npublic class Greeter { public String greet() { return \"hi\"; } }\n");

        String main = "package com.example;\n"
                + "import com.example.util.Greeter;\n"
                + "public class Main {\n"
                + "    void go() { String s = new Greeter().greet(); }\n"
                + "}\n";

        assertEquals(List.of(), errorsIn("Main", main));
    }

    /**
     * <b>A provider that is not ready yet must not make the PACKAGE disappear.</b>
     *
     * <p>The real index reads over a wire, so {@code sourceOf} answers null on the first ask and schedules
     * a read — while {@code declaresPackage} answers immediately, because names come from paths and cost
     * no I/O. Every stand-in above answers both instantly, so the asymmetry the real one has is untested.</p>
     *
     * <p>What matters is WHICH error comes out. "cannot be resolved to a type" is a file that has not
     * arrived yet and will, and the next analysis fixes it. "The import com.example.util cannot be
     * resolved" is the package being denied, which no later read can repair.</p>
     */
    @Test
    public void aSourceThatHasNotArrivedYetStillLeavesThePackageDeclared() {
        NotYet workspace = new NotYet();
        ProjectSourcesRegistry.contribute(workspace);

        String main = "package com.example;\n"
                + "import com.example.util.Greeter;\n"
                + "public class Main {\n"
                + "    void go() { String s = new Greeter().greet(); }\n"
                + "}\n";

        List<String> first = errorsIn("Main", main);
        for (String error : first) {
            assertTrue("the package must stay declared even while its text is in flight, but got: "
                    + error, !error.contains("The import com.example.util cannot be resolved"));
        }

        // ...and once the read lands, the next analysis is clean.
        assertEquals("the arrived text did not resolve", List.of(), errorsIn("Main", main));
    }

    /** A workspace whose names are instant and whose TEXT arrives one ask late. @see ProjectIndex */
    private static final class NotYet implements ProjectSources {
        private boolean asked;

        @Override
        public String sourceOf(String qualifiedName) {
            if (!"com.example.util.Greeter".equals(qualifiedName)) return null;
            if (!asked) {
                asked = true;
                return null;
            }
            return "package com.example.util;\npublic class Greeter { public String greet() { return \"hi\"; } }\n";
        }

        @Override
        public boolean declaresPackage(String packageName) {
            return "com".equals(packageName)
                    || "com.example".equals(packageName)
                    || "com.example.util".equals(packageName);
        }
    }

    // ── Navigation and documentation ─────────────────────────────────────────

    /**
     * <b>Ctrl+B on a project type points at the FILE, not at a library.</b>
     *
     * <p>Every declaration this engine produced for a symbol outside the edited unit was
     * {@code DeclarationSite.inLibrary}, because that was the only kind there was. The workbench routes a
     * non-project resource to the decompiler, so a type declared two files away opened a viewer on a
     * {@code .class} that had never been compiled — <b>an empty tab</b>. Nothing failed: the site was
     * well-formed, the routing was correct, and the answer was a truthful description of the wrong
     * thing.</p>
     */
    @Test
    public void aProjectTypeDeclaresItselfInTheWorkspace() {
        workspace().declare("com.example.util.Greeter",
                "package com.example.util;\n"
                + "public class Greeter {\n"
                + "    public String greet(String who) { return who; }\n"
                + "}\n");

        String main = "package com.example;\n"
                + "import com.example.util.Greeter;\n"
                + "public class Main {\n"
                + "    void go() { String s = new Greeter().greet(\"x\"); }\n"
                + "}\n";

        try (SourceAnalyzer.Analysis analysis = analyse("Main", main, newestLevel())) {
            SymbolInfo greet = analysis.resolveAt(main.indexOf(".greet(") + 2);
            assertNotNull("the sibling's member did not resolve", greet);
            DeclarationSite site = greet.declaration();
            assertNotNull("no declaration site at all", site);
            assertNotNull("a project type must name a resource", site.resource());
            assertTrue("it was described as a library, so Ctrl+B opens the decompiler on a .class "
                    + "that does not exist: " + site.resource(), site.resource().isProject());
            assertTrue("the site names the wrong file: " + site.resource(),
                    site.resource().toString().contains("Greeter.java"));
            assertTrue("a project site must carry a real line, not the (0,0) a decompiled type gets",
                    site.start().row() > 0);
        }
    }

    /**
     * <b>A project type's javadoc and real parameter names are quoted, like any other source.</b>
     *
     * <p>The same defect wearing a different hat. Documentation is read out of an <em>attached</em>
     * source, and a workspace file was not one — so a sibling's member hovered with a signature assembled
     * from its binding: no comment, and {@code shout(String)} with the parameter name dropped, which is
     * exactly what a classpath class with no sources attached looks like. The file was open in the next
     * tab.</p>
     */
    @Test
    public void aProjectTypeQuotesItsJavadocAndParameterNames() {
        workspace().declare("com.example.util.Formatter",
                "package com.example.util;\n"
                + "public final class Formatter {\n"
                + "    /** Trims and shouts the given text. */\n"
                + "    public static String shout(String text) { return text; }\n"
                + "}\n");

        String main = "package com.example;\n"
                + "import com.example.util.Formatter;\n"
                + "public class Main {\n"
                + "    void go() { String s = Formatter.shout(\"x\"); }\n"
                + "}\n";

        try (SourceAnalyzer.Analysis analysis = analyse("Main", main, newestLevel())) {
            SymbolInfo shout = analysis.resolveAt(main.indexOf(".shout(") + 2);
            assertNotNull("the sibling's member did not resolve", shout);
            assertNotNull("no javadoc was quoted from the project file", shout.documentation());
            assertTrue("the quoted javadoc was " + shout.documentation(),
                    shout.documentation().contains("shouts"));
            assertNotNull("no signature", shout.signature());
            assertTrue("the parameter name was dropped, which is what an unattached classpath class "
                    + "looks like: " + shout.signature(),
                    shout.signature().toString().contains("text"));
        }
    }
}
