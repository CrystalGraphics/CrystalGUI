package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.text.Selection;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * What the editor draws — the gutter, the current-line band, the scrollbars, virtualisation, soft wrap,
 * the §G decorations (indent guides, whitespace markers, rulers) and zoom.
 *
 * <p>Sits beside the view parts. Nearly every assertion here reads realised elements rather than model
 * state, which is why the readers live in {@link EditorTestBase} and not in this file.</p>
 */
public class EditorViewTest extends EditorTestBase {

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
     * <b>Typing over a SELECTION must not throw.</b>
     *
     * <h3>An edit is a range, and the shift treated it as a point</h3>
     *
     * <p>{@code shiftRowSyntax} took the edit's offset and its net delta, and asked of each token whether
     * it began before or after that one offset. For an insertion those are the same thing — nothing is
     * consumed, so the point IS the range — and every other test here types at a caret, so the difference
     * never appeared.</p>
     *
     * <p>Replacing a selection is where they part. The delta is {@code inserted - selectionLength} and
     * hugely negative, while a token that lived <em>inside</em> the selection still answers "after the
     * offset" and is shifted by it. {@code SyntaxToken}'s constructor refuses the result, so one keystroke
     * threw {@code IllegalArgumentException: bad token range -2..1} out of the buffer's change signal, on
     * the frame thread — which on a Minecraft host is a crash report titled "Rendering screen", two layers
     * away from anything about syntax.</p>
     *
     * <h3>The fixture has to put a TOKEN inside the selection, and the obvious one does not</h3>
     *
     * <p>Selecting {@code value} in {@code int value = 1;} reproduces nothing: {@link KeywordTokenizer}
     * emits keywords, types, strings and numbers, and an identifier is none of those — so the selection
     * covers no token and both branches behave identically. Measured, not assumed: that fixture passed
     * against the unfixed build.</p>
     *
     * <p>So the selection starts at column 1 and swallows the {@code int} at column 2, which is the shape
     * the crash needs — a token whose start is past the edit's offset and inside its extent. The second
     * {@code int} sits beyond the selection and must survive, which exercises the other branch: the one
     * that still has to shift, and by the right amount.</p>
     *
     * <p>Driven through {@code insertAtCaret}, which is what {@code typeCharacter} calls — it builds a
     * {@code Change(selection.start(), selection.end(), text)} per caret, so a selection makes it the
     * replacement path exactly as a keystroke does.</p>
     */
    @Test
    public void typingOverASelectionDoesNotThrow() {
        build("x int value; int z;" + NL + "int y = 2;");
        editor.setTokenizer(com.crystalgui.text.syntax.KeywordTokenizer.java());
        settle();
        editor.updateWindow();
        settle();

        // Columns 1..7 -- " int v" -- which starts BEFORE the `int` token at column 2 and ends inside the
        // identifier after it. Six characters out, one in.
        editor.setSelection(editor.buffer().pointToOffset(new TextPoint(0, 1)),
                editor.buffer().pointToOffset(new TextPoint(0, 7)));
        editor.insertAtCaret("v");

        assertEquals("the row's text must be what was typed over it",
                "xvalue; int z;", editor.buffer().line(0));

        settle();
        editor.updateWindow();
        settle();
        assertFalse("the row lost its colours entirely",
                ((UIText) linesOf().get(0).getChildren().get(0)).highlights().get("type").isEmpty());
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
            for (UIElement number : descendantsOf(child)) {
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

    /**
     * <b>An edit that changes the LINE COUNT must drop every cached row measurement.</b>
     *
     * <p>{@code measuredRows} is keyed by row index, and measuring a row is a text-shaping call, so the
     * editor invalidates one row rather than the map when an edit provably renumbers nothing — which is
     * what ordinary typing is, and what took a keystroke from 4.0 ms to 3.4 ms on a 500-line document.</p>
     *
     * <p>The guard on that shortcut is the line count. Add or remove a line and every row below is
     * renumbered, so row <i>n</i>'s cached widths now describe some other row's text — and the widths are
     * what place the caret. Dropping only the edited row then puts the caret at a position measured from a
     * line that has moved.</p>
     *
     * <p>Written because removing the guard <b>broke no existing test</b>: the optimisation shipped
     * unpinned, and the failure it allows is a caret that is silently a few pixels wrong on a row nobody
     * edited. Asserted differentially — the same final text reached by editing must place the caret
     * exactly where reaching it directly does.</p>
     */
    @Test
    public void aLineCountChangeDropsEveryCachedRowMeasurement() {
        String longRow = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        build("SHORT" + NL + longRow);
        showEditor();
        // Touch BOTH rows so both are measured and cached under indices 0 and 1.
        editor.setCaret(editor.getText().length());
        showEditor();

        // One newline at the very top renumbers every row below it: row 1 was the long line and is now
        // "SHORT". Its cache entry is the one that goes stale, so that is the row to look at -- the row
        // the edit LANDED on is dropped even by the broken shortcut, and the row past the end was never
        // cached, so neither of those can see the bug.
        editor.setCaret(0);
        editor.insertAtCaret(NL);
        showEditor();

        java.util.List<UIElement> rendered = linesOf();
        assertTrue("expected at least three rows on screen", rendered.size() >= 3);
        assertEquals("row 1 must PAINT its new text, not the measurement cached for the old row 1",
                "SHORT", ((UIText) rendered.get(1).getChildren().get(0)).getText());

        // And the widths behind it must be the new row's, since they are what place the caret.
        editor.setCaret(editor.getText().indexOf("SHORT") + "SHORT".length());
        showEditor();
        float edited = childWithClass(TextEditor.CARET_CLASS).getRuntimeCache().getX();
        String editedText = editor.getText();

        // The same final text, reached directly, with no cache that could be stale.
        build(NL + "SHORT" + NL + longRow);
        showEditor();
        editor.setCaret(editor.getText().indexOf("SHORT") + "SHORT".length());
        showEditor();
        float fresh = childWithClass(TextEditor.CARET_CLASS).getRuntimeCache().getX();

        assertEquals("the two editors must hold the same text", editedText, editor.getText());
        assertEquals("caret x after a line-count change must not come from a stale row measurement",
                fresh, edited, 0.5f);
    }

    /**
     * <b>Folding must lift EVERY caret off a hidden row, not only the primary one.</b>
     *
     * <p>A caret on a hidden row has no view line at all. It cannot be drawn where it actually is, so
     * {@code ProjectedLines.toViewPosition} walks it to the nearest visible row and the caret is painted on
     * a line it is not on — typing then inserts somewhere other than where the caret appears.</p>
     *
     * <p>{@code liftCaretsOutOfHiddenRows} is named in the plural and its javadoc says "every caret", but it
     * read {@code selections.primary()} inside the loop and returned after the first fix. With one caret
     * that is indistinguishable from correct, which is why it survived: every folding test so far has used
     * a single caret.</p>
     */
    @Test
    public void foldingLiftsEveryCaretOffAHiddenRow() {
        build("void a() {" + NL + "    one();" + NL + "    two();" + NL + "}" + NL
                + "void b() {" + NL + "    three();" + NL + "    four();" + NL + "}");
        showEditor();

        // Primary OUTSIDE any region that will close; secondary INSIDE the one being folded.
        // The one INSIDE the fold is added FIRST, so the later one is primary and the inside caret is a
        // secondary. The other way round the "secondary" is actually primary and the bug hides.
        editor.setCaret(editor.getText().indexOf("three();"));
        editor.addCaret(editor.getText().indexOf("void a()"));
        showEditor();
        assertEquals("two carets to begin with", 2, editor.caretCount());

        editor.toggleFoldAt(4);
        showEditor();

        java.util.List<com.crystalgui.text.fold.FoldingModel.RowRange> hidden =
                editor.foldingModel().hiddenRows();
        assertFalse("the fold must actually have hidden something", hidden.isEmpty());
        for (com.crystalgui.text.Selection selection : editor.selections().all()) {
            int row = editor.buffer().offsetToPoint(selection.head()).row();
            for (com.crystalgui.text.fold.FoldingModel.RowRange range : hidden) {
                assertFalse("caret left on hidden row " + row + " (hidden " + range.startRow()
                        + ".." + range.endRow() + ")", range.contains(row));
            }
        }
    }

    /**
     * <b>A selection that starts at column 0 must reach the left edge of the text area.</b>
     *
     * <p>Triple-clicking a line left an unselected strip between the gutter and the first glyph — the
     * {@code codeLeftPad} margin, which the band started <em>after</em> because it is placed from the x of
     * its first selected character and column 0's x is the first glyph, not the edge of the box.</p>
     *
     * <p>IntelliJ has no such strip: its line highlight runs from the gutter's border, and the text does
     * not touch that border because the gap lives inside the gutter instead. Ours keeps the margin (the
     * level-0 indent guide needs somewhere to be) and extends the band across it, which looks the same and
     * changes nothing about where text is drawn.</p>
     */
    @Test
    public void aSelectionFromColumnZeroReachesTheLeftEdge() {
        build("    private static final int MAX = 8;" + NL + "    private String name;" + NL);
        showEditor();
        tripleClickOn("MAX");

        UIElement band = childWithClass(TextEditor.SELECTION_CLASS);
        UIElement viewport = childWithClass(TextEditor.TEXT_VIEWPORT_CLASS);
        assertEquals("the band must start at the text area's left edge, leaving no unselected strip",
                viewport.getRuntimeCache().getX(), band.getRuntimeCache().getX(), 0.5f);
    }

    /**
     * <b>Triple-click must not select onto the line below, nor move the caret there.</b>
     *
     * <p>{@code MouseSelection.unitAt} returned {@code [lineStart, lineEnd + 1]} — VS Code's span, which
     * includes the newline and therefore <em>ends at the first offset of the next row</em>. Two things fall
     * out of that: the band loop draws a sliver on the next row, and the caret, which sits at the
     * selection's head, is painted a line below the one that was clicked.</p>
     *
     * <p>IntelliJ ends the selection at the end of the clicked line and leaves the caret on it. That is the
     * behaviour asked for here, and it is also the one that makes "triple-click then type" replace the line
     * you pointed at rather than the line break after it.</p>
     */
    @Test
    public void tripleClickStaysOnTheLineItClicked() {
        build("one();" + NL + "two();" + NL + "three();" + NL);
        showEditor();
        tripleClickOn("two");

        assertEquals("exactly one row is banded", 1,
                allWithClass(TextEditor.SELECTION_CLASS).stream()
                        .filter(b -> b.getRuntimeCache().getWidth() > 0f).count());
        assertEquals("the caret stays on the clicked row", 1,
                editor.buffer().offsetToPoint(editor.getCaret()).row());
    }

    /**
     * <b>A single edit can span MANY rows, and every one of them must be re-measured.</b>
     *
     * <p>{@code invalidateMeasuredRows} drops one row when an edit provably renumbers nothing — and its
     * guard was only "the line count did not change". That is not enough: {@code setText} replaces the
     * whole document as ONE change, so a replacement with the same number of lines passed the guard and
     * only row 0 was invalidated. Every row below kept its cached {@link RowMetrics}, which holds the
     * DISPLAY TEXT, so the editor painted the old document with a new first line.</p>
     *
     * <p>Found in the workspace harness scene: reloading a file from disk changed nothing on screen until
     * a keystroke happened to alter the line count and trigger the wholesale clear. The existing test
     * covered a line-count CHANGE and so walked straight past this.</p>
     */
    @Test
    public void anEditSpanningSeveralRowsReMeasuresAllOfThem() {
        build("alpha" + NL + "bravo" + NL + "charlie");
        showEditor();
        assertEquals(java.util.List.of("alpha", "bravo", "charlie"), renderedLines());

        // Same line count, every line different -- exactly what setText does on a reload.
        editor.setText("delta" + NL + "echo" + NL + "foxtrot");
        showEditor();

        assertEquals("every row must paint its new text",
                java.util.List.of("delta", "echo", "foxtrot"), renderedLines());
    }

    /** The same through a mid-document replacement rather than setText. */
    @Test
    public void aMultiRowReplacementReMeasuresEveryRowItTouched() {
        build("one" + NL + "two" + NL + "three" + NL + "four");
        showEditor();

        int from = editor.getText().indexOf("two");
        int to = editor.getText().indexOf("four");
        editor.setSelection(from, to);
        editor.insertAtCaret("TWO" + NL + "THREE" + NL);
        showEditor();

        assertEquals(java.util.List.of("one", "TWO", "THREE", "four"), renderedLines());
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
            for (UIElement candidate : descendantsOf(child)) {
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
            for (UIElement number : descendantsOf(child)) {
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
            for (UIElement number : descendantsOf(child)) {
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
     * <b>Exactly one block is active, and it is the caret's.</b> Ported behaviour from Monaco's
     * {@code getActiveIndentGuide}; this pins that the model actually reaches the elements.
     */
    @Test
    public void theBlockTheCaretIsInHasAnActiveGuide() {
        buildBlock();

        editor.setCaret(0);
        showEditor();
        assertEquals("at the top level nothing is inside a block", 0, activeGuideCount());

        // Into `body();`, two levels deep.
        editor.setCaret(editor.getText().indexOf("body"));
        showEditor();
        assertTrue("the enclosing block must light up", activeGuideCount() > 0);
    }

    /** Moving the caret out of the block puts the highlight out with it. */
    @Test
    public void theActiveGuideFollowsTheCaret() {
        buildBlock();
        editor.setCaret(editor.getText().indexOf("body"));
        showEditor();
        assertTrue(activeGuideCount() > 0);

        editor.setCaret(0);
        showEditor();
        assertEquals("nothing encloses the first line", 0, activeGuideCount());
    }

    /**
     * <b>A pooled guide must drop the class when it is reused.</b> The elements are recycled across rows
     * and levels, so a guide that was active a frame ago and is now describing an unrelated row would stay
     * bright — a stale highlight wandering the file as you scroll.
     */
    @Test
    public void arecycledGuideDoesNotStayActive() {
        // TWO levels deep on purpose: level 0 is the gutter's edge rather than a guide element, so a
        // singly-indented block has no guide for the highlight to land on.
        StringBuilder document = new StringBuilder("class A {" + NL + "    void f() {" + NL);
        for (int i = 0; i < 60; i++) document.append("        a();").append(NL);
        document.append("    }").append(NL).append("}").append(NL);
        for (int i = 0; i < 60; i++) document.append("b();").append(NL);
        build(document.toString());
        editor.setIndentGuidesVisible(true);
        showEditor();

        editor.setCaret(editor.getText().indexOf("a();"));
        showEditor();
        assertTrue("inside the block to begin with", activeGuideCount() > 0);

        // Scroll well past the block, into the unindented tail. Nothing there is inside anything.
        editor.setScrollImmediate(0f, 100f * editor.lineHeight());
        showEditor();

        for (UIElement guide : allWithClass(TextEditor.INDENT_GUIDE_CLASS)) {
            if (guide.getTaffyLayout().contentBoxHeight() <= 0f) continue;
            assertFalse("a recycled guide kept the active class",
                    guide.hasClass(TextEditor.ACTIVE_GUIDE_CLASS));
        }
    }

    /**
     * <b>The caret matches a bracket from either side of it.</b>
     *
     * <p>What every editor does, and what makes the feature usable: after typing an opening brace the
     * caret is <em>past</em> it, so a match that only fired when the caret sat directly on the character
     * would never fire at the moment you most want it.</p>
     */
    @Test
    public void aBracketMatchesFromEitherSideOfTheCaret() {
        build("class A {" + NL + "    x();" + NL + "}");
        showEditor();

        int brace = editor.getText().indexOf('{');

        editor.setCaret(brace);
        showEditor();
        assertTrue("standing before the brace", highlightNamesAt(brace).contains("bracket"));

        editor.setCaret(brace + 1);
        showEditor();
        assertTrue("and just after it", highlightNamesAt(brace).contains("bracket"));
    }

    /** Both halves of the pair are marked, not only the one under the caret. */
    @Test
    public void bothBracketsOfThePairAreMarked() {
        build("class A {" + NL + "    x();" + NL + "}");
        showEditor();
        editor.setCaret(editor.getText().indexOf('{') + 1);
        showEditor();

        int closing = editor.getText().lastIndexOf('}');
        assertTrue("the partner must be marked too", highlightNamesAt(closing).contains("bracket"));
    }

    /**
     * <b>The current-line band reaches across the gutter.</b>
     *
     * <p>It is two elements, not one wide one, and that is forced by the stacking: the gutter paints an
     * opaque background above the text so a long line scrolled sideways passes behind the numbers — so a
     * single band behind everything is covered in the gutter region, and one in front of everything hides
     * the numbers. A band inside the gutter sits in the gutter's own context, beneath its digits and above
     * its background, which is the only place it can be both.</p>
     */
    @Test
    public void theCurrentLineBandCoversTheGutterToo() {
        build("one" + NL + "    two" + NL + "three");
        editor.setCaret(editor.getText().indexOf("two"));
        showEditor();

        java.util.List<UIElement> bands = allWithClass(TextEditor.CURRENT_LINE_CLASS);
        int drawn = 0;
        float leftmost = Float.MAX_VALUE;
        float rightmost = 0f;
        for (UIElement band : bands) {
            if (band.getTaffyLayout().contentBoxHeight() <= 0f) continue;
            drawn++;
            leftmost = Math.min(leftmost, band.getRuntimeCache().getX());
            rightmost = Math.max(rightmost,
                    band.getRuntimeCache().getX() + band.getRuntimeCache().getWidth());
        }

        assertEquals("one band for the gutter, one for the code", 2, drawn);
        assertEquals("and together they start at the editor's own edge",
                editor.getRuntimeCache().getX(), leftmost, 1f);
        assertTrue("and reach past the gutter into the text",
                rightmost > editor.getRuntimeCache().getX() + editor.getGutterWidth());
    }

    /** Both halves go away together when there is a selection — two bands must not disagree. */
    @Test
    public void bothHalvesOfTheBandHideTogether() {
        build("one" + NL + "    two" + NL + "three");
        editor.setCaret(editor.getText().indexOf("two"));
        showEditor();
        assertEquals(2, countOf(TextEditor.CURRENT_LINE_CLASS));

        editor.setSelection(0, 5);
        showEditor();

        assertEquals("a selection replaces the band, in both halves", 0,
                countOf(TextEditor.CURRENT_LINE_CLASS));
    }

    /** And the gutter half tracks the scroll, since its parent is scroll-exempt. */
    @Test
    public void theGutterBandTracksTheScroll() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 200; i++) document.append("line ").append(i).append(NL);
        build(document.toString());
        editor.setCaret(editor.getText().indexOf("line 40"));
        showEditor();

        java.util.List<UIElement> before = allWithClass(TextEditor.CURRENT_LINE_CLASS);
        float spread = Math.abs(before.get(0).getRuntimeCache().getY()
                - before.get(1).getRuntimeCache().getY());
        assertEquals("the two halves must sit on the same row", 0f, spread, 1f);

        editor.setScrollImmediate(0f, 20f * editor.lineHeight());
        showEditor();

        java.util.List<UIElement> after = allWithClass(TextEditor.CURRENT_LINE_CLASS);
        float spreadAfter = Math.abs(after.get(0).getRuntimeCache().getY()
                - after.get(1).getRuntimeCache().getY());
        assertEquals("and still after scrolling", 0f, spreadAfter, 1f);
    }

    /**
     * <b>Scrolled text runs under the gutter's border, not into a gap before it.</b>
     *
     * <p>The clip starts at the gutter's EDGE; the text's own margin lives inside it. Putting the clip at
     * the text origin instead — where the first glyph sits — ate a margin's worth of every line scrolled
     * sideways, so glyphs vanished a few pixels short of the border rather than passing beneath it.</p>
     *
     * <p>Asserted as a relationship between the clip and the text rather than as a pixel: the margin is a
     * CSS value a theme may change, so an absolute figure would pin the sheet rather than the rule.</p>
     */
    @Test
    public void theTextClipsAtTheGutterEdgeNotAtTheTextOrigin() {
        build("x".repeat(400));
        showEditor();

        UIElement viewport = childWithClass(TextEditor.TEXT_VIEWPORT_CLASS);
        UIElement line = linesOf().get(0);

        float clipLeft = viewport.getRuntimeCache().getX();
        float gutterRight = editor.getRuntimeCache().getX()
                + editor.getTaffyLayout().border().left + editor.getTaffyLayout().padding().left
                + editor.getGutterWidth();

        assertEquals("the clip must begin where the gutter ends", gutterRight, clipLeft, 1f);
        assertTrue("and the unscrolled text must sit a margin inside it, not on it",
                line.getRuntimeCache().getX() > clipLeft);
    }

    /**
     * <b>The caret and the selection track the text sideways — exactly once.</b>
     *
     * <p>Written because the scroll-layer change broke this and nothing noticed. Everything drawn over the
     * text moved into a layer that carries {@code -scrollLeft}, and two parts kept subtracting it
     * themselves as well: the caret and the selection band drifted at <b>twice</b> the scroll, ending up
     * far from the character they belong to. The whole suite stayed green, because no test asked where
     * the caret was after scrolling sideways.</p>
     *
     * <p>Double compensation is the characteristic failure of moving an offset from N call sites into one
     * container, and it is invisible at {@code scrollLeft == 0} — which is where every other fixture
     * sits.</p>
     */
    @Test
    public void theCaretTracksTheTextWhenScrollingSideways() {
        build("x".repeat(400));
        editor.setCaret(200);
        showEditor();

        float before = drawnX(childWithClass(TextEditor.CARET_CLASS));

        editor.setScrollImmediate(30f, 0f);
        showEditor();

        assertEquals("the caret moved by exactly the scroll, not twice it",
                before - 30f, drawnX(childWithClass(TextEditor.CARET_CLASS)), 0.5f);
    }

    /**
     * <b>A pure scroll changes no element's laid-out position.</b> This is the whole scroll-layer design,
     * stated as an assertion.
     *
     * <h3>Why this is the property worth pinning</h3>
     *
     * <p>The text viewport is scroll-exempt, so its children do not get the scroll translate an ordinary
     * scroll container gives for free — and the editor used to make up for that by rewriting every row's
     * and every decoration's {@code left} and {@code top} into the cascade on every frame the view moved.
     * Cascade writes reach Taffy, so a layout pass followed. Measured against an ordinary scroller in the
     * same window with no GL, that was <b>1,628µs a scrolled frame against 367µs</b>.</p>
     *
     * <p>Now everything inside a {@code __scroll-layer__} is positioned in <b>document</b> coordinates
     * and the layer alone carries a {@link com.crystalgui.ui.UITransform}, which is layout-free by
     * construction. So a scroll that does not change WHICH rows are realised must not change where any of
     * them is laid out — and the scroll-specific cost of a frame fell to about 229µs, a 5.5x reduction.
     * This is Monaco's {@code linesContent}.</p>
     *
     * <p>Deterministic on purpose. The timing that motivated it belongs in {@code EditorFrameCostTest}
     * under {@code -Pbench} and is flaky as an assertion; <em>this</em> is the mechanism, and if a future
     * change reintroduces a per-row rewrite the numbers would merely drift while this fails outright.</p>
     */
    @Test
    public void aPureScrollMovesNothingInLayout() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) document.append("    value ").append(i).append(NL);
        build(document.toString());
        showEditor();

        float height = editor.lineHeight();
        // Sub-line, so the realised set cannot change and every line keeps its view line.
        editor.setScrollImmediate(0f, 10f * height);
        showEditor();

        java.util.List<Float> before = new java.util.ArrayList<>();
        for (UIElement line : linesOf()) before.add(line.getRuntimeCache().getY());
        assertTrue("lines must be on screen to say anything", before.size() > 3);

        editor.setScrollImmediate(0f, 10f * height + height * 0.4f);
        showEditor();

        java.util.List<Float> after = new java.util.ArrayList<>();
        for (UIElement line : linesOf()) after.add(line.getRuntimeCache().getY());
        assertEquals("the same lines are realised", before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals("line " + i + " was re-laid-out by a scroll", before.get(i), after.get(i), 0.001f);
        }
    }

    /**
     * ...and the layer is what moved instead, by exactly the scroll.
     *
     * <p>The other half of the test above: "nothing moved" is also what a completely broken editor would
     * report. Together they say the offset went somewhere, and somewhere is one transform.</p>
     */
    @Test
    public void theScrollLayerCarriesTheOffsetInstead() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) document.append("    value ").append(i).append(NL);
        build(document.toString());
        showEditor();

        float height = editor.lineHeight();
        // SUB-LINE, so the realised set is unchanged and linesOf().get(0) is the same row both times. A
        // whole-line scroll would compare one row against a different one and report nonsense.
        editor.setScrollImmediate(0f, 10f * height);
        showEditor();
        float drawnBefore = drawnY(linesOf().get(0));

        editor.setScrollImmediate(0f, 10f * height + height * 0.4f);
        showEditor();

        assertEquals("the text moved by exactly the scroll",
                drawnBefore - height * 0.4f, drawnY(linesOf().get(0)), 0.01f);
    }

    /** Scrolling sideways moves the text towards the border rather than off a nearer edge. */
    @Test
    public void scrollingSidewaysMovesTextTowardsTheGutterBorder() {
        build("x".repeat(400));
        showEditor();

        UIElement viewport = childWithClass(TextEditor.TEXT_VIEWPORT_CLASS);
        UIElement line = linesOf().get(0);
        // drawnX, not getX: a line's laid-out position is in document coordinates and the scroll layer's
        // transform is what moves it. The property under test -- that the text moves by exactly the
        // scroll -- is unchanged; which of the two carries the offset is what changed.
        float gapBefore = drawnX(line) - viewport.getRuntimeCache().getX();

        editor.setScrollImmediate(20f, 0f);
        showEditor();

        float gapAfter = drawnX(linesOf().get(0)) - viewport.getRuntimeCache().getX();
        assertEquals("the text moved by exactly the scroll", gapBefore - 20f, gapAfter, 1f);
        assertTrue("and it is now past the clip's edge, i.e. under the border", gapAfter < 0f);
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
        // A LONG HOLD, EXPIRED DELIBERATELY BELOW -- not a 10ms one raced against the frames.
        //
        // advanceFrame() takes its delta from System.nanoTime(), so settle()'s frames consume WALL CLOCK:
        // showEditor() runs six of them, and with a hold of 0.01s the indicator had to survive six real
        // frames inside ten milliseconds. That passed on the machine it was written on and failed the
        // moment anything made a frame slower, reporting "shown while holding" — which reads as the
        // indicator being broken rather than as the test timing out. The explicit tickFrame below is what
        // the second half actually needs, and it works for any hold.
        editor.setZoomIndicatorSeconds(5f);
        showEditor();
        key(CgKeyCodes.KEY_EQUALS, CgModifiers.CTRL);
        showEditor();

        UIElement indicator = null;
        for (UIElement child : allDescendants()) {
            if (child.hasClass(TextEditor.ZOOM_INDICATOR_CLASS)) indicator = child;
        }
        assertNotNull(indicator);
        assertTrue("shown while holding", indicator.hasClass(TextEditor.SHOWN_CLASS));

        // Past the hold in one explicit step, so what expires it is this line rather than how long the
        // frames above happened to take.
        editor.tickFrame(10f);
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
