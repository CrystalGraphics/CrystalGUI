package com.crystalgui.widget.texteditor.part;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The error stripe — a mark in the vertical scrollbar's groove for every problem in the document.
 *
 * <p>Everything here asserts <b>positions</b> rather than counts, because the failure this feature is
 * prone to is not "no marks" but "every mark in the same place". A stripe whose marks all sit at the top
 * of the groove looks populated, passes any count-based test, and is useless.</p>
 */
public class ErrorStripeTest extends UiDocumentTestBase {

    private static final int ROWS = 200;

    private TextEditor editor;

    /** A document long enough that the vertical scrollbar is genuinely needed — with no scrollbar there is
     * no groove, so there is nothing for a stripe to live in and every assertion here would be vacuous. */
    private TextEditor build() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ROWS; i++) text.append("line ").append(i).append('\n');

        editor = new TextEditor(text.toString());
        editor.layout(l -> l.width(300).height(160));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UINode root = new UINode().layout(l -> l.width(320).height(200));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
        return editor;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private List<UINode> marks() {
        List<UINode> found = new ArrayList<>();
        collect(editor.verticalScroller().track(), found);
        return found;
    }

    private static void collect(UINode element, List<UINode> out) {
        if (element.hasClass("__error-stripe__") && element.box().height() > 0f) {
            out.add(element);
        }
        for (UINode child : element.children()) collect(child, out);
    }

    private static Diagnostic errorOn(int row) {
        return Diagnostic.error(new TextPoint(row, 0), new TextPoint(row, 4), "problem on " + row);
    }

    /** The groove's own height — the precondition every positional assertion below rests on. */
    private float trackHeight() {
        return editor.verticalScroller().track().box().height();
    }

    // ── Preconditions ───────────────────────────────────────────────────────────────────────────

    /**
     * The scrollbar groove has real height before anything else is asserted.
     *
     * <p>Written first and deliberately: a zero-height groove makes every position below zero, so all the
     * ordering assertions would pass against an implementation that put every mark at the origin. This is
     * the guard the folding test in {@code SquigglesTest} was missing when its mutant survived.</p>
     */
    @Test
    public void theGrooveHasHeightSoPositionsMeanSomething() {
        build();
        assertTrue("no scrollbar groove — every positional assertion would be vacuous",
                trackHeight() > 10f);
    }

    // ── Marks ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aCleanDocumentDrawsNoMarks() {
        build();
        assertTrue(marks().isEmpty());
    }

    @Test
    public void oneProblemDrawsOneMarkCarryingItsSeverity() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(10)));
        settle();

        List<UINode> found = marks();
        assertEquals(1, found.size());
        assertTrue(found.get(0).hasClass("__error-stripe-error__"));
        assertFalse(found.get(0).hasClass("__error-stripe-warning__"));
    }

    /**
     * <b>The one that matters.</b> A problem near the end sits near the bottom of the groove.
     *
     * <p>Asserted as an ordering plus a magnitude, not an exact pixel: the point is that position is
     * <em>derived from</em> where the problem is. Marks all at the same place is the failure mode a count
     * assertion cannot see, and it is what "forgot to divide by the line count" looks like on screen.</p>
     */
    @Test
    public void aMarkSitsProportionallyDownTheGrooveAtItsProblemsPosition() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(2), errorOn(ROWS / 2), errorOn(ROWS - 2)));
        settle();

        List<UINode> found = marks();
        assertEquals(3, found.size());

        float top = found.get(0).box().y();
        float middle = found.get(1).box().y();
        float bottom = found.get(2).box().y();

        assertTrue("marks are not ordered down the groove: " + top + ", " + middle + ", " + bottom,
                top < middle && middle < bottom);
        assertTrue("the last problem's mark is not in the lower half of the groove",
                bottom > editor.verticalScroller().track().box().y() + trackHeight() / 2f);
    }

    /** The last problem in a file must still be visible — a mark placed at exactly 100% would hang off the
     * end of the groove and clip to nothing, making the final error the one you cannot see. */
    @Test
    public void aMarkOnTheFinalRowStaysInsideTheGroove() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(ROWS - 1)));
        settle();

        List<UINode> found = marks();
        assertEquals(1, found.size());
        float trackBottom = editor.verticalScroller().track().box().y() + trackHeight();
        var cache = found.get(0).box();
        assertTrue("the mark hangs off the bottom of the groove",
                cache.y() + cache.height() <= trackBottom + 0.5f);
        assertTrue("the mark has no height", cache.height() > 0f);
    }

    // ── Housekeeping ────────────────────────────────────────────────────────────────────────────

    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void clearingRetiresEveryMark() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(3), errorOn(30)));
        settle();
        assertEquals(2, marks().size());

        editor.diagnostics().clear();
        settle();

        assertTrue(marks().isEmpty());
    }

    @Test
    public void aRecycledMarkDoesNotKeepItsPreviousSeverity() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(5)));
        settle();

        editor.diagnostics().setAll(List.of(Diagnostic.warning(
                new TextPoint(5, 0), new TextPoint(5, 4), "now a warning")));
        settle();

        List<UINode> found = marks();
        assertEquals(1, found.size());
        assertTrue(found.get(0).hasClass("__error-stripe-warning__"));
        assertFalse("the error class survived recycling", found.get(0).hasClass("__error-stripe-error__"));
    }

    /** A hint draws no mark, exactly as it draws no squiggle — the stripe is for problems, and a
     * suggestion in the scrollbar makes a clean file look dirty at a glance. */
    @Test
    public void aHintDrawsNoMark() {
        build();
        editor.diagnostics().setAll(List.of(new Diagnostic(
                new TextPoint(9, 0), new TextPoint(9, 4), DiagnosticSeverity.HINT, "h", null, null)));
        settle();

        assertTrue(marks().isEmpty());
    }

    /** Stale diagnostics naming rows past the end are dropped rather than clamped: a mark pinned to the
     * bottom of the groove would claim there is a problem on the last line of the file. */
    @Test
    public void aDiagnosticPastTheEndOfTheDocumentDrawsNoMark() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(ROWS + 500)));
        settle();

        assertTrue(marks().isEmpty());
    }

    /**
     * A mark paints ABOVE the scrollbar thumb.
     *
     * <p>Under it, marks vanish for exactly the files where losing them costs most attention: a document
     * that mostly fits gives a thumb covering most of the groove, so most problems are simply not shown.
     * Observed in the harness with two of four marks missing, and invisible to every other assertion here
     * because the elements existed, were positioned correctly, and were painted underneath something.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void aMarkPaintsAboveTheScrollbarThumb() {
        build();
        editor.diagnostics().setAll(List.of(errorOn(10)));
        settle();

        UINode mark = marks().get(0);
        UINode thumb = findByClass(editor.verticalScroller(), "__thumb__");
        assertNotNull("no thumb to compare against", thumb);

        assertTrue("the stripe sits under the thumb and will be hidden by it",
                mark.getStyle().getGeneralGroup().zIndex()
                        > thumb.getStyle().getGeneralGroup().zIndex());
    }

    private static UINode findByClass(UINode element, String cssClass) {
        if (element.hasClass(cssClass)) return element;
        for (UINode child : element.children()) {
            UINode found = findByClass(child, cssClass);
            if (found != null) return found;
        }
        return null;
    }
}
