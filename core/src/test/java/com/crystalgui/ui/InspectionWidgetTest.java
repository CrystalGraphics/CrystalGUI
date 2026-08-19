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
import static org.junit.Assert.assertNull;
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

    /**
     * A severity's chip, only if it is being shown.
     *
     * <p>The chips are built once and hidden with {@code display: none}, so they are always <em>in</em> the
     * tree — asserting on presence alone would pass for every severity at once.</p>
     */
    private UIElement chip(String severityClass) {
        for (UIElement candidate : allWith(editor, "__inspection-count__")) {
            if (candidate.hasClass(severityClass)) {
                return shown(candidate) ? candidate : null;
            }
        }
        return null;
    }

    private static boolean shown(UIElement element) {
        return element.getStyle().getLayoutGroup()
                .getValueSave(com.crystalgui.style.property.layout.LayoutProperties.DISPLAY)
                != dev.vfyjxf.taffy.style.TaffyDisplay.NONE;
    }

    /** The number beside a severity's icon. */
    private String countOf(String severityClass) {
        UIElement chip = chip(severityClass);
        assertNotNull("no visible chip for " + severityClass, chip);
        return ((com.crystalgui.ui.elements.UIText) chip.getChildren().get(1)).getText();
    }

    private static List<UIElement> allWith(UIElement element, String cssClass) {
        List<UIElement> out = new java.util.ArrayList<>();
        if (element.hasClass(cssClass)) out.add(element);
        for (UIElement child : element.getChildren()) out.addAll(allWith(child, cssClass));
        return out;
    }

    /**
     * The widget is present on a clean file, saying so with the tick.
     *
     * <p>That it says <em>something</em> is the point: a readout that appeared only when something was
     * wrong could never report a clean file, and its absence would be indistinguishable from the feature
     * being broken.</p>
     */
    @Test
    public void aCleanFileSaysSoRatherThanShowingNothing() {
        build();
        assertTrue("a clean file should show the tick", shown(find("__inspection-ok__")));
        assertNull("no severity chip belongs on a clean file", chip("severity-error"));
        assertNotNull("a clean file must still carry the clean class",
                find("__inspection-clean__"));
    }

    /** One chip per severity that has something, and none for the ones that do not. */
    @Test
    public void eachSeverityGetsItsOwnCountAndZeroesAreOmitted() {
        build();
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(0, 0), new TextPoint(0, 3), "e1"),
                Diagnostic.error(new TextPoint(1, 0), new TextPoint(1, 3), "e2"),
                Diagnostic.warning(new TextPoint(2, 0), new TextPoint(2, 3), "w")));
        settle();

        assertEquals("2", countOf("severity-error"));
        assertEquals("1", countOf("severity-warning"));
        assertNull("a severity with nothing to report is absent, not a zero", chip("severity-info"));
        assertFalse("the tick belongs only to a file with nothing at all",
                shown(find("__inspection-ok__")));
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

        assertTrue("a fixed file goes back to the tick", shown(find("__inspection-ok__")));
        assertNull("the error chip survived the fix", chip("severity-error"));
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
