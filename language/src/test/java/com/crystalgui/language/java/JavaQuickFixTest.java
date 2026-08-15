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
            return analysis.codeActionsIn(at, at + needle.length());
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
}
