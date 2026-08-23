package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.syntax.SyntaxToken;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>M10.3 and M10.4: what Rhino's parse is turned into.</b>
 *
 * <p>Two halves of one walk. The <b>diagnostics</b> half is mostly about what is <em>not</em> shown —
 * Rhino's raw output for a single unsupported keyword is five errors, four of them artefacts of its own
 * recovery — and about naming this engine's refusals so an author reads a sentence rather than a lexer's
 * complaint. The <b>tokens</b> half is the set of distinctions that need resolved scopes and that no
 * grammar can reach: parameter against local against const, reassigned, captured, unresolved.</p>
 *
 * <h3>Asserted through the bridge, not against Rhino</h3>
 *
 * <p>Every assertion here goes through {@link Analysis}, which is the language-neutral answer every
 * consumer above reads. That is deliberate: a test that reached for the AST would be testing the parse,
 * which is Mozilla's to get right, rather than the conversion, which is ours.</p>
 */
public class JsAnalysisTest {

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    private static Analysis analyse(String source) {
        return JsLanguage.analyzer().analyze("Probe.js", source, 1L);
    }

    private static List<String> capturesOf(String source, String text) {
        List<String> found = new ArrayList<>();
        for (SyntaxToken token : analyse(source).semanticTokens()) {
            if (source.substring(token.start(), token.end()).equals(text)) found.add(token.name());
        }
        return found;
    }

    private static List<String> messagesOf(String source) {
        List<String> messages = new ArrayList<>();
        for (Diagnostic problem : analyse(source).diagnostics()) messages.add(problem.message());
        return messages;
    }

    // ── M10.3: what the parser says, and what is worth showing ──────────────────────────────────

    /**
     * The measurement this whole policy exists for.
     *
     * <p>{@code class A { m() { return 1; } }} produces <b>five</b> errors from Rhino — one real and
     * four from its recovery re-synchronising. Shown raw that is five squiggles across a correct line
     * and four Problems rows that navigate to arbitrary punctuation.</p>
     */
    @Test
    public void anUnsupportedKeywordIsOneErrorRatherThanFive() {
        List<Diagnostic> problems = analyse("class A { m() { return 1; } }").diagnostics();
        assertEquals("the recovery cascade was not suppressed: " + messages(problems),
                1, problems.size());
        assertEquals(DiagnosticSeverity.ERROR, problems.get(0).severity());
        assertEquals(0, problems.get(0).start().row());
        assertEquals("the error is not on the keyword", 0, problems.get(0).start().column());
    }

    /**
     * The four constructs no shipped band accepts, each named as itself.
     *
     * <p>Rhino says "identifier is a reserved word: class", which is accurate about its lexer and
     * useless to an author who did not think they were declaring an identifier.</p>
     */
    @Test
    public void theEnginesRefusalsAreNamedRatherThanLexed() {
        assertTrue(messagesOf("class A {}").toString(),
                messagesOf("class A {}").get(0).startsWith("'class': classes are not supported"));
        assertTrue(messagesOf("import x from 'y';").get(0)
                .startsWith("'import': ES modules are not supported"));
        assertTrue(messagesOf("export const a = 1;").get(0)
                .startsWith("'export': ES modules are not supported"));
        // AND THE ENGINE IS NAMED, because "not supported" without a subject reads as a bug in the
        // editor rather than a limit of what is running the script.
        assertTrue(messagesOf("class A {}").get(0).contains("Rhino"));
    }

    /**
     * {@code async} is the one refusal that is not in the message at all.
     *
     * <p>It lexes as an ordinary identifier, so the parser only complains at the {@code function} after
     * it — and the author reads "missing ; before statement" pointing at a keyword they wrote correctly.
     * Recognising it needs the source rather than the message, which is why it is worth its own test.</p>
     */
    @Test
    public void asyncIsRecognisedFromTheSourceBecauseItIsNotInTheMessage() {
        List<String> messages = messagesOf("async function f() { return 1; }");
        assertEquals(1, messages.size());
        assertTrue(messages.toString(),
                messages.get(0).startsWith("'async': async functions are not supported"));
    }

    /** An ordinary syntax error keeps Rhino's own wording — the policy rewrites limits, not messages. */
    @Test
    public void anOrdinaryParseErrorIsLeftAlone() {
        List<String> messages = messagesOf("var a = .;");
        assertEquals(1, messages.size());
        assertEquals("syntax error", messages.get(0));
    }

    /**
     * A warning is not suppressed by an error on its line.
     *
     * <p>The cascade rule is about <em>recovery</em>, and a warning comes from an independent check —
     * so a duplicate parameter name is still true however broken the line around it is.</p>
     */
    @Test
    public void aWarningSurvivesAnErrorOnTheSameLine() {
        List<Diagnostic> problems = analyse("function f(a, a) { return a; }").diagnostics();
        boolean anyWarning = false;
        for (Diagnostic problem : problems) {
            if (problem.severity() == DiagnosticSeverity.WARNING) anyWarning = true;
        }
        assertTrue("Rhino's duplicate-parameter warning was lost: " + messages(problems), anyWarning);
    }

    // ── M10.3: unused names, which are the analyser's rather than the parser's ───────────────────

    @Test
    public void aLocalDeclaredAndNeverUsedIsAWarning() {
        List<String> messages = messagesOf("function f() { var unusedOne = 1; return 2; }");
        assertEquals(messages.toString(), 1, messages.size());
        assertEquals("'unusedOne' is declared but never used", messages.get(0));
    }

    /**
     * A script's top level is its surface, and a parameter's name is its caller's.
     *
     * <p>Both exclusions were learned the same way: without the first, the entry point of the fixture
     * this milestone is traced in was reported unused; without the second, every {@code (err, data)}
     * callback that ignores its error would be.</p>
     */
    @Test
    public void topLevelDeclarationsAndParametersAreNotReportedUnused() {
        assertEquals(List.of(), messagesOf("var top = 1;"));
        assertEquals(List.of(), messagesOf("function main() { return 1; }"));
        assertEquals(List.of(), messagesOf("function f(ignored) { return 1; }"));
    }

    /**
     * No unused warnings while the file does not parse.
     *
     * <p>A broken file has half a tree, so a name whose only use is inside the part that failed looks
     * unused — and a warning that appears mid-edit and vanishes when you finish is worse than none.
     * {@code optionalProblemsAnalysed} is the engine reporting which of the two happened, rather than
     * a consumer inferring it from "errors and no warnings", which is equally the shape of a file that
     * parses fine and genuinely has none.</p>
     */
    @Test
    public void unusedWarningsAreSuppressedWhileTheFileIsBroken() {
        Analysis broken = analyse("function f() { var unusedOne = 1; return 2; }\nfunction (\n");
        assertFalse(broken.optionalProblemsAnalysed());
        for (Diagnostic problem : broken.diagnostics()) {
            assertEquals("a warning was reported for a file that did not parse: " + problem.message(),
                    DiagnosticSeverity.ERROR, problem.severity());
        }
        assertTrue(analyse("var a = 1;").optionalProblemsAnalysed());
    }

    // ── M10.4: the colours that need resolved scopes ────────────────────────────────────────────

    @Test
    public void aParameterIsNotALocalAndAConstIsNotEither() {
        assertEquals(List.of("variable.parameter", "variable.parameter"),
                capturesOf("function f(p) { return p; }", "p"));
        assertEquals(List.of("variable"),
                capturesOf("function f() { var a = 1; return 2; }", "a"));
        // `const` IS THE ONE DISTINCTION WITH NO SHAPE TO GIVE IT AWAY, which is why it is worth an
        // engine: `const K = 2` and `var K = 2` are the same characters bar the keyword, and only one
        // of them can change.
        assertEquals(List.of("constant", "constant"),
                capturesOf("function f() { const K = 2; return K; }", "K"));
        // A DECLARATION, AND SAID SO — `function.method` rather than bare `function`, which is what the
        // Java engine emits for a method declaration and what every reference scheme colours differently
        // from a call (Islands gives the declaration a blue and leaves the call at the default
        // foreground). Bare `function` is what a producer says when it cannot tell the two apart; this
        // one can. Pinned at `function` until the JavaScript and Java output were dumped side by side.
        assertEquals(List.of("function.method"),
                capturesOf("function named() { return 1; }", "named"));
    }

    /**
     * <b>A call is the grammar's answer, and the engine stands aside.</b>
     *
     * <p>Every top-level function used inside another function was marked {@code variable.captured} —
     * {@code owner} is null at script scope and {@code isInside(x, null)} answers "is there any enclosing
     * function at all", so a global read from anywhere counted. Semantic tokens <em>replace</em> grammar
     * tokens, so `summarise(…)`, `describe(…)` and every other call in a typical file drew as a captured
     * variable instead of a call: the mark was on nearly every name, which is the same as being on none.</p>
     *
     * <p>It also retyped what it described — the capture was a literal {@code "variable.captured"}, so a
     * function came out a variable. Both halves had to go for a call to read as a call.</p>
     */
    @Test
    public void aFunctionCalledFromAnotherFunctionIsNotACapturedVariable() {
        String source = "function helper() { return 1; }\nfunction main() { return helper(); }";
        for (String capture : capturesOf(source, "helper")) {
            assertNotEquals("a top-level function is not a closure capture",
                    "variable.captured", capture);
            assertFalse("nor is it a variable of any kind: " + capture,
                    capture.startsWith("variable"));
        }
    }

    /** A genuine capture — declared in a function, read from one nested inside it — still says so. */
    @Test
    public void aRealClosureCaptureIsStillMarked() {
        List<String> captures = capturesOf(
                "function outer() { var held = 1; return function () { return held; }; }", "held");
        assertTrue("the inner read must be marked captured, got " + captures,
                captures.contains("variable.captured"));
    }

    /** A binding that moves, in a language with no {@code final} to say so. */
    @Test
    public void aReassignedNameSaysSoAtEveryOccurrence() {
        List<String> captures = capturesOf("function f() { var a = 1; a = 2; return a; }", "a");
        assertEquals(3, captures.size());
        for (String capture : captures) {
            assertEquals("variable.reassigned", capture);
        }
    }

    /**
     * And {@code ++}/{@code --} are assignments too — the case that was written and never tested.
     *
     * <p>It was detected with {@code getOperator() == Token.INC}, and a {@code Token} constant is a
     * {@code static final int} that <b>javac inlines</b>: this module compiles against the band-8 Rhino
     * and the later bands renumbered the set, so the comparison quietly stopped matching on every band a
     * user is actually likely to be on. Nothing threw — the colour was simply wrong. The test above
     * passed throughout, because it only ever used {@code a = 2}. @see RhinoTokens</p>
     */
    @Test
    public void anIncrementIsAReassignmentToo() {
        for (String mutation : new String[]{"a++", "++a", "a--", "--a"}) {
            List<String> captures = capturesOf("function f() { var a = 1; " + mutation + "; return a; }",
                    "a");
            assertEquals(mutation + " did not mark every occurrence: " + captures, 3, captures.size());
            for (String capture : captures) {
                assertEquals(mutation + " is an assignment", "variable.reassigned", capture);
            }
        }
        // AND AN ORDINARY UNARY IS NOT ONE. Without this the fix could be "call every unary a write",
        // which would mark `-a` and `typeof a` as mutations and make the colour meaningless.
        for (String read : new String[]{"-a", "!a", "typeof a", "void a"}) {
            List<String> captures = capturesOf("function f() { var a = 1; return " + read + "; }", "a");
            assertEquals(read + " was treated as an assignment", List.of("variable", "variable"),
                    captures);
        }
    }

    /**
     * The single most important thing to be able to see in JavaScript, and the least visible.
     *
     * <p>A closure is a use from inside a function other than the one that declared the name. Nothing
     * in the text says so; only the scopes do.</p>
     */
    @Test
    public void aCaptureIsMarkedAtTheUseAndNotAtTheDeclaration() {
        List<String> captures =
                capturesOf("function f() { var a = 1; return function () { return a; }; }", "a");
        assertEquals(List.of("variable", "variable.captured"), captures);
    }

    /**
     * A property is not a variable, and getting this wrong is not subtle.
     *
     * <p>Rhino models {@code o.k} as two {@link com.crystalgui.text.syntax.SyntaxToken}-worthy names,
     * of which only the receiver refers to anything. Treating the other as free would report every
     * {@code .length} in the file as an unresolved global.</p>
     */
    @Test
    public void aPropertyNameIsNeitherAReferenceNorUnresolved() {
        assertEquals(List.of(), capturesOf("var o = {k: 1}; var v = o.k;", "k"));
        // `variable.member` because `o` is declared at the TOP of the file. What this test is about is
        // that `k` gets nothing and `o` gets something — see the test below for which colour.
        assertEquals(List.of("variable.member", "variable.member"),
                capturesOf("var o = {k: 1}; var v = o.k;", "o"));
    }

    /**
     * <b>A name at the top of a file is a FIELD; one inside a function is a local.</b>
     *
     * <p>Not a nicety. Since M15 S6 a module's top-level declarations are what it <em>exports</em>, so
     * this is the difference between "a scratch value in this function" and "part of this file's
     * surface", which is what a field IS. It reuses {@code variable.member} rather than naming something
     * new, because every scheme has already decided how to draw a field — Islands purple against a grey
     * local, Eclipse Dark cyan against yellow, Dark+ deliberately alike. Both halves are asserted
     * together: a rule that promoted everything would be no rule at all.</p>
     */
    @Test
    public void aTopLevelNameIsAFieldAndOneInsideAFunctionIsNot() {
        assertEquals(List.of("variable.member"), capturesOf("var atTheTop = 1;", "atTheTop"));
        assertEquals(List.of("variable"),
                capturesOf("function f() { var inside = 1; return inside; }", "inside").subList(0, 1));
    }

    /**
     * A free name that nothing knows — the nearest thing to a typo check a language with no compiler
     * has before the script runs.
     */
    @Test
    public void anUnknownNameIsMarkedAndAKnownGlobalIsNot() {
        assertEquals(List.of("variable.unresolved"),
                capturesOf("var a = totallyNotDefined + 1;", "totallyNotDefined"));
        // AND THE GLOBALS COME FROM THE ENGINE. `Math` exists on every band; `Proxy` exists on 1.9.1
        // and not on 1.7.15.1 — so a hand-written list would mark working code broken on one of them,
        // which is why RhinoGlobals asks rather than declares.
        assertEquals(List.of("variable.builtin"), capturesOf("var a = Math.max(1, 2);", "Math"));
        // Rhino's own Java bridge, which `initStandardObjects` does not define and a script uses
        // constantly. Marking `java.util.List` unresolved would be the most visible possible error.
        //
        // `module` RATHER THAN `variable.builtin`, since 10.12: a package root inside a chain is drawn as
        // the package it is, which is strictly more than "the engine has this name". ONE token either way
        // -- the free-name pass stands aside for whichever pass below it is more specific, because two
        // tokens on one range are resolved by paint order rather than by intent.
        assertEquals(List.of("module"), capturesOf("var l = new java.util.ArrayList();", "java"));
    }

    /** Two functions' {@code i} are two declarations, not one — scope identity, not name identity. */
    @Test
    public void twoScopesWithTheSameNameAreTwoDeclarations() {
        String source = "function a() { var i = 1; return i; }\nfunction b() { var i = 2; return 3; }\n";
        // The second `i` is unused and the first is not, which can only be true if they are distinct.
        List<String> messages = messagesOf(source);
        assertEquals(messages.toString(), 1, messages.size());
        assertEquals("'i' is declared but never used", messages.get(0));
        assertEquals("the warning is on the wrong function", 1,
                analyse(source).diagnostics().get(0).start().row());
    }

    /** Tokens arrive in document order, which the editor's per-row bucketing requires. */
    @Test
    public void tokensAreSortedByPosition() {
        List<SyntaxToken> tokens = analyse(
                "function f(p) { var a = p; return function () { return a; }; }").semanticTokens();
        assertFalse(tokens.isEmpty());
        for (int i = 1; i < tokens.size(); i++) {
            assertTrue("tokens are out of order at " + i,
                    tokens.get(i).start() >= tokens.get(i - 1).start());
        }
    }

    private static String messages(List<Diagnostic> problems) {
        List<String> out = new ArrayList<>();
        for (Diagnostic problem : problems) out.add(problem.message());
        return out.toString();
    }
}
