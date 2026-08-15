package com.crystalgui.language.java;

import com.crystalgui.language.engine.EngineBand;
import com.crystalgui.language.engine.EngineSource;
import com.crystalgui.language.engine.JavaEngine;
import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The error → fix table, asserted on the <b>text it produces</b> rather than on the action's title.
 *
 * <p>A title is prose; the edit is the thing that touches the file. These apply the {@link ChangeSet} to
 * the fixture and compare the result, because that is the only assertion that catches the failure mode
 * that matters — a fix whose range is one character out leaves {@code import ;} behind and still passes
 * anything checking that an action was offered.</p>
 */
public class JavaQuickFixTest {

    /** A stand-in classpath index — the real one is built from jars and is not this test's subject. */
    private static final java.util.function.Function<String, List<String>> CANDIDATES = name ->
            "List".equals(name) ? List.of("java.util.List", "java.awt.List") : List.of();

    private JavaEngine engine;
    private SourceAnalyzer analyzer;

    // THROUGH JavaEngine, never by constructing EcjSourceAnalyzer here: JDT lives behind the band loader,
    // so a direct `new` compiles and then dies on NoClassDefFoundError for a class the file imports.
    @Before
    public void openEngine() throws Exception {
        EngineBand band = EngineBand.detect();
        EngineSource source = EngineSource.ofPathList(
                System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion()));
        Assume.assumeTrue("no jars supplied for band " + band + "; skipping",
                !source.jarsFor(band).isEmpty());
        engine = JavaEngine.open(band, source);
        analyzer = engine.analyzer();
    }

    @After
    public void closeEngine() throws java.io.IOException {
        if (engine != null) engine.close();
    }

    private List<CodeAction> actionsIn(String source, String needle) {
        SourceAnalyzer.Analysis analysis = analyzer.analyze("Script", source, List.of(), 8, 7L);
        int at = source.indexOf(needle);
        if (at < 0) throw new IllegalArgumentException("no '" + needle + "' in the fixture");
        try {
            return analysis.codeActionsIn(at, at + needle.length(), CANDIDATES);
        } finally {
            analysis.close();
        }
    }

    private static CodeAction titled(List<CodeAction> actions, String title) {
        for (CodeAction action : actions) {
            if (action.title().equals(title)) return action;
        }
        return null;
    }

    /** What {@code edit} does to {@code source} — the only assertion worth making about a fix. */
    private static String applied(String source, CodeAction action) {
        assertNotNull("no edit on <" + action.title() + ">", action.edit());
        StringBuilder out = new StringBuilder(source);
        List<com.crystalgui.text.Change> changes = action.edit().changes();
        for (int i = changes.size() - 1; i >= 0; i--) {          // back to front, so offsets stay valid
            com.crystalgui.text.Change change = changes.get(i);
            out.replace(change.from(), change.to(), change.insert());
        }
        return out.toString();
    }

    @Test
    public void anUnusedImportIsRemovedWholeLine() {
        String source = ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, String> go() { return null; }\n"
                + "}\n";
        List<CodeAction> actions = actionsIn(source, "java.util.List");
        CodeAction fix = titled(actions, "Remove unused import");
        assertNotNull("no fix offered: " + actions, fix);
        assertTrue("the one fix for a problem should be the preferred one", fix.preferred());
        assertEquals(CodeActionKind.QUICK_FIX, fix.kind());

        // THE WHOLE LINE, terminator included. Deleting only the node leaves an empty line behind, and a
        // file tidied that way slowly fills with them.
        assertEquals(""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, String> go() { return null; }\n"
                + "}\n", applied(source, fix));
    }

    /**
     * <b>The batch is a different intention, not the same one with a count.</b>
     *
     * <p>You either meant this line or you meant to tidy the file. It is deliberately not preferred: a fix
     * that edits lines you were not looking at should be chosen rather than defaulted to.</p>
     */
    @Test
    public void severalUnusedImportsAlsoOfferTheBatch() {
        String source = ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "import java.util.Set;\n"
                + "public class Script { }\n";
        List<CodeAction> actions = actionsIn(source, "java.util.List");
        CodeAction batch = titled(actions, "Remove unused imports");
        assertNotNull("no batch offered: " + actions, batch);
        assertTrue("the batch must not be the default", !batch.preferred());
        assertEquals("import java.util.Map;\nimport java.util.Set;\npublic class Script { }\n".length()
                        - "import java.util.Map;\nimport java.util.Set;\n".length(),
                applied(source, batch).length());
        assertEquals("public class Script { }\n", applied(source, batch));
    }

    @Test
    public void anUnusedLocalIsRemovedAndNamedInTheTitle() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        String s = \"fah\";\n"
                + "    }\n"
                + "}\n";
        List<CodeAction> actions = actionsIn(source, "String s");
        CodeAction fix = titled(actions, "Remove variable 's'");
        assertNotNull("no fix offered: " + actions, fix);
        assertEquals(""
                + "public class Script {\n"
                + "    void go() {\n"
                + "    }\n"
                + "}\n", applied(source, fix));
    }

    /**
     * <b>A declaration with more than one name is refused.</b>
     *
     * <p>{@code int a, b;} with only {@code b} unused would lose {@code a} as well. A quick fix that
     * silently deletes working code is worse than no quick fix, so this offers nothing rather than
     * guessing at a rewrite.</p>
     */
    @Test
    public void aMultiNameDeclarationOffersNoRemoval() {
        String source = ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        int a = 1, b = 2;\n"
                + "        return a;\n"
                + "    }\n"
                + "}\n";
        assertNull("deleting the statement would take 'a' with it",
                titled(actionsIn(source, "b = 2"), "Remove variable 'b'"));
    }

    /** An id nothing knows about offers nothing — the designed answer, not a gap. */
    @Test
    public void anUnknownProblemOffersNothing() {
        String source = "public class Script { void go() { undefined(); } }\n";
        assertTrue(actionsIn(source, "undefined").isEmpty());
    }

    /**
     * <b>An unresolved type offers one action per candidate, and none of them is preferred.</b>
     *
     * <p>The first problem whose answer is several actions rather than one, which is the case the merge
     * and the "More actions…" list were built for and had never been exercised. None is preferred on
     * purpose: with {@code List} on the classpath twice, defaulting to whichever the index returned
     * first is a coin toss that edits the file. IntelliJ makes you pick too.</p>
     */
    @Test
    public void anUnresolvedTypeOffersOneImportPerCandidate() {
        String source = ""
                + "package demo;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n";
        List<CodeAction> actions = actionsIn(source, "List<String>");

        CodeAction utilImport = titled(actions, "Import 'java.util.List'");
        CodeAction awtImport = titled(actions, "Import 'java.awt.List'");
        assertNotNull("no import offered: " + actions, utilImport);
        assertNotNull("only one candidate was offered: " + actions, awtImport);
        assertTrue("with two candidates neither may be the default", !utilImport.preferred());

        // AFTER THE PACKAGE, never before it -- the one placement that turns a fix into a new error.
        assertEquals(""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n", applied(source, utilImport));
    }

    /** A candidate already imported is not offered again. */
    @Test
    public void anImportAlreadyPresentIsNotOffered() {
        String source = ""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n";
        assertNull("it is already imported",
                titled(actionsIn(source, "List<String>"), "Import 'java.util.List'"));
    }
}
