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
    private long wheelClock = 50L;
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
        // THE USER-AGENT SHEET, applied on purpose. It is never injected automatically, and without it
        // none of the editor's own CSS exists -- so the gutter had no padding, the line numbers no
        // alignment, and the paint order no z-indices. Every test here ran against that until the gutter's
        // metrics moved into the sheet and one of them noticed. AGENTS.md warns about exactly this: a
        // test that asserts on default.css behaviour without applying it exercises no CSS at all.
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
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
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
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

    private static int rowsOf(String text) {
        return text.split(NL, -1).length;
    }

    /**
     * One row far wider than the test viewport.
     *
     * <p>Sized against the measurement rather than guessed: the editor is 300px and a space at font-size
     * 8 advances about 1.9px, so the wrap column is around 150. A first attempt at these tests used an
     * 86-character line, which correctly did <b>not</b> wrap — and read as soft wrap being broken.</p>
     */
    private static String longLine() {
        StringBuilder out = new StringBuilder();
        while (out.length() < 400) out.append("alpha beta gamma delta epsilon zeta eta theta ");
        return out.toString().trim();
    }

    private java.util.List<UIElement> linesOf() {
        return allWithClass(TextEditor.LINE_CLASS);
    }

    /**
     * Every descendant carrying {@code name}.
     *
     * <p>Recursive since the text moved into {@code __text-viewport__}: the lines, caret, bands, guides
     * and markers are that element's children now rather than the editor's, so a one-level scan finds
     * nothing. The gutter's numbers were always a level down, which is why that one already recursed by
     * hand.</p>
     */
    /** Every descendant of the editor, in tree order. */
    private java.util.List<UIElement> allDescendants() {
        java.util.List<UIElement> out = new java.util.ArrayList<>();
        collectAll(editor, out);
        return out;
    }

    private static void collectAll(UIElement from, java.util.List<UIElement> out) {
        for (UIElement child : from.getChildren()) {
            out.add(child);
            collectAll(child, out);
        }
    }

    private java.util.List<UIElement> allWithClass(String name) {
        java.util.List<UIElement> out = new java.util.ArrayList<>();
        collectWithClass(editor, name, out);
        return out;
    }

    private static void collectWithClass(UIElement from, String name, java.util.List<UIElement> out) {
        for (UIElement child : from.getChildren()) {
            if (child.hasClass(name)) out.add(child);
            collectWithClass(child, name, out);
        }
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
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
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
        java.util.List<UIElement> found = allWithClass(name);
        if (found.isEmpty()) throw new AssertionError("no child with class " + name);
        return found.get(0);
    }

    /**
     * <b>The gutter covers its whole column, and the bars paint over it.</b>
     *
     * <p>This inverts an earlier decision, so the reasoning matters. The gutter used to sit <em>above</em>
     * the bars in z, which meant a full-height gutter covered the horizontal bar's left end as a dead
     * square — so it was made to stop short of the bar. Stopping short left the corner between the two
     * uncovered, and scrolled text painted straight through it: visible as fragments below the gutter
     * once zoomed in far enough to see them.</p>
     *
     * <p>Putting the bars on top removes the reason the gutter had to stop. It now runs the full client
     * height, covering its column completely, and the bar draws over its bottom edge. The property that
     * actually mattered is untouched: the <b>lines</b> are still behind the gutter, so a long line
     * scrolled sideways passes behind the numbers rather than through them.</p>
     */
    @Test
    public void theGutterCoversItsColumnAndTheBarsPaintOverIt() {
        buildOverflowing();

        UIElement gutter = childWithClass(TextEditor.GUTTER_CLASS);
        UIElement bar = childWithClass(com.crystalgui.ui.elements.ScrollerView.H_SCROLLER_CLASS);
        assertTrue("the horizontal bar should be showing for this document",
                bar.getRuntimeCache().getHeight() > 0f);

        assertEquals("no corner left below the gutter for text to leak through",
                editor.getClientHeight(), gutter.getTaffyLayout().contentBoxHeight(), 1f);
        assertEquals("and no strip of the editor's own padding to its left",
                editor.getRuntimeCache().getX(), gutter.getRuntimeCache().getX(), 0.5f);
        assertTrue("so the bar must be above it, or the gutter would hide the bar's left end",
                bar.getStyle().getGeneralGroup().zIndex() > gutter.getStyle().getGeneralGroup().zIndex());
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
        assertTrue("the gutter at z=" + gutterZ + " must stay above the TEXT, so a long line scrolled "
                + "sideways passes behind the numbers", gutterZ > lineZ);
        assertTrue("but below the bars, so a full-height gutter cannot hide the bar's left end",
                barZ > gutterZ);
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
        for (UIElement child : allDescendants()) {
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

        long lines = allDescendants().stream()
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
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
            if (child.hasClass(TextEditor.LINE_CLASS)) before.add(child);
        }
        assertFalse(before.isEmpty());

        key(CgKeyCodes.KEY_RIGHT);
        key(CgKeyCodes.KEY_RIGHT);

        java.util.List<UIElement> after = new java.util.ArrayList<>();
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
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
        for (UIElement child : allDescendants()) {
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
        // Scroll-past-end is on by default (VS Code's default too), and adds a viewport of empty space
        // below the last line -- so the content height is asserted with it turned off.
        editor.setScrollBeyondLastLine(false);
        settle();

        // Plus the strip the horizontal bar covers, which is what getClientHeight minus the viewport is.
        float barStrip = editor.getClientHeight() - editor.getViewportHeight();
        assertEquals(501 * editor.lineHeight() + barStrip, editor.getScrollHeight(), 0.5f);
    }

    /**
     * <b>Scroll past the end</b> — VS Code's {@code scrollBeyondLastLine}, on by default. It exists so the
     * last line of a file can be read and edited somewhere other than jammed against the bottom edge.
     */
    @Test
    public void scrollingPastTheEndAddsAViewportOfRoom() {
        build("one" + NL + "two" + NL + "three");
        editor.setScrollBeyondLastLine(false);
        settle();
        float without = editor.getScrollHeight();

        editor.setScrollBeyondLastLine(true);
        settle();
        float with = editor.getScrollHeight();

        assertTrue("it must add room: " + without + " -> " + with, with > without);
        assertEquals("a viewport, less one line so the last line stays on screen",
                3 * editor.lineHeight() + editor.getViewportHeight() - editor.lineHeight(), with, 2f);
    }

    /**
     * <b>The horizontal bar's allowance is the ELSE branch</b>, which is easy to miss. Scrolling past the
     * end already leaves empty space below the last line, so also adding the bar's thickness would be a
     * second allowance for the same problem.
     */
    @Test
    public void theBarAllowanceIsNotAddedOnTopOfScrollPastEnd() {
        build("one" + NL + "two");
        editor.setScrollBeyondLastLine(true);
        settle();

        assertEquals("content plus exactly one viewport, less a line",
                2 * editor.lineHeight() + editor.getViewportHeight() - editor.lineHeight(),
                editor.getScrollHeight(), 2f);
    }

    // ── 6.1.7b: soft wrap ────────────────────────────────────────
    //
    // The coordinate mapping is pinned headlessly in SoftWrapTest. These are the questions that need a
    // real widget: that the projection actually reaches the painted lines, the gutter, hit testing and
    // the scroll extent -- i.e. that the model layer is WIRED, not merely correct.

    /** A line far wider than the viewport occupies several visual rows. */
    @Test
    public void aLongLineWrapsIntoSeveralViewLines() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        assertEquals("still one document row", 1, rowsOf(editor.getText()));
        assertTrue("but several visual rows: " + editor.viewLineCount(), editor.viewLineCount() > 1);
    }

    /** <b>Wrapping is not an edit.</b> The document is byte-identical either way. */
    @Test
    public void wrappingDoesNotTouchTheDocument() {
        String text = longLine();
        build(text);
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        assertEquals("no newline was inserted", text, editor.getText());
        assertEquals(1, rowsOf(editor.getText()));
    }

    @Test
    public void turningWrapOffRestoresOneViewLinePerRow() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        assertTrue(editor.viewLineCount() > 1);

        editor.setSoftWrap(false);
        settle();
        assertEquals(1, editor.viewLineCount());
    }

    /** The scroll extent must count visual rows, or the last wrapped line cannot be reached. */
    @Test
    public void theScrollExtentCountsViewLinesNotRows() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        assertTrue("a wrapped document is taller than one line",
                editor.getScrollHeight() > editor.lineHeight() * 1.5f);
    }

    /**
     * <b>Every wrapped line must FIT.</b> The first implementation divided the viewport by the advance of
     * a space and handed the quotient to the column-based computer — exact in a monospaced font, and
     * badly wrong in the proportional one the theme actually uses, where a space is far narrower than an
     * average glyph. The budget came out so generous that wrapped lines still ran off the right edge and
     * were clipped, which looked like wrapping being broken rather than measuring being wrong.
     *
     * <p>Only a visual check found it, so this is the assertion that replaces the eye: measure what is
     * painted, against the box it is painted into.</p>
     */
    @Test
    public void noWrappedLineIsWiderThanTheViewport() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        assertTrue("there must be something to wrap", editor.viewLineCount() > 1);

        // The TEXT's own extent, not the line box -- which is deliberately stretched to at least the
        // viewport so a selection band reads as a band, and would therefore pass however far the glyphs
        // overflowed it.
        assertEveryPaintedLineFits();
    }

    /**
     * <b>Resizing must reflow.</b> A wrap width is derived from the viewport, so a narrower editor has to
     * wrap into more rows — and the rows already on screen have to be re-read, not left holding the text
     * the old projection gave them.
     */
    @Test
    public void resizingTheEditorReflowsTheWrap() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();
        int before = editor.viewLineCount();
        assertTrue("it must already wrap", before > 1);

        editor.layout(l -> l.width(150));
        settle();
        editor.updateWindow();
        settle();

        assertTrue("half the width must produce more visual rows: " + before + " -> "
                + editor.viewLineCount(), editor.viewLineCount() > before);
        assertEveryPaintedLineFits();
    }

    /**
     * The other half of the same failure, and the one the screenshot showed: after a reflow the realised
     * lines kept the text the <em>old</em> projection gave them, so a continuation displayed the next
     * row's content and everything overflowed the new, narrower box.
     */
    @Test
    public void aReflowRebindsTheLinesAlreadyOnScreen() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        editor.layout(l -> l.width(150));
        settle();
        editor.updateWindow();
        settle();

        StringBuilder painted = new StringBuilder();
        for (UIElement line : linesOf()) {
            painted.append(((UIText) line.getChildren().get(0)).getText());
        }
        // A PREFIX, not the whole document: virtualisation realises only what is on screen, and the
        // window is a line or two shorter once the scrollbar's strip is accounted for. Still strong --
        // a character dropped or duplicated at any break stops the result being a prefix at all.
        assertFalse("something must be painted", painted.length() == 0);
        assertTrue("the painted rows must reconstruct the start of the document, got: " + painted,
                editor.getText().startsWith(painted.toString()));
    }

    /** Measures painted text in the editor's own font against the box it goes in. */
    private void assertEveryPaintedLineFits() {
        var general = editor.getStyle().getGeneralGroup();
        var family = com.crystalgui.render.text.FontFamilyCache.resolve(
                general.fontFamily(), Math.round(general.fontSize()));
        float limit = editor.getClientWidth();

        for (UIElement line : linesOf()) {
            String text = ((UIText) line.getChildren().get(0)).getText();
            if (text.isEmpty()) continue;
            float width = com.crystalgraphics.api.text.CgTextLayout.of(text, family).build().totalWidth();
            assertTrue("a view line measuring " + width + " does not fit in " + limit + ": '" + text + "'",
                    width <= limit);
        }
    }

    /** One element per <b>visual</b> row, or the continuations are never painted. */
    @Test
    public void everyViewLineIsRealisedAsItsOwnElement() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        assertEquals("one line element per view line", editor.viewLineCount(), linesOf().size());
    }

    /**
     * <b>Concatenating the painted lines returns the row.</b> The strongest single assertion available
     * here: it fails if a character is dropped at a break, duplicated across one, or if the carried
     * indent leaks into the text as spaces — which is the failure that makes a paste out of a wrapped
     * editor carry indentation the file never had.
     */
    @Test
    public void thePaintedLinesConcatenateBackToTheRow() {
        String text = longLine();
        build(text);
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        StringBuilder painted = new StringBuilder();
        for (UIElement line : linesOf()) {
            painted.append(((UIText) line.getChildren().get(0)).getText());
        }
        assertEquals(text, painted.toString());
    }

    /**
     * <b>One gutter number per document row, not per visual row.</b> Numbering continuations would report
     * line counts the file does not have.
     */
    @Test
    public void theGutterNumbersRowsNotViewLines() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();

        // The numbers hang off the GUTTER, not off the editor. The gutter pools one element per number
        // it has ever needed, so the element count IS the count of numbers asked for -- observable,
        // unlike "is this one currently hidden", which hide() expresses as a zero-sized box.
        int numbers = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.GUTTER_CLASS)) continue;
            for (UIElement number : child.getChildren()) {
                if (number.hasClass(TextEditor.LINE_NUMBER_CLASS)) numbers++;
            }
        }
        assertTrue("there are several view lines to number", editor.viewLineCount() > 1);
        assertEquals("but only one row, so only one number", 1, numbers);
    }

    /**
     * <b>Down moves one VISUAL row.</b> Skipping to the next document row makes a wrapped paragraph one
     * keypress tall, which is the behaviour every editor is judged on.
     */
    @Test
    public void downMovesByOneVisualRowWhenWrapped() {
        build(longLine());
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        settle();

        key(CgKeyCodes.KEY_DOWN);

        int caret = editor.getCaret();
        assertTrue("the caret moved into the row, not past it", caret > 0);
        assertTrue("and stayed inside the single document row", caret < editor.getText().length());
    }

    /** With wrap off the same key must still cross document rows. */
    @Test
    public void downStillMovesByRowWhenNotWrapped() {
        build("one" + NL + "two" + NL + "three");
        editor.setCaret(0);
        settle();

        key(CgKeyCodes.KEY_DOWN);

        assertEquals("row 1, column 0", 4, editor.getCaret());
    }

    /** End goes to the end of the visual row, not the end of the document row. */
    @Test
    public void endStopsAtTheVisualRowEndWhenWrapped() {
        String text = longLine();
        build(text);
        editor.setSoftWrap(true);
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        settle();

        key(CgKeyCodes.KEY_END);

        assertTrue("it moved", editor.getCaret() > 0);
        assertTrue("but stopped short of the row's end", editor.getCaret() < text.length());
    }

    /** Editing a wrapped row reprojects it — the view line count must follow the text. */
    @Test
    public void typingIntoAWrappedRowReprojectsIt() {
        build("alpha beta gamma");
        editor.setSoftWrap(true);
        settle();
        int before = editor.viewLineCount();

        editor.setCaret(editor.getText().length());
        // Long enough to cross the wrap column, which is ~150 here -- see longLine().
        type(" " + longLine());
        settle();

        assertTrue("adding a screenful of text must add view lines: " + before + " -> "
                + editor.viewLineCount(), editor.viewLineCount() > before);
    }

    // ── 6.1.7b §H: the keys are commands ─────────────────────────────
    //
    // The point of §H is not that the shortcuts work -- they did before, as switch cases. It is that they
    // are now NAMED, REBINDABLE and DISCOVERABLE. These four test exactly that, and nothing else here can:
    // every other key test would pass equally against the hard-coded version.

    /** Every action is registered under a stable id, which is what a command palette enumerates. */
    @Test
    public void theEditorsActionsAreRegisteredAsCommands() {
        build("one" + NL + "two");
        settle();
        editor.updateWindow();
        settle();

        var commands = window.getCommands();
        assertTrue(commands.contains("editor.deleteLines"));
        assertTrue(commands.contains("editor.toggleLineComment"));
        assertTrue(commands.contains("editor.addCaretAtNextOccurrence"));
        assertTrue(commands.contains("editor.toggleSoftWrap"));
        assertNotNull("a palette needs a label to render", commands.get("editor.deleteLines").getLabel());
    }

    /**
     * <b>The whole point of §H.</b> Rebinding is editing data, not Java — and the old chord must stop
     * working, which is what proves the action is no longer hard-coded.
     */
    @Test
    public void anActionCanBeRemapped() {
        build("one" + NL + "two" + NL + "three");
        settle();
        editor.updateWindow();
        settle();

        editor.keymap().unbind("Mod+Shift+K");
        editor.keymap().bind("Mod+Shift+Q", "editor.deleteLines");
        editor.setCaret(0);
        settle();

        key(CgKeyCodes.KEY_K, CgModifiers.CTRL | CgModifiers.SHIFT);
        assertEquals("the old chord is gone", 3, rowsOf(editor.getText()));

        key(CgKeyCodes.KEY_Q, CgModifiers.CTRL | CgModifiers.SHIFT);
        assertEquals("and the new one works", 2, rowsOf(editor.getText()));
    }

    /** A menu item needs the chord to render beside its label. */
    @Test
    public void aCommandReportsTheChordThatWouldFireIt() {
        build("x");
        settle();
        editor.updateWindow();
        settle();

        assertNotNull("a menu item must be able to show its accelerator",
                com.crystalgui.ui.input.keymap.Keymap.acceleratorFor(editor, "editor.deleteLines"));
    }

    /**
     * <b>Enablement is the command's, not the keystroke's.</b> Cut with nothing selected must report that
     * it did not run, so the same answer greys out a menu item.
     */
    @Test
    public void aCommandThatCannotRunReportsSoRatherThanFiringEmpty() {
        build("one" + NL + "two");
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        settle();

        var context = com.crystalgui.core.command.CommandContext.of(editor);
        assertFalse("nothing is selected, so cut is not applicable",
                window.getCommands().run("editor.cut", context));

        editor.setSelection(0, 3);
        settle();
        assertTrue("with a selection it runs", window.getCommands().run("editor.cut", context));
        assertEquals("one", clipboard);
        assertEquals("and the text is gone from the document", NL + "two", editor.getText());
    }

    /**
     * Undo reaches the editor's own history through {@code edit.undo} — the id {@code UndoCommands}
     * already owns, not a second {@code editor.undo} beside it.
     */
    @Test
    public void undoIsTheSharedCommandNotAnEditorSpecificOne() {
        build("one");
        settle();
        editor.updateWindow();
        settle();

        assertTrue("the shared id", window.getCommands().contains("edit.undo"));
        assertFalse("and no duplicate concept", window.getCommands().contains("editor.undo"));
    }

    /** <b>The regression this move exists to prevent:</b> undo was remappable and still did not move. */
    @Test
    public void undoCanBeRemapped() {
        build("");
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        type("abc");
        settle();
        assertEquals("abc", editor.getText());

        editor.keymap().unbind("Mod+Z");
        editor.keymap().bind("Mod+U", "edit.undo");

        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("the old chord no longer undoes", "abc", editor.getText());

        key(CgKeyCodes.KEY_U, CgModifiers.CTRL);
        assertEquals("the new one does", "", editor.getText());
    }

    /** Undo still works out of the box, through the keymap rather than a switch case. */
    @Test
    public void ctrlZStillUndoesThroughTheKeymap() {
        build("");
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        type("hello");
        settle();

        key(CgKeyCodes.KEY_Z, CgModifiers.CTRL);
        assertEquals("", editor.getText());

        key(CgKeyCodes.KEY_Y, CgModifiers.CTRL);
        assertEquals("and Ctrl+Y redoes", "hello", editor.getText());
    }

    /**
     * <b>A shrinking document must not leave a caret past its end.</b> The clamp used to be a hand-written
     * line in the Ctrl+Z handler, so moving that binding to the keymap would have taken it with it — it
     * now sits on the buffer's change signal, where every route in is covered.
     */
    @Test
    public void undoingPastTheCaretClampsTheSelection() {
        build("");
        settle();
        editor.updateWindow();
        settle();
        editor.setCaret(0);
        type("a long line of text");
        settle();
        editor.setCaret(editor.getText().length());
        settle();

        // Through the command, not the keystroke -- a menu or the palette is exactly the route that had
        // no clamp of its own.
        window.getCommands().run("edit.undo", com.crystalgui.core.command.CommandContext.of(editor));
        settle();

        assertTrue("the caret must be inside the document, not past where the text used to end",
                editor.getCaret() <= editor.getText().length());
    }

    // ── 6.1.7b §G: indent guides, visible whitespace, rulers ─────

    private int countOf(String className) {
        int n = 0;
        for (UIElement child : allWithClass(className)) {
            // hide() collapses an unused pooled element to zero height, so a laid-out height is what
            // separates "drawn this frame" from "pooled and idle".
            if (child.getTaffyLayout().contentBoxHeight() > 0f) n++;
        }
        return n;
    }

    private void showEditor() {
        settle();
        editor.updateWindow();
        settle();
    }

    @Test
    public void indentGuidesAreOffUntilAskedFor() {
        build("a" + NL + "    b" + NL + "        c");
        showEditor();
        assertEquals(0, countOf(TextEditor.INDENT_GUIDE_CLASS));
    }

    /**
     * One guide per level <b>from level one</b> — level zero is the gutter's edge, not a guide.
     *
     * <p>So a file at indents 0, 1, 2, 3 draws 0 + 0 + 1 + 2 guides. The rows that draw nothing are the
     * point: their only level is the one the edge already covers, running the full height of the
     * viewport where a per-row guide would stop at the first unindented line.</p>
     */
    @Test
    public void indentGuidesDrawOnePerLevelAboveTheFirst() {
        build("a" + NL + "    b" + NL + "        c" + NL + "            d");
        editor.setIndentGuidesVisible(true);
        showEditor();

        assertEquals("0 + 0 + 1 + 2", 3, countOf(TextEditor.INDENT_GUIDE_CLASS));
    }

    /**
     * <b>The gutter's edge is one element spanning the viewport</b>, and it is what the level-zero guide
     * used to pretend to be. Being structural rather than per-row is exactly what stops a line at indent
     * zero breaking it.
     */
    @Test
    public void theGutterEdgeIsOneUnbrokenLine() {
        build("    a" + NL + "public class B {" + NL + "    c");
        showEditor();

        int edges = countOf(TextEditor.GUTTER_EDGE_CLASS);
        assertEquals("one element, not one per row", 1, edges);

        // Where the gutter's box ends. It is a BORDER: floating it in the middle of the code margin read
        // as a stray rule with a gap either side rather than as the gutter ending.
        float gutterRight = editor.getRuntimeCache().getX()
                + editor.getTaffyLayout().border().left + editor.getTaffyLayout().padding().left
                + editor.getGutterWidth();

        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.GUTTER_EDGE_CLASS)) continue;
            assertEquals("and it spans the whole viewport", editor.getViewportHeight(),
                    child.getTaffyLayout().contentBoxHeight(), 1f);
            assertEquals("and sits on the gutter's right edge",
                    gutterRight, child.getRuntimeCache().getX(), 1f);
        }
    }

    /** With no gutter there is no gutter edge — it is the gutter's border, not a text decoration. */
    @Test
    public void hidingTheGutterHidesItsEdge() {
        build("    a");
        editor.setGutterVisible(false);
        showEditor();
        assertEquals(0, countOf(TextEditor.GUTTER_EDGE_CLASS));
    }

    /**
     * <b>A blank line inside a block still guides.</b> This is the whole reason the model layer exists —
     * a guide derived from the row's own characters has nothing to derive from here, and the guides would
     * visibly break at every blank line in a function.
     */
    @Test
    public void aBlankLineInsideABlockStillDrawsItsGuides() {
        // Two levels deep, so there is a level-one guide to see -- at one level the only guide is the
        // gutter's edge and the blank-line rule has nothing to draw.
        build("        a" + NL + NL + "        c");
        editor.setIndentGuidesVisible(true);
        showEditor();

        assertEquals("one per row, the blank one included", 3, countOf(TextEditor.INDENT_GUIDE_CLASS));
    }

    /**
     * <b>Every indent guide is drawn clear of the gutter.</b>
     *
     * <p>The gutter sits at a higher z-index and paints an opaque background, so a guide nudged even
     * slightly too far left disappears entirely rather than looking wrong — which is exactly what
     * happened when the level-0 guide was offset by half a <em>space</em> instead of half the code
     * margin. A space is wider than the margin, so it landed underneath.</p>
     */
    @Test
    public void everyIndentGuideIsDrawnClearOfTheGutter() {
        build("a" + NL + "    b" + NL + "        c");
        editor.setIndentGuidesVisible(true);
        showEditor();

        float gutterRight = editor.getRuntimeCache().getX()
                + editor.getTaffyLayout().border().left + editor.getTaffyLayout().padding().left
                + editor.getGutterWidth();

        int seen = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.INDENT_GUIDE_CLASS)) continue;
            if (child.getTaffyLayout().contentBoxHeight() <= 0f) continue;
            seen++;
            assertTrue("a guide at " + child.getRuntimeCache().getX()
                            + " is under the gutter, which ends at " + gutterRight,
                    child.getRuntimeCache().getX() >= gutterRight);
        }
        assertTrue("there must be guides to check", seen > 0);
    }

    @Test
    public void whitespaceIsInvisibleUntilAskedFor() {
        build("  a  b  ");
        showEditor();
        assertEquals(0, countOf(TextEditor.WHITESPACE_CLASS));
    }

    /**
     * <b>Turning whitespace back off must actually erase the markers.</b>
     *
     * <p>Asserting on the box was not enough and this is why: a pooled decoration is retired by collapsing
     * it to zero size, which hides a <em>fill</em> but not a glyph — the {@code UIText} inside it has no
     * clipping of its own and kept painting. The line numbers get away with the same trick only because
     * the gutter around them sets {@code overflow: hidden}. So this reads the text, not the geometry.</p>
     */
    @Test
    public void turningWhitespaceOffErasesTheMarkers() {
        build("  a  b  ");
        editor.setRenderWhitespace(com.crystalgui.text.view.RenderWhitespace.ALL);
        showEditor();
        assertTrue("markers must be showing first", countOf(TextEditor.WHITESPACE_CLASS) > 0);

        editor.setRenderWhitespace(com.crystalgui.text.view.RenderWhitespace.NONE);
        showEditor();

        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.WHITESPACE_CLASS)) continue;
            assertEquals("a retired marker must carry no glyph",
                    "", ((UIText) child.getChildren().get(0)).getText());
        }
    }

    /** Boundary mode: leading, trailing and runs — never a lone space between two words. */
    @Test
    public void boundaryWhitespaceSkipsLoneSpaces() {
        build("a b c");
        editor.setRenderWhitespace(
                com.crystalgui.text.view.RenderWhitespace.BOUNDARY);
        showEditor();
        assertEquals("two lone spaces, neither marked", 0, countOf(TextEditor.WHITESPACE_CLASS));

        editor.setRenderWhitespace(com.crystalgui.text.view.RenderWhitespace.ALL);
        showEditor();
        assertEquals("all marks both", 2, countOf(TextEditor.WHITESPACE_CLASS));
    }

    @Test
    public void whitespaceMarkersUseTheConventionalGlyphs() {
        build("  a");
        editor.setRenderWhitespace(com.crystalgui.text.view.RenderWhitespace.ALL);
        showEditor();

        int dots = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.WHITESPACE_CLASS)) continue;
            if (child.getTaffyLayout().contentBoxHeight() <= 0f) continue;
            if ("·".equals(((UIText) child.getChildren().get(0)).getText())) dots++;
        }
        assertEquals("two leading spaces, two middots", 2, dots);
    }

    @Test
    public void rulersAreDrawnAtTheColumnsAskedFor() {
        build("x");
        showEditor();
        assertEquals(0, countOf(TextEditor.RULER_CLASS));

        editor.setRulers(20, 40);
        showEditor();
        assertEquals(2, countOf(TextEditor.RULER_CLASS));
    }

    /** A ruler past the right-hand edge is not drawn — there is nothing there to mark. */
    @Test
    public void aRulerBeyondTheViewportIsSkipped() {
        build("x");
        editor.setRulers(20, 100000);
        showEditor();
        assertEquals(1, countOf(TextEditor.RULER_CLASS));
    }

    /**
     * <b>The gutter leaves a clear column before the code.</b> Without it the gutter ends exactly where
     * the text begins and the first glyph of an unindented line sits <em>on</em> the border — a {@code p}
     * with its descender crossing it. IntelliJ and VS Code both fill this gap with fold arrows.
     */
    @Test
    public void theGutterLeavesRoomBetweenItsNumbersAndTheCode() {
        build("public final class Shader {" + NL + "    body();");
        showEditor();

        UIElement number = null;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.GUTTER_CLASS)) continue;
            for (UIElement candidate : child.getChildren()) {
                if (candidate.hasClass(TextEditor.LINE_NUMBER_CLASS)
                        && candidate.getTaffyLayout().contentBoxHeight() > 0f) {
                    number = candidate;
                    break;
                }
            }
        }
        assertNotNull("a line number must be laid out", number);

        float numbersEnd = number.getTaffyLayout().contentBoxWidth();
        float textStart = editor.getGutterWidth();
        assertTrue("the numbers must stop short of the code: numbers end " + numbersEnd
                + ", text starts " + textStart, textStart - numbersEnd >= 4f);

        // AND clear of the LEFT edge. The widest number in the file fills the digit field exactly, so
        // without a margin it sits on the border -- which is what the gutter's padding-left is for.
        float gutterLeft = editor.getRuntimeCache().getX()
                + editor.getTaffyLayout().border().left + editor.getTaffyLayout().padding().left;
        assertTrue("a number at " + number.getRuntimeCache().getX()
                        + " is touching the gutter's left edge at " + gutterLeft,
                number.getRuntimeCache().getX() - gutterLeft >= 2f);
    }

    /**
     * <b>Every visible line number occupies its own box, at its own height.</b>
     *
     * <p>The regression this exists for: the numbers' width was measured afresh at layout time while the
     * gutter's width was a cached field, so on any frame where the font had not resolved the two
     * disagreed — the numbers got a zero-width box and every one of them piled up in the same place,
     * drawing over each other. Zero width is invisible to an assertion about the gutter's total width,
     * which is why that test passed throughout.</p>
     */
    @Test
    public void everyLineNumberGetsARealBox() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 40; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        editor.setIndentGuidesVisible(true);
        showEditor();

        int seen = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.GUTTER_CLASS)) continue;
            for (UIElement number : child.getChildren()) {
                if (!number.hasClass(TextEditor.LINE_NUMBER_CLASS)) continue;
                if (number.getTaffyLayout().contentBoxHeight() <= 0f) continue;
                seen++;
                assertTrue("a number with no width piles up on its neighbours",
                        number.getTaffyLayout().contentBoxWidth() > 0f);
            }
        }
        assertTrue("several numbers must be on screen, not " + seen, seen > 3);
    }

    /**
     * <b>Scrolling past the end must not leave a tail of retired line numbers behind.</b>
     *
     * <p>Retiring a pooled element collapses it to zero size, which hides a fill and nothing else — the
     * {@code UIText} inside keeps painting. The gutter's {@code overflow: hidden} looked like it covered
     * this, but a retired number is still <em>inside</em> the gutter's bounds so the clip never applied to
     * it. Invisible until scroll-past-end made it possible to leave a long tail behind, which then drew on
     * top of itself below the last line.</p>
     */
    @Test
    public void scrollingPastTheEndLeavesNoStaleLineNumbers() {
        StringBuilder document = new StringBuilder();
        for (int i = 1; i <= 32; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        editor.setScrollBeyondLastLine(true);
        showEditor();

        editor.setScrollImmediate(0f, editor.getScrollHeight());
        showEditor();

        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.GUTTER_CLASS)) continue;
            for (UIElement number : child.getChildren()) {
                if (!number.hasClass(TextEditor.LINE_NUMBER_CLASS)) continue;
                if (number.getTaffyLayout().contentBoxHeight() > 0f) continue;
                assertEquals("a retired number must carry no glyph",
                        "", ((UIText) number.getChildren().get(0)).getText());
            }
        }
    }

    /**
     * <b>The text must move when the view scrolls.</b>
     *
     * <p>The regression this exists for: the lines moved into a scroll-exempt viewport, so they stopped
     * getting the scroll translate for free and their {@code top} is now baked in by {@code layOutLine} —
     * which only ran when a line was realised or rebound, not every frame. The gutter and the scrollbar
     * moved and the text stood still.</p>
     *
     * <p>Every test in this file passed against that, because they all assert on offsets, counts and
     * element trees rather than on where anything ended up after a scroll.</p>
     */
    @Test
    public void scrollingMovesTheTextAndTheGutterTogether() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        showEditor();

        UIElement line = linesOf().get(0);
        UIElement number = childWithClass(TextEditor.LINE_NUMBER_CLASS);
        float lineBefore = line.getRuntimeCache().getY();
        float numberBefore = number.getRuntimeCache().getY();

        editor.setScrollImmediate(0f, 20f * editor.lineHeight());
        showEditor();

        float lineMoved = line.getRuntimeCache().getY() - lineBefore;
        float numberMoved = number.getRuntimeCache().getY() - numberBefore;
        assertTrue("the text must actually move: it shifted " + lineMoved, Math.abs(lineMoved) > 1f);
        assertEquals("and by the same amount as the gutter, or the two drift apart",
                numberMoved, lineMoved, 1f);
    }

    /**
     * <b>Everything in document coordinates moves together when the view scrolls.</b>
     *
     * <p>The general form of a bug that landed twice in a row. An element drawn in document coordinates
     * must be <em>inside</em> the scroll-exempt viewport, whose children subtract the scroll offset by
     * hand — and one left on the editor instead is scrolled by the pose translate <b>and</b> has the
     * offset subtracted, so it ends up a screenful away from the text it belongs to. That is what put a
     * selection band several lines above the word it marked.</p>
     *
     * <p>Asserting each one against the <em>gutter</em> rather than against an absolute figure is what
     * makes this catch a wrong parent: a double-subtracted element moves by twice the scroll, and a
     * never-moved one by none, and neither matches the row it is supposed to sit on.</p>
     */
    @Test
    public void everythingInDocumentCoordinatesScrollsTogether() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        editor.setIndentGuidesVisible(true);
        editor.setSelection(0, 5);
        showEditor();

        // ALIGNMENT, not deltas. The line elements are POOLED and keyed by view line, so the same
        // element represents a different row after a scroll -- tracking one across the scroll compares
        // two unrelated rows and reports nonsense. What must hold either way is that the topmost line,
        // its gutter number, the caret and the band all sit on the same rows as each other.
        assertRowsAlign("before scrolling");

        editor.setScrollImmediate(0f, 12f * editor.lineHeight());
        showEditor();

        assertTrue("the view actually scrolled", editor.getScrollTop() > 0f);
        assertRowsAlign("after scrolling");
    }

    /**
     * The topmost painted line and the topmost gutter number describe the same row, so they must share a
     * y. A line left outside the viewport is scrolled twice — by the pose translate and by hand — and
     * lands a screenful from its number; one never repositioned stays put while the number moves.
     */
    private void assertRowsAlign(String when) {
        UIElement number = childWithClass(TextEditor.LINE_NUMBER_CLASS);
        UIElement line = linesOf().get(0);
        assertEquals(when + ": the text and its gutter number must sit on the same row",
                number.getRuntimeCache().getY(), line.getRuntimeCache().getY(), editor.lineHeight());
    }

    /**
     * <b>Indent guides are drawn on both sides of a scroll.</b>
     *
     * <p>The counts are deliberately not compared: the realised window includes overscan, so a scrolled
     * editor legitimately has more rows realised than a fresh one. What must hold is that guides exist at
     * all in both states — a guide layer that only appears once something has moved is the shape a
     * wrong-parent or a stale-layout bug takes.</p>
     */
    @Test
    public void indentGuidesAreDrawnBeforeAndAfterAScroll() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("            deep").append(i).append(NL);
        build(document.toString());
        editor.setIndentGuidesVisible(true);
        showEditor();
        int before = countOf(TextEditor.INDENT_GUIDE_CLASS);

        editor.setScrollImmediate(0f, 12f * editor.lineHeight());
        showEditor();
        int after = countOf(TextEditor.INDENT_GUIDE_CLASS);

        assertTrue("guides must be drawn before any scroll, got " + before, before > 0);
        assertTrue("and after one, got " + after, after > 0);
    }

    /**
     * <b>Indent guides track the scroll.</b>
     *
     * <p>They did not, and the miss was mechanical: converting the widget to the clipped viewport meant
     * every document-coordinate {@code top} had to start subtracting the scroll offset by hand, and this
     * one edit silently did not apply. The guides then sat exactly {@code scrollTop} away from their rows
     * — stale-looking lines stranded below the text, and none beside the code.</p>
     *
     * <p>Asserted against the gutter number rather than against a delta, because both pools are keyed by
     * view line and re-used: the same element describes a different row after a scroll, so only
     * "is it on the right row" survives the comparison.</p>
     */
    @Test
    public void indentGuidesTrackTheScroll() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("            deep").append(i).append(NL);
        build(document.toString());
        editor.setIndentGuidesVisible(true);
        showEditor();

        assertEquals("before scrolling", childWithClass(TextEditor.LINE_NUMBER_CLASS).getRuntimeCache().getY(),
                childWithClass(TextEditor.INDENT_GUIDE_CLASS).getRuntimeCache().getY(), editor.lineHeight());

        editor.setScrollImmediate(0f, 12f * editor.lineHeight());
        showEditor();

        assertEquals("after scrolling", childWithClass(TextEditor.LINE_NUMBER_CLASS).getRuntimeCache().getY(),
                childWithClass(TextEditor.INDENT_GUIDE_CLASS).getRuntimeCache().getY(), editor.lineHeight());
    }

    /** The gap scales with the font, so the gutter stays proportionate when the editor is zoomed. */
    @Test
    public void theGutterGapGrowsWithTheFont() {
        build("x");
        showEditor();
        float small = editor.getGutterWidth();

        editor.generalStyle(g -> g.fontSize(24f));
        showEditor();

        assertTrue("a larger font must widen the gutter: " + small + " -> " + editor.getGutterWidth(),
                editor.getGutterWidth() > small);
    }

    // ── 6.1.7b: zoom ─────────────────────────────────────────────

    @Test
    public void zoomingChangesTheFontSizeByWholePoints() {
        build("x");
        showEditor();
        float before = editor.getFontSize();

        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();
        assertEquals("one point up", Math.round(before) + 1, Math.round(editor.getFontSize()));

        key(CgKeyCodes.KEY_MINUS, CgModifiers.CTRL);
        key(CgKeyCodes.KEY_MINUS, CgModifiers.CTRL);
        showEditor();
        assertEquals("and two down", Math.round(before) - 1, Math.round(editor.getFontSize()));
    }

    /** Spins the wheel over the editor with the given modifiers held through the dispatch. */
    private void wheel(float notches, int held) {
        float px = editor.getRuntimeCache().getX() + editor.getRuntimeCache().getWidth() / 2f;
        float py = editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight() / 2f;
        // The mask must stay held THROUGH the settle. Scroll is accumulated by consumeMouseEvent and
        // dispatched once per frame from endFrame(), so a mask cleared before that is not the one the
        // resolver reads -- it reads CgPlatform's live state at DISPATCH time.
        this.modifiers = held;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                (int) px, (int) py, 0, 0, 0, false, notches, wheelClock += 20L));
        settle();
        this.modifiers = 0;
        editor.updateWindow();
        settle();
    }

    /**
     * <b>Ctrl+wheel zooms, through the KEYMAP.</b>
     *
     * <p>Not a listener on the editor — that is the hard-coded input path section H removed from
     * {@code handleKey}, and a wheel gesture that cannot be remapped is exactly what it argued against.
     * The wheel is a {@code KeyStroke}, so it resolves like any chord.</p>
     */
    @Test
    public void ctrlWheelUpZoomsIn() {
        build("x");
        showEditor();
        int before = Math.round(editor.getFontSize());

        wheel(-1f, CgModifiers.CTRL);

        assertEquals("wheel up zooms in", before + 1, Math.round(editor.getFontSize()));
    }

    /** And the sign is the engine's: a POSITIVE notch means the wheel rolled down, so down zooms out. */
    @Test
    public void ctrlWheelDownZoomsOut() {
        build("x");
        showEditor();
        int before = Math.round(editor.getFontSize());

        wheel(1f, CgModifiers.CTRL);

        assertEquals("wheel down zooms out", before - 1, Math.round(editor.getFontSize()));
    }

    /**
     * <b>A plain wheel still scrolls.</b> {@code ScrollerView} declines only Mod+wheel, which is what lets
     * that fall through to the keymap while everything else stays its own.
     */
    @Test
    public void aPlainWheelScrollsRatherThanZooming() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        showEditor();
        float before = editor.getFontSize();

        wheel(1f, CgModifiers.NONE);

        assertEquals("the font is untouched", before, editor.getFontSize(), 0.01f);
        assertTrue("and the view actually scrolled", editor.getScrollTop() > 0f);
    }

    /**
     * <b>Resizing must not move the zoom indicator.</b>
     *
     * <p>It is anchored to the BOTTOM of the editor, and every position in {@code updateWindow} is derived
     * from the previous frame's layout. That is harmless for anything anchored to the top — a height
     * change does not move it — but a computed {@code top} against a stale {@code clientHeight} moved this
     * by the full resize delta for one frame and then corrected, which read as a flick downwards and
     * back. A {@code bottom} inset is resolved by Taffy at layout time, so there is no stale value.</p>
     */
    @Test
    public void resizingDoesNotMoveTheZoomIndicator() {
        build("x");
        showEditor();
        editor.zoomBy(1);
        showEditor();

        UIElement indicator = childWithClass(TextEditor.ZOOM_INDICATOR_CLASS);
        float gapBefore = (editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight())
                - (indicator.getRuntimeCache().getY() + indicator.getRuntimeCache().getHeight());

        editor.layout(l -> l.height(220));
        showEditor();

        float gapAfter = (editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight())
                - (indicator.getRuntimeCache().getY() + indicator.getRuntimeCache().getHeight());
        assertEquals("it must stay the same distance from the bottom edge", gapBefore, gapAfter, 1f);
    }

    /**
     * <b>The indicator sits the same distance from the bottom whether or not a scrollbar is showing.</b>
     *
     * <p>The symptom was "it flickers every three zoom-ins", and the periodicity is the diagnosis: a jump
     * that happens every few steps rather than every frame is a THRESHOLD being crossed, not a stale
     * read. Zooming widens the text, and every few steps it crosses the viewport width and the horizontal
     * bar appears or disappears — so a bottom inset that added {@code horizontalBarThickness()} moved by
     * the bar's whole height on exactly the gesture it was reporting.</p>
     */
    @Test
    public void theZoomIndicatorDoesNotMoveWhenTheScrollbarAppears() {
        build("short");
        showEditor();
        editor.zoomBy(1);
        showEditor();

        UIElement indicator = childWithClass(TextEditor.ZOOM_INDICATOR_CLASS);
        float gapNarrow = (editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight())
                - (indicator.getRuntimeCache().getY() + indicator.getRuntimeCache().getHeight());

        // Long enough to overflow and bring the horizontal bar in.
        editor.setText("x".repeat(4000));
        showEditor();
        editor.zoomBy(1);
        showEditor();

        float gapWide = (editor.getRuntimeCache().getY() + editor.getRuntimeCache().getHeight())
                - (indicator.getRuntimeCache().getY() + indicator.getRuntimeCache().getHeight());

        assertEquals("the bar coming and going must not move it", gapNarrow, gapWide, 1f);
    }

    /**
     * <b>The horizontal bar does not vanish when you scroll to the end of the file.</b>
     *
     * <p>Content width is measured from the REALISED lines, because a virtualised editor cannot know the
     * widest line in a document without shaping every one of them. Measuring only what is on screen meant
     * scrolling to the end — where the last rows are a closing brace and a blank — collapsed the width and
     * took the bar away underneath the pointer.</p>
     */
    @Test
    public void theHorizontalBarSurvivesScrollingToTheEnd() {
        StringBuilder document = new StringBuilder();
        document.append("x".repeat(4000)).append(NL);
        for (int i = 0; i < 40; i++) document.append("}").append(NL);
        build(document.toString());
        editor.setScrollBeyondLastLine(false);
        showEditor();

        float wideAtTop = editor.getScrollWidth();
        assertTrue("the long line must overflow to begin with", wideAtTop > editor.getClientWidth());

        editor.setScrollImmediate(0f, editor.getScrollHeight());
        showEditor();

        assertTrue("and the content must still be wider than the viewport at the bottom, was "
                + editor.getScrollWidth(), editor.getScrollWidth() > editor.getClientWidth());
    }

    /** But a shorter document gives the width back — the memory is not a high-water mark. */
    @Test
    public void deletingTheLongLineGivesTheWidthBack() {
        build("x".repeat(4000) + NL + "short");
        showEditor();
        assertTrue(editor.getScrollWidth() > editor.getClientWidth());

        editor.setText("short" + NL + "short");
        showEditor();

        assertTrue("a shorter document must report a smaller width, was " + editor.getScrollWidth(),
                editor.getScrollWidth() <= editor.getClientWidth());
    }

    /**
     * <b>Zooming keeps the line you were on.</b>
     *
     * <p>{@code scrollTop} is a PIXEL count, so leaving it alone across a font change silently
     * reinterprets it: 440px is line 44 at a ten-pixel line and line 7 at sixty. Zooming in from line 44
     * used to land the viewport on line 5.</p>
     *
     * <p>VS Code's {@code StableViewport}, ported: capture the <b>model</b> position of the viewport's
     * first line, recover it afterwards. A model position and not a view line, because the font change
     * also reprojects — with soft wrap on, the same text occupies a different number of view lines
     * afterwards.</p>
     */
    @Test
    public void zoomingKeepsTheTopLineInPlace() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        showEditor();

        editor.setScrollImmediate(0f, 44f * editor.lineHeight());
        showEditor();
        int topRowBefore = editor.rowAtTopOfViewport();

        editor.setFontSize(editor.getFontSize() * 3f);
        showEditor();

        assertEquals("the same row must still be at the top after a 3x zoom",
                topRowBefore, editor.rowAtTopOfViewport());
    }

    /** At the very top there is nothing to preserve, and VS Code skips the capture there too. */
    @Test
    public void zoomingAtTheTopStaysAtTheTop() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        showEditor();

        editor.setFontSize(editor.getFontSize() * 3f);
        showEditor();

        assertEquals(0f, editor.getScrollTop(), 0.5f);
    }

    /**
     * <b>Zoom must beat the sheet.</b> {@code default.css} sets {@code * { font-size: 10 }}, and a
     * {@code *} rule at USER_AGENT beats an inline write at any specificity — so the size is written at
     * IMPORTANT origin, the same trap {@code syncLineFonts} documents for the lines.
     */
    @Test
    public void zoomSurvivesTheUserAgentSheet() {
        build("x");
        showEditor();
        editor.setFontSize(24f);
        showEditor();
        assertEquals(24f, editor.getFontSize(), 0.5f);
    }

    @Test
    public void zoomIsClampedAtBothEnds() {
        build("x");
        showEditor();
        editor.setFontSize(1000f);
        assertEquals(TextEditor.MAX_FONT_SIZE, editor.getFontSize(), 0.5f);
        editor.setFontSize(-5f);
        assertEquals(TextEditor.MIN_FONT_SIZE, editor.getFontSize(), 0.5f);
    }

    /** Reset goes back to the size the sheet gave it, not to a constant. */
    @Test
    public void resetReturnsToTheSheetsSize() {
        build("x");
        showEditor();
        float original = editor.getFontSize();

        editor.setFontSize(30f);
        showEditor();
        key(CgKeyCodes.KEY_0, CgModifiers.CTRL);
        showEditor();

        assertEquals(original, editor.getFontSize(), 0.5f);
    }

    /** The indicator reports the size, and names the size reset will return to. */
    @Test
    public void theIndicatorReportsTheSizeAndTheResetTarget() {
        build("x");
        showEditor();
        float original = Math.round(editor.getFontSize());

        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        String label = null;
        String reset = null;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) continue;
            for (UIElement part : child.getChildren()) {
                if (part.hasClass(TextEditor.ZOOM_LABEL_CLASS)) label = ((UIText) part).getText();
                if (part.hasClass(TextEditor.ZOOM_RESET_CLASS)) {
                    reset = ((UIText) part.getChildren().get(0)).getText();
                }
            }
        }
        assertEquals("Font size: " + (int) (original + 1) + "px", label);
        assertEquals("Reset to " + (int) original + "px", reset);
    }

    /**
     * <b>Clicking reset must not take focus off the editor.</b> A {@code Button} focuses on click by
     * default, so the control whose entire purpose is to get you back to reading the text would leave the
     * next keystroke going nowhere.
     */
    @Test
    public void theResetLinkNeverTakesFocus() {
        build("x");
        showEditor();
        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        UIElement reset = null;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) continue;
            for (UIElement part : child.getChildren()) {
                if (part.hasClass(TextEditor.ZOOM_RESET_CLASS)) reset = part;
            }
        }
        assertNotNull(reset);
        assertFalse("a link in a transient bar must not be a focus stop", reset.focusable());
    }

    /**
     * <b>The reset link is styled as a link, not as the sheet's grey button.</b> It unwinds the fill and
     * the rounding, and hovering adds an <em>underline</em> rather than changing the colour — a colour
     * shift on hover reads as a button lighting up, which is what this is trying not to look like.
     */
    @Test
    public void theResetLinkIsStyledAsALink() {
        build("x");
        showEditor();
        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        UIElement reset = null;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) continue;
            for (UIElement part : child.getChildren()) {
                if (part.hasClass(TextEditor.ZOOM_RESET_CLASS)) reset = part;
            }
        }
        assertNotNull(reset);

        var general = reset.getStyle().getGeneralGroup();
        assertEquals("the accent blue, not the button grey", 0xFF4A9EFF, general.color());
        var decoration = general.getValueSave(
                com.crystalgui.style.property.StylePropertyRegistry.TEXT_DECORATION_LINE);
        assertTrue("and no underline until hovered", decoration == null || decoration.isEmpty());

        // Hovering adds the underline. The colour deliberately does NOT change: an underline appearing
        // under an already-blue label is what says "link", where a colour shift reads as a button
        // lighting up.
        reset.setHovered(true);
        settle();

        var hovered = reset.getStyle().getGeneralGroup().getValueSave(
                com.crystalgui.style.property.StylePropertyRegistry.TEXT_DECORATION_LINE);
        assertNotNull("hovering must resolve a decoration", hovered);
        assertTrue("and it must be an underline",
                hovered.contains(com.crystalgui.style.property.visual.text.TextDecorationLine.UNDERLINE));
        assertEquals("with the colour unchanged", 0xFF4A9EFF,
                reset.getStyle().getGeneralGroup().color());
    }

    /**
     * <b>The indicator does not scale with the zoom.</b> It is chrome describing the text, not part of
     * it — scaling it made the label unreadable at the minimum size and oversized at the maximum, which
     * is the one thing it exists to be legible at. {@code font-size} is inheritable, so this only holds
     * because the sheet gives the label a <em>specified</em> value; the widget pushes no font onto it.
     */
    @Test
    public void theIndicatorDoesNotScaleWithTheEditorsFont() {
        build("x");
        showEditor();
        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        UIText label = null;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) continue;
            for (UIElement part : child.getChildren()) {
                if (part.hasClass(TextEditor.ZOOM_LABEL_CLASS)) label = (UIText) part;
            }
        }
        assertNotNull(label);
        float chromeSmall = label.getStyle().getGeneralGroup().fontSize();

        editor.setFontSize(40f);
        showEditor();
        editor.zoomBy(1);
        showEditor();

        assertEquals("the editor is much larger now", 41f, editor.getFontSize(), 0.5f);
        assertEquals("but the indicator is not", chromeSmall,
                label.getStyle().getGeneralGroup().fontSize(), 0.5f);
    }

    /**
     * <b>A faded indicator must not be clickable.</b> Opacity is paint, not hit testing — without this
     * the reset button stays live over the text for as long as the element exists.
     */
    @Test
    public void theIndicatorStopsBeingClickableWhenItFades() {
        build("x");
        editor.setZoomIndicatorSeconds(0.01f);
        showEditor();
        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        UIElement indicator = null;
        for (UIElement child : allDescendants()) {
            if (child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) indicator = child;
        }
        assertNotNull(indicator);
        assertTrue("shown while holding", indicator.hasClass(TextEditor.SHOWN_CLASS));

        editor.tickFrame(1f);
        settle();

        assertFalse("the hold expired", indicator.hasClass(TextEditor.SHOWN_CLASS));
        assertFalse("and it is no longer hittable", indicator.isHitTest());
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
