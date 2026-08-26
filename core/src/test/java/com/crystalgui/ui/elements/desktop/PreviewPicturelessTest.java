package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A window with no picture is an ordinary window to the preview</b> — and the panel survives a move
 * that is abandoned.
 *
 * <p>A window restored HIDDEN at startup has never been painted, so it has no photograph, and that is
 * now how a session ordinarily opens. Three faults hid behind that one case, each reported separately
 * and each invisible while every window on the strip had a picture:</p>
 * <ul>
 *   <li>its preview was a bare header — "minimised windows have no previews";</li>
 *   <li>moving the panel onto it stalled placement for good ({@code fittedSize()} answered null, which the
 *       placement read as "not measured yet"), so the panel stayed where — and as tall as — the previous
 *       window had left it, and the hover logic behind the pending placement never ran again;</li>
 *   <li>the move had already switched the thumbnail's sizing off, and only a COMPLETED morph switched it
 *       back on, so every later preview kept whatever box it had — a wide window drawn letterboxed in a
 *       tall one's.</li>
 * </ul>
 *
 * <p><b>Animations ON</b>, deliberately: the morph and its hand-back are the mechanism under test, and
 * with animations off the continuation runs synchronously and none of this can happen. Timings are real
 * time, as {@code TaskbarPreviews} advances on {@code System.nanoTime()}.</p>
 */
public class PreviewPicturelessTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private Desktop desktop;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(true);
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
        try { Thread.sleep(8); } catch (InterruptedException ignored) { }
    }

    private void frames(long ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < until) frame();
    }

    private WindowFrame open(String title, float width, float height) {
        WindowFrame frame = window.openWindow(new WindowFrame(title).setIcon("crystalgui:code"));
        frame.resizeTo(width, height).moveTo(10, 10);
        frame();
        frame();
        return frame;
    }

    /** A window that has never been painted — what a session restores a minimised window as. */
    private WindowFrame openHidden(String title) {
        WindowFrame frame = open(title, 250f, 250f);
        frame.hide();
        frame();
        assertFalse("the fixture needs a window with NO photograph", frame.snapshot().isValid());
        return frame;
    }

    private Button entryOf(WindowFrame target) {
        Button entry = desktop.taskbar().entryFor(target);
        assertNotNull("no taskbar entry for " + target.getTitle(), entry);
        return entry;
    }

    private void hover(WindowFrame target) {
        var box = entryOf(target).getRuntimeCache();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f), 0, 0, -1, false, 0f, -1L));
    }

    private void leave() {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(600, 200, 0, 0, -1, false, 0f, -1L));
    }

    private WindowPreview panel() {
        for (UIElement child : desktop.taskbar().getChildren()) {
            if (child instanceof WindowPreview p) return p;
        }
        throw new AssertionError("the taskbar has no preview panel");
    }

    /** The panel's centre against its entry's, both in root coordinates — what "centred over" means. */
    private float offCentreBy(WindowFrame target) {
        WindowPreview p = panel();
        var self = p.getRuntimeCache();
        var root = window.ui.rootElement.getRuntimeCache();
        AnchoredPlacement.Rect anchor = AnchoredPlacement.anchorRectInRoot(entryOf(target), window);
        float panelCentre = self.getX() - root.getX() + self.getWidth() / 2f;
        float entryCentre = anchor.x() + anchor.width() / 2f;
        return Math.abs(panelCentre - entryCentre);
    }

    private void assertFitted(WindowPreview p, String what) {
        float[] fit = p.fittedThumbnailSize();
        assertNotNull(what + ": nothing to fit to", fit);
        var thumb = p.thumbnailBox();
        assertEquals(what + ": the picture is not the width it fits to", fit[0], thumb.getWidth(), 0.5f);
        assertEquals(what + ": the picture is not the height it fits to", fit[1], thumb.getHeight(), 0.5f);
    }

    @Test
    public void aWindowWithNoPictureShowsItsIconOnACardAndTheHeaderMatchesIt() {
        // A LONG title on purpose: longer than the card is wide, so it has to elide. The title is `width: 0;
        // flex-grow: 1`, and UIText's self-sizing auto-detect reads it while the panel is still
        // `display: none`, sees a zero box, and latches it SELF-sizing -- after which it pushes its text
        // width at IMPORTANT, cannot shrink, and shoves the close button out past the panel's edge. Only
        // "Crystal Editor" was long enough to show it; "Geometry" and "Welcome" fit beside the buttons.
        WindowFrame hidden = openHidden("A never-painted window with a long title");
        hover(hidden);
        frames(700);

        WindowPreview p = panel();
        assertSame(hidden, desktop.taskbar().previewedWindow());
        assertTrue("a pictureless window shows the icon placeholder, not a bare header", p.isShowingPlaceholder());
        var thumb = p.thumbnailBox();
        assertTrue("the placeholder card has a size", thumb.getWidth() > 0f && thumb.getHeight() > 0f);
        assertTrue("the card is landscape", thumb.getWidth() > thumb.getHeight());
        UIElement header = p.querySelector(".__preview-header__");
        assertNotNull(header);
        assertEquals("the header is matched to the card", thumb.getWidth(), header.getRuntimeCache().getWidth(), 0.5f);
        assertTrue("centred over its entry", offCentreBy(hidden) < 3f);

        // THE CLOSE SHOWS ONLY WITH THE POINTER ON THE PANEL, so it has to be hovered before it can be
        // measured at all -- asserted on a hidden button, the check below is vacuously true.
        UIElement close = p.querySelector(".__preview-close__");
        assertNotNull(close);
        assertEquals("the close is hidden while only the entry is hovered", 0f, close.getRuntimeCache().getWidth(), 0.01f);
        var self = p.getRuntimeCache();
        float heightAtRest = self.getHeight();
        float pictureTopAtRest = thumb.getY();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((self.getX() + self.getWidth() / 2f) * 2f),
                Math.round((self.getY() + self.getHeight() / 2f) * 2f), 0, 0, -1, false, 0f, -1L));
        frames(120);
        assertSame("hovering the panel must keep it up", hidden, desktop.taskbar().previewedWindow());
        var closeBox = close.getRuntimeCache();
        var headerBox = header.getRuntimeCache();
        assertTrue("the close did not appear when the panel was hovered", closeBox.getWidth() > 0f);
        // AND NOTHING MOVED: the header is a fixed height, so the button arriving re-flows the title only.
        assertEquals("revealing the close changed the panel's height", heightAtRest, p.getRuntimeCache().getHeight(), 0.01f);
        assertEquals("revealing the close moved the picture", pictureTopAtRest, p.thumbnailBox().getY(), 0.01f);
        assertTrue("the close button was pushed out of the header by a title that would not shrink",
                closeBox.getX() + closeBox.getWidth() <= headerBox.getX() + headerBox.getWidth() + 0.5f);
    }

    @Test
    public void movingOntoAPicturelessWindowStillMovesThePanel() {
        WindowFrame wide = open("Wide", 320f, 160f);
        WindowFrame hidden = openHidden("Never painted");

        hover(wide);
        frames(700);
        assertSame(wide, desktop.taskbar().previewedWindow());

        hover(hidden);
        frames(700);
        assertSame("the panel never moved on to the pictureless window", hidden, desktop.taskbar().previewedWindow());
        assertTrue(panel().isShowingPlaceholder());
        assertTrue("the panel was left where the previous window had it", offCentreBy(hidden) < 3f);

        hover(wide);
        frames(700);
        assertSame("the hover logic stopped running after the pictureless window", wide, desktop.taskbar().previewedWindow());
        assertFalse(panel().isShowingPlaceholder());
        assertFitted(panel(), "back on a window with a picture");
        assertTrue(offCentreBy(wide) < 3f);
    }

    @Test
    public void anAbandonedMoveDoesNotFreezeThePicture() {
        WindowFrame wide = open("Wide", 320f, 160f);
        WindowFrame tall = open("Tall", 200f, 300f);

        hover(tall);
        frames(700);
        assertFitted(panel(), "the tall window");

        // Start a move and walk away before its morph is created.
        hover(wide);
        frames(40);
        leave();
        frames(600);

        hover(wide);
        frames(700);
        assertSame(wide, desktop.taskbar().previewedWindow());
        assertFitted(panel(), "after an abandoned move");
        var thumb = panel().thumbnailBox();
        assertTrue("a wide window came back in a tall box", thumb.getWidth() > thumb.getHeight());
    }
}
