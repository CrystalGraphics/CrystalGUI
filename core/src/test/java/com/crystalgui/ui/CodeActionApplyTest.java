package com.crystalgui.ui;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;
import com.crystalgui.ui.elements.editor.TextEditor;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Applying a code action — the version gate, and undo.
 *
 * <p><b>The gate is the part of this feature that can damage a file</b>, which is why it is tested before
 * any of the UI that reaches it. An edit is a set of offsets; offsets into a document that has since been
 * typed in still resolve, they simply name different text. So a stale action does not fail — it silently
 * edits the wrong place, and nothing downstream can tell.</p>
 */
public class CodeActionApplyTest extends UiTestBase {

    private UIWindow window;
    private TextEditor editor;

    @Before
    public void setUp() {
        editor = new TextEditor("alpha\nbravo\ncharlie\n");
        editor.layout(l -> l.width(400).height(200));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 400);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    /** Deletes "alpha\n", stamped with the version it was built against. */
    private CodeAction removeFirstLine(long version) {
        return CodeAction.preferredFix("test.removeLine", "Remove line",
                ChangeSet.replace(editor.getText().length(), 0, 6, ""), version);
    }

    @Test
    public void afreshActionApplies() {
        CodeAction action = removeFirstLine(editor.buffer().version());
        assertTrue(editor.applyCodeAction(action));
        assertEquals("bravo\ncharlie\n", editor.getText());
    }

    /**
     * <b>A stale action is refused rather than applied.</b>
     *
     * <p>The fixture is the failure exactly: the action deletes offsets 0–6, which was "alpha\n" when it
     * was computed. After typing at the top those offsets are somebody else's text, and applying would
     * delete it while looking like a successful fix.</p>
     */
    @Test
    public void aStaleActionIsRefused() {
        CodeAction action = removeFirstLine(editor.buffer().version());
        editor.setSelection(0, 0);
        editor.insertAtCaret("XX");
        settle();

        String before = editor.getText();
        assertFalse("a fix built against an older document must not be applied",
                editor.applyCodeAction(action));
        assertEquals("the document must be untouched", before, editor.getText());
    }

    /**
     * <b>An action with no edit never goes stale.</b>
     *
     * <p>A command names what it acts on rather than where, so there are no offsets to be wrong. Gating it
     * on a version would make "Copy problem message" stop working the moment you typed.</p>
     */
    @Test
    public void aCommandActionIsAlwaysApplicable() {
        CodeAction copy = CodeAction.command("problem.copyMessage", "Copy problem message",
                CodeActionKind.SOURCE, "problem.copyMessage");
        assertTrue(copy.isApplicableTo(editor.buffer().version()));
        assertTrue(copy.isApplicableTo(editor.buffer().version() + 99));
    }

    /**
     * <b>"Copy problem message" copies the problem it was offered ABOUT, not the one under the caret.</b>
     *
     * <p>The popup this row sits in can be opened from an error-stripe mark or a hover nowhere near the
     * caret. The command used to re-read the caret's problems when it ran, so it copied whatever the caret
     * happened to be on — the action now carries the message as an argument, decided when it was offered.
     * Two problems on two rows, caret on the first, actions requested for the second: the clipboard must
     * hold the second's message.</p>
     */
    @Test
    public void copyMessageCopiesTheProblemTheActionWasOfferedAbout() {
        editor.diagnostics().setAll(List.of(
                Diagnostic.error(new TextPoint(0, 0),
                        new TextPoint(0, 5), "alpha is wrong"),
                Diagnostic.warning(new TextPoint(2, 0),
                        new TextPoint(2, 7), "charlie is doubtful")));
        settle();
        editor.setSelection(0, 0);                              // caret on the FIRST problem

        int charlie = editor.getText().indexOf("charlie");
        @SuppressWarnings("unchecked")
        List<CodeAction>[] offered = new List[1];
        editor.requestCodeActions(charlie, actions -> offered[0] = actions);
        assertTrue("the engine-free actions arrive synchronously", offered[0] != null);
        CodeAction copy = offered[0].stream()
                .filter(a -> "problem.copyMessage".equals(a.commandId())).findFirst().orElse(null);
        assertTrue("copy is offered for the hovered problem", copy != null);

        assertTrue(editor.applyCodeAction(copy));
        assertEquals("charlie is doubtful", CgPlatform.input().getClipboard());
    }

    /**
     * <b>One fix is one undo step, and does not merge with the typing around it.</b>
     *
     * <p>Without the leading break the fix joins the run of keystrokes before it; without the trailing one
     * the next keystroke joins the fix. Either way Ctrl+Z takes back half a fix and half a sentence.</p>
     */
    @Test
    public void aFixIsExactlyOneUndoStep() {
        editor.setSelection(0, 0);
        editor.insertAtCaret("Z");
        settle();
        String afterTyping = editor.getText();

        assertTrue(editor.applyCodeAction(
                CodeAction.preferredFix("test.removeLine", "Remove line",
                        ChangeSet.replace(editor.getText().length(), 0, 7, ""),
                        editor.buffer().version())));
        settle();

        editor.undoStack().undo();
        settle();
        assertEquals("undo took back more than the fix", afterTyping, editor.getText());
    }

    /**
     * <b>Navigating to a problem centres it.</b>
     *
     * <p>Reported as "clicking a stripe mark takes me to a slightly off offset": the line landed at the
     * very top of the viewport rather than in the middle, which is half a screen out and is what a
     * minimal reveal looks like when a centred one was asked for.</p>
     */
    @Test
    public void goingToAProblemCentresIt() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) document.append("line ").append(i).append("\n");
        editor.setText(document.toString());
        for (int i = 0; i < 6; i++) settle();

        Diagnostic problem = Diagnostic.onRow(200, DiagnosticSeverity.WARNING, "somewhere in the middle");
        assertTrue(editor.goToDiagnostic(problem));
        for (int i = 0; i < 4; i++) settle();

        float lineHeight = editor.lineHeight();
        float boxHeight = editor.getRuntimeCache().getHeight();
        float lineTopOnScreen = 200 * lineHeight - editor.getScrollTop();
        float middle = boxHeight / 2f;
        assertTrue("row 200 sits at " + lineTopOnScreen + " in a box of " + boxHeight
                        + " (scrollTop=" + editor.getScrollTop() + ", lineHeight=" + lineHeight + ")",
                Math.abs(lineTopOnScreen - middle) <= lineHeight * 1.5f);
    }
}
