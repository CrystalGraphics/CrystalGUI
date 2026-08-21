package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.assist.AttachedSources;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.markup.MarkupParser;

import javax.annotation.Nullable;

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
     * <b>{@code @inheritDoc} resolves through the WHOLE chain, not one hop.</b>
     *
     * <p>The middle of a three-level hierarchy is the case, and it is not exotic — an abstract class
     * between an interface and its implementation is the ordinary shape, and the comment it carries is
     * very often nothing but the marker, written to mean "the same as above". One hop took that text and
     * stripped its marker, which yields the empty string: the bottom method rendered its own prose and
     * lost the sentence it had explicitly asked for, while a two-level hierarchy — every fixture written
     * before this one — worked perfectly.</p>
     */
    @Test
    public void inheritDocFollowsMoreThanOneLevel() {
        String source = ""
                + "public class Script {\n"
                + "    interface Source {\n"
                + "        /** Reads the next row. */\n"
                + "        String read();\n"
                + "    }\n"
                + "    static abstract class Base implements Source {\n"
                + "        /** {@inheritDoc} */\n"
                + "        public abstract String read();\n"
                + "    }\n"
                + "    static class Impl extends Base {\n"
                + "        /** {@inheritDoc} Blocks until one arrives. */\n"
                + "        public String read() { return \"\"; }\n"
                + "    }\n"
                + "    String use(Impl impl) { return impl.read(); }\n"
                + "}\n";
        String read = MarkupParser.parse(docAt(source, "read(); }")).text();

        assertTrue("the sentence two levels up never arrived: <" + read + ">",
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

    /**
     * <b>A member reference answers with the MEMBER, not with its owning type.</b>
     *
     * <p>{@code List#add} opened {@code List} — related, and not what was asked. It was recorded as
     * needing a probe that CALLS the member so overload resolution picks one, which is what
     * {@code InteropResolver.describeMember} builds and is child-side. That reasoning was sound and its
     * conclusion was wrong: a call is not the only construct JDT resolves a member through, and the
     * other one is the reference the author already typed. The probe is a doc comment.</p>
     */
    @Test
    public void aMemberReferenceDescribesTheMember() {
        SymbolInfo member = describe("java.util.List#add");
        assertNotNull("a member reference described nothing at all", member);
        assertEquals("the owning type answered instead of the member", "add", member.name());
    }

    /**
     * And the argument list picks the overload, exactly as javadoc's own rules do.
     *
     * <p>Asserted on the PARAMETER COUNT rather than on the name, because both overloads are called
     * {@code add} — a test on the name passes against a probe that ignores the argument list entirely,
     * which is the whole thing being checked here.</p>
     */
    @Test
    public void anArgumentListPicksTheOverload() {
        SymbolInfo two = describe("java.util.List#add(int, java.lang.Object)");
        assertNotNull("an overload written with its argument types described nothing", two);
        assertEquals("add", two.name());
        assertEquals("the argument list did not choose the overload", 2, two.parameters().size());
    }

    /**
     * A member nothing declares falls back to the type it was qualified by.
     *
     * <p>An unresolvable javadoc reference reports <b>no diagnostic</b> — the javadoc problems are
     * options of their own and are off — so the probe cannot be checked the way the type probe is, and
     * the answer's own name is the assertion. It falls back rather than answering null because the type
     * half of the reference did resolve, and opening it is a useful answer where opening nothing is not.
     */
    @Test
    public void anUnknownMemberFallsBackToItsType() {
        SymbolInfo fallback = describe("java.util.List#noSuchMemberAnywhere");
        assertNotNull("neither the member nor the type it names answered", fallback);
        assertEquals("List", fallback.name());
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

    /**
     * <b>A reference to something in THIS file resolves, and a member resolves to itself.</b>
     *
     * <p>The fallback builds a probe — a separate compilation unit compiled against the classpath —
     * and the classpath does not contain the file being edited. So every inward-pointing link answered
     * nothing: {@code @see #helper()}, {@code {@link MyOtherClass}}, a bare {@code #member}. In a file
     * whose See Also mixed JDK references with its own, the JDK half worked and the rest did not,
     * which reads as the link being broken at random.</p>
     *
     * <p>A member is answered as ITSELF here, which the probe path cannot do — the plan's "a member
     * resolves to its owning type" partial is about members reached through the classpath, and a
     * declaration in this file needs no unit that calls it.</p>
     */
    @Test
    public void aReferenceIntoThisFileResolves() {
        String source = ""
                + "public class Script {\n"
                + "    /** Uses {@link Helper} and {@link #other()}. */\n"
                + "    void run() { }\n"
                + "    void other() { }\n"
                + "    interface Helper { void help(String s); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            SymbolInfo type = analysis.describe("Helper");
            assertNotNull("a type declared in this very file resolved to nothing", type);
            assertEquals("Helper", type.name());

            SymbolInfo bare = analysis.describe("#other()");
            assertNotNull("a bare #member -- 'on the class this comment is in' -- resolved to nothing",
                    bare);
            assertEquals("a bare member must resolve to the METHOD, not to its owning type",
                    "other", bare.name());

            SymbolInfo qualified = analysis.describe("Helper#help");
            assertNotNull("a qualified member in this file resolved to nothing", qualified);
            assertEquals("help", qualified.name());

            assertNull("a qualifier naming another type must not be answered by this file's own member",
                    analysis.describe("Nowhere#other"));
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A member reference carries a QUALIFIED owner into the href.</b>
     *
     * <p>{@code @see Map#entrySet()} is a {@code MethodRef} rather than a {@code Name}, so it fell
     * through the qualification added for types and kept the author's spelling. That cuts to a bare
     * {@code Map}, which resolves nowhere — while the two plain-name references above it in the same
     * See Also worked, because they happen to be names.</p>
     */
    @Test
    public void aMemberReferenceIsLinkedByItsQualifiedOwner() {
        String source = ""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    /**\n"
                + "     * Uses things.\n"
                + "     *\n"
                + "     * @see Map#entrySet()\n"
                + "     */\n"
                + "    int rows() { return 1; }\n"
                + "    int use() { return rows(); }\n"
                + "}\n";
        String docs = docAt(source, "rows();");
        assertNotNull(docs);
        assertTrue("the owner must be qualified or the href resolves nowhere: <" + docs + ">",
                docs.contains("java:java.util.Map#entrySet()"));
    }

    /**
     * <b>A reference that did not really resolve is not coloured as though it had.</b>
     *
     * <p>{@code setBindingsRecovery} synthesises a binding for an unknown type rather than answering
     * null, so {@code no.such.Type} comes back a CLASS named {@code Type} in a container
     * {@code no.such}. In code that is harmless — the same range is marked {@code unresolved} and the
     * later token wins — but that mark is deliberately suppressed inside a doc comment, so a recovered
     * kind would be the ONLY thing said about the name, and a broken reference would draw in the same
     * confident colour as a working one beside it.</p>
     */
    @Test
    public void anUnresolvableDocReferenceIsNotColouredAsAType() {
        String source = ""
                + "public class Script {\n"
                + "    /** Sees {@link no.such.Type} and {@link String}. */\n"
                + "    void run() { }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            int missing = source.indexOf("no.such.Type") + "no.such.".length();
            int real = source.indexOf("{@link String}") + "{@link ".length();
            String missingCapture = null;
            String realCapture = null;
            for (com.crystalgui.text.syntax.SyntaxToken t : analysis.semanticTokens()) {
                if (missing >= t.start() && missing < t.end()) missingCapture = t.name();
                if (real >= t.start() && real < t.end()) realCapture = t.name();
            }
            assertEquals("the reference that DOES resolve must still be coloured", "type", realCapture);
            assertNull("a recovered binding was coloured as a real type: " + missingCapture,
                    missingCapture);
        } finally {
            analysis.close();
        }
    }

    // ── V1: where a classpath symbol is declared ────────────────────────────────────

    /**
     * <b>A classpath type reports where it is declared, in a {@code library://} resource.</b>
     *
     * <p>{@code findDeclaringNode} only ever answers for the unit it is asked, so this was null for
     * everything on the classpath — which is what made Ctrl+B into {@code ArrayList} do nothing at all.
     * The source was readable the whole time; nothing was asking where in it.</p>
     */
    @Test
    public void aClasspathTypeIsDeclaredInALibraryResource() {
        assumeSourceFor("java.util.ArrayList");
        DeclarationSite site = siteAt(
                "public class Script {\n    java.util.ArrayList<String> names;\n}\n", "ArrayList<String>");
        assertNotNull("the archive has ArrayList's source and nothing located the declaration", site);

        assertFalse("a classpath type must not claim to be in this document", site.isSameDocument());
        assertTrue("the site is not a library site: " + site.resource(), site.isLibrary());
        assertEquals("library://java.util.ArrayList", site.resource().toString());
    }

    /**
     * <b>A MEMBER reports its own position, not its type's.</b>
     *
     * <p>The distinction the whole feature turns on: opening {@code ArrayList.java} at line 1 when the
     * user asked about {@code add} is technically a jump to the right file and is not the answer.</p>
     */
    @Test
    public void aClasspathMemberIsDeclaredAtItsOwnName() {
        assumeSourceFor("java.util.ArrayList");
        DeclarationSite type = siteAt(
                "public class Script {\n    java.util.ArrayList<String> names;\n}\n", "ArrayList<String>");
        assertNotNull("the type located nothing", type);
        DeclarationSite member = siteAt(""
                + "public class Script {\n"
                + "    void run(java.util.ArrayList<String> names) { names.isEmpty(); }\n"
                + "}\n", "isEmpty()");
        assertNotNull("a member of a classpath type located nothing", member);
        assertTrue(member.isLibrary());
        assertEquals("library://java.util.ArrayList", member.resource().toString());
        assertTrue("the member answered with its type's own line",
                member.start().row() > type.start().row());
    }

    /**
     * <b>A NESTED type is declared in its top-level file.</b>
     *
     * <p>{@code Map.Entry} has no file of its own — a source archive is keyed by compilation unit — so
     * the resource names {@code java.util.Map} and the range points inside it. Getting this wrong asks
     * the archive for {@code Map$Entry.java}, which exists nowhere, and the feature silently does
     * nothing for every nested type in the JDK.</p>
     */
    @Test
    public void aNestedTypeIsDeclaredInItsTopLevelFile() {
        assumeSourceFor("java.util.Map");
        DeclarationSite site = siteAt(
                "public class Script {\n    java.util.Map.Entry<String, String> row;\n}\n", "Entry<String");
        assertNotNull("the archive has Map's source and the nested type located nothing", site);
        assertTrue(site.isLibrary());
        assertEquals("a nested type must resolve to the file that declares it",
                "library://java.util.Map", site.resource().toString());
    }

    /**
     * <b>A type declared HERE wins over a classpath type of the same name.</b>
     *
     * <p>The local unit is definitive about itself and the archive is a fallback — in that order, or
     * editing a class called {@code List} navigates into {@code java.util.List} instead of into the
     * declaration three lines above the caret.</p>
     */
    @Test
    public void aLocalDeclarationOutranksTheClasspath() {
        DeclarationSite site = siteAt(""
                + "public class Script {\n"
                + "    static class ArrayList { }\n"
                + "    ArrayList mine;\n"
                + "}\n", "ArrayList mine");
        assertNotNull(site);
        assertTrue("the classpath answered for a type declared in this very file",
                site.isSameDocument());
    }

    /**
     * <b>The position is legal against the text the archive serves.</b>
     *
     * <p>The rule every diagnostic in this stack follows, applied to navigation: a row and column mean
     * something only against the document they were computed from. Asserted by slicing that text at the
     * reported range and reading the identifier back — an assertion no off-by-one can pass, where
     * comparing row numbers against a remembered constant passes against a file that has since moved
     * on.</p>
     */
    @Test
    public void theRangeLandsOnTheIdentifierInTheServedText() {
        DeclarationSite site = siteAt(""
                + "public class Script {\n"
                + "    void run(java.util.ArrayList<String> names) { names.isEmpty(); }\n"
                + "}\n", "isEmpty()");
        assertNotNull("the archive has ArrayList's source and nothing located the declaration", site);

        String served = AttachedSources.forClasspath(HostClasspath.detect())
                .textOf(site.resource().path());
        assertNotNull("the archive served nothing for the resource the site names", served);

        String[] lines = served.split("\n", -1);
        assertTrue("the site names a row past the end of the served text",
                site.start().row() < lines.length);
        String line = lines[site.start().row()];
        assertTrue("the site names a column past the end of its row",
                site.start().column() <= line.length());
        assertTrue("the range does not land on the identifier: <"
                        + line.substring(Math.min(site.start().column(), line.length())) + ">",
                line.startsWith("isEmpty", site.start().column()));
    }

    /**
     * Skips when this host genuinely has no source for {@code topLevelName}.
     *
     * <p><b>The gate asks the ARCHIVE, never the answer.</b> Assuming on the site itself skips when the
     * host has no sources and skips when the feature is broken, and those are opposite outcomes wearing
     * one face — a regression would report as a green run with a quiet skip. Asking whether the text
     * exists is a question the feature cannot influence, so what follows it is an assertion.</p>
     */
    private static void assumeSourceFor(String topLevelName) {
        Assume.assumeNotNull("no attached source for " + topLevelName + " on this host",
                AttachedSources.forClasspath(HostClasspath.detect()).textOf(topLevelName));
    }

    /** Where the engine says the symbol at {@code needle} is declared. */
    @Nullable
    private static DeclarationSite siteAt(String source, String needle) {
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "Script", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf(needle));
            return symbol == null ? null : symbol.declaration();
        } finally {
            analysis.close();
        }
    }

    // ── V5: services for a document nobody can edit ────────────────────────────────

    /**
     * <b>A JDK source file resolves — which it cannot do at the band's ordinary compliance.</b>
     *
     * <p>The trap this exists for: a file declaring {@code package java.util} parsed at Java 9 or above
     * lands in the unnamed module against a package {@code java.base} owns, and JDT reports <em>"the
     * package java.util conflicts with a package accessible from another module"</em>. That single error
     * is not local — it poisons resolution for the entire unit, so <b>every</b> name in the file becomes
     * unresolvable. A viewer given ordinary services would open every JDK class fully underlined with
     * nothing hoverable, which is worse than opening it with no services at all.</p>
     *
     * <p>Asserted by resolving a name INSIDE such a file, because that is the thing the conflict takes
     * away. Counting errors would pass against a build where diagnostics are simply suppressed — which
     * they also are, and which is the sibling test below.</p>
     */
    @Test
    public void aPlatformSourceResolvesAtTheOlderCompliance() {
        String source = ""
                + "package java.util;\n"
                + "public class Probe {\n"
                + "    java.util.List<String> rows;\n"
                + "    int count() { return rows.size(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "java.util.Probe", source, HostClasspath.detect(), 8, 1L);
        try {
            SymbolInfo symbol = analysis.resolveAt(source.indexOf("size()"));
            assertNotNull("a name inside a java.* file resolved to nothing -- the module conflict "
                    + "poisoned the unit, which is what compliance 8 exists to avoid", symbol);
            assertEquals("size", symbol.name());
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>And the same file at the band's own compliance is the failure being avoided.</b>
     *
     * <p>The negative control. Without it the test above passes on a band whose ceiling happens to be 8
     * and proves nothing at all — and it would keep passing if the compliance choice were deleted.
     * Skipped rather than asserted when the band <em>is</em> 8, because there is then no second level to
     * compare against and no conflict to provoke.</p>
     */
    @Test
    public void theSameFileAtTheBandsCeilingIsWhyTheOlderOneIsChosen() {
        Assume.assumeTrue("this band compiles at 8, so there is no higher level to contrast with",
                engine.releaseLevel() > 8);
        String source = ""
                + "package java.util;\n"
                + "public class Probe {\n"
                + "    java.util.List<String> rows;\n"
                + "    int count() { return rows.size(); }\n"
                + "}\n";
        SourceAnalyzer.Analysis analysis = engine.analyzer().analyze(
                "java.util.Probe", source, HostClasspath.detect(), engine.releaseLevel(), 1L);
        try {
            boolean conflicted = false;
            for (Diagnostic problem : analysis.diagnostics()) {
                if (problem.message() != null && problem.message().contains("conflicts with a package")) {
                    conflicted = true;
                }
            }
            assertTrue("the module conflict did not occur, so compliance 8 is guarding nothing -- "
                    + "check this before deleting it", conflicted);
        } finally {
            analysis.close();
        }
    }

    /**
     * <b>A library document announces no diagnostics, and still resolves.</b>
     *
     * <p>Both halves in one test, because either alone passes against the wrong thing: a document that
     * reports nothing <em>because it analysed nothing</em> would satisfy the first assertion and is the
     * failure being avoided.</p>
     *
     * <p>Why suppress at all: the problems of a borrowed class are <b>ours</b>. Its classpath is one we
     * approximate, its compliance is one we chose for it, and a decompiled body is a reconstruction that
     * need not compile. Filing them puts rows in the Problems panel that name somebody else's correct
     * code, that nobody can act on, and that push the reader's own problems off the list. IntelliJ draws
     * the same line — a decompiled file navigates and highlights and is never inspected.</p>
     */
    @Test
    public void aLibraryDocumentReportsNothingAndStillResolves() {
        // A DELIBERATE ERROR, so "no diagnostics" cannot be true by accident.
        TextBuffer buffer = new TextBuffer(""
                + "public class Probe {\n"
                + "    java.util.List<String> rows = nonsense();\n"
                + "}\n");
        LanguageServices ordinary = new JavaLanguageServices(
                buffer, engine, null, "Probe", HostClasspath.detect());
        int reportedNormally;
        try {
            reportedNormally = countDiagnostics(ordinary);
        } finally {
            ordinary.close();
        }
        assertTrue("the fixture compiles cleanly, so suppression cannot be observed",
                reportedNormally > 0);

        LanguageServices library = JavaLanguageServices.forLibrary(
                new TextBuffer(buffer.toString()), engine, null, "Probe", HostClasspath.detect(), false);
        try {
            assertEquals("a borrowed document filed problems nobody can act on",
                    0, countDiagnostics(library));
            AtomicReference<SymbolInfo> answered = new AtomicReference<>();
            library.resolver().describe("java.util.List",
                    (Versioned<SymbolInfo> v) -> answered.set(v.value()));
            assertNotNull("suppressing diagnostics also switched the analysis off", answered.get());
            assertEquals("List", answered.get().name());
        } finally {
            library.close();
        }
    }

    /** How many problems a services object announces for the text it holds. */
    private static int countDiagnostics(LanguageServices services) {
        AtomicReference<List<Diagnostic>> reported = new AtomicReference<>(List.of());
        services.onDiagnostics(versioned -> reported.set(versioned.value()));
        // ANALYSIS IS DEBOUNCED AND SCHEDULED. With no scheduler it runs inline on the first ask, which
        // is why these are constructed with a null one -- but the announcement still arrives through the
        // listener rather than as a return value, so it is read after.
        for (int i = 0; i < 4 && reported.get().isEmpty(); i++) {
            services.resolver().describe("java.lang.String", versioned -> { });
        }
        return reported.get().size();
    }
}
