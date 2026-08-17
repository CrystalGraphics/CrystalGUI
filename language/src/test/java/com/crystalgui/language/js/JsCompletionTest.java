package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.js.host.JsHost;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;

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

/**
 * <b>M10.7 — what could go here.</b> The twin of {@code JavaMemberCompletionTest}, over the same seam.
 *
 * <p>The milestone's own criteria: a {@code .} after a Java object lists the Java engine's members; after
 * a run, {@code settings.} lists what the object actually has; no keyword the engine refuses is ever
 * offered; and {@code inheritedFromObject} is set for the things every object inherits.</p>
 */
public class JsCompletionTest {

    /** The caret marker in a fixture — removed before the buffer is built. */
    private static final String CARET = "|";

    private TextBuffer buffer;
    private JsLanguageServices services;

    @BeforeClass
    public static void openTheEngines() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    @After
    public void closeDocument() {
        if (services != null) services.close();
    }

    /**
     * Completes at the {@code |} in {@code fixture}.
     *
     * <p>Through the real {@link JsLanguageServices} rather than the provider directly, because the point
     * is that a document has one — a provider that works and is not wired to anything looks identical to
     * one that does not exist.</p>
     */
    private CompletionList completeAt(String fixture) {
        int caret = fixture.indexOf(CARET);
        assertTrue("the fixture has no caret marker", caret >= 0);
        String text = fixture.substring(0, caret) + fixture.substring(caret + 1);
        buffer = new TextBuffer(text);
        if (services != null) services.close();
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null,
                JsLanguage.typeIndexForTesting());

        String prefix = prefixBefore(text, caret);
        AtomicReference<CompletionList> answered = new AtomicReference<>();
        services.completion().complete(CompletionProvider.Request.explicit(caret, prefix),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));
        assertNotNull("the provider never answered", answered.get());
        return answered.get();
    }

    /** The partial word already typed, which is what the session filters on. */
    private static String prefixBefore(String text, int caret) {
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        return text.substring(start, caret);
    }

    /**
     * The names, as the session filters on them — {@code filterKey()} and never {@code label()}.
     *
     * <p>A method's label is decorated with its parameters ({@code add(Object)}, {@code f()}), because that
     * is what a popup row shows; the name is what is typed against it. Asserting on the label is how a
     * perfectly correct list looks like a missing one.</p>
     */
    private static List<String> names(CompletionList list) {
        List<String> names = new ArrayList<>(list.size());
        for (CompletionItem item : list.items()) names.add(item.filterKey());
        return names;
    }

    private static CompletionItem itemNamed(CompletionList list, String name) {
        for (CompletionItem item : list.items()) {
            if (name.equals(item.filterKey())) return item;
        }
        return null;
    }

    // ── Members after a dot ─────────────────────────────────────────────────────────────────────

    /** <b>The headline claim.</b> A Java receiver's members are the Java engine's own answer. */
    @Test
    public void aJavaReceiverListsTheJavaEnginesMembers() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        CompletionList list = completeAt("var list = new java.util.ArrayList();\nlist.|\n");
        List<String> offered = names(list);
        assertFalse("nothing was offered after a Java receiver", offered.isEmpty());
        for (String expected : new String[]{"add", "get", "size", "isEmpty"}) {
            assertTrue(expected + " is missing from " + offered.size() + " rows", offered.contains(expected));
        }
    }

    /** And a method row inserts its brackets with the caret between them. */
    @Test
    public void amethodInsertsItsBracketsAsOneSnippet() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        CompletionItem add = itemNamed(completeAt("var l = new java.util.ArrayList();\nl.|\n"), "add");
        assertNotNull(add);
        assertEquals("add(" + CompletionItem.CARET + ")", add.textToInsert());
        assertEquals(CompletionItem.InsertTextFormat.SNIPPET, add.insertTextFormat());
    }

    /** A partial member name narrows to the same list — the session filters, the provider does not. */
    @Test
    public void aPartialMemberNameStillOffersTheWholeSet() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        assertTrue(names(completeAt("var l = new java.util.ArrayList();\nl.ad|\n")).contains("add"));
    }

    /**
     * {@code Java.type("…")} is the class object, so its statics are offered and its instance members
     * are not — the same split resolution makes, arriving where the user can see it.
     */
    @Test
    public void theClassObjectOffersItsStatics() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        List<String> offered = names(completeAt(
                "var I = Java.type('java.lang.Integer');\nI.|\n"));
        assertTrue("parseInt is missing from Integer's statics", offered.contains("parseInt"));
        assertFalse("an instance method was offered on the class object", offered.contains("intValue"));
    }

    /**
     * <b>A CALL is a receiver too</b> — reported from the harness as an empty popup.
     *
     * <p>{@code Files.emptyList().} is the shape half of all Java interop code is written in, and it put a
     * {@code )} immediately before the dot: completion resolved at a character no identifier covers, got
     * nothing, and opened an empty list. Nothing failed — the popup appeared, which is what made it read
     * as "completion is flaky in places" rather than as one missing case.</p>
     */
    @Test
    public void aCallIsAReceiver() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        List<String> offered = names(completeAt(
                "var C = Java.type('java.util.Collections');\nvar n = C.emptyList().|\n"));
        assertFalse("a call receiver offered nothing at all", offered.isEmpty());
        assertTrue("the return type's members are missing from " + offered.size() + " rows: " + offered,
                offered.contains("size") && offered.contains("isEmpty"));
    }

    /** And so is a chain of them, to any depth — a call's type is its callee's, recursively. */
    @Test
    public void aChainOfCallsResolvesThroughEveryLink() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        List<String> offered = names(completeAt(
                "var s = new java.lang.StringBuilder();\nvar t = s.append('a').append('b').|\n"));
        assertTrue("the second link in the chain did not resolve: " + offered,
                offered.contains("append") && offered.contains("toString"));
    }

    /** A dot after a decimal point is not a receiver. */
    @Test
    public void aDecimalPointDoesNotOpenAMemberList() {
        // `1.` is a number being typed. Treating it as a receiver puts a member popup over a literal.
        List<String> offered = names(completeAt("var n = 1.|\n"));
        assertTrue("a member list opened on a number literal: " + offered,
                offered.contains("var") || offered.contains("function"));
    }

    // ── The live object ─────────────────────────────────────────────────────────────────────────

    private void run(String source) throws Throwable {
        JsHost host = new JsHost(JsLanguage.executor());
        try {
            host.run(host.compileScript("Probe.js", source, Map.of()), Map.of());
        } finally {
            host.close();
        }
    }

    /**
     * <b>Post-run completion on a live object</b> — the criterion no static analysis can meet.
     *
     * <p>{@code settings} is assigned from a call nothing can follow, so before a run there is nothing to
     * offer. After one, the editor knows the object's actual properties.</p>
     */
    @Test
    public void afterARunAnObjectOffersWhatItActuallyHas() throws Throwable {
        run("var settings = { retries: 5, timeout: 2500, label: 'scratch' };\n");
        buffer = new TextBuffer("var settings = make();\nsettings.\n");
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null, null);
        services.setLiveScope(JsLanguage.executor().snapshotScope());

        AtomicReference<CompletionList> answered = new AtomicReference<>();
        int caret = buffer.toString().indexOf("settings.") + "settings.".length();
        services.completion().complete(CompletionProvider.Request.character(caret, "", "."),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));

        List<String> offered = names(answered.get());
        assertTrue("the live object's own properties are missing: " + offered,
                offered.containsAll(List.of("retries", "timeout", "label")));
    }

    /**
     * And what it inherits is marked as inherited — the fourth criterion.
     *
     * <p>The ids come from the run's own {@code Object.prototype} rather than a list written here, so the
     * mark is true of the engine that will actually run the script.</p>
     */
    @Test
    public void whatEveryObjectInheritsIsMarkedInherited() throws Throwable {
        run("var settings = { retries: 5 };\n");
        buffer = new TextBuffer("var settings = make();\nsettings.\n");
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null, null);
        services.setLiveScope(JsLanguage.executor().snapshotScope());

        AtomicReference<CompletionList> answered = new AtomicReference<>();
        int caret = buffer.toString().indexOf("settings.") + "settings.".length();
        services.completion().complete(CompletionProvider.Request.character(caret, "", "."),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));

        CompletionItem toString = itemNamed(answered.get(), "toString");
        assertNotNull("Object.prototype's members were not offered", toString);
        assertTrue("toString is not marked inherited", toString.inheritedFromObject());
        CompletionItem own = itemNamed(answered.get(), "retries");
        assertNotNull(own);
        assertFalse("the object's own property was marked inherited", own.inheritedFromObject());
    }

    /**
     * A receiver nothing can type still opens a list, and says the answer is partial.
     *
     * <p>The honest behaviour for a dynamic language, and the JavaScript equivalent of Java's
     * probe-and-re-parse: the editor cannot know what {@code make()} returned, so it offers what exists
     * and reports the list incomplete rather than staying shut.</p>
     */
    @Test
    public void anUntypableReceiverOffersTheLiveNamesAndSaysItIsPartial() throws Throwable {
        run("var fromRun = 1;\n");
        buffer = new TextBuffer("var thing = make();\nthing.\n");
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null, null);
        services.setLiveScope(JsLanguage.executor().snapshotScope());

        AtomicReference<CompletionList> answered = new AtomicReference<>();
        int caret = buffer.toString().indexOf("thing.") + "thing.".length();
        services.completion().complete(CompletionProvider.Request.character(caret, "", "."),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));

        assertTrue("an unknowable receiver must not report a complete answer",
                answered.get().incomplete());
        assertTrue(names(answered.get()).contains("fromRun"));
    }

    // ── Open code ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void openCodeOffersWhatIsInScopeNearestFirst() {
        CompletionList list = completeAt(
                "var outer = 1;\nfunction f() {\n  var inner = 2;\n  |\n}\n");
        List<String> offered = names(list);
        assertTrue(offered.contains("inner"));
        assertTrue(offered.contains("outer"));
        assertTrue("the function itself is nameable: " + offered, offered.contains("f"));
        // AND ITS LABEL CARRIES ITS BRACKETS, which is what the row shows and why the assertions above
        // read filterKey rather than label.
        assertEquals("f()", itemNamed(list, "f").label());
        assertTrue("a local must be offered before a file-level name: " + offered,
                offered.indexOf("inner") < offered.indexOf("outer"));
    }

    @Test
    public void openCodeOffersTheLanguagesOwnGlobals() {
        List<String> offered = names(completeAt("var x = |\n"));
        for (String global : new String[]{"Math", "JSON", "console", "parseInt", "Java"}) {
            assertTrue(global + " is not offered", offered.contains(global));
        }
    }

    /**
     * <b>No keyword the engine refuses is ever offered</b> — the third criterion.
     *
     * <p>Measured against the band's own parser, not written down. {@code class}, {@code import} and
     * {@code async} are refused by both Rhinos we ship, so a row offering one would be a promise that
     * accepting it produces something that runs — and it does not.</p>
     */
    @Test
    public void aKeywordTheEngineRefusesIsNotOffered() {
        List<String> offered = names(completeAt("|\n"));
        assertTrue("the ordinary keywords are missing", offered.contains("function"));
        assertTrue(offered.contains("return"));
        assertTrue(offered.contains("var"));
        for (String refused : new String[]{"class", "import", "export", "async", "await"}) {
            assertFalse(refused + " is refused by this engine and must not be offered",
                    offered.contains(refused));
        }
        // AND THE ONES IT DOES TAKE ARE THERE. Without this the test passes against an empty keyword
        // list, which would be the same as the feature being absent.
        assertTrue("let/const are accepted by both bands and should be offered",
                offered.contains("let") && offered.contains("const"));
    }

    @Test
    public void aKeywordIsSortedBelowADeclaredNameOfTheSamePrefix() {
        CompletionList list = completeAt("var format = 1;\nfo|\n");
        CompletionItem declared = itemNamed(list, "format");
        CompletionItem keyword = itemNamed(list, "for");
        assertNotNull(declared);
        assertNotNull(keyword);
        assertTrue("a keyword must not outrank a variable in scope",
                declared.sortKey().compareTo(keyword.sortKey()) < 0);
    }

    @Test
    public void theJavaPackageRootsAreOffered() {
        List<String> offered = names(completeAt("var x = |\n"));
        assertTrue(offered.contains("java"));
        assertTrue(offered.contains("Packages"));
    }

    // ── Java.type("…") ──────────────────────────────────────────────────────────────────────────

    /**
     * Inside the string, the classpath's class names — and nowhere else.
     *
     * <p>A Java class name cannot be written bare in JavaScript, so offering the index in open code would
     * fill the popup with rows that are all syntax errors where they would land. Inside the literal every
     * one of them is exactly right and needs no edit beyond itself, which is the one place this interop
     * is simpler than Java's — there is no import to bring.</p>
     */
    @Test
    public void insideAJavaTypeStringTheClassNamesAreOffered() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        CompletionList list = completeAt("var t = Java.type('ArrayLis|');\n");
        List<String> offered = names(list);
        assertFalse("no class names were offered inside Java.type: " + offered, offered.isEmpty());
        assertTrue("ArrayList is not among " + offered, offered.contains("ArrayList"));
        assertTrue("an index-backed list is a sample and must be reported incomplete",
                list.incomplete());

        CompletionItem item = itemNamed(list, "ArrayList");
        assertNotNull(item);
        assertEquals("the whole qualified name belongs inside the string",
                "java.util.ArrayList", item.textToInsert());
        assertEquals("java.util", item.detail());
        assertEquals("nothing else is edited — there is no import to add",
                0, item.additionalTextEdits().size());
        assertEquals(SymbolKind.CLASS, item.kind());
    }

    @Test
    public void classNamesAreNotOfferedInOpenCode() {
        Assume.assumeTrue(JavaLanguage.isAvailable());
        // `ArrayLis` outside a Java.type string must not offer the index: a bare class name is not
        // something JavaScript can name, so every such row would be a syntax error where it landed.
        assertFalse(names(completeAt("var t = ArrayLis|;\n")).contains("ArrayList"));
    }

    // ── Degradation ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void aFileWithASyntaxErrorStillCompletes() {
        // The reason IDE mode exists: a file is broken most of the time somebody is typing in it.
        List<String> offered = names(completeAt("var good = 1;\nfunction ( {\n|\n"));
        assertFalse("a broken file offered nothing at all", offered.isEmpty());
        assertTrue(offered.contains("good"));
    }

    @Test
    public void completingOffTheEndIsHarmless() {
        buffer = new TextBuffer("var a = 1;\n");
        services = new JsLanguageServices(buffer, JsLanguage.analyzer(), null, "Probe.js", null, null);
        AtomicReference<CompletionList> answered = new AtomicReference<>();
        services.completion().complete(CompletionProvider.Request.explicit(9999, ""),
                versioned -> answered.set(versioned.orElse(CompletionList.EMPTY)));
        assertNotNull(answered.get());
    }
}
