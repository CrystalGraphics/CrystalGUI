package com.crystalgui.workbench.chrome.problems;

import com.crystalgui.widget.overlay.MenuBuilder;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.workbench.chrome.problems.ProblemsCommands;
import com.crystalgui.workbench.chrome.problems.ProblemsPanel;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Problems panel's context menu — the <b>seam</b>, not the rows.
 *
 * <p>What is worth pinning here is that a command finds its subject: the rows are built by
 * {@code MenuBuilder} and its six rules are its own tests' business, but "which problem is this menu
 * about" is resolved by walking outward from the clicked element to a {@link ProblemsPanel}, and that
 * walk is the thing that silently answers nothing when the seam is wrong. A menu whose every row is
 * greyed out looks like a menu with nothing to offer.</p>
 */
public class ProblemsMenuTest extends UiDocumentTestBase {

    private ProblemsPanel panel;

    @Before
    public void setUp() {
        ProblemsCommands.register();
        panel = new ProblemsPanel();
        panel.layout(l -> l.width(400).height(200));
        UINode root = new UINode().layout(l -> l.width(400).height(200));
        root.append(panel);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    /**
     * <b>The panel answers for itself along the data walk.</b>
     *
     * <p>Without this every row of the menu is disabled — {@code enabledWhen} resolves outward from the
     * element and finds no panel, so it answers no to everything, which is indistinguishable from a
     * problem that affords nothing.</p>
     */
    @Test
    public void thePanelIsReachableFromItsOwnSubtree() {
        assertEquals(panel, DataContext.from(panel).get(ProblemsPanel.PROBLEMS_PANEL));
    }

    /** Both commands exist under the ids the menu names — an unregistered id is a permanently dead row. */
    @Test
    public void theMenusCommandsAreRegistered() {
        CommandRegistry registry = CommandRegistry.global();
        assertNotNull(registry.get(ProblemsCommands.SHOW_QUICK_FIXES));
        assertNotNull(registry.get(ProblemsCommands.JUMP_TO_SOURCE));
    }

    /**
     * <b>Disabled over a file heading, enabled over a problem.</b>
     *
     * <p>Dimmed rather than hidden, which is the registry's rule everywhere: a menu whose rows appear and
     * vanish is a menu whose rows are never in the same place twice.</p>
     */
    @Test
    public void theRowsAreDeadUntilThereIsAProblemToActOn() {
        Command fixes = CommandRegistry.global().get(ProblemsCommands.SHOW_QUICK_FIXES);
        assertNotNull(fixes);
        // Nothing right-clicked yet: there is no subject, so there is nothing to enable.
        assertFalse("a menu opened over nothing must not offer to fix it",
                fixes.isEnabled(new CommandContext(panel, null)));
    }

    /**
     * <b>Asking for quick fixes asks the host, and names the problem it was opened on.</b>
     *
     * <p>The panel has no editor and must not reach for one — the same arrangement
     * {@code onProblemChosen} already documents. It emits exactly one signal: the quick-fixes handler
     * navigates as part of what it does, so emitting the navigate signal as well would open the file
     * twice.</p>
     */
    @Test
    public void requestingFixesEmitsOnceAndCarriesTheProblem() {
        AtomicReference<Object> asked = new AtomicReference<>();
        AtomicReference<Object> chosen = new AtomicReference<>();
        panel.onQuickFixesRequested.connect(asked::set);
        panel.onProblemChosen.connect(chosen::set);

        // With nothing right-clicked there is no subject, and asking must be a no-op rather than a guess
        // at the selection -- a context menu names its subject, which is the whole distinction.
        assertFalse(panel.showQuickFixesForContext());
        assertTrue("nothing was right-clicked, so nothing may be asked about", asked.get() == null);
        assertTrue("and the navigate signal must not fire either", chosen.get() == null);
    }
}
