package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.text.Selection;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The editor's document, caret and keys — typing, movement, selection, indentation, multi-caret, line
 * operations, comments, undo and the command bindings.
 *
 * <p>What is left after {@link EditorFindTest}, {@link EditorFoldingTest} and {@link EditorViewTest} took
 * their features. The fixture is {@link EditorTestBase}.</p>
 */
public class TextEditorTest extends EditorTestBase {

    /**
     * <b>Opening the find bar must not move where a click lands.</b>
     *
     * <p>The bar floats and insets the editor by its own height, written as {@code padding-top} from a
     * ticker — so the editor's box changes a frame or more <em>after</em> the bar opens, which is what
     * made the report intermittent. {@code offsetAtLocal} reads that padding back as the text origin, so
     * the two have to agree; while they do not, a click resolves to a line it was not on.</p>
     *
     * <p>Asserted as "the click lands on the row it was aimed at" rather than on any particular number,
     * and with a second assertion that the scroll offset is <b>finite</b>: a non-finite scrollTop makes
     * {@code (int) (relativeY / lineHeight())} clamp to view line zero, which is the reported symptom —
     * a click anywhere putting the caret at the top of the document.</p>
     */
    @Test
    public void openingTheFindBarDoesNotMoveWhereAClickLands() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        for (int i = 0; i < 6; i++) settle();
        // IMMEDIATE, and only once the layout has settled. setScrollTop eases under the user-agent sheet's
        // `scroll-behavior`, and a target set before the scroll extents are known is clamped on the way
        // and then keeps travelling as they grow -- which is a moving fixture, not a bug in the widget.
        editor.setScrollImmediate(0f, 2000f);
        for (int i = 0; i < 4; i++) settle();

        float y = 60f;
        float scrollBefore = editor.getScrollTop();
        int before = editor.offsetAt(20f, y);
        assertTrue("fixture must be scrolled away from the top", before > 0);

        editor.searchBar().open();
        // SEVERAL frames: the inset is applied from a ticker, then the layout it changes has to settle.
        for (int i = 0; i < 6; i++) settle();

        assertTrue("the scroll offset went non-finite, which lands every click at the top",
                Float.isFinite(editor.getScrollTop()));
        int after = editor.offsetAt(20f, y);
        assertTrue("a click at the same point now lands at offset " + after
                + ", was " + before + "; scrollTop " + scrollBefore + " -> " + editor.getScrollTop(),
                Math.abs(after - before) < 200);
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

    /**
     * <b>One press on an auto-indented blank line, and the caret is on the line above.</b>
     *
     * <p>The end-to-end half of {@code CursorOperationsTest}'s blank-line rule, and the shape it was
     * reported in: press Enter twice inside a method and the caret sits on indentation nobody typed, so
     * walking it by tab stops costs two presses before the one that was meant. The model test pins the
     * offset; this pins that {@code KEY_BACK} reaches it and that the caret ends up where the user
     * looked for it.</p>
     */
    @Test
    public void backspaceOnABlankIndentedLineLandsOnTheLineAbove() {
        build("void f() {" + NL + NL + "        " + NL + "}");
        editor.setCaret(editor.getText().indexOf("        ") + 8);

        key(CgKeyCodes.KEY_BACK);

        assertEquals("the indent and the break go together",
                "void f() {" + NL + NL + "}", editor.getText());
        assertEquals("and the caret is on the blank line above", 1,
                editor.buffer().offsetToPoint(editor.getCaret()).row());
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

    /**
     * <b>A selection that started as a word keeps looking for that word.</b> VS Code's rule: pressing
     * Ctrl+D on `count` must not go on to select the `count` inside `counter`. A selection dragged out by
     * hand is a request about those characters instead, and matches them anywhere.
     */
    @Test
    public void addingCaretsFromAWordDoesNotMatchInsideALongerOne() {
        build("count counter count");
        editor.setCaret(0);

        assertTrue("first press selects the word", editor.addCaretAtNextOccurrence());
        assertTrue("second finds the other whole one", editor.addCaretAtNextOccurrence());
        assertEquals(2, editor.caretCount());
        for (Selection selection : editor.selections().all()) {
            assertEquals("never the one inside `counter`", "count",
                    editor.getText().substring(selection.start(), selection.end()));
            assertNotEquals(6, selection.start());
        }
    }

    /**
     * <b>Ctrl+D keeps going after it wraps.</b> It resumed from the last selection BY POSITION, so once
     * the search wrapped to the top the newest caret was there and the resume point was still at the
     * bottom — every further press found the match it had already taken and refused. Multi-caret simply
     * stopped responding, which is what "kind of broken" looked like from the outside.
     */
    @Test
    public void addingCaretsAtOccurrencesSurvivesTheWrap() {
        build("cat dog cat bird cat");
        editor.setSelection(8, 11);                     // the middle "cat"

        assertTrue("the third", editor.addCaretAtNextOccurrence());
        assertTrue("then the first, having wrapped", editor.addCaretAtNextOccurrence());
        assertEquals(3, editor.caretCount());

        assertFalse("and there is genuinely nothing left", editor.addCaretAtNextOccurrence());
        assertEquals("which is not the same as losing what it had", 3, editor.caretCount());
    }

    /**
     * <b>Undo puts the caret back where the typing happened.</b> Reported from the harness: the text came
     * back and the caret did not, so the next keystroke landed wherever it had been left.
     */
    @Test
    public void undoPutsTheCaretBackWhereTheEditWasMade() {
        build("hello world");
        editor.setCaret(5);
        editor.insertAtCaret("XYZ");
        assertEquals(8, editor.getCaret());

        editor.setCaret(0);          // wander off, as anyone would before pressing Ctrl+Z
        editor.buffer().undo();

        assertEquals("helloXYZ world".replace("XYZ", ""), editor.getText());
        assertEquals("the caret is at the edit, not where it was left", 5, editor.getCaret());
    }

    /** And redo puts it where that edit left it. */
    @Test
    public void redoPutsTheCaretWhereTheEditLeftIt() {
        build("hello world");
        editor.setCaret(5);
        editor.insertAtCaret("XYZ");
        editor.buffer().undo();
        editor.setCaret(0);

        editor.buffer().redo();

        assertEquals("helloXYZ world", editor.getText());
        assertEquals(8, editor.getCaret());
    }

    /**
     * <b>Enter between a brace pair opens a line between them and leaves the caret on it.</b> Reported
     * from the harness: it produced one line with the closing brace beside the caret, because the rule
     * asked whether the LINE ended in an opener and with the caret between the pair it ends in the closer.
     */
    @Test
    public void enterBetweenBracesLandsTheCaretOnAMiddleLine() {
        build("  if (x) {}");
        editor.setCaret(editor.getText().length() - 1);

        key(CgKeyCodes.KEY_RETURN);

        assertEquals("  if (x) {" + NL + "      " + NL + "  }", editor.getText());
        assertEquals("and the caret is on the middle line, not below the closer",
                "  if (x) {".length() + NL.length() + 6, editor.getCaret());
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

    /**
     * <b>A bracket inside a string is not punctuation.</b> The scan counted characters with no idea what
     * they were, so the {@code (} in {@code "("} counted the next real {@code )} in the file and drew a
     * pair spanning code it had nothing to do with — authoritatively, which is the part that makes it
     * worth a test rather than a shrug.
     */
    @Test
    public void aBracketInsideAStringDoesNotMatchOneOutsideIt() {
        build("String s = \"(\"; foo(a);");
        editor.setLanguage(Language.java());
        editor.setCaret(12);              // on the '(' inside the string literal
        settle();
        editor.updateWindow();
        settle();

        assertFalse("there is no partner inside the literal", lineHasHighlight(0, "bracket"));
    }

    /** And a quote is not a bracket: its two halves are the same character, so there is no depth. */
    @Test
    public void aQuoteIsNotTreatedAsABracket() {
        build("String s = \"ab\";");
        editor.setLanguage(Language.java());
        editor.setCaret(11);              // on the opening quote
        settle();
        editor.updateWindow();
        settle();

        assertFalse(lineHasHighlight(0, "bracket"));
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
        // THE ONE THAT WAS NOT. Ctrl+Space was matched inside the completion key handler, so it could
        // not be rebound and did not appear in any list — the single exception in a widget whose own
        // section header says its named actions are commands.
        assertTrue(commands.contains("editor.triggerSuggest"));
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

    /**
     * <b>Replacing the document while scrolled away must not leave the old highlights behind.</b>
     *
     * <p>{@code refreshHighlights} early-outs when the visible OFFSET RANGE is unchanged, which is not the
     * same question as "are the ranges still valid": a wholesale replace under a scrolled viewport can
     * produce an identical range over completely different text. Nothing then dirties the highlights
     * again, so every realised row keeps the previous document's ranges permanently.</p>
     *
     * <p>Found through the Run console's per-script filter — ten link ranges published and one still
     * painted, a character short, over the wrong word — but it is reachable here by reloading a file from
     * disk while scrolled away from the top.</p>
     */
    @Test
    public void replacingTheDocumentWhileScrolledRefreshesHighlights() {
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < 200; i++) first.append("int alpha").append(i).append(" = 1;").append(NL);
        build(first.toString());
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        // AWAY FROM THE TOP, which is what makes the offset range survive the replace.
        editor.setScrollTop(600f);
        settle();
        editor.updateWindow();
        settle();

        StringBuilder second = new StringBuilder();
        // TAB-INDENTED AND OF VARYING LENGTH, which is what makes the source->display mapping load-bearing:
        // a table measured from the wrong row clamps every range at a different point, which is precisely
        // how this presented -- `RunTest` where `RunTest.java:61` belonged.
        for (int i = 0; i < 200; i++) {
            second.append('	').append("double beta").append(i)
                    .append(" = 2;").append("//").append("x".repeat(i % 17)).append(NL);
        }
        editor.buffer().load(second.toString());
        settle();
        editor.updateWindow();
        settle();

        int checked = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            UIText text = (UIText) child.getChildren().get(0);
            String shown = text.getText();
            if (shown.isEmpty()) continue;
            for (com.crystalgui.ui.text.TextRange range : text.highlights().get("type")) {
                assertTrue("a range runs past the row it is on", range.end() <= shown.length());
                assertEquals("the highlight must name the new document's type, not the old one's",
                        "double", shown.substring(range.start(), range.end()));
                checked++;
            }
        }
        assertTrue("no highlights were checked, so this proves nothing", checked > 0);
    }

    /**
     * <b>A wholesale replace must reproject EVERY row, not the few a line-count delta implies.</b>
     *
     * <p>{@code reprojectAfterEdit} took the incremental path for any single {@code Change}, and derived
     * the rows it touched from the line-count delta — which assumes the edit is local. {@code
     * TextBuffer.load} is one Change spanning the whole document, so replacing 478 rows with 427 was read
     * as "52 rows at row 0 became 1 row" and everything below kept its OLD projection.</p>
     *
     * <p>Silent, because the rows still PAINT correctly — the text comes from elsewhere. What breaks is
     * anything clipped to a view line's end: {@code refreshHighlights} clamps every range to it, so the
     * Run console's stack-frame links came out truncated by however far each stale end fell short.</p>
     *
     * <p><b>This test does NOT reproduce that staleness</b>, and says so rather than implying coverage it
     * does not have: {@code settle()} drives enough passes to reproject anyway, so it passes with the old
     * arithmetic restored. The bug was diagnosed from an instrumented run instead — the same row
     * reporting {@code lineEnd=9040} unfiltered and {@code 9031} filtered. What this pins is the weaker
     * and still worthwhile property: after a wholesale replace, every published range describes the text
     * it is actually on.</p>
     */
    @Test
    public void replacingTheWholeDocumentReprojectsEveryRow() {
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < 120; i++) first.append("return ").append(i).append(NL);
        build(first.toString());
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        // LONGER rows, and fewer of them -- the shape a filter produces. A stale projection then reports
        // an end from the shorter document, which is the truncation.
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            second.append("return a much longer replacement row number ").append(i).append(NL);
        }
        editor.buffer().load(second.toString());
        settle();
        editor.updateWindow();
        settle();

        // PAST THE REPROJECTED PREFIX. The stale arithmetic reprojects a run of rows starting at the
        // edit, so rows near the top are correct however wrong the rest are -- which is exactly why this
        // was invisible until somebody scrolled back to a stack trace two hundred rows down.
        editor.setScrollTop(900f);
        settle();
        editor.updateWindow();
        settle();

        // OBSERVED THROUGH THE HIGHLIGHTS, which is where a stale projection actually shows: every range
        // is clipped to its view line's end, so one that is short truncates the range by however far.
        int checked = 0;
        for (UIElement child : allDescendants()) {
            if (!child.hasClass(TextEditor.LINE_CLASS)) continue;
            UIText text = (UIText) child.getChildren().get(0);
            String shown = text.getText();
            if (shown.isEmpty()) continue;
            for (com.crystalgui.ui.text.TextRange range : text.highlights().get("keyword")) {
                assertEquals("a range was clipped to a stale view-line end",
                        "return", shown.substring(range.start(), range.end()));
                checked++;
            }
        }
        assertTrue("no ranges were checked, so this proves nothing", checked > 0);
    }

    // -- Line endings ---------------------------------------------------------------------------

    /**
     * A file loaded with CRLF must not keep its carriage returns in the document.
     *
     * <h4>What this cost, because nothing about the symptom said "line endings"</h4>
     *
     * <p>{@code setText} replaced the buffer's contents <em>raw</em>, where the constructor and
     * {@code TextBuffer.load} both normalise -- and {@code setText} is the path every opened file
     * arrives through. So a CRLF file kept a carriage return on the end of every row.</p>
     *
     * <p>It surfaced as a <b>rendering</b> bug: the shaper treats a carriage return as a paragraph
     * break, exactly as it should, so each single-line row shaped as two paragraphs and reported double
     * height. The row box stayed one line tall and {@code .__line__} centres its text, so every line of
     * code sat half a row above its own line number while the numbers -- which never carry one -- were
     * exactly right. It was reported as "the gutter drifts, but only in JavaScript", and the only reason
     * it looked like a language was that the CRLF file happened to be a {@code .js} one.</p>
     *
     * <p>Asserted on the document rather than on geometry: the pixels are a consequence, and a test that
     * measured them would pass the moment somebody centred the text differently while the buffer stayed
     * wrong.</p>
     */
    @Test
    public void loadingACrlfFileLeavesNoCarriageReturnsInTheDocument() {
        build("");
        editor.setText("first\r\nsecond\r\nthird\r\n");
        // FOUR, not three: the text ends with a terminator, so there is an empty last line — which is
        // what every editor shows and what the buffer has to agree with.
        assertEquals(4, editor.buffer().lineCount());
        for (int row = 0; row < editor.buffer().lineCount(); row++) {
            String line = editor.buffer().line(row);
            assertFalse("row " + row + " kept its carriage return: [" + line + "]",
                    line.indexOf('\r') >= 0);
        }
        assertEquals("first", editor.buffer().line(0));
        assertEquals("third", editor.buffer().line(2));
    }

    /** And the original ending is remembered, so saving does not silently convert the file. */
    @Test
    public void theOriginalLineEndingSurvivesForTheSave() {
        build("");
        editor.setText("first\r\nsecond\r\n");
        assertEquals("first\r\nsecond\r\n", editor.buffer().textWithOriginalLineEndings());
        editor.setText("first\nsecond\n");
        assertEquals("first\nsecond\n", editor.buffer().textWithOriginalLineEndings());
    }

    /**
     * Re-reading an unchanged CRLF file is still not an edit.
     *
     * <p>The early-out compares the incoming text against what the buffer holds, and the buffer holds
     * LF -- so before the fix a CRLF file never matched and every re-read replaced the whole document,
     * resetting the caret and discarding the widest measured line. That is the flicker the early-out
     * exists to prevent, and it was defeated for exactly the files that needed it.</p>
     */
    @Test
    public void reReadingTheSameCrlfTextDoesNotDisturbTheCaret() {
        build("");
        editor.setText("first\r\nsecond\r\nthird\r\n");
        editor.setCaret(8);
        editor.setText("first\r\nsecond\r\nthird\r\n");
        assertEquals("an identical re-read moved the caret", 8, editor.getCaret());
    }
}
