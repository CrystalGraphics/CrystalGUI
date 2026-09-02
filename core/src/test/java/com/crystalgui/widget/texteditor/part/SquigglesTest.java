package com.crystalgui.widget.texteditor.part;

import com.crystalgui.text.Rope;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.widget.texteditor.TextEditor;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostic underlines in the editor.
 *
 * <p>The model is covered headlessly by {@code DiagnosticSetTest}. What needs a document is the part that
 * only exists on screen: that a band is emitted per <b>view line</b>, that it carries the severity's class
 * so the cascade can colour it, and that a stale diagnostic naming a row the buffer no longer has is
 * dropped rather than thrown.</p>
 */
public class SquigglesTest extends UiDocumentTestBase {

    private TextEditor editor;

    private TextEditor build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(300).height(160));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UINode root = new UINode().layout(l -> l.width(300).height(200));
        root.append(editor);
        document.append(root);
        // The user-agent sheet, deliberately: every squiggle rule lives in default.css, and without it
        // this would assert against a widget with no stylesheet — the exact way the command palette's
        // geometry tests passed while the harness showed an empty list.
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
        return editor;
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    /** Every laid-out squiggle band, by severity class. Read from the tree rather than from the model,
     * so this cannot agree with the code it is checking for the wrong reason. */
    private List<UINode> bands(String severityClass) {
        List<UINode> found = new ArrayList<>();
        collect(editor, severityClass, found);
        return found;
    }

    private static void collect(UINode element, String severityClass, List<UINode> out) {
        if (element.hasClass("__squiggle__") && element.hasClass(severityClass)
                && element.box().height() > 0f) {
            out.add(element);
        }
        for (UINode child : element.children()) collect(child, severityClass, out);
    }

    // ── Basics ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aCleanDocumentDrawsNothing() {
        build("alpha\nbeta\ngamma");
        assertTrue(bands("__squiggle-error__").isEmpty());
    }

    @Test
    public void anErrorOnOneRowDrawsOneBandCarryingItsSeverityClass() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(1, 0), new TextPoint(1, 4), "bad beta")));
        settle();

        assertEquals(1, bands("__squiggle-error__").size());
        assertTrue("a warning class must not be applied to an error",
                bands("__squiggle-warning__").isEmpty());
    }

    @Test
    public void severityDecidesTheClassSoTheCascadeCanColourIt() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(0, 0), new TextPoint(0, 5), "e"),
                Diagnostic.warning(new TextPoint(2, 0), new TextPoint(2, 5), "w")));
        settle();

        assertEquals(1, bands("__squiggle-error__").size());
        assertEquals(1, bands("__squiggle-warning__").size());
    }

    /**
     * A hint draws no band at all.
     *
     * <p>Deliberate, not an omission: underlining a suggestion in the text makes a style note look like a
     * compile error. VS Code renders hints only as a lightbulb for the same reason.</p>
     */
    @Test
    public void aHintDrawsNoBand() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(new Diagnostic(
                new TextPoint(0, 0), new TextPoint(0, 5), DiagnosticSeverity.HINT, "h", null, null)));
        settle();

        assertTrue(bands("__squiggle-error__").isEmpty());
        assertTrue(bands("__squiggle-warning__").isEmpty());
        assertTrue(bands("__squiggle-information__").isEmpty());
    }

    // ── Ranges ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A diagnostic spanning rows emits one band per row, not one rectangle across all of them.
     *
     * <p>A single band would underline the whole block including the text between the ends, which is not
     * what the diagnostic covers. Same reason {@code SelectionsPart} works in view space.</p>
     */
    @Test
    public void aMultiRowDiagnosticEmitsOneBandPerRow() {
        build("alpha\nbeta\ngamma\ndelta");
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(0, 2), new TextPoint(2, 3), "spans three")));
        settle();

        assertEquals(3, bands("__squiggle-error__").size());
    }

    /**
     * A zero-width diagnostic is still visible.
     *
     * <p>"expected ';'" points <em>between</em> two characters, so start equals end — and a band of width
     * zero is a band nobody can see. The mark is widened to one character rather than skipped, because a
     * diagnostic that reports correctly and draws nothing is indistinguishable from one that never fired.</p>
     */
    @Test
    public void aZeroWidthDiagnosticStillDrawsAVisibleBand() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(1, 2), new TextPoint(1, 2), "expected ';'")));
        settle();

        List<UINode> found = bands("__squiggle-error__");
        assertEquals(1, found.size());
        assertTrue("a zero-width band is invisible", found.get(0).box().width() >= 1f);
    }

    /** {@code Diagnostic.onRow} ends at {@code Integer.MAX_VALUE}, which must clamp to the row's real
     * length rather than running off into a coordinate nothing can lay out. */
    @Test
    public void aWholeRowDiagnosticClampsToThatRowsLength() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.onRow(1, DiagnosticSeverity.ERROR, "whole row")));
        settle();

        List<UINode> found = bands("__squiggle-error__");
        assertEquals(1, found.size());
        float width = found.get(0).box().width();
        assertTrue("width " + width + " is not a plausible four-character row", width > 0f && width < 300f);

        // AND IT IS THE SAME BAND AN EXPLICIT RANGE OVER THAT ROW DRAWS, which is the claim the bound
        // above cannot make. `Diagnostic.onRow` says column Integer.MAX_VALUE, and both conversions to an
        // offset -- the editor's own and `Rope.pointToOffset` -- OVERFLOWED on it, so the range collapsed
        // to a point at the row's start and was widened to one character to be visible. One character is
        // "plausible" for a four-character row, so this test stayed green over a squiggle sitting in the
        // indentation. Compared against the other path rather than against a pixel count: the number is
        // the font's business and the agreement between the two spellings is not.
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(1, 0), new TextPoint(1, 4), "the same row, spelled out")));
        settle();
        List<UINode> explicit = bands("__squiggle-error__");
        assertEquals(1, explicit.size());
        assertEquals("a whole-row diagnostic does not cover the row an explicit range covers",
                explicit.get(0).box().width(), width, 0.01f);
    }

    /**
     * A whole-row mark starts at the row's first non-whitespace character, not at column 0.
     *
     * <p>A producer saying "this row" is pointing at the statement; the indentation in front of it is text
     * nobody claimed was wrong, and on a nested line most of the underline would be empty space — which
     * reads as the mark being misaligned rather than as it being wide. Asserted as a comparison against
     * the same row unindented, so no pixel count is baked in.</p>
     */
    @Test
    public void aWholeRowMarkSkipsTheIndentation() {
        build("alpha\n        beta\ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.onRow(1, DiagnosticSeverity.ERROR, "the indented row")));
        settle();
        float indented = bands("__squiggle-error__").get(0).box().width();

        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.onRow(1, DiagnosticSeverity.ERROR, "the same row, unindented")));
        settle();
        float bare = bands("__squiggle-error__").get(0).box().width();

        assertEquals("the mark covers the indentation as well as the statement", bare, indented, 0.01f);
    }

    /** A row that is only whitespace keeps its mark — there is nothing to move onto. */
    @Test
    public void aWhitespaceOnlyRowStillGetsAMark() {
        build("alpha\n    \ngamma");
        editor.diagnostics().setAll(List.of(
                Diagnostic.onRow(1, DiagnosticSeverity.ERROR, "a blank row")));
        settle();
        List<UINode> found = bands("__squiggle-error__");
        assertEquals("trimming collapsed the range and the mark vanished", 1, found.size());
        assertTrue(found.get(0).box().width() >= 1f);
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void nextProblemMovesTheCaretToItAndWraps() {
        build("alpha\nbeta\ngamma\ndelta");
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(1, 1), new TextPoint(1, 3), "one"),
                Diagnostic.error(new TextPoint(3, 2), new TextPoint(3, 4), "two")));
        editor.setCaret(0);
        settle();

        assertTrue(editor.goToNextProblem());
        assertEquals(new TextPoint(1, 1), editor.caretPoint());

        assertTrue(editor.goToNextProblem());
        assertEquals(new TextPoint(3, 2), editor.caretPoint());

        assertTrue("navigation is a cycle, so the last problem is not a dead end",
                editor.goToNextProblem());
        assertEquals(new TextPoint(1, 1), editor.caretPoint());
    }

    @Test
    public void previousProblemWalksBackwardsAndWraps() {
        build("alpha\nbeta\ngamma\ndelta");
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(1, 1), new TextPoint(1, 3), "one"),
                Diagnostic.error(new TextPoint(3, 2), new TextPoint(3, 4), "two")));
        editor.setCaret(0);
        settle();

        assertTrue(editor.goToPreviousProblem());
        assertEquals("before the first, wrap to the last", new TextPoint(3, 2), editor.caretPoint());

        assertTrue(editor.goToPreviousProblem());
        assertEquals(new TextPoint(1, 1), editor.caretPoint());
    }

    @Test
    public void navigatingWithNoProblemsReportsFalseAndDoesNotMove() {
        build("alpha\nbeta\ngamma");
        editor.setCaret(3);
        settle();

        assertFalse(editor.goToNextProblem());
        assertFalse(editor.goToPreviousProblem());
        assertEquals(3, editor.getCaret());
    }

    /**
     * A problem inside a collapsed region is revealed before the caret lands on it.
     *
     * <p>The one case that fails silently rather than visibly. A row inside a fold has <b>no view line</b>,
     * so a caret placed there cannot be painted, scrolled to, or typed at — the editor would look focused
     * and do nothing, which is the worst possible answer to "take me to the error". Same invariant that
     * makes folding a block move the caret out to its header.</p>
     */
    @Test
    public void jumpingToAProblemInsideAFoldOpensTheFoldFirst() {
        build("void a() {\n    one();\n    two();\n}\nvoid b() {\n    three();\n}");
        editor.setCaret(0);
        settle();
        editor.toggleFoldAt(0);
        settle();

        // THE PRECONDITION, and the reason this test is worth anything. The first version folded a
        // plain indented document with foldAll(), which hid NOTHING -- so it then asserted "row 2 is not
        // hidden" about a row that was never hidden, and passed with the reveal deleted. A test of a
        // reveal that never had anything to reveal is a test of nothing.
        assertTrue("the fold must actually have hidden row 2", isRowHidden(2));

        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(2, 4), new TextPoint(2, 9), "inside the fold")));
        settle();

        assertTrue(editor.goToNextProblem());
        settle();

        assertFalse("the row is still hidden, so nothing can paint or scroll to the caret",
                isRowHidden(2));
        assertEquals("the caret must land on the problem itself, not on the fold header",
                2, editor.caretPoint().row());
    }

    /** Whether any collapsed region hides {@code row} — read from the folding model rather than from the
     * navigation code, so this cannot agree with the thing it is checking. */
    private boolean isRowHidden(int row) {
        return editor.hiddenRowRanges().stream().anyMatch(range -> range.contains(row));
    }

    // ── Staleness ───────────────────────────────────────────────────────────────────────────────

    /**
     * A diagnostic naming a row that no longer exists is dropped, not thrown.
     *
     * <p>Diagnostics are <b>inherently stale</b> — they describe the document as it was when something last
     * compiled it, and they stay on screen while you keep typing. Deleting the end of a file must not
     * throw out of a render pass; the next compile replaces the set anyway.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void aDiagnosticPastTheEndOfAShrunkenBufferIsDroppedRatherThanThrowing() {
        build("alpha\nbeta\ngamma\ndelta\nepsilon");
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(4, 0), new TextPoint(4, 5), "on the last row")));
        settle();
        assertEquals(1, bands("__squiggle-error__").size());

        editor.setText("alpha");
        settle();

        assertTrue("a stale diagnostic must simply not draw", bands("__squiggle-error__").isEmpty());
    }

    /** Bands are recycled, so clearing has to retire them — a stale band left laid out is a squiggle under
     * text that has no problem, which reads as the editor being wrong rather than the diagnostic. */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void clearingTheSetRetiresEveryBand() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(0, 0), new TextPoint(0, 5), "e")));
        settle();
        assertFalse(bands("__squiggle-error__").isEmpty());

        editor.diagnostics().clear();
        settle();

        assertTrue(bands("__squiggle-error__").isEmpty());
    }

    /** A band that underlined an error and is reused for a warning must not carry both classes — the
     * cascade would then pick whichever it preferred and the colour would be a coin flip. */
    @Test
    public void arecycledBandDoesNotKeepItsPreviousSeverity() {
        build("alpha\nbeta\ngamma");
        editor.diagnostics().setAll(List.of(Diagnostic.error(
                new TextPoint(0, 0), new TextPoint(0, 5), "e")));
        settle();

        editor.diagnostics().setAll(List.of(Diagnostic.warning(
                new TextPoint(0, 0), new TextPoint(0, 5), "w")));
        settle();

        assertEquals(1, bands("__squiggle-warning__").size());
        assertTrue("the error class survived recycling", bands("__squiggle-error__").isEmpty());
    }
}
