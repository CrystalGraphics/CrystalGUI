package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.ui.elements.chrome.ProblemsPanel;
import com.crystalgui.ui.elements.UIText;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Problems panel — a table over a {@link DiagnosticSet}, reporting what the user chose.
 */
public class ProblemsPanelTest extends UiTestBase {

    private UIWindow window;
    private ProblemsPanel panel;

    private ProblemsPanel build() {
        panel = new ProblemsPanel();
        panel.layout(l -> l.width(320).height(200));

        UIElement root = new UIElement().layout(l -> l.width(320).height(220));
        root.addChild(panel);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(640, 440);
        settle();
        return panel;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private static Diagnostic errorOn(int row, String message) {
        return Diagnostic.error(new TextPoint(row, 0), new TextPoint(row, 3), message);
    }

    @Test
    public void anUnboundPanelShowsNothing() {
        build();
        assertTrue(panel.visibleProblems().isEmpty());
    }

    @Test
    public void bindingShowsTheSetsContents() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(errorOn(1, "first"), errorOn(4, "second")));

        panel.bindTo(set);
        settle();

        assertEquals(2, panel.visibleProblems().size());
    }

    /** The panel follows its set: a recompile that changes the problems changes the table with no
     * further call from the caller. */
    @Test
    public void theTableFollowsLaterChangesToTheBoundSet() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        panel.bindTo(set);
        settle();
        assertTrue(panel.visibleProblems().isEmpty());

        set.setAll(List.of(errorOn(2, "appeared")));
        settle();
        assertEquals(1, panel.visibleProblems().size());

        set.clear();
        settle();
        assertTrue(panel.visibleProblems().isEmpty());
    }

    /**
     * After a rebind the panel shows the new set and nothing from the old one.
     *
     * <p><b>This does not test that the old listener was disconnected</b>, and an earlier version of it
     * claimed to. It cannot: {@code refresh()} always reads the currently bound set, so a leaked listener
     * firing rebuilds from the right source and the contents come out identical either way — the
     * assertion passed with {@code binding.disconnect()} deleted. The disconnect's real cost is retention
     * and duplicated work, neither visible from out here. What is left is still worth pinning: that
     * re-pointing works at all, and that the old set's <em>contents</em> never leak into the view.</p>
     */
    @Test
    public void rebindingShowsTheNewSetAndNothingFromTheOld() {
        build();
        DiagnosticSet first = new DiagnosticSet();
        DiagnosticSet second = new DiagnosticSet();
        first.setAll(List.of(errorOn(1, "from the abandoned document")));
        panel.bindTo(first);
        settle();
        assertEquals(1, panel.visibleProblems().size());

        panel.bindTo(second);
        settle();
        assertTrue("the old set's contents survived the rebind", panel.visibleProblems().isEmpty());

        second.setAll(List.of(errorOn(3, "from the live one")));
        settle();
        assertEquals(1, panel.visibleProblems().size());
        assertEquals("from the live one", panel.visibleProblems().get(0).message());
    }

    @Test
    public void bindingToNullClearsAndDetaches() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(errorOn(1, "x")));
        panel.bindTo(set);
        settle();
        assertFalse(panel.visibleProblems().isEmpty());

        panel.bindTo(null);
        settle();
        assertTrue(panel.visibleProblems().isEmpty());

        set.setAll(List.of(errorOn(2, "y")));
        settle();
        assertTrue("a detached panel must not follow its old set", panel.visibleProblems().isEmpty());
    }

    /** Activating a row reports it. The panel deliberately does not navigate — in a real workspace the
     * problem may be in a file that is not open, which is a workspace-level act. */
    @Test
    public void activatingARowReportsThatProblem() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(errorOn(1, "first"), errorOn(4, "second")));
        panel.bindTo(set);
        settle();

        List<Diagnostic> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);

        panel.table().onRowActivated.emit(1);

        assertEquals(1, chosen.size());
        assertEquals("second", chosen.get(0).message());
    }

    /** An out-of-range activation must not throw — the table's index and the model can disagree for a
     * frame after a recompile shrinks the set. */
    @Test
    public void anOutOfRangeActivationIsIgnored() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(errorOn(1, "only one")));
        panel.bindTo(set);
        settle();

        List<Diagnostic> chosen = new ArrayList<>();
        panel.onProblemChosen.connect(chosen::add);

        panel.table().onRowActivated.emit(7);

        assertTrue(chosen.isEmpty());
    }

    /**
     * <b>A row is severity, message and line — packed, not columned.</b>
     *
     * <p>The panel used to be a three-column table with a header. IntelliJ packs the same information into
     * one line, and it is the better shape here: the Line column spent its width on a four-character
     * number, and the Severity column existed to label an icon. The two tests that pinned those columns
     * went with them — they described a design that was deliberately replaced.</p>
     *
     * <p>The severity reaches the sheet as a <b>class</b>, never a colour from Java, which is what lets one
     * palette serve these rows and the notification cards.</p>
     */
    @Test
    public void aRowCarriesSeverityMessageAndLine() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(
                new Diagnostic(new TextPoint(142, 0), new TextPoint(142, 0), DiagnosticSeverity.WARNING,
                        "Field can be converted to a local variable", "java", null)));
        panel.bindTo(set);
        settle();

        List<UIElement> rows = panel.getElementsByClassName(ProblemsPanel.ROW_CLASS);
        assertFalse("no row was realised", rows.isEmpty());
        UIElement row = rows.get(0);

        UIElement icon = row.querySelector("." + ProblemsPanel.ICON_CLASS);
        assertNotNull(icon);
        assertTrue("severity must reach the sheet as a class: " + icon.getClasses(),
                icon.hasClass(ProblemsPanel.SEVERITY_PREFIX + "warning"));
        assertEquals("Field can be converted to a local variable",
                ((UIText) row.querySelector("." + ProblemsPanel.MESSAGE_CLASS)).getText());
        assertEquals("one-based, like every editor and compiler",
                ":143", ((UIText) row.querySelector("." + ProblemsPanel.LINE_CLASS)).getText());
    }

    /**
     * A problem with nowhere to point renders no line at all.
     *
     * <p>A shader graph reports about a node, not a row. With the Line column gone there is no empty cell
     * to fill, so the row simply ends after the message — which is better than the {@code —} the column
     * needed.</p>
     */
    @Test
    public void aProblemWithNoPositionShowsNoLine() {
        build();
        DiagnosticSet set = new DiagnosticSet();
        set.setAll(List.of(
                new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION, DiagnosticSeverity.ERROR,
                        "The graph has no Output node", "shadergraph", "node-1")));
        panel.bindTo(set);
        settle();

        UIElement row = panel.getElementsByClassName(ProblemsPanel.ROW_CLASS).get(0);
        assertEquals("", ((UIText) row.querySelector("." + ProblemsPanel.LINE_CLASS)).getText());
    }
}
