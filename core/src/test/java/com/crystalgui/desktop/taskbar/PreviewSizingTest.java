package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.box.Box;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.service.Input;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A preview is one height for every window, with a width that follows the window</b>, as Windows' are. A move
 * widens the cell while the picture keeps its final scale: a picture rescaled every frame of a slide shimmered.
 */
public class PreviewSizingTest extends UiDocumentTestBase {

    private Input input;
    private Desktop desktop;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(false);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        input = document.input();
        desktop = Desktop.of(document);
        frame();
    }

    @After
    public void restoreDefault() {
        Desktop.setAnimationsEnabled(false);
    }


    private WindowFrame open(String title, float width, float height) {
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame(title));
        frame.resizeTo(width, height).moveTo(10, 10);
        frame();
        frame();
        return frame;
    }

    /** Rests the pointer on an entry and waits out the hover delay, so the preview is up and settled. */
    private WindowPreview preview(WindowFrame target) {
        Taskbar taskbar = desktop.taskbar();
        Button entry = taskbar.entryFor(target);
        assertNotNull("no taskbar entry for " + target.getTitle(), entry);
        var box = entry.box();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(box.worldX() + box.width() / 2f * uiScale()),
                Math.round(box.worldY() + box.height() / 2f * uiScale()), 0, 0, -1, false, 0f, -1L));
        long until = System.nanoTime() + 700L * 1_000_000L;
        while (System.nanoTime() < until) {
            frame();
        }
        assertEquals("the preview is showing a different document", target, taskbar.previewedWindow());
        for (UIElement child : taskbar.children()) {
            if (child instanceof WindowPreview panel) return panel;
        }
        throw new AssertionError("the taskbar has no preview panel");
    }

    /**
     * One height for every window; the width is the window's at that height, between square and the sheet's
     * width. A tall window does not sit in a wide empty cell, and a very wide one is fitted to the sheet's width.
     */
    @Test
    public void everyWindowGetsOneHeightAndItsOwnWidth() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame medium = open("Medium", 300f, 200f);
        WindowFrame wide = open("A rather longer document name", 600f, 150f);

        float[] tallThumb = copy(preview(tall).thumbnailBox());
        float[] mediumThumb = copy(preview(medium).thumbnailBox());
        float[] wideThumb = copy(preview(wide).thumbnailBox());
        float[] again = copy(preview(tall).thumbnailBox());

        assertTrue("the cell was never laid out", tallThumb[0] > 0f && tallThumb[1] > 0f);
        assertEquals("the height follows the window", tallThumb[1], mediumThumb[1], 0.5f);
        assertEquals("the height follows the window", tallThumb[1], wideThumb[1], 0.5f);
        assertEquals("the width is not the window's at the cell's height", 300f / 200f,
                mediumThumb[0] / mediumThumb[1], 0.02f);
        assertEquals("a tall window's cell is narrower than square", tallThumb[1], tallThumb[0], 0.5f);
        assertTrue("a very wide window is not fitted to the sheet's width",
                wideThumb[0] > mediumThumb[0] && wideThumb[0] < 4f * wideThumb[1]);
        assertEquals("a move left the cell another size", tallThumb[0], again[0], 0.5f);
    }

    /**
     * A move widens the cell over the slide rather than at once, and the panel lands centred on its entry: the
     * placement centres on where the width is going, not where it is when the move starts.
     */
    @Test
    public void aMoveWidensTheCellAndLandsCentred() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame medium = open("Medium", 300f, 200f);
        float from = copy(preview(tall).thumbnailBox())[0];

        hover(medium);
        long hovered = System.nanoTime();
        while (System.nanoTime() < hovered + 150L * 1_000_000L) frame();
        assertEquals("the panel switched before the pointer had rested, as Windows' does not", tall,
                desktop.taskbar().previewedWindow());
        // 100ms into the move, after the 250ms switch delay
        while (System.nanoTime() < hovered + 350L * 1_000_000L) frame();
        WindowPreview panel = panelOf();
        float during = panel.thumbnailBox().width();

        WindowPreview settled = preview(medium);
        float to = settled.thumbnailBox().width();
        assertTrue("the move widened nothing", to > from + 10f);
        assertTrue("the cell took its new width at once instead of over the slide: " + from + " -> " + during
                + " -> " + to, during > from + 0.5f && during < to - 0.5f);

        var self = settled.box();
        var root = document.box();
        AnchoredPlacement.Rect anchor = AnchoredPlacement.anchorRectInRoot(desktop.taskbar().entryFor(medium), document);
        float panelCentre = self.x() - root.x() + self.width() / 2f;
        assertEquals("the panel landed off its entry's centre", anchor.x() + anchor.width() / 2f, panelCentre, 3f);
    }

    private void hover(WindowFrame target) {
        var box = desktop.taskbar().entryFor(target).box();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(box.worldX() + box.width() / 2f * uiScale()),
                Math.round(box.worldY() + box.height() / 2f * uiScale()), 0, 0, -1, false, 0f, -1L));
    }

    private WindowPreview panelOf() {
        for (UIElement child : desktop.taskbar().children()) {
            if (child instanceof WindowPreview panel) return panel;
        }
        throw new AssertionError("the taskbar has no preview panel");
    }

    /**
     * <b>The gap around the picture is the panel's padding, and nothing else.</b>
     *
     * <p>It was a function of the document's NAME while the header was free to size the panel: generous
     * around a long title, almost none around a short one. The second document here is deliberately named
     * far longer than the first.</p>
     */
    @Test
    public void theGapAroundThePictureIsTheSameForEveryWindow() {
        WindowFrame first = open("A", 200f, 300f);
        WindowFrame second = open("A document with a very much longer title", 320f, 160f);

        float firstGap = gapOf(preview(first));
        float secondGap = gapOf(preview(second));

        assertEquals("the gap around the picture follows the TITLE, not the panel's padding",
                firstGap, secondGap, 1f);
    }

    /** Panel width less thumbnail width — the padding either side of the picture. */
    private static float gapOf(WindowPreview panel) {
        return panel.box().width() - panel.thumbnailBox().width();
    }

    private static float[] copy(Box box) {
        return new float[] { box.width(), box.height() };
    }
}
