package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.ui.elements.editor.TextEditor;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Where the quick-fix bulb is, and when it is there at all.
 *
 * <p>Nothing measured either, and both went wrong: it appeared on rows with nothing to fix and sat well
 * below the row it claimed to be about. A gutter decoration that is merely <em>present</em> looks correct
 * in every test that only asks whether it exists.</p>
 */
public class QuickFixBulbTest extends UiTestBase {

    private static final String BULB_CLASS = "__quick-fix-bulb__";

    private UIWindow window;
    private TextEditor editor;

    @Before
    public void openAnEditor() {
        editor = new TextEditor("alpha\nbravo\ncharlie\ndelta\n");
        editor.layout(l -> l.width(400).height(200));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 200);
        window.setUiScale(1f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    /** The bulb element, or null when it has never been created. */
    private UIElement bulb() {
        return find(editor);
    }

    private static UIElement find(UIElement element) {
        if (element.hasClass(BULB_CLASS)) return element;
        for (UIElement child : element.getChildren()) {
            UIElement found = find(child);
            if (found != null) return found;
        }
        return null;
    }

    private boolean bulbVisible() {
        UIElement found = bulb();
        return found != null && found.getRuntimeCache().getHeight() > 0f;
    }

    private void putCaretOn(int row) {
        int offset = editor.buffer().document().lineStartOffset(row);
        editor.setSelection(offset, offset);
        settle();
    }

    private void problemOnRow(int row) {
        editor.diagnostics().setAll(List.of(Diagnostic.warning(
                new TextPoint(row, 0), new TextPoint(row, 3), "something to fix")));
        settle();
    }

    @Test
    public void noProblemMeansNoBulb() {
        putCaretOn(1);
        assertTrue("a clean document must show no bulb at all", !bulbVisible());
    }

    /** <b>Only on the caret's row.</b> A bulb that follows the caret onto clean rows says nothing true. */
    @Test
    public void theBulbAppearsOnlyWhileTheCaretIsOnTheProblem() {
        problemOnRow(1);
        putCaretOn(1);
        assertTrue("the caret is on the problem", bulbVisible());

        putCaretOn(3);
        assertTrue("the caret has left the problem", !bulbVisible());
    }

    /**
     * <b>And it sits on that row.</b>
     *
     * <p>Asserted against the squiggle for the same diagnostic rather than against the expression that
     * places it, so this cannot agree with the code it is checking: the squiggle is under the text the
     * problem is about, and the bulb is in the gutter beside it. Both are absolute, so a bulb placed
     * relative to the wrong origin shows up here as a gap of whatever that origin is.</p>
     */
    @Test
    public void theBulbSitsOnTheRowItIsAbout() {
        problemOnRow(2);
        putCaretOn(2);
        UIElement found = bulb();
        assertNotNull("no bulb was created", found);

        UIElement squiggle = findClass(editor, "__squiggle__");
        assertNotNull("no squiggle to compare against", squiggle);
        float line = editor.lineHeight();
        assertEquals("the bulb is not on the same row as the problem it marks",
                squiggle.getRuntimeCache().getY(), found.getRuntimeCache().getY(), line);
    }

    private static UIElement findClass(UIElement element, String className) {
        if (element.hasClass(className) && element.getRuntimeCache().getHeight() > 0f) return element;
        for (UIElement child : element.getChildren()) {
            UIElement found = findClass(child, className);
            if (found != null) return found;
        }
        return null;
    }
}
