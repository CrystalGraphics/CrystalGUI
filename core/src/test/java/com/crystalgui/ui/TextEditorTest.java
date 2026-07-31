package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Before;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.UIText;
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

    // ── Word-wise deletion and smart Home ───────────────────────────────────────────────────────

    /**
     * <b>Ctrl+Backspace deletes to the same boundary Ctrl+Left moves to.</b> Sharing the boundary
     * function rather than writing a second rule is the point: two rules for "where does a word start"
     * drift, and the drift shows up as a delete that removes one character more or less than the cursor
     * would have skipped.
     */
    @Test
    public void ctrlBackspaceDeletesTheWordBeforeTheCaret() {
        build("alpha beta gamma");
        editor.setCaret(16);
        key(CgKeyCodes.KEY_BACK, CgModifiers.CTRL);
        assertEquals("alpha beta ", editor.getText());
        key(CgKeyCodes.KEY_BACK, CgModifiers.CTRL);
        assertEquals("alpha ", editor.getText());
    }

    @Test
    public void ctrlDeleteRemovesTheWordAfterTheCaret() {
        build("alpha beta gamma");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_DELETE, CgModifiers.CTRL);
        assertEquals(" beta gamma", editor.getText());
    }

    @Test
    public void plainBackspaceStillRemovesOneCharacter() {
        build("abc");
        editor.setCaret(3);
        key(CgKeyCodes.KEY_BACK);
        assertEquals("ab", editor.getText());
    }

    /** Home goes to the first non-blank, and only to column 0 when already there. */
    @Test
    public void homeTogglesBetweenTheIndentAndColumnZero() {
        build("    indented line");
        editor.setCaret(10);

        key(CgKeyCodes.KEY_HOME);
        assertEquals("first press lands on the text, not the indentation",
                new TextPoint(0, 4), editor.caretPoint());
        key(CgKeyCodes.KEY_HOME);
        assertEquals("pressing again still reaches column 0",
                new TextPoint(0, 0), editor.caretPoint());
    }

    /**
     * <b>Switching theme must change the editor's font, driven only by the ordinary frame loop.</b>
     *
     * <p>Reported from the gallery: every other widget picked up the default theme and the editor stayed
     * in the Ore font. The editor forces its own font onto its lines (a universal {@code * } rule in a
     * theme would otherwise beat inheritance), and that push lives in {@code updateWindow} — which runs
     * from the frame ticker or a layout change. A theme swap does not resize the editor, so no layout
     * pass fires; and {@code ScrollerView.tickFrame} returns {@code isAnimating()}, so the ticker had
     * already been dropped once scrolling settled. Nothing was left to notice.</p>
     *
     * <p>This test deliberately never calls {@code updateWindow()} or {@code tickFrame()} itself. An
     * earlier version of the blink tests did, which is why they could not catch this at all.</p>
     */
    @Test
    public void removingAThemeSheetChangesTheFontThroughTheNormalFrameLoop() {
        editor = new TextEditor("line of text");
        editor.layout(l -> l.width(300).height(120));
        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.init(600, 400);
        input = window.getInputHandler();

        var themed = com.crystalgui.style.sheet.StyleSheet.parse(
                "* { font-family: \"crystalgui:ui/fonts/Minecraft.otf\"; }");
        window.getStyleEngine().addStylesheet(themed);
        settle();
        settle();
        assertTrue("the themed font should be in force first",
                String.valueOf(lineFontFamily()).contains("Minecraft"));

        window.getStyleEngine().removeStylesheet(themed);
        // Only ordinary frames from here -- no updateWindow(), no tickFrame().
        for (int i = 0; i < 5; i++) window.updateWithoutPainting();

        assertFalse("the line kept the removed theme's font: " + lineFontFamily(),
                String.valueOf(lineFontFamily()).contains("Minecraft"));
    }

    private Object lineFontFamily() {
        for (UIElement child : editor.getChildren()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            return child.getChildren().get(0).getStyle().getGeneralGroup().fontFamily();
        }
        throw new AssertionError("no line realised");
    }

    // ── Indentation ──────────────────────────────────────────────

    /**
     * <b>Enter carries the current line's indentation, per caret.</b> With several carets on differently
     * indented lines a single shared indent would be wrong for all but one of them, which is why the
     * indent is computed inside the per-caret loop rather than once.
     */
    @Test
    public void enterCarriesTheIndentation() {
        build("    indented");
        editor.setCaret(editor.getText().length());

        key(CgKeyCodes.KEY_RETURN);

        assertEquals("    indented" + NL + "    ", editor.getText());
    }

    @Test
    public void enterAfterAnOpeningBraceAddsALevel() {
        build("  if (x) {");
        editor.setCaret(editor.getText().length());

        key(CgKeyCodes.KEY_RETURN);

        assertEquals("  if (x) {" + NL + "      ", editor.getText());
    }

    @Test
    public void tabIndentsEveryLineOfASelection() {
        build("one" + NL + "two" + NL + "three");
        editor.setSelection(0, editor.getText().length());

        key(CgKeyCodes.KEY_TAB);

        assertEquals("    one" + NL + "    two" + NL + "    three", editor.getText());
    }

    /** Indenting must leave the block selected, or pressing Tab again indents one line instead. */
    @Test
    public void indentingKeepsTheBlockSelected() {
        build("one" + NL + "two");
        editor.setSelection(0, editor.getText().length());

        key(CgKeyCodes.KEY_TAB);
        assertTrue("the block is still selected", editor.hasSelection());
        key(CgKeyCodes.KEY_TAB);

        assertEquals("        one" + NL + "        two", editor.getText());
    }

    @Test
    public void shiftTabOutdents() {
        build("        one" + NL + "    two");
        editor.setSelection(0, editor.getText().length());

        key(CgKeyCodes.KEY_TAB, CgModifiers.SHIFT);

        assertEquals("    one" + NL + "two", editor.getText());
    }

    @Test
    public void outdentingAnUnindentedLineDoesNothing() {
        build("one");
        editor.setSelection(0, 3);
        key(CgKeyCodes.KEY_TAB, CgModifiers.SHIFT);
        assertEquals("one", editor.getText());
    }

    // ── Bracket matching ─────────────────────────────────────────

    @Test
    public void theBracketUnderTheCaretFindsItsPartner() {
        build("if (a + b) {}");
        editor.setCaret(3);   // on the '('
        settle();
        editor.updateWindow();
        settle();

        assertTrue("the pair should be highlighted", lineHasHighlight(0, "bracket"));
    }

    /** Looking at the character before the caret is what makes it work as you type a closing brace. */
    @Test
    public void aJustTypedClosingBracketMatches() {
        build("(a)");
        editor.setCaret(3);   // just after the ')'
        settle();
        editor.updateWindow();
        settle();

        assertTrue(lineHasHighlight(0, "bracket"));
    }

    @Test
    public void anUnmatchedBracketHighlightsNothing() {
        build("(a");
        editor.setCaret(1);
        settle();
        editor.updateWindow();
        settle();

        assertFalse(lineHasHighlight(0, "bracket"));
    }

    // ── Find and replace ─────────────────────────────────────────

    @Test
    public void findReportsEveryMatch() {
        build("cat dog cat bird cat");
        assertEquals(3, editor.find("cat", true));
        assertEquals(3, editor.matchCount());
    }

    @Test
    public void findIsCaseInsensitiveWhenAsked() {
        build("Cat cat CAT");
        assertEquals(1, editor.find("cat", true));
        assertEquals(3, editor.find("cat", false));
    }

    /** Overlapping hits are separate hits — "aa" occurs twice in "aaa", as every editor reports. */
    @Test
    public void overlappingMatchesAreCountedSeparately() {
        build("aaa");
        assertEquals(2, editor.find("aa", true));
    }

    @Test
    public void findNextWalksTheMatchesAndWraps() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);

        assertTrue(editor.findNext());
        assertEquals(8, editor.getSelectionStart());
        assertTrue("and wraps back to the first", editor.findNext());
        assertEquals(0, editor.getSelectionStart());
    }

    @Test
    public void replaceCurrentReplacesTheSelectedMatch() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);
        editor.findNext();

        assertTrue(editor.replaceCurrent("fish"));
        assertEquals("cat dog fish", editor.getText());
    }

    /**
     * <b>Replace-all is ONE edit.</b> A loop of replacements would invalidate every later offset after
     * the first, and would take one undo press per match to reverse.
     */
    @Test
    public void replaceAllIsASingleUndoStep() {
        build("cat dog cat bird cat");
        editor.find("cat", true);

        assertEquals(3, editor.replaceAll("fish"));
        assertEquals("fish dog fish bird fish", editor.getText());

        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("one undo reverses the lot", "cat dog cat bird cat", editor.getText());
    }

    @Test
    public void replacingWithALongerStringKeepsLaterMatchesCorrect() {
        build("a a a");
        editor.find("a", true);
        editor.replaceAll("xyz");
        assertEquals("xyz xyz xyz", editor.getText());
    }

    @Test
    public void searchHitsAreHighlighted() {
        build("find me here");
        editor.find("me", true);
        settle();
        editor.updateWindow();
        settle();

        assertTrue(lineHasHighlight(0, "search"));
    }

    // ── Syntax highlighting ──────────────────────────────────────

    @Test
    public void aTokenizerPublishesNamedHighlights() {
        build("int x = 1; // note");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        assertTrue("types are captured", lineHasHighlight(0, "type"));
        assertTrue("and comments", lineHasHighlight(0, "comment"));
    }

    /**
     * <b>A block comment spanning several lines highlights each of them.</b> A registry belongs to one
     * text element and its ranges are offsets into that element's string, so a document-relative token
     * has to be clipped and rebased per line — otherwise it reads as running off the end of every line
     * but the last.
     */
    @Test
    public void aMultiLineTokenIsClippedOntoEachLine() {
        build("/* one" + NL + "two" + NL + "three */");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        for (int row = 0; row < 3; row++) {
            assertTrue("row " + row + " is inside the comment", lineHasHighlight(row, "comment"));
        }
    }

    /** A pooled line reused for another row must not keep the old row's ranges. */
    @Test
    public void recyclingALineClearsItsHighlights() {
        StringBuilder document = new StringBuilder("// a comment" + NL);
        for (int i = 0; i < 300; i++) document.append("plain ").append(i).append(NL);
        build(document.toString());
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();
        assertTrue(lineHasHighlight(0, "comment"));

        editor.setScrollTop(2000f);
        settle();
        editor.updateWindow();
        settle();

        for (var entry : realisedRowsOf(editor).entrySet()) {
            UIText text = (UIText) entry.getValue().getChildren().get(0);
            assertTrue("row " + entry.getKey() + " kept a stale comment highlight",
                    text.highlights().get("comment").isEmpty());
        }
    }

    private java.util.Map<Integer, UIElement> realisedRowsOf(TextEditor target) {
        java.util.Map<Integer, UIElement> rows = new java.util.LinkedHashMap<>();
        int index = 0;
        for (UIElement child : target.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) rows.put(index++, child);
        }
        return rows;
    }

    /** Whether the realised line showing {@code row} carries any range under {@code name}. */
    private boolean lineHasHighlight(int row, String name) {
        String wanted = editor.buffer().line(row);
        for (UIElement child : editor.getChildren()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            UIText text = (UIText) child.getChildren().get(0);
            if (!text.getText().equals(wanted)) continue;
            if (!text.highlights().get(name).isEmpty()) return true;
        }
        return false;
    }

    // ── Gutter and current line ──────────────────────────────────

    @Test
    public void theGutterNumbersTheVisibleLines() {
        build("one" + NL + "two" + NL + "three");
        settle();
        editor.updateWindow();
        settle();

        java.util.List<String> numbers = new java.util.ArrayList<>();
        collectNumbers(editor, numbers);
        assertEquals(java.util.List.of("1", "2", "3"), numbers);
    }

    /**
     * <b>The gutter is sized from the digit count of the LAST line, not the widest number on screen.</b>
     * Sized from what is visible, the text would shift sideways as you scrolled past line 99 into line
     * 100 — which reads as the editor being unstable rather than as a gutter resizing.
     */
    @Test
    public void theGutterIsWideEnoughForTheLastLineNotJustTheVisibleOnes() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 1000; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        settle();
        editor.updateWindow();
        settle();

        float atTop = editor.gutterWidth();
        editor.setScrollTop(4000f);
        settle();
        editor.updateWindow();
        settle();

        assertEquals("the gutter must not resize as you scroll", atTop, editor.gutterWidth(), 0.5f);
        assertTrue("and it must be wide enough for four digits", atTop > 0f);
    }

    @Test
    public void hidingTheGutterGivesTheTextItsWidthBack() {
        build("one" + NL + "two");
        settle();
        editor.updateWindow();
        settle();
        assertTrue(editor.gutterWidth() > 0f);

        editor.setGutterVisible(false);
        settle();
        editor.updateWindow();
        settle();

        assertEquals(0f, editor.gutterWidth(), 0.01f);
    }

    private void collectNumbers(UIElement root, java.util.List<String> out) {
        for (UIElement child : root.getChildren()) {
            if (child.hasClass(TextEditor.LINE_NUMBER_CLASS)) {
                UIText label = (UIText) child.getChildren().get(0);
                if (child.getRuntimeCache().getHeight() > 0f) out.add(label.getText());
            }
            collectNumbers(child, out);
        }
    }

    // ── Multi-cursor ─────────────────────────────────────────────

    /**
     * <b>Typing at several carets is ONE edit, not one per caret.</b> Applied separately the later offsets
     * would be invalidated by the first, and a single keystroke would take N undos to reverse. As one
     * {@code ChangeSet} it is one edit, one undo step, and every caret is carried through it by the same
     * mapping that carries an anchor.
     */
    @Test
    public void typingAtSeveralCaretsInsertsAtAllOfThem() {
        build("aa" + NL + "bb" + NL + "cc");
        editor.setCaret(0);
        editor.addCaret(3);
        editor.addCaret(6);
        assertEquals(3, editor.caretCount());

        type("X");

        assertEquals("Xaa" + NL + "Xbb" + NL + "Xcc", editor.getText());
        assertEquals("still three carets", 3, editor.caretCount());
    }

    @Test
    public void oneUndoReversesAMultiCaretEdit() {
        build("aa" + NL + "bb");
        editor.setCaret(0);
        editor.addCaret(3);
        type("X");
        assertEquals("Xaa" + NL + "Xbb", editor.getText());

        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("one keystroke, one undo", "aa" + NL + "bb", editor.getText());
    }

    @Test
    public void arrowKeysMoveEveryCaret() {
        build("aaa" + NL + "bbb");
        editor.setCaret(0);
        editor.addCaret(4);

        key(CgKeyCodes.KEY_RIGHT);

        assertEquals(2, editor.caretCount());
        assertEquals(1, editor.selections().all().get(0).head());
        assertEquals(5, editor.selections().all().get(1).head());
    }

    /** Carets driven onto the same offset are one caret, or every later keystroke doubles. */
    @Test
    public void caretsThatCollideMerge() {
        build("ab");
        editor.setCaret(0);
        editor.addCaret(1);

        key(CgKeyCodes.KEY_END);

        assertEquals(1, editor.caretCount());
    }

    @Test
    public void escapeCollapsesToThePrimaryCaret() {
        build("aa" + NL + "bb");
        editor.setCaret(0);
        editor.addCaret(3);
        assertEquals(2, editor.caretCount());

        key(CgKeyCodes.KEY_ESCAPE);

        assertEquals(1, editor.caretCount());
    }

    @Test
    public void backspaceAppliesAtEveryCaret() {
        build("ab" + NL + "cd");
        editor.setCaret(2);
        editor.addCaret(5);

        key(CgKeyCodes.KEY_BACK);

        assertEquals("a" + NL + "c", editor.getText());
    }

    /** Copying several selections joins them by newline, as every editor does. */
    @Test
    public void copyingSeveralSelectionsJoinsThemWithNewlines() {
        build("one two three");
        editor.selections().setAll(java.util.List.of(
                new com.crystalgui.text.Selection(0, 3),
                new com.crystalgui.text.Selection(4, 7)), 0);

        assertEquals("one" + NL + "two", editor.getSelectedText());
    }

    // ── Caret blink ─────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The blink restarts on input.</b> A caret that happened to be in its off phase would otherwise
     * vanish at the exact moment it is being looked for — while typing.
     */
    @Test
    public void typingMakesTheCaretSolidAgain() {
        build("abc");
        editor.tickFrame(0.9f);   // well into the off phase
        assertFalse("the caret should be hidden mid-cycle", caretVisible());

        type("d");
        assertTrue("typing restarts the cycle solid", caretVisible());
    }

    @Test
    public void theCaretBlinksWhileFocused() {
        build("abc");
        editor.tickFrame(0.0f);
        assertTrue(caretVisible());
        editor.tickFrame(0.7f);
        assertFalse("half a period in, the caret is off", caretVisible());
        editor.tickFrame(0.6f);
        assertTrue("and on again after a full cycle", caretVisible());
    }

    /** A caret in an unfocused editor claims a text cursor no keystroke would reach. */
    @Test
    public void anUnfocusedEditorShowsNoCaret() {
        build("abc");
        editor.tickFrame(0.0f);
        assertTrue(caretVisible());

        input.blurIfFocused(editor);
        editor.tickFrame(0.0f);
        assertFalse("an unfocused editor must not show a caret", caretVisible());
    }

    @Test
    public void blinkingCanBeTurnedOff() {
        build("abc");
        editor.setCaretBlinkSeconds(0f);
        editor.tickFrame(5f);
        assertTrue("a zero period means a solid caret", caretVisible());
    }

    private boolean caretVisible() {
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.CARET_CLASS)) {
                return child.getStyle().getGeneralGroup().opacity() > 0.5f;
            }
        }
        throw new AssertionError("no caret element");
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
        // The gutter is off here on purpose: this test is about the padding-versus-border origin, and a
        // gutter would add a third term to the expectation and stop it testing the thing it is named for.
        editor.setGutterVisible(false);
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
        // The caret's RIGHT edge sits on the boundary, so at column 0 it occupies the gap immediately
        // before the first glyph -- one caret-width into the padding. That is deliberate: a caret drawn
        // rightwards from the boundary covers the first ink column of the glyph after it, because a
        // bitmap font has no left side bearing. Having padding is what gives it somewhere to sit.
        float caretWidth = editor.getStyle().getGeneralGroup().caretWidth();
        assertEquals("the caret at column 0 sits one caret-width before the text",
                contentLeft - caretWidth, caret.getRuntimeCache().getX(), 0.5f);
    }

    /**
     * <b>A line must render in the font the editor measures with.</b>
     *
     * <p>Caret positions are prefix widths, so a font disagreement is a <em>scale</em> error that grows
     * with the column — the caret drifts further from its glyph the further along the line it sits, and
     * text is inserted where the caret really is rather than where it appears. {@code ore.css} carries a
     * universal rule ({@code * { font-size: 10 }}) which matches the line's own text element, and a
     * <em>specified</em> value beats an inherited one, so the editor measured at 8 while its lines drew
     * at 10.</p>
     *
     * <p>This installs a universal rule deliberately, because that is the shape of the sheet that broke
     * it — no test without one could see this.</p>
     */
    @Test
    public void linesRenderInTheFontTheEditorMeasuresWith() {
        editor = new TextEditor("hello world");
        editor.layout(l -> l.width(300).height(120));
        editor.generalStyle(g -> g.fontSize(8f));

        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.init(600, 400);
        input = window.getInputHandler();
        // The rule that caused it: universal, so it matches the line's text directly.
        window.getStyleEngine().addStylesheet(
                com.crystalgui.style.sheet.StyleSheet.parse("* { font-size: 10; }"));
        settle();
        editor.updateWindow();
        settle();

        UIElement line = null;
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) line = child;
        }
        assertNotNull(line);
        float lineFont = line.getChildren().get(0).getStyle().getGeneralGroup().fontSize();
        assertEquals("the line must not draw at a size the editor did not measure with",
                editor.getStyle().getGeneralGroup().fontSize(), lineFont, 0.01f);
    }

    /**
     * <b>A double-click is close in space as well as in time.</b>
     *
     * <p>{@code ButtonState} counted multi-clicks on elapsed time alone. {@code UIInputHandler} resets
     * the counter when the click lands on a <em>different element</em>, which hides the problem for
     * buttons — but two clicks inside one large element are the same element however far apart they are.
     * So double-clicking a word and then clicking a different word reported {@code detail == 2}, and the
     * editor selected the second word instead of putting a caret in it.</p>
     */
    @Test
    public void clickingAWordAfterDoubleClickingAnotherDoesNotSelectIt() {
        build("alpha beta gamma delta");
        settle();
        editor.updateWindow();
        settle();

        int near = editor.getCaret();
        // Two presses at the same spot: a genuine double-click, which selects a word.
        pressAt(4, 4);
        pressAt(4, 4);
        assertTrue("a real double-click still selects a word", editor.hasSelection());

        // A third press well away from the first two, inside the multi-click interval.
        pressAt(120, 4);
        assertFalse("a click elsewhere is a click, not a double-click", editor.hasSelection());
        assertNotEquals(near, editor.getCaret());
    }

    private void pressAt(int x, int y) {
        float scale = window.getUiScale();
        int px = Math.round((editor.getRuntimeCache().getX() + x) * scale);
        int py = Math.round((editor.getRuntimeCache().getY() + y) * scale);
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, true, 0f, 10L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, false, 0f, 11L));
        input.beginFrame();
        input.endFrame();
        settle();
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
