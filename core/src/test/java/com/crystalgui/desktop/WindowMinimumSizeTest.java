package com.crystalgui.desktop;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.layout.MinimumSize;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A window is never resized below what its content needs</b>, as Windows asks a window in {@code WM_GETMINMAXINFO}:
 * its chrome plus its content's minimum, the sheet's floor for content that states none.
 */
public class WindowMinimumSizeTest extends UiDocumentTestBase {

    private Desktop desktop;

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        withDefaultStyles();
        desktop = Desktop.of(document);
    }

    private WindowFrame open(UIElement content) {
        WindowFrame frame = desktop.addWindow(new WindowFrame("Window"));
        frame.content().append(content);
        frame.resizeTo(400f, 300f);
        for (int i = 0; i < 4; i++) frame();
        return frame;
    }

    /** Caption and borders: what the window adds around its content, measured. */
    private static float chrome(WindowFrame frame, boolean vertical) {
        return vertical ? frame.box().height() - frame.content().box().height()
                : frame.box().width() - frame.content().box().width();
    }

    private float[] shrunk(WindowFrame frame) {
        frame.resizeTo(10f, 10f);
        for (int i = 0; i < 3; i++) frame();
        return new float[] { frame.box().width(), frame.box().height() };
    }

    @Test
    public void contentThatStatesNothingKeepsTheSheetsFloor() {
        WindowFrame frame = open(new UIElement());
        float[] size = shrunk(frame);
        assertEquals("the sheet's min-width", 120f, size[0], 0.5f);
        assertEquals("the sheet's min-height", 48f, size[1], 0.5f);
    }

    @Test
    public void aStatedMinimumHoldsTheWindowAroundIt() {
        UIElement root = new UIElement().layout(l -> l.minWidth(250f).minHeight(180f));
        WindowFrame frame = open(root);
        float chromeW = chrome(frame, false), chromeH = chrome(frame, true);
        float[] size = shrunk(frame);
        assertEquals(250f + chromeW, size[0], 0.5f);
        assertEquals(180f + chromeH, size[1], 0.5f);
        assertTrue("its content overflowed", frame.content().box().height() >= 180f - 0.5f);
    }

    @Test
    public void aColumnAddsUpItsChildrenAndGapsWithNoMinimumOfItsOwn() {
        UIElement root = new UIElement().layout(l -> l.gapRow(10f));
        root.append(new UIElement().layout(l -> l.minHeight(100f)));
        root.append(new UIElement().layout(l -> l.height(60f)));     // fixed and not shrinking: a floor too
        WindowFrame frame = open(root);
        float chromeH = chrome(frame, true);
        assertEquals(100f + 60f + 10f + chromeH, frame.minimumSize(true), 0.5f);
        assertEquals(170f + chromeH, shrunk(frame)[1], 0.5f);
    }

    /** A label keeps its line, a scrolling list between two gives up everything: a header, a tree, a hint. */
    @Test
    public void textKeepsItsLineAndTheListBetweenScrolls() {
        UIElement root = new UIElement();
        UIText header = new UIText("s0/cues");
        UIElement list = new UIElement().layout(l -> l.height(0f).flexGrow(1f).flexShrink(1f));
        StyleGroup.inlinePipeline(list.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.AUTO));
        list.append(new UIElement().layout(l -> l.minHeight(600f)));
        UIText hint = new UIText("Click a cue to play it");
        root.append(header);
        root.append(list);
        root.append(hint);
        WindowFrame frame = open(root);
        float lines = header.box().height() + hint.box().height();
        assertTrue("the labels laid out no line", lines > 1f);
        // Never under the sheet's 48px floor, which two short lines and a caption can come in under
        assertEquals(Math.max(48f, lines + chrome(frame, true)), frame.minimumSize(true), 0.5f);
        assertTrue("the list's 600px of rows held the window open", frame.minimumSize(true) < 200f);
    }

    /** A toolbar of content-sized controls states no width, yet cannot be narrower than its controls laid side by side. */
    @Test
    public void aRowOfContentSizedControlsHoldsTheWidth() {
        UIElement root = new UIElement();
        UIElement toolbar = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapColumn(8f));
        UIText play = new UIText("Play"), speed = new UIText("Speed 1.00x"), target = new UIText("Target 30m");
        toolbar.append(play);
        toolbar.append(speed);
        toolbar.append(target);
        root.append(toolbar);
        WindowFrame frame = open(root);
        float labels = play.box().width() + speed.box().width() + target.box().width();
        assertTrue("the labels laid out no width", labels > 20f);
        assertEquals(Math.max(120f, labels + 16f + chrome(frame, false)), frame.minimumSize(false), 0.5f);
        assertEquals("shrunk past them, the window stopped short of them", frame.minimumSize(false), shrunk(frame)[0], 0.5f);
    }

    /**
     * A sentence that neither wraps nor elides cannot be narrower than its line; one that elides gives its width up. (One
     * that wraps needs its longest word, which any window clears.)
     */
    @Test
    public void aLineThatCannotBreakHoldsTheWidthAndOneThatElidesDoesNot() {
        UIElement root = new UIElement();
        UIText sentence = new UIText("Not recording. Press Record to capture CrystalGraphics and CrystalGUI.");
        StyleGroup.inlinePipeline(sentence.getStyle().getGeneralGroup(), g -> g.whiteSpace(WhiteSpace.NOWRAP));
        root.append(sentence);
        WindowFrame frame = open(root);
        float line = sentence.measure(new Measurable.Constraints(Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                Measurable.Fit.MAX_CONTENT, Measurable.Fit.MAX_CONTENT)).width();
        assertTrue("the sentence laid out narrower than any window", line > 150f);
        assertTrue("a line that cannot break let the window narrow past it", frame.minimumSize(false) >= line);

        StyleGroup.inlinePipeline(sentence.getStyle().getGeneralGroup(), g -> g.textOverflow(TextOverflow.ELLIPSIS));
        for (int i = 0; i < 3; i++) frame();
        assertTrue("a line that elides held the window open", frame.minimumSize(false) < line);
    }

    /**
     * A window's content fills its width, as a page does, rather than growing to its longest line: a sentence that may
     * wrap wraps, and nothing scrolls sideways. Grown to its line, the content ran past the window behind a scrollbar.
     */
    @Test
    public void theContentFillsTheWindowRatherThanItsLongestLine() {
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f));
        root.append(new UIText("Not recording. Press Record to capture CrystalGraphics and CrystalGUI, then look."));
        WindowFrame frame = open(root);
        frame.resizeTo(200f, 300f);
        for (int i = 0; i < 4; i++) frame();
        var content = frame.content().box();
        assertEquals("the content grew to its longest line", content.contentBoxWidth(), root.box().width(), 0.5f);
        assertEquals("a window whose text can wrap scrolled sideways", 0f, content.maxScrollLeft(), 0.001f);
    }

    @Test
    public void contentThatAnswersForItselfIsAsked() {
        WindowFrame frame = open(new Board());
        assertEquals(300f + chrome(frame, false), frame.minimumSize(false), 0.5f);
    }

    @Test
    public void whatScrollsForcesNothing() {
        UIElement scroller = new UIElement().layout(l -> l.height(50f).flexShrink(1f));
        StyleGroup.inlinePipeline(scroller.getStyle().getGeneralGroup(), g -> g.overflow(Overflow.AUTO));
        scroller.append(new UIElement().layout(l -> l.minHeight(900f)));
        WindowFrame frame = open(scroller);
        assertEquals("a scroller's content scrolls, it does not hold the window open", 48f, frame.minimumSize(true), 0.5f);
    }

    @Test
    public void aRestoreStopsAtTheMinimumToo() {
        UIElement root = new UIElement().layout(l -> l.minHeight(220f));
        WindowFrame frame = open(root);
        float chromeH = chrome(frame, true);
        frame.resizeTo(400f, 100f);    // asked below: held at the minimum
        for (int i = 0; i < 3; i++) frame();
        frame.maximize();
        frame();
        frame.restore();
        for (int i = 0; i < 3; i++) frame();
        assertEquals(220f + chromeH, frame.box().height(), 0.5f);
    }

    @Test
    public void aWorkAreaSmallerThanTheMinimumWins() {
        UIElement root = new UIElement().layout(l -> l.minHeight(5000f));
        WindowFrame frame = open(root);
        float area = desktop.windowLayer().box().height();
        assertEquals("the window is clamped to the screen, its content scrolls", area, frame.box().height(), 0.5f);
    }

    /** Content whose floor is its own to say. */
    private static final class Board extends UIElement implements MinimumSize {
        @Override
        public float minimumSize(boolean vertical) {
            return vertical ? 100f : 300f;
        }
    }
}
