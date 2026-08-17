package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.text.Change;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

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

/**
 * <b>M10.9 — the JavaScript fix catalog, one fixture per family.</b>
 *
 * <p>Shaped like {@code FixFixture}: a source with a {@code |} where the caret is, and an assertion on the
 * <em>text the edit produces</em> rather than on the edit's internals. Applying the change and comparing
 * the result is the only assertion that cannot pass against an edit at the wrong offsets.</p>
 */
public class JsQuickFixTest {

    private static final String CARET = "|";

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    /** Every action offered at the {@code |}. */
    private static List<CodeAction> actionsAt(String fixture) {
        int caret = fixture.indexOf(CARET);
        assertTrue("the fixture has no caret marker", caret >= 0);
        String text = fixture.substring(0, caret) + fixture.substring(caret + 1);
        return JsLanguage.analyzer().analyze("Probe.js", text, 1L, null)
                .codeActionsIn(caret, caret, null);
    }

    private static String sourceOf(String fixture) {
        int caret = fixture.indexOf(CARET);
        return fixture.substring(0, caret) + fixture.substring(caret + 1);
    }

    private static List<String> titlesAt(String fixture) {
        List<String> titles = new ArrayList<>();
        for (CodeAction action : actionsAt(fixture)) titles.add(action.title());
        return titles;
    }

    private static CodeAction titled(String fixture, String title) {
        for (CodeAction action : actionsAt(fixture)) {
            if (title.equals(action.title())) return action;
        }
        return null;
    }

    /**
     * The document after applying an action — the only assertion worth making.
     *
     * <p>Applied back to front so an earlier change cannot move a later one's offsets, which is what a
     * {@code ChangeSet} means and is the property a hand-rolled loop gets wrong.</p>
     */
    private static String applied(String fixture, String title) {
        CodeAction action = titled(fixture, title);
        assertNotNull("no action titled [" + title + "] among " + titlesAt(fixture), action);
        ChangeSet edit = action.edit();
        assertNotNull("the action carries no edit", edit);

        List<Change> changes = new ArrayList<>(edit.changes());
        changes.sort((a, b) -> Integer.compare(b.from(), a.from()));
        StringBuilder out = new StringBuilder(sourceOf(fixture));
        for (Change change : changes) out.replace(change.from(), change.to(), change.insert());
        return out.toString();
    }

    // ── Corrections ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnusedLocalCanBeRemovedWithItsLine() {
        String fixture = "function f() {\n    var nev|erRead = 1;\n    return 2;\n}\n";
        assertEquals("function f() {\n    return 2;\n}\n",
                applied(fixture, "Remove 'neverRead'"));
    }

    @Test
    public void oneUnusedNameOfSeveralLosesOnlyItsOwnInitializer() {
        // Taking the statement would delete `used`, which is used.
        String fixture = "function f() {\n    var used = 1, unus|ed = 2;\n    return used;\n}\n";
        assertEquals("function f() {\n    var used = 1;\n    return used;\n}\n",
                applied(fixture, "Remove 'unused'"));
    }

    @Test
    public void aUsedLocalIsNotOfferedARemoval() {
        String fixture = "function f() {\n    var kep|t = 1;\n    return kept;\n}\n";
        assertFalse(titlesAt(fixture).toString(), titlesAt(fixture).contains("Remove 'kept'"));
    }

    /**
     * "Did you mean" over what is actually in scope, ranked by the <b>Java catalog's</b> own ranking.
     *
     * <p>{@code SimilarNames} takes strings and returns strings, so nothing about it was ever Java's — and
     * two tolerances for "close enough" would drift until one engine suggested a name the other would not.
     * The candidates are what a completion list would offer here, because a suggestion the author cannot
     * then use is worse than none.</p>
     */
    @Test
    public void aMisspeltNameIsOfferedTheOneThatIsInScope() {
        String fixture = "function f() {\n    var precision = 1;\n    return precisi|no;\n}\n";
        assertEquals("function f() {\n    var precision = 1;\n    return precision;\n}\n",
                applied(fixture, "Change to 'precision'"));
    }

    @Test
    public void aRenameSuggestionIsPreferred() {
        // A near-miss on an existing name is overwhelmingly why a free name appears in a file being edited.
        CodeAction action = titled("function f() {\n    var total = 1;\n    return tota|1;\n}\n",
                "Change to 'total'");
        Assume.assumeNotNull(action);
        assertTrue("the likeliest repair is not marked preferred", action.preferred());
        assertEquals(CodeActionKind.QUICK_FIX, action.kind());
    }

    @Test
    public void anUndeclaredNameCanBeDeclaredAsALocal() {
        String fixture = "function f() {\n    return coun|ter;\n}\n";
        assertEquals("function f() {\n    var counter;\n    return counter;\n}\n",
                applied(fixture, "Declare 'counter' as a local"));
    }

    /**
     * A global is not a mistake, and must never be offered either repair.
     *
     * <p>{@code Math} resolves to nothing in the scopes and to something at run time, so offering to rename
     * or declare it would be offering to break working code — the one failure mode a fix must not have.</p>
     */
    @Test
    public void aBuiltinGlobalIsNotTreatedAsAFreeName() {
        List<String> titles = titlesAt("function f() {\n    return Mat|h.max(1, 2);\n}\n");
        for (String title : titles) {
            assertFalse("a builtin was offered a repair: " + title,
                    title.startsWith("Declare 'Math'") || title.startsWith("Change to 'Map'"));
        }
    }

    /** A Java package root is not one either. */
    @Test
    public void aJavaPackageRootIsNotTreatedAsAFreeName() {
        List<String> titles = titlesAt("function f() {\n    return new jav|a.util.ArrayList();\n}\n");
        assertFalse(titles.toString(), titles.contains("Declare 'java' as a local"));
    }

    // ── Intentions ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void aVarCanBecomeLet() {
        assertEquals("function f() {\n    let x = 1;\n    x = 2;\n    return x;\n}\n",
                applied("function f() {\n    v|ar x = 1;\n    x = 2;\n    return x;\n}\n",
                        "Change 'var' to 'let'"));
    }

    /** {@code const} only when nothing writes to the name — which the scopes already know. */
    @Test
    public void aVarThatIsNeverReassignedCanBecomeConst() {
        assertEquals("function f() {\n    const x = 1;\n    return x;\n}\n",
                applied("function f() {\n    v|ar x = 1;\n    return x;\n}\n",
                        "Change 'var' to 'const'"));
    }

    @Test
    public void aReassignedVarIsNotOfferedConst() {
        List<String> titles = titlesAt("function f() {\n    v|ar x = 1;\n    x = 2;\n    return x;\n}\n");
        assertTrue(titles.contains("Change 'var' to 'let'"));
        assertFalse("const on a reassigned name would not run", titles.contains("Change 'var' to 'const'"));
    }

    @Test
    public void anUninitialisedVarIsNotOfferedConst() {
        // A const must be initialised, so this can never become one whatever else is true.
        List<String> titles = titlesAt("function f() {\n    v|ar x;\n    x = 2;\n    return x;\n}\n");
        assertFalse(titles.toString(), titles.contains("Change 'var' to 'const'"));
    }

    @Test
    public void looseEqualityCanBeTightened() {
        assertEquals("function f(a, b) { return a === b; }\n",
                applied("function f(a, b) { return a =|= b; }\n", "Change to '==='"));
        assertEquals("function f(a, b) { return a !== b; }\n",
                applied("function f(a, b) { return a !|= b; }\n", "Change to '!=='"));
    }

    /**
     * {@code x == null} is idiomatic and must be left alone.
     *
     * <p>It catches {@code undefined} too, which is usually what was meant, so tightening it changes
     * behaviour. Every style guide that mandates {@code ===} carves out exactly this case.</p>
     */
    @Test
    public void equalityAgainstNullIsLeftAlone() {
        List<String> titles = titlesAt("function f(a) { return a =|= null; }\n");
        assertFalse(titles.toString(), titles.contains("Change to '==='"));
    }

    @Test
    public void alreadyStrictEqualityIsNotOfferedAgain() {
        List<String> titles = titlesAt("function f(a, b) { return a ==|= b; }\n");
        assertFalse("an edit that changes nothing was offered", titles.contains("Change to '==='"));
    }

    @Test
    public void theTwoJavaTypeSpellingsConvertBothWays() {
        assertEquals("var t = Java.type(\"java.util.ArrayList\");\n",
                applied("var t = jav|a.util.ArrayList;\n",
                        "Change to 'Java.type(\"java.util.ArrayList\")'"));
        assertEquals("var t = Packages.java.util.ArrayList;\n",
                applied("var t = Java.ty|pe('java.util.ArrayList');\n",
                        "Change to 'Packages.java.util.ArrayList'"));
    }

    @Test
    public void aConcatenationWithAStringCanBecomeATemplate() {
        Assume.assumeTrue("this band refuses template literals",
                titlesAt("var s = 'a' |+ x + 'b';\n").contains("Change to a template literal"));
        assertEquals("var s = `a${x}b`;\n",
                applied("var s = 'a' |+ x + 'b';\n", "Change to a template literal"));
    }

    @Test
    public void anArithmeticSumIsNotOfferedATemplate() {
        // `a + b` on two numbers is arithmetic; a template would change what the program computes.
        assertFalse(titlesAt("function f(a, b) { return a |+ b; }\n")
                .contains("Change to a template literal"));
    }

    @Test
    public void aStatementCanBeSurroundedWithTryCatch() {
        assertEquals("function f() {\n"
                        + "    try {\n"
                        + "        risky();\n"
                        + "    } catch (e) {\n"
                        + "        console.error(e);\n"
                        + "    }\n"
                        + "}\n",
                applied("function f() {\n    ris|ky();\n}\n", "Surround with try/catch"));
    }

    // ── What is deliberately never offered ──────────────────────────────────────────────────────

    /**
     * A refused keyword gets <b>no fix</b>.
     *
     * <p>{@code class} cannot be rewritten as a function honestly — a prototype translation changes what the
     * code means — and the diagnostic already names the band that accepts it. A repair that silently altered
     * semantics is worse than no repair, which is the one entry in the catalog that is an absence.</p>
     */
    @Test
    public void aRefusedKeywordIsOfferedNoRepair() {
        for (String title : titlesAt("cla|ss Point {}\n")) {
            assertFalse("a fix was offered for a construct the engine refuses: " + title,
                    title.toLowerCase(java.util.Locale.ROOT).contains("class"));
        }
    }

    @Test
    public void aBrokenFileStillOffersWhatItCan() {
        // IDE mode's whole point: a file is broken most of the time somebody is typing in it.
        String fixture = "function f() {\n    var unu|sed = 1;\n    return 2;\n}\nfunction ( {\n";
        assertTrue(titlesAt(fixture).contains("Remove 'unused'"));
    }

    @Test
    public void anEmptyDocumentOffersNothingAndDoesNotThrow() {
        assertEquals(List.of(), JsLanguage.analyzer().analyze("Probe.js", "", 1L, null)
                .codeActionsIn(0, 0, null));
    }

    @Test
    public void everyActionIsStampedWithTheAnalysisVersion() {
        // A document that has moved on must refuse the edit rather than applying it at coordinates that
        // now mean something else.
        for (CodeAction action : actionsAt("function f() {\n    var unu|sed = 1;\n    return 2;\n}\n")) {
            assertEquals(1L, action.version());
            assertTrue(action.isApplicableTo(1L));
            assertFalse(action.isApplicableTo(2L));
        }
    }
}
