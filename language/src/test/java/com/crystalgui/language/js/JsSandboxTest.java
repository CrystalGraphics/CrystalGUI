package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.language.run.ScriptRuntime;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>M10.10 — one allowlist, obeyed by all four surfaces.</b>
 *
 * <p>The milestone's exit criterion is a single claim with four halves: a refused class is absent from
 * {@code membersOf}, absent from the completion list, absent from the type index, and <em>throws when the
 * script calls it</em>. Any three without the fourth is the failure this exists to prevent — a class
 * offered by the popup and refused at run time, or reachable at run time and invisible to the editor.</p>
 */
public class JsSandboxTest {

    /**
     * Refused in these tests, and chosen because a script can plainly reach it.
     *
     * <p>{@code java.lang.System} is the shape a policy actually exists for: {@code System.exit} ends the
     * game. Using something harmless would test the mechanism against a case nobody would write down.</p>
     */
    private static final String REFUSED = "java.lang.System";

    /** Allowed, so every assertion has a control: the policy narrows rather than switching off. */
    private static final String ALLOWED = "java.util.ArrayList";

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @After
    public void restoreTheOpenPosture() {
        // THE POLICY IS PROCESS-WIDE, so a test that left one installed would restrict every later test in
        // the JVM -- and the failure would look like resolution breaking rather than like a leaked posture.
        // BOTH POSTURES AT ONCE: the mapping is static for the same reason and leaks the same way, so one
        // call restores them and a new test cannot restore half.
        JsLanguage.resetPosturesForTesting();
    }

    /** Only `java.util`, so ArrayList is reachable and System is not. */
    private static void restrictToJavaUtil() {
        JsLanguage.restrictTo(ScriptPolicy.of(List.of("java.util")));
    }

    private static Analysis analyse(String source) {
        return JsLanguage.analyzer().analyze("Probe.js", source, 1L);
    }

    // ── The policy itself ───────────────────────────────────────────────────────────────────────

    @Test
    public void aPrefixMatchesOnADotBoundary() {
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue(policy.allowsClass("java.util.List"));
        assertTrue("a subpackage is under the prefix",
                policy.allowsClass("java.util.concurrent.Future"));
        assertTrue("a nested class is part of the class its prefix named",
                policy.allowsClass("java.util.Map$Entry"));
        assertFalse("a prefix must not admit a longer package name",
                policy.allowsClass("java.utility.Thing"));
        assertFalse(policy.allowsClass("java.lang.System"));
    }

    @Test
    public void anArrayIsItsElementType() {
        // Refusing the array form while admitting the element would refuse a spelling, not a class.
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue(policy.allowsClass("java.util.List[]"));
        assertFalse(policy.allowsClass("java.lang.System[]"));
    }

    @Test
    public void aPrimitiveIsAlwaysReachable() {
        // `int` has no package and no members; refusing it makes every method taking one undescribable.
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue(policy.allowsClass("int"));
        assertTrue(policy.allowsClass("void"));
    }

    @Test
    public void anEmptyAllowlistRefusesEverythingRatherThanNothing() {
        // A host that means "no Java at all" has to be able to say it; widening it silently would be the
        // worst thing this class could do.
        ScriptPolicy policy = ScriptPolicy.of(List.of());
        assertFalse(policy.allowsEverything());
        assertFalse(policy.allowsClass("java.util.List"));
    }

    @Test
    public void allowAllIsTheDefaultAndSaysSo() {
        assertTrue(ScriptPolicy.allowAll().allowsEverything());
        assertTrue(ScriptPolicy.allowAll().allowsClass("anything.at.All"));
        assertTrue("a null list is not a restriction", ScriptPolicy.of(null).allowsEverything());
    }

    @Test
    public void aPackageIsVisibleWhenAnythingUnderItIs() {
        // `java` must be offerable for `java.util.List` to be reachable through it.
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));
        assertTrue(policy.allowsPackage("java"));
        assertTrue(policy.allowsPackage("java.util.concurrent"));
        assertFalse(policy.allowsPackage("javax"));
    }

    // ── The four surfaces ───────────────────────────────────────────────────────────────────────

    /** <b>membersOf.</b> A refused class has no members, so nothing can be suggested about it. */
    @Test
    public void aRefusedClassHasNoMembers() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var s = Java.type('" + REFUSED + "');\nvar a = new " + ALLOWED + "();\n";

        restrictToJavaUtil();
        Analysis restricted = analyse(source);
        SymbolInfo refused = restricted.resolveAt(source.indexOf("s ="));
        // The name still resolves as a declaration -- it is in the file -- but nothing is behind it.
        assertTrue("a refused class was described anyway",
                refused == null || refused.type() == null
                        || restricted.membersOf(refused.type(), 0).isEmpty());

        SymbolInfo allowed = restricted.resolveAt(source.indexOf("a ="));
        assertNotNull(allowed);
        assertFalse("the control class lost its members too — the policy is not narrowing, it is off",
                restricted.membersOf(allowed.type(), 0).isEmpty());
    }

    /**
     * <b>The hover.</b> The fourth surface §21.9 names, and the one nothing was asking about.
     *
     * <p>The governance row reads "a refused type is absent from execution, completion, <b>hover</b> and
     * index in the same test". Three of those had a case here and hover did not — so the row was
     * claiming coverage that did not exist, which is worse than a row admitting a gap. The gate was
     * real ({@code InteropResolver.describe} consults the policy on its second line); nothing had ever
     * checked it, so a regression there would have been invisible in the surface a user reaches by
     * moving the mouse.</p>
     *
     * <p>And the control matters as much as the subject. A hover that answered nothing for
     * <em>everything</em> would pass the first assertion perfectly — that is not a narrowed policy, it
     * is a broken resolver.</p>
     */
    @Test
    public void aRefusedClassDoesNotDescribeItselfOnHover() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        String source = "var s = Java.type('" + REFUSED + "');\n"
                + "var a = Java.type('" + ALLOWED + "');\n";

        Analysis open = analyse(source);
        SymbolInfo beforeRestriction = open.resolveAt(source.indexOf("s ="));
        Assume.assumeNotNull("the interop tier answered nothing even unrestricted, so this test would "
                + "pass for the wrong reason", beforeRestriction);

        restrictToJavaUtil();
        Analysis restricted = analyse(source);

        SymbolInfo refused = restricted.resolveAt(source.indexOf("s ="));
        assertTrue("a refused class described itself on hover, in the one surface reached by moving "
                        + "the mouse rather than by asking",
                refused == null || refused.type() == null);

        SymbolInfo allowed = restricted.resolveAt(source.indexOf("a ="));
        assertNotNull("the control class stopped describing itself too -- the resolver is not "
                + "narrowing, it is answering nothing", allowed);
        assertNotNull("...and it lost its TYPE, which is the half the hover draws", allowed.type());
    }

    /** <b>The completion list.</b> Absent from it, rather than offered and then refused. */
    @Test
    public void aRefusedClassIsNotOfferedInsideJavaType() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        restrictToJavaUtil();
        assertFalse("a refused class was offered for insertion",
                insertionsFor("var t = Java.type('Syste").contains(REFUSED));
        assertTrue("the control class is not offered either — nothing is being filtered, everything is",
                insertionsFor("var t = Java.type('ArrayLis").contains(ALLOWED));
    }

    /** <b>The type index.</b> The filtered view hides it; the shared index itself is untouched. */
    @Test
    public void theFilteredIndexHidesARefusedTypeAndTheSharedOneDoesNot() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        TypeIndex index = JsLanguage.typeIndexForTesting();
        assertNotNull(index);
        ScriptPolicy policy = ScriptPolicy.of(List.of("java.util"));

        assertTrue("the shared index must stay unfiltered — it is shared per classpath",
                qualifiedNames(index.matching("System")).contains(REFUSED));
        assertFalse("the filtered view still offered a refused type",
                qualifiedNames(index.filtered(policy::allowsClass).matching("System"))
                        .contains(REFUSED));
        assertTrue("the filtered view lost an allowed type",
                qualifiedNames(index.filtered(policy::allowsClass).matching("ArrayList"))
                        .contains(ALLOWED));
    }

    /**
     * <b>And the call throws.</b> The half without which the other three are decoration.
     *
     * <p>Refused by Rhino's own {@code ClassShutter}, at the moment the script names the class — not by
     * anything the host could check afterwards, which is what makes the refusal real rather than
     * advisory.</p>
     */
    @Test
    public void aRefusedClassThrowsWhenTheScriptCallsIt() throws Throwable {
        restrictToJavaUtil();
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            assertFalse("the host is not obeying the policy", host.policy().allowsEverything());
            try {
                host.run(host.compileScript("Probe.js",
                        "var S = Java.type('" + REFUSED + "');\n", Map.of()), Map.of());
                fail("a refused class was reachable at run time");
            } catch (Throwable refused) {
                String message = String.valueOf(refused.getMessage());
                assertTrue("the refusal does not name what was refused: " + message,
                        message.contains(REFUSED) || message.contains("not permitted")
                                || message.contains("prohibited"));
            }

            // AND THE CONTROL RUNS. Without this the test passes against a policy that refuses everything,
            // which is not a sandbox, it is a broken engine.
            Object answer = host.run(host.compileScript("Probe.js",
                    "var a = new " + ALLOWED + "(); a.add('x'); a.size();\n", Map.of()), Map.of());
            assertEquals(1, ((Number) answer).intValue());
        } finally {
            host.close();
        }
    }

    /**
     * <b>A refusal is refused per REACH, and the script may carry on.</b>
     *
     * <p>That is the whole difference between the two engines: Java refuses a script as a file, before
     * it starts, because compiled bytecode links its classes when the class is defined; Rhino is asked
     * every time the script names one, so it can answer every time. The harness fixtures are written
     * around that distinction and say so at the top of each file.</p>
     *
     * <p>It was not true of {@code Java.type}. That path threw a bare {@link SecurityException}, and
     * Rhino does not hand an arbitrary Java exception raised inside a host {@code Callable} to a script's
     * own {@code catch} — so the whole file died on the first refused reach, one line into a fixture
     * whose first line says the opposite. The shutter path was always correct, which is what made it look
     * like a fixture problem: section 3 of {@code SandboxTest.js} was caught and section 1 was not.</p>
     *
     * <p>Catchability weakens nothing. The reach is still refused; all a script gains is the ability to
     * notice, which is what any other failed operation already offers.</p>
     */
    @Test
    public void aScriptCanCatchARefusalAndKeepGoing() throws Throwable {
        restrictToJavaUtil();
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            Object answer = host.run(host.compileScript("Probe.js",
                    "var reached = 'no';\n"
                            + "try { Java.type('" + REFUSED + "'); reached = 'yes'; }\n"
                            + "catch (refused) { reached = 'caught'; }\n"
                            + "reached;\n", Map.of()), Map.of());
            assertEquals("the script could not catch its own refusal", "caught",
                    String.valueOf(answer));
        } finally {
            host.close();
        }
    }

    /** Same for a class that simply is not there — an ordinary failure, and ordinary failures are catchable. */
    @Test
    public void aMissingClassIsCatchableToo() throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            Object answer = host.run(host.compileScript("Probe.js",
                    "try { Java.type('no.such.Class'); 'no'; } catch (e) { 'caught'; }\n",
                    Map.of()), Map.of());
            assertEquals("caught", String.valueOf(answer));
        } finally {
            host.close();
        }
    }

    /** The package spelling is refused too — one class, not one path to it. */
    @Test
    public void theBarePackageSpellingIsRefusedAsWell() throws Throwable {
        restrictToJavaUtil();
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            host.run(host.compileScript("Probe.js", "java.lang.System.exit(0);\n", Map.of()), Map.of());
            fail("a refused class was reachable through its package chain");
        } catch (Throwable expected) {
            // The point of the test — and `exit(0)` would have ended the JVM had it got through.
        } finally {
            host.close();
        }
    }

    /** Setting it through the runtime seam and through the language are the same act. */
    @Test
    public void theRuntimeSeamAndTheLanguageShareOnePolicy() {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            ScriptRuntime asSeam = host;
            asSeam.restrictTo(ScriptPolicy.of(List.of("java.util")));
            assertFalse("the language did not see a policy set through the runtime",
                    JsLanguage.policy().allowsEverything());
            assertFalse(host.policy().allowsClass(REFUSED));
        } finally {
            host.close();
        }
    }

    // ── Reading the answers ─────────────────────────────────────────────────────────────────────

    private static List<String> insertionsFor(String prefixText) {
        String text = prefixText + "');\n";
        TextBuffer buffer = new TextBuffer(text);
        JsLanguageServices services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null,
                "Probe.js", null, JsLanguage.typeIndexForTesting(), JsLanguage::policy);
        try {
            int caret = prefixText.length();
            int wordStart = caret;
            while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) wordStart--;
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.explicit(caret, text.substring(wordStart, caret)),
                    versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));
            List<String> insertions = new ArrayList<>();
            for (CompletionItem item : answered.get().items()) insertions.add(item.textToInsert());
            return insertions;
        } finally {
            services.close();
        }
    }

    private static List<String> qualifiedNames(TypeIndex.Match match) {
        List<String> names = new ArrayList<>();
        for (TypeIndex.Entry entry : match.entries()) {
            names.add(entry.qualifiedName());
        }
        return names;
    }
}
