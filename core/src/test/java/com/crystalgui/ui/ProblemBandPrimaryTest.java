package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;
import com.crystalgui.ui.elements.editor.DocumentationPopup;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Which action the problem band puts in its inline slot.
 *
 * <p>Nothing asserted this rule, and the band shipped choosing <b>nothing</b> from a list of perfectly
 * good fixes: the slot required {@code preferred}, and a correction that is one of several plausible
 * answers deliberately does not set it — an import per candidate, a rename per near miss, add-throws
 * beside surround-with-try. So most real problems showed a message and a bare "More actions…", with the
 * single obvious fix one keystroke further away than before, and every test stayed green because the only
 * observable was the list of what was <em>available</em>.</p>
 */
public class ProblemBandPrimaryTest extends UiTestBase {

    private UIWindow window;
    private DocumentationPopup popup;

    @Before
    public void openAPopup() {
        popup = new DocumentationPopup();
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(400, 200);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private static final List<Diagnostic> ONE_PROBLEM = List.of(
            Diagnostic.error(new TextPoint(0, 0), new TextPoint(0, 5), "cannot resolve method 'helpr'"));

    private static CodeAction fix(String id, String title, boolean preferred) {
        return new CodeAction(id, title, CodeActionKind.QUICK_FIX, ChangeSet.empty(10), null, preferred, 0L);
    }

    private static CodeAction source(String id, String title) {
        return new CodeAction(id, title, CodeActionKind.SOURCE, ChangeSet.empty(10), null, false, 0L);
    }

    private void show(List<CodeAction> available) {
        popup.showProblemsAt(window, ONE_PROBLEM, 10f, 10f, 12f);
        popup.setProblem(ONE_PROBLEM, available);
        settle();
    }

    /** <b>The defect.</b> One obvious fix, not flagged preferred, must still be the one you can press. */
    @Test
    public void anUnpreferredFixStillTakesTheSlot() {
        show(List.of(fix("create", "Create method 'helper(int, String)'", false)));
        assertEquals("Create method 'helper(int, String)'", popup.primaryAction().title());
    }

    /** Rank decides, and {@code preferred} is what ranking means — so it still wins when present. */
    @Test
    public void aPreferredFixOutranksAnUnpreferredOne() {
        List<CodeAction> available = new java.util.ArrayList<>(List.of(
                fix("a", "Change to 'helper'", false),
                fix("b", "Remove it", true)));
        available.sort(CodeAction.ORDER);
        show(available);
        assertEquals("Remove it", popup.primaryAction().title());
    }

    /** With several equal candidates the first ranked one is offered — the rest are behind the menu. */
    @Test
    public void theFirstOfSeveralEqualCandidatesIsOffered() {
        show(List.of(
                fix("import", "Import 'java.util.List'", false),
                fix("import", "Import 'java.awt.List'", false)));
        assertEquals("Import 'java.util.List'", popup.primaryAction().title());
    }

    /**
     * <b>A whole-file action never takes the slot.</b> "Organize imports", the unused-import batch and
     * "Copy problem message" are all things to choose rather than default to — each of them argues that
     * for itself — and one keystroke from a hover is exactly defaulting to it.
     */
    @Test
    public void aWholeFileActionIsNeverTheOneYouCanPress() {
        show(List.of(
                source("organize", "Organize imports"),
                source("copy", "Copy problem message")));
        assertNull("nothing here is a fix for this problem", popup.primaryAction());
    }

    /** …but a fix beside them still is. */
    @Test
    public void aFixBesideWholeFileActionsIsStillOffered() {
        List<CodeAction> available = new java.util.ArrayList<>(List.of(
                source("copy", "Copy problem message"),
                fix("create", "Create method 'helper()'", false)));
        available.sort(CodeAction.ORDER);
        show(available);
        assertEquals("Create method 'helper()'", popup.primaryAction().title());
    }
    // ── Intentions, which have no problem behind them ───────────────────────────────────────────

    private static CodeAction refactor(String id, String title) {
        return new CodeAction(id, title, CodeActionKind.REFACTOR, ChangeSet.empty(10), null, false, 0L);
    }

    /**
     * <b>With no problem, the top action takes the slot whatever kind it is.</b>
     *
     * <p>The QUICK_FIX-only rule is really "the inline action must answer the message above it", and with
     * no message there is nothing for it to answer. An intention is the only reason the strip opened at
     * all — "Replace with lambda" on a convertible anonymous class — so refusing it there leaves a popup
     * showing a bare "More actions…" and the one thing on offer a keystroke further away. IntelliJ shows
     * exactly this inline, with Alt+Shift+Enter beside it.</p>
     */
    @Test
    public void anIntentionTakesTheSlotWhenThereIsNoProblem() {
        popup.showProblemsAt(window, List.of(), 10f, 10f, 12f);
        popup.setProblem(List.of(), List.of(refactor("lambda", "Replace with lambda")));
        settle();
        assertNotNull("nothing was put in the inline slot", popup.primaryAction());
        assertEquals("Replace with lambda", popup.primaryAction().title());
    }

    /**
     * <b>And a tidy still may not take it while there IS a problem.</b> That is the half of the rule that
     * has to survive: "Organize imports" beside a real error is a thing to choose, not to default to, and
     * one keystroke from a hover is exactly defaulting to it.
     */
    @Test
    public void aTidyStillCannotTakeTheSlotBesideAProblem() {
        show(List.of(source("organize", "Organize imports")));
        assertNull("a whole-file tidy is not the answer to this problem", popup.primaryAction());
    }
}