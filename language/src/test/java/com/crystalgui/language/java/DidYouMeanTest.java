package com.crystalgui.language.java;

import com.crystalgui.text.SimilarNames;

import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * "Change to 'String'" — the near-miss corrections, and the ranking under them.
 *
 * <p>Asserted on the resulting text and on the compiler's verdict afterwards, as every correction is.
 * The extra thing worth pinning here is the <em>order</em>: with several candidates the popup shows one
 * inline, so which is first is what the user sees.</p>
 */
public class DidYouMeanTest extends FixFixture {

    private static List<String> titles(String source, String needle, String id) {
        List<String> titles = new ArrayList<>();
        for (CodeAction action : actionsIn(source, needle)) {
            if (id.equals(action.id())) titles.add(action.title());
        }
        return titles;
    }

    // ── The ranking itself ──────────────────────────────────────────────────────────────────────

    @Test
    public void distanceCountsATranspositionAsOne() {
        assertEquals(1, SimilarNames.distance("lsit", "list", 2));
        assertEquals(1, SimilarNames.distance("strimg", "string", 2));
        assertEquals(2, SimilarNames.distance("lst", "least", 2));
        assertEquals("cut off past the limit", 3, SimilarNames.distance("abc", "xyz", 2));
    }

    @Test
    public void rankingIsCappedCasedAndTotal() {
        List<String> ranked = SimilarNames.rank("string",
                List.of("String", "string2", "Strong", "STRING", "spring", "strings", "sting", "st"));
        assertEquals("String", ranked.get(0));                       // distance 0, case differs -- but first
        assertTrue("capped at five: " + ranked, ranked.size() <= 5);
        assertFalse("a name too far away is not offered", ranked.contains("st"));
        assertEquals("the same input ranks the same way twice", ranked,
                SimilarNames.rank("string",
                        List.of("String", "string2", "Strong", "STRING", "spring", "strings", "sting", "st")));
    }

    @Test
    public void aShortNameToleratesOnlyOneKeystroke() {
        assertEquals(List.of("Map"), SimilarNames.rank("Mpa", List.of("Map", "Mode", "Mop", "Mapping")));
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMisspeltJavaLangTypeIsRenamedWithoutAnImport() {
        String source = ""
                + "public class Script {\n"
                + "    Strin name;\n"
                + "}\n";
        assertReported(source, IProblem.UndefinedType);
        assertEquals(List.of("Change to 'String'"), titles(source, "Strin", DidYouMeanCorrections.CHANGE_TYPE));
        assertFix(source, "Strin", DidYouMeanCorrections.CHANGE_TYPE, ""
                + "public class Script {\n"
                + "    String name;\n"
                + "}\n");
        assertResolves(source, "Strin", DidYouMeanCorrections.CHANGE_TYPE, IProblem.UndefinedType);
    }

    /**
     * <b>Renaming to a type that is not imported imports it</b> — otherwise the fix trades one unresolved
     * name for another and looks like it worked. And two packages spelling the same simple name give two
     * actions that say which is which.
     */
    @Test
    public void aMisspeltUnimportedTypeIsRenamedAndImported() {
        String source = ""
                + "package demo;\n"
                + "public class Script {\n"
                + "    Lst<String> names;\n"
                + "}\n";
        List<String> titles = titles(source, "Lst", DidYouMeanCorrections.CHANGE_TYPE);
        assertEquals(List.of("Change to 'List' (java.util)", "Change to 'List' (java.awt)"), titles);

        CodeAction util = offeredTitled(source, "Lst", "Change to 'List' (java.util)");
        assertNotNull(util);
        assertEquals(""
                + "package demo;\n"
                + "import java.util.List;\n"
                + "public class Script {\n"
                + "    List<String> names;\n"
                + "}\n", applied(source, util));
        assertResolves(source, "Lst", DidYouMeanCorrections.CHANGE_TYPE, IProblem.UndefinedType);
    }

    @Test
    public void aTypeDeclaredInThisFileIsACandidateAndNeedsNoImport() {
        String source = ""
                + "public class Script {\n"
                + "    Helpr helper;\n"
                + "}\n"
                + "class Helper { }\n";
        assertFix(source, "Helpr", DidYouMeanCorrections.CHANGE_TYPE, ""
                + "public class Script {\n"
                + "    Helper helper;\n"
                + "}\n"
                + "class Helper { }\n");
        assertResolves(source, "Helpr", DidYouMeanCorrections.CHANGE_TYPE, IProblem.UndefinedType);
    }

    // ── Methods ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMisspeltMethodOnAReceiverIsRenamed() {
        String source = ""
                + "public class Script {\n"
                + "    int go(String s) { return s.lenght(); }\n"
                + "}\n";
        assertReported(source, IProblem.UndefinedMethod);
        assertEquals("Change to 'length()'",
                offered(source, "lenght", DidYouMeanCorrections.CHANGE_METHOD).title());
        assertFix(source, "lenght", DidYouMeanCorrections.CHANGE_METHOD, ""
                + "public class Script {\n"
                + "    int go(String s) { return s.length(); }\n"
                + "}\n");
        assertResolves(source, "lenght", DidYouMeanCorrections.CHANGE_METHOD, IProblem.UndefinedMethod);
    }

    /** An unqualified call looks in the enclosing type and its supertypes. */
    @Test
    public void aMisspeltUnqualifiedMethodLooksInTheEnclosingTypeAndItsSupers() {
        String source = ""
                + "public class Script {\n"
                + "    void helper() { }\n"
                + "    void go() { helpr(); tostring(); }\n"
                + "}\n";
        assertFix(source, "helpr", DidYouMeanCorrections.CHANGE_METHOD, ""
                + "public class Script {\n"
                + "    void helper() { }\n"
                + "    void go() { helper(); tostring(); }\n"
                + "}\n");
        assertEquals("inherited from Object", "Change to 'toString()'",
                offered(source, "tostring", DidYouMeanCorrections.CHANGE_METHOD).title());
    }

    /**
     * <b>Arity promotes within equal distance.</b> {@code s.substrng(1)} is one keystroke from
     * {@code substring} whichever overload; a candidate with no overload of the call's arity may still be
     * offered but never first.
     */
    @Test
    public void aCandidateWithTheCallsArityRanksFirst() {
        String source = ""
                + "public class Script {\n"
                + "    void a(int x) { }\n"
                + "    void ab() { }\n"
                + "    void go() { ax(1); }\n"
                + "}\n";
        List<String> titles = titles(source, "ax", DidYouMeanCorrections.CHANGE_METHOD);
        assertFalse(titles.isEmpty());
        assertEquals("'a(int)' takes one argument like the call; 'ab()' does not",
                "Change to 'a()'", titles.get(0));
    }

    // ── Names ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMisspeltLocalIsRenamedToTheLocalDeclaredAboveIt() {
        String source = ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        int count = 1;\n"
                + "        return cont;\n"
                + "    }\n"
                + "}\n";
        assertReported(source, IProblem.UnresolvedVariable);
        assertFix(source, "cont;", DidYouMeanCorrections.CHANGE_NAME, ""
                + "public class Script {\n"
                + "    int go() {\n"
                + "        int count = 1;\n"
                + "        return count;\n"
                + "    }\n"
                + "}\n");
        assertResolves(source, "cont;", DidYouMeanCorrections.CHANGE_NAME, IProblem.UnresolvedVariable);
    }

    @Test
    public void aMisspeltFieldThroughAReceiverIsRenamed() {
        String source = ""
                + "public class Script {\n"
                + "    int total;\n"
                + "    int go(Script other) { return other.totl + this.totla; }\n"
                + "}\n";
        assertReported(source, IProblem.UndefinedField);
        assertFix(source, "totl ", DidYouMeanCorrections.CHANGE_NAME, ""
                + "public class Script {\n"
                + "    int total;\n"
                + "    int go(Script other) { return other.total + this.totla; }\n"
                + "}\n");
        assertFix(source, "totla", DidYouMeanCorrections.CHANGE_NAME, ""
                + "public class Script {\n"
                + "    int total;\n"
                + "    int go(Script other) { return other.totl + this.total; }\n"
                + "}\n");
    }

    /** Nothing near means nothing offered — the designed answer, not a gap. */
    @Test
    public void aNameNothingIsNearOffersNothing() {
        String source = "public class Script { void go() { xyzzyq(); } }\n";
        assertTrue(titles(source, "xyzzyq", DidYouMeanCorrections.CHANGE_METHOD).isEmpty());
    }
}
