package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The inspection widget — the problem readout in the editor's top-right corner.
 *
 * <p>Asserted through the tree rather than through the part, which is package-private and would otherwise
 * have to be opened up purely to be tested. What a reader sees is the text in the panel and the classes
 * on it, so that is what this reads.</p>
 */
public class InspectionWidgetTest extends UiTestBase {

    private UIWindow window;
    private TextEditor editor;

    private TextEditor build() {
        editor = new TextEditor("alpha\nbeta\ngamma\ndelta");
        editor.layout(l -> l.width(300).height(160));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UIElement root = new UIElement().layout(l -> l.width(320).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(640, 400);
        settle();
        return editor;
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private UIElement find(String cssClass) {
        return find(editor, cssClass);
    }

    private static UIElement find(UIElement element, String cssClass) {
        if (element.hasClass(cssClass)) return element;
        for (UIElement child : element.getChildren()) {
            UIElement found = find(child, cssClass);
            if (found != null) return found;
        }
        return null;
    }

    private String statusText() {
        UIElement status = find("__inspection-status__");
        assertNotNull("no status readout in the tree", status);
        return ((com.crystalgui.ui.elements.UIText) status).getText();
    }

    /**
     * The widget is present on a clean file, saying so.
     *
     * <p>"No problems" is the most reassuring thing it says, and a readout that appeared only when
     * something was wrong could never say it — its absence would be indistinguishable from the feature
     * being broken.</p>
     */
    @Test
    public void aCleanFileSaysSoRatherThanShowingNothing() {
        build();
        assertEquals("No problems", statusText());
        assertNotNull("a clean file must still carry the clean class",
                find("__inspection-clean__"));
    }

    @Test
    public void countsArePluralisedAndZeroCategoriesAreOmitted() {
        build();
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(0, 0), new TextPoint(0, 3), "e1"),
                Diagnostic.error(new TextPoint(1, 0), new TextPoint(1, 3), "e2"),
                Diagnostic.warning(new TextPoint(2, 0), new TextPoint(2, 3), "w")));
        settle();

        String text = statusText();
        assertTrue(text, text.contains("2 errors"));
        assertTrue(text, text.contains("1 warning"));
        assertFalse("a plural 's' on a count of one", text.contains("1 warnings"));
        assertFalse("zero categories must not be listed", text.contains("note"));
    }

    /** The worst thing in the file decides the colour class, so a file with one error among many
     * warnings still reads as broken. */
    @Test
    public void theWorstSeverityDecidesTheClass() {
        build();
        editor.diagnostics().setAll(List.of(
                Diagnostic.warning(new TextPoint(0, 0), new TextPoint(0, 3), "w"),
                Diagnostic.error(new TextPoint(1, 0), new TextPoint(1, 3), "e")));
        settle();
        assertNotNull(find("__inspection-errors__"));

        editor.diagnostics().setAll(List.of(
                Diagnostic.warning(new TextPoint(0, 0), new TextPoint(0, 3), "w")));
        settle();
        assertNotNull(find("__inspection-warnings__"));
        assertEquals("the error class survived", null, find("__inspection-errors__"));
    }

    /** A fixed file goes back to clean — the classes are cleared, not only added. */
    @Test
    public void fixingEverythingReturnsTheWidgetToClean() {
        build();
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(0, 0), new TextPoint(0, 3), "e")));
        settle();
        assertNotNull(find("__inspection-errors__"));

        editor.diagnostics().clear();
        settle();

        assertEquals("No problems", statusText());
        assertNotNull(find("__inspection-clean__"));
        assertEquals(null, find("__inspection-errors__"));
    }

    /**
     * The arrows are disabled rather than removed when there is nothing to visit.
     *
     * <p>Removing them would change the panel's width, so the readout would shift sideways the instant a
     * file became clean — movement that draws the eye to the exact moment nothing is happening.</p>
     */
    @Test
    public void theArrowsAreDisabledOnACleanFileAndEnabledOnceThereIsAProblem() {
        build();
        UIElement next = find("__inspection-next__");
        UIElement previous = find("__inspection-previous__");
        assertNotNull(next);
        assertNotNull(previous);
        assertFalse("nothing to navigate to, so the arrow must be disabled", next.isEnabled());

        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(2, 0), new TextPoint(2, 3), "e")));
        settle();

        assertTrue(next.isEnabled());
        assertTrue(previous.isEnabled());
    }

    /** Never focusable: a Button takes focus on click by default, and an arrow that stole focus from the
     * editor would leave the next keystroke going nowhere — from a control whose whole purpose is to put
     * you back in the text. */
    @Test
    public void theArrowsNeverTakeFocus() {
        build();
        assertFalse(find("__inspection-next__").focusable());
        assertFalse(find("__inspection-previous__").focusable());
    }

    /** Chrome, not content: it stays in the corner as the text scrolls under it. */
    @Test
    public void theWidgetIsScrollExempt() {
        build();
        assertTrue(find("__inspection__").isScrollExempt());
    }
}
