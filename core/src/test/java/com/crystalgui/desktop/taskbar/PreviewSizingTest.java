package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.box.Box;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.service.Input;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A preview is sized by its thumbnail, and its thumbnail is the document fitted into a maximum.</b>
 *
 * <p>Windows' model: the taskbar asks for a bitmap no larger than a maximum on each axis and the document
 * answers with its own shape scaled to fit, so BOTH dimensions vary from document to document — a tall document
 * comes back full-height and narrow, a wide one full-width and short. Nothing is letterboxed and no two
 * previews need be the same size.</p>
 *
 * <p>Every near-miss on the way here looked reasonable and produced a different visible fault: a fixed
 * box letterboxed tall windows; a fixed HEIGHT with a derived width made the panel's proportions follow
 * the document while its size did not; and a header left to size itself made the gap around the picture a
 * function of the document's NAME. Those are three different bugs with one test between them, which is why
 * this asserts the geometry rather than any one of the fixes.</p>
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
     * <b>The thumbnail is the document's own shape, and it fits inside the maximum on both axes.</b>
     *
     * <p>Which is the same statement as "nothing is letterboxed": the picture is drawn fitted into this
     * box, so a box that matches the document's aspect leaves no bars.</p>
     */
    @Test
    public void aThumbnailKeepsItsWindowsShape() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowPreview panel = preview(tall);
        var thumb = panel.thumbnailBox();

        assertTrue("the thumbnail was never sized", thumb.width() > 0f && thumb.height() > 0f);
        assertEquals("the thumbnail is not the document's shape, so the picture is letterboxed in it",
                200f / 300f, thumb.width() / thumb.height(), 0.02f);
        assertTrue("a tall document should come back taller than it is wide",
                thumb.height() > thumb.width());
    }

    /**
     * <b>Two differently-shaped windows get differently-sized previews.</b>
     *
     * <p>The thing every version of this got wrong in a different way. A tall document and a wide one must
     * differ in BOTH dimensions — matching on either axis means something other than the document is
     * deciding that axis.</p>
     */
    @Test
    public void differentlyShapedWindowsGetDifferentlySizedPreviews() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame wide = open("A rather longer document name", 320f, 160f);

        var tallThumb = copy(preview(tall).thumbnailBox());
        var wideThumb = copy(preview(wide).thumbnailBox());

        assertTrue("both thumbnails are the same width, so the document is not deciding it",
                Math.abs(tallThumb[0] - wideThumb[0]) > 1f);
        assertTrue("both thumbnails are the same height, so the document is not deciding it",
                Math.abs(tallThumb[1] - wideThumb[1]) > 1f);
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

    /**
     * <b>Moving the panel between entries leaves its picture free to resize again.</b>
     *
     * <p>A move MORPHS the thumbnail — it writes a size every frame — which means it also has to hold off
     * the sizing that would otherwise put the destination straight back over each frame, and then hand
     * that back. A morph that did not would leave the picture frozen at whatever it last reached, for
     * every document the panel showed afterwards, and the fault would only appear on the SECOND move: the
     * first still looks perfect, because it is the thing that got stuck at the right answer.</p>
     *
     * <p>Hence three hops rather than two. The panel returns to the document it started on and its picture
     * has to be the shape it was the first time.</p>
     */
    @Test
    public void movingBetweenEntriesDoesNotFreezeThePicture() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame wide = open("Wide", 320f, 160f);

        float[] first = copy(preview(tall).thumbnailBox());
        preview(wide);
        float[] again = copy(preview(tall).thumbnailBox());

        assertEquals("the picture kept the previous document's width, so sizing was never handed back",
                first[0], again[0], 1f);
        assertEquals("the picture kept the previous document's height, so sizing was never handed back",
                first[1], again[1], 1f);
    }

    /** Panel width less thumbnail width — the padding either side of the picture. */
    private static float gapOf(WindowPreview panel) {
        return panel.box().width() - panel.thumbnailBox().width();
    }

    private static float[] copy(Box box) {
        return new float[] { box.width(), box.height() };
    }
}
