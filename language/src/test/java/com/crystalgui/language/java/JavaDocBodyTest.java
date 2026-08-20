package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.markup.MarkupParser;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import com.crystalgui.text.lang.Versioned;

import com.crystalgui.text.lang.LanguageServices;

import com.crystalgui.text.TextBuffer;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>M13 §25.6 — the documentation popup's body, which was empty for every symbol.</b>
 *
 * <p>{@code SymbolInfo.documentation} had never been populated by any engine, so the popup showed a
 * declaration and a location and nothing else — {@code DocumentationPopup}'s own javadoc said so, and
 * `plan_m11.md` §24.1 shipped it that way deliberately, waiting for "the ECJ side to learn to read
 * {@code Javadoc} nodes off the AST".</p>
 *
 * <p>Two things had to be true and neither was: ECJ's doc-comment support had to be enabled, and
 * something had to render a {@code Javadoc} node. {@code JavaSignatures.quotedFragment} had already
 * recorded the first half from the other side — it steps over a doc comment by <em>scanning the text</em>
 * because the node covered the comment while {@code getJavadoc()} denied it existed.</p>
 */
public class JavaDocBodyTest {

    private static JavaEngine engine;

    @BeforeClass
    public static void openTheEngine() throws Exception {
        Assume.assumeTrue(EngineHost.defaultSource() != EngineSource.NONE);
        engine = JavaEngine.over(EngineHost.shared(EngineHost.defaultSource()));
    }

    @AfterClass
    public static void closeTheEngine() {
        // BORROWED, so it is not closed here: `over` shares the process's one band loader and closing it
        // would take every other engine down with it.
        engine = null;
    }

    /** The documentation the symbol at {@code needle} carries, or null. */
    private static String docAt(String source, String needle) {
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf(needle));
            assertNotNull("nothing resolved at <" + needle + ">", symbol);
            return symbol.documentation();
        } finally {
            analysis.close();
        }
    }

    /** The description, which is the tag with no name and the thing a reader came for. */
    @Test
    public void aMethodInTheFileShowsItsOwnDocComment() {
        String source = ""
                + "public class Script {\n"
                + "    /** Answers how many rows are loaded. */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";
        String docs = docAt(source, "rows();");
        assertNotNull("the body is still empty -- doc-comment support is off, or nothing renders it",
                docs);
        assertTrue("<" + docs + ">", docs.contains("Answers how many rows are loaded"));
    }

    /**
     * <b>{@code @param} keeps its subject on the same line</b>, or a list of descriptions has no way to
     * say which parameter each belongs to.
     */
    @Test
    public void blockTagsAreRenderedWithTheirSubject() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * Adds a row.\n"
                + "     *\n"
                + "     * @param name the row's label\n"
                + "     * @return whether it fitted\n"
                + "     */\n"
                + "    boolean add(String name) { return true; }\n"
                + "    boolean use() { return add(\"x\"); }\n"
                + "}\n";
        String docs = docAt(source, "add(\"x\")");
        assertNotNull(docs);
        assertTrue("the @param lost its name: <" + docs + ">", docs.contains("name"));
        assertTrue("the @param lost its description: <" + docs + ">", docs.contains("row's label"));
        assertTrue("the @return went missing: <" + docs + ">", docs.contains("whether it fitted"));
    }

    /**
     * <b>An inline tag renders as its subject, and the author's HTML survives to the parser.</b>
     *
     * <p>This asserted that {@code <b>} never reached the reader, and enforced it by stripping the tag
     * here. It still never reaches the reader &mdash; one layer later. A doc comment's tags are the only
     * structure it has, so the emitter passes them through and {@link MarkupParser} is what turns them
     * into styled runs; stripping them here threw that structure away before anything could use it,
     * which is what made the popup a wall of text.</p>
     *
     * <p>So the assertion is the <b>round trip</b>: the tag is present in what the emitter produces, and
     * absent from what a reader is shown. Asserting only the first half would pass against an emitter
     * that never resolved anything, and only the second against the stripping this replaced.</p>
     */
    @Test
    public void inlineTagsBecomeTheirSubjectAndTheAuthorsHtmlReachesTheParser() {
        String source = ""
                + "public class Script {\n"
                + "    /** Delegates to {@link #other}, and <b>never</b> returns {@code null}. */\n"
                + "    String one() { return \"\"; }\n"
                + "    String other() { return \"\"; }\n"
                + "    String use() { return one(); }\n"
                + "}\n";
        String docs = docAt(source, "one();");
        assertNotNull(docs);
        assertTrue("the link's subject was dropped: <" + docs + ">", docs.contains("other"));
        assertTrue("the inline code was dropped: <" + docs + ">", docs.contains("null"));
        assertTrue("the author's emphasis was stripped before the parser could see it: <" + docs + ">",
                docs.contains("<b>never</b>"));

        String read = MarkupParser.parse(docs).text();
        assertTrue("markup reached the reader: <" + read + ">", !read.contains("<b>"));
        assertTrue("the emphasised word was lost with its tag: <" + read + ">", read.contains("never"));
    }

    /**
     * <b>A tag whose subject IS its whole content gets no dangling separator.</b>
     *
     * <p>The dash separates a subject from a description — {@code name — the row's label}
     * — and was written the moment the subject was seen, so {@code @author nobody} rendered as
     * {@code author nobody —}: a separator pointing at nothing. It is now held back until
     * something actually follows it.</p>
     */
    @Test
    public void aTagWithNoDescriptionDoesNotTrailASeparator() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * Does a thing.\n"
                + "     *\n"
                + "     * @author nobody\n"
                + "     * @param name the label\n"
                + "     */\n"
                + "    void act(String name) { }\n"
                + "    void use() { act(\"x\"); }\n"
                + "}\n";
        String read = MarkupParser.parse(docAt(source, "act(\"x\")")).text();

        assertTrue("the author tag lost its value: <" + read + ">", read.contains("nobody"));
        assertTrue("a separator dangles after a tag with nothing after it: <" + read + ">",
                !read.contains("nobody \u2014"));
        assertTrue("the param lost the separator it genuinely needs: <" + read + ">",
                read.contains("\u2014"));
    }

    /**
     * <b>{@code @inheritDoc} <b>inside</b> a comment splices the supertype's text in place.</b>
     *
     * <p>Distinct from the bare case, where a method has no comment at all and inherits the whole of
     * one. Here the author wrote their own prose <em>and</em> asked for the inherited text at a point
     * in it, which used to render as nothing — so the comment lost the half it had asked for,
     * silently, while still looking like a working feature.</p>
     */
    @Test
    public void inheritDocInsideACommentSplicesTheSupertypesText() {
        String source = ""
                + "public class Script {\n"
                + "    interface Source {\n"
                + "        /** Reads the next row. */\n"
                + "        String read();\n"
                + "    }\n"
                + "    static class Impl implements Source {\n"
                + "        /** {@inheritDoc} Blocks until one arrives. */\n"
                + "        public String read() { return \"\"; }\n"
                + "    }\n"
                + "    String use(Impl impl) { return impl.read(); }\n"
                + "}\n";
        // THE NEEDLE IS THE CALL AND NOT THE RECEIVER -- `impl.read()` lands on `impl` and resolves the
        // VARIABLE, whose documentation is legitimately null, so the walk under test never runs. The
        // sibling override test records the same trap; this one repeated it within the hour.
        String read = MarkupParser.parse(docAt(source, "read(); }")).text();

        assertTrue("the inherited sentence was not spliced in: <" + read + ">",
                read.contains("Reads the next row"));
        assertTrue("the overriding method lost its own prose: <" + read + ">",
                read.contains("Blocks until one arrives"));
        assertTrue("the marker leaked to the reader: <" + read + ">",
                !read.contains("inheritDoc"));
    }

    /**
     * <b>A qualified name resolves to a symbol without any position meaning it.</b>
     *
     * <p>What a documentation link needs, and what every other {@code Resolver} method cannot give:
     * {@code {@link java.util.List}} names its target outright and there is no offset in any open file
     * that means it. Without this the link was styled, hit-testable and inert.</p>
     *
     * <p>The documentation is asserted as well as the name, because resolving the type and then
     * describing it poorly is the failure that looks like success — a popup that opens on a real
     * symbol with nothing under it.</p>
     */
    @Test
    public void aQualifiedNameCanBeDescribedWithoutAnOffset() {
        SymbolInfo described = describe("java.util.List");

        assertNotNull("a qualified name on the classpath described nothing", described);
        assertEquals("List", described.name());
        assertNotNull("the described symbol carried no documentation", described.documentation());
    }

    /**
     * A name nothing has answers nothing.
     *
     * <p>Which is the right answer rather than a gap: a link that opens an EMPTY popup is worse than one
     * that does nothing, because the empty popup replaces what was being read.</p>
     */
    @Test
    public void anUnknownQualifiedNameDescribesNothing() {
        assertNull(describe("no.such.Type"));
    }

    /** What the engine says a name refers to, with no offset involved. */
    private static SymbolInfo describe(String name) {
        String source = "public class Script {\n    void run() { }\n}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            return analysis.describe(name);
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>Repeated tags become ONE section, laid out the way that tag reads.</b>
     *
     * <p>Four {@code @author} lines are four names in one answer and join with commas; four {@code @see}
     * lines are four places to look and take a line each. IntelliJ makes the same split and it is a fact
     * about what the tag means rather than about how many there are.</p>
     *
     * <p>Asserted through the RENDERED text, so it survives a change to how a section is marked up —
     * what is pinned is that the values are grouped and separated, not which element carries them.</p>
     */
    @Test
    public void repeatedTagsBecomeOneSectionPerLabel() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * Does a thing.\n"
                + "     *\n"
                + "     * @author Ada\n"
                + "     * @author Grace\n"
                + "     * @see java.util.List\n"
                + "     * @see java.util.Map\n"
                + "     */\n"
                + "    void act() { }\n"
                + "    void use() { act(); }\n"
                + "}\n";
        String markup = docAt(source, "act(); }");
        String read = MarkupParser.parse(markup).text();

        assertTrue("the label was not humanised: <" + read + ">", read.contains("Author:"));
        assertTrue("the label was not humanised: <" + read + ">", read.contains("See Also:"));
        assertEquals("the author label was repeated instead of grouped: <" + read + ">",
                1, countOf(read, "Author:"));
        assertEquals("the see label was repeated instead of grouped: <" + read + ">",
                1, countOf(read, "See Also:"));
        assertTrue("authors were not joined on one line: <" + read + ">",
                read.contains("Ada, Grace"));
        assertTrue("a reference kept its package: <" + read + ">",
                read.contains("List") && !read.contains("java.util.List"));
    }

    /**
     * <b>The subject dash belongs to the tags that HAVE a subject.</b>
     *
     * <p>{@code @param count the number of rows} is a name and a description. Every other tag is one run
     * of prose, and the dash was going into all of them after whatever the first fragment happened to be
     * — so a tag whose text contained an inline {@code {@code}} got a dash dropped at it, mid
     * sentence. It reads as a stray character rather than as a rule misapplied.</p>
     */
    @Test
    public void onlyASubjectTagGetsTheSeparator() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * Does a thing.\n"
                + "     *\n"
                + "     * @implNote Left to {@code javac} and its own discretion.\n"
                + "     * @param name the label\n"
                + "     */\n"
                + "    void act(String name) { }\n"
                + "    void use() { act(\"x\"); }\n"
                + "}\n";
        String read = MarkupParser.parse(docAt(source, "act(\"x\")")).text();

        assertTrue("the implNote lost its wording to a separator: <" + read + ">",
                read.contains("Left to javac and its own discretion"));
        assertTrue("the param lost the separator it needs: <" + read + ">",
                read.contains("name \u2014"));
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
    }

    /**
     * <b>Block tags render in section order, not in the order they were written.</b>
     *
     * <p>Ported from IntelliJ's {@code JavaDocInfoGenerator}: deprecated, parameters, return, throws,
     * since, author/version, the API tags, see-also, then anything unrecognised. A doc comment may
     * write its tags in any order and plenty do, so emitting them as authored means two comments
     * describing the same method lay out differently &mdash; which is what a reader uses position to
     * avoid.</p>
     *
     * <p>The fixture writes them in exactly the wrong order, so a version that preserved source order
     * would fail on every pair rather than by coincidence.</p>
     */
    @Test
    public void blockTagsRenderInSectionOrderRatherThanSourceOrder() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * Adds a row.\n"
                + "     *\n"
                + "     * @see #clear\n"
                + "     * @since 1.2\n"
                + "     * @throws IllegalStateException when closed\n"
                + "     * @return whether it fitted\n"
                + "     * @param name the row's label\n"
                + "     * @deprecated use the builder\n"
                + "     */\n"
                + "    boolean add(String name) { return true; }\n"
                + "    boolean use() { return add(\"x\"); }\n"
                + "    void clear() { }\n"
                + "}\n";
        String docs = docAt(source, "add(\"x\")");
        assertNotNull(docs);

        // Read positions off the RENDERED text rather than the markup, so the assertion survives a
        // change to how a label is emitted -- it is the ordering that is being pinned, not the tags.
        String read = MarkupParser.parse(docs).text();
        int deprecated = read.indexOf("use the builder");
        int param = read.indexOf("row's label");
        int returns = read.indexOf("whether it fitted");
        int throwsAt = read.indexOf("when closed");
        int since = read.indexOf("1.2");
        int see = read.indexOf("clear");

        assertTrue("a section went missing entirely: <" + read + ">",
                deprecated >= 0 && param >= 0 && returns >= 0 && throwsAt >= 0 && since >= 0 && see >= 0);
        assertTrue("@deprecated did not lead: <" + read + ">", deprecated < param);
        assertTrue("@param did not precede @return: <" + read + ">", param < returns);
        assertTrue("@return did not precede @throws: <" + read + ">", returns < throwsAt);
        assertTrue("@throws did not precede @since: <" + read + ">", throwsAt < since);
        assertTrue("@since did not precede @see: <" + read + ">", since < see);
    }

    /**
     * <b>An override with no comment of its own inherits one</b> — the trap that would have made this
     * look broken on arrival.
     *
     * <p>An overriding method usually carries {@code @Override} and nothing else, so without the walk a
     * large fraction of methods render an empty body — which reads as the feature not working rather
     * than as the method having nothing to say. Java's own tooling and IntelliJ both do this walk.</p>
     */
    @Test
    public void anOverrideInheritsTheDocItDoesNotRepeat() {
        String source = ""
                + "public class Script {\n"
                + "    interface Source {\n"
                + "        /** Where the bytes come from. */\n"
                + "        String open();\n"
                + "    }\n"
                + "    static class FromDisk implements Source {\n"
                + "        @Override public String open() { return \"\"; }\n"
                + "    }\n"
                + "    String use(FromDisk d) { return d.open(); }\n"
                + "}\n";
        // THE NEEDLE IS THE CALL, not the receiver. `indexOf("d.open()")` lands on `d` and resolves the
        // VARIABLE, whose documentation is legitimately null -- so the test failed while the walk it was
        // written to exercise never ran at all. And `open()` alone matches the interface's own
        // declaration first, which would have passed for the wrong reason.
        String docs = docAt(source, "open(); }");
        assertNotNull("an override with no comment rendered nothing, which is most overrides", docs);
        assertTrue("<" + docs + ">", docs.contains("Where the bytes come from"));
    }

    /**
     * <b>A JDK method's own javadoc</b>, through the same attached source the signature is quoted from.
     *
     * <p>Skipped where {@code src.zip} is not on disk, which is a JRE — the production shape, and
     * exactly the gap §25.4/§25.5 exist to close.</p>
     */
    @Test
    public void aClasspathMethodShowsTheJdkAuthorsOwnDoc() {
        String source = ""
                + "public class Script {\n"
                + "    void run() { System.out.println(\"hi\"); }\n"
                + "}\n";
        String docs = docAt(source, "println");
        Assume.assumeNotNull("no src.zip on this machine, so there is no attached source to read", docs);
        assertTrue("the JDK's own wording did not arrive: <" + docs + ">",
                docs.toLowerCase(java.util.Locale.ROOT).contains("print"));
    }

    /** No comment is null, not empty — the popup hides the band on blank and an empty one reads as a fault. */
    @Test
    public void anUndocumentedSymbolAnswersNothing() {
        String source = ""
                + "public class Script {\n"
                + "    int plain() { return 1; }\n"
                + "    int use() { return plain(); }\n"
                + "}\n";
        assertNull(docAt(source, "plain();"));
    }

    /** And a local variable has no doc comment to find, which must not throw looking. */
    @Test
    public void aLocalIsAnswerlessAndDoesNotThrow() {
        String source = "public class Script {\n    void run() { int count = 1; count++; }\n}\n";
        assertNull(docAt(source, "count++"));
    }

    /** The engine still reports no javadoc DIAGNOSTICS — the flag turns on parsing, not ~40 problems. */
    @Test
    public void enablingDocCommentsAddsNoNewProblems() {
        String source = ""
                + "public class Script {\n"
                + "    /** @param gone a parameter that is not there */\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, List.of(), engine.releaseLevel(), 1L);
        try {
            assertTrue("a javadoc diagnostic appeared: " + analysis.diagnostics(),
                    analysis.diagnostics().isEmpty());
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A link's target is qualified, because the popup that follows it has no imports.</b>
     *
     * <p>A javadoc reference is written against the package and imports of the file it appears in.
     * {@code java.text.Collator} says {@code @see RuleBasedCollator} and means
     * {@code java.text.RuleBasedCollator}; put the reference in the href exactly as written and
     * {@code Resolver.describe} builds {@code class $Probe { RuleBasedCollator $x; }}, which resolves to
     * nothing and is refused. The link is styled, hoverable, and does nothing — and it is most of the
     * JDK, since almost no {@code @see} in it is written out in full.</p>
     *
     * <p>It looked like it worked because the fixtures were qualified. {@code @see java.util.List} is
     * its own answer.</p>
     */
    @Test
    public void anUnqualifiedReferenceIsLinkedByItsResolvedName() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    /**\n"
                + "     * Uses things.\n"
                + "     *\n"
                + "     * @see List\n"
                + "     */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";
        String docs = docAt(source, "rows();");
        assertNotNull(docs);
        assertTrue("the href must carry a name that resolves on its own, not the one the author"
                        + " could write because of an import: <" + docs + ">",
                docs.contains("java:java.util.List"));
        // The reader still sees what the author wrote.
        assertTrue("the LABEL should stay short: <" + docs + ">", docs.contains(">List<"));
    }

    /**
     * <b>A generic reference is qualified by its ERASURE.</b>
     *
     * <p>{@code ITypeBinding.getQualifiedName()} answers {@code java.util.List<E>} for the generic
     * declaration, and no probe can declare a field of that — so the qualification would have swapped
     * one unfollowable link for another. The erasure also keeps the SOURCE spelling of a nested type,
     * {@code java.util.Map.Entry} rather than the binary {@code Map$Entry}, which is the form the probe
     * has to write down.</p>
     */
    @Test
    public void aGenericReferenceIsQualifiedWithoutItsTypeArguments() {
        String source = ""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    /**\n"
                + "     * Uses entries.\n"
                + "     *\n"
                + "     * @see Map.Entry\n"
                + "     */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";
        String docs = docAt(source, "rows();");
        assertNotNull(docs);
        assertTrue("a nested type must keep its source spelling: <" + docs + ">",
                docs.contains("java:java.util.Map.Entry"));
        assertFalse("type arguments must not reach the href: <" + docs + ">", docs.contains("<E>"));
        assertFalse("the binary spelling cannot be declared by a probe: <" + docs + ">",
                docs.contains("Map$Entry"));
    }


    /**
     * <b>A documentation link works in a SCRIPT, not only in a file that declares a type.</b>
     *
     * <p>{@code JavaLanguageServices.analyse} branches on {@link ScriptPrelude#declaresType}: a file
     * with a class is analysed as written, and a bare body — which is what a person writes in the Run
     * panel — is wrapped in a prelude and every answer translated back through {@code SnippetAnalysis}.
     * That class overrides each method that has an offset to move, and {@code describe} takes a NAME, so
     * there was nothing to translate and nothing was written. The bridge's {@code default} then answered
     * null for it.</p>
     *
     * <p><b>The symptom was file-shaped and read as random.</b> Every link worked in a file with a class
     * declaration and none worked in a one-line scratch file, with the same popup, the same emitter and
     * the same engine underneath — three rounds went to the press, the anchor and the href before the
     * difference turned out to be which of two {@code Analysis} objects was answering.</p>
     */
    @Test
    public void aScriptCanFollowADocumentationLink() {
        TextBuffer buffer = new TextBuffer("String abc = \"ABC\";\n");
        LanguageServices services = new JavaLanguageServices(
                buffer, engine, null, "Script", HostClasspath.detect());
        try {
            AtomicReference<SymbolInfo> answered = new AtomicReference<>();
            services.resolver().describe("java.text.Collator",
                    (Versioned<SymbolInfo> v) -> answered.set(v.value()));
            assertNotNull("a bare snippet answered nothing for a link every other file follows",
                    answered.get());
            assertEquals("Collator", answered.get().name());
        } finally {
            services.close();
        }
    }

    /**
     * <b>A long comment is rendered whole.</b>
     *
     * <p>There was a 4000-character cap, from when the popup was a fixed band of text rather than
     * something scrollable and resizable. It cut RENDERED MARKUP, so the cut landed wherever 4000
     * characters happened to fall — mid-tag as readily as mid-word — and handed the parser markup that
     * does not close. {@code java.lang.Class} is the everyday case: its comment is several times the
     * cap, so the nesting section ended in {@code , w…} and the rest was simply gone.</p>
     */
    @Test
    public void aLongCommentIsNotTruncated() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("     * Sentence number ").append(i)
                .append(" of a comment that is comfortably past any cap.\n");
        }
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + body
                + "     * @since 1.0\n"
                + "     */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";

        String docs = docAt(source, "rows();");
        assertNotNull(docs);
        assertTrue("the comment was cut short: " + docs.length() + " characters",
                docs.length() > 8000);
        assertTrue("the last sentence is missing, so it was truncated",
                docs.contains("Sentence number 199"));
        assertFalse("an ellipsis means the cap is still there", docs.endsWith("\u2026"));
        // The section table is emitted AFTER the description, so a cap would have taken it entirely.
        assertTrue("the trailing section was lost with the truncation", docs.contains("Since:"));
    }

    /**
     * <b>An author's link label may contain markup, and it must survive.</b>
     *
     * <p>{@code flatten} passes an author's HTML through verbatim — that is the design, their markup is
     * the structure — so a label arrives here already rendered, and escaping it again turns it back into
     * text. {@code {@linkplain Class#isHidden() <em>hidden</em>}} is the JDK's own spelling and drew as
     * a literal {@code <em>hidden</em>}, angle brackets and all, mid-sentence.</p>
     */
    @Test
    public void aLinkLabelKeepsItsOwnMarkup() {
        String source = ""
                + "public class Script {\n"
                + "    /**\n"
                + "     * A {@linkplain java.util.List <em>special</em> list} of things.\n"
                + "     */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";

        String docs = docAt(source, "rows();");
        assertNotNull(docs);
        assertTrue("the label's markup was escaped back into text: <" + docs + ">",
                docs.contains("<em>special</em>"));
        assertFalse("the angle brackets were escaped: <" + docs + ">", docs.contains("&lt;em&gt;"));
    }
}
