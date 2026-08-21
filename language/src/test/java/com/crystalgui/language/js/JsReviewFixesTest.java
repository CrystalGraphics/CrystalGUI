package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.language.engine.bridge.CodeActionContext;

/**
 * <b>The nets the M10 review found missing</b> — one per finding, named for the finding.
 *
 * <p>Every test here failed against the code as it stood at {@code bbe3559}, which is the only property
 * that makes a regression test worth writing: each was reachable by ordinary use, none was caught by the
 * 178 tests that were already green, and most were invisible because the feature <em>appeared</em> to
 * work. They live together rather than being scattered through the six existing classes because what
 * they have in common is the review, and a reader following {@code plan_m10.md} §12a should find them in
 * one place.</p>
 */
public class JsReviewFixesTest {

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @After
    public void restoreThePostures() {
        JsLanguage.resetPosturesForTesting();
        JsLanguage.analyzer().useHostBindings(Map.of());
    }

    private static Analysis analyse(String source) {
        return analyse(source, LiveScopeSnapshot.EMPTY);
    }

    private static Analysis analyse(String source, LiveScopeSnapshot live) {
        return JsLanguage.analyzer().analyze("Probe.js", source, 1L, live);
    }

    private static SymbolInfo resolveAt(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue("the fixture does not contain " + needle, offset >= 0);
        return analyse(source).resolveAt(offset);
    }

    private static List<String> namesOf(List<SymbolInfo> symbols) {
        List<String> names = new ArrayList<>();
        for (SymbolInfo symbol : symbols) names.add(symbol.name());
        return names;
    }

    // ── R-01 · the live tier contributes a type; it does not replace the declaration ─────────────

    /**
     * A run must not cost a documented function its documentation.
     *
     * <p>The live tier rebuilt the whole symbol from the live entry, so after any run this function
     * hovered with no description, no parameter types, and type {@code function} — which for anything
     * invocable means its RETURN type, so it was not merely less information but wrong information.</p>
     */
    @Test
    public void aRunDoesNotStripAFunctionsJsDoc() {
        String source = "/**\n * Joins them.\n * @param {string} name\n * @returns {string}\n */\n"
                + "function join(name) { return name; }\n";
        LiveScopeSnapshot afterRun = LiveScopeSnapshot.of(List.of(
                new LiveScopeSnapshot.Entry("join", LiveScopeSnapshot.Kind.FUNCTION, null, "join", 1,
                        List.of())));
        SymbolInfo symbol = analyse(source, afterRun).resolveAt(source.indexOf("join(name) {"));

        assertNotNull(symbol);
        assertEquals(SymbolKind.FUNCTION, symbol.kind());
        // THE DESCRIPTION IS MARKUP NOW, not the source it was written in -- so this asks whether the
        // words survived the run rather than comparing against the raw comment. @see JsDocs
        assertNotNull("the JSDoc description was lost to the run", symbol.documentation());
        assertTrue("the JSDoc description was lost to the run: " + symbol.documentation(),
                symbol.documentation().contains("Joins them."));
        assertNotNull("the JSDoc return type was replaced by the live `function`", symbol.type());
        assertEquals("string", symbol.type().displayName());
        assertEquals("the parameter list came from the live entry rather than the declaration",
                1, symbol.parameters().size());
        assertEquals("string", symbol.parameters().get(0).displayName());
    }

    /** ...while a VARIABLE that became a Java object still types from the run, which is the tier's point. */
    @Test
    public void aVariableStillTypesFromTheRun() {
        String source = "var made = makeIt();\n";
        LiveScopeSnapshot afterRun = LiveScopeSnapshot.of(List.of(
                new LiveScopeSnapshot.Entry("made", LiveScopeSnapshot.Kind.JAVA_OBJECT,
                        "java.util.ArrayList", null, -1, List.of())));
        SymbolInfo symbol = analyse(source, afterRun).resolveAt(source.indexOf("made"));

        assertNotNull(symbol);
        assertNotNull("the live tier stopped typing a variable it alone can type", symbol.type());
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());
        assertTrue("the provenance is not stated: " + symbol.container(),
                String.valueOf(symbol.container()).contains("from last run"));
    }

    // ── R-05 · a catch parameter is declared ─────────────────────────────────────────────────────

    @Test
    public void aCatchParameterIsNotAFreeName() {
        String source = "function f() {\n  try { g(); } catch (e) { print(e); }\n}\n";
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            if (token.start() == source.lastIndexOf("e)") ) continue;
            assertFalse("the catch parameter is drawn as a mistake: " + token,
                    "variable.unresolved".equals(token.name())
                            && "e".equals(source.substring(token.start(), token.end())));
        }
        SymbolInfo symbol = analyse(source).resolveAt(source.indexOf("print(e)") + 6);
        assertNotNull("a catch parameter resolves to nothing", symbol);
        assertEquals(SymbolKind.PARAMETER, symbol.kind());
    }

    // ── R-06 · block scope ───────────────────────────────────────────────────────────────────────

    /**
     * Two sibling blocks each declaring {@code let x} are two names, and never both in scope.
     *
     * <p>{@code Declaration} knew only the enclosing function, so both were visible everywhere in it: the
     * completion list showed {@code x} twice and a hover in the second block described the first one.</p>
     */
    @Test
    public void aLetInASiblingBlockIsNotInScope() {
        String source = "function f() {\n"
                + "  { let x = 'first'; print(x); }\n"
                + "  { let x = 2; print(x); }\n"
                + "}\n";
        int inSecondBlock = source.lastIndexOf("print(x)") + 6;
        List<String> visible = namesOf(analyse(source).symbolsInScope(inSecondBlock));
        assertEquals("the same name is in scope twice: " + visible,
                1, visible.stream().filter("x"::equals).count());

        SymbolInfo second = analyse(source).resolveAt(inSecondBlock);
        assertNotNull(second);
        assertNotNull("the second block's `x` resolved to nothing", second.type());
        assertEquals("the hover describes the OTHER block's declaration",
                "number", second.type().displayName());
    }

    /** A `var` is hoisted to its function, so it is still in scope after the block it was written in. */
    @Test
    public void aVarInABlockIsStillVisibleAfterIt() {
        String source = "function f() {\n  { var total = 1; }\n  return total;\n}\n";
        List<String> visible = namesOf(analyse(source).symbolsInScope(source.indexOf("return total")));
        assertTrue("a hoisted var went out of scope with its block: " + visible, visible.contains("total"));
    }

    // ── R-21 / R-22 · the standard prototypes, and what the fallback is for ──────────────────────

    @Test
    public void aStringOffersItsPrototypesMembers() {
        String source = "var s = 'text';\n";
        SymbolInfo receiver = resolveAt(source, "s = ");
        assertNotNull(receiver);
        List<String> members = namesOf(analyse(source).membersOf(receiver.type(), source.length()));
        assertTrue("a string offered nothing at all", members.contains("charAt"));
        assertTrue(members.contains("indexOf") && members.contains("toUpperCase"));
    }

    @Test
    public void anArrayOffersItsPrototypesMembers() {
        String source = "var xs = [1, 2, 3];\n";
        SymbolInfo receiver = resolveAt(source, "xs = ");
        assertNotNull(receiver);
        List<String> members = namesOf(analyse(source).membersOf(receiver.type(), source.length()));
        assertTrue("an array offered nothing at all: " + members, members.contains("push"));
        assertTrue(members.contains("slice") && members.contains("length"));
    }

    /** An object literal's own properties come first, and what every object inherits comes with them. */
    @Test
    public void anObjectLiteralOffersItsOwnPropertiesAndWhatItInherits() {
        String source = "var o = { alpha: 1, beta: 2 };\n";
        SymbolInfo receiver = resolveAt(source, "o = ");
        assertNotNull(receiver);
        List<String> members = namesOf(analyse(source).membersOf(receiver.type(), source.length()));
        assertTrue("the literal's own keys are missing: " + members,
                members.contains("alpha") && members.contains("beta"));
        assertTrue("the inherited half is missing: " + members, members.contains("hasOwnProperty"));
        assertEquals("the object's own property is listed after what it inherits",
                0, members.indexOf("alpha"));
    }

    // ── R-02 · host bindings ─────────────────────────────────────────────────────────────────────

    /**
     * A binding the host puts in scope is not a typo, and it has a type before anything has run.
     *
     * <p>Every one of them was drawn {@code variable.unresolved}, offered a rename and an offer to declare
     * it as a local, and hovered as nothing — in the one editor whose own runtime provides it.</p>
     */
    @Test
    public void aHostBindingIsAGlobalWithAJavaType() {
        JsLanguage.analyzer().useHostBindings(Map.of("world", "java.util.ArrayList"));
        String source = "world.size();\n";

        SymbolInfo symbol = resolveAt(source, "world");
        assertNotNull("a host binding resolved to nothing", symbol);
        assertNotNull(symbol.type());
        assertEquals("java.util.ArrayList", symbol.type().qualifiedName());

        boolean drawnAsGlobal = false;
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            if (token.start() != 0) continue;
            assertFalse("a host binding is drawn as a mistake", "variable.unresolved".equals(token.name()));
            drawnAsGlobal |= "variable.global".equals(token.name());
        }
        assertTrue("the `variable.global` capture is still unused", drawnAsGlobal);

        assertTrue("a host binding is not offered in open code",
                namesOf(analyse(source).symbolsInScope(source.length())).contains("world"));
    }

    /** And the Java engine answers for it — a member list from the first keystroke, with no run. */
    @Test
    public void aHostBindingsMembersComeFromTheJavaEngine() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        JsLanguage.analyzer().useHostBindings(Map.of("world", "java.util.ArrayList"));
        String source = "world.size();\n";
        SymbolInfo symbol = resolveAt(source, "world");
        assertNotNull(symbol);
        List<String> members = namesOf(analyse(source).membersOf(symbol.type(), source.length()));
        assertTrue("the Java engine was not asked about a host binding: " + members.size() + " rows",
                members.contains("add") && members.contains("size"));
    }

    // ── R-12 · one token per range ───────────────────────────────────────────────────────────────

    @Test
    public void anUnresolvedCallHasOneTokenNotTwo() {
        String source = "notDefinedAnywhere();\n";
        int marks = 0;
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            if (token.start() == 0) marks++;
        }
        assertEquals("two semantic tokens on one range, under different names", 1, marks);
    }

    // ── R-16 · any expression is a receiver ──────────────────────────────────────────────────────

    @Test
    public void aStringLiteralIsAReceiver() {
        String source = "var n = 'text'.length;\n";
        SymbolInfo symbol = analyse(source).resolveAt(source.indexOf("'text'") + 5);
        assertNotNull("a literal receiver resolved to nothing", symbol);
        assertNotNull(symbol.type());
        assertEquals("string", symbol.type().displayName());
    }

    // ── R-30 · the builtins are candidates for "did you mean" ───────────────────────────────────

    @Test
    public void aMisspeltBuiltinIsOfferedTheRealOne() {
        String source = "consle.log('x');\n";
        List<String> titles = new ArrayList<>();
        analyse(source).codeActionsIn(0, 6, NO_HOST_CONTEXT)
                .forEach(action -> titles.add(action.title()));
        assertTrue("the commonest typo in the language is offered nothing: " + titles,
                titles.contains("Change to 'console'"));
    }

    private static final com.crystalgui.language.engine.bridge.CodeActionContext NO_HOST_CONTEXT =
            new com.crystalgui.language.engine.bridge.CodeActionContext() {
                @Override
                public List<String> importCandidates(String simpleName) {
                    return List.of();
                }

                @Override
                public List<String> similarTypeNames(String simpleName) {
                    return List.of();
                }
            };

    // ── R-13 / R-32 · the three lists that had drifted ──────────────────────────────────────────

    @Test
    public void everyGlobalTheExecutorInstallsIsKnownToTheAnalyser() {
        List<String> known = JsLanguage.analyzer().globals();
        for (String installed : List.of("console", "print", "readLine", "prompt", "Java")) {
            assertTrue("the executor installs `" + installed + "` and the analyser has never heard of it",
                    known.contains(installed));
        }
    }

    @Test
    public void everyPackageRootTheInferenceTierReadsIsAGlobal() {
        List<String> known = JsLanguage.analyzer().globals();
        for (String root : List.of("java", "javax", "org", "com", "edu", "net", "Packages")) {
            assertTrue("`" + root + "` is read as a package root and marked unresolved: " + root,
                    known.contains(root));
        }
    }

    /** `net.minecraft.…` is the first line a mod author writes, and it was drawn as a mistake. */
    @Test
    public void aPackageRootIsNotDrawnAsAMistake() {
        String source = "var w = net.minecraft.world.World;\n";
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            assertFalse("a package root is drawn as unresolved",
                    "variable.unresolved".equals(token.name())
                            && "net".equals(source.substring(token.start(), token.end())));
        }
    }

    // ── R-17 · a type is a value ─────────────────────────────────────────────────────────────────

    @Test
    public void twoObjectLiteralsAreNotOneType() {
        String source = "var a = { alpha: 1 };\nvar b = { beta: 2 };\n";
        Analysis analysis = analyse(source);
        SymbolInfo first = analysis.resolveAt(source.indexOf("a = "));
        SymbolInfo second = analysis.resolveAt(source.indexOf("b = "));
        assertNotNull(first);
        assertNotNull(second);

        List<String> a = namesOf(analysis.membersOf(first.type(), source.length()));
        List<String> b = namesOf(analysis.membersOf(second.type(), source.length()));
        assertTrue("the first literal's own key is missing", a.contains("alpha"));
        assertFalse("one literal's members leaked into the other's", a.contains("beta"));
        assertTrue(b.contains("beta"));
        assertFalse(b.contains("alpha"));
    }

    // ── R-31 · `let` is not `var` ────────────────────────────────────────────────────────────────

    @Test
    public void aLetSaysLetAndAVarSaysVar() {
        Assume.assumeTrue("this band refuses `let`", JsLanguage.analyzer().keywords().contains("let"));
        SymbolInfo declared = resolveAt("let total = 1;\n", "total");
        assertNotNull(declared);
        assertNotNull(declared.signature());
        assertTrue("a `let` renders as `var`: " + declared.signature().text(),
                declared.signature().text().startsWith("let "));
    }

    // ── R-20 · the matrix rows that claimed more than shipped ──────────────────────

    /** A branch whose condition cannot vary is a warning. */
    @Test
    public void aConstantConditionIsAWarning() {
        String source = "function f() {\n  if (false) { print('never'); }\n}\n";
        boolean warned = false;
        for (Diagnostic problem : analyse(source).diagnostics()) {
            warned |= problem.message().contains("constant");
        }
        assertTrue("a dead branch is not reported at all", warned);
    }

    /** ...and `while (true)` deliberately is not, because that is how a forever loop is spelled. */
    @Test
    public void aDeliberateForeverLoopIsNotAWarning() {
        String source = "function f() {\n  while (true) { break; }\n}\n";
        for (Diagnostic problem : analyse(source).diagnostics()) {
            assertFalse("the idiomatic forever loop is marked: " + problem.message(),
                    problem.message().contains("constant"));
        }
    }

    /** A package chain is drawn as packages and a type — a distinction no grammar can make. */
    @Test
    public void aPackageChainIsColouredAsPackagesAndAType() {
        String source = "var list = new java.util.ArrayList();\n";
        boolean module = false;
        boolean type = false;
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            String text = source.substring(token.start(), token.end());
            module |= "module".equals(token.name()) && "util".equals(text);
            type |= "type".equals(token.name()) && "ArrayList".equals(text);
        }
        assertTrue("a package segment is not drawn as one", module);
        assertTrue("the type at the end of the chain is not drawn as one", type);
    }

    /** `list.add(|)` knows what belongs there, which is what an expected type is actually for. */
    @Test
    public void aJavaCalleeSaysWhatTypeBelongsInItsArgument() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var list = new java.util.ArrayList();\nlist.add(x);\n";
        TypeRef expected = analyse(source).expectedTypeAt(source.indexOf("(x)") + 1);
        assertNotNull("a Java callee's parameter type is still unanswered", expected);
        assertEquals("java.lang.Object", expected.qualifiedName());
    }

    // ── R-11 · a nested class, in the spelling a script writes ───────────────────────────────────

    @Test
    public void aNestedClassIsFoundInTheSourceSpelling() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        // The editor resolves and offers `java.util.Map.Entry`; the JVM knows only `Map$Entry`, so the
        // name the popup suggested threw "no such class" the moment it ran.
        SymbolInfo symbol = resolveAt("var e = java.util.Map.Entry;\n", "e = ");
        assertNotNull("the source spelling of a nested class does not resolve", symbol);
        assertNotNull(symbol.type());
    }
}
