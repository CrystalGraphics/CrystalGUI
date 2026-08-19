package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.js.rhino.resolve.InteropResolver;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.language.js.JsLanguage;

/**
 * <b>M10.6 — the four resolution tiers, and the Java engine behind the interop one.</b>
 *
 * <p>The milestone's own exit criteria, one test each: {@code new java.util.ArrayList()} answers what the
 * Java analyser answers for {@code ArrayList}; a JSDoc {@code @param {string}} types a parameter; the live
 * scope types a global after a run; and the tier that answered is visible on the {@link SymbolInfo}.</p>
 */
public class JsResolutionTest {

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        // JAVA FIRST HERE, because the interop tier is what most of this file is about -- but the lend is
        // retried per document, so the order is a convenience rather than a requirement.
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    private static Analysis analyse(String source) {
        return analyse(source, LiveScopeSnapshot.EMPTY);
    }

    private static Analysis analyse(String source, LiveScopeSnapshot live) {
        return JsLanguage.analyzer().analyze("Probe.js", source, 1L, live);
    }

    /** Resolves at the first occurrence of {@code needle}. */
    private static SymbolInfo resolve(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue("the fixture does not contain " + needle, offset >= 0);
        return analyse(source).resolveAt(offset);
    }

    /** Resolves at the LAST occurrence — for a use rather than the declaration. */
    private static SymbolInfo resolveLast(String source, String needle) {
        int offset = source.lastIndexOf(needle);
        assertTrue("the fixture does not contain " + needle, offset >= 0);
        return analyse(source).resolveAt(offset);
    }

    private static List<String> namesOf(List<SymbolInfo> symbols) {
        List<String> names = new ArrayList<>();
        for (SymbolInfo symbol : symbols) names.add(symbol.name());
        return names;
    }

    // ── The declaration tier ────────────────────────────────────────────────────────────────────

    @Test
    public void aLocalResolvesToItsDeclaration() {
        SymbolInfo symbol = resolveLast("function f() {\n  var count = 1;\n  return count;\n}\n", "count");
        assertNotNull("a declared local resolved to nothing", symbol);
        assertEquals("count", symbol.name());
        assertEquals(SymbolKind.LOCAL_VARIABLE, symbol.kind());
        assertNotNull("no declaration site, so go-to-definition has nowhere to send", symbol.declaration());
        assertEquals("the declaration is on the line it was written on",
                1, symbol.declaration().start().row());
    }

    @Test
    public void aConstIsAConstantAndIsFinal() {
        SymbolInfo symbol = resolve("const RATE = 1.5;\n", "RATE");
        assertEquals(SymbolKind.CONSTANT, symbol.kind());
        assertTrue("a const is not marked final", symbol.is(SymbolModifier.FINAL));
    }

    @Test
    public void aParameterIsAParameterAndItsOwnerIsTheFunction() {
        SymbolInfo symbol = resolveLast("function add(a, b) { return a + b; }\n", "a");
        assertEquals(SymbolKind.PARAMETER, symbol.kind());
        assertEquals("add", symbol.container());
    }

    @Test
    public void aTopLevelDeclarationIsContainedByTheFile() {
        assertEquals("Probe.js", resolve("var top = 1;\n", "top").container());
    }

    @Test
    public void anUndeclaredNameResolvesToNothing() {
        // Nothing declared it, no run defined it, and it is not a Java package chain. Answering null is
        // what makes the completion list and the hover say nothing rather than something invented.
        assertNull(resolve("missing();\n", "missing"));
    }

    // ── The inference tier ──────────────────────────────────────────────────────────────────────

    @Test
    public void aLiteralInitializerTypesItsVariable() {
        // NAMES THAT DO NOT OCCUR INSIDE `var`. `resolve` searches the source text, so `var a` and
        // `var r` find the letters of the keyword itself — the offset lands in a token that is not a
        // name, and the failure reads as inference being broken when the fixture is.
        assertEquals("string", resolve("var str = 'hi';\n", "str").type().displayName());
        assertEquals("number", resolve("var num = 42;\n", "num").type().displayName());
        assertEquals("boolean", resolve("var flag = true;\n", "flag").type().displayName());
        assertEquals("Array", resolve("var items = [1, 2];\n", "items").type().displayName());
        assertEquals("Object", resolve("var obj = { x: 1 };\n", "obj").type().displayName());
        assertEquals("RegExp", resolve("var pattern = /a+/;\n", "pattern").type().displayName());
        assertEquals("null", resolve("var nothing = null;\n", "nothing").type().displayName());
    }

    @Test
    public void aFunctionDeclarationIsAFunctionWithItsParameters() {
        SymbolInfo symbol = resolve("function add(a, b) { return a + b; }\n", "add");
        assertEquals(SymbolKind.FUNCTION, symbol.kind());
        assertEquals("both parameters are reported, typed or not", 2, symbol.parameters().size());
        assertEquals("?", symbol.parameters().get(0).displayName());
    }

    @Test
    public void aDeclarationWithNoInitializerHasNoType() {
        // The honest answer: `var x;` says nothing about what x will be, and a dynamic language means it.
        assertNull(resolve("var x;\n", "x").type());
    }

    @Test
    public void aLocalNamedLikeAPackageRootShadowsIt() {
        // `var com = {...}` makes `com.foo` an ordinary property read, exactly as it is at run time.
        SymbolInfo symbol = resolve("var com = { thing: 1 };\ncom.thing;\n", "com");
        assertEquals("Object", symbol.type().displayName());
    }

    // ── The interop tier ────────────────────────────────────────────────────────────────────────

    @Test
    public void aJavaConstructorTypesItsVariableAsThatClass() {
        SymbolInfo symbol = resolve("var list = new java.util.ArrayList();\n", "list");
        assertNotNull(symbol.type());
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
    }

    @Test
    public void javaTypeWithAStringLiteralNamesTheClass() {
        SymbolInfo symbol = resolve("var List = Java.type('java.util.ArrayList');\n", "List");
        assertNotNull(symbol.type());
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
    }

    @Test
    public void packagesSpellingResolvesToTheSameClass() {
        SymbolInfo symbol = resolve("var l = new Packages.java.util.ArrayList();\n", "l");
        assertNotNull("the Packages spelling did not resolve", symbol.type());
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
    }

    /**
     * <b>The milestone's headline claim.</b> The members of a Java receiver reached from JavaScript are
     * the Java engine's own answer — not reflection's approximation of it.
     */
    @Test
    public void aJavaReceiverOffersTheJavaEnginesOwnMembers() {
        Assume.assumeTrue("no Java engine staged; the reflection fallback is tested separately",
                JavaLanguage.isAvailable());
        String source = "var list = new java.util.ArrayList();\nlist.add('one');\n";
        Analysis analysis = analyse(source);
        SymbolInfo list = analysis.resolveAt(source.indexOf("list"));
        List<String> members = namesOf(analysis.membersOf(list.type(), source.indexOf("list.add")));

        assertFalse("no members came back for java.util.ArrayList", members.isEmpty());
        for (String expected : new String[]{"add", "get", "size", "isEmpty", "toString"}) {
            assertTrue(expected + " is missing from " + members.size() + " members",
                    members.contains(expected));
        }
    }

    @Test
    public void aMemberOnAJavaReceiverResolvesToTheJavaMember() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var list = new java.util.ArrayList();\nlist.add('one');\n";
        SymbolInfo add = analyse(source).resolveAt(source.indexOf("add"));
        assertNotNull("the member did not resolve", add);
        assertEquals("add", add.name());
        assertEquals(SymbolKind.METHOD, add.kind());
        // THE GENERIC DECLARATION, which is what the Java engine's own hover reports for the same member
        // (`java.util.List<E>` over `List.add`). The raw name is what `membersOf` carries for a completion
        // row -- there the receiver is the thing the user typed -- and the two differing is the point
        // rather than a slip: only the hover is describing a declaration. Pinned raw until the two
        // editors were put side by side.
        assertEquals("the container is not the declaring Java class", "java.util.ArrayList<E>",
                add.container());
    }

    /**
     * The static side and the instance side are different member sets, and the spelling decides which.
     *
     * <p>{@code Java.type("a.b.C")} evaluates to the class object, whose members are its statics;
     * {@code new a.b.C()} is an instance. Offering the wrong set is worse than offering none — every row
     * in the list would be something the script cannot call there.</p>
     */
    @Test
    public void theClassObjectOffersStaticsAndAnInstanceOffersInstanceMembers() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String statics = "var S = Java.type('java.lang.Integer');\n";
        Analysis staticAnalysis = analyse(statics);
        List<String> staticMembers = namesOf(staticAnalysis.membersOf(
                staticAnalysis.resolveAt(statics.indexOf("S")).type(), 0));
        assertTrue("parseInt is missing from Integer's statics", staticMembers.contains("parseInt"));
        assertFalse("an instance method is offered on the class object",
                staticMembers.contains("intValue"));

        String instance = "var i = new java.lang.Integer(1);\n";
        Analysis instanceAnalysis = analyse(instance);
        List<String> instanceMembers = namesOf(instanceAnalysis.membersOf(
                instanceAnalysis.resolveAt(instance.indexOf("i")).type(), 0));
        assertTrue("intValue is missing from an Integer instance", instanceMembers.contains("intValue"));
        assertFalse("a static is offered on an instance", instanceMembers.contains("parseInt"));
    }

    /**
     * A package prefix is not a type — {@code java.util} on its own has no members.
     *
     * <p>Without the rule, the last segment of any chain would be treated as a class and a namespace
     * would be offered a member list, which reads as the resolver inventing things.</p>
     */
    @Test
    public void aPackagePrefixIsNotAType() {
        assertNull(resolve("var p = java.util;\n", "p").type());
    }

    /**
     * A call's type is its callee's return type — which is what makes a chain resolvable.
     *
     * <p>{@code C.emptyList()} has no name and no declaration; what it <em>is</em> is a value of the return
     * type, and that is the only thing worth saying about it. Asked at the closing bracket, because that is
     * where completion asks: the character before the dot in {@code emptyList().}</p>
     */
    @Test
    public void aCallHasTheTypeItsCalleeReturns() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var C = Java.type('java.util.Collections');\nvar n = C.emptyList();\n";
        SymbolInfo call = analyse(source).resolveAt(source.indexOf("emptyList()") + "emptyList(".length());
        assertNotNull("a call resolved to nothing", call);
        assertNotNull("a call has no type, so nothing can be completed on it", call.type());
        assertEquals("java.util.List", call.type().qualifiedName());
    }

    /**
     * A function declaration's own type is what it RETURNS, never the string {@code function}.
     *
     * <p>The same thing a {@code SymbolInfo} means for a Java method, and the reason a call can be typed at
     * all: a call's type is its callee's. A <em>variable</em> holding a function is the other case and keeps
     * {@code function}, because there the value really is one.</p>
     */
    @Test
    public void aFunctionsTypeIsItsReturnTypeAndAVariablesIsTheFunction() {
        // `plain`, not `f`: `indexOf("f")` finds the 'f' of `function` and resolves inside the keyword.
        assertNull("a function with no documented return type claims one",
                resolve("function plain() { return 1; }\n", "plain").type());
        assertEquals("number",
                resolve("/** @returns {number} */\nfunction sized() { return 1; }\n", "sized")
                        .type().displayName());
        assertEquals("a variable holding a function is a function", "function",
                resolve("var held = function () { return 1; };\n", "held").type().displayName());
    }

    // ── The JSDoc tier ──────────────────────────────────────────────────────────────────────────

    @Test
    public void aJsDocTypeTagTypesAVariable() {
        // The description must not contain the identifier: `resolve` searches the source text and would
        // otherwise land the offset inside the comment, which resolves to nothing.
        String source = "/** What it says. @type {string} */\nvar label = compute();\n";
        SymbolInfo symbol = resolve(source, "label");
        assertNotNull("the JSDoc type was not read", symbol);
        assertNotNull("the JSDoc type was not read", symbol.type());
        assertEquals("string", symbol.type().displayName());
        assertEquals("What it says.", symbol.documentation());
    }

    @Test
    public void aJsDocParamTagTypesAParameter() {
        String source = "/**\n * Adds.\n * @param {string} name\n * @param {number} count\n */\n"
                + "function add(name, count) { return name; }\n";
        SymbolInfo symbol = resolve(source, "add");
        assertEquals(2, symbol.parameters().size());
        assertEquals("string", symbol.parameters().get(0).displayName());
        assertEquals("number", symbol.parameters().get(1).displayName());
    }

    @Test
    public void aJsDocReturnsTagTypesTheFunction() {
        String source = "/** @returns {number} */\nfunction size() { return 1; }\n";
        assertEquals("number", resolve(source, "size").type().displayName());
    }

    @Test
    public void aJsDocDeprecatedTagMarksIt() {
        String source = "/** @deprecated use size */\nfunction count() { return 1; }\n";
        assertTrue(resolve(source, "count").is(SymbolModifier.DEPRECATED));
    }

    @Test
    public void aJsDocJavaTypeGoesThroughTheJavaResolver() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "/** @type {java.util.ArrayList} */\nvar list = make();\n";
        SymbolInfo symbol = resolve(source, "list");
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
    }

    /**
     * A file's header comment is not the first declaration's documentation.
     *
     * <p>The search is "the JSDoc block above, with only whitespace between" — without that test, this
     * fixture's own twenty-line milestone log would be reported as {@code MAX_RETRIES}' description.</p>
     */
    @Test
    public void aHeaderCommentSeparatedByCodeIsNotTheDeclarationsDoc() {
        String source = "/** A file header. */\n'use strict';\n\nvar first = 1;\n";
        assertNull("the header comment was attached across a statement",
                resolve(source, "first").documentation());
    }

    // ── The live tier ───────────────────────────────────────────────────────────────────────────

    private static LiveScopeSnapshot afterRunning(String source) throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            host.run(host.compileScript("Probe.js", source, java.util.Map.of()), java.util.Map.of());
            return JsLanguage.executor().snapshotScope();
        } finally {
            host.close();
        }
    }

    @Test
    public void aRunReportsWhatItLeftInScope() throws Throwable {
        LiveScopeSnapshot live = afterRunning(
                "var greeting = 'hi';\nvar total = 3;\nfunction go(a, b) { return a; }\n"
                        + "var list = new java.util.ArrayList();\nvar settings = { a: 1, b: 2 };\n");

        assertEquals(LiveScopeSnapshot.Kind.STRING, live.get("greeting").kind());
        assertEquals(LiveScopeSnapshot.Kind.NUMBER, live.get("total").kind());
        assertEquals(LiveScopeSnapshot.Kind.FUNCTION, live.get("go").kind());
        assertEquals(2, live.get("go").arity());
        assertEquals(LiveScopeSnapshot.Kind.JAVA_OBJECT, live.get("list").kind());
        assertEquals("java.util.ArrayList", live.get("list").javaClassName());
        assertEquals(LiveScopeSnapshot.Kind.OBJECT, live.get("settings").kind());
        assertEquals(List.of("a", "b"), live.get("settings").ownIds());
    }

    /**
     * The standard library is not something the script defined.
     *
     * <p>The globals live on the same scope object the script's own {@code var}s land on, so "what did
     * this run leave behind" is a difference against a baseline rather than a listing. Without it the
     * live tier would report {@code Math}, {@code parseInt} and {@code console} as the script's.</p>
     */
    @Test
    public void theStandardLibraryIsNotReportedAsTheScriptsOwn() throws Throwable {
        LiveScopeSnapshot live = afterRunning("var mine = 1;\n");
        assertTrue(live.has("mine"));
        for (String standard : new String[]{"Object", "Math", "parseInt", "console", "print", "Java"}) {
            assertFalse(standard + " was reported as something the script defined", live.has(standard));
        }
    }

    @Test
    public void theLiveScopeTypesAGlobalTheSourceCannotType() throws Throwable {
        // The source says nothing -- `make()` is unknowable statically -- and after a run the editor
        // knows exactly what it produced. That is the whole reason the live tier outranks inference.
        LiveScopeSnapshot live = afterRunning("var made = new java.util.ArrayList();\n");
        String source = "var made = make();\nmade;\n";
        SymbolInfo symbol = analyse(source, live).resolveAt(source.lastIndexOf("made"));
        assertNotNull(symbol);
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
    }

    @Test
    public void aNameOnlyAPreviousRunDefinedStillResolves() throws Throwable {
        LiveScopeSnapshot live = afterRunning("var fromRun = 'hello';\n");
        SymbolInfo symbol = analyse("fromRun;\n", live).resolveAt(0);
        assertNotNull("a global from the last run resolved to nothing", symbol);
        assertEquals("string", symbol.type().displayName());
    }

    // ── Provenance ──────────────────────────────────────────────────────────────────────────────

    /**
     * The tier that answered is on the symbol — the milestone's fourth criterion.
     *
     * <p>In the owner band's text, which is where the popup already draws the container: {@code count —
     * from last run}, {@code add — from JSDoc}. A JavaScript answer's provenance is information a Java
     * answer never had to carry, and a type that looks wrong without it reads as a bug rather than as a
     * guess the engine is being open about. Inference carries none, because it is the ordinary answer.</p>
     */
    @Test
    public void theTierThatAnsweredIsVisible() throws Throwable {
        String jsDoc = "/** @type {string} */\nvar labelled = compute();\n";
        assertTrue("JSDoc did not say so: " + resolve(jsDoc, "labelled").container(),
                resolve(jsDoc, "labelled").container().endsWith("from JSDoc"));

        LiveScopeSnapshot live = afterRunning("var ran = 1;\n");
        SymbolInfo fromRun = analyse("var ran = compute();\n", live).resolveAt(4);
        assertTrue("the live tier did not say so: " + fromRun.container(),
                fromRun.container().endsWith("from last run"));

        // AND INFERENCE SAYS NOTHING. It is what the editor does by default, so labelling it would put a
        // provenance note on nearly every symbol in the file and make the two that matter invisible.
        assertEquals("Probe.js", resolve("var plain = 'hi';\n", "plain").container());
    }

    // ── symbolsInScope ──────────────────────────────────────────────────────────────────────────

    @Test
    public void symbolsInScopeAreNearestFirstAndIncludeTheLastRunsGlobals() throws Throwable {
        LiveScopeSnapshot live = afterRunning("var fromRun = 1;\n");
        String source = "var outer = 1;\nfunction f() {\n  var inner = 2;\n  \n}\n";
        List<String> names = namesOf(analyse(source, live).symbolsInScope(source.indexOf("  \n")));

        assertTrue(names.contains("inner"));
        assertTrue(names.contains("outer"));
        assertTrue("the last run's globals are not offered", names.contains("fromRun"));
        assertTrue("a local must come before a file-level name: " + names,
                names.indexOf("inner") < names.indexOf("outer"));
    }

    @Test
    public void aNameThatIsBothDeclaredAndLiveAppearsOnce() throws Throwable {
        LiveScopeSnapshot live = afterRunning("var both = 1;\n");
        List<String> names = namesOf(analyse("var both = 1;\n", live).symbolsInScope(12));
        int seen = 0;
        for (String name : names) {
            if ("both".equals(name)) seen++;
        }
        assertEquals("the same identifier was offered twice", 1, seen);
    }

    // ── expectedTypeAt ──────────────────────────────────────────────────────────────────────────

    @Test
    public void aJsDocParamSaysWhatTypeBelongsInThatArgument() {
        String source = "/** @param {string} name */\nfunction greet(name) { return name; }\n"
                + "greet('x');\n";
        TypeRef expected = analyse(source).expectedTypeAt(source.lastIndexOf("'x'"));
        assertNotNull("nothing was expected where JSDoc plainly said", expected);
        assertEquals("string", expected.displayName());
    }

    @Test
    public void anUndocumentedArgumentExpectsNothing() {
        String source = "function greet(name) { return name; }\ngreet('x');\n";
        assertNull(analyse(source).expectedTypeAt(source.lastIndexOf("'x'")));
    }

    // ── Resilience ──────────────────────────────────────────────────────────────────────────────

    /**
     * A broken file still resolves what it can.
     *
     * <p>The reason the parser runs in IDE mode at all: a file is broken most of the time somebody is
     * typing in it, and an engine that answered only for well-formed input would answer exactly when it
     * is not needed.</p>
     */
    @Test
    public void aFileWithASyntaxErrorStillResolvesWhatParsed() {
        String source = "var good = 'hi';\nfunction ( {\n";
        SymbolInfo symbol = analyse(source).resolveAt(source.indexOf("good"));
        assertNotNull("nothing resolved in a file with a syntax error", symbol);
        assertEquals("string", symbol.type().displayName());
    }

    @Test
    public void resolvingOffTheEndIsHarmless() {
        assertNull(analyse("var a = 1;\n").resolveAt(9999));
        assertNull(analyse("").resolveAt(0));
    }

    // ── The reflection fallback ─────────────────────────────────────────────────────────────────

    /**
     * With no Java engine, the member list comes from reflection — and is still the truth.
     *
     * <p>A build that ships Rhino without ECJ still runs scripts that call Java, so refusing to answer
     * would make the editor useless for exactly the case the stack is designed to degrade through.
     * Reflection is also what Rhino does at call time, so the list is what the script can really call.</p>
     */
    @Test
    public void reflectionAnswersWhenThereIsNoJavaEngine() {
        InteropResolver reflective = new InteropResolver(null, List.of(), 8);
        assertFalse(reflective.hasJavaEngine());
        List<String> members = namesOf(reflective.membersOf("java.util.ArrayList", false));
        assertFalse("reflection returned nothing for a class that plainly exists", members.isEmpty());
        assertTrue(members.contains("add"));
        assertTrue(members.contains("size"));
        assertFalse("a static leaked into the instance list", members.contains("valueOf"));
        assertTrue("a class that does not exist is not an error",
                reflective.membersOf("no.such.Class", false).isEmpty());
    }

    @Test
    public void theInteropCacheIsBoundedAndStaysUsable() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        JavaEngine java = JavaLanguage.engine();
        InteropResolver resolver = new InteropResolver(java.analyzer(), HostClasspath.detect(),
                java.releaseLevel());
        try {
            String[] classes = {"java.util.ArrayList", "java.util.HashMap", "java.util.LinkedList",
                    "java.lang.String", "java.lang.Integer", "java.util.TreeMap", "java.util.HashSet",
                    "java.lang.StringBuilder", "java.util.Random", "java.io.File", "java.net.URI",
                    "java.util.Optional", "java.time.Duration", "java.util.BitSet"};
            for (String name : classes) {
                assertFalse(name + " answered nothing", resolver.membersOf(name, false).isEmpty());
            }
            // AND THE FIRST ONE STILL WORKS after being evicted -- an eviction that closed an analysis
            // somebody still held would answer empty here rather than re-analysing.
            assertFalse("an evicted class did not come back",
                    resolver.membersOf(classes[0], false).isEmpty());
        } finally {
            resolver.close();
        }
    }
}
