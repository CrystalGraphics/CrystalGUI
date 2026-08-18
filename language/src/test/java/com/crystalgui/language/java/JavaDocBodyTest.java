package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineHost;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.language.java.classpath.HostClasspath;
import com.crystalgui.text.lang.SymbolInfo;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

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
     * <b>An inline tag renders as its subject.</b> {@code {@link List#add}} is information; the braces
     * are markup for a renderer that does not exist yet, and showing them shows markup to a reader.
     */
    @Test
    public void inlineTagsBecomeTheirSubjectAndHtmlIsStripped() {
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
        assertTrue("HTML reached the reader: <" + docs + ">", !docs.contains("<b>"));
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
}
