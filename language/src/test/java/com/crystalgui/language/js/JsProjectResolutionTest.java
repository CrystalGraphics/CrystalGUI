package com.crystalgui.language.js;

import com.crystalgui.language.TestWorkspace;
import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An imported script resolving in the EDITOR — M15 S7.
 *
 * <h3>The half §24.6 called the largest unknown</h3>
 *
 * <p>S6 made {@code import util.Greeter;} bind another script's exports at run time. That is a value:
 * {@code require()} hands back whatever the module assigned, so knowing it statically is a genuine
 * analysis problem rather than a lookup. {@link com.crystalgui.language.js.rhino.JsExports} reads the
 * three shapes people write and says so; these pin what that buys and, just as importantly, what it does
 * not.</p>
 *
 * <p>The editor's answer has to agree with the runtime's, in the same order — a name that completes as a
 * Java class and runs as a project script would be worse than either alone.</p>
 */
public class JsProjectResolutionTest {

    private static final String CARET = "|";

        private TextBuffer buffer;
    private JsLanguageServices services;
    private TestWorkspace workspace;

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @Before
    public void openWorkspace() {
        ProjectSourcesRegistry.resetForTesting();
        workspace = new TestWorkspace(".js");
        ProjectSourcesRegistry.contribute(workspace);
    }

    @After
    public void closeDocument() {
        if (services != null) services.close();
        ProjectSourcesRegistry.resetForTesting();
    }

    private JsLanguageServices servicesFor(String text) {
        buffer = new TextBuffer(text);
        if (services != null) services.close();
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null,
                JsLanguage.typeIndexForTesting());
        return services;
    }

    private CompletionList completeAt(String fixture) {
        int caret = fixture.indexOf(CARET);
        assertTrue("the fixture has no caret marker", caret >= 0);
        String text = fixture.substring(0, caret) + fixture.substring(caret + 1);
        servicesFor(text);

        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        AtomicReference<CompletionList> answered = new AtomicReference<>();
        services.completion().complete(
                CompletionProvider.Request.explicit(caret, text.substring(start, caret)),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));
        assertNotNull("the provider never answered", answered.get());
        return answered.get();
    }

    private static List<String> names(CompletionList list) {
        List<String> names = new ArrayList<>(list.size());
        for (CompletionItem item : list.items()) names.add(item.filterKey());
        return names;
    }

    private SymbolInfo resolveAt(String fixture) {
        int caret = fixture.indexOf(CARET);
        assertTrue("the fixture has no caret marker", caret >= 0);
        String text = fixture.substring(0, caret) + fixture.substring(caret + 1);
        AtomicReference<SymbolInfo> answered = new AtomicReference<>();
        servicesFor(text).resolver().resolveAt(caret, versioned -> answered.set(versioned.orElse(null)));
        return answered.get();
    }

    // ── The headline ────────────────────────────────────────────────────────────────────────────

    /**
     * <b>An imported script's exports complete.</b> S7's exit criterion.
     *
     * <p>Before this, the name resolved through the Java tier — which knows nothing about it — so the
     * popup behind the dot was empty, on a file sitting in the next tab.</p>
     */
    @Test
    public void anImportedScriptsExportsComplete() {
        workspace.edit("util.Greeter",
                "exports.hi = function () { return 'hi'; };\n"
                + "exports.bye = function () { return 'bye'; };\n");

        List<String> offered = names(completeAt("import util.Greeter;\nGreeter.|\n"));

        assertTrue("the module's exports were not offered: " + offered, offered.contains("hi"));
        assertTrue("the module's exports were not offered: " + offered, offered.contains("bye"));
    }

    /** <b>{@code module.exports = { … }} is read too</b> — the second of the three shapes. */
    @Test
    public void anObjectAssignedToModuleExportsIsRead() {
        workspace.edit("util.Api",
                "module.exports = {\n"
                + "    open: function () { },\n"
                + "    close: function () { }\n"
                + "};\n");

        List<String> offered = names(completeAt("import util.Api;\nApi.|\n"));

        assertTrue("open was not offered: " + offered, offered.contains("open"));
        assertTrue("close was not offered: " + offered, offered.contains("close"));
    }

    /** <b>...and {@code module.exports.name = …}</b>, the third. */
    @Test
    public void aQualifiedExportsAssignmentIsRead() {
        workspace.edit("util.Late", "module.exports.ready = true;\n");

        assertTrue(names(completeAt("import util.Late;\nLate.|\n")).contains("ready"));
    }

    /**
     * <b>A conditional export is reported.</b>
     *
     * <p>Collected wherever an assignment appears rather than only at the top of a file, because
     * {@code if (supported) { exports.fast = … }} is ordinary and its export is real. The cost is that it
     * reads as unconditional — the same over-reporting a person does by eye, and much less wrong than
     * dropping it.</p>
     */
    @Test
    public void anExportInsideABranchIsStillFound() {
        workspace.edit("util.Maybe",
                "if (someFlag) {\n"
                + "    exports.fast = function () { };\n"
                + "}\n");

        assertTrue(names(completeAt("import util.Maybe;\nMaybe.|\n")).contains("fast"));
    }

    // ── Hover ───────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The imported name itself resolves, and says it is a module.</b>
     *
     * <p>Not a class, which is what the Java tier would have called it. The container is the file it came
     * from, so a hover can say where the value is written rather than only what it holds.</p>
     */
    @Test
    public void theImportedNameResolvesAsAModule() {
        workspace.edit("util.Greeter", "exports.hi = function () { };\n");

        SymbolInfo symbol = resolveAt("import util.Greeter;\nGree|ter.hi();\n");

        assertNotNull("the imported name did not resolve at all", symbol);
        assertEquals("Greeter", symbol.name());
        assertEquals(SymbolKind.MODULE, symbol.kind());
        assertNotNull("no type, so nothing can be listed behind the dot", symbol.type());
        assertEquals("util.Greeter", symbol.type().qualifiedName());
    }

    // ── The tiers, and the limits ───────────────────────────────────────────────────────────────

    /**
     * <b>A name the workspace does not declare is still resolved as a Java type.</b>
     *
     * <p>The regression guard, and the reason the project tier is added in FRONT of the Java one rather
     * than replacing it.</p>
     */
    @Test
    public void aNameTheWorkspaceDoesNotDeclareStillResolvesAsJava() {
        SymbolInfo symbol = resolveAt("import java.util.ArrayList;\nvar l = new Array|List();\n");

        assertNotNull("the Java import stopped resolving", symbol);
    }

    /**
     * <b>A module whose exports cannot be read statically resolves to a module with no members.</b>
     *
     * <p>The honest limit, pinned so it stays a known shape rather than a surprise. Exports built in a
     * loop are unreadable by any static pass — what matters is that the failure is <em>graceful</em>: the
     * name is still known, still typed as a module, and the popup is empty rather than the name being
     * undefined and drawn as an error.</p>
     */
    @Test
    public void aDynamicallyBuiltExportIsNotInvented() {
        workspace.edit("util.Dynamic",
                "var names = ['a', 'b'];\n"
                + "for (var i = 0; i < names.length; i++) { exports[names[i]] = i; }\n");

        SymbolInfo symbol = resolveAt("import util.Dynamic;\nDynam|ic.a;\n");
        assertNotNull("the name should still resolve even when its exports cannot be read", symbol);
        assertEquals(SymbolKind.MODULE, symbol.kind());

        List<String> offered = names(completeAt("import util.Dynamic;\nDynamic.|\n"));
        assertFalse("a name nobody can see statically was invented: " + offered, offered.contains("a"));
    }

    /**
     * <b>A module that imports another does not confuse the reader.</b>
     *
     * <p>{@code JsExports} blanks imports before parsing, exactly as the analyser and the executor do. A
     * reader that did not would fail on line 1 of every module that imports anything and report no
     * exports at all — which looks identical to a module that exports nothing.</p>
     */
    @Test
    public void aModuleThatImportsSomethingStillReportsItsExports() {
        workspace.edit("util.Inner", "exports.word = function () { };\n");
        workspace.edit("util.Outer",
                "import util.Inner;\n"
                + "exports.say = function () { return Inner.word(); };\n");

        assertTrue(names(completeAt("import util.Outer;\nOuter.|\n")).contains("say"));
    }

    // ── Navigation, and saying the same thing twice ──────────────────────────────────

    /**
     * <b>The IMPORT LINE describes the module too, not a class.</b>
     *
     * <p>An import statement is blanked before the parser sees it, so no node covers those offsets and
     * {@code resolveAt} cannot find it by walking — the spans survive on {@code JsImports.Imported} and a
     * separate path answers from them. That path went straight to the Java engine, so hovering
     * {@code Greeter} on the import line said <i>public class Greeter</i> while hovering the identical
     * name two rows below said <i>module Greeter</i>. The same name, two answers, in one file.</p>
     */
    @Test
    public void theImportLineDescribesTheModuleRatherThanAClass() {
        workspace.edit("util.Greeter", "exports.hi = function () { };\n");

        SymbolInfo onTheImport = resolveAt("import util.Gree|ter;\nGreeter.hi();\n");

        assertNotNull("the import line resolved to nothing at all", onTheImport);
        assertEquals("the import line still describes a Java class",
                SymbolKind.MODULE, onTheImport.kind());
    }

    /**
     * <b>Ctrl+B on an imported module opens its file.</b>
     *
     * <p>Without a declaration site the name resolves, hovers correctly and simply cannot be opened —
     * which reads as navigation being unimplemented rather than as one field nobody filled in. The site is
     * a PROJECT resource, so the workbench routes it to the editor rather than to the decompiler.</p>
     */
    @Test
    public void anImportedModuleCanBeNavigatedTo() {
        workspace.edit("util.Greeter", "exports.hi = function () { };\n");

        SymbolInfo symbol = resolveAt("import util.Greeter;\nGree|ter.hi();\n");

        assertNotNull(symbol);
        assertNotNull("no declaration site, so Ctrl+B does nothing", symbol.declaration());
        assertNotNull("a module must name a resource to be opened", symbol.declaration().resource());
        assertTrue("the site does not point into the workspace: " + symbol.declaration().resource(),
                symbol.declaration().resource().isProject());
        assertTrue("the site names the wrong file: " + symbol.declaration().resource(),
                symbol.declaration().resource().toString().contains("Greeter.js"));
    }

    /**
     * <b>A member carries a signature and its own jump target.</b>
     *
     * <p>The signature is not decoration. A symbol without one falls through to the documentation popup's
     * <em>assembled</em> renderer, which paints from its own three bands instead of the editor's capture
     * scheme — so a module's member hovered in visibly different colours from every other member in the
     * same file, and read as a theming bug rather than a missing field.</p>
     *
     * <p>The site points at the line the export is written at, which is why {@code JsExports} reports an
     * offset per name rather than only the names.</p>
     */
    @Test
    public void aModuleMemberCarriesASignatureAndItsOwnSite() {
        workspace.edit("util.Greeter",
                "exports.first = function () { };\n"
                + "exports.second = 'value';\n");

        // ASKED THE WAY A HOVER ASKS — `resolveAt` on the member itself, which is what the popup does and
        // therefore what the reported symptom was about.
        SymbolInfo second = resolveAt("import util.Greeter;\nGreeter.sec|ond;\n");

        assertNotNull("the member did not resolve at all", second);
        assertEquals("second", second.name());
        assertNotNull("no signature, so the popup renders it in its own colours", second.signature());
        assertNotNull("no declaration site, so Ctrl+B on a member does nothing", second.declaration());
        assertTrue("the member's site does not point into the workspace",
                second.declaration().resource().isProject());
        assertTrue("a named export should point at its OWN line, not the top of the file",
                second.declaration().start().row() > 0);
    }

    // ── What a declaration says that an assignment cannot ────────────────────────────

    /** <b>Top-level declarations complete, with no {@code exports} in the file at all.</b> */
    @Test
    public void topLevelDeclarationsComplete() {
        workspace.edit("util.Plain",
                "function hi(who) { return who; }\n"
                + "var name = 'plain';\n");

        List<String> offered = names(completeAt("import util.Plain;\nPlain.|\n"));

        assertTrue("the function was not offered: " + offered, offered.contains("hi"));
        assertTrue("the value was not offered: " + offered, offered.contains("name"));
    }

    /**
     * <b>An exported function reads as a FUNCTION, with its parameters.</b>
     *
     * <p>The other half of why the implicit form is better, and the reason the hover was bare. A
     * declaration carries a kind and parameter names; an assignment of an anonymous function to a
     * property carries a name and little else — and {@code JsSignatures} renders from the KIND, so
     * calling every export a property printed the name alone where a function prints
     * {@code function hi(who)}.</p>
     */
    @Test
    public void anExportedFunctionCarriesItsKindAndParameters() {
        workspace.edit("util.Plain", "function hi(who, loudly) { return who; }\n");

        SymbolInfo hi = resolveAt("import util.Plain;\nPlain.h|i('x');\n");

        assertNotNull("the member did not resolve", hi);
        assertEquals(SymbolKind.FUNCTION, hi.kind());
        assertNotNull("no signature", hi.signature());
        assertTrue("the parameters are missing from " + hi.signature().text(),
                hi.signature().text().contains("who") && hi.signature().text().contains("loudly"));
    }

    /** <b>...and so does one assigned to {@code exports}</b>, which reads its right-hand side. */
    @Test
    public void anAssignedFunctionAlsoCarriesItsParameters() {
        workspace.edit("util.Assigned", "exports.hi = function (who) { return who; };\n");

        SymbolInfo hi = resolveAt("import util.Assigned;\nAssigned.h|i('x');\n");

        assertNotNull(hi);
        assertEquals(SymbolKind.FUNCTION, hi.kind());
        assertTrue("the parameter is missing from " + hi.signature().text(),
                hi.signature().text().contains("who"));
    }

    /**
     * <b>The owner is drawn as a MODULE, not a class.</b>
     *
     * <p>The popup discarded a non-type {@code containerKind} and fell back to guessing from the container
     * STRING — and {@code util.Greeter} looks like a type. It hid because the module symbol itself was
     * fine: its own kind is MODULE, so it reached a different branch and came out right. Only members
     * were wrong, which reads as a theming bug.</p>
     */
    @Test
    public void aModuleMemberNamesItsOwnerAsAModule() {
        workspace.edit("util.Plain", "function hi() { }\n");

        SymbolInfo hi = resolveAt("import util.Plain;\nPlain.h|i();\n");

        assertNotNull(hi);
        assertEquals("the owner is not reported as a module", SymbolKind.MODULE, hi.containerKind());
    }

    // ── An imported member describes itself as its own file does ───────────────────────

    /**
     * <b>A value export carries its type and the keyword that introduced it.</b>
     *
     * <p>The popup for {@code Greeter.defaultName} read {@code defaultName} while the same name hovered
     * in its own file read {@code var defaultName: string}. Two descriptions of one declaration, and the
     * poorer one shown in the place with less context — which is exactly backwards.</p>
     */
    @Test
    public void aValueExportCarriesItsTypeAndKeyword() {
        workspace.edit("util.Plain", "var defaultName = 'world';\n");

        SymbolInfo name = resolveAt("import util.Plain;\nPlain.default|Name;\n");

        assertNotNull("the member did not resolve", name);
        assertNotNull("no type, so the signature is a bare word", name.type());
        assertNotNull("no signature", name.signature());
        assertTrue("the keyword is missing from " + name.signature().text(),
                name.signature().text().contains("var"));
        assertTrue("the type is missing from " + name.signature().text(),
                name.signature().text().contains("string"));
    }

    /**
     * <b>And its documentation.</b>
     *
     * <p>An imported member showed its container and its signature with nothing underneath, which reads
     * as the member being undocumented rather than as a field dropped at the seam — the same failure the
     * Java side records for {@code describeMember}, arrived at from the other language.</p>
     */
    @Test
    public void anExportCarriesItsDocComment() {
        workspace.edit("util.Plain",
                "/** Trims and shouts. Null-safe. */\n"
                + "function shout(text) { return text; }\n");

        SymbolInfo shout = resolveAt("import util.Plain;\nPlain.sho|ut('x');\n");

        assertNotNull(shout);
        assertNotNull("the doc comment did not travel with the member", shout.documentation());
        assertTrue("the wrong text: " + shout.documentation(),
                shout.documentation().contains("Trims and shouts"));
    }

    /** <b>...including one written above an explicit {@code exports.} assignment.</b> */
    @Test
    public void anExplicitExportAlsoCarriesItsDocComment() {
        workspace.edit("util.Assigned",
                "/** Says hello. */\n"
                + "exports.hi = function (who) { return who; };\n");

        SymbolInfo hi = resolveAt("import util.Assigned;\nAssigned.h|i('x');\n");

        assertNotNull(hi);
        assertNotNull("the doc comment did not travel with the member", hi.documentation());
        assertTrue("the wrong text: " + hi.documentation(), hi.documentation().contains("Says hello"));
    }

    /**
     * <b>A top-level name is a FIELD, and every renderer agrees about that.</b>
     *
     * <p>Asserted as a KIND rather than as a colour, because the kind is what the editor's capture, the
     * popup's capture and the popup's keyword are all derived from — and teaching them one at a time is
     * how the same name came to be drawn as a field in the text and as a local in the popup hovering over
     * it. The keyword is asserted alongside because promoting the kind is exactly what dropped it: a
     * JavaScript field is still introduced by {@code var}, unlike a Java one.</p>
     */
    @Test
    public void aTopLevelNameIsAFieldEverywhereItIsDescribed() {
        workspace.edit("util.Plain", "var defaultName = 'world';\n");

        SymbolInfo imported = resolveAt("import util.Plain;\nPlain.default|Name;\n");
        assertNotNull(imported);
        assertEquals("an imported top-level name is not a field", SymbolKind.FIELD, imported.kind());
        assertNotNull("no signature", imported.signature());
        assertTrue("the keyword went missing from " + imported.signature().text(),
                imported.signature().text().startsWith("var "));
    }

    // \u2500\u2500 The workspace's own Java, from JavaScript \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /** A project Java class, as the harness fixture writes one. */
    private static final String PROJECT_JAVA = "package com.example;\n"
            + "public class Main {\n"
            + "    /** Says the quiet part. */\n"
            + "    public static String peepoo() { return \"fa\"; }\n"
            + "}\n";

    /**
     * <b>An imported project Java class resolves as a CLASS.</b>
     *
     * <p>The editor half of the tier the run gained. A {@code .java} file in the workspace is on no
     * classpath, so the interop probe has to reach it the way every other Java resolution does \u2014 through
     * the project tier the name environment has carried since M15 S4.</p>
     */
    @Test
    public void anImportedProjectJavaClassResolves() {
        workspace.edit("com.example.Main", PROJECT_JAVA, ".java");

        SymbolInfo symbol = resolveAt("import com.example.Main;\nMai|n.peepoo();\n");

        assertNotNull("the imported project Java class did not resolve", symbol);
        assertEquals(SymbolKind.CLASS, symbol.kind());
    }

    /** <b>...and its members complete behind the dot.</b> */
    @Test
    public void aProjectJavaClassesMembersComplete() {
        workspace.edit("com.example.Main", PROJECT_JAVA, ".java");

        List<String> offered = names(completeAt("import com.example.Main;\nMain.|\n"));

        assertTrue("no members offered for a project Java class: " + offered,
                offered.contains("peepoo"));
    }

    /** <b>...and one of them resolves as a METHOD, with somewhere to go.</b> */
    @Test
    public void aProjectJavaMemberResolvesAndCanBeNavigatedTo() {
        workspace.edit("com.example.Main", PROJECT_JAVA, ".java");

        SymbolInfo symbol = resolveAt("import com.example.Main;\nMain.peep|oo();\n");

        assertNotNull("the member did not resolve", symbol);
        assertEquals(SymbolKind.METHOD, symbol.kind());
        assertNotNull("Ctrl+B has nowhere to go", symbol.declaration());
    }

    /**
     * <b>A project Java class probed BEFORE the workspace declares it is not written off forever.</b>
     *
     * <p>The shape a real session takes and a fixture never does. {@code InteropResolver} caches a probe
     * <em>even when the type did not resolve</em> \u2014 right for the classpath, where a name that is not a
     * class today will not be one tomorrow, and wrong the moment the same probe can resolve against a
     * WORKSPACE file. The index crawls in the background and {@code sourceOf} schedules a read rather than
     * waiting, so the first ask for a project type routinely lands before there is an answer; cached, that
     * first miss is permanent for the life of the process. Completion empty, no hover, no Ctrl+B \u2014 and
     * nothing that ever retries.</p>
     */
    @Test
    public void aProjectJavaClassProbedBeforeItExistsIsRetried() {
        String fixture = "import com.example.Late;\nLat|e.hello();\n";
        // ASKED FIRST, with nothing to find. This is the call that used to poison the cache.
        resolveAt(fixture);

        workspace.edit("com.example.Late",
                "package com.example;\n"
                + "public class Late { public static String hello() { return \"hi\"; } }\n", ".java");

        SymbolInfo symbol = resolveAt(fixture);
        assertNotNull("nothing resolved after the file arrived", symbol);
        assertEquals("a miss from before the file existed was cached forever",
                SymbolKind.CLASS, symbol.kind());
    }

    /**
     * <b>...and editing one changes what completes, with no save.</b>
     *
     * <p>The other half of the same staleness. A jar's class is a fact for the life of the process, which
     * is what let all three caches here be keyed on the class name alone; a workspace file is whatever the
     * author last typed. Without this, a method added to {@code Main.java} in one tab never appears behind
     * {@code Main.} in the {@code .js} tab beside it \u2014 the same no-save promise M15 S5 makes for running.</p>
     */
    @Test
    public void editingAProjectJavaClassChangesWhatCompletes() {
        String fixture = "import com.example.Growing;\nGrowing.|\n";
        workspace.edit("com.example.Growing",
                "package com.example;\n"
                + "public class Growing { public static void one() { } }\n", ".java");
        assertTrue("the first member never appeared", names(completeAt(fixture)).contains("one"));

        workspace.edit("com.example.Growing",
                "package com.example;\n"
                + "public class Growing {\n"
                + "    public static void one() { }\n"
                + "    public static void two() { }\n"
                + "}\n", ".java");

        List<String> offered = names(completeAt(fixture));
        assertTrue("an unsaved edit to a project Java file did not reach the popup: " + offered,
                offered.contains("two"));
    }

    /**
     * <b>...and the same {@code import} line in JavaScript.</b>
     *
     * <p>Not the same code path: Java asks the index {@code childrenOf} and takes the packages it returns,
     * JavaScript asks {@code allUnder} and derives the next segment itself. One omission, two queries \u2014
     * so fixing either alone leaves the other exactly as broken.</p>
     */
    @Test
    public void anImportLineOffersAProjectPackage() {
        workspace.edit("com.example.Main",
                "package com.example;\npublic class Main { }\n", ".java");

        List<String> offered = names(completeAt("import com.|\n"));

        assertTrue("the workspace's own package was not offered: " + offered,
                offered.contains("example"));
    }
}
