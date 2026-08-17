package com.crystalgui.language.engine;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>What each band's Rhino actually accepts — measured, and written down for the engine to read.</b>
 *
 * <p>M10 §1 (`plan_m10.md`). Two Rhinos ship across three bands — {@code 1.7.15.1} on band 8, because
 * the 1.7.x line is the last that runs on Java 8, and {@code 1.9.1} on 11 and 17 — and they do not
 * accept the same JavaScript. Which constructs each refuses decides four things downstream: which
 * diagnostics are re-titled as "not supported by this engine", which keywords completion may offer,
 * what the compatibility-band warning says to an author on a newer host, and which intentions
 * ({@code var}→{@code let}, arrow functions, template literals) may be offered at all.</p>
 *
 * <h3>Why this is a probe that writes a file, not a table in a class</h3>
 *
 * <p>A constant would be a claim about a jar, made in a different file from the jar, checked by nobody.
 * It also could not be <em>per band</em> without becoming three constants that drift. So the answer is
 * measured against the real jars through the real {@link EngineClassLoader} and written to
 * {@code build/probe/rhino-<band>.properties}; the engine reads that rather than a literal, and a pin
 * bumped to a Rhino with different behaviour changes the file rather than silently disagreeing with a
 * comment. `plan_m10.md` §13a's table is the <em>expectation</em>; this is the truth, and where they
 * disagree the table is what gets edited.</p>
 *
 * <h3>What it measures</h3>
 *
 * <ul>
 *   <li><b>Syntax</b> — one snippet per construct through {@code Context.compileString}, which parses
 *       and compiles, so a refusal is the engine's own {@code EvaluatorException} with its own message.
 *       That message is what the diagnostic will quote.</li>
 *   <li><b>Globals</b> — {@code typeof X} in a standard scope, because "does this band have
 *       {@code Symbol}" is a different question from "does it parse {@code class}" and both are in
 *       §13a's table.</li>
 *   <li><b>Behaviour</b> — the few of `plan_m10.md` §15's questions that need only a context: whether a
 *       sealed scope can still be extended, whether promises drain at the end of an evaluation, and
 *       whether the frame accessor the console's line attribution wants is reachable.</li>
 *   <li><b>API surface</b> — the parser, AST, scope and interop types M10 is written against, pinned the
 *       way {@link EngineApiSurfaceTest} pins the ones M5 needed, so a band bump fails the build rather
 *       than the popup.</li>
 * </ul>
 *
 * <p>Skips cleanly when the band jars were not handed over, like every other test in this package.</p>
 */
public class RhinoCapabilityProbeTest {

    /** Snippets whose <b>parse</b> is the question. Ordered as `plan_m10.md` §13a's table is. */
    private static final Map<String, String> SYNTAX = new LinkedHashMap<>();

    static {
        SYNTAX.put("let", "let a = 1;");
        SYNTAX.put("const", "const a = 1;");
        SYNTAX.put("arrowFunction", "var f = function (a) { return a; }; var g = a => a + 1;");
        SYNTAX.put("templateLiteral", "var s = `a${1}b`;");
        SYNTAX.put("taggedTemplate", "function t(x) { return x; } var s = t`a`;");
        SYNTAX.put("defaultParams", "function f(a = 1) { return a; }");
        SYNTAX.put("restParams", "function f(...a) { return a; }");
        SYNTAX.put("spreadCall", "var m = Math.max(...[1, 2]);");
        SYNTAX.put("spreadArray", "var a = [...[1, 2]];");
        SYNTAX.put("destructuringArray", "var [a, b] = [1, 2];");
        SYNTAX.put("destructuringObject", "var {a, b} = {a: 1, b: 2};");
        SYNTAX.put("destructuringDefaults", "var {a = 1} = {};");
        SYNTAX.put("forOf", "for (var x of [1, 2]) { x; }");
        SYNTAX.put("generator", "function* g() { yield 1; }");
        SYNTAX.put("shorthandProperty", "var a = 1; var o = {a};");
        SYNTAX.put("computedProperty", "var o = {[\"a\"]: 1};");
        SYNTAX.put("getterSetter", "var o = { get a() { return 1; }, set a(v) {} };");
        // The four that decide whether an author on a newer host can ship to an older one.
        SYNTAX.put("class", "class A { m() { return 1; } }");
        SYNTAX.put("moduleImport", "import x from \"y\";");
        SYNTAX.put("moduleExport", "export const a = 1;");
        SYNTAX.put("asyncFunction", "async function f() { return 1; }");
        SYNTAX.put("await", "async function f() { return await 1; }");
        SYNTAX.put("optionalChaining", "var b = {}; var a = b?.c;");
        SYNTAX.put("nullishCoalescing", "var b = null; var a = b ?? 1;");
        SYNTAX.put("exponent", "var a = 2 ** 3;");
        SYNTAX.put("trailingCommaInCall", "function f(a) { return a; } f(1,);");
        SYNTAX.put("labelledArrowSwitch", "switch (1) { case 1: break; }");
    }

    /** Globals whose <b>presence</b> is the question — {@code typeof X}, in a standard scope. */
    private static final String[] GLOBALS = {
            "Symbol", "Map", "Set", "WeakMap", "WeakSet", "Promise", "Proxy", "Reflect", "BigInt",
            "ArrayBuffer", "Int8Array", "Float64Array", "JSON", "Math", "RegExp", "Error",
            "Object", "Array", "String", "Number", "Boolean", "Date", "Function",
            // Not ES at all -- Rhino's own Java bridge, and the reason `Java.type` has to be installed
            // by us rather than assumed (`plan_m10.md` §6.4).
            "Packages", "java", "JavaAdapter", "importClass", "importPackage", "Java",
    };

    // ── The probe ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void everyBandAnswersForEveryConstructAndTheAnswerIsWrittenDown() throws IOException {
        StringBuilder report = new StringBuilder();
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Map<String, String> answers = new LinkedHashMap<>();
                Rhino rhino = new Rhino(loader);

                for (Map.Entry<String, String> probe : SYNTAX.entrySet()) {
                    answers.put("syntax." + probe.getKey(), rhino.parses(probe.getValue()));
                }
                for (String global : GLOBALS) {
                    answers.put("global." + global, rhino.typeOf(global));
                }
                answers.putAll(rhino.behaviour());
                answers.put("rhino.implementationVersion", rhino.implementationVersion());

                // EVERY CONSTRUCT HAS AN ANSWER -- the shape assertion. A probe that silently skipped one
                // would leave the policy reading a missing key and defaulting to "accepted", which is the
                // wrong direction to fail in: it would offer band 8 a keyword it cannot parse.
                for (String key : SYNTAX.keySet()) {
                    assertNotNull(band + " has no answer for " + key, answers.get("syntax." + key));
                }

                Path written = write(band, answers);
                report.append('\n').append(band).append(" -> ").append(written).append('\n')
                        .append(summarise(answers));
            } finally {
                loader.close();
            }
        }
        System.out.println("Rhino capability probe:" + report);
    }

    /**
     * The four constructs `plan_m10.md` §13a claims no shipped band accepts.
     *
     * <p>Asserted rather than merely recorded, because the whole ES-level story told to authors rests on
     * them: if a band ever accepts {@code class}, the compatibility-band warning, the keyword filter and
     * a paragraph of the plan are all wrong at once, and this is where that should surface.</p>
     */
    @Test
    public void noBandAcceptsClassesOrModulesOrAsync() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Rhino rhino = new Rhino(loader);
                for (String construct : new String[] {
                        "class", "moduleImport", "moduleExport", "asyncFunction" }) {
                    String answer = rhino.parses(SYNTAX.get(construct));
                    assertFalse(band + " now ACCEPTS " + construct + " — plan_m10.md §13a and the "
                                    + "compatibility-band warning both need revising",
                            ACCEPTED.equals(answer));
                }
            } finally {
                loader.close();
            }
        }
    }

    /** Band 8 must be the floor: anything it accepts, the newer bands accept too. */
    @Test
    public void aNewerBandNeverAcceptsLessThanAnOlderOne() throws IOException {
        Map<String, String> floor = null;
        EngineBand floorBand = null;
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Rhino rhino = new Rhino(loader);
                Map<String, String> answers = new LinkedHashMap<>();
                for (Map.Entry<String, String> probe : SYNTAX.entrySet()) {
                    answers.put(probe.getKey(), rhino.parses(probe.getValue()));
                }
                if (floor != null) {
                    for (Map.Entry<String, String> older : floor.entrySet()) {
                        if (!ACCEPTED.equals(older.getValue())) continue;
                        assertEquals(band + " refuses " + older.getKey() + " which " + floorBand
                                        + " accepts — a script that runs on the older host would not run "
                                        + "on the newer one, and the compatibility warning assumes the "
                                        + "opposite",
                                ACCEPTED, answers.get(older.getKey()));
                    }
                }
                floor = answers;
                floorBand = band;
            } finally {
                loader.close();
            }
        }
    }

    // ── The API surface M10 is written against ──────────────────────────────────────────────────

    /**
     * The parser, AST and interop types the JS adapter names.
     *
     * <p>{@link EngineApiSurfaceTest} pins what M5 needed — {@code Context}, {@code ClassShutter} and
     * friends. This pins what M10 adds: IDE-mode parsing, the positioned AST, the symbol tables that
     * make scope resolution free, and the interop types the resolver unwraps. Same argument, one
     * milestone later.</p>
     */
    @Test
    public void everyBandCarriesTheParserAndInteropApiTheJsAdapterUses() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> environs = require(loader, "org.mozilla.javascript.CompilerEnvirons");
                requireMethod(environs, "setRecordingComments", boolean.class);
                requireMethod(environs, "setRecordingLocalJsDocComments", boolean.class);
                requireMethod(environs, "setRecoverFromErrors", boolean.class);
                requireMethod(environs, "setIdeMode", boolean.class);
                requireMethod(environs, "setLanguageVersion", int.class);
                // The compiled-mode opt-in of `plan_m10.md` §13a: the observer only fires in compiled
                // mode when the codegen was told to count, so a hot handler cannot be stopped without it.
                requireMethod(environs, "setGenerateObserverCount", boolean.class);

                Class<?> errorReporter = require(loader, "org.mozilla.javascript.ErrorReporter");
                Class<?> parser = require(loader, "org.mozilla.javascript.Parser");
                requireConstructor(parser, environs, errorReporter);
                requireMethod(parser, "parse", String.class, String.class, int.class);

                Class<?> collector = require(loader, "org.mozilla.javascript.ast.ErrorCollector");
                requireMethod(collector, "getErrors");

                Class<?> problem = require(loader, "org.mozilla.javascript.ast.ParseProblem");
                requireMethod(problem, "getFileOffset");
                requireMethod(problem, "getLength");
                requireMethod(problem, "getMessage");
                requireMethod(problem, "getType");

                Class<?> node = require(loader, "org.mozilla.javascript.ast.AstNode");
                requireMethod(node, "getAbsolutePosition");
                requireMethod(node, "getLength");
                requireMethod(node, "getType");
                requireMethod(node, "getJsDoc");
                requireMethod(node, "getEnclosingFunction");

                require(loader, "org.mozilla.javascript.ast.AstRoot");
                Class<?> scope = require(loader, "org.mozilla.javascript.ast.Scope");
                // THE SYMBOL TABLES. `plan_m10.md` §1.2 rests on these: they are why static structure
                // comes from Rhino's own parse rather than from a second tree-sitter view of the file.
                requireMethod(scope, "getSymbolTable");
                Class<?> symbol = require(loader, "org.mozilla.javascript.ast.Symbol");
                requireMethod(symbol, "getDeclType");
                requireMethod(symbol, "getName");
                Class<?> name = require(loader, "org.mozilla.javascript.ast.Name");
                requireMethod(name, "getDefiningScope");
                requireMethod(name, "getIdentifier");

                Class<?> function = require(loader, "org.mozilla.javascript.ast.FunctionNode");
                requireMethod(function, "getParams");
                requireMethod(function, "getFunctionName");

                require(loader, "org.mozilla.javascript.ast.VariableDeclaration");
                require(loader, "org.mozilla.javascript.ast.ObjectLiteral");
                require(loader, "org.mozilla.javascript.ast.NewExpression");
                require(loader, "org.mozilla.javascript.ast.StringLiteral");
                require(loader, "org.mozilla.javascript.ast.NodeVisitor");

                // THE ACCESSORS THE SCOPE WALK CALLS, resolved through `getMethod` so the hierarchy is
                // walked -- which is the whole point. See the test below for what this caught.
                requireMethod(require(loader, "org.mozilla.javascript.ast.PropertyGet"), "getTarget");
                requireMethod(require(loader, "org.mozilla.javascript.ast.PropertyGet"), "getProperty");
                requireMethod(require(loader, "org.mozilla.javascript.ast.Assignment"), "getLeft");
                Class<?> unary = require(loader, "org.mozilla.javascript.ast.UnaryExpression");
                requireMethod(unary, "getOperator");
                requireMethod(require(loader, "org.mozilla.javascript.ast.FunctionCall"), "getTarget");
                requireMethod(symbol, "getNode");
                requireMethod(scope, "getSymbol", String.class);
                requireMethod(node, "getParent");

                // Interop: what the resolver unwraps, and what the remap seam will patch.
                Class<?> javaObject = require(loader, "org.mozilla.javascript.NativeJavaObject");
                requireMethod(javaObject, "unwrap");
                require(loader, "org.mozilla.javascript.NativeJavaClass");
                require(loader, "org.mozilla.javascript.NativeJavaPackage");
                require(loader, "org.mozilla.javascript.WrapFactory");

                // Execution: the stop, the loader, and the script stack the console links.
                Class<?> context = require(loader, "org.mozilla.javascript.Context");
                requireMethod(context, "setApplicationClassLoader", ClassLoader.class);
                requireMethod(context, "setInstructionObserverThreshold", int.class);
                requireMethod(context, "compileString",
                        String.class, String.class, int.class, Object.class);
                Class<?> factory = require(loader, "org.mozilla.javascript.ContextFactory");
                requireDeclaredMethod(factory, "observeInstructionCount", context, int.class);
                Class<?> exception = require(loader, "org.mozilla.javascript.RhinoException");
                requireMethod(exception, "getScriptStackTrace");
                requireMethod(exception, "lineNumber");
                requireMethod(exception, "columnNumber");
            } finally {
                loader.close();
            }
        }
    }

    /**
     * <b>The bands are not source-compatible everywhere, and this is the one place they are not.</b>
     *
     * <p>{@code ObjectProperty} extends {@code InfixExpression} on band 8 — so it has
     * {@code getLeft()}/{@code getRight()} — and extends {@code AbstractObjectProperty} on 1.9.1, where
     * it has {@code getKey()}/{@code getValue()} instead. The adapter must therefore use <em>neither</em>
     * pair, and asks by position instead.</p>
     *
     * <h4>Why the compile-against-the-oldest-band rule did not catch it</h4>
     *
     * <p>That rule guarantees an API <b>exists</b> in band 8, which is what stops the adapter using
     * something newer. It says nothing about whether the call <b>resolves on a newer band</b>: javac
     * records the static type as the invocation's owner, so {@code ObjectProperty.getLeft()} compiled
     * perfectly against 1.7.15.1 and died at runtime with {@code NoSuchMethodError} on bands 11 and 17
     * only. A developer on Java 8 would never see it — the same failure shape as the regular-expression
     * one, and the second time this milestone has met it.</p>
     *
     * <p>So this test asserts the divergence rather than the compatibility: if a future Rhino restores
     * {@code getLeft} on both, this fails and the comment in {@code RhinoScopes} can be retired. Pinning
     * it the other way round — asserting both bands agree — is what the accessor list above does for
     * every method the adapter <em>does</em> call.</p>
     */
    @Test
    public void objectPropertyIsTheOneTypeTheBandsDisagreeAbout() throws IOException {
        boolean anyHasLeft = false;
        boolean anyLacksLeft = false;
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> property = require(loader, "org.mozilla.javascript.ast.ObjectProperty");
                boolean hasLeft = true;
                try {
                    property.getMethod("getLeft");
                } catch (NoSuchMethodException absent) {
                    hasLeft = false;
                }
                anyHasLeft |= hasLeft;
                anyLacksLeft |= !hasLeft;
                System.out.println(band + ": ObjectProperty.getLeft is "
                        + (hasLeft ? "present" : "ABSENT") + " (super "
                        + property.getSuperclass().getSimpleName() + ")");
            } finally {
                loader.close();
            }
        }
        assertTrue("no band has ObjectProperty.getLeft — the divergence this documents is gone in the "
                + "other direction, and RhinoScopes' comment needs revising", anyHasLeft);
        assertTrue("every band now has ObjectProperty.getLeft — the divergence is over and RhinoScopes "
                + "may use it again", anyLacksLeft);
    }

    /**
     * `plan_m10.md` §15.1 — how the console will find which script line is printing.
     *
     * <p>Reported rather than asserted, because there are two acceptable answers and the milestone picks
     * one from what it finds: a public {@code getSourcePositionFromStack} is the direct route, and if it
     * is package-private the fallback is constructing an {@code EvaluatorException} on the script thread
     * (Rhino fills its line from the current interpreter frame). A third option, named in the plan, is a
     * same-package accessor shaded beside Rhino. This test's job is to say which of the three is
     * needed.</p>
     */
    @Test
    public void reportHowTheCurrentScriptLineIsReachable() throws IOException {
        for (EngineBand band : EngineBand.values()) {
            EngineClassLoader loader = loaderFor(band);
            try {
                Class<?> context = require(loader, "org.mozilla.javascript.Context");
                String verdict = "absent";
                for (Method method : context.getDeclaredMethods()) {
                    if (!"getSourcePositionFromStack".equals(method.getName())) continue;
                    verdict = Modifier.isPublic(method.getModifiers()) ? "public" : "package-private";
                }
                System.out.println(band + ": Context.getSourcePositionFromStack is " + verdict);
            } finally {
                loader.close();
            }
        }
    }

    // ── Driving Rhino reflectively, because only the child loader can see it ────────────────────

    private static final String ACCEPTED = "accepted";

    /** The handful of Rhino calls this probe makes, behind names instead of reflection at every use. */
    private static final class Rhino {

        private final EngineClassLoader loader;
        private final Class<?> context;
        private final Class<?> scriptable;
        private final Class<?> scriptableObject;
        private final int es6;

        Rhino(EngineClassLoader loader) {
            this.loader = loader;
            this.context = require(loader, "org.mozilla.javascript.Context");
            this.scriptable = require(loader, "org.mozilla.javascript.Scriptable");
            this.scriptableObject = require(loader, "org.mozilla.javascript.ScriptableObject");
            try {
                this.es6 = context.getField("VERSION_ES6").getInt(null);
            } catch (ReflectiveOperationException absent) {
                throw new IllegalStateException("no Context.VERSION_ES6", absent);
            }
        }

        /** {@link #ACCEPTED}, or {@code refused: <the engine's own message>}. */
        String parses(String source) {
            Object cx = enter();
            try {
                context.getMethod("compileString", String.class, String.class, int.class, Object.class)
                        .invoke(cx, source, "probe.js", 1, null);
                return ACCEPTED;
            } catch (InvocationTargetException refused) {
                Throwable cause = refused.getCause();
                return "refused: " + oneLine(cause == null ? refused.toString() : cause.getMessage());
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException("the probe could not call compileString", broken);
            } finally {
                exit();
            }
        }

        /**
         * Whether a global is usable in a fresh standard scope, and as what.
         *
         * <p><b>{@code typeof} alone is not the question</b>, which cost a round: Rhino initialises some
         * standard objects lazily, and {@code typeof RegExp} answered {@code "undefined"} on 1.9.1 for a
         * global that plainly exists — a lazy slot that a {@code typeof} check does not force. So a
         * {@code "undefined"} answer is re-asked by <em>referencing</em> the name, which throws a
         * {@code ReferenceError} when it is genuinely absent and quietly initialises it when it is not.
         * Completion's question is "may an author write this", and that is the one being answered.</p>
         */
        String typeOf(String name) {
            Object cx = enter();
            try {
                Object scope = context.getMethod("initStandardObjects").invoke(cx);
                Object answer = evaluate(cx, scope, "typeof " + name);
                if ("undefined".equals(String.valueOf(answer))) {
                    answer = evaluate(cx, scope, "(function () { try { " + name
                            + "; return 'lazy:' + (typeof " + name
                            + "); } catch (e) { return 'undefined'; } })()");
                }
                return String.valueOf(answer);
            } catch (InvocationTargetException failed) {
                Throwable cause = failed.getCause();
                return "error: " + oneLine(cause == null ? failed.toString() : cause.getMessage());
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException("the probe could not evaluate a typeof", broken);
            } finally {
                exit();
            }
        }

        /** The §15 questions a context alone can answer. */
        Map<String, String> behaviour() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("behaviour.sealedScopeRefusesNewGlobals", sealedScopeRefusesNewGlobals());
            out.put("behaviour.promisesDrainAfterEvaluate", promisesDrainAfterEvaluate());
            out.put("behaviour.regexpLiteralWorks", regexpLiteralWorks());
            return out;
        }

        /**
         * Whether a regular-expression <b>literal</b> evaluates — which is not the same question as
         * whether the {@code RegExp} global exists, and on 1.9.1 the two answers differ.
         *
         * <p>The probe found {@code RegExp} genuinely absent from a plain {@code initStandardObjects()}
         * scope on that band: referencing it throws a {@code ReferenceError} rather than initialising a
         * lazy slot. That is worth knowing in two places — completion must not offer the constructor on a
         * band that has none, and if literals were broken too then a large fraction of real scripts would
         * simply not run, which would be a far bigger fact than an absent global.</p>
         */
        private String regexpLiteralWorks() {
            Object cx = enter();
            try {
                Object scope = context.getMethod("initStandardObjects").invoke(cx);
                Object answer = evaluate(cx, scope, "String(/a(b)/.exec('zab')[1])");
                return "b".equals(String.valueOf(answer)) ? "yes" : "unexpected: " + answer;
            } catch (InvocationTargetException failed) {
                Throwable cause = failed.getCause();
                return "no: " + oneLine(cause == null ? failed.toString() : cause.getMessage());
            } catch (ReflectiveOperationException broken) {
                return "unknown: " + broken;
            } finally {
                exit();
            }
        }

        /**
         * Whether {@code initStandardObjects(null, true)} leaves room to install {@code console} and
         * {@code Java} afterwards — `plan_m10.md` §15.4. If it refuses, the executor seals *after*
         * installing rather than before.
         */
        private String sealedScopeRefusesNewGlobals() {
            Object cx = enter();
            try {
                Object scope = context.getMethod("initStandardObjects", scriptableObject, boolean.class)
                        .invoke(cx, null, true);
                scriptableObject.getMethod("putProperty", scriptable, String.class, Object.class)
                        .invoke(null, scope, "cgProbe", "x");
                Object read = evaluate(cx, scope, "typeof cgProbe");
                return "string".equals(String.valueOf(read)) ? "no (a sealed scope still takes globals)"
                        : "yes (install before sealing)";
            } catch (InvocationTargetException refused) {
                return "yes (install before sealing)";
            } catch (ReflectiveOperationException broken) {
                return "unknown: " + broken;
            } finally {
                exit();
            }
        }

        /**
         * Whether a resolved promise's continuation has run by the time {@code evaluateString} returns —
         * `plan_m10.md` §15.8. If not, {@code JsHost} drains the queue itself after a run, or a script's
         * {@code .then} never fires and reads as the engine ignoring promises.
         */
        private String promisesDrainAfterEvaluate() {
            Object cx = enter();
            try {
                Object scope = context.getMethod("initStandardObjects").invoke(cx);
                if (!"function".equals(String.valueOf(evaluate(cx, scope, "typeof Promise")))) {
                    return "n/a (no Promise on this band)";
                }
                evaluate(cx, scope, "var r = 'pending';"
                        + " Promise.resolve().then(function () { r = 'drained'; });");
                return "drained".equals(String.valueOf(evaluate(cx, scope, "r")))
                        ? "yes" : "no (JsHost must drain)";
            } catch (InvocationTargetException failed) {
                Throwable cause = failed.getCause();
                return "error: " + oneLine(cause == null ? failed.toString() : cause.getMessage());
            } catch (ReflectiveOperationException broken) {
                return "unknown: " + broken;
            } finally {
                exit();
            }
        }

        String implementationVersion() {
            Object cx = enter();
            try {
                return String.valueOf(context.getMethod("getImplementationVersion").invoke(cx));
            } catch (ReflectiveOperationException absent) {
                return "unknown";
            } finally {
                exit();
            }
        }

        private Object evaluate(Object cx, Object scope, String source)
                throws ReflectiveOperationException {
            return context.getMethod("evaluateString",
                            scriptable, String.class, String.class, int.class, Object.class)
                    .invoke(cx, scope, source, "probe.js", 1, null);
        }

        private Object enter() {
            try {
                Object cx = context.getMethod("enter").invoke(null);
                context.getMethod("setLanguageVersion", int.class).invoke(cx, es6);
                // INTERPRETED, which is what the runtime will use (`plan_m10.md` §9.1) -- and it also
                // keeps the probe from asking the codegen to emit class files this JVM may not load.
                context.getMethod("setOptimizationLevel", int.class).invoke(cx, -1);
                context.getMethod("setApplicationClassLoader", ClassLoader.class).invoke(cx, loader);
                return cx;
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException("the probe could not enter a Context", broken);
            }
        }

        private void exit() {
            try {
                context.getMethod("exit").invoke(null);
            } catch (ReflectiveOperationException ignored) {
                // Nothing useful to do; the next enter() on this thread would fail loudly.
            }
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    /**
     * A loader for the band, <b>installed as this thread's context classloader before Rhino is touched
     * at all</b>.
     *
     * <h4>The ordering is the finding, and it is not a test detail</h4>
     *
     * <p>Rhino 1.8+ resolves its regular-expression engine through {@code ServiceLoader} on
     * {@code org.mozilla.javascript.RegExpLoader}, and the no-argument {@code ServiceLoader.load} reads
     * the <em>thread's</em> loader rather than the caller's. For a child-first engine loader that is the
     * host's, which cannot see the service file inside the band jar — so the lookup finds nothing.</p>
     *
     * <p>And <b>the answer is cached at class initialisation</b>, which is what makes setting the loader
     * late useless: this probe originally swapped it inside {@code enter()} and still got no regexes,
     * because reading {@code Context.VERSION_ES6} one line earlier had already initialised the class and
     * cached the negative answer. Once cached it is never re-asked for the life of that loader.</p>
     *
     * <p>The symptom is not a load error. It is {@code "Regular expressions are not available."} thrown
     * from the first regex a script evaluates — on bands 11 and 17 only, because band 8's older Rhino
     * predates the service lookup and works either way. {@code RhinoExecutor} carries the same rule.</p>
     */
    private static EngineClassLoader loaderFor(EngineBand band) throws IOException {
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        EngineClassLoader loader =
                EngineClassLoader.over(band, source, RhinoCapabilityProbeTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        return loader;
    }

    /** Puts the test's own loader back, whichever way a test left. @see #loaderFor */
    @After
    public void restoreContextClassLoader() {
        Thread.currentThread().setContextClassLoader(RhinoCapabilityProbeTest.class.getClassLoader());
    }

    private static Class<?> require(EngineClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException absent) {
            fail(loader.band() + " has no " + className + " — the JS adapter names it");
            return null;
        }
    }

    private static void requireMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            assertNotNull(owner.getMethod(name, parameters));
        } catch (NoSuchMethodException absent) {
            fail("missing " + signature(owner, name, parameters));
        }
    }

    /** For a method that is not public — {@code observeInstructionCount} is protected, by design. */
    private static void requireDeclaredMethod(Class<?> owner, String name, Class<?>... parameters) {
        try {
            assertNotNull(owner.getDeclaredMethod(name, parameters));
        } catch (NoSuchMethodException absent) {
            fail("missing " + signature(owner, name, parameters));
        }
    }

    private static void requireConstructor(Class<?> owner, Class<?>... parameters) {
        try {
            assertNotNull(owner.getConstructor(parameters));
        } catch (NoSuchMethodException absent) {
            fail("missing " + signature(owner, "<init>", parameters));
        }
    }

    private static String signature(Class<?> owner, String name, Class<?>... parameters) {
        StringBuilder out = new StringBuilder(owner.getName()).append('.').append(name).append('(');
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) out.append(", ");
            out.append(parameters[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    private static String oneLine(String message) {
        if (message == null) return "(no message)";
        String flattened = message.replaceAll("\\s+", " ").trim();
        return flattened.length() > 160 ? flattened.substring(0, 157) + "..." : flattened;
    }

    /**
     * Writes the band's answers where the engine will read them.
     *
     * <p>Hand-written rather than {@code Properties.store}, which writes a timestamp comment — so the
     * file would differ on every run and a build could never tell "the probe re-ran" from "the answer
     * changed". Deterministic output is what makes this diffable, and diffable is the point.</p>
     */
    private static Path write(EngineBand band, Map<String, String> answers) {
        Path directory = probeRoot();
        try {
            Files.createDirectories(directory);
            StringBuilder out = new StringBuilder();
            out.append("# Measured by RhinoCapabilityProbeTest against band ").append(band)
                    .append("'s real jars. Do not hand-edit — re-run the test.\n");
            for (Map.Entry<String, String> answer : answers.entrySet()) {
                out.append(answer.getKey()).append('=').append(answer.getValue()).append('\n');
            }
            Path file = directory.resolve("rhino-" + band.minimumFeatureVersion() + ".properties");
            Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** {@code language/build/probe/}, found from this test's own output directory. */
    private static Path probeRoot() {
        Path classes = Path.of(RhinoCapabilityProbeTest.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath().replace("%20", " ").replaceFirst("^/", ""));
        // .../build/classes/java/test -> .../build
        return classes.getParent().getParent().getParent().resolve("probe");
    }

    private static String summarise(Map<String, String> answers) {
        StringBuilder accepted = new StringBuilder();
        StringBuilder refused = new StringBuilder();
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            if (!answer.getKey().startsWith("syntax.")) continue;
            String construct = answer.getKey().substring("syntax.".length());
            StringBuilder target = ACCEPTED.equals(answer.getValue()) ? accepted : refused;
            if (target.length() > 0) target.append(' ');
            target.append(construct);
        }
        return "    accepted: " + accepted + "\n    refused:  " + refused + "\n";
    }

    /** A sanity check on the probe itself: something must be refused, or it is measuring nothing. */
    @Test
    public void theProbeCanTellAcceptedFromRefused() throws IOException {
        EngineBand band = EngineBand.values()[0];
        EngineClassLoader loader = loaderFor(band);
        try {
            Rhino rhino = new Rhino(loader);
            assertEquals(ACCEPTED, rhino.parses("var a = 1;"));
            assertTrue("a deliberate syntax error was not refused",
                    rhino.parses("var = = ;").startsWith("refused"));
            assertEquals("undefined", rhino.typeOf("cgDefinitelyNotAGlobal"));
        } finally {
            loader.close();
        }
    }
}
