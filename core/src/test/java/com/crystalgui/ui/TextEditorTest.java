package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.6 — the editor widget.
 *
 * <h3>Driven through the input handler, not the API</h3>
 * <p>Every key here goes through {@code consumeKeyboardEvent} and real dispatch. This session already
 * produced one widget whose whole suite passed while the thing was unusable, because the tests drove the
 * API from the side the code was written from — the table's headers were unclickable and no test could
 * see it. Typing is the only thing this widget does; testing it any other way tests the wrong seam.</p>
 */
public class TextEditorTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private TextEditor editor;

    /**
     * The live modifier mask.
     *
     * <p>{@code UIInputHandler} reads modifiers from {@code CgPlatform.input().getCurrentModifiers()},
     * <b>not</b> from the key events it is handed — so synthesising a Shift key-down does nothing at all.
     * The mask is platform state, and a test sets it by being the platform.</p>
     */
    private static final String NL = "\n";

    private int modifiers;
    private String clipboard = "";

    @Before
    public void installInputStub() {
        modifiers = 0;
        clipboard = "";
        // Clipboard and modifiers live on the same service, so one stub covers both.
        TestPlatformService.install().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return modifiers; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return clipboard; }
            @Override public void setClipboard(String text) { clipboard = text; }
        });
    }

    private TextEditor build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(300).height(120));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.init(600, 400);
        input = window.getInputHandler();
        settle();
        input.requestFocus(editor);
        return editor;
    }

    /**
     * Advances the frame AND pumps an input frame.
     *
     * <p>{@code updateWithoutPainting()} deliberately does no input handling — no frame was presented, so
     * hover has nothing to be relative to — which means it never sets {@code firstFrameOver}. And
     * {@code consumeKeyboardEvent} early-returns until that flag is set, so without the
     * begin/endFrame pair here every key is silently dropped before dispatch and the widget looks
     * completely dead while being perfectly correct.</p>
     */
    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
        if (input != null) {
            input.beginFrame();
            input.endFrame();
        }
    }

    private void key(int code) {
        key(code, CgModifiers.NONE);
    }

    private void key(int code, int held) {
        this.modifiers = held;
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', code, true, false, 2L));
        this.modifiers = 0;
        settle();
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(c, 0, true, false, 3L));
        }
        settle();
    }

    // ── Typing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    public void typingInsertsAtTheCaret() {
        build("");
        type("hello");
        assertEquals("hello", editor.getText());
        assertEquals(5, editor.getCaret());
    }

    @Test
    public void enterStartsANewLine() {
        build("");
        type("ab");
        key(CgKeyCodes.KEY_RETURN);
        type("cd");
        assertEquals("ab\ncd", editor.getText());
        assertEquals(new TextPoint(1, 2), editor.caretPoint());
    }

    @Test
    public void backspaceAndDeleteRemoveOneCharacterEitherSide() {
        build("abcd");
        editor.setCaret(2);
        key(CgKeyCodes.KEY_BACK);
        assertEquals("acd", editor.getText());
        key(CgKeyCodes.KEY_DELETE);
        assertEquals("ad", editor.getText());
    }

    @Test
    public void typingOverASelectionReplacesIt() {
        build("hello world");
        editor.setSelection(0, 5);
        type("goodbye");
        assertEquals("goodbye world", editor.getText());
        assertFalse(editor.hasSelection());
    }

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void arrowsMoveTheCaretAcrossLines() {
        build("ab\ncd");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_DOWN);
        assertEquals("down keeps the column", new TextPoint(1, 0), editor.caretPoint());
        key(CgKeyCodes.KEY_RIGHT);
        assertEquals(new TextPoint(1, 1), editor.caretPoint());
        key(CgKeyCodes.KEY_UP);
        assertEquals(new TextPoint(0, 1), editor.caretPoint());
    }

    /**
     * <b>Vertical movement remembers the column it started from.</b> Without it, passing through a short
     * line drags the caret inward and it never comes back — down-down-up from column 8 ends at column 2
     * instead of 8. Every editor keeps this, and it is the single most noticeable thing about a caret
     * that does not.
     */
    @Test
    public void verticalMovementRemembersThePreferredColumn() {
        build("long line here\nx\nanother long line");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(0, 12)));

        key(CgKeyCodes.KEY_DOWN);
        assertEquals("clamped by the short line", new TextPoint(1, 1), editor.caretPoint());
        key(CgKeyCodes.KEY_DOWN);
        assertEquals("but the original column is restored", new TextPoint(2, 12), editor.caretPoint());
    }

    @Test
    public void homeAndEndGoToTheEndsOfTheLine() {
        build("first line\nsecond line");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(1, 3)));
        key(CgKeyCodes.KEY_HOME);
        assertEquals(new TextPoint(1, 0), editor.caretPoint());
        key(CgKeyCodes.KEY_END);
        assertEquals(new TextPoint(1, 11), editor.caretPoint());
    }

    @Test
    public void ctrlHomeAndEndGoToTheEndsOfTheDocument() {
        build("first line\nsecond line");
        editor.setCaret(4);
        key(CgKeyCodes.KEY_END, CgModifiers.CTRL);
        assertEquals(editor.getText().length(), editor.getCaret());
        key(CgKeyCodes.KEY_HOME, CgModifiers.CTRL);
        assertEquals(0, editor.getCaret());
    }

    @Test
    public void ctrlArrowsMoveByWord() {
        build("alpha beta gamma");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_RIGHT, CgModifiers.CTRL);
        assertEquals(5, editor.getCaret());
        key(CgKeyCodes.KEY_RIGHT, CgModifiers.CTRL);
        assertEquals(10, editor.getCaret());
        key(CgKeyCodes.KEY_LEFT, CgModifiers.CTRL);
        assertEquals(6, editor.getCaret());
    }

    @Test
    public void shiftExtendsTheSelectionInsteadOfMovingIt() {
        build("abcdef");
        editor.setCaret(2);
        key(CgKeyCodes.KEY_RIGHT, CgModifiers.SHIFT);
        key(CgKeyCodes.KEY_RIGHT, CgModifiers.SHIFT);
        assertTrue(editor.hasSelection());
        assertEquals(2, editor.getSelectionStart());
        assertEquals(4, editor.getSelectionEnd());
        assertEquals("cd", editor.getSelectedText());
    }

    @Test
    public void pageDownMovesByAScreenful() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("row ").append(i).append('\n');
        build(document.toString());
        editor.setCaret(0);

        key(CgKeyCodes.KEY_NEXT);
        assertTrue("page down moved more than a line", editor.caretPoint().row() > 1);
        assertTrue("but not to the end", editor.caretPoint().row() < 199);
    }

    // ── Undo, through the keyboard ──────────────────────────────────────────────────────────────

    @Test
    public void ctrlZUndoesAndCtrlShiftZRedoes() {
        build("");
        type("hello");
        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("", editor.getText());
        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL | CgModifiers.SHIFT);
        assertEquals("hello", editor.getText());
    }

    @Test
    public void ctrlASelectsEverything() {
        build("one\ntwo\nthree");
        key(CgKeyCodes.KEY_A, CgModifiers.CTRL);
        assertEquals(0, editor.getSelectionStart());
        assertEquals(editor.getText().length(), editor.getSelectionEnd());
    }

    /** Undo must not leave the caret pointing past the end of a document that just got shorter. */
    @Test
    public void undoLeavesTheCaretInsideTheDocument() {
        build("");
        type("abcdef");
        assertEquals(6, editor.getCaret());
        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("", editor.getText());
        assertTrue("caret " + editor.getCaret() + " is outside a document of length 0",
                editor.getCaret() <= editor.getText().length());
    }

    // ── Virtualisation ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>A large document must not realise a large number of elements.</b> The editor renders through the
     * same windowing idea as the virtualised list; if that ever silently stopped working, the widget would
     * still be correct and would build ten thousand elements to show forty.
     */
    @Test
    public void onlyTheVisibleLinesExist() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 5000; i++) document.append("line ").append(i).append('\n');
        build(document.toString());
        settle();
        editor.updateWindow();

        long lines = editor.getChildren().stream()
                .filter(child -> child.hasClass(TextEditor.LINE_CLASS))
                .count();
        assertTrue("realised " + lines + " lines for a 5001-line document", lines < 60);
        assertTrue("but it did realise some", lines > 0);
    }

    /**
     * <b>A realised line must have a real width.</b> An absolutely-positioned box with no width resolves
     * to zero, and a zero-width line lays its text out as though it had no extent — which shaved the
     * first character off every visible row. Invisible to every behavioural test, because the caret
     * arithmetic, the document and the navigation were all correct; it only showed up on screen.
     */
    @Test
    public void everyRealisedLineHasARealWidth() {
        build("line zero here" + NL + "line one here" + NL + "line two here");
        settle();
        editor.updateWindow();
        settle();

        int checked = 0;
        for (UIElement child : editor.getChildren()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            assertTrue("a line box collapsed to zero width",
                    child.getRuntimeCache().getWidth() > 1f);
            checked++;
        }
        assertTrue("no lines were realised at all", checked > 0);
    }

    /**
     * <b>Moving the caret must not rebuild the visible lines.</b> {@code setSelection} used to call
     * {@code invalidateWindow()}, which recycles every realised line — so each arrow key tore down and
     * rebuilt the whole screen, and the realised set was momentarily empty. Correct, and wasteful in a
     * way that only shows up as a number on a status line.
     */
    @Test
    public void movingTheCaretKeepsTheRealisedLines() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 100; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        settle();
        editor.updateWindow();
        settle();

        java.util.List<UIElement> before = new java.util.ArrayList<>();
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) before.add(child);
        }
        assertFalse(before.isEmpty());

        key(CgKeyCodes.KEY_RIGHT);
        key(CgKeyCodes.KEY_RIGHT);

        java.util.List<UIElement> after = new java.util.ArrayList<>();
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) after.add(child);
        }
        assertEquals("the same line elements must survive a caret move", before, after);
    }

    /**
     * <b>Text, caret and hit testing must all use the same horizontal origin, and it is the padding
     * box.</b>
     *
     * <p>Taffy places an absolutely positioned child relative to its containing block's padding box, so
     * an inset of 0 already sits after the border — while the scrollport clips to the <em>content</em>
     * box. Lines placed at 0 therefore began inside the padding and lost their first characters, and the
     * caret, which added border and padding, sat further right than the text it was meant to be inside.
     * A single editor with both a border and padding is the only configuration that separates the three
     * candidate origins, which is why it took a screenshot to find.</p>
     */
    @Test
    public void linesAndCaretShareTheContentBoxOrigin() {
        editor = new TextEditor("abc");
        editor.layout(l -> l.width(300).height(120).paddingLeft(10f).borderLeft(2f));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.init(600, 400);
        input = window.getInputHandler();
        settle();
        editor.updateWindow();
        settle();

        float contentLeft = editor.getRuntimeCache().getX()
                + editor.getTaffyLayout().border().left + editor.getTaffyLayout().padding().left;

        UIElement line = null;
        UIElement caret = null;
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) line = child;
            if (child.hasClass(TextEditor.CARET_CLASS)) caret = child;
        }
        assertNotNull(line);
        assertNotNull(caret);

        assertEquals("a line must start at the content box, not inside the padding",
                contentLeft, line.getRuntimeCache().getX(), 0.5f);
        editor.setCaret(0);
        settle();
        assertEquals("and the caret at column 0 must sit exactly there too",
                contentLeft, caret.getRuntimeCache().getX(), 0.5f);
    }

    @Test
    public void theScrollableHeightCoversEveryLine() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 500; i++) document.append("line ").append(i).append('\n');
        build(document.toString());
        settle();

        assertEquals(501 * editor.lineHeight(), editor.getScrollHeight(), 0.5f);
    }

    /** The editor is a composite: its lines and caret are its own, and callers do not add children. */
    @Test
    public void theEditorRefusesPublicChildren() {
        build("x");
        assertFalse(editor.acceptsPublicChildren());
        try {
            editor.addChild(new UIElement());
            fail("a composite widget must refuse public children");
        } catch (RuntimeException expected) {
            // the engine's own guard
        }
    }
}
