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

    /**
     * <b>A registered range with no matching rule paints nothing, and looks identical to a broken
     * tokenizer.</b>
     *
     * <p>This is the 6.1.1 lesson in a new coat. There, highlight properties resolved and were never
     * painted; here the ranges were registered on each line's {@code UIText} while the gallery's selector
     * named the <em>editor</em> — {@code .ed::highlight(keyword)} — which owns no ranges. Everything
     * reported success and the screen was monochrome.</p>
     *
     * <p>So this asserts the end of the chain: that the style engine resolves a real colour for the
     * element that actually holds the range. Asserting the registry alone is what let it through.</p>
     */
    @Test
    public void aHighlightRuleActuallyResolvesForTheLineThatOwnsTheRange() {
        build("int x = 1;");
        // The CLASS form, because that is exactly what the gallery sheet uses -- testing the tag form
        // would leave the shipped selector untested and this bug was a wrong selector.
        editor.addClass("ed");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.parse(
                ".ed text::highlight(type) { color: #FF8800; }"));
        settle();
        editor.updateWindow();
        settle();

        UIText line = null;
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) line = (UIText) child.getChildren().get(0);
        }
        assertNotNull("no line was realised", line);
        assertFalse("the tokenizer must have registered a type range",
                line.highlights().get("type").isEmpty());

        var style = window.getStyleEngine().highlightStyle(line, "type");
        assertFalse("the rule resolved nothing, so the range would paint in the plain text colour",
                style.isEmpty());
        assertEquals(0xFFFF8800, style.color(0xFFFFFFFF));
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

    /**
     * <b>Typing must not strip the highlights off the whole document for a frame.</b>
     *
     * <p>Every edit used to call {@code invalidateWindow()}, which recycles every realised line — and
     * recycling has to clear a line's highlights, since a pooled line reused for another row would keep
     * offsets into a string that no longer exists. The ranges were republished during
     * {@code updateWindow}, which runs <em>after</em> {@code calculateStyle} in the frame, so the cascade
     * only saw them on the next one: for a frame every line rendered with no highlight style at all. On
     * screen that is the entire editor's colour flickering on each keystroke.</p>
     *
     * <p>The test asserts the two things that together prevent it: the line elements survive the edit,
     * and their highlights are never emptied.</p>
     */
    @Test
    public void typingDoesNotDropTheHighlightsForAFrame() {
        build("int x = 1;" + NL + "int y = 2;");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        java.util.List<UIElement> before = linesOf();
        assertFalse(before.isEmpty());
        assertFalse("a type range should be present to begin with",
                ((UIText) before.get(0).getChildren().get(0)).highlights().get("type").isEmpty());

        // The edit is applied WITHOUT running another frame, and the assertion happens before one runs.
        // That is the whole point: lines are pooled, so after a settle the same instances come back with
        // their highlights restored -- the empty window exists only INSIDE the frame, which is exactly
        // where the cascade sees it and exactly why the flicker is invisible to a test that settles first.
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(0, 9)));
        editor.insertAtCaret("2");

        assertEquals("the line elements must survive an edit that changes no line count",
                before, linesOf());
        assertFalse("the highlights must never be emptied, even for one frame",
                ((UIText) before.get(0).getChildren().get(0)).highlights().get("type").isEmpty());
    }

    /**
     * <b>Pressing Enter must not drop the highlights either.</b> The first fix spared edits that kept the
     * line count and still rebuilt on one that changed it — so typing was smooth and Enter still flashed.
     * The realised map is keyed by row index, so rebinding by row is correct however far the rows shifted.
     */
    @Test
    public void addingALineDoesNotDropTheHighlights() {
        build("int x = 1;" + NL + "int y = 2;");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        UIElement first = linesOf().get(0);
        assertFalse(((UIText) first.getChildren().get(0)).highlights().get("type").isEmpty());

        editor.setCaret(editor.getText().length());
        editor.insertAtCaret(NL + "int z = 3;");

        assertFalse("Enter must not empty the highlights any more than typing does",
                ((UIText) first.getChildren().get(0)).highlights().get("type").isEmpty());
    }

    /** The window still grows to cover a new row. */
    @Test
    public void addingALineRebuildsTheWindow() {
        build("one" + NL + "two");
        settle();
        editor.updateWindow();
        settle();
        int before = linesOf().size();

        editor.setCaret(3);
        key(CgKeyCodes.KEY_RETURN);
        settle();
        editor.updateWindow();
        settle();

        assertEquals("a new row exists", before + 1, linesOf().size());
    }

    private java.util.List<UIElement> linesOf() {
        java.util.List<UIElement> out = new java.util.ArrayList<>();
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) out.add(child);
        }
        return out;
    }

    // ── 6.1.7b: line endings, read-only, language ────────────────

    /**
     * <b>CRLF must not reach the buffer.</b> Every offset in the engine counts a break as ONE unit, so a
     * {@code \r\n} would make it sometimes two and every piece of offset arithmetic wrong by the number
     * of preceding lines. Normalised in, remembered, restored out — which is why editing a Windows file
     * does not silently convert it.
     */
    @Test
    public void crlfIsNormalisedInAndRestoredOut() {
        com.crystalgui.text.TextBuffer buffer = new com.crystalgui.text.TextBuffer("a\r\nb\r\nc");

        assertEquals("the document itself is LF", "a" + NL + "b" + NL + "c", buffer.toString());
        assertEquals(3, buffer.lineCount());
        assertEquals(com.crystalgui.text.LineEnding.CRLF, buffer.lineEnding());
        assertEquals("a\r\nb\r\nc", buffer.textWithOriginalLineEndings());
    }

    @Test
    public void aLoneCarriageReturnIsAlsoALineBreak() {
        com.crystalgui.text.TextBuffer buffer = new com.crystalgui.text.TextBuffer("a\rb");
        assertEquals(2, buffer.lineCount());
    }

    /** Mixed files exist; one stray CRLF must not convert an otherwise Unix file on save. */
    @Test
    public void theDominantLineEndingWins() {
        assertEquals(com.crystalgui.text.LineEnding.LF,
                com.crystalgui.text.LineEnding.detect("a\nb\nc\r\nd"));
        assertEquals(com.crystalgui.text.LineEnding.CRLF,
                com.crystalgui.text.LineEnding.detect("a\r\nb\r\nc\nd"));
    }

    /**
     * <b>Read-only is enforced where every edit funnels through</b>, not at each key. A per-key check is a
     * list to keep in step with the handler, and the failure when one is missed is a read-only document
     * that quietly changed.
     */
    @Test
    public void readOnlyRefusesEveryEditPath() {
        build("locked");
        editor.setReadOnly(true);
        editor.setCaret(3);

        type("X");
        key(CgKeyCodes.KEY_BACK);
        key(CgKeyCodes.KEY_RETURN);
        key(CgKeyCodes.KEY_TAB);
        editor.setSelection(0, 6);
        key(CgKeyCodes.KEY_DELETE);

        assertEquals("locked", editor.getText());
        assertTrue("but selection still works", editor.hasSelection());
    }

    // ── 6.1.7b: ported sticky columns, tabs, auto-close ──────────

    /**
     * <b>The goal column is per caret, not shared.</b> VS Code keeps {@code leftoverVisibleColumns} on
     * each cursor; one shared value means whichever caret moved last imposes its column on the others, and
     * a rectangular block of carets collapses into a ragged one after a single Down.
     */
    @Test
    public void eachCaretKeepsItsOwnGoalColumn() {
        build("aaaaaaaa" + NL + "bb" + NL + "cccccccc" + NL + "dd" + NL + "eeeeeeee" + NL + "ffffffff");
        // One caret at column 6, another at column 2, each above a SHORT line so both get clamped and
        // then have to recover their own column.
        editor.selections().setAll(java.util.List.of(
                com.crystalgui.text.Selection.caret(6),
                com.crystalgui.text.Selection.caret(editor.buffer().pointToOffset(new TextPoint(2, 2)))), 0);

        // TWO moves, deliberately. On the first the goals are still unset, so a shared goal and a
        // per-caret one behave identically -- the difference only appears once a goal has been stored,
        // which is exactly what a one-press test cannot see.
        key(CgKeyCodes.KEY_DOWN);
        key(CgKeyCodes.KEY_DOWN);

        assertEquals(2, editor.caretCount());
        assertEquals("the first caret recovered its own column 6",
                new TextPoint(2, 6), editor.buffer().offsetToPoint(editor.selections().all().get(0).head()));
        assertEquals("the second kept column 2 rather than inheriting the first's",
                new TextPoint(4, 2), editor.buffer().offsetToPoint(editor.selections().all().get(1).head()));
    }

    /** Down through a short line and back up returns to the original column. */
    @Test
    public void theGoalColumnSurvivesAShortLine() {
        build("long line here" + NL + "x" + NL + "another long one");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(0, 12)));

        key(CgKeyCodes.KEY_DOWN);
        key(CgKeyCodes.KEY_DOWN);
        assertEquals(new TextPoint(2, 12), editor.caretPoint());

        key(CgKeyCodes.KEY_UP);
        key(CgKeyCodes.KEY_UP);
        assertEquals("and back up to where it started", new TextPoint(0, 12), editor.caretPoint());
    }

    /** A horizontal move forgets the goal, which is why "down, right, up" does not return. */
    @Test
    public void aHorizontalMoveClearsTheGoalColumn() {
        build("long line here" + NL + "x" + NL + "another long one");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(0, 12)));

        key(CgKeyCodes.KEY_DOWN);
        key(CgKeyCodes.KEY_RIGHT);
        key(CgKeyCodes.KEY_UP);

        assertNotEquals("the goal must not survive a horizontal move",
                new TextPoint(0, 12), editor.caretPoint());
    }

    /**
     * <b>A tab advances to its stop, not by one character.</b> VS Code's {@code CursorColumns} separates a
     * column (an offset into the line) from a visible column (where it lands), and without that every
     * tab-indented file misaligns — the caret walks one position per tab while the text jumps a stop.
     */
    @Test
    public void aTabAdvancesToItsStop() {
        build("	x");
        editor.setTabSize(4);
        settle();
        editor.updateWindow();
        settle();

        UIText line = null;
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) line = (UIText) child.getChildren().get(0);
        }
        assertNotNull(line);
        assertEquals("the displayed text expands the tab to its stop", "    x", line.getText());
        assertEquals("but the document still holds a tab", 2, editor.getText().length());
    }

    @Test
    public void aTabMidLineAdvancesToTheNextStopNotByFour() {
        build("ab	c");
        editor.setTabSize(4);
        settle();
        editor.updateWindow();
        settle();

        UIText line = null;
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) line = (UIText) child.getChildren().get(0);
        }
        assertNotNull(line);
        assertEquals("two characters in, the tab fills only to column 4", "ab  c", line.getText());
    }

    /**
     * <b>Auto-close uses an allowlist of what may follow.</b> The naive rule ("not before a letter or
     * digit") still opens a pair before {@code $foo} or {@code #define}; listing what may follow is both
     * stricter and shorter, and it is the list VS Code ships.
     */
    @Test
    public void autoCloseOnlyFiresBeforeTheAllowedCharacters() {
        build("$foo");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setCaret(0);
        type("(");
        assertEquals("no pair before a $", "($foo", editor.getText());

        build("; rest");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setCaret(0);
        type("(");
        assertEquals("but a pair before a semicolon", "(); rest", editor.getText());
    }

    /** An apostrophe in prose must not become a pair. */
    @Test
    public void aQuoteAfterAWordDoesNotAutoClose() {
        build("dont ");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setCaret(4);

        type("'");

        assertEquals("dont' ", editor.getText());
    }

    // ── 6.1.7b: ported mouse selection ───────────────────────────

    /** A press at a document offset, with a click count, through the real input handler. */
    private void pressWithClicks(int offset, int clicks) {
        settle();
        editor.updateWindow();
        settle();
        float scale = window.getUiScale();
        var point = editor.buffer().offsetToPoint(offset);
        float x = editor.getRuntimeCache().getX() + editor.gutterWidth() + 4f + point.column() * 4f;
        float y = editor.getRuntimeCache().getY() + point.row() * editor.lineHeight() + 2f;
        for (int i = 0; i < clicks; i++) {
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * scale), Math.round(y * scale), 0, 0, 0, true, 0f, 10L + i));
            input.beginFrame();
            input.endFrame();
            input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                    Math.round(x * scale), Math.round(y * scale), 0, 0, 0, false, 0f, 11L + i));
            input.beginFrame();
            input.endFrame();
        }
        settle();
    }

    /**
     * <b>A triple-click selects the line.</b> Click count picks a GRANULARITY rather than a separate
     * action — one click a caret, two a word, three a line — which is how VS Code's mouse handler is
     * structured and what lets the same code serve the press and every drag update after it.
     */
    @Test
    public void tripleClickSelectsTheLine() {
        build("alpha beta" + NL + "second line");
        pressWithClicks(2, 3);

        assertTrue("something should be selected", editor.hasSelection());
        assertEquals("the selection starts at the line start", 0, editor.getSelectionStart());
        assertTrue("and covers the line", editor.getSelectionEnd() >= "alpha beta".length());
    }

    @Test
    public void doubleClickSelectsTheWordUnderIt() {
        build("alpha beta gamma");
        pressWithClicks(7, 2);

        assertEquals("beta", editor.getSelectedText());
    }

    @Test
    public void aSingleClickJustPlacesTheCaret() {
        build("alpha beta");
        pressWithClicks(3, 1);
        assertFalse(editor.hasSelection());
    }

    // ── 6.1.7b: ported cursor semantics ──────────────────────────

    /**
     * <b>A plain Left with a selection cancels it at the START — it does not move.</b>
     *
     * <p>Ported from VS Code's {@code MoveOperations.moveLeft}. Moving one character from the head — the
     * obvious implementation, and what this did — lands the caret one character <em>inside</em> the text
     * you just deselected. It is wrong in the way people feel without being able to name, which is
     * exactly the class of thing worth porting rather than inventing.</p>
     */
    @Test
    public void leftWithASelectionCollapsesToItsStart() {
        build("abcdefgh");
        editor.setSelection(2, 6);

        key(CgKeyCodes.KEY_LEFT);

        assertFalse(editor.hasSelection());
        assertEquals("the caret lands on the selection start, not one left of the head",
                2, editor.getCaret());
    }

    @Test
    public void rightWithASelectionCollapsesToItsEnd() {
        build("abcdefgh");
        editor.setSelection(2, 6);

        key(CgKeyCodes.KEY_RIGHT);

        assertFalse(editor.hasSelection());
        assertEquals(6, editor.getCaret());
    }

    /** A reversed selection collapses to the same edges — direction of the gesture must not matter here. */
    @Test
    public void aReversedSelectionCollapsesToTheSameEdges() {
        build("abcdefgh");
        editor.setSelection(6, 2);
        key(CgKeyCodes.KEY_RIGHT);
        assertEquals(6, editor.getCaret());
    }

    /** Shift+Left still EXTENDS from the head rather than collapsing. */
    @Test
    public void shiftLeftStillExtends() {
        build("abcdefgh");
        editor.setSelection(2, 6);

        key(CgKeyCodes.KEY_LEFT, CgModifiers.SHIFT);

        assertTrue(editor.hasSelection());
        assertEquals(5, editor.getCaret());
    }

    /** Word moves do NOT take the collapse shortcut — they move by word from the active position. */
    @Test
    public void ctrlLeftWithASelectionStillMovesByWord() {
        build("alpha beta gamma");
        editor.setSelection(6, 10);

        key(CgKeyCodes.KEY_LEFT, CgModifiers.CTRL);

        assertEquals("it moved to a word start rather than parking on the selection edge",
                6, editor.getCaret());
    }

    /** Underscores are word characters, so Ctrl+Right crosses them. */
    @Test
    public void wordMovementTreatsUnderscoresAsPartOfTheWord() {
        build("some_long_name tail");
        editor.setCaret(0);

        key(CgKeyCodes.KEY_RIGHT, CgModifiers.CTRL);

        assertEquals(14, editor.getCaret());
    }

    @Test
    public void doubleClickSelectsAWholeIdentifier() {
        build("call some_long_name(x)");
        editor.setCaret(0);
        editor.selections().set(new com.crystalgui.text.Selection(8, 8));
        // Through the same helper double-click uses.
        int[] word = com.crystalgui.text.WordOperations.wordAt(
                editor.buffer().document(), 8, com.crystalgui.text.WordClassifier.DEFAULT);
        assertNotNull(word);
        assertEquals(5, word[0]);
        assertEquals(19, word[1]);
    }

    // ── 6.1.7b: multi-caret commands ─────────────────────────────

    /**
     * <b>Ctrl+D is what makes multi-cursor reachable.</b> Before this the only way to make a second caret
     * was Alt+Click — the model was built and all but unusable.
     */
    @Test
    public void ctrlDSelectsTheWordThenAddsTheNextOccurrence() {
        build("foo bar foo baz foo");
        editor.setCaret(1);

        key(CgKeyCodes.KEY_D, CgModifiers.CTRL);
        assertEquals("first press selects the word", "foo", editor.getSelectedText());
        assertEquals(1, editor.caretCount());

        key(CgKeyCodes.KEY_D, CgModifiers.CTRL);
        assertEquals("second adds the next occurrence", 2, editor.caretCount());
        key(CgKeyCodes.KEY_D, CgModifiers.CTRL);
        assertEquals(3, editor.caretCount());
    }

    @Test
    public void typingWithCaretsFromCtrlDEditsEveryOccurrence() {
        build("foo bar foo");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_D, CgModifiers.CTRL);
        key(CgKeyCodes.KEY_D, CgModifiers.CTRL);

        type("X");

        assertEquals("X bar X", editor.getText());
    }

    @Test
    public void ctrlShiftLSelectsEveryOccurrence() {
        build("a x a x a");
        editor.setSelection(0, 1);

        key(CgKeyCodes.KEY_L, CgModifiers.CTRL | CgModifiers.SHIFT);

        assertEquals(3, editor.caretCount());
    }

    @Test
    public void ctrlAltDownAddsACaretBelow() {
        build("one" + NL + "two" + NL + "three");
        editor.setCaret(1);

        key(CgKeyCodes.KEY_DOWN, CgModifiers.CTRL | CgModifiers.ALT);

        assertEquals(2, editor.caretCount());
        assertEquals("and it keeps the column", 1, editor.selections().all().get(1).head() - 4);
    }

    // ── 6.1.7b: line operations ──────────────────────────────────

    @Test
    public void altDownMovesTheLineDown() {
        build("one" + NL + "two" + NL + "three");
        editor.setCaret(0);

        key(CgKeyCodes.KEY_DOWN, CgModifiers.ALT);

        assertEquals("two" + NL + "one" + NL + "three", editor.getText());
    }

    @Test
    public void altUpMovesTheLineUp() {
        build("one" + NL + "two" + NL + "three");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(1, 0)));

        key(CgKeyCodes.KEY_UP, CgModifiers.ALT);

        assertEquals("two" + NL + "one" + NL + "three", editor.getText());
    }

    @Test
    public void movingTheFirstLineUpDoesNothing() {
        build("one" + NL + "two");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_UP, CgModifiers.ALT);
        assertEquals("one" + NL + "two", editor.getText());
    }

    @Test
    public void shiftAltDownDuplicatesTheLine() {
        build("one" + NL + "two");
        editor.setCaret(0);

        key(CgKeyCodes.KEY_DOWN, CgModifiers.ALT | CgModifiers.SHIFT);

        assertEquals("one" + NL + "one" + NL + "two", editor.getText());
    }

    @Test
    public void ctrlShiftKDeletesTheLine() {
        build("one" + NL + "two" + NL + "three");
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(1, 0)));

        key(CgKeyCodes.KEY_K, CgModifiers.CTRL | CgModifiers.SHIFT);

        assertEquals("one" + NL + "three", editor.getText());
    }

    @Test
    public void ctrlEnterOpensALineBelow() {
        build("    indented");
        editor.setCaret(2);

        key(CgKeyCodes.KEY_RETURN, CgModifiers.CTRL);

        assertEquals("and carries the indent", "    indented" + NL + "    ", editor.getText());
    }

    @Test
    public void ctrlLSelectsTheLine() {
        build("one" + NL + "two");
        editor.setCaret(1);

        key(CgKeyCodes.KEY_L, CgModifiers.CTRL);

        assertEquals("one" + NL, editor.getSelectedText());
    }

    @Test
    public void ctrlJJoinsTheNextLineUp() {
        build("one" + NL + "    two");
        editor.setCaret(0);

        key(CgKeyCodes.KEY_J, CgModifiers.CTRL);

        assertEquals("the indentation is collapsed to one space", "one two", editor.getText());
    }

    // ── 6.1.7b: comments ─────────────────────────────────────────

    @Test
    public void ctrlSlashTogglesTheLineComment() {
        build("int x;");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setCaret(0);

        key(CgKeyCodes.KEY_SLASH, CgModifiers.CTRL);
        assertEquals("// int x;", editor.getText());

        key(CgKeyCodes.KEY_SLASH, CgModifiers.CTRL);
        assertEquals("int x;", editor.getText());
    }

    /**
     * <b>A mixed block comments out rather than half-toggling.</b> Every editor does this, and it is the
     * only rule that behaves sensibly: a selection where one line is already commented should end up
     * fully commented.
     */
    @Test
    public void aPartlyCommentedBlockCommentsOut() {
        build("// one" + NL + "two");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setSelection(0, editor.getText().length());

        key(CgKeyCodes.KEY_SLASH, CgModifiers.CTRL);

        assertEquals("// // one" + NL + "// two", editor.getText());
    }

    @Test
    public void aLanguageWithoutCommentsIgnoresTheKey() {
        build("plain");
        editor.setCaret(0);
        key(CgKeyCodes.KEY_SLASH, CgModifiers.CTRL);
        assertEquals("plain", editor.getText());
    }

    // ── 6.1.7b: typing aids ──────────────────────────────────────

    @Test
    public void bracketsAutoClose() {
        build("");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        type("(");
        assertEquals("()", editor.getText());
        assertEquals("and the caret sits between them", 1, editor.getCaret());
    }

    /**
     * <b>Type-over is what makes auto-closing bearable.</b> Without it you type {@code (}, get {@code ()},
     * type the {@code )} you expected to need, and end up with {@code ())}.
     */
    @Test
    public void typingAClosingBracketStepsOverIt() {
        build("");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        type("(");
        type(")");

        assertEquals("()", editor.getText());
        assertEquals(2, editor.getCaret());
    }

    @Test
    public void typingABracketOverASelectionSurroundsIt() {
        build("value");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setSelection(0, 5);

        type("(");

        assertEquals("a selection must not be replaced by the bracket", "(value)", editor.getText());
    }

    /** An opener before a word means "wrap this", not "make an empty pair in front of it". */
    @Test
    public void anOpenerBeforeAWordDoesNotAutoClose() {
        build("word");
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());
        editor.setCaret(0);

        type("(");

        assertEquals("(word", editor.getText());
    }

    @Test
    public void backspaceInIndentationRemovesAWholeLevel() {
        build("        text");
        editor.setCaret(8);

        key(CgKeyCodes.KEY_BACK);

        assertEquals("    text", editor.getText());
    }

    @Test
    public void backspaceInsideTextStillRemovesOneCharacter() {
        build("    abcd");
        editor.setCaret(8);

        key(CgKeyCodes.KEY_BACK);

        assertEquals("    abc", editor.getText());
    }

    // ── 6.1.7b: search keys ──────────────────────────────────────

    @Test
    public void f3WalksTheMatches() {
        build("cat dog cat");
        editor.find("cat", true);
        editor.setCaret(0);

        key(CgKeyCodes.KEY_F3);
        assertEquals(8, editor.getSelectionStart());
        key(CgKeyCodes.KEY_F3, CgModifiers.SHIFT);
        assertEquals(0, editor.getSelectionStart());
    }

    @Test
    public void ctrlF3SearchesTheWordUnderTheCaret() {
        build("alpha beta alpha");
        editor.setCaret(1);

        key(CgKeyCodes.KEY_F3, CgModifiers.CTRL);

        assertEquals(2, editor.matchCount());
        assertEquals("alpha", editor.getSelectedText());
    }

    // ── Scrollbars ───────────────────────────────────────────────

    /**
     * Builds an editor whose content overflows on <b>both</b> axes, so both bars are showing.
     *
     * <p>The user-agent sheet is installed deliberately: without it the scrollers have no size, and a
     * test about what the scrollbars cover would pass by there being no scrollbars.</p>
     */
    private void buildOverflowing() {
        StringBuilder document = new StringBuilder();
        document.append("a line long enough to force horizontal scrolling xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx").append(NL);
        for (int i = 0; i < 80; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();
        editor.updateWindow();
        settle();
    }

    private UIElement childWithClass(String name) {
        for (UIElement child : editor.getChildren()) {
            if (child.hasClass(name)) return child;
        }
        throw new AssertionError("no child with class " + name);
    }

    /**
     * <b>The gutter must stop above the horizontal scrollbar, not paint over it.</b>
     *
     * <p>The gutter sits at a higher z than the scrollbars on purpose, so a long line scrolled sideways
     * passes behind the numbers rather than through them. The cost of that is a full-height gutter
     * covering the bar's left end, which shows as a dead square in the corner that no drag responds to.
     * </p>
     */
    @Test
    public void theGutterStopsAboveTheHorizontalScrollbar() {
        buildOverflowing();

        UIElement gutter = childWithClass(TextEditor.GUTTER_CLASS);
        UIElement bar = childWithClass(com.crystalgui.ui.elements.ScrollerView.H_SCROLLER_CLASS);
        assertTrue("the horizontal bar should be showing for this document",
                bar.getRuntimeCache().getHeight() > 0f);

        float gutterBottom = gutter.getRuntimeCache().getY() + gutter.getRuntimeCache().getHeight();
        float barTop = bar.getRuntimeCache().getY();
        assertTrue("the gutter runs to " + gutterBottom + ", over a bar starting at " + barTop,
                gutterBottom <= barTop + 0.5f);
    }

    /**
     * <b>Scrolling to the caret must leave its line above the horizontal scrollbar.</b>
     *
     * <p>The bars are drawn <em>over</em> the content, which is fine for a list — a row half-behind a bar
     * is still obviously a row. In an editor the hidden line is the one being typed on, because that is
     * precisely the line the caret was scrolled to.</p>
     */
    @Test
    public void theCaretLineIsNotLeftUnderTheHorizontalScrollbar() {
        buildOverflowing();

        // Through the keyboard, because that is the path that scrolls: moving the caret keeps it visible,
        // whereas setCaret() places it without scrolling.
        key(CgKeyCodes.KEY_END, CgModifiers.CTRL);
        settle();
        editor.updateWindow();
        settle();

        // The bar is scroll-exempt so it is already in viewport space; the caret is an ordinary child in
        // DOCUMENT space, and the scroll offset is applied at paint time. Comparing them without
        // converting is comparing two different coordinate systems.
        float editorTop = editor.getRuntimeCache().getY();
        UIElement bar = childWithClass(com.crystalgui.ui.elements.ScrollerView.H_SCROLLER_CLASS);
        float barTop = bar.getRuntimeCache().getY() - editorTop;

        UIElement caret = childWithClass(TextEditor.CARET_CLASS);
        float caretBottom = caret.getRuntimeCache().getY() - editorTop
                + caret.getRuntimeCache().getHeight() - editor.getScrollTop();
        assertTrue("the caret reaches " + caretBottom + ", under a bar starting at " + barTop,
                caretBottom <= barTop + 0.5f);
    }

    /**
     * <b>A resize handle must stay at the element's visible corner after scrolling.</b>
     *
     * <p>The handles are absolutely positioned children, and a scroll offset in this engine is a pose
     * translate applied to every non-exempt child — so they slid away with the content. Scrolling down by
     * one line carried the bottom-right grabber up out of the corner and the corner stopped responding:
     * the handle was still there, just no longer where the corner is. It is fixed in {@code UIResizer}
     * rather than here, because it is true of anything scrollable and resizable, not just an editor.</p>
     */
    @Test
    public void theResizeHandleStaysInTheCornerAfterScrolling() {
        buildOverflowing();
        editor.generalStyle(g -> g.resize(com.crystalgui.style.property.visual.Resize.BOTH));
        settle();
        editor.updateWindow();
        settle();

        float scale = window.getUiScale();
        float cornerX = editor.getRuntimeCache().getX() + editor.getRuntimeCache().getWidth() - 2f;
        float cornerY = editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight() - 2f;

        UIElement before = window.getHoveredElement(cornerX * scale, cornerY * scale);
        assertNotNull(before);
        assertTrue("the corner should start out grabbable, found " + before.getClasses(),
                before.hasClass("__resizer__"));

        editor.setScrollImmediate(0f, 200f);
        settle();
        editor.updateWindow();
        settle();

        UIElement after = window.getHoveredElement(cornerX * scale, cornerY * scale);
        assertNotNull("nothing at the corner after scrolling", after);
        assertTrue("after scrolling the corner is " + after.getClasses() + ", not a resize handle",
                after.hasClass("__resizer__"));
    }

    /** The horizontal bar is pinned too, or it would scroll out from under the pointer mid-drag. */
    @Test
    public void theHorizontalBarStartsAfterTheGutter() {
        buildOverflowing();

        UIElement bar = childWithClass(com.crystalgui.ui.elements.ScrollerView.H_SCROLLER_CLASS);
        float barLeft = bar.getRuntimeCache().getX() - editor.getRuntimeCache().getX();
        assertTrue("the bar starts at " + barLeft + ", under a gutter " + editor.gutterWidth() + " wide",
                barLeft >= editor.gutterWidth() - 0.5f);
    }

    /**
     * <b>The scrollbars must paint above the text.</b>
     *
     * <p>Equal-z siblings paint in insertion order, and {@code ScrollerView}'s constructor creates the
     * scrollers while the lines are realised later — so at equal z the text drew <em>over</em> the
     * horizontal bar and the row straddling its strip spilled across it. Reserving viewport height does
     * not help: the overscan rows are realised on purpose and the scrollport clips to the full box.</p>
     */
    @Test
    public void theScrollbarsPaintAboveTheText() {
        buildOverflowing();

        UIElement bar = childWithClass(com.crystalgui.ui.elements.ScrollerView.H_SCROLLER_CLASS);
        UIElement line = childWithClass(TextEditor.LINE_CLASS);
        UIElement gutter = childWithClass(TextEditor.GUTTER_CLASS);

        float barZ = bar.getStyle().getGeneralGroup().zIndex();
        float lineZ = line.getStyle().getGeneralGroup().zIndex();
        float gutterZ = gutter.getStyle().getGeneralGroup().zIndex();

        assertTrue("a bar at z=" + barZ + " is not above text at z=" + lineZ, barZ > lineZ);
        assertTrue("the gutter at z=" + gutterZ + " must stay above the bars", gutterZ > barZ);
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
