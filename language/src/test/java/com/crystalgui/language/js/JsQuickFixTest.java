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

    // -- The four that rebuild a construct --------------------------------------------------------

    /**
     * <b>A function expression becomes an arrow, and a single {@code return} loses its braces.</b>
     *
     * <p>The JavaScript twin of {@code LambdaCorrections}. Asserted on the produced text, because an
     * arrow written at the wrong offsets is still a syntactically valid arrow.</p>
     */
    @Test
    public void aFunctionExpressionConvertsToAnArrow() {
        assertEquals("var f = (a, b) => a + b;\n",
                applied("var f = fun|ction (a, b) { return a + b; };\n",
                        "Convert to an arrow function"));
    }

    /** A body that is not one {@code return} keeps its block — there is nothing to shorten. */
    @Test
    public void anArrowKeepsABlockBody() {
        assertEquals("list.forEach((x) => { console.log(x); });\n",
                applied("list.forEach(fun|ction (x) { console.log(x); });\n",
                        "Convert to an arrow function"));
    }

    /**
     * <b>Not offered on a body that names {@code this}</b> — an arrow inherits it lexically, so the
     * converted function reads a different object under the same name and still runs.
     *
     * <p>This one shipped broken for a round and the cause is worth keeping: the check compared the
     * source span of the {@code this} node, and the {@code KeywordLiteral} in {@code this.x} reports
     * its length as <em>five</em> characters — {@code "this."}, dot included. The comparison quietly
     * never matched, so the conversion was offered on exactly the shape it exists to refuse.
     */
    @Test
    public void anArrowIsRefusedWhenTheBodyUsesThis() {
        assertNull("an arrow was offered for a function that uses `this`",
                titled("var f = fun|ction () { return this.x; };\n", "Convert to an arrow function"));
        assertNull("...or `arguments`", titled(
                "var f = fun|ction () { return arguments.length; };\n",
                "Convert to an arrow function"));
    }

    /**
     * <b>Not offered on a function <em>declaration</em></b>, which is hoisted where an assigned arrow is
     * not — so anything calling it above its own line would stop working.
     */
    @Test
    public void anArrowIsRefusedForADeclaration() {
        assertNull(titled("fun|ction f(a) { return a; }\n", "Convert to an arrow function"));
    }

    /**
     * <b>An index loop becomes {@code for…of}</b>, and the element is named from the sequence.
     *
     * <p>{@code LoopIntentions}' rule ported, with one divergence: the Java version derives the element
     * name from the resolved element <em>type</em>, and there is no type here — so the collection's own
     * name is the evidence, which is usually good evidence because a collection is named for what it
     * holds.</p>
     */
    @Test
    public void anIndexLoopConvertsToForOf() {
        assertEquals("var items = [1, 2];\nfor (var item of items) {\n    console.log(item);\n}\n",
                applied("var items = [1, 2];\nfo|r (var i = 0; i < items.length; i++) {\n"
                        + "    console.log(items[i]);\n}\n", "Convert to 'for\u2026of'"));
    }

    /**
     * <b>Refused when the index is used for anything but a fetch.</b> The {@code of} form has no index
     * to offer, so the conversion would produce code that does not run — and looks like it should.
     */
    @Test
    public void forOfIsRefusedWhenTheIndexIsUsedForItself() {
        assertNull(titled("var xs = [1];\nfo|r (var i = 0; i < xs.length; i++) {\n"
                + "    console.log(i + 1);\n}\n", "Convert to 'for\u2026of'"));
    }

    /**
     * <b>And refused when the loop writes <em>through</em> the index.</b>
     *
     * <p>The one shape here that converts, still runs, and silently stops doing its job: {@code xs[i] = 0}
     * stores into the array, while {@code x = 0} in a {@code for…of} assigns to the loop variable and is
     * discarded. Nothing throws and nothing looks wrong.</p>
     */
    @Test
    public void forOfIsRefusedWhenTheLoopWritesThroughTheIndex() {
        assertNull(titled("var xs = [1];\nfo|r (var i = 0; i < xs.length; i++) {\n"
                + "    xs[i] = 0;\n}\n", "Convert to 'for\u2026of'"));
    }

    /**
     * <b>An {@code if}/{@code else if} chain becomes a {@code switch}</b>, each arm keeping its braces.
     *
     * <p>The braces are not formatting. The arms were separate blocks and the cases are one, so two arms
     * declaring the same name give a syntax error under {@code let} and a silently shared binding under
     * {@code var}. One line per arm buys back the scoping the {@code if} form had.</p>
     */
    @Test
    public void anIfChainConvertsToASwitch() {
        assertEquals("var kind = 'a';\n"
                        + "switch (kind) {\n"
                        + "    case 'a': {\n        one();\n        break;\n    }\n"
                        + "    case 'b': {\n        two();\n        break;\n    }\n"
                        + "    default: {\n        other();\n    }\n"
                        + "}\n",
                applied("var kind = 'a';\ni|f (kind === 'a') {\n    one();\n"
                                + "} else if (kind === 'b') {\n    two();\n} else {\n    other();\n}\n",
                        "Convert to 'switch'"));
    }

    /** One arm is not a chain — a {@code switch} over it is longer and says less. */
    @Test
    public void aSingleIfIsNotASwitch() {
        assertNull(titled("var k = 'a';\ni|f (k === 'a') { one(); }\n", "Convert to 'switch'"));
    }

    /**
     * <b>Refused when an arm contains a {@code break}.</b>
     *
     * <p>Inside an {@code if} in a loop it leaves the loop; the identical statement inside a
     * {@code switch} leaves the switch. Nothing fails to parse — the loop simply stops stopping.
     */
    @Test
    public void aSwitchIsRefusedWhenAnArmBreaks() {
        assertNull(titled("while (true) {\n"
                        + "    i|f (k === 'a') { break; } else if (k === 'b') { two(); }\n}\n",
                "Convert to 'switch'"));
    }

    /**
     * <b>Extract to local</b>, with the name read off what is being extracted.
     *
     * <p>{@code Names}' deriving half is what this was deferred on — it took a JDT binding, so there was
     * nothing for JavaScript to reuse. It is {@code DerivedNames} in {@code core} now, split where the
     * rule stops needing a type, and {@code getDisplayName()} names itself {@code displayName} in both
     * languages from the one implementation.</p>
     */
    @Test
    public void anExpressionExtractsToALocal() {
        assertEquals("var displayName = player.getDisplayName();\nconsole.log(displayName);\n",
                applied("console.log(player.getDisplayNam|e());\n",
                        "Introduce variable 'displayName'"));
    }

    /**
     * <b>The caret on a callee extracts the CALL, never the callee.</b>
     *
     * <p>Hoisting {@code player.getDisplayName} on its own gives a detached function, so the call that
     * follows runs with the wrong receiver — it parses, and usually returns something.
     */
    @Test
    public void extractingFromACalleeTakesTheWholeCall() {
        String extracted = applied("console.log(player.getDisplayNam|e());\n",
                "Introduce variable 'displayName'");
        assertTrue("the receiver was left behind: " + extracted,
                extracted.contains("player.getDisplayName()"));
    }

    /**
     * <b>Never out of a loop header.</b> Hoisting {@code xs.length} above {@code while (i < xs.length)}
     * evaluates it once and the loop stops terminating — a hang, from an edit accepted without reading.
     *
     * <p>It was offered, too, and the cause was one inheritance: Rhino's loops extend {@code Scope}
     * (they must — {@code for (let i …)} declares into one), so the structural "is my parent a statement
     * container" walk stopped on the loop's own condition and reported it as a statement.</p>
     */
    @Test
    public void extractIsRefusedInALoopHeader() {
        for (String title : titlesAt("while (i < xs.leng|th) { i++; }\n")) {
            assertFalse("an expression was extracted out of a loop header: " + title,
                    title.startsWith("Introduce variable"));
        }
    }

    /** A whole expression statement is already as extracted as it gets. */
    @Test
    public void extractIsRefusedOnABareStatement() {
        for (String title : titlesAt("fo|o();\n")) {
            assertFalse(title, title.startsWith("Introduce variable"));
        }
    }

    /**
     * The same inheritance, from the other side: "Surround with try/catch" on a loop condition used to
     * offer to wrap <em>the condition</em>, producing a {@code while} header containing a {@code try}.
     */
    @Test
    public void wrappingInALoopHeaderWrapsTheLoop() {
        String wrapped = applied("while (i < xs.leng|th) { i++; }\n", "Surround with try/catch");
        assertTrue("the condition was wrapped instead of the loop: " + wrapped,
                wrapped.contains("try {\n    while (i < xs.length)"));
    }

    // \u2500\u2500 Unused imports \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * <b>"Remove unused import" takes the whole line.</b>
     *
     * <p>The offer Java has always made, on the same finding. Asserted on the TEXT the edit produces \u2014
     * the only assertion an edit at the wrong offsets cannot pass, and the one that catches the line
     * surviving as a blank, which is how an automated edit teaches people not to trust it.</p>
     */
    @Test
    public void anUnusedImportCanBeRemoved() {
        assertEquals("import util.Greeter;\nGreeter.hi();\n",
                applied("import util.Greeter;\nimport util.Un|used;\nGreeter.hi();\n",
                        "Remove unused import"));
    }

    /**
     * <b>...offered from anywhere on the statement, the keyword included.</b>
     *
     * <p>The fade deliberately covers the NAME only, because that is the span JDT marks for Java and the
     * two languages sit in one editor. A fix is reached by putting the caret on the line though, so a
     * reader who lands on the word {@code import} must not be told there is nothing to do here.</p>
     */
    @Test
    public void theFixIsOfferedAcrossTheWholeStatement() {
        String tail = ";\nvar x = 1;\nconsole.log(x);\n";
        for (String fixture : new String[] {
                "im|port util.Unused" + tail,
                "import |util.Unused" + tail,
                "import util.Unu|sed" + tail,
                "import util.Unused|" + tail}) {
            assertTrue("not offered in [" + fixture.substring(0, fixture.indexOf(';') + 1) + "]",
                    titlesAt(fixture).contains("Remove unused import"));
        }
    }

    /** <b>An import that IS used offers nothing.</b> The guard that stops this deleting live code. */
    @Test
    public void aUsedImportIsNotOffered() {
        assertFalse(titlesAt("import util.Gre|eter;\nGreeter.hi();\n")
                .contains("Remove unused import"));
    }
}
