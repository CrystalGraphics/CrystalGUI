package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.Versioned;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What {@code System.} actually offers — the list a person sees, asserted against a real compiler.
 *
 * <p>Written because the harness capture showed <b>no fields at all</b>: IntelliJ opens that list with
 * {@code out}, {@code err} and {@code in}, and ours had only methods. Static methods were present, so the
 * engine and the member walk were plainly working — which is the kind of partial failure a test asserting
 * "the list is non-empty" passes straight through.</p>
 *
 * <p>{@code System} rather than a fixture class of our own, deliberately: it is a <b>binary</b> type off the
 * classpath with no source attached, which is the case that behaves differently from a type in the file
 * being edited and the one every real completion is against.</p>
 */
public class JavaMemberCompletionTest {

    private JavaEngine engine;

    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
        EngineSource source = EngineSource.ofPathList(paths);
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
    }

    @After
    public void closeEngine() throws IOException {
        if (engine != null) engine.close();
    }

    /** The source a person is looking at when the popup opens: a dot, and nothing after it yet. */
    private static final String AFTER_THE_DOT = ""
            + "class Demo {\n"
            + "    void run() {\n"
            + "        System.\n"
            + "    }\n"
            + "}\n";

    private List<CompletionItem> completeAfterTheDot(String source) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("System.") + "System.".length();
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            return answered.get().items();
        } finally {
            services.close();
        }
    }

    private static List<String> labelsOf(List<CompletionItem> items) {
        List<String> labels = new ArrayList<>();
        for (CompletionItem item : items) labels.add(item.label());
        return labels;
    }

    private static CompletionItem named(List<CompletionItem> items, String label) {
        for (CompletionItem item : items) {
            if (item.label().equals(label)) return item;
        }
        return null;
    }

    // ── The defect ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void systemOffersItsStaticFieldsAndNotOnlyItsMethods() {
        List<CompletionItem> items = completeAfterTheDot(AFTER_THE_DOT);
        List<String> labels = labelsOf(items);

        assertTrue("the list should not be empty at all: " + labels, !items.isEmpty());
        // Sanity: the half that already worked. If this fails the test is wrong, not the code.
        assertTrue("static methods were already present and must stay: " + labels,
                labels.contains("currentTimeMillis()"));

        assertTrue("System.out is missing -- IntelliJ opens this very list with it: " + labels,
                labels.contains("out"));
        assertTrue("System.err is missing: " + labels, labels.contains("err"));
        assertTrue("System.in is missing: " + labels, labels.contains("in"));
    }

    /**
     * <b>{@code System.out} is a CONSTANT, and this test used to assert it was a field.</b>
     *
     * <p>It is {@code public static final PrintStream out} — static and final, which is the rule for a
     * constant and the rule this engine's <em>semantic-token</em> pass has always applied to it. So the
     * editor drew {@code System.out} in the constant colour while the completion popup listed it with a
     * field icon: <b>one declaration, two answers, from one engine</b>, depending only on which question
     * was asked.</p>
     *
     * <p>The disagreement surfaced through JavaScript, which is the useful part. Once JS started
     * colouring Java members through {@code membersOf}, a {@code static final} field drew as a plain
     * property in a {@code .js} file and as a constant in a {@code .java} one — the same member, from
     * the same declaration, three lines apart in two tabs. Chasing that found the two rules.</p>
     *
     * <p>The name below keeps saying "so the icon and the ranking are right", because that is still what
     * it is for: a kind that is <em>wrong</em> costs an icon and a rank. It just has to be the same wrong
     * or right in both places.</p>
     */
    @Test
    public void aStaticFinalFieldIsReportedAsAConstantSoTheIconAndTheRankingAreRight() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull("System.out is missing", out);
        assertEquals("System.out is `public static final` -- the same rule the semantic pass applies",
                SymbolKind.CONSTANT, out.kind());
        assertEquals("the detail column shows the type", "PrintStream", out.detail());
    }

    /**
     * And an <b>instance</b> field is still a field — the half the change above must not take with it.
     *
     * <p>Without this, "everything is a constant now" would pass the test above perfectly.</p>
     */
    @Test
    public void anInstanceFieldIsStillAField() {
        // ITS OWN CALL rather than `completeAfterTheDot`, which hardcodes `System.` -- the shape
        // `anInstanceReceiverOffersItsInstanceMembers` below already uses for the same reason.
        String source = ""
                + "class Demo {\n"
                + "    static class Box { public int count; }\n"
                + "    void run(Box b) {\n"
                + "        b.\n"
                + "    }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("b.\n") + 2;
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            CompletionItem count = named(answered.get().items(), "count");
            assertNotNull("the instance field is missing", count);
            assertEquals(SymbolKind.FIELD, count.kind());
        } finally {
            services.close();
        }
    }

    /**
     * The declaring type's own members are reachable too.
     *
     * <p>A negative control for the fix: if {@code System} started answering because the walk began
     * returning <em>everything</em>, a type whose fields are instance fields rather than statics would
     * expose it.</p>
     */
    @Test
    public void anInstanceReceiverOffersItsInstanceMembers() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        String s = \"x\";\n"
                + "        s.\n"
                + "    }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("s.\n") + 2;
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            List<String> labels = labelsOf(answered.get().items());
            assertTrue("String's instance methods should be offered: " + labels,
                    labels.contains("substring(int)"));
        } finally {
            services.close();
        }
    }

    // ── Phase 1: signatures and overloads ───────────────────────────────────────────────────────

    @Test
    public void aMethodShowsItsSignatureAndStillFiltersOnItsBareName() {
        CompletionItem exit = named(completeAfterTheDot(AFTER_THE_DOT), "exit(int)");
        assertNotNull("the label must carry the parameter list", exit);
        assertEquals("typing the bare name must still match it", "exit", exit.filterKey());
        // The insertion now carries brackets and a caret marker -- see
        // acceptingAMethodWritesItsBracketsAroundTheCaret. What this test is about is that the NAME is
        // what leads it, so the four fields still describe one member rather than three.
        assertTrue("the insertion must start with the bare name: " + exit.textToInsert(),
                exit.textToInsert().startsWith("exit"));
    }

    /**
     * Two overloads must be two DISTINGUISHABLE rows.
     *
     * <p>The whole point of the label/filter split. Before this they were two rows both reading
     * {@code getProperty}, with no way to tell which one Enter would take.</p>
     */
    @Test
    public void overloadsAreDistinguishable() {
        List<String> labels = labelsOf(completeAfterTheDot(AFTER_THE_DOT));
        assertTrue("one-argument getProperty is missing: " + labels, labels.contains("getProperty(String)"));
        assertTrue("two-argument getProperty is missing: " + labels,
                labels.contains("getProperty(String, String)"));
    }

    @Test
    public void aStaticMemberIsReportedAsStaticSoTheIconCanSaySo() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull(out);
        assertTrue("System.out is static and the icon's second axis reads this",
                out.is(com.crystalgui.text.lang.SymbolModifier.STATIC));
    }

    @Test
    public void aFieldCarriesNoParameterListAtAll() {
        CompletionItem out = named(completeAfterTheDot(AFTER_THE_DOT), "out");
        assertNotNull(out);
        assertEquals("a field labelled `out()` would be a lie about what it is", "out", out.label());
    }

    // ── Bug fixes ───────────────────────────────────────────────────────────────────────────────

    /** Completion at an arbitrary caret, for the cases that are not "straight after System.". */
    private List<CompletionItem> completeAt(String source, int caret, String prefix, boolean afterDot) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            CompletionProvider.Request request = afterDot
                    ? CompletionProvider.Request.character(caret, prefix, ".")
                    : CompletionProvider.Request.explicit(caret, prefix);
            services.completion().complete(request,
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            return answered.get().items();
        } finally {
            services.close();
        }
    }

    /**
     * Every overload is its own row.
     *
     * <p>The dedup key was {@code name + "/" + parameterCount}, which collapsed all ten one-argument
     * {@code println} overloads into one: the popup offered {@code println()} and {@code println(boolean)}
     * and nothing else. The key had to be the erased <em>signature</em> — which is what "the same method"
     * means, and what makes the dedup do the job it was added for: an override appearing once, not twice.</p>
     */
    @Test
    public void everyOverloadIsItsOwnRow() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        System.out.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("System.out.") + "System.out.".length();
        List<String> printlns = new ArrayList<>();
        for (String label : labelsOf(completeAt(source, caret, "", true))) {
            if (label.startsWith("println")) printlns.add(label);
        }

        assertTrue("PrintStream declares ten printlns; got " + printlns, printlns.size() >= 9);
        assertTrue(printlns.toString(), printlns.contains("println(int)"));
        assertTrue(printlns.toString(), printlns.contains("println(char)"));
        assertTrue(printlns.toString(), printlns.contains("println(String)"));
        assertTrue(printlns.toString(), printlns.contains("println()"));
    }

    /**
     * Accepting a method writes its brackets, with the caret where the argument goes.
     *
     * <p>{@code $0} is LSP's marker and the only part of the snippet format implemented — a caret position,
     * not linked editing.</p>
     */
    @Test
    public void acceptingAMethodWritesItsBracketsAroundTheCaret() {
        List<CompletionItem> items = completeAfterTheDot(AFTER_THE_DOT);

        CompletionItem exit = named(items, "exit(int)");
        assertNotNull(exit);
        assertEquals("exit(" + CompletionItem.CARET + ")", exit.textToInsert());

        CompletionItem gc = named(items, "gc()");
        assertNotNull(gc);
        assertEquals("a no-argument method leaves the caret AFTER the brackets",
                "gc()" + CompletionItem.CARET, gc.textToInsert());

        CompletionItem out = named(items, "out");
        assertNotNull(out);
        assertEquals("a field is not something you call", "out", out.textToInsert());
    }

    /**
     * The JDK's own types are offered, which they were not.
     *
     * <p>Typing {@code System} listed {@code SystemClock} and friends from the classpath and never
     * {@code java.lang.System}: since Java 9 the platform lives in the jrt image rather than on the
     * classpath the index scanned.</p>
     */
    @Test
    public void theTypeIndexKnowsThePlatformTypes() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        System\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        System") + "        System".length();
        List<String> labels = labelsOf(completeAt(source, caret, "System", false));

        assertTrue("java.lang.System is missing from the index: " + labels, labels.contains("System"));
    }

    /**
     * The index must match the way the RANKER matches, or its candidates never arrive.
     *
     * <p>Typing {@code CgRenderer} offered one row. Nothing on the machine <em>starts</em> with that, and
     * the index filtered with {@code startsWith} while {@code SearchMatcher} — which ranks whatever the
     * index hands over — matches scattered characters. So {@code CgBatchRenderer}, {@code CgQuadRenderer}
     * and {@code CgTextRenderer} were rejected a step before anything could rank them, and the single row
     * that did show had survived from an earlier, shorter query's batch.</p>
     *
     * <p>Asserted through the provider rather than on {@code TypeIndex} directly, because the defect was
     * precisely that two layers disagreed: a test of either one alone passes.</p>
     */
    @Test
    public void theIndexOffersScatteredMatchesAndNotOnlyPrefixes() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        CgRenderer\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        CgRenderer") + "        CgRenderer".length();
        List<String> labels = labelsOf(completeAt(source, caret, "CgRenderer", false));

        // Whatever else is on the classpath, a name of the shape Cg<something>Renderer has to be reachable.
        boolean anyScatteredRenderer = false;
        for (String label : labels) {
            if (label.startsWith("Cg") && label.endsWith("Renderer") && !label.equals("CgRenderer")) {
                anyScatteredRenderer = true;
                break;
            }
        }
        assertTrue("no Cg*Renderer reached the list, so the index is still pre-filtering on startsWith: "
                + labels, anyScatteredRenderer);
    }

    /** A camel-hump query is the everyday form of the same thing. */
    @Test
    public void aCamelHumpQueryReachesTheTypeItNames() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        AbsMeth\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        AbsMeth") + "        AbsMeth".length();
        List<String> labels = labelsOf(completeAt(source, caret, "AbsMeth", false));

        assertTrue("AbstractMethodError is the JDK's own and should be reachable: " + labels,
                labels.contains("AbstractMethodError"));
    }

    /**
     * A type name offers only what you can reach through one.
     *
     * <p>An instance method reached through a type name does not compile, so offering it is offering a
     * mistake — worse than offering nothing, because the list looks authoritative and the error arrives
     * after acceptance. The same rule {@code membersOf} already applies to accessibility.</p>
     */
    @Test
    public void staticAccessOffersOnlyStaticMembers() {
        List<CompletionItem> items = completeAfterTheDot(AFTER_THE_DOT);
        assertFalse("the list should not be empty", items.isEmpty());

        for (CompletionItem item : items) {
            assertTrue("instance member offered through a type name: " + item.label(),
                    item.is(SymbolModifier.STATIC));
        }
        // ...and the statics really are there, so this cannot pass by offering nothing at all.
        assertTrue(labelsOf(items).contains("currentTimeMillis()"));
        assertTrue(labelsOf(items).contains("out"));
    }

    /** An INSTANCE receiver is unaffected — the negative control for the filter above. */
    @Test
    public void anInstanceReceiverKeepsItsInstanceMembers() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        String s = \"x\";\n"
                + "        s.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("s.\n") + 2;
        List<String> labels = labelsOf(completeAt(source, caret, "", true));

        assertTrue("substring is an instance method and must survive: " + labels,
                labels.contains("substring(int)"));
    }

    /**
     * A bare trailing dot still offers members.
     *
     * <p>{@code ctx.} on its own is not a parseable expression: recovery has nothing to hang the member
     * access on, so there is no node at that offset and no binding. It is <b>not</b> a timing problem — the
     * same text parses the same way however long you wait — which is why typing one more character appeared
     * to fix it and waiting never did.</p>
     *
     * <p>The answer is a probe parse with a synthetic name at the caret, which is what IntelliJ does. This
     * test is the whole reason that path exists.</p>
     */
    @Test
    public void aBareTrailingDotStillOffersMembers() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        System.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("System.") + "System.".length();
        List<String> labels = labelsOf(completeAt(source, caret, "", true));

        assertTrue("a trailing dot offered nothing: " + labels, labels.contains("out"));
        assertTrue(labels.toString(), labels.contains("currentTimeMillis()"));
    }

    // ── What the icons are read from ────────────────────────────────────────────────────────────

    /** Everything the index offers for {@code prefix}, as name -> kind. */
    private java.util.Map<String, SymbolKind> kindsOffered(String prefix) {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        " + prefix + "\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        " + prefix) + ("        " + prefix).length();
        java.util.Map<String, SymbolKind> kinds = new java.util.HashMap<>();
        for (CompletionItem item : completeAt(source, caret, prefix, false)) {
            kinds.put(item.label(), item.kind());
        }
        return kinds;
    }

    /**
     * A type's kind comes from its ACCESS FLAGS, not from the path it lives at.
     *
     * <p>Every index row used to draw as a class, because a class file's path spells its name and nothing
     * else. Interface, enum, annotation and record are all one {@code u2} in the header — and the order the
     * flags are tested in matters, since an annotation is also an interface and an enum is also a class.</p>
     */
    @Test
    public void aTypesKindIsReadFromItsClassFile() {
        java.util.Map<String, SymbolKind> kinds = kindsOffered("Runnable");
        assertEquals("Runnable is an interface", SymbolKind.INTERFACE, kinds.get("Runnable"));

        assertEquals("RetentionPolicy is an enum", SymbolKind.ENUM,
                kindsOffered("RetentionPolic").get("RetentionPolicy"));
        assertEquals("Override is an annotation, and annotations are also interfaces",
                SymbolKind.ANNOTATION, kindsOffered("Overrid").get("Override"));
    }

    /** A throwable gets its own drawing — the fact a reader most wants from a list of similar names. */
    @Test
    public void aThrowableIsReportedAsAnException() {
        assertEquals(SymbolKind.EXCEPTION, kindsOffered("IllegalStateExceptio").get("IllegalStateException"));
        assertEquals("several hops from Throwable and still one", SymbolKind.EXCEPTION,
                kindsOffered("FileNotFoundExceptio").get("FileNotFoundException"));
        assertEquals("an Error is a Throwable too", SymbolKind.EXCEPTION,
                kindsOffered("StackOverflowErro").get("StackOverflowError"));
    }

    /** The control: an ordinary class is still a class, so the walk above cannot be answering yes always. */
    @Test
    public void anOrdinaryClassIsStillAClass() {
        assertEquals(SymbolKind.CLASS, kindsOffered("StringBuilde").get("StringBuilder"));
    }

    /** Abstractness rides as a MODIFIER, so kind and modifier stay orthogonal and the icon can compose. */
    @Test
    public void anAbstractClassSaysSoWithoutBecomingItsOwnKind() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        AbstractLis\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        AbstractLis") + "        AbstractLis".length();
        CompletionItem found = named(completeAt(source, caret, "AbstractLis", false), "AbstractList");
        assertNotNull("AbstractList should be offered", found);
        assertEquals(SymbolKind.CLASS, found.kind());
        assertTrue("and it is abstract", found.is(SymbolModifier.ABSTRACT));
    }

    /**
     * An abstract METHOD carries the modifier its icon composes from.
     *
     * <p>The type half of this was tested when the flags landed; the method half was assumed, and the two
     * travel by completely different routes — a type's abstractness is read from access flags in
     * {@code TypeIndex}, while a method's comes from ECJ's own binding through {@code modifiersOf}.
     * Assuming one from the other is how half a feature ships.</p>
     */
    @Test
    public void anAbstractMethodCarriesTheAbstractModifier() {
        String source = ""
                + "class Demo {\n"
                + "    void run(java.util.AbstractList<String> list) {\n"
                + "        list.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        list.") + "        list.".length();
        List<CompletionItem> items = completeAt(source, caret, "", true);

        CompletionItem get = named(items, "get(int)");
        assertNotNull("AbstractList.get should be offered: " + labelsOf(items), get);
        assertTrue("get(int) is abstract on AbstractList", get.is(SymbolModifier.ABSTRACT));

        // The control, from the same list: a concrete method beside it must NOT be marked, or the
        // assertion above passes against a provider that marks everything.
        CompletionItem isEmpty = named(items, "isEmpty()");
        assertNotNull(isEmpty);
        assertFalse("isEmpty() is concrete on AbstractList", isEmpty.is(SymbolModifier.ABSTRACT));
    }

    /**
     * A record is reported as a record.
     *
     * <p>Claimed when the access-flag reading landed and never asserted — and it is the one kind that is
     * <b>not</b> a flag: there is no {@code ACC_RECORD}, so it is inferred from the superclass being
     * {@code java.lang.Record}. A test of the flag-driven kinds says nothing about it.</p>
     */
    @Test
    public void aRecordIsReportedAsARecord() {
        // One of ours, because the JDK exports very few public records and this one is certainly on the
        // classpath the test is running against.
        assertEquals(SymbolKind.RECORD, kindsOffered("SymbolInf").get("SymbolInfo"));
    }

    /**
     * A LOCAL VARIABLE receiver with a bare trailing dot.
     *
     * <p>The probe parse landed for {@code System.} and not for {@code fb.}, and the difference is the
     * receiver's <em>kind</em>. Inserting a bare name makes {@code fb.Probe} a <b>QualifiedName</b> — Java's
     * syntax for a package or type path — which JDT binds as a path, so a local has nothing to resolve to.
     * It worked for {@code System.} only because a type name is a legal qualifier, which is the coincidence
     * that made half the fix look like all of it.</p>
     *
     * <p>Inserting a call instead forces a {@code MethodInvocation}, whose expression is unambiguously the
     * receiver whatever it is.</p>
     */
    @Test
    public void aLocalVariableReceiverOffersItsMembers() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        String fb = null;\n"
                + "        fb.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        fb.") + "        fb.".length();
        List<String> labels = labelsOf(completeAt(source, caret, "", true));

        assertTrue("a local receiver offered nothing: " + labels, labels.contains("substring(int)"));
        assertTrue(labels.toString(), labels.contains("length()"));
    }

    /** A FIELD receiver too — the other everyday shape, and it parses the same wrong way without the call. */
    @Test
    public void aFieldReceiverOffersItsMembers() {
        String source = ""
                + "class Demo {\n"
                + "    private String name = null;\n"
                + "    void run() {\n"
                + "        name.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        name.") + "        name.".length();
        List<String> labels = labelsOf(completeAt(source, caret, "", true));

        assertTrue("a field receiver offered nothing: " + labels, labels.contains("length()"));
    }

    /**
     * Object's members are marked, so they can be sorted last and drawn quieter.
     *
     * <p>Every type has these seven, so they are never what a list was opened for — and they crowd the top
     * of an alphabetical tail, where {@code equals}, {@code getClass} and {@code hashCode} all sort early.
     * The mark comes from the declaring type the engine reported, not from a list of the seven names: a
     * class declaring its own {@code toString} overrides Object's and is emphatically not inherited.</p>
     */
    @Test
    public void objectsOwnMembersAreMarkedAsInherited() {
        String source = ""
                + "class Demo {\n"
                + "    void run() {\n"
                + "        String s = null;\n"
                + "        s.\n"
                + "    }\n"
                + "}\n";
        int caret = source.indexOf("        s.") + "        s.".length();
        List<CompletionItem> items = completeAt(source, caret, "", true);

        CompletionItem getClass = named(items, "getClass()");
        assertNotNull("getClass should be offered: " + labelsOf(items), getClass);
        assertTrue("getClass is Object's", getClass.inheritedFromObject());

        CompletionItem wait = named(items, "wait()");
        assertNotNull(wait);
        assertTrue("wait is Object's", wait.inheritedFromObject());

        // THE CONTROL, and the reason this is keyed on the declaring type rather than on the seven names:
        // String declares its own toString and equals, so neither is inherited however familiar it looks.
        CompletionItem toString = named(items, "toString()");
        assertNotNull(toString);
        assertFalse("String declares its own toString", toString.inheritedFromObject());

        CompletionItem substring = named(items, "substring(int)");
        assertNotNull(substring);
        assertFalse("an ordinary member is not inherited", substring.inheritedFromObject());
    }

    /**
     * A CALL is a receiver — the shape a JavaScript bug made worth asking about here too.
     *
     * <p>{@code list.get(0).} puts a {@code )} immediately before the dot, so whatever resolves the receiver
     * has to answer for an offset no identifier covers. The JavaScript engine did not, and opened an empty
     * popup on the commonest shape in interop code; this asks the same question of ECJ, because "it works in
     * the other engine" is a claim and not a test.</p>
     */
    @Test
    public void aCallIsAReceiver() {
        String source = ""
                + "import java.util.List;\n"
                + "class Demo {\n"
                + "    void run(List<String> list) {\n"
                + "        list.get(0).\n"
                + "    }\n"
                + "}\n";
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Demo", HostClasspath.detect());
        try {
            int caret = source.indexOf("get(0).") + "get(0).".length();
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            List<String> labels = labelsOf(answered.get().items());
            assertFalse("a call receiver offered nothing at all", labels.isEmpty());
            assertTrue("String's members are missing from " + labels.size() + " rows",
                    labels.toString().contains("length"));
        } finally {
            services.close();
        }
    }

    // ── The snippet shape ───────────────────────────────────────────────────────────────────────

    /** Completes at {@code caret} through the full services stack, exactly as the editor does. */
    private List<String> completeAt(String source, int caret) {
        TextBuffer buffer = new TextBuffer(source);
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Script", HostClasspath.detect());
        try {
            AtomicReference<CompletionList> answered = new AtomicReference<>(CompletionList.EMPTY);
            services.completion().complete(
                    CompletionProvider.Request.character(caret, "", "."),
                    (Versioned<CompletionList> v) -> answered.set(v.orElse(CompletionList.EMPTY)));
            return labelsOf(answered.get().items());
        } finally {
            services.close();
        }
    }

    /**
     * <b>A SCRIPT is a snippet, and every test above is a compilation unit.</b>
     *
     * <p>{@code JavaLanguageServices.analyse} branches on {@link ScriptPrelude#declaresType}: a file that
     * declares a type is analysed as written, and a bare body is wrapped in a prelude first and every
     * answer translated back through {@code SnippetAnalysis}. Both member-completion tests here opened
     * with {@code class Demo {}, so the wrapped half — which is what a person actually writes in the Run
     * panel — had no coverage at all, and the translation sits between the provider and every offset it
     * asks about.</p>
     *
     * <p>{@code System.out.} is the first line most scripts have.</p>
     */
    @Test
    public void aBareSnippetOffersMembersToo() {
        String source = "System.out.\n";
        List<String> labels = completeAt(source, source.indexOf("System.out.") + "System.out.".length());
        assertFalse("a snippet offered nothing at all", labels.isEmpty());
        assertTrue("println is missing from " + labels.size() + " rows: " + labels,
                labels.toString().contains("println"));
    }

    /**
     * And a CALL receiver in a snippet — the shape reported as offering three rows.
     *
     * <p>Two things compose here that are each covered alone: a {@code )} immediately before the dot,
     * where no identifier covers the offset, and the prelude's offset translation. The probe re-parse has
     * to survive both, and it inserts its name into the <em>snippet</em> before the wrap.</p>
     */
    @Test
    public void aCallReceiverInASnippetOffersMembers() {
        String source = "new java.util.ArrayList<String>().\n";
        String upTo = "new java.util.ArrayList<String>().";
        List<String> labels = completeAt(source, source.indexOf(upTo) + upTo.length());
        assertFalse("a call receiver in a snippet offered nothing at all", labels.isEmpty());
        assertTrue("ArrayList's members are missing from " + labels.size() + " rows: " + labels,
                labels.toString().contains("size"));
    }
}
