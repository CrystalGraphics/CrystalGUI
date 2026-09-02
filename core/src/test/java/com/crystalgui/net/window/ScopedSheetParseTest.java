package com.crystalgui.net.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.app.machine.ui.MachineStyles;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import org.junit.Test;

/**
 * The parse half of {@link ScopedSheets}, which the headless test cannot reach.
 *
 * <p>Driven with the <b>real</b> {@code machine.css} rather than a snippet, because the snippet
 * passed: every rewrite test scoped a comment-free selector and checked the string, and the sheet that
 * shipped is a third comments. A comment between two rules was read as part of the next selector,
 * split on the commas inside it, and the whole sheet was refused with <i>Unparseable selector fragment
 * near '.' in 'that.'</i> — <code>that.</code> being prose. The window opened with no styling at all,
 * and the warning sat in the log for two runs before anyone searched for it.</p>
 */
public class ScopedSheetParseTest extends UiTestBase {

    private static final String SCOPE = ScopedSheets.scopeClass("crystalgui:machine");

    @Test
    public void theShippedSheetScopesToSomethingTheParserAccepts() {
        StyleSheet plain = StyleSheet.parse(MachineStyles.CSS);
        StyleSheet scoped = StyleSheet.parse(ScopedSheets.scope(MachineStyles.CSS, SCOPE));

        assertTrue("the plain sheet has rules, or this proves nothing", plain.getRules().size() > 0);
        assertTrue("every rule survives scoping (a refused sheet has none)",
                scoped.getRules().size() >= plain.getRules().size());
    }

    @Test
    public void theScopedSheetReachesThePanelRootAndNothingOutsideTheScope() {
        StyleSheet scoped = StyleSheet.parse(ScopedSheets.scope(MachineStyles.CSS, SCOPE));

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(scoped);
        window.init(800, 600);

        UIElement inScope = new UIElement();
        inScope.addClass(MachineStyles.PANEL_CLASS);
        inScope.addClass(SCOPE);
        UIElement stranger = new UIElement();
        stranger.addClass(MachineStyles.PANEL_CLASS);
        root.addChild(inScope);
        root.addChild(stranger);
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();

        // .machine-panel { width: 560px } — the rule on the ROOT, which a descendant-only scope misses.
        assertEquals("the panel root takes its width from the server's sheet",
                560f, inScope.getRuntimeCache().getWidth(), 0.5f);
        assertNotEquals("a panel outside the scope is untouched by it",
                560f, stranger.getRuntimeCache().getWidth(), 0.5f);
    }
}
