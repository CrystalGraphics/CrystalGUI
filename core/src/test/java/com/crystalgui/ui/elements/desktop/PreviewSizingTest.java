package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A preview is sized by its thumbnail, and its thumbnail is the window fitted into a maximum.</b>
 *
 * <p>Windows' model: the taskbar asks for a bitmap no larger than a maximum on each axis and the window
 * answers with its own shape scaled to fit, so BOTH dimensions vary from window to window — a tall window
 * comes back full-height and narrow, a wide one full-width and short. Nothing is letterboxed and no two
 * previews need be the same size.</p>
 *
 * <p>Every near-miss on the way here looked reasonable and produced a different visible fault: a fixed
 * box letterboxed tall windows; a fixed HEIGHT with a derived width made the panel's proportions follow
 * the window while its size did not; and a header left to size itself made the gap around the picture a
 * function of the window's NAME. Those are three different bugs with one test between them, which is why
 * this asserts the geometry rather than any one of the fixes.</p>
 */
public class PreviewSizingTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private Desktop desktop;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(false);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        input = window.getInputHandler();
        desktop = window.desktop();
        frame();
    }

    @After
    public void restoreDefault() {
        Desktop.setAnimationsEnabled(false);
    }

    private void frame() {
        window.updateWithoutPainting();
        input.beginFrame();
        input.endFrame();
    }

    private WindowFrame open(String title, float width, float height) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
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
        var box = entry.getRuntimeCache();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f), 0, 0, -1, false, 0f, -1L));
        long until = System.nanoTime() + 700L * 1_000_000L;
        while (System.nanoTime() < until) {
            frame();
        }
        assertEquals("the preview is showing a different window", target, taskbar.previewedWindow());
        for (UIElement child : taskbar.getChildren()) {
            if (child instanceof WindowPreview panel) return panel;
        }
        throw new AssertionError("the taskbar has no preview panel");
    }

    /**
     * <b>The thumbnail is the window's own shape, and it fits inside the maximum on both axes.</b>
     *
     * <p>Which is the same statement as "nothing is letterboxed": the picture is drawn fitted into this
     * box, so a box that matches the window's aspect leaves no bars.</p>
     */
    @Test
    public void aThumbnailKeepsItsWindowsShape() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowPreview panel = preview(tall);
        var thumb = panel.thumbnailBox();

        assertTrue("the thumbnail was never sized", thumb.getWidth() > 0f && thumb.getHeight() > 0f);
        assertEquals("the thumbnail is not the window's shape, so the picture is letterboxed in it",
                200f / 300f, thumb.getWidth() / thumb.getHeight(), 0.02f);
        assertTrue("a tall window should come back taller than it is wide",
                thumb.getHeight() > thumb.getWidth());
    }

    /**
     * <b>Two differently-shaped windows get differently-sized previews.</b>
     *
     * <p>The thing every version of this got wrong in a different way. A tall window and a wide one must
     * differ in BOTH dimensions — matching on either axis means something other than the window is
     * deciding that axis.</p>
     */
    @Test
    public void differentlyShapedWindowsGetDifferentlySizedPreviews() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame wide = open("A rather longer window name", 320f, 160f);

        var tallThumb = copy(preview(tall).thumbnailBox());
        var wideThumb = copy(preview(wide).thumbnailBox());

        assertTrue("both thumbnails are the same width, so the window is not deciding it",
                Math.abs(tallThumb[0] - wideThumb[0]) > 1f);
        assertTrue("both thumbnails are the same height, so the window is not deciding it",
                Math.abs(tallThumb[1] - wideThumb[1]) > 1f);
    }

    /**
     * <b>The gap around the picture is the panel's padding, and nothing else.</b>
     *
     * <p>It was a function of the window's NAME while the header was free to size the panel: generous
     * around a long title, almost none around a short one. The second window here is deliberately named
     * far longer than the first.</p>
     */
    @Test
    public void theGapAroundThePictureIsTheSameForEveryWindow() {
        WindowFrame first = open("A", 200f, 300f);
        WindowFrame second = open("A window with a very much longer title", 320f, 160f);

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
     * every window the panel showed afterwards, and the fault would only appear on the SECOND move: the
     * first still looks perfect, because it is the thing that got stuck at the right answer.</p>
     *
     * <p>Hence three hops rather than two. The panel returns to the window it started on and its picture
     * has to be the shape it was the first time.</p>
     */
    @Test
    public void movingBetweenEntriesDoesNotFreezeThePicture() {
        WindowFrame tall = open("Tall", 200f, 300f);
        WindowFrame wide = open("Wide", 320f, 160f);

        float[] first = copy(preview(tall).thumbnailBox());
        preview(wide);
        float[] again = copy(preview(tall).thumbnailBox());

        assertEquals("the picture kept the previous window's width, so sizing was never handed back",
                first[0], again[0], 1f);
        assertEquals("the picture kept the previous window's height, so sizing was never handed back",
                first[1], again[1], 1f);
    }

    /** Panel width less thumbnail width — the padding either side of the picture. */
    private static float gapOf(WindowPreview panel) {
        return panel.getRuntimeCache().getWidth() - panel.thumbnailBox().getWidth();
    }

    private static float[] copy(UIElement.RuntimeCache box) {
        return new float[] { box.getWidth(), box.getHeight() };
    }
}
