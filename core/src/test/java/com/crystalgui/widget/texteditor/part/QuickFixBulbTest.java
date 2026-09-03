package com.crystalgui.widget.texteditor.part;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;
import com.crystalgui.text.lang.CodeActionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.widget.texteditor.TextEditor;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

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
public class QuickFixBulbTest extends UiDocumentTestBase {

    private static final String BULB_CLASS = "__quick-fix-bulb__";

    private TextEditor editor;

    @Before
    public void openAnEditor() {
        editor = new TextEditor("alpha\nbravo\ncharlie\ndelta\n");
        editor.layout(l -> l.width(400).height(200));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.boxes().setUiScale(1f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    /** The bulb element, or null when it has never been created. */
    private UIElement bulb() {
        return find(editor);
    }

    private static UIElement find(UIElement element) {
        if (element.hasClass(BULB_CLASS)) return element;
        for (UIElement child : element.children()) {
            UIElement found = find(child);
            if (found != null) return found;
        }
        return null;
    }

    private boolean bulbVisible() {
        UIElement found = bulb();
        return found != null && heightOf(found) > 0f;
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
                squiggle.box().y(), found.box().y(), line);
    }

    private static UIElement findClass(UIElement element, String className) {
        if (element.hasClass(className) && heightOf(element) > 0f) return element;
        for (UIElement child : element.children()) {
            UIElement found = findClass(child, className);
            if (found != null) return found;
        }
        return null;
    }

    // ── Intentions ──────────────────────────────────────────────────────────────────────────────

    /** An engine that offers one action on row 1 and nothing anywhere else, with no diagnostic at all. */
    private void intentionOnRow(int row) {
        int only = editor.buffer().document().lineStartOffset(row);
        editor.setLanguageServices(new LanguageServices() {
            @Override public String id() {
                return "stub";
            }

            @Override public CodeActionProvider codeActions() {
                return (request, answer) -> answer.accept(Versioned.of(request.version(),
                        request.from() == only
                                ? List.of(new CodeAction("stub.intention", "Do the thing",
                                        CodeActionKind.REFACTOR, null, null, Map.of(), false, request.version()))
                                : List.of()));
            }
        });
        settle();
    }

    /**
     * <b>An action with no diagnostic still lights the bulb.</b>
     *
     * <p>The rule used to be <em>bulb ⟺ diagnostic</em>, which was the same thing as <em>bulb ⟺ actions</em>
     * only while every action came from a problem. An intention — "Replace with lambda" is the first —
     * fires on code where nothing is wrong, so it lit no bulb and marked no stripe, and the feature was
     * reachable only by knowing to press Alt+Enter. The class's own note said this rule would have to
     * change when that happened.</p>
     */
    @Test
    public void anIntentionLightsTheBulbWithNoDiagnostic() {
        intentionOnRow(1);
        putCaretOn(1);
        assertTrue("an offered action with no problem behind it must still show a bulb", bulbVisible());
    }

    /** And a row the engine offers nothing for stays dark, so the bulb is not simply always on. */
    @Test
    public void aRowWithNothingOfferedStaysDark() {
        intentionOnRow(1);
        putCaretOn(3);
        assertTrue("nothing is offered here", !bulbVisible());
    }
}
