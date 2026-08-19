package com.crystalgui.language.js;

import com.crystalgui.fs.Resource;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.grammar.TreeSitterLanguages;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.language.run.ScriptRuntimes;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>M10.2: the JavaScript engine is reachable from an application, not only from a test.</b>
 *
 * <p>The twin of {@code JavaLanguageRegistrationTest}, and it exists for the reason that one records:
 * everything an engine builds can pass its own tests while being <em>unreachable</em>, because each test
 * constructs the engine itself and no test asks the registry. So this goes through the front door —
 * {@link JsLanguage#register()} with the same system property a deployment sets — and then asks the
 * <b>registry</b> and the <b>runtime list</b> for what a workbench would get.</p>
 *
 * <h3>And it is the first test of the second engine, which makes it the first test of the seams</h3>
 *
 * <p>Two registries were built at M9.5's close specifically so a second language would need no edits to
 * the shell: {@code LanguageRegistry.Entry.withServices} and {@code ScriptRuntimes.contribute}. Until now
 * both had exactly one caller, which is not evidence. Registering Java and JavaScript together — in both
 * orders, beside tree-sitter — is what turns them from a claim into a fact.</p>
 */
public class JsLanguageRegistrationTest {

    @BeforeClass
    public static void registerAsAnApplicationWould() {
        // THE GRAMMARS FIRST, and then BOTH engines, exactly as a host does. That order is the one that
        // used to be load-bearing without anybody knowing: `TreeSitterLanguages.register` built a bare
        // entry and discarded whatever services were on it, so registering an engine first silently
        // threw it away. Both sides read-then-write now, and this test running with three registrations
        // against overlapping extensions is what keeps it that way.
        TreeSitterLanguages.register(null);

        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    private static LanguageRegistry.Entry entryFor(String fileName) {
        return LanguageRegistry.forFileName(fileName);
    }

    // ── The registry ────────────────────────────────────────────────────────────────────────────

    @Test
    public void everyJavaScriptExtensionCarriesBothATokenizerAndAnEngine() {
        for (String name : new String[]{"Main.js", "module.mjs", "legacy.cjs"}) {
            LanguageRegistry.Entry entry = entryFor(name);
            assertSame(name, Language.JAVASCRIPT, entry.language());
            SyntaxTokenizer tokenizer = entry.newTokenizer();
            assertNotNull(name + " has no tokenizer", tokenizer);
            assertFalse(name + " fell back to the no-op tokenizer — the grammar registration was lost",
                    tokenizer == SyntaxTokenizer.NONE);

            TextBuffer buffer = new TextBuffer("var x = 1;\n");
            LanguageServices services =
                    entry.newServices(buffer, Resource.of(Resource.SCHEME_PROJECT, "src/" + name));
            assertNotNull(name + " has no engine behind it — nothing called withServices", services);
            assertEquals("javascript", services.id());
            services.close();
        }
    }

    /**
     * Registering the Java engine must not disturb the JavaScript one, or the other way round.
     *
     * <p>They claim different extensions, so this looks trivially true — and it is exactly what was not
     * true of tree-sitter versus an engine, for the same reason: whoever writes an entry decides what is
     * on it. Asserting both after both have registered is one line and closes the whole class.</p>
     */
    @Test
    public void thetwoEnginesCoexist() {
        LanguageServices js = entryFor("Main.js").newServices(new TextBuffer("var a;"), null);
        LanguageServices java = entryFor("Main.java").newServices(new TextBuffer("class A {}"), null);
        assertNotNull(js);
        assertNotNull(java);
        assertEquals("javascript", js.id());
        assertEquals("java", java.id());
        js.close();
        java.close();
    }

    // ── The Run panel's side ────────────────────────────────────────────────────────────────────

    @Test
    public void theRunPanelFindsARuntimeForAJavaScriptFile() {
        ScriptRuntimes runtimes = ScriptRuntimes.open(null);
        try {
            ScriptRuntime js = runtimes.forFile("Main.js");
            assertNotNull("no runtime claims a .js file — ScriptRuntimes.contribute was never called",
                    js);
            assertSame(Language.JAVASCRIPT, js.language());
            // AND THE OTHER ONE IS STILL THERE. A contribution that replaced rather than added would
            // pass every assertion above and lose Java, which is the failure this pair exists to catch.
            ScriptRuntime java = runtimes.forFile("Main.java");
            assertNotNull("the Java runtime went missing when JavaScript registered", java);
            assertSame(Language.JAVA, java.language());
            assertEquals(2, runtimes.all().size());
        } finally {
            runtimes.close();
        }
    }

    /** A file no runtime claims still answers null rather than guessing. */
    @Test
    public void aFileNoRuntimeClaimsIsRefused() {
        ScriptRuntimes runtimes = ScriptRuntimes.open(null);
        try {
            assertEquals(null, runtimes.forFile("notes.txt"));
            assertEquals(null, runtimes.forFile("shader.frag"));
        } finally {
            runtimes.close();
        }
    }

    // ── The engine actually answers ─────────────────────────────────────────────────────────────

    /**
     * A syntax error reaches the document, through the whole stack.
     *
     * <p>Registry → services → scheduler-less analysis → {@code onDiagnostics}. Every one of those was
     * built at a different milestone and this is the first thing that runs them end to end for
     * JavaScript.</p>
     */
    @Test
    public void aSyntaxErrorArrivesAsADiagnosticOnItsOwnLine() {
        TextBuffer buffer = new TextBuffer("var a = 1;\nfunction (\n");
        LanguageServices services = entryFor("Broken.js")
                .newServices(buffer, Resource.of(Resource.SCHEME_PROJECT, "src/Broken.js"));
        try {
            List<Diagnostic> reported = announced(services);
            assertFalse("Rhino reported nothing about an unclosed parameter list", reported.isEmpty());
            boolean anyError = false;
            for (Diagnostic problem : reported) {
                if (problem.severity() == DiagnosticSeverity.ERROR) anyError = true;
            }
            assertTrue("no error among " + reported, anyError);
            // ON THE SECOND LINE, which is the half that proves the offset conversion. A row/column
            // built against the wrong text lands on line 0 and looks plausible.
            boolean onSecondLine = false;
            for (Diagnostic problem : reported) {
                if (problem.hasPosition() && problem.start().row() == 1) onSecondLine = true;
            }
            assertTrue("nothing was reported on the broken line: " + reported, onSecondLine);
        } finally {
            services.close();
        }
    }

    /** A file that parses reports nothing, which is the other half of the same claim. */
    @Test
    public void aCleanScriptReportsNoProblems() {
        TextBuffer buffer = new TextBuffer("var greeting = 'hi';\nfunction add(a, b) { return a + b; }\n");
        LanguageServices services = entryFor("Clean.js").newServices(buffer, null);
        try {
            assertEquals(List.of(), announced(services));
        } finally {
            services.close();
        }
    }

    /**
     * The compile gate: a broken script is refused before anything tries to run it.
     *
     * <p>Separate from the diagnostic above, and deliberately so — the analyser tells the <em>editor</em>
     * what is wrong, and the runtime decides whether Run may start. Two engines' worth of experience says
     * those drift apart when one is derived from the other.</p>
     */
    @Test
    public void aBrokenScriptDoesNotCompileAndSaysWhy() {
        ScriptRuntimes runtimes = ScriptRuntimes.open(null);
        try {
            ScriptRuntime js = runtimes.forFile("Main.js");
            ScriptRuntime.Compiled broken = js.compileScript("Main.js", "function (", Map.of());
            assertFalse(broken.successful());
            assertFalse("a refusal with no message tells the author nothing",
                    broken.messages().isEmpty());

            ScriptRuntime.Compiled fine = js.compileScript("Main.js", "var x = 1 + 1;", Map.of());
            assertTrue(fine.messages().toString(), fine.successful());
            assertSame(js, fine.runtime());
        } finally {
            runtimes.close();
        }
    }

    /**
     * Rhino 1.9.1 resolves its regular-expression engine through {@code ServiceLoader}, and the lookup
     * reads the <b>thread's</b> classloader — so a regex compiles only if the executor installed the
     * engine loader before touching Rhino.
     *
     * <p>Asserted through the front door because that is where it broke: the capability probe found this
     * by measuring, and the fix lives inside {@code RhinoExecutor}. A test that reached past it would
     * pass while the shipped path stayed broken, on bands 11 and 17 only, with band 8 working — which is
     * the worst possible distribution of a failure.</p>
     */
    @Test
    public void aRegularExpressionCompiles() {
        ScriptRuntimes runtimes = ScriptRuntimes.open(null);
        try {
            ScriptRuntime.Compiled compiled = runtimes.forFile("Main.js")
                    .compileScript("Main.js", "var m = /a(b)c/.exec('zabc');", Map.of());
            assertTrue(compiled.messages().toString(), compiled.successful());
        } finally {
            runtimes.close();
        }
    }

    /**
     * The last list a document's services announced.
     *
     * <p>No scheduler was given, so the analysis ran on the calling thread inside the constructor and the
     * announcement is already waiting — {@code onDiagnostics} replays it to a listener that attaches
     * late, which is the behaviour a view attaching after the first analysis depends on and is exercised
     * here for free.</p>
     */
    private static List<Diagnostic> announced(LanguageServices services) {
        List<List<Diagnostic>> seen = new ArrayList<>();
        services.onDiagnostics(announcement -> seen.add(announcement.orElse(List.of()))).disconnect();
        assertFalse("nothing was announced — the analysis never ran", seen.isEmpty());
        return seen.get(seen.size() - 1);
    }
}
