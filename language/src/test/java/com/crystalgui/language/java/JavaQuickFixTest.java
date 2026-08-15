package com.crystalgui.language.java;

import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The error → fix table, asserted on the <b>text it produces</b> and on what the compiler says afterwards.
 *
 * <p>Three questions per correction, in the order {@link FixFixture} puts them: is the diagnostic reported
 * at all, does the edit produce exactly the intended text, and is the problem gone once it is applied
 * without anything new breaking. The first is invisible from a fix's own code and the third is the only
 * reader of the output with no stake in it.</p>
 *
 * <p>{@code IProblem} constants are named here rather than written as numbers. They are inlined by the
 * compiler, so this file carries the literal and never reaches for JDT at runtime — see the
 * {@code testCompileOnly} note in the build script.</p>
 */
public class JavaQuickFixTest extends FixFixture {

    // ── Unused imports ──────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnusedImportIsRemovedWholeLine() {
        String source = ""
                + "import java.util.List;\n"
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, String> go() { return null; }\n"
                + "}\n";
        assertReported(source, IProblem.UnusedImport);

        CodeAction fix = offered(source, "java.util.List", UnusedCorrections.REMOVE_IMPORT);
        assertNotNull("no fix offered", fix);
        assertTrue("the one fix for a problem should be the preferred one", fix.preferred());
        assertEquals(CodeActionKind.QUICK_FIX, fix.kind());

        // THE WHOLE LINE, terminator included. Deleting only the node leaves an empty line behind, and a
        // file tidied that way slowly fills with them.
        assertFix(source, "java.util.List", UnusedCorrections.REMOVE_IMPORT, ""
                + "import java.util.Map;\n"
                + "public class Script {\n"
                + "    Map<String, String> go() { return null; }\n"
                + "}\n");
        assertResolves(source, "java.util.List", UnusedCorrections.REMOVE_IMPORT,
                IProblem.UnusedImport);
    }

    /**
     * <b>The only import of a package-less file leaves no blank line behind.</b>
     *
     * <p>The shape a script actually has, and the one JDT's rewriter gets wrong: it removes a list's
     * elements together with the separators <em>between</em> them, so emptying a list that nothing
     * precedes strands the final terminator. That is why the import region is not described to
     * {@code Rewrites} — see its class note — and this is the case that says so.</p>
     */
    @Test
    public void theOnlyImportOfAPackagelessFileLeavesNoBlankLine() {
        String source = ""
                + "import java.util.List;\n"
                + "public class Script { }\n";
        assertFix(source, "java.util.List", UnusedCorrections.REMOVE_IMPORT,
                "public class Script { }\n");
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
        CodeAction batch = offered(source, "java.util.List", UnusedCorrections.REMOVE_IMPORTS);
        assertNotNull("no batch offered", batch);
        assertFalse("the batch must not be the default", batch.preferred());
        assertEquals(CodeActionKind.SOURCE, batch.kind());
        assertEquals("public class Script { }\n", applied(source, batch));
    }

    // ── Unused locals and fields ────────────────────────────────────────────────────────────────

    @Test
    public void anUnusedLocalIsRemovedAndNamedInTheTitle() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        String s = \"fah\";\n"
                + "    }\n"
                + "}\n";
        assertReported(source, IProblem.LocalVariableIsNeverUsed);
        assertEquals("Remove variable 's'",
                offered(source, "String s", UnusedCorrections.REMOVE_LOCAL).title());
        assertFix(source, "String s", UnusedCorrections.REMOVE_LOCAL, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "String s", UnusedCorrections.REMOVE_LOCAL,
                IProblem.LocalVariableIsNeverUsed);
    }

    @Test
    public void anUnusedPrivateFieldIsRemoved() {
        String source = ""
                + "public class Script {\n"
                + "    private int count;\n"
                + "    void go() { }\n"
                + "}\n";
        assertReported(source, IProblem.UnusedPrivateField);
        assertEquals("Remove field 'count'",
                offered(source, "private int count", UnusedCorrections.REMOVE_FIELD).title());
        assertFix(source, "private int count", UnusedCorrections.REMOVE_FIELD, ""
                + "public class Script {\n"
                + "    void go() { }\n"
                + "}\n");
        assertResolves(source, "private int count", UnusedCorrections.REMOVE_FIELD,
                IProblem.UnusedPrivateField);
    }

    /**
     * <b>A declaration with more than one name loses only the unused one.</b>
     *
     * <p>This was refused for as long as a fix was a computed range: deleting the statement would take
     * {@code a} with it, and a fix that silently removes working code is worse than no fix. The reasoning
     * never changed — what changed is that removing one element of a list is now something the edit can
     * <em>say</em>, so the comma goes with {@code b} and nothing in the correction knows where it was.</p>
     */
    @Test
    public void aMultiNameDeclarationLosesOnlyTheUnusedName() {
        String source = ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        int a = 1, b = 2;\n"
                + "        return a;\n"
                + "    }\n"
                + "}\n";
        assertFix(source, "b = 2", UnusedCorrections.REMOVE_LOCAL, ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        int a = 1;\n"
                + "        return a;\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "b = 2", UnusedCorrections.REMOVE_LOCAL,
                IProblem.LocalVariableIsNeverUsed);
    }

    // ── Whole members ───────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnusedPrivateMethodIsRemoved() {
        String source = ""
                + "public class Script {\n"
                + "    private void helper() { }\n"
                + "    void go() { }\n"
                + "}\n";
        assertReported(source, IProblem.UnusedPrivateMethod);
        assertFix(source, "private void helper", UnusedCorrections.REMOVE_METHOD, ""
                + "public class Script {\n"
                + "    void go() { }\n"
                + "}\n");
        assertResolves(source, "private void helper", UnusedCorrections.REMOVE_METHOD,
                IProblem.UnusedPrivateMethod);
    }

    @Test
    public void anUnusedPrivateConstructorIsRemovedAndCalledAConstructor() {
        String source = ""
                + "public class Script {\n"
                + "    private Script(int unusedParameter) { }\n"
                + "    Script() { }\n"
                + "}\n";
        assertReported(source, IProblem.UnusedPrivateConstructor);
        assertEquals("a constructor is not a method",
                "Remove constructor 'Script'",
                offered(source, "private Script(", UnusedCorrections.REMOVE_CONSTRUCTOR).title());
        assertResolves(source, "private Script(", UnusedCorrections.REMOVE_CONSTRUCTOR,
                IProblem.UnusedPrivateConstructor);
    }

    /**
     * <b>The noun is read from the declaration, not carried as a parameter.</b>
     *
     * <p>Three registrations share one correction, so a fixed word would have made a private interface
     * offer to remove a "class". The kind is sitting on the node.</p>
     */
    @Test
    public void anUnusedPrivateInterfaceIsCalledAnInterface() {
        String source = ""
                + "public class Script {\n"
                + "    private interface Helper { }\n"
                + "    void go() { }\n"
                + "}\n";
        assertReported(source, IProblem.UnusedPrivateType);
        assertEquals("Remove interface 'Helper'",
                offered(source, "private interface Helper", UnusedCorrections.REMOVE_TYPE).title());
        assertFix(source, "private interface Helper", UnusedCorrections.REMOVE_TYPE, ""
                + "public class Script {\n"
                + "    void go() { }\n"
                + "}\n");
    }

    // ── Nothing at all ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Only reported because the policy switches it on</b> — ECJ leaves {@code emptyStatement} at
     * {@code ignore}, so without that entry this correction would be dead code that looks alive.
     */
    @Test
    public void aSuperfluousSemicolonIsRemovedWithItsLine() {
        String source = ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "        ;\n"
                + "    }\n"
                + "}\n";
        assertReported(source, IProblem.SuperfluousSemicolon);
        assertFix(source, ";", UnusedCorrections.REMOVE_SEMICOLON, ""
                + "public class Script {\n"
                + "    void go() {\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, ";", UnusedCorrections.REMOVE_SEMICOLON, IProblem.SuperfluousSemicolon);
    }

    // ── Unresolved types ────────────────────────────────────────────────────────────────────────

    /**
     * <b>An unresolved type offers one action per candidate, and none of them is preferred.</b>
     *
     * <p>The first problem whose answer is several actions rather than one, which is the case the merge
     * and the "More actions…" list were built for. None is preferred on purpose: with {@code List} on the
     * classpath twice, defaulting to whichever the index returned first is a coin toss that edits the
     * file. IntelliJ makes you pick too.</p>
     *
     * <p>Keyed on the title rather than the id, and this is the one correction where that is right — the
     * candidates are one correction offering alternatives, so they share an id. @see CodeAction#id()</p>
     */
    @Test
    public void anUnresolvedTypeOffersOneImportPerCandidate() {
        String source = ""
                + "package demo;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n";
        assertReported(source, IProblem.UndefinedType);

        CodeAction utilImport = offeredTitled(source, "List<String>", "Import 'java.util.List'");
        CodeAction awtImport = offeredTitled(source, "List<String>", "Import 'java.awt.List'");
        assertNotNull("no import offered", utilImport);
        assertNotNull("only one candidate was offered", awtImport);
        assertFalse("with two candidates neither may be the default", utilImport.preferred());
        assertEquals("both candidates are the same correction",
                utilImport.id(), awtImport.id());

        // AFTER THE PACKAGE, never before it -- the one placement that turns a fix into a new error.
        assertEquals(""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n", applied(source, utilImport));
        assertResolves(source, "List<String>", ImportCorrections.ADD_IMPORT, IProblem.UndefinedType);
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
                offeredTitled(source, "List<String>", "Import 'java.util.List'"));
    }

    // ── The designed empty answer ───────────────────────────────────────────────────────────────

    /** An id nothing knows about offers nothing — the designed answer, not a gap. */
    @Test
    public void anUnknownProblemOffersNothing() {
        String source = "public class Script { void go() { undefined(); } }\n";
        List<CodeAction> actions = actionsIn(source, "undefined");
        assertTrue("an unhandled problem must contribute nothing: " + actions, actions.isEmpty());
    }
}
