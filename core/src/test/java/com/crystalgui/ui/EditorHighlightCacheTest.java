package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.Rope;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The editor must ask the tokenizer as little as possible — asserted by counting the asking.
 *
 * <h3>Why count queries rather than time them</h3>
 * <p>An absolute timing assertion is a flaky test on somebody else's machine, and this cost is not
 * subtle enough to need one: a viewport-sized tree-sitter query measured <b>3.3ms</b> on a 5,000-line
 * file — most of a 60fps frame — and was being paid on every keystroke and every scroll step. What
 * matters is <em>how many</em> queries happen and <em>how much text</em> each covers, and both are exact
 * integers. Same argument as {@code EditorFrameCostTest}'s ratio, one step further.</p>
 *
 * <h3>What broke</h3>
 * <p>{@code refreshHighlights} re-queried the whole visible range whenever anything invalidated it, and
 * rebuilt every realised line's ranges from the result. Scrolling one line re-asked about the entire
 * viewport; typing one character did the same. The per-row cache exists so that scrolling asks only
 * about newly-exposed rows, scrolling back asks nothing at all, and typing asks about the row that
 * changed.</p>
 */
public class EditorHighlightCacheTest extends UiTestBase {

    /** Stands in for a grammar: counts what it is asked, and captures every run of letters. */
    private static final class CountingTokenizer implements SyntaxTokenizer {
        int queries;
        long charsQueried;

        @Override
        public List<SyntaxToken> tokenize(Rope document, int from, int to) {
            queries++;
            charsQueried += Math.max(0, to - from);
            List<SyntaxToken> tokens = new ArrayList<>();
            String text = document.toString();
            int limit = Math.min(to, text.length());
            for (int i = Math.max(0, from); i < limit; i++) {
                if (Character.isLetter(text.charAt(i))) {
                    int start = i;
                    while (i < limit && Character.isLetter(text.charAt(i))) i++;
                    tokens.add(new SyntaxToken(start, i, "keyword"));
                }
            }
            return tokens;
        }

        void reset() {
            queries = 0;
            charsQueried = 0;
        }
    }

    private TextEditor editor;
    private UIWindow window;
    private CountingTokenizer tokenizer;

    private void build(int rows) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            document.append("    private static final int VALUE_").append(i).append(" = ").append(i).append(";\n");
        }
        editor = new TextEditor(document.toString());
        editor.layout(l -> l.width(400).height(300));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        tokenizer = new CountingTokenizer();
        editor.setTokenizer(tokenizer);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settle(20);
    }

    private void settle(int frames) {
        for (int i = 0; i < frames; i++) {
            editor.updateWindow();
            window.updateWithoutPainting();
        }
    }

    @Test
    public void anIdleFrameAsksNothing() {
        build(5_000);
        tokenizer.reset();
        settle(100);

        assertEquals("a repaint with nothing changed must not reach the tokenizer at all",
                0, tokenizer.queries);
    }

    @Test
    public void scrollingAsksOnlyAboutNewlyExposedRows() {
        build(5_000);
        tokenizer.reset();

        for (int i = 0; i < 100; i++) {
            editor.setScrollTop(editor.getScrollTop() + 12);
            settle(1);
        }

        // A viewport is ~2,000 characters, so re-querying it per step would be ~200,000. The rows actually
        // newly exposed across 100 small steps are a tiny fraction of that.
        assertTrue("scrolling asked about " + tokenizer.charsQueried
                        + " characters; that looks like a per-step viewport re-query",
                tokenizer.charsQueried < 20_000);
    }

    @Test
    public void scrollingBackOverSeenRowsAsksNothing() {
        // The clearest statement of what the cache buys: rows already tokenized stay tokenized, so
        // reversing direction is free. This is also why the cache is keyed by MODEL ROW -- a view line is
        // not stable under folding or wrapping, and a row is.
        build(5_000);
        for (int i = 0; i < 100; i++) {
            editor.setScrollTop(editor.getScrollTop() + 12);
            settle(1);
        }

        tokenizer.reset();
        for (int i = 0; i < 100; i++) {
            editor.setScrollTop(Math.max(0, editor.getScrollTop() - 12));
            settle(1);
        }

        assertEquals("scrolling back over rows already seen must not re-ask", 0, tokenizer.queries);
    }

    @Test
    public void typingAsksAboutTheEditedRowRatherThanTheViewport() {
        build(5_000);
        settle(5);
        tokenizer.reset();

        for (int i = 0; i < 100; i++) {
            editor.buffer().insert(10, "x");
            settle(1);
        }

        // One row is well under 200 characters; a viewport is ~2,000. The distinction is the whole fix.
        long perKeystroke = tokenizer.charsQueried / 100;
        assertTrue("each keystroke asked about " + perKeystroke
                        + " characters, which is viewport-sized rather than row-sized",
                perKeystroke < 300);
    }

    @Test
    public void aLineCountChangeReTokenizesRatherThanKeepingRenumberedRows() {
        // The measuredRows rule, and the reason it is not merely "drop the edited row": the cache is keyed
        // by row INDEX, so inserting a line renumbers every row below and their cached tokens would now
        // describe someone else's text. Getting this wrong puts one line's colour on another.
        //
        // Asserted through the query count, which is the observable consequence: a same-row edit asks
        // about one row, an edit that adds a line has to ask again about the rows it shifted.
        build(200);
        settle(5);

        tokenizer.reset();
        editor.buffer().insert(10, "x");                 // same row, no line-count change
        settle(2);
        long sameRowEdit = tokenizer.charsQueried;

        tokenizer.reset();
        editor.buffer().insert(0, "// inserted\n");      // adds a line: every row below renumbers
        settle(2);
        long lineAddingEdit = tokenizer.charsQueried;

        assertTrue("an edit that adds a line must re-ask about more than the one row it touched"
                        + " (same-row asked " + sameRowEdit + ", line-adding asked " + lineAddingEdit + ")",
                lineAddingEdit > sameRowEdit);
    }
}
