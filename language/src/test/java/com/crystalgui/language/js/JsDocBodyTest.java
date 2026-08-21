package com.crystalgui.language.js;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.java.JavaLanguage;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A documented JavaScript declaration hovers with its documentation.</b>
 *
 * <p>§6.1 of the javadoc plan: the renderer, the model, the link gesture and {@code Resolver.describe}
 * were all language-neutral already, and the JavaScript side produced nothing to feed them. What it did
 * produce was the description RAW — so a documented function hovered with its markdown markers intact,
 * asterisks and backticks and all, and no tag ever reached the reader.</p>
 *
 * <p>Asserted on the emitted markup rather than on pixels, and through the real engine rather than by
 * calling the emitter: the question is whether a comment attached to a declaration comes back attached
 * to its symbol, which is three seams away from the string this produces.</p>
 */
public class JsDocBodyTest {

    @BeforeClass
    public static void openTheEngine() {
        Assume.assumeTrue("no staged engine directory; run :language:stageEngines",
                EngineHost.defaultSource() != EngineSource.NONE);
        // BOTH ENGINES, as a host opens them. Registering JavaScript alone works for everything this
        // class asserts and leaves the process in a state the next test class inherits: the interop
        // tier is lent the Java engine at registration, so a JavaScript-only registration is a shape no
        // deployment produces. `JsLanguageRegistrationTest` is the one that checks the pair, and it saw
        // Java missing when this class had run first.
        JavaLanguage.register(null, EngineHost.defaultSource());
        Assume.assumeTrue("the staged directory has no Rhino for this band",
                JsLanguage.register(null, EngineHost.defaultSource()));
    }

    /** The documentation of the symbol at {@code needle}, or null. */
    private static String docAt(String source, String needle) {
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 1L);
        SymbolInfo symbol = analysis.resolveAt(source.indexOf(needle));
        assertNotNull("nothing resolved at <" + needle + ">", symbol);
        return symbol.documentation();
    }

    /** The commonest shape there is: prose, and nothing else. */
    @Test
    public void aDescriptionReachesTheSymbol() {
        String docs = docAt(""
                + "/** Joins a name and a count into a label. */\n"
                + "function summarise(name, count) { return name; }\n"
                + "summarise('a', 1);\n", "summarise('a'");
        assertNotNull("a documented function carried no documentation at all", docs);
        assertTrue(docs, docs.contains("Joins a name and a count into a label."));
    }

    /**
     * <b>The description is Markdown, and arrives as markup rather than as its own source.</b>
     *
     * <p>This is what shipping the raw description looked like: the words were all there and every
     * marker was too, so bold read as a word between asterisks and a name meant to be code read as a
     * word between backticks.</p>
     */
    @Test
    public void theDescriptionIsRenderedAsMarkdown() {
        String docs = docAt(""
                + "/**\n"
                + " * A **bold** word, a `code` word, and a [link](https://example.com).\n"
                + " *\n"
                + " * - a bullet\n"
                + " * - another\n"
                + " */\n"
                + "function documented() { }\n"
                + "documented();\n", "documented();");
        assertNotNull(docs);
        assertTrue("bold was left as its own markers: " + docs, docs.contains("<b>bold</b>"));
        assertTrue("a code span was left as backticks: " + docs, docs.contains("<code>code</code>"));
        assertTrue("a link was left as brackets: " + docs, docs.contains("<a href=\"https://example.com\""));
        assertTrue("a list was left as hyphens: " + docs, docs.contains("<li>"));
        assertFalse("a marker survived into the output: " + docs, docs.contains("**bold**"));
    }

    /**
     * <b>Block tags become a section table, in a fixed order rather than the author's.</b>
     *
     * <p>The same {@code <dl>} javadoc emits, which is what lets one renderer draw both — and the same
     * ordering, because {@code @returns} written above {@code @param} is a fact about the author's
     * typing rather than about the function.</p>
     */
    @Test
    public void blockTagsBecomeASectionTable() {
        String docs = docAt(""
                + "/**\n"
                + " * Does a thing.\n"
                + " *\n"
                + " * @returns {string} the label\n"
                + " * @param {string} name the name to show\n"
                + " * @param {number} [count=1] how many\n"
                + " * @throws {TypeError} if the name is not a string\n"
                + " * @since 1.0\n"
                + " */\n"
                + "function labelled(name, count) { return name; }\n"
                + "labelled('a', 1);\n", "labelled('a'");
        assertNotNull(docs);
        assertTrue("no section table was emitted: " + docs, docs.contains("<dl>"));
        assertTrue(docs, docs.contains("<dt>Params:</dt>"));
        assertTrue(docs, docs.contains("<dt>Returns:</dt>"));
        assertTrue(docs, docs.contains("<dt>Throws:</dt>"));
        assertTrue(docs, docs.contains("<dt>Since:</dt>"));

        assertTrue("a parameter's name is what a reader scans for: " + docs,
                docs.contains("<code>name</code>"));
        assertTrue("its type belongs beside it: " + docs, docs.contains("<code>string</code>"));
        assertTrue("the prose belongs after the dash: " + docs, docs.contains("the name to show"));

        assertTrue("the sections are in section order, not source order: " + docs,
                docs.indexOf("Params:") < docs.indexOf("Returns:"));
        assertTrue(docs.indexOf("Returns:") < docs.indexOf("Throws:"));
    }

    /**
     * <b>Optionality decoration belongs to the signature, not to the row.</b>
     *
     * <p>{@code [count=1]} says the parameter is optional and defaults to one — both facts about the
     * declaration, which the popup draws a few lines above from the declaration itself. Repeating the
     * brackets in the row is the popup disagreeing with itself about how many arguments the function
     * takes.</p>
     */
    @Test
    public void anOptionalParameterIsNamedWithoutItsBrackets() {
        String docs = docAt(""
                + "/**\n"
                + " * Does a thing.\n"
                + " *\n"
                + " * @param {number} [count=1] how many\n"
                + " */\n"
                + "function optional(count) { return count; }\n"
                + "optional(1);\n", "optional(1)");
        assertNotNull(docs);
        assertTrue(docs, docs.contains("<code>count</code>"));
        assertFalse("the bracket decoration reached the reader: " + docs, docs.contains("[count"));
    }

    /**
     * <b>Several parameters keep the order they were declared in.</b>
     *
     * <p>The one ordering inside a doc comment that carries meaning — it is the parameter order — so
     * the sort has to be stable. Every other tag is sorted into its section and the author's order
     * between sections is discarded on purpose.</p>
     */
    @Test
    public void parametersKeepTheirDeclaredOrder() {
        String docs = docAt(""
                + "/**\n"
                + " * @param {string} first the first\n"
                + " * @param {string} second the second\n"
                + " * @param {string} third the third\n"
                + " */\n"
                + "function ordered(first, second, third) { return first; }\n"
                + "ordered('a', 'b', 'c');\n", "ordered('a'");
        assertNotNull(docs);
        assertTrue(docs, docs.indexOf("first") < docs.indexOf("second"));
        assertTrue(docs, docs.indexOf("second") < docs.indexOf("third"));
    }

    /**
     * <b>An example is code, not prose.</b>
     *
     * <p>Rendered as a paragraph it is a run of semicolons with the line breaks collapsed out of it,
     * which is the one shape a sample must not take.</p>
     */
    @Test
    public void anExampleIsACodeBlock() {
        String docs = docAt(""
                + "/**\n"
                + " * Does a thing.\n"
                + " *\n"
                + " * @example\n"
                + " * const t = make('rows');\n"
                + " * console.log(t.size * 2);\n"
                + " */\n"
                + "function make(name) { return name; }\n"
                + "make('a');\n", "make('a')");
        assertNotNull(docs);
        assertTrue(docs, docs.contains("<dt>Example:</dt>"));
        assertTrue("the sample must survive as code: " + docs, docs.contains("<pre>"));
        assertTrue(docs, docs.contains("const t = make('rows');"));
        assertTrue("its line breaks are its structure: " + docs,
                docs.contains("console.log(t.size * 2);"));
        assertFalse("an asterisk in code was read as emphasis: " + docs, docs.contains("<i>"));
    }

    /** Several authors are one list; several references are not. */
    @Test
    public void someSectionsJoinAndOthersDoNot() {
        String docs = docAt(""
                + "/**\n"
                + " * Does a thing.\n"
                + " *\n"
                + " * @author Ada\n"
                + " * @author Grace\n"
                + " * @see somethingElse\n"
                + " * @see anotherThing\n"
                + " */\n"
                + "function credited() { }\n"
                + "credited();\n", "credited();");
        assertNotNull(docs);
        assertTrue("two authors are one answer: " + docs, docs.contains("Ada, Grace"));
        assertFalse("two references are two places to look: " + docs,
                docs.contains("somethingElse, anotherThing"));
    }

    /**
     * <b>An inline tag is a reference the popup can follow.</b>
     *
     * <p>JSDoc borrows javadoc's spelling for it, so without this the braces reach the reader as text.
     * The {@code js:} scheme is what {@code EditorLanguageFeatures} strips before handing the rest to
     * whichever engine owns the document — nothing in between has to know what a reference looks like
     * in either language.</p>
     */
    @Test
    public void anInlineTagBecomesALink() {
        String docs = docAt(""
                + "/** Prefer {@link other} instead. */\n"
                + "function old() { }\n"
                + "function other() { }\n"
                + "old();\n", "old();");
        assertNotNull(docs);
        assertTrue(docs, docs.contains("<a href=\"js:other\">other</a>"));
    }

    /** A comment that is only tags still renders, and one that says nothing renders nothing. */
    @Test
    public void anEmptyCommentSaysNothing() {
        assertNull("an empty comment must not leave a band with nothing in it",
                docAt("/** */\nfunction bare() { }\nbare();\n", "bare();"));

        String tagsOnly = docAt(""
                + "/**\n"
                + " * @since 2.0\n"
                + " */\n"
                + "function dated() { }\n"
                + "dated();\n", "dated();");
        assertNotNull("a comment of nothing but tags still has something to say", tagsOnly);
        assertTrue(tagsOnly, tagsOnly.contains("<dt>Since:</dt>"));
    }

    /** A variable's comment reaches it too — the tag grammar already found those. */
    @Test
    public void aVariableIsDocumentedAsWell() {
        String docs = docAt(""
                + "/** How many times to try. */\n"
                + "var retries = 3;\n"
                + "console.log(retries);\n", "retries);");
        assertNotNull(docs);
        assertTrue(docs, docs.contains("How many times to try."));
    }

    // ── Java, reached from JavaScript ───────────────────────────────────────────────────────────

    /**
     * <b>A Java MEMBER carries the same javadoc its TYPE does.</b>
     *
     * <p>Asserted as a relationship rather than against quoted text, because whether anything quotes at
     * all depends on the host: {@code src.zip} ships with a JDK and not with a JRE, so a machine with only
     * a runtime assembles signatures and has no comments to show. The <em>relationship</em> holds either
     * way, and it is exactly what broke — {@code membersOf} carries no documentation (it answers with
     * hundreds of members for a completion list), so the hover's probe is the only thing that has it, and
     * the hover took the signature and the container off that probe and dropped the comment. A type
     * hovered from JavaScript showed its whole javadoc; a member of that same type showed a correct
     * container, a correct signature and nothing underneath — which reads as the member being
     * undocumented.</p>
     */
    @Test
    public void aJavaMemberCarriesTheSameJavadocItsTypeDoes() {
        String source = ""
                + "var names = new java.util.ArrayList();\n"
                + "names.add('first');\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 1L);
        SymbolInfo type = analysis.resolveAt(source.indexOf("ArrayList"));
        assertNotNull("the type itself did not resolve", type);
        Assume.assumeTrue("no attached Java sources on this host, so nothing quotes a comment",
                type.documentation() != null && !type.documentation().isEmpty());

        SymbolInfo member = analysis.resolveAt(source.indexOf("add("));
        assertNotNull("the member did not resolve", member);
        assertEquals("add", member.name());
        assertNotNull("the type quoted its javadoc and the member dropped it", member.documentation());
        assertFalse("the member's documentation came back empty", member.documentation().isEmpty());
    }

    /**
     * <b>A name inside an {@code import} hovers, though the parser never saw it.</b>
     *
     * <p>{@code JsImports} blanks every import statement before Rhino parses — it has to, or Rhino reads
     * {@code import a.b.C;} as an ES module declaration and the error poisons the whole file — so there is
     * no node at those offsets and {@code resolveAt} walked to nothing. The line <em>coloured</em>
     * correctly throughout, from the very spans this now reads, which is what made it look like hovering
     * was broken rather than absent.</p>
     */
    @Test
    public void anImportedTypeHoversAsItsType() {
        String source = ""
                + "import java.util.ArrayList;\n"
                + "var names = new ArrayList();\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 1L);
        SymbolInfo imported = analysis.resolveAt(source.indexOf("ArrayList"));
        assertNotNull("an import statement is blanked, so this needs a path of its own", imported);
        assertEquals("ArrayList", imported.name());
    }

    /**
     * <b>A PACKAGE segment of an import is not the type.</b>
     *
     * <p>The whole line names one type, so answering with it for any offset on the line is the easy thing
     * to do and is wrong: hovering the {@code java} of {@code java.util.ArrayList} would pop up
     * {@code ArrayList}'s documentation, which is worse than the nothing it says. Only the trailing
     * segment resolves, which is the same split {@code RhinoSemanticTokens.markImports} draws.</p>
     */
    @Test
    public void aPackageSegmentOfAnImportIsNotTheType() {
        String source = ""
                + "import java.util.ArrayList;\n"
                + "var names = new ArrayList();\n";
        Analysis analysis = JsLanguage.analyzer().analyze("Probe.js", source, 1L);
        SymbolInfo segment = analysis.resolveAt(source.indexOf("java.util"));
        assertTrue("a package segment answered with the imported type",
                segment == null || !"ArrayList".equals(segment.name()));
    }

    /**
     * <b>A tag nobody taught us keeps its own word and still takes the column's shape.</b>
     *
     * <p>JSDoc's tag set is open and plugins add to it, so an unknown tag is the ordinary case rather
     * than an error. Returning the bare word put a lowercase, colon-less label in a column of
     * {@code Since:} and {@code Throws:}, which reads as a rendering fault rather than as a tag we do
     * not know. The word is the author's; the capital and the colon belong to the column.</p>
     */
    @Test
    public void anUnknownTagKeepsItsNameAndTakesTheShape() {
        String docs = docAt(""
                + "/**\n"
                + " * Emits when done.\n"
                + " *\n"
                + " * @fires Session#complete\n"
                + " */\n"
                + "function finish() { }\n"
                + "finish();\n", "finish();");
        assertNotNull(docs);
        assertTrue("the author's own tag name was lost: " + docs, docs.contains("Fires:"));
        assertFalse("a bare lowercase heading was drawn beside the capitalised ones: " + docs,
                docs.contains("<dt>fires</dt>"));
    }
}
