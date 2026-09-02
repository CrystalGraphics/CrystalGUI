package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.desktop.Desktop;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.service.Input;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A document with no picture is an ordinary document to the preview</b> — and the panel survives a move
 * that is abandoned.
 *
 * <p>A document restored HIDDEN at startup has never been painted, so it has no photograph, and that is
 * now how a session ordinarily opens. Three faults hid behind that one case, each reported separately
 * and each invisible while every document on the strip had a picture:</p>
 * <ul>
 *   <li>its preview was a bare header — "minimised windows have no previews";</li>
 *   <li>moving the panel onto it stalled placement for good ({@code fittedSize()} answered null, which the
 *       placement read as "not measured yet"), so the panel stayed where — and as tall as — the previous
 *       document had left it, and the hover logic behind the pending placement never ran again;</li>
 *   <li>the move had already switched the thumbnail's sizing off, and only a COMPLETED morph switched it
 *       back on, so every later preview kept whatever box it had — a wide document drawn letterboxed in a
 *       tall one's.</li>
 * </ul>
 *
 * <p><b>Animations ON</b>, deliberately: the morph and its hand-back are the mechanism under test, and
 * with animations off the continuation runs synchronously and none of this can happen. Timings are real
 * time, as {@code TaskbarPreviews} advances on {@code System.nanoTime()}.</p>
 */
public class PreviewPicturelessTest extends UiDocumentTestBase {

    /**
     * Animations OFF for the fixture. Several tests below turn them back on for the thing they are
     * about and restore this in a finally; without a @Before the class relied on that restore having
     * run, i.e. on another test having gone first. A window's state change is DEFERRED while a
     * timeline plays, so the assertions here read VISIBLE for a window that has been closed.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    private Input input;
    private Desktop desktop;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(true);
        UINode root = new UINode().layout(l -> l.width(600).height(400));
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


    private void frames(long ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < until) frame();
    }

    private WindowFrame open(String title, float width, float height) {
        // OPENED WITH ANIMATIONS OFF, then handed straight back to the gesture under test — the rule the
        // document-animation tests already follow, reached from a new direction. A taskbar entry now ramps
        // its own width open over 150ms of REAL time, and this fixture measures entry positions and hovers
        // them by coordinate, so a document opened with animations on leaves every entry a sliver for the
        // whole test. The previews being tested here are unaffected: they animate on hover, not on open.
        // @see TaskbarEntryMotion
        Desktop.setAnimationsEnabled(false);
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame(title).setIcon("crystalgui:code"));
        Desktop.setAnimationsEnabled(true);
        frame.resizeTo(width, height).moveTo(10, 10);
        frame();
        frame();
        return frame;
    }

    /** A document that has never been painted — what a session restores a minimised document as. */
    private WindowFrame openHidden(String title) {
        WindowFrame frame = open(title, 250f, 250f);
        frame.hide();
        frame();
        assertFalse("the fixture needs a document with NO photograph", frame.snapshot().isValid());
        return frame;
    }

    private Button entryOf(WindowFrame target) {
        Button entry = desktop.taskbar().entryFor(target);
        assertNotNull("no taskbar entry for " + target.getTitle(), entry);
        return entry;
    }

    private void hover(WindowFrame target) {
        var box = entryOf(target).box();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(box.worldX() + box.width() / 2f * uiScale()),
                Math.round(box.worldY() + box.height() / 2f * uiScale()), 0, 0, -1, false, 0f, -1L));
    }

    private void leave() {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(600, 200, 0, 0, -1, false, 0f, -1L));
    }

    private WindowPreview panel() {
        for (UINode child : desktop.taskbar().children()) {
            if (child instanceof WindowPreview p) return p;
        }
        throw new AssertionError("the taskbar has no preview panel");
    }

    /** The panel's centre against its entry's, both in root coordinates — what "centred over" means. */
    private float offCentreBy(WindowFrame target) {
        WindowPreview p = panel();
        var self = p.box();
        var root = document.box();
        AnchoredPlacement.Rect anchor = AnchoredPlacement.anchorRectInRoot(entryOf(target), document);
        float panelCentre = self.x() - root.x() + self.width() / 2f;
        float entryCentre = anchor.x() + anchor.width() / 2f;
        return Math.abs(panelCentre - entryCentre);
    }

    private void assertFitted(WindowPreview p, String what) {
        float[] fit = p.fittedThumbnailSize();
        assertNotNull(what + ": nothing to fit to", fit);
        var thumb = p.thumbnailBox();
        assertEquals(what + ": the picture is not the width it fits to", fit[0], thumb.width(), 0.5f);
        assertEquals(what + ": the picture is not the height it fits to", fit[1], thumb.height(), 0.5f);
    }

    @Test
    public void aWindowWithNoPictureShowsItsIconOnACardAndTheHeaderMatchesIt() {
        // A LONG title on purpose: longer than the card is wide, so it has to elide. The title is `width: 0;
        // flex-grow: 1`, and UIText's self-sizing auto-detect reads it while the panel is still
        // `display: none`, sees a zero box, and latches it SELF-sizing -- after which it pushes its text
        // width at IMPORTANT, cannot shrink, and shoves the close button out past the panel's edge. Only
        // "Crystal Editor" was long enough to show it; "Geometry" and "Welcome" fit beside the buttons.
        WindowFrame hidden = openHidden("A never-painted document with a long title");
        hover(hidden);
        frames(700);

        WindowPreview p = panel();
        assertSame(hidden, desktop.taskbar().previewedWindow());
        assertTrue("a pictureless document shows the icon placeholder, not a bare header", p.isShowingPlaceholder());
        var thumb = p.thumbnailBox();
        assertTrue("the placeholder card has a size", thumb.width() > 0f && thumb.height() > 0f);
        assertTrue("the card is landscape", thumb.width() > thumb.height());
        UINode header = deepOrNull(p, ".__preview-header__");
        assertNotNull(header);
        assertEquals("the header is matched to the card", thumb.width(), header.box().width(), 0.5f);
        assertTrue("centred over its entry", offCentreBy(hidden) < 3f);

        // THE CLOSE SHOWS ONLY WITH THE POINTER ON THE PANEL, so it has to be hovered before it can be
        // measured at all -- asserted on a hidden button, the check below is vacuously true.
        UINode close = deepOrNull(p, ".__preview-close__");
        assertNotNull(close);
        // ...and a hidden node has NO box here, which is the same answer the comment above describes.
        assertEquals("the close is hidden while only the entry is hovered", 0f, widthOf(close), 0.01f);
        var self = p.box();
        float heightAtRest = self.height();
        float pictureTopAtRest = thumb.y();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(self.worldX() + self.width() / 2f * uiScale()),
                Math.round(self.worldY() + self.height() / 2f * uiScale()), 0, 0, -1, false, 0f, -1L));
        frames(120);
        assertSame("hovering the panel must keep it up", hidden, desktop.taskbar().previewedWindow());
        var closeBox = close.box();
        var headerBox = header.box();
        assertTrue("the close did not appear when the panel was hovered", closeBox.width() > 0f);
        // AND NOTHING MOVED: the header is a fixed height, so the button arriving re-flows the title only.
        assertEquals("revealing the close changed the panel's height", heightAtRest, p.box().height(), 0.01f);
        assertEquals("revealing the close moved the picture", pictureTopAtRest, p.thumbnailBox().y(), 0.01f);
        assertTrue("the close button was pushed out of the header by a title that would not shrink",
                closeBox.x() + closeBox.width() <= headerBox.x() + headerBox.width() + 0.5f);
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
        assertSame("the panel never moved on to the pictureless document", hidden, desktop.taskbar().previewedWindow());
        assertTrue(panel().isShowingPlaceholder());
        assertTrue("the panel was left where the previous document had it", offCentreBy(hidden) < 3f);

        hover(wide);
        frames(700);
        assertSame("the hover logic stopped running after the pictureless document", wide, desktop.taskbar().previewedWindow());
        assertFalse(panel().isShowingPlaceholder());
        assertFitted(panel(), "back on a document with a picture");
        assertTrue(offCentreBy(wide) < 3f);
    }

    @Test
    public void anAbandonedMoveDoesNotFreezeThePicture() {
        WindowFrame wide = open("Wide", 320f, 160f);
        WindowFrame tall = open("Tall", 200f, 300f);

        hover(tall);
        frames(700);
        assertFitted(panel(), "the tall document");

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
        assertTrue("a wide document came back in a tall box", thumb.width() > thumb.height());
    }
}
