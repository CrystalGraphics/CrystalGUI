package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.exec.ScriptHost;
import com.crystalgui.language.java.exec.ScriptPrelude;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.RunState;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.language.run.exec.ScriptRefusedException;
import com.crystalgui.scripting.ScriptSink;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>The Java class filter — §19.2's table, for the language that had none of it.</b>
 *
 * <p>JavaScript had all four rows and Java had zero: {@code ScriptClassLoader} is parent-first over the
 * host loader with no check, so a script could reach anything on the classpath. These pin the two halves
 * that make the guardrail worth having, and the composition rule that lets a deployment write it.</p>
 *
 * <p><b>Read §19.1 before reading these as security tests.</b> For Java this is a guardrail: compiled
 * bytecode links what it links and there is no {@code SecurityManager} to stop it. What is asserted here
 * is that an honest script cannot reach a refused class by accident, and that the tool and the runtime
 * agree about which classes those are — not that a determined author is contained.</p>
 */
public class JavaSandboxTest {

    /**
     * The side effect lives in {@link ScriptSink}, which is deliberately NOT a nested class here.
     *
     * <p>Every other test in this module nests its sink, which puts it in {@code com.crystalgui.language.*}
     * — and that is exactly what {@code ScriptPolicy.ALWAYS_REFUSED} refuses. Those tests run under
     * {@code allowAll()}, where the floor is never consulted, so they are unaffected; this one configures a
     * policy, so its sink has to live where a host's API actually would.</p>
     */
    private JavaEngine engine;
    private ScriptHost host;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        host = ScriptHost.of(engine);
        ScriptSink.WRITTEN.clear();
    }

    @After
    public void closeEngine() throws IOException {
        // THE POLICY IS PROCESS-WIDE, so a test that set one and did not clear it restricts every test
        // that runs after it -- in whatever order the runner picked, which is how a suite acquires a
        // failure nobody can reproduce alone.
        JavaLanguage.restrictTo(null);
        if (host != null) host.close();
        if (engine != null) engine.close();
    }

    private ScriptHost.Compiled compile(String body) {
        ScriptPrelude.Wrapped wrapped = ScriptPrelude.forClass("Script").build().wrap(body);
        ScriptHost.Compiled compiled = host.compile(wrapped);
        assertTrue("the script did not compile: " + compiled.messages(), compiled.successful());
        return compiled;
    }

    private static String sink() {
        return ScriptSink.class.getCanonicalName();
    }

    // ── The policy itself ───────────────────────────────────────────────────────────────────────

    /** A denial is a veto: an allowlist entry cannot re-permit one, or {@code UNSAFE} means nothing. */
    @Test
    public void aDenialOutranksAnAllowance() {
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.lang"), List.of("java.lang.reflect"));
        assertTrue("an allowed class was refused", policy.allowsClass("java.lang.String"));
        assertFalse("an allowlist entry re-permitted a denied package",
                policy.allowsClass("java.lang.reflect.Method"));
    }

    /**
     * The posture this was built for — everything except the routes out of a class filter.
     *
     * <p>An allowlist wide enough to be usable is thousands of entries; ten refusals a deployment will
     * maintain beats ten thousand permissions it will not.
     */
    @Test
    public void denyingTheUnsafeSetLeavesOrdinaryJavaReachable() {
        ScriptPolicy policy = ScriptPolicy.denying(ScriptPolicy.UNSAFE);
        assertTrue(policy.allowsClass("java.util.ArrayList"));
        assertTrue(policy.allowsClass("com.example.mod.Thing"));
        assertFalse(policy.allowsClass("java.lang.reflect.Method"));
        assertFalse(policy.allowsClass("java.lang.invoke.MethodHandles"));
        assertFalse(policy.allowsClass("java.lang.ClassLoader"));
        assertFalse(policy.allowsClass("java.lang.Runtime"));
        assertFalse("a nested class is part of the class its prefix named",
                policy.allowsClass("java.lang.ClassLoader$NativeLibrary"));
    }

    /**
     * A package is not its worst member.
     *
     * <p>{@code java.lang.reflect} is refused and {@code java.lang} is not refused for containing it —
     * the opposite of the allow test, where a prefix UNDER the package does admit it, because a path has
     * to be walkable to reach what is at the end of it.
     */
    @Test
    public void aPackageIsRefusedOnlyAtOrUnderTheDenial() {
        ScriptPolicy policy = ScriptPolicy.denying(List.of("java.lang.reflect"));
        assertTrue(policy.allowsPackage("java.lang"));
        assertFalse(policy.allowsPackage("java.lang.reflect"));
        assertFalse(policy.allowsPackage("java.lang.reflect.generics"));
    }

    @Test
    public void anEmptyDenialIsNotAPolicy() {
        assertTrue("a denying() of nothing must be allow-all, not refuse-all",
                ScriptPolicy.denying(List.of()).allowsEverything());
    }

    // ── The ahead-of-time scan ──────────────────────────────────────────────────────────────────

    /**
     * <b>Refused before a line of it runs</b>, which is the whole reason the scan exists beside the
     * loader gate. A loader check alone refuses mid-run: the script has already written to the sink
     * before it touches the class that stops it, and a partly-applied script is its own hazard.
     */
    @Test
    public void aScriptNamingARefusedClassNeverStarts() {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io")));
        ScriptHost.Compiled compiled = compile(
                sink() + ".write(\"the side effect\");\n"
                        + "java.io.File file = new java.io.File(\"x\");\n");

        try {
            host.run(compiled, Map.of());
            fail("a script reaching a refused class was allowed to run");
        } catch (ScriptRefusedException refused) {
            assertTrue("the refusal does not name the class: " + refused.refused(),
                    refused.refused().contains("java.io.File"));
        } catch (Throwable other) {
            fail("expected a refusal, got " + other);
        }

        assertTrue("the script ran far enough to have an effect before being refused",
                ScriptSink.WRITTEN.isEmpty());
    }

    /**
     * <b>A refusal is a run that failed, and has to be recorded as one.</b>
     *
     * <p>Not bookkeeping. {@code RunPanel} decides between its empty state and the console from
     * {@code RunSessions.isEmpty()}, and an empty listing <em>detaches the transcript from the tree</em>
     * — so a refusal that told nobody appended its explanation to a console that was not on screen, and
     * the author got a balloon reading "see the Run console" over a panel showing "To run a script, do
     * one of the following". Everything about the refusal was right except that nothing could see it.</p>
     *
     * <p>Nothing throws when this regresses, which is why it is asserted rather than left to the panel.</p>
     */
    @Test
    public void aRefusalIsRecordedAsAFailedRun() {
        RunSessions sessions = new RunSessions();
        host.reportTo(sessions);
        Resource file = Resource.of("project", "src/Script.java");
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io.File")));

        try {
            host.run(compile("java.io.File f = new java.io.File(\"x\");\n").withSource(file), Map.of());
            fail("the refused script ran");
        } catch (ScriptRefusedException expected) {
            // The state below is the assertion.
        } catch (Throwable other) {
            fail("expected a refusal, got " + other);
        }

        assertFalse("the panel decides between its empty state and the console from this listing, so a "
                + "refusal nobody recorded is a refusal nobody can read", sessions.isEmpty());
        assertEquals(RunState.FAILED, sessions.stateOf(file));
    }

    /** And a script that stays inside the policy is untouched by any of this. */
    @Test
    public void aScriptWithinThePolicyStillRuns() throws Throwable {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io")));
        host.run(compile(sink() + ".write(\"allowed\");\n"), Map.of());
        assertEquals(List.of("allowed"), ScriptSink.WRITTEN);
    }

    /**
     * A script's own classes are never asked about.
     *
     * <p>They exist in no policy, so asking would refuse every script under any allowlist that did not
     * happen to name the package the prelude invented for it.
     */
    @Test
    public void aScriptsOwnClassesAreNotSubjectToTheAllowlist() throws Throwable {
        // The OUTER class too: a nested class's bytecode names its enclosing type, and a dot-boundary
        // prefix of `…JavaSandboxTest$Sink` does not admit `…JavaSandboxTest`.
        JavaLanguage.restrictTo(ScriptPolicy.of(List.of("java.lang", "java.util",
                ScriptSink.class.getName())));
        host.run(compile(sink() + ".write(\"still ran\");\n"), Map.of());
        assertEquals(List.of("still ran"), ScriptSink.WRITTEN);
    }

    /**
     * <b>The kill switch is not subject to the policy it would otherwise break.</b>
     *
     * <p>{@code Safepoints} injects a call to {@code ScriptControl.checkpoint()} into every method of
     * every script, so every compiled script <em>links</em> a class its author never wrote. Policing that
     * made the kill switch the thing that refused the script: the first ordinary allowlist tried here
     * refused with "this script reaches 2 classes… ScriptControl", naming an internal the author has
     * never heard of. Both halves exempt it — the scan and the loader — so this asserts a policy that
     * names none of our internals still runs.
     */
    @Test
    public void theInjectedSafepointIsNotPoliced() throws Throwable {
        JavaLanguage.restrictTo(ScriptPolicy.of(List.of("java.lang", ScriptSink.class.getName())));
        host.run(compile(sink() + ".write(\"ran with a narrow allowlist\");\n"), Map.of());
        assertEquals(List.of("ran with a narrow allowlist"), ScriptSink.WRITTEN);
    }

    /**
     * <b>The compiler's own plumbing is not the author's reach.</b>
     *
     * <p>Since Java 9 a string concatenation compiles to an {@code invokedynamic} against
     * {@code StringConcatFactory}, and a lambda to one against {@code LambdaMetafactory}; both bootstrap
     * descriptors name {@code MethodHandles.Lookup}. All of it lands in the constant pool of a class
     * whose source says {@code "count: " + n}. So a policy refusing {@code java.lang.invoke} — which
     * {@link ScriptPolicy#UNSAFE} does, because method handles are a real escape — refused <em>every
     * script that concatenates a string or takes a lambda</em>.</p>
     *
     * <p>Found in the harness rather than here: a four-line fixture reported "reaches 7 classes" over a
     * file whose author had reached for three. The three extras were all bootstrap.</p>
     *
     * <p>Vacuous on a toolchain that does not emit {@code invokedynamic} for these, which is why the
     * assertion is that the script <em>runs</em> rather than that the pool contains something.</p>
     */
    @Test
    public void stringConcatenationAndLambdasAreNotAReachIntoMethodHandles() throws Throwable {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.lang.invoke")));
        host.run(compile(
                "int n = 2;\n"
                        + sink() + ".write(\"count: \" + n);\n"
                        + "java.util.List<String> items = java.util.Arrays.asList(\"a\", \"b\");\n"
                        + "items.forEach(item -> " + sink() + ".write(item));\n"), Map.of());

        assertEquals(List.of("count: 2", "a", "b"), ScriptSink.WRITTEN);
    }

    /** But an author who actually calls into method handles is still refused — it emits an instruction. */
    @Test
    public void callingMethodHandlesDirectlyIsStillRefused() {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.lang.invoke")));
        ScriptHost.Compiled compiled = compile(
                "java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();\n"
                        + sink() + ".write(String.valueOf(lookup));\n");
        try {
            host.run(compiled, Map.of());
            fail("a direct call into MethodHandles was allowed");
        } catch (ScriptRefusedException refused) {
            assertTrue("the refusal does not name it: " + refused.refused(),
                    refused.refused().stream().anyMatch(n -> n.startsWith("java.lang.invoke.MethodHandles")));
        } catch (Throwable other) {
            fail("expected a refusal, got " + other);
        }
    }

    // ── The policy is not reachable from what it polices ────────────────────────────────────────

    /**
     * <b>A script cannot switch the filter off.</b>
     *
     * <p>This was open, and trivially: {@code JavaLanguage.restrictTo} is {@code public static}, the
     * class sits on the host classpath, and {@code ScriptClassLoader} is parent-first — so under a policy
     * of "deny java.io" the name {@code com.crystalgui.language.java.JavaLanguage} is not denied, the
     * scan passes it, the loader passes it, and one line of script turns the whole thing off for every
     * script after it. A filter its subject can disable is not a filter.</p>
     *
     * <p>{@code ScriptPolicy.ALWAYS_REFUSED} is the answer and is deliberately <b>not</b> part of
     * {@link ScriptPolicy#UNSAFE}: UNSAFE is a list a host composes and may edit down, and this is the
     * machinery that enforces whatever it composes. A host cannot permit it, which is the only property
     * that makes it worth having.</p>
     */
    @Test
    public void aScriptCannotReachThePolicyThatPolicesIt() {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io")));
        ScriptHost.Compiled compiled = compile(
                "com.crystalgui.language.java.JavaLanguage.restrictTo(null);\n");

        try {
            host.run(compiled, Map.of());
        } catch (ScriptRefusedException expected) {
            assertTrue("the refusal does not name the language stack: " + expected.refused(),
                    expected.refused().stream().anyMatch(n -> n.startsWith("com.crystalgui.language")));
        } catch (Throwable other) {
            // A loader refusal is equally acceptable; what is not acceptable is the call succeeding.
        }

        assertFalse("a script disabled the policy that was policing it",
                JavaLanguage.policy().allowsEverything());
        assertFalse("and the policy it escaped is no longer being applied",
                JavaLanguage.policy().allowsClass("java.io.File"));
    }

    /** The engine's own machinery is refused too — the loader, the host, the bands. */
    @Test
    public void theEngineMachineryIsRefusedUnderAnyPolicy() {
        ScriptPolicy wideOpenExceptOneThing = ScriptPolicy.denying(List.of("java.io"));
        assertFalse(wideOpenExceptOneThing.allowsClass(
                "com.crystalgui.language.java.JavaLanguage"));
        assertFalse(wideOpenExceptOneThing.allowsClass("com.crystalgui.language.js.JsLanguage"));
        assertFalse(wideOpenExceptOneThing.allowsClass("com.crystalgui.language.run.ScriptPolicy"));
        assertFalse(wideOpenExceptOneThing.allowsClass(
                "com.crystalgui.language.engine.ScriptClassLoader"));

        // AND AN ALLOWLIST CANNOT PERMIT IT EITHER, which is the half that makes it a floor rather than
        // a default. Naming it explicitly is the obvious way to try.
        ScriptPolicy tryingToPermitIt = ScriptPolicy.of(List.of("com.crystalgui.language"));
        assertFalse(tryingToPermitIt.allowsClass("com.crystalgui.language.java.JavaLanguage"));
    }

    /** But the UI a script is given is not the machinery, and stays reachable. */
    @Test
    public void theHostsOwnApiIsUntouchedByTheFloor() {
        ScriptPolicy policy = ScriptPolicy.denying(ScriptPolicy.UNSAFE);
        assertTrue("the floor must not swallow com.crystalgui.ui, which is what scripts are FOR",
                policy.allowsClass("com.crystalgui.ui.elements.Button"));
        assertTrue(policy.allowsClass("com.crystalgui.text.TextBuffer"));
    }

    // ── The loader gate ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>The half the scan cannot do.</b> {@code Class.forName} of a name built at run time puts nothing
     * in the constant pool but {@code java.lang.Class}, which every script names — so the scan passes it
     * and only the loader sees what is actually resolved. Both halves, or neither is worth much.
     */
    @Test
    public void aClassResolvedByNameAtRunTimeIsStillRefused() {
        JavaLanguage.restrictTo(ScriptPolicy.denying(List.of("java.io")));
        ScriptHost.Compiled compiled = compile(
                "String name = \"java.io\" + \".\" + \"File\";\n"
                        + "Class.forName(name);\n");

        try {
            host.run(compiled, Map.of());
            fail("a late-resolved refused class was loaded");
        } catch (ScriptRefusedException tooEarly) {
            fail("the scan should not have caught this -- it is the loader's case, and a scan that "
                    + "does catch it is reading something other than the constant pool");
        } catch (Throwable expected) {
            assertTrue("expected the loader to refuse it, got " + expected,
                    causeChainMentions(expected, "ScriptPolicy"));
        }
    }

    private static boolean causeChainMentions(Throwable thrown, String fragment) {
        for (Throwable at = thrown; at != null; at = at.getCause()) {
            if (at.getMessage() != null && at.getMessage().contains(fragment)) return true;
            if (at.getCause() == at) break;
        }
        return false;
    }
}
